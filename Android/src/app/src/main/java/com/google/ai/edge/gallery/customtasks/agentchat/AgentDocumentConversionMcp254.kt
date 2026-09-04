package com.google.ai.edge.gallery.customtasks.agentchat

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP254 conversion hardening derived from the six MCP253 conversion field logs.
 *
 * This layer is deliberately format-policy / normalization only. Android rendering and SAF I/O
 * remain in the established Office backend.
 */
object AgentDocumentConversionMcp254 {
  const val MAX_CONVERSION_TEXT_BYTES = 500_000
  const val META_INPUT_PATH_NORMALIZED = "_mcp254_input_path_normalized"

  enum class ConversionPlan {
    BINARY_COPY,
    TEXT_ROUND_TRIP,
  }

  fun normalizeRequest(skillName: String, request: JSONObject) {
    if (skillName != DOCUMENT_CONVERT_SKILL_NAME) return

    val rawInput = request.optString("input_path").ifBlank { request.optString("path") }
    if (rawInput.isNotBlank()) {
      val normalized = normalizeWorkspaceInputPath(rawInput)
      request.put("input_path", normalized)
      if (normalized != rawInput.replace('\\', '/').trim()) {
        request.put(META_INPUT_PATH_NORMALIZED, true)
      }
    }

    val format = canonicalFormat(request.optString("output_format").ifBlank { request.optString("format") })
    if (format.isNotBlank()) request.put("output_format", format)
  }

  /** Bare generated-document names live in file/. Paths already naming another workspace folder stay intact. */
  internal fun normalizeWorkspaceInputPath(rawPath: String): String {
    val raw = rawPath.replace('\\', '/').trim()
    if (raw.isBlank() || raw.startsWith("/") || raw.split('/').any { it == ".." }) return raw
    val path = raw.removePrefix("./")
    return if ('/' in path) path else "file/$path"
  }

  internal fun canonicalFormat(raw: String): String {
    return raw.trim().lowercase(Locale.US).removePrefix(".").let {
      when (it) {
        "word", "doc" -> "docx"
        "text", "plain", "plaintext" -> "txt"
        "htm" -> "html"
        else -> it
      }
    }
  }

  internal fun supportedInput(format: String): Boolean = canonicalFormat(format) in setOf("txt", "docx", "pdf", "html")

  internal fun supportedOutput(format: String): Boolean = canonicalFormat(format) in setOf("txt", "docx", "pdf", "html")

  internal fun conversionPlan(inputFormat: String, outputFormat: String): ConversionPlan {
    val input = canonicalFormat(inputFormat)
    val output = canonicalFormat(outputFormat)
    require(supportedInput(input)) { "Unsupported conversion input format: $inputFormat" }
    require(supportedOutput(output)) { "Unsupported conversion output format: $outputFormat" }
    return if (input == output) ConversionPlan.BINARY_COPY else ConversionPlan.TEXT_ROUND_TRIP
  }

  internal fun sameWorkspacePath(first: String, second: String): Boolean =
    normalizeWorkspacePathForCompare(first) == normalizeWorkspacePathForCompare(second)

  private fun normalizeWorkspacePathForCompare(rawPath: String): String =
    rawPath.replace('\\', '/').trim().trimStart('/').removePrefix("./")

  internal fun textFitsLimit(text: String): Boolean =
    text.toByteArray(Charsets.UTF_8).size <= MAX_CONVERSION_TEXT_BYTES

  /**
   * Comparison used after a text-oriented conversion. Layout whitespace is intentionally ignored:
   * Android PdfDocument wraps lines for page layout and PDFTextStripper can reintroduce those line
   * breaks as spaces. The MCP253 logs proved that exact semantic content was being rejected here.
   */
  internal fun preservesSemanticContent(source: String, output: String): Boolean {
    val expected = compactSemantic(source)
    val actual = compactSemantic(output)
    if (expected.isBlank()) return true
    if (actual.isBlank()) return false

    // Reject material truncation before fragment matching. Extra whitespace is already removed.
    if (actual.length * 100 < expected.length * 92) return false

    if (expected.length <= 180) return actual.contains(expected)

    val width = minOf(72, maxOf(28, expected.length / 8))
    val starts =
      listOf(
        0,
        (expected.length / 4 - width / 2).coerceAtLeast(0),
        (expected.length / 2 - width / 2).coerceAtLeast(0),
        (expected.length * 3 / 4 - width / 2).coerceAtLeast(0),
        (expected.length - width).coerceAtLeast(0),
      ).distinct()
    return starts.all { start ->
      val end = (start + width).coerceAtMost(expected.length)
      actual.contains(expected.substring(start, end))
    }
  }

  internal fun containsIgnoringLayoutWhitespace(actual: String, expected: String): Boolean {
    val needle = compactSemantic(expected)
    if (needle.isBlank()) return true
    return compactSemantic(actual).contains(needle)
  }

  internal fun compactSemantic(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    return buildString(normalized.length) {
      normalized.forEach { ch ->
        if (!ch.isWhitespace() && !Character.isSpaceChar(ch) && ch !in setOf('\u200B', '\u200C', '\u200D', '\uFEFF')) {
          append(ch)
        }
      }
    }
  }

  /** Also tolerate bare names in PDF merge arrays because those files are generated under file/. */
  fun normalizeOfficeInputArrays(skillName: String, request: JSONObject) {
    if (skillName != PDF_DOCUMENT_SKILL_NAME) return
    val paths = request.optJSONArray("input_paths") ?: return
    val normalized = JSONArray()
    for (i in 0 until paths.length()) {
      val value = paths.optString(i)
      normalized.put(if (value.isBlank()) value else normalizeWorkspaceInputPath(value))
    }
    request.put("input_paths", normalized)
  }
}
