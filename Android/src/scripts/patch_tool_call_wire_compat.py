#!/usr/bin/env python3
"""Idempotently harden the COMPAT tool-call boundary without disturbing the MCP210 runtime baseline.

MCP218 keeps one model-family parser as the source of truth, recovers long multiline workspace-write
calls, and adds a tiny explicit-web-search guard. AgentTools remains the execution/permission
authority; LiteRT-LM, Conversation lifetime, Box, and thinking policy are untouched.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_target(relative_path: str, replacements: list[tuple[str, str]], sentinel: str) -> None:
    target = ROOT / relative_path
    text = target.read_text(encoding="utf-8")
    if sentinel in text:
        return
    for old, new in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(
                f"patch_tool_call_wire_compat: expected exactly one anchor in {relative_path}, "
                f"found {count}: {old[:100]!r}"
            )
        text = text.replace(old, new, 1)
    target.write_text(text, encoding="utf-8")
    print(f"Patched {relative_path}")


# 1) Make the model-family adapter the single public parsing entrypoint.
patch_target(
    "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt",
    [
        (
            "fun parseCompatToolCall(rawText: String): ParsedCompatToolCall? {",
            "internal fun decodeCanonicalCompatToolCall(rawText: String): ParsedCompatToolCall? {",
        ),
        (
            "\nfun stripCompatThinkingText(rawText: String): String {",
            "\nfun parseCompatToolCall(rawText: String): ParsedCompatToolCall? =\n"
            "  CompatToolCallWireAdapter.parseFirstToolCall(rawText)\n\n"
            "fun stripCompatThinkingText(rawText: String): String {",
        ),
    ],
    sentinel="CompatToolCallWireAdapter.parseFirstToolCall(rawText)",
)


# 2) Route canonical + model-family formats through one adapter, and recover very long workspace
# writes even when the model emits raw newlines or unescaped quotes inside content.
adapter_normalize_old = '''  internal fun normalizeFirstToolCall(rawText: String): String? {
    val raw = rawText.trim()
    if (raw.isBlank()) return null
    parseCompatToolCall(raw)?.let { return canonical(it) }
    return listOfNotNull(
        parseLooseToolBlock(raw),
        parseQwen35(raw),
        parseGlm(raw),
        parseGemma(raw),
        parseMistral(raw),
        parseDeepSeek(raw),
        parseGptOss(raw),
        parsePythonTag(raw),
        parseMarkedJson(raw),
        parseInvokeXml(raw),
        parseToolUseXml(raw),
        parseBareJson(raw),
      )
      .firstOrNull()
      ?.let(::canonical)
  }
'''
adapter_normalize_new = '''  internal fun normalizeFirstToolCall(rawText: String): String? =
    parseFirstToolCall(rawText)?.let(::canonical)

  internal fun parseFirstToolCall(rawText: String): ParsedCompatToolCall? {
    val raw = rawText.trim()
    if (raw.isBlank()) return null
    decodeCanonicalCompatToolCall(raw)?.let { return it }
    return listOfNotNull(
        parseLongWorkspaceWrite(raw),
        parseLooseToolBlock(raw),
        parseQwen35(raw),
        parseGlm(raw),
        parseGemma(raw),
        parseMistral(raw),
        parseDeepSeek(raw),
        parseGptOss(raw),
        parsePythonTag(raw),
        parseMarkedJson(raw),
        parseInvokeXml(raw),
        parseToolUseXml(raw),
        parseBareJson(raw),
      )
      .firstOrNull()
  }
'''
long_write_parser = r'''  // Recovery path for long article/file writes. Models sometimes emit valid tool structure but
  // leave raw newlines or ASCII quotes unescaped inside the content string, which strict JSON rejects.
  private fun parseLongWorkspaceWrite(text: String): ParsedCompatToolCall? {
    val open = text.indexOf("<tool_call>", ignoreCase = true)
    if (open < 0) return null
    val close = text.indexOf("</tool_call>", startIndex = open, ignoreCase = true)
    val block =
      text.substring(
        open + "<tool_call>".length,
        if (close >= 0) close else text.length,
      ).trim()
    val toolMatch =
      Regex("[\\\"'](?:tool|name|tool_name)[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
        .find(block) ?: return null
    val toolName = normalizeName(toolMatch.groupValues[1])
    if (
      toolName.lowercase() !in
        setOf(
          "write_workspace_file",
          "write_workspace_text_file",
          "append_workspace_file",
          "append_workspace_text_file",
        )
    ) {
      return null
    }

    val contentMatch =
      Regex("[\\\"']content[\\\"']\\s*:\\s*([\\\"'])", RegexOption.IGNORE_CASE).find(block)
        ?: return null
    val quote = contentMatch.groupValues[1].firstOrNull() ?: return null
    val contentStart = contentMatch.range.last + 1
    val tail = block.substring(contentStart)
    val terminal =
      if (quote == '\"') {
        Regex("\\\"\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
      } else {
        Regex("'\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
      }
    val terminalMatch = terminal.find(tail) ?: return null
    val rawContent = tail.substring(0, terminalMatch.range.first)
    val prefix = block.substring(0, contentMatch.range.first)
    val path =
      Regex("[\\\"']path[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
        .find(prefix)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRecoveredString)
        .orEmpty()
    if (path.isBlank()) return null

    return ParsedCompatToolCall(
      toolName = toolName,
      arguments =
        JSONObject()
          .put("path", path)
          .put("content", decodeRecoveredString(rawContent)),
    )
  }

  private fun decodeRecoveredString(raw: String): String {
    return raw
      .replace("\\r\\n", "\n")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\'", "'")
      .replace("\\\\", "\\")
  }

'''
patch_target(
    "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatToolCallWireAdapter.kt",
    [
        (adapter_normalize_old, adapter_normalize_new),
        (
            "  // Generic <tool_call> variants with relaxed JSON, unquoted keys, or call:NAME{...}.\n",
            long_write_parser
            + "  // Generic <tool_call> variants with relaxed JSON, unquoted keys, or call:NAME{...}.\n",
        ),
        (
            "internal class CompatToolCallStreamGate(private val enabled: Boolean) {",
            "internal class CompatToolCallStreamGate(\n"
            "  private val enabled: Boolean,\n"
            "  private val holdUntilDone: Boolean = false,\n"
            ") {",
        ),
        (
            "  fun accept(text: String): String {\n    if (!enabled) return text\n",
            "  fun accept(text: String): String {\n"
            "    if (!enabled) return text\n"
            "    if (holdUntilDone) {\n"
            "      mode = Mode.TOOL\n"
            "      buffered.append(text)\n"
            "      return \"\"\n"
            "    }\n",
        ),
    ],
    sentinel="internal fun parseFirstToolCall(rawText: String): ParsedCompatToolCall?",
)


# 3) Use the same parser for runtime tool fingerprints as the UI/execution path.
fingerprint_old = '''  private fun extractToolCallFingerprint(text: String): String? {
    val open = text.indexOf(TOOL_CALL_OPEN_TAG_RUNTIME, ignoreCase = true)
    if (open < 0) return null
    val payloadStart = open + TOOL_CALL_OPEN_TAG_RUNTIME.length
    val close =
      text.indexOf(
        TOOL_CALL_CLOSE_TAG_RUNTIME,
        startIndex = payloadStart,
        ignoreCase = true,
      )
    val payload = text.substring(payloadStart, if (close >= 0) close else text.length).trim()
    if (payload.isBlank()) return null
    val jsonText = extractFirstJsonObject(payload)
    if (jsonText != null) {
      val json = runCatching { JSONObject(jsonText) }.getOrNull()
      if (json != null) {
        return canonicalizeJsonObject(json).take(MAX_FINGERPRINT_CHARS)
      }
    }
    return payload.replace(Regex("\\s+"), " ").trim().take(MAX_FINGERPRINT_CHARS)
  }
'''
fingerprint_new = '''  private fun extractToolCallFingerprint(text: String): String? {
    val call = parseCompatToolCall(text) ?: return null
    val canonical =
      JSONObject()
        .put("tool", call.toolName.trim())
        .put("arguments", call.arguments)
    return canonicalizeJsonObject(canonical).take(MAX_FINGERPRINT_CHARS)
  }
'''
patch_target(
    "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt",
    [(fingerprint_old, fingerprint_new)],
    sentinel="val call = parseCompatToolCall(text) ?: return null",
)


# 4) LlmChatModelHelper: inject the date/search control only for explicit web requests, hold that
# first pass off the UI, and if the model still refuses to search, replace the stale prose with a
# host-generated search_web call using the untouched original request. No extra corrective LLM pass.
compat_kind_old = '''    val compatPassKind =
      when (compatReason) {
        COMPAT_FRESH_REASON_TOP_LEVEL -> "top_level"
        COMPAT_FRESH_REASON_TOOL_CONTINUATION -> "continuation"
        else -> "none"
      }

    val effectiveExtraContext =
'''
compat_kind_new = '''    val compatPassKind =
      when (compatReason) {
        COMPAT_FRESH_REASON_TOP_LEVEL -> "top_level"
        COMPAT_FRESH_REASON_TOOL_CONTINUATION -> "continuation"
        else -> "none"
      }
    val searchRequiredForTopLevel =
      isCompatPass &&
        compatReason == COMPAT_FRESH_REASON_TOP_LEVEL &&
        CompatSearchRequiredPolicy.isSearchRequiredInput(effectiveInput)

    val effectiveExtraContext =
'''
patch_target(
    "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt",
    [
        (
            "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n",
            "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n"
            "import com.google.ai.edge.gallery.customtasks.agentchat.CompatSearchRequiredPolicy\n"
            "import com.google.ai.edge.gallery.customtasks.agentchat.CompatToolCallStreamGate\n",
        ),
        (compat_kind_old, compat_kind_new),
        (
            "    var generatedChars = 0\n    val generatedText = StringBuilder()\n\n    conversation.sendMessageAsync(\n",
            "    var generatedChars = 0\n"
            "    val generatedText = StringBuilder()\n"
            "    val compatToolCallGate =\n"
            "      CompatToolCallStreamGate(\n"
            "        enabled = isCompatPass,\n"
            "        holdUntilDone = searchRequiredForTopLevel,\n"
            "      )\n\n"
            "    conversation.sendMessageAsync(\n",
        ),
        (
            "            resultListener(text, false, thought.takeIf { it.isNotBlank() })\n",
            "            val visibleText = compatToolCallGate.accept(text)\n"
            "            if (visibleText.isNotEmpty() || thought.isNotBlank()) {\n"
            "              resultListener(visibleText, false, thought.takeIf { it.isNotBlank() })\n"
            "            }\n",
        ),
        (
            "          override fun onDone() {\n            val decision =\n              AgentCompatRuntimeCoordinator.onGenerationCompleted(\n                modelName = model.name,\n                generatedText = generatedText.toString(),\n              )\n",
            "          override fun onDone() {\n"
            "            val rawGeneration = generatedText.toString()\n"
            "            val generationForRouting =\n"
            "              if (\n"
            "                searchRequiredForTopLevel &&\n"
            "                  !CompatSearchRequiredPolicy.hasWebSearchToolCall(rawGeneration)\n"
            "              ) {\n"
            "                CompatSearchRequiredPolicy.buildFallbackToolCall(effectiveInput) ?: rawGeneration\n"
            "              } else {\n"
            "                rawGeneration\n"
            "              }\n"
            "            val completedGeneration = compatToolCallGate.finish(generationForRouting)\n"
            "            if (completedGeneration.uiTail.isNotBlank()) {\n"
            "              resultListener(completedGeneration.uiTail, false, null)\n"
            "            }\n"
            "            val decision =\n"
            "              AgentCompatRuntimeCoordinator.onGenerationCompleted(\n"
            "                modelName = model.name,\n"
            "                generatedText = completedGeneration.runtimeText,\n"
            "              )\n",
        ),
        (
            "    val compactedRawInput = compactCompatEnvelope(input)\n",
            "    val compactedRawInput =\n"
            "      CompatSearchRequiredPolicy.injectIntoCompatInput(compactCompatEnvelope(input))\n",
        ),
    ],
    sentinel="searchRequiredForTopLevel",
)
