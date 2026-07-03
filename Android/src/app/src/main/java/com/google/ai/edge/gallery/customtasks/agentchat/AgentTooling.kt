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
private const val THINK_OPEN_TAG = "<think>"
private const val THINK_CLOSE_TAG = "</think>"
const val MAX_COMPAT_TOOL_STEPS = 8
private const val MAX_COMPAT_MODEL_TOOL_RESULT_CHARS = 6000

object AgentToolModeValues {
  const val NATIVE = "原生"
  const val COMPAT = "兼容"
  val options = listOf(NATIVE, COMPAT)
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
  val systemInstruction: com.google.ai.edge.litertlm.Contents?,
  val enableConversationConstrainedDecoding: Boolean,
  val useNativeTools: Boolean,
  val compatInstructionPayload: String?,
)

object AgentConfigKeys {
  val TOOL_MODE = ConfigKey("agent_tool_mode", "智能体工具模式")
}

fun getConfiguredAgentToolMode(model: Model): String {
  return model.getStringConfigValue(
    key = AgentConfigKeys.TOOL_MODE,
    defaultValue = defaultAgentToolMode(model),
  )
}

fun supportsNativeAgentTools(model: Model): Boolean {
  val normalizedName = model.name.lowercase()
  return normalizedName.contains("gemma-4") ||
    normalizedName.contains("functiongemma") ||
    normalizedName.contains("function-gemma") ||
    normalizedName.contains("function_gemma") ||
    normalizedName.contains("tiny_garden")
}

fun defaultAgentToolMode(model: Model): String {
  return if (supportsNativeAgentTools(model)) AgentToolModeValues.NATIVE else AgentToolModeValues.COMPAT
}

fun resolveAgentToolMode(model: Model): ResolvedAgentToolMode {
  return when (getConfiguredAgentToolMode(model)) {
    AgentToolModeValues.NATIVE -> ResolvedAgentToolMode.NATIVE
    AgentToolModeValues.COMPAT -> ResolvedAgentToolMode.COMPAT
    else -> if (supportsNativeAgentTools(model)) ResolvedAgentToolMode.NATIVE else ResolvedAgentToolMode.COMPAT
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
        compatInstructionPayload = null,
      )
    ResolvedAgentToolMode.COMPAT ->
      AgentSessionConfig(
        resolvedMode = resolvedMode,
        systemInstruction = null,
        enableConversationConstrainedDecoding = false,
        useNativeTools = false,
        compatInstructionPayload =
          buildCompatAgentInstructionPayload(
            baseSystemPrompt = baseSystemPrompt,
            selectedSkills = skillManagerViewModel.getSelectedSkills(),
          ),
      )
  }
}

fun buildCompatUserInput(
  userInput: String,
  compatInstructionPayload: String,
): String {
  return """
COMPAT_AGENT_INSTRUCTIONS
$compatInstructionPayload

USER_REQUEST
$userInput
"""
    .trimIndent()
}

fun buildCompatContinuationInput(
  continuationPayload: String,
  compatInstructionPayload: String,
): String {
  return """
COMPAT_AGENT_INSTRUCTIONS
$compatInstructionPayload

$continuationPayload
"""
    .trimIndent()
}

fun buildCompatToolResultPrompt(
  toolName: String,
  result: Map<String, Any?>,
  originalUserRequest: String = "",
): String {
  val compactPayload = compactCompatToolResultForModel(result)
  return """
TOOL_RESULT
original_user_request: $originalUserRequest
tool: $toolName
payload:
$compactPayload

You are in compatibility tool mode.
Use this tool result to answer the original user request directly in Chinese. Do not output $THINK_OPEN_TAG, $THINK_CLOSE_TAG, hidden reasoning, analysis text, scratchpad text, raw JSON, or another tool call unless the result explicitly says the tool failed.
Keep the answer concise and stop after the answer is complete.
"""
    .trimIndent()
}

fun compactCompatToolResultForModel(result: Map<String, Any?>): String {
  val status = result["status"]?.toString().orEmpty()
  val resultText =
    listOf("result", "summary", "content", "text", "output")
      .asSequence()
      .mapNotNull { key -> result[key]?.toString()?.takeIf { it.isNotBlank() } }
      .firstOrNull()
      .orEmpty()
  val error = result["error"]?.toString().orEmpty()
  val recoveryHint = result["recovery_hint"]?.toString().orEmpty()
  val normalizedResult =
    resultText
      .replace("\\n", "\n")
      .replace("\\/", "/")
      .lines()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .joinToString("\n")
      .take(MAX_COMPAT_MODEL_TOOL_RESULT_CHARS)
  return buildString {
    if (status.isNotBlank()) {
      append("status: ")
      append(status)
      append('\n')
    }
    if (normalizedResult.isNotBlank()) {
      append("result:\n")
      append(normalizedResult)
      append('\n')
    }
    if (error.isNotBlank()) {
      append("error: ")
      append(error.take(1000))
      append('\n')
    }
    if (recoveryHint.isNotBlank()) {
      append("recovery_hint: ")
      append(recoveryHint.take(1000))
      append('\n')
    }
  }.trim()
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
  val balancedPayload = extractFirstJsonObject(payload) ?: return null
  val json = runCatching { JSONObject(balancedPayload) }.getOrNull() ?: return null
  val toolName =
    json.optString("tool").ifBlank {
      json.optString("name")
    }
  if (toolName.isBlank()) {
    return null
  }
  val arguments = json.optJSONObject("arguments") ?: json.optJSONObject("parameters") ?: JSONObject()
  return ParsedCompatToolCall(toolName = toolName.trim(), arguments = arguments)
}

