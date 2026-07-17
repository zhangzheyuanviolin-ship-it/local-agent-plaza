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
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.Skill
import org.json.JSONObject

private const val TOOL_CALL_OPEN_TAG = "<tool_call>"
private const val TOOL_CALL_CLOSE_TAG = "</tool_call>"
private const val THINK_OPEN_TAG = "<think>"
private const val THINK_CLOSE_TAG = "</think>"
const val MAX_COMPAT_TOOL_STEPS = 8
private const val DEFAULT_COMPAT_MODEL_TOOL_RESULT_CHARS = 6000
private const val MIN_COMPAT_MODEL_TOOL_RESULT_CHARS = 1200
private const val MAX_COMPAT_MODEL_TOOL_RESULT_CHARS = 12000
private const val COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS = 1400

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
  if (normalizedName.contains("12b")) {
    return false
  }
  return normalizedName.contains("gemma-4-e2b") ||
    normalizedName.contains("gemma-4-e4b") ||
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
    AgentToolModeValues.NATIVE ->
      if (supportsNativeAgentTools(model)) {
        ResolvedAgentToolMode.NATIVE
      } else {
        ResolvedAgentToolMode.COMPAT
      }
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
  model: Model? = null,
): String {
  val maxResultChars = resolveCompatToolResultCharBudget(model = model)
  val compactPayload =
    buildCompatToolResultPayload(
      toolName = toolName,
      result = result,
      originalUserRequest = originalUserRequest,
      maxResultChars = maxResultChars,
    )
  return """
TOOL_RESULT
original_user_request: ${compactPayload["original_user_request"]}
tool: ${compactPayload["tool"]}
status: ${compactPayload["status"]}
payload:
${compactPayload["result"]}

You are in compatibility tool mode.
Use this tool result to answer the original user request directly in Chinese. Do not output $THINK_OPEN_TAG, $THINK_CLOSE_TAG, hidden reasoning, analysis text, scratchpad text, raw JSON, or another tool call unless the result explicitly says the tool failed.
If the tool result contains XLSX row facts, treat each "行事实" line as authoritative. Preserve the exact metric name, unit, year, and value from the same line. Do not say a unit is missing when the value already contains text such as 亿美元, 亿, 万张, %, CAGR, or 人.
If the payload says it was truncated for context safety, answer only from the visible payload and tell the user that the full exact tool output is available in the saved audit file.
Keep the answer concise and stop after the answer is complete.
"""
    .trimIndent()
}

fun buildCompatToolResultPayload(
  toolName: String,
  result: Map<String, Any?>,
  originalUserRequest: String = "",
  maxResultChars: Int = DEFAULT_COMPAT_MODEL_TOOL_RESULT_CHARS,
): Map<String, Any?> {
  return mapOf(
    "original_user_request" to originalUserRequest,
    "tool" to toolName,
    "status" to (result["status"]?.toString().orEmpty().ifBlank { "succeeded" }),
    "result" to compactCompatToolResultForModel(result = result, maxResultChars = maxResultChars),
    "instruction" to
      "请基于这个工具结果，用中文直接回答原始用户请求；不要输出思考过程、工具调用、原始JSON或无关重复内容。",
  )
}

