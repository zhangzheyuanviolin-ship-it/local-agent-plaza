/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.Skill
import org.json.JSONObject

private const val TOOL_CALL_OPEN_TAG = "<tool_call>"
private const val TOOL_CALL_CLOSE_TAG = "</tool_call>"
const val MAX_COMPAT_TOOL_STEPS = 8

object AgentToolModeValues {
  const val AUTO = "自动"
  const val NATIVE = "原生"
  const val COMPAT = "兼容"
  val options = listOf(AUTO, NATIVE, COMPAT)
}

enum class ResolvedAgentToolMode {
  NATIVE,
  COMPAT,
}

data class ParsedCompatToolCall(
  val toolName: String,
  val arguments: JSONObject,
)

data class AgentSessionConfig(
  val resolvedMode: ResolvedAgentToolMode,
  val systemInstruction: com.google.ai.edge.litertlm.Contents,
  val enableConversationConstrainedDecoding: Boolean,
  val useNativeTools: Boolean,
)

object AgentConfigKeys {
  val TOOL_MODE = ConfigKey("agent_tool_mode", "智能体工具模式")
}

fun getConfiguredAgentToolMode(model: Model): String {
  return model.getStringConfigValue(
    key = AgentConfigKeys.TOOL_MODE,
    defaultValue = AgentToolModeValues.AUTO,
  )
}

fun supportsNativeAgentTools(model: Model): Boolean {
  if (!model.imported) {
    return true
  }
  val normalizedName = model.name.lowercase()
  return normalizedName.contains("functiongemma") ||
    normalizedName.contains("function-gemma") ||
    normalizedName.contains("function_gemma") ||
    normalizedName.contains("tiny_garden")
}

fun resolveAgentToolMode(model: Model): ResolvedAgentToolMode {
  return when (getConfiguredAgentToolMode(model)) {
    AgentToolModeValues.NATIVE -> ResolvedAgentToolMode.NATIVE
    AgentToolModeValues.COMPAT -> ResolvedAgentToolMode.COMPAT
    else -> {
      if (supportsNativeAgentTools(model)) {
        ResolvedAgentToolMode.NATIVE
      } else {
        ResolvedAgentToolMode.COMPAT
      }
    }
  }
}

internal fun shouldEnableNativeAgentConstrainedDecoding(model: Model): Boolean {
  return resolveAgentToolMode(model) == ResolvedAgentToolMode.NATIVE && !model.imported
}

fun createAgentSessionConfig(
  model: Model,
  baseSystemPrompt: String,
  skillManagerViewModel: SkillManagerViewModel,
): AgentSessionConfig {
  val resolvedMode = resolveAgentToolMode(model)
  return when (resolvedMode) {
    ResolvedAgentToolMode.NATIVE ->
      AgentSessionConfig(
        resolvedMode = resolvedMode,
        systemInstruction = skillManagerViewModel.injectSkills(baseSystemPrompt),
        enableConversationConstrainedDecoding = shouldEnableNativeAgentConstrainedDecoding(model),
        useNativeTools = true,
      )
    ResolvedAgentToolMode.COMPAT ->
      AgentSessionConfig(
        resolvedMode = resolvedMode,
        systemInstruction =
          com.google.ai.edge.litertlm.Contents.of(
            buildCompatAgentSystemPrompt(
              baseSystemPrompt = baseSystemPrompt,
              selectedSkills = skillManagerViewModel.getSelectedSkills(),
            )
          ),
        enableConversationConstrainedDecoding = false,
        useNativeTools = false,
      )
  }
}

fun buildCompatToolResultPrompt(toolName: String, result: Map<String, Any?>): String {
  val resultJson = JSONObject()
  result.forEach { (key, value) -> resultJson.put(key, JSONObject.wrap(value)) }
  return """
TOOL_RESULT
tool: $toolName
payload:
$resultJson

You are in compatibility tool mode.
Use this tool result to continue the task.
If another tool is required, reply with exactly one $TOOL_CALL_OPEN_TAG...$TOOL_CALL_CLOSE_TAG block and nothing else.
Otherwise, answer the user directly in natural language.
"""
    .trimIndent()
}