fun stripCompatThinkingText(rawText: String): String {
  var text = rawText
  while (true) {
    val start = text.indexOf(THINK_OPEN_TAG, ignoreCase = true)
    if (start < 0) {
      return text.trim()
    }
    val end = text.indexOf(THINK_CLOSE_TAG, startIndex = start, ignoreCase = true)
    text =
      if (end >= start) {
        text.removeRange(start, end + THINK_CLOSE_TAG.length)
      } else {
        text.substring(0, start)
      }
  }
}

private fun extractFirstJsonObject(text: String): String? {
  val start = text.indexOf('{')
  if (start < 0) {
    return null
  }
  var depth = 0
  var inString = false
  var escaping = false
  for (index in start until text.length) {
    val char = text[index]
    if (escaping) {
      escaping = false
      continue
    }
    if (char == '\\' && inString) {
      escaping = true
      continue
    }
    if (char == '"') {
      inString = !inString
      continue
    }
    if (inString) {
      continue
    }
    when (char) {
      '{' -> depth++
      '}' -> {
        depth--
        if (depth == 0) {
          return text.substring(start, index + 1)
        }
      }
    }
  }
  return null
}

internal fun buildCompatAgentInstructionPayload(
  baseSystemPrompt: String,
  selectedSkills: List<Skill>,
): String {
  val selectedSkillsList =
    selectedSkills.joinToString(separator = "\n") { "- ${it.name}: ${it.description}" }
      .ifBlank { "- 暂无已启用技能。" }
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillsList,
  )
}

internal fun buildCompatAgentInstructionPayloadForTest(
  baseSystemPrompt: String,
  selectedSkillSummaries: List<String>,
): String {
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillSummaries.joinToString("\n") { "- $it" },
  )
}

private fun buildCompatAgentInstructionPayloadFromSummary(
  selectedSkillsList: String,
): String {
  return """
You are running in Qwen-compatible tool mode. Reply in the user's language unless the user asks otherwise.
Thinking is disabled for tool mode. Do not output $THINK_OPEN_TAG, $THINK_CLOSE_TAG, hidden reasoning, analysis text, or scratchpad text.

When you need a tool, reply with exactly one tool call block and nothing else. The block must start with $TOOL_CALL_OPEN_TAG and end with $TOOL_CALL_CLOSE_TAG. Inside the block, output one JSON object with a tool name and an arguments object. Put the user's actual request keywords in the query field.

Compatibility mode rules:
- Never mix natural language with a tool call block.
- Only request one tool per assistant turn.
- After a tool result is returned, either request the next tool in the same format or answer the user directly.
- Only load or use skills that are enabled in this session.
- Use "arguments" or "parameters" as a JSON object. Both are accepted.
- Do not call load_skill unless the user explicitly asks to inspect a skill. Prefer direct compatibility tools.
- If exactly one search tool is enabled, use that search tool. If multiple search tools are enabled and the user does not name one, prefer langsearch-search for Chinese queries and exa-search for English queries.
- After search results are returned, summarize the sources directly. Do not repeat raw JSON, dates, URL fragments, or punctuation noise.
- Stop after a concise final answer. Do not continue with repeated phrases, punctuation, or filler.

Available compatibility tools:
- exa-search arguments: {"query":"..."} . Searches the web with Exa when exa-search is enabled.
- tavily-search arguments: {"query":"..."} . Searches the web with Tavily when tavily-search is enabled.
- langsearch-search arguments: {"query":"..."} . Searches the web with LangSearch when langsearch-search is enabled.
- search_web arguments: {"query":"...","skill_name":"exa-search"} . Searches the web using an enabled search skill.
- list_workspace arguments: {"path":"."} . Lists files in the mounted workspace.
- write_workspace_text_file arguments: {"path":"notes/output.md","content":"..."} . Writes the full final text into the workspace.

Enabled skills for this session:
$selectedSkillsList
"""
    .trimIndent()
}