fun compactCompatToolResultForModel(
  result: Map<String, Any?>,
  maxResultChars: Int = DEFAULT_COMPAT_MODEL_TOOL_RESULT_CHARS,
): String {
  val status = result["status"]?.toString().orEmpty()
  val resultText =
    listOf("result", "content", "text", "output", "summary")
      .asSequence()
      .mapNotNull { key -> result[key]?.toString()?.takeIf { it.isNotBlank() } }
      .firstOrNull()
      .orEmpty()
  val error = result["error"]?.toString().orEmpty()
  val recoveryHint = result["recovery_hint"]?.toString().orEmpty()
  val normalizedResult = normalizeCompatToolResultText(resultText)
  val prioritizedResult = prioritizeCompatToolResultForModel(normalizedResult)
  val safeMaxResultChars =
    maxResultChars.coerceIn(
      MIN_COMPAT_MODEL_TOOL_RESULT_CHARS,
      MAX_COMPAT_MODEL_TOOL_RESULT_CHARS,
    )
  val truncated = prioritizedResult.length > safeMaxResultChars
  val modelResult = prioritizedResult.take(safeMaxResultChars)
  return buildString {
    if (status.isNotBlank()) {
      append("status: ")
      append(status)
      append('\n')
    }
    if (modelResult.isNotBlank()) {
      append("result:\n")
      append(modelResult)
      append('\n')
    }
    if (truncated) {
      append("context_safety_note: Tool output was truncated before being sent to the model. ")
      append("The saved audit JSON contains the complete exact tool output.\n")
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

fun resolveCompatToolResultCharBudget(model: Model?): Int {
  if (model == null) {
    return DEFAULT_COMPAT_MODEL_TOOL_RESULT_CHARS
  }
  val contextWindow = model.getConfiguredContextWindow().takeIf { it > 0 } ?: model.llmMaxContextLength ?: 0
  if (contextWindow <= 0) {
    return DEFAULT_COMPAT_MODEL_TOOL_RESULT_CHARS
  }
  val reservedOutputTokens =
    model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = model.llmMaxToken)
      .coerceAtLeast(512)
  val availableToolTokens =
    (contextWindow - reservedOutputTokens - COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS)
      .coerceAtLeast(900)
  return (availableToolTokens * 1.35f).toInt()
    .coerceIn(MIN_COMPAT_MODEL_TOOL_RESULT_CHARS, MAX_COMPAT_MODEL_TOOL_RESULT_CHARS)
}

private fun normalizeCompatToolResultText(text: String): String {
  return text
    .replace("\\n", "\n")
    .replace("\\/", "/")
    .lines()
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .joinToString("\n")
}

private fun prioritizeCompatToolResultForModel(text: String): String {
  if (!text.contains("行事实") && !text.contains("xlsx", ignoreCase = true)) {
    return text
  }
  val lines = text.lines()
  val headerLines =
    lines.filter { line ->
      line.startsWith("Read ") ||
        line.contains("工作表") ||
        line.contains("表格") ||
        line.contains("xlsx", ignoreCase = true)
    }
  val factLines = lines.filter { it.contains("行事实") }
  if (factLines.isEmpty()) {
    return text
  }
  val otherUsefulLines =
    lines.filter { line ->
      !line.contains("行事实") &&
        (
          line.contains("列") ||
            line.contains("字段") ||
            line.contains("单位") ||
            line.contains("年份") ||
            line.contains("指标")
        )
    }
  return (headerLines + otherUsefulLines + factLines)
    .distinct()
    .joinToString("\n")
    .ifBlank { text }
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
  if (startIndex < 0) {
    return null
  }
  val closingTagIndex = rawText.indexOf(TOOL_CALL_CLOSE_TAG, startIndex = startIndex)
  val endIndex = if (closingTagIndex > startIndex) closingTagIndex else rawText.length
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
  val namedRootToolCall = extractNamedRootToolCall(json)
  val toolName =
    json.optString("tool").ifBlank {
      json.optString("name").ifBlank { json.optString("tool_name").ifBlank { namedRootToolCall?.first.orEmpty() } }
    }
  val arguments =
    json.optJSONObject("arguments") ?:
      json.optJSONObject("parameters") ?:
      namedRootToolCall?.second ?:
      buildCompatArgumentsFromRoot(json)
  val resolvedToolName =
    toolName.ifBlank {
      when {
        getOptionalStringFromJson(arguments, listOf("query", "search_query", "searchQuery", "q", "input", "topic"))
          .isNotBlank() -> "search_web"
        arguments.has("path") && !arguments.has("content") -> "list_workspace"
        arguments.has("path") && arguments.has("content") -> "write_workspace_text_file"
        else -> ""
      }
    }
  if (resolvedToolName.isBlank()) {
    return null
  }
  return normalizeCompatToolCall(toolName = resolvedToolName.trim(), arguments = arguments)
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

private fun buildCompatArgumentsFromRoot(json: JSONObject): JSONObject {
  return JSONObject().also { arguments ->
    json.keys().forEach { key ->
      if (key !in setOf("tool", "name", "tool_name", "function", "function_name")) {
        arguments.put(key, json.get(key))
      }
    }
  }
}

private fun normalizeCompatToolCall(toolName: String, arguments: JSONObject): ParsedCompatToolCall? {
  val trimmedToolName = toolName.trim()
  val lowerToolName = trimmedToolName.lowercase()
  if (lowerToolName == "tool_call" || lowerToolName == "call_tool" || lowerToolName == "function_call") {
    val rootToolCall = extractNamedRootToolCall(arguments)
    val nestedToolName =
      getOptionalStringFromJson(
          arguments,
          listOf("tool", "name", "tool_name", "function", "function_name"),
        )
        .ifBlank { rootToolCall?.first.orEmpty() }
    val nestedArguments =
      getOptionalJsonObjectFromJson(arguments, listOf("arguments", "parameters", "input")) ?:
        rootToolCall?.second ?:
        buildCompatArgumentsFromRoot(arguments)
    if (nestedToolName.isNotBlank()) {
      return normalizeCompatToolCall(toolName = nestedToolName, arguments = nestedArguments)
    }
  }
  return ParsedCompatToolCall(toolName = trimmedToolName, arguments = arguments)
}

private fun extractNamedRootToolCall(json: JSONObject): Pair<String, JSONObject>? {
  val keys = mutableListOf<String>()
  val iterator = json.keys()
  while (iterator.hasNext()) {
    keys.add(iterator.next())
  }
  if (keys.size != 1) {
    return null
  }
  val key = keys.first()
  val value = json.optJSONObject(key) ?: return null
  val normalizedKey = key.trim()
  if (normalizedKey.isBlank()) {
    return null
  }
  return normalizedKey to value
}

private fun getOptionalStringFromJson(json: JSONObject, names: List<String>): String {
  for (name in names) {
    val value = json.optString(name)
    if (value.isNotBlank()) {
      return value
    }
  }
  return ""
}

private fun getOptionalJsonObjectFromJson(json: JSONObject, names: List<String>): JSONObject? {
  for (name in names) {
    val objectValue = json.optJSONObject(name)
    if (objectValue != null) {
      return objectValue
    }
    val stringValue = json.optString(name)
    if (stringValue.trim().startsWith("{")) {
      val parsed = runCatching { JSONObject(stringValue) }.getOrNull()
      if (parsed != null) {
        return parsed
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
  val selectedSkillNames = selectedSkills.map { it.name.trim().lowercase() }.toSet()
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillsList,
    availableToolsList = buildAvailableCompatToolsList(selectedSkillNames),
  )
}

internal fun buildCompatAgentInstructionPayloadForTest(
  baseSystemPrompt: String,
  selectedSkillSummaries: List<String>,
): String {
  val selectedSkillNames = selectedSkillSummaries.map { it.substringBefore(":").trim().lowercase() }.toSet()
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillSummaries.joinToString("\n") { "- $it" },
    availableToolsList = buildAvailableCompatToolsList(selectedSkillNames),
  )
}

private fun buildCompatAgentInstructionPayloadFromSummary(
  selectedSkillsList: String,
  availableToolsList: String,
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
- For web search, call search_web only. The app will route it to an enabled search provider.
- After search results are returned, summarize the sources directly. Do not repeat raw JSON, dates, URL fragments, or punctuation noise.
- Stop after a concise final answer. Do not continue with repeated phrases, punctuation, or filler.

Available compatibility tools:
$availableToolsList

Enabled skills for this session:
$selectedSkillsList
"""
    .trimIndent()
}

private fun buildAvailableCompatToolsList(selectedSkillNames: Set<String>): String {
  val tools = mutableListOf<String>()
  if (
    selectedSkillNames.contains(EXA_SEARCH_SKILL_NAME) ||
      selectedSkillNames.contains(TAVILY_SEARCH_SKILL_NAME) ||
      selectedSkillNames.contains(LANGSEARCH_SEARCH_SKILL_NAME)
  ) {
    tools += "- search_web arguments: {\"query\":\"...\"} . Searches the web using an enabled search skill selected by the app."
  }
  if (selectedSkillNames.contains(FILE_WORKSPACE_SKILL_NAME)) {
    tools += "- list_workspace arguments: {\"path\":\".\"} . Lists files in the mounted workspace."
    tools +=
      "- read_workspace_text_file arguments: {\"path\":\"notes/input.txt\",\"max_bytes\":64000} . Reads text from txt, md, csv, json, xml, log, html, pdf, docx, or xlsx files in the mounted workspace. Use a larger max_bytes for spreadsheets or long documents when details matter."
    tools +=
      "- download_workspace_file arguments: {\"url\":\"https://.../file.pdf\",\"path\":\"downloads/file.pdf\",\"resume\":true} . Downloads a file into the mounted workspace with resumable HTTP Range support when the server supports it."
    tools += "- write_workspace_file arguments: {\"path\":\"notes/output.txt\",\"content\":\"...\"} . Writes text directly into the mounted workspace."
    tools += "- delete_workspace_file arguments: {\"path\":\"notes/output.txt\"} . Deletes a workspace file or empty directory."
  }
  if (selectedSkillNames.contains(LONG_TEXT_WRITER_SKILL_NAME)) {
    tools += "- write_workspace_text_file arguments: {\"path\":\"notes/output.md\",\"content\":\"...\"} . Writes the full final text into the workspace."
  }
  return tools.joinToString("\n").ifBlank { "- No compatibility tools are enabled. Answer directly without tool calls." }
}