fun summarizeCompatToolResult(result: Map<String, Any?>): String {
  val status = result["status"]?.toString().orEmpty()
  val summary =
    listOf("summary", "result", "error", "recovery_hint")
      .asSequence()
      .mapNotNull { key -> result[key]?.toString()?.takeIf { it.isNotBlank() } }
      .firstOrNull()
      .orEmpty()
  return buildString {
    if (status.isNotBlank()) {
      append("status=")
      append(status)
    }
    if (summary.isNotBlank()) {
      if (isNotEmpty()) {
        append(" | ")
      }
      append(summary.take(500))
    }
  }
}

fun parseCompatToolCall(rawText: String): ParsedCompatToolCall? {
  val startIndex = rawText.indexOf(TOOL_CALL_OPEN_TAG)
  val endIndex = rawText.indexOf(TOOL_CALL_CLOSE_TAG)
  if (startIndex < 0 || endIndex <= startIndex) {
    return null
  }
  val payload =
    rawText
      .substring(startIndex + TOOL_CALL_OPEN_TAG.length, endIndex)
      .trim()
      .removePrefix("```json")
      .removePrefix("```")
      .removeSuffix("```")
      .trim()
  val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
  val toolName =
    json.optString("tool").ifBlank {
      json.optString("name")
    }
  if (toolName.isBlank()) {
    return null
  }
  val arguments = json.optJSONObject("arguments") ?: JSONObject()
  return ParsedCompatToolCall(toolName = toolName.trim(), arguments = arguments)
}

private fun buildCompatAgentSystemPrompt(baseSystemPrompt: String, selectedSkills: List<Skill>): String {
  val selectedSkillsList =
    selectedSkills.joinToString(separator = "\n") { "- ${it.name}: ${it.description}" }
      .ifBlank { "- 暂无已启用技能。" }
  val basePromptWithSkills = injectSelectedSkills(baseSystemPrompt = baseSystemPrompt, selectedSkills = selectedSkills)
  return """
$basePromptWithSkills

You are currently running in compatibility tool mode because this model is not using Google native tool calling for this session.

When you need to use a tool, reply with exactly one tool call block and nothing else:
$TOOL_CALL_OPEN_TAG
{"tool":"load_skill","arguments":{"skill_name":"tavily-search"}}
$TOOL_CALL_CLOSE_TAG

Compatibility mode rules:
- Never mix natural language with a tool call block.
- Only request one tool per assistant turn.
- After a tool result is returned, either request the next tool in the same format or answer the user directly.
- Prefer calling load_skill first when a relevant skill exists.
- Only load or use skills that are enabled in this session.

Available compatibility tools:
- load_skill arguments: {"skill_name":"..."} . Loads the selected skill instructions.
- run_js arguments: {"skill_name":"...","script_name":"index.html","data":"..."} . Runs a JS skill script.
- run_intent arguments: {"intent":"...","parameters":"{...}"} . Runs an Android intent tool.
- run_configured_intent arguments: {"skill_name":"...","intent":"...","parameters":"{...}"} . Runs an intent with saved skill configuration.
- write_workspace_text_file arguments: {"path":"notes/output.md","content":"..."} . Writes the full final text into the workspace.

Enabled skills for this session:
$selectedSkillsList
"""
    .trimIndent()
}

private fun injectSelectedSkills(baseSystemPrompt: String, selectedSkills: List<Skill>): String {
  val selectedSkillsNamesAndDescriptions =
    selectedSkills.joinToString(separator = "\n") { "- ${it.name}: ${it.description}" }
      .ifBlank { "- 暂无已启用技能。" }
  return if (baseSystemPrompt.contains("___SKILLS___")) {
    baseSystemPrompt.replace("___SKILLS___", selectedSkillsNamesAndDescriptions)
  } else {
    buildString {
      append(baseSystemPrompt)
      if (baseSystemPrompt.isNotBlank()) {
        append("\n\n")
      }
      append("可用技能:\n")
      append(selectedSkillsNamesAndDescriptions)
    }
  }
}
