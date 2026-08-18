#!/usr/bin/env python3
"""Build-time MCP218 COMPAT hardening while preserving the MCP210 runtime baseline."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> tuple[Path, str]:
    path = ROOT / rel
    return path, path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    print(f"MCP218 patched {path.relative_to(ROOT)}")


def require_index(text: str, marker: str, rel: str, start: int = 0) -> int:
    index = text.find(marker, start)
    if index < 0:
        print(f"MCP218 patch missing marker in {rel}: {marker[:120]!r}", file=sys.stderr)
        raise SystemExit(1)
    return index


def replace_once(text: str, old: str, new: str, rel: str) -> str:
    count = text.count(old)
    if count != 1:
        print(
            f"MCP218 patch expected one marker in {rel}, found {count}: {old[:120]!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    return text.replace(old, new, 1)


# AgentTooling: preserve the old strict decoder as an internal canonical decoder and make the
# model-family adapter the only public parseCompatToolCall entrypoint.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt"
path, text = read(rel)
if "CompatToolCallWireAdapter.parseFirstToolCall(rawText)" not in text:
    text = replace_once(
        text,
        "fun parseCompatToolCall(rawText: String): ParsedCompatToolCall? {",
        "internal fun decodeCanonicalCompatToolCall(rawText: String): ParsedCompatToolCall? {",
        rel,
    )
    text = replace_once(
        text,
        "\nfun stripCompatThinkingText(rawText: String): String {",
        "\nfun parseCompatToolCall(rawText: String): ParsedCompatToolCall? =\n"
        "  CompatToolCallWireAdapter.parseFirstToolCall(rawText)\n\n"
        "fun stripCompatThinkingText(rawText: String): String {",
        rel,
    )
    write(path, text)


# CompatToolCallWireAdapter: unify canonical/model-family parsing and recover invalid long writes.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatToolCallWireAdapter.kt"
path, text = read(rel)
if "internal fun parseFirstToolCall(rawText: String): ParsedCompatToolCall?" not in text:
    start_marker = "  internal fun normalizeFirstToolCall(rawText: String): String? {"
    end_marker = "\n\n  internal fun hasStrongToolSignal(text: String): Boolean {"
    start = require_index(text, start_marker, rel)
    end = require_index(text, end_marker, rel, start)
    replacement = '''  internal fun normalizeFirstToolCall(rawText: String): String? =
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
  }'''
    text = text[:start] + replacement + text[end:]

    insertion_marker = "  // Generic <tool_call> variants with relaxed JSON, unquoted keys, or call:NAME{...}.\n"
    insertion_at = require_index(text, insertion_marker, rel)
    long_write_parser = r'''  // Long article/file writes can contain raw newlines or unescaped ASCII quotes inside content.
  // Recover the stable outer envelope and treat the final quote before the two closing braces as
  // the content terminator. Valid JSON is already handled by decodeCanonicalCompatToolCall first.
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
    ) return null

    val contentMatch =
      Regex("[\\\"']content[\\\"']\\s*:\\s*([\\\"'])", RegexOption.IGNORE_CASE).find(block)
        ?: return null
    val quote = contentMatch.groupValues[1].firstOrNull() ?: return null
    val contentStart = contentMatch.range.last + 1
    val tail = block.substring(contentStart)
    val terminal =
      if (quote == '\"') Regex("\\\"\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
      else Regex("'\\s*}\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL)
    val terminalMatch = terminal.find(tail) ?: return null
    val rawContent = tail.substring(0, terminalMatch.range.first)
    val prefix = block.substring(0, contentMatch.range.first)
    val pathValue =
      Regex("[\\\"']path[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']", RegexOption.IGNORE_CASE)
        .find(prefix)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRecoveredString)
        .orEmpty()
    if (pathValue.isBlank()) return null
    return ParsedCompatToolCall(
      toolName = toolName,
      arguments = JSONObject().put("path", pathValue).put("content", decodeRecoveredString(rawContent)),
    )
  }

  private fun decodeRecoveredString(raw: String): String =
    raw
      .replace("\\r\\n", "\n")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")
      .replace("\\\"", "\"")
      .replace("\\'", "'")
      .replace("\\\\", "\\")

'''
    text = text[:insertion_at] + long_write_parser + text[insertion_at:]

    text = replace_once(
        text,
        "internal class CompatToolCallStreamGate(private val enabled: Boolean) {",
        "internal class CompatToolCallStreamGate(\n"
        "  private val enabled: Boolean,\n"
        "  private val holdUntilDone: Boolean = false,\n"
        ") {",
        rel,
    )
    text = replace_once(
        text,
        "  fun accept(text: String): String {\n    if (!enabled) return text\n",
        "  fun accept(text: String): String {\n"
        "    if (!enabled) return text\n"
        "    if (holdUntilDone) {\n"
        "      mode = Mode.TOOL\n"
        "      buffered.append(text)\n"
        "      return \"\"\n"
        "    }\n",
        rel,
    )
    write(path, text)


# Runtime coordinator: fingerprint exactly the same ParsedCompatToolCall used by execution/UI.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt"
path, text = read(rel)
if "val call = parseCompatToolCall(text) ?: return null" not in text:
    start_marker = "  private fun extractToolCallFingerprint(text: String): String? {"
    end_marker = "\n\n  private fun extractFirstJsonObject(text: String): String? {"
    start = require_index(text, start_marker, rel)
    end = require_index(text, end_marker, rel, start)
    replacement = '''  private fun extractToolCallFingerprint(text: String): String? {
    val call = parseCompatToolCall(text) ?: return null
    val canonical =
      JSONObject()
        .put("tool", call.toolName.trim())
        .put("arguments", call.arguments)
    return canonicalizeJsonObject(canonical).take(MAX_FINGERPRINT_CHARS)
  }'''
    text = text[:start] + replacement + text[end:]
    write(path, text)


# LlmChatModelHelper: add the explicit-search control after prompt compaction, hold the required
# first pass off the UI, and host-fallback straight to search_web if the model refuses to search.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
path, text = read(rel)
if "searchRequiredForTopLevel" not in text:
    text = replace_once(
        text,
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n",
        "import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.CompatSearchRequiredPolicy\n"
        "import com.google.ai.edge.gallery.customtasks.agentchat.CompatToolCallStreamGate\n",
        rel,
    )

    compat_start = require_index(text, "    val compatPassKind =", rel)
    extra_context = require_index(text, "\n\n    val effectiveExtraContext =", rel, compat_start)
    search_block = '''
    val searchRequiredForTopLevel =
      isCompatPass &&
        compatReason == COMPAT_FRESH_REASON_TOP_LEVEL &&
        CompatSearchRequiredPolicy.isSearchRequiredInput(effectiveInput)'''
    text = text[:extra_context] + search_block + text[extra_context:]

    text = replace_once(
        text,
        "    var generatedChars = 0\n    val generatedText = StringBuilder()\n\n    conversation.sendMessageAsync(\n",
        "    var generatedChars = 0\n"
        "    val generatedText = StringBuilder()\n"
        "    val compatToolCallGate =\n"
        "      CompatToolCallStreamGate(\n"
        "        enabled = isCompatPass,\n"
        "        holdUntilDone = searchRequiredForTopLevel,\n"
        "      )\n\n"
        "    conversation.sendMessageAsync(\n",
        rel,
    )
    text = replace_once(
        text,
        "            resultListener(text, false, thought.takeIf { it.isNotBlank() })\n",
        "            val visibleText = compatToolCallGate.accept(text)\n"
        "            if (visibleText.isNotEmpty() || thought.isNotBlank()) {\n"
        "              resultListener(visibleText, false, thought.takeIf { it.isNotBlank() })\n"
        "            }\n",
        rel,
    )

    send_start = require_index(text, "    conversation.sendMessageAsync(", rel)
    done_start = require_index(text, "          override fun onDone() {", rel, send_start)
    perf_marker = "            AgentPerformanceCoordinator.onInferenceDone(model.name, generatedChars)"
    perf_at = require_index(text, perf_marker, rel, done_start)
    new_done_prefix = '''          override fun onDone() {
            val rawGeneration = generatedText.toString()
            val generationForRouting =
              if (
                searchRequiredForTopLevel &&
                  !CompatSearchRequiredPolicy.hasWebSearchToolCall(rawGeneration)
              ) {
                CompatSearchRequiredPolicy.buildFallbackToolCall(effectiveInput) ?: rawGeneration
              } else {
                rawGeneration
              }
            val completedGeneration = compatToolCallGate.finish(generationForRouting)
            if (completedGeneration.uiTail.isNotBlank()) {
              resultListener(completedGeneration.uiTail, false, null)
            }
            val decision =
              AgentCompatRuntimeCoordinator.onGenerationCompleted(
                modelName = model.name,
                generatedText = completedGeneration.runtimeText,
              )
'''
    text = text[:done_start] + new_done_prefix + text[perf_at:]

    text = replace_once(
        text,
        "    val compactedRawInput = compactCompatEnvelope(input)\n",
        "    val compactedRawInput =\n"
        "      CompatSearchRequiredPolicy.injectIntoCompatInput(compactCompatEnvelope(input))\n",
        rel,
    )
    write(path, text)
