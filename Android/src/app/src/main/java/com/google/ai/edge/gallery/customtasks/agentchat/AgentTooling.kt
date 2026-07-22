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
  return AgentToolModeValues.AUTO
}

fun resolveAgentToolMode(model: Model): ResolvedAgentToolMode {
  return when (getConfiguredAgentToolMode(model)) {
    AgentToolModeValues.AUTO ->
      if (supportsNativeAgentTools(model)) ResolvedAgentToolMode.NATIVE else ResolvedAgentToolMode.COMPAT
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
            mediaToolboxConfig = readMediaToolboxConfig(skillManagerViewModel.dataStoreRepository),
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
  mediaToolboxConfig: MediaToolboxConfig = MediaToolboxConfig(),
): String {
  val selectedSkillsList =
    selectedSkills.joinToString(separator = "\n") { "- ${it.name}: ${it.description}" }
      .ifBlank { "- 暂无已启用技能。" }
  val selectedSkillNames = selectedSkills.map { it.name.trim().lowercase() }.toSet()
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillsList,
    availableToolsList = buildAvailableCompatToolsList(selectedSkillNames, mediaToolboxConfig),
  )
}

internal fun buildCompatAgentInstructionPayloadForTest(
  baseSystemPrompt: String,
  selectedSkillSummaries: List<String>,
  mediaToolboxConfig: MediaToolboxConfig = MediaToolboxConfig(),
): String {
  val selectedSkillNames = selectedSkillSummaries.map { it.substringBefore(":").trim().lowercase() }.toSet()
  return buildCompatAgentInstructionPayloadFromSummary(
    selectedSkillsList = selectedSkillSummaries.joinToString("\n") { "- $it" },
    availableToolsList = buildAvailableCompatToolsList(selectedSkillNames, mediaToolboxConfig),
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

private fun buildAvailableCompatToolsList(
  selectedSkillNames: Set<String>,
  mediaToolboxConfig: MediaToolboxConfig,
): String {
  val tools = mutableListOf<String>()
  if (
    selectedSkillNames.contains(EXA_SEARCH_SKILL_NAME) ||
      selectedSkillNames.contains(TAVILY_SEARCH_SKILL_NAME) ||
      selectedSkillNames.contains(LANGSEARCH_SEARCH_SKILL_NAME) ||
      selectedSkillNames.contains(ANYSEARCH_SEARCH_SKILL_NAME)
  ) {
    tools += "- search_web arguments: {\"query\":\"...\"} . Searches the web using an enabled search skill selected by the app."
  }
  if (selectedSkillNames.contains(ANYSEARCH_SEARCH_SKILL_NAME)) {
    tools += "- anysearch_extract arguments: {\"url\":\"https://example.com/page\"} . Extracts a full web page through AnySearch and returns compact Markdown-like content."
    tools += "- anysearch_get_sub_domains arguments: {\"domain\":\"finance\"} . Lists AnySearch vertical sub-domains when vertical search is enabled in settings."
  }
  if (selectedSkillNames.contains(WEB_PAGE_EXTRACT_SKILL_NAME)) {
    tools += "- extract_web_page arguments: {\"url\":\"https://example.com/page\",\"max_chars\":12000} . Fetches an HTML page directly and extracts title, description, and readable Markdown-like content."
  }
  if (selectedSkillNames.contains(WEATHER_QUERY_SKILL_NAME)) {
    tools += "- query_weather arguments: {\"location\":\"昆明\",\"mode\":\"current|24h|week\"} . Gets current weather, next 24 hours, or next 7 days. Use a city name for stable results; use location=current only when the user asks for current device location. If current location is unavailable, ask for a city name instead of guessing."
  }
  if (selectedSkillNames.contains(EDGE_TTS_SKILL_NAME)) {
    tools += "- list_edge_tts_voices arguments: {} . Lists up to 15 curated Microsoft Edge TTS voices."
    tools += "- edge_tts_synthesize arguments: {\"input_path\":\"file/story.txt\",\"voice\":\"zh-CN-XiaoxiaoNeural\",\"output_path\":\"media/output.mp3\"} . Synthesizes text to an MP3 file in the mounted workspace. If the text is already in a workspace file, MUST pass input_path directly and MUST NOT read the file first or copy its full text into text."
  }
  if (selectedSkillNames.contains(AGNES_OMNI_SKILL_NAME)) {
    tools += "- generate_agnes_image arguments: {\"prompt\":\"image prompt\",\"output_path\":\"media/image.png\"} . Generates an image using the configured Agnes model, size, and ratio, then saves it into the workspace media folder."
    tools += "- generate_agnes_video arguments: {\"prompt\":\"video prompt\",\"output_path\":\"media/video.mp4\"} . Generates a video using the configured Agnes model, duration, and resolution, then saves it into the workspace media folder."
  }
  if (selectedSkillNames.contains(MINIMAX_OMNI_SKILL_NAME)) {
    tools += "- minimax_generate_text arguments: {\"prompt\":\"question or writing task\"} . Generates text using the configured MiniMax text model."
    tools += "- minimax_generate_image arguments: {\"prompt\":\"image prompt\",\"output_path\":\"media/image.jpg\"} . Generates one image using the configured MiniMax image ratio, then saves it into the workspace media folder."
    tools += "- minimax_tts_synthesize arguments: {\"input_path\":\"file/story.txt\",\"output_path\":\"media/speech.mp3\"} . Synthesizes speech with the configured MiniMax voice. If the text is already in a workspace file, MUST pass input_path directly and MUST NOT read the file first or copy its full text into text."
    tools += "- minimax_generate_music arguments: {\"prompt\":\"music style prompt\",\"output_path\":\"media/music.mp3\"} . Generates music using the configured MiniMax music mode and saves it into the workspace media folder. This may take several minutes; wait for the tool result."
    tools += "- minimax_analyze_image arguments: {\"input_path\":\"media/photo.jpg\",\"prompt\":\"describe this image\"} . Analyzes a workspace image and returns a concise description."
    tools += "- minimax_analyze_video arguments: {\"input_path\":\"media/video.mp4\",\"prompt\":\"describe this video\"} . Analyzes a workspace video with the configured MiniMax video model and returns a concise description."
    tools += "- minimax_search_web arguments: {\"query\":\"search keywords\"} . Searches the web using MiniMax Token Plan search and returns compact results."
  }
  if (selectedSkillNames.contains(MEDIA_TOOLBOX_SKILL_NAME)) {
    if (mediaToolboxConfig.imageModeEnabled) {
      tools += "- media_image_info arguments: {\"input_path\":\"media/photo.jpg\"} . Reads width, height, MIME type, resolution, and file size for a workspace image."
      tools += "- media_image_resize arguments: {\"input_path\":\"media/photo.jpg\",\"target\":\"1080p\",\"output_path\":\"media/photo-1080.jpg\"} . Resizes an image. target can be 512, 720p, 1080p, or 4k; width and height are also accepted."
      tools += "- media_image_convert arguments: {\"input_path\":\"media/photo.jpg\",\"target_format\":\"png\",\"output_path\":\"media/photo.png\"} . Converts image format to jpg, png, or webp."
      tools += "- media_image_to_video arguments: {\"input_path\":\"media/photo.jpg\",\"duration_seconds\":5,\"output_path\":\"media/photo-loop.mp4\"} . Turns one image into a short MP4 video."
    }
    if (mediaToolboxConfig.audioModeEnabled) {
      tools += "- media_audio_info arguments: {\"input_path\":\"media/audio.mp3\"} . Reads duration, MIME type, bitrate, audio-track state, and file size."
      tools += "- media_audio_convert arguments: {\"input_path\":\"media/audio.mp3\",\"target_format\":\"wav\",\"output_path\":\"media/audio.wav\"} . Converts audio format to mp3, wav, m4a, aac, ogg, or flac."
      tools += "- media_audio_compress arguments: {\"input_path\":\"media/audio.mp3\",\"compression_level\":\"1/2\",\"output_path\":\"media/audio-small.mp3\"} . Compresses audio using simple levels: 1/2, 1/3, or 1/4."
      tools += "- media_audio_concat arguments: {\"input_paths\":[\"media/a.mp3\",\"media/b.mp3\"],\"output_path\":\"media/combined.mp3\"} . Concatenates 2 to 5 audio files in order."
      tools += "- media_audio_trim arguments: {\"input_path\":\"media/audio.mp3\",\"start\":\"00:10\",\"end\":\"00:30\",\"output_path\":\"media/clip.mp3\"} . Clips one audio segment. Time can be seconds, mm:ss, or hh:mm:ss."
      tools += "- media_audio_mix arguments: {\"primary_path\":\"media/voice.mp3\",\"secondary_path\":\"media/music.mp3\",\"secondary_volume\":0.3,\"loop_secondary\":true,\"output_path\":\"media/mix.mp3\"} . Mixes two audio tracks; supports volume, delayed secondary_start, and looping background."
    }
    if (mediaToolboxConfig.videoModeEnabled) {
      tools += "- media_video_info arguments: {\"input_path\":\"media/video.mp4\"} . Reads duration, width, height, rotation, MIME type, bitrate, track state, and file size."
      tools += "- media_video_convert arguments: {\"input_path\":\"media/video.mov\",\"target_format\":\"mp4\",\"output_path\":\"media/video.mp4\"} . Converts video format to mp4, mov, mkv, or webm."
      tools += "- media_video_resize arguments: {\"input_path\":\"media/video.mp4\",\"target\":\"1080p\",\"output_path\":\"media/video-1080p.mp4\"} . Resizes video. target can be 512, 720p, 1080p, 2k, or 4k; width and height are also accepted."
      tools += "- media_video_compress arguments: {\"input_path\":\"media/video.mp4\",\"compression_level\":\"1/2\",\"output_path\":\"media/video-small.mp4\"} . Compresses video using simple levels: 1/2, 1/3, or 1/4."
      tools += "- media_video_concat arguments: {\"input_paths\":[\"media/a.mp4\",\"media/b.mp4\"],\"output_path\":\"media/combined.mp4\"} . Concatenates 2 to 5 video files in order; the tool automatically normalizes size, codec, fps, and missing audio before joining."
      tools += "- media_video_trim arguments: {\"input_path\":\"media/video.mp4\",\"start\":\"5\",\"end\":\"12.5\",\"output_path\":\"media/clip.mp4\"} . Clips one video segment. Time can be seconds, mm:ss, or hh:mm:ss."
      tools += "- media_video_extract_audio arguments: {\"input_path\":\"media/video.mp4\",\"target_format\":\"mp3\",\"output_path\":\"media/audio.mp3\"} . Extracts a video's audio track."
      tools += "- media_video_mute arguments: {\"input_path\":\"media/video.mp4\",\"output_path\":\"media/muted.mp4\"} . Removes the video's audio track. For any user request to mute a video, silence a video, remove sound, or make a video silent, MUST use media_video_mute, not media_audio_mix."
      tools += "- media_video_add_audio arguments: {\"video_path\":\"media/video.mp4\",\"audio_path\":\"media/music.mp3\",\"audio_volume\":0.6,\"output_path\":\"media/video-music.mp4\"} . Adds external audio while preserving original audio when present."
    }
  }
  if (selectedSkillNames.contains(FILE_WORKSPACE_SKILL_NAME)) {
    tools += "- list_workspace arguments: {\"path\":\".\"} . Lists files in the mounted workspace."
    tools +=
      "- read_workspace_text_file arguments: {\"path\":\"file/input.txt\",\"max_bytes\":64000} . Reads text from txt, md, csv, json, xml, log, html, pdf, docx, or xlsx files in the mounted workspace. Workspace folders: file for text/documents, media for generated media, download for downloaded files, tool-audit for exact tool logs."
    tools +=
      "- download_workspace_file arguments: {\"url\":\"https://.../file.pdf\",\"path\":\"download/file.pdf\",\"resume\":true} . Downloads a file into the mounted workspace with resumable HTTP Range support when the server supports it."
    tools += "- write_workspace_file arguments: {\"path\":\"file/output.txt\",\"content\":\"...\"} . Writes text directly into the mounted workspace. Prefer file/ for text and documents."
    tools += "- delete_workspace_file arguments: {\"path\":\"file/output.txt\"} . Deletes a workspace file or empty directory."
  }
  if (selectedSkillNames.contains(LONG_TEXT_WRITER_SKILL_NAME)) {
    tools += "- write_workspace_text_file arguments: {\"path\":\"file/output.md\",\"content\":\"...\"} . Writes the full final text into the workspace."
  }
  return tools.joinToString("\n").ifBlank { "- No compatibility tools are enabled. Answer directly without tool calls." }
}
