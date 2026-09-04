#!/usr/bin/env python3
"""MCP254 evidence-driven document-conversion repair.

MUST run after MCP251, MCP252, and MCP253. This patch is restricted to the additive Office /
document-conversion boundary. Its primary defects are reproduced from
logs/mcp-253_测试日志与文档_20260904.zip before the dedicated build proceeds.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"


def require_count(text: str, needle: str, expected: int, label: str) -> None:
    count = text.count(needle)
    if count != expected:
        raise SystemExit(f"MCP254 fail-closed: {label} count={count}, expected={expected}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    require_count(text, old, 1, label)
    return text.replace(old, new, 1)


def replace_function(text: str, start_marker: str, next_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"MCP254 fail-closed: {label} start marker missing")
    end = text.find(next_marker, start)
    if end < 0:
        raise SystemExit(f"MCP254 fail-closed: {label} end marker missing")
    return text[:start] + replacement.rstrip() + "\n\n" + text[end:]


def patch_file(path: Path, marker: str, patcher) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"MCP254 already applied: {path.name}")
        return
    updated = patcher(text)
    if updated == text or marker not in updated:
        raise SystemExit(f"MCP254 fail-closed: marker missing after patch: {path}")
    path.write_text(updated, encoding="utf-8")
    print(f"MCP254 patched: {path}")


def patch_truth_guard(text: str) -> str:
    mcp253_hook = '''    // MCP253_OFFICE_EVIDENCE_DIALECT_NORMALIZATION\n    AgentOfficeMcp253Compat.normalizeBeforeRouting(skillName = skillName, request = request)\n'''
    require_count(text, mcp253_hook, 2, "MCP253 normalization hook")
    mcp254_hook = mcp253_hook + '''    // MCP254_DOCUMENT_CONVERSION_NORMALIZATION\n    AgentDocumentConversionMcp254.normalizeRequest(skillName = skillName, request = request)\n    AgentDocumentConversionMcp254.normalizeOfficeInputArrays(skillName = skillName, request = request)\n'''
    text = text.replace(mcp253_hook, mcp254_hook)

    old = '''    val outputPath = expectedOutputPath(operation, request)\n    val writesOutput = isWriteOperation(operation)\n    val existingOutput = outputPath?.let { resolveExisting(root, it) }\n\n    // A create call must never silently destroy an existing document. This specifically closes\n'''
    new = '''    val outputPath = expectedOutputPath(operation, request)\n    val writesOutput = isWriteOperation(operation)\n    val existingOutput = outputPath?.let { resolveExisting(root, it) }\n\n    // MCP254_DOCUMENT_CONVERSION_SOURCE_PRESERVATION\n    // The conversion skill promises to preserve the source. Bare input names have already been\n    // normalized to file/, and explicit outputs may only replace an existing *different* target\n    // when overwrite=true. Same-path conversion is always rejected.\n    if (operation == "document_convert") {\n      val inputPath = request.optString("input_path").ifBlank { request.optString("path") }\n      if (outputPath != null &&\n        AgentDocumentConversionMcp254.sameWorkspacePath(inputPath, outputPath)\n      ) {\n        return failure(\n          operation,\n          "Conversion output must not overwrite the source file: $inputPath",\n          "Omit output_path to let the app allocate a collision-safe converted filename, or choose a different workspace-relative output path.",\n          request,\n        )\n      }\n      if (existingOutput != null && !request.optBoolean("overwrite", false)) {\n        return failure(\n          operation,\n          "Refusing to overwrite existing conversion target $outputPath.",\n          "Choose another output_path, omit output_path for an automatic collision-safe name, or set overwrite=true only when replacing that separate target is explicitly intended.",\n          request,\n        )\n      }\n    }\n\n    // A create call must never silently destroy an existing document. This specifically closes\n'''
    text = replace_once(text, old, new, "conversion source preservation")

    old_pdf_verify = '''        require(semanticContainsAll(extracted, expected)) { "PDF read-back does not contain the requested text." }\n'''
    new_pdf_verify = '''        // MCP254_PDF_LAYOUT_INSENSITIVE_VERIFICATION\n        // Android PDF rendering inserts line wraps; PDFTextStripper re-emits them as whitespace.\n        // Compare semantic characters while ignoring layout whitespace so valid PDFs are not rolled back.\n        require(expected.all { AgentDocumentConversionMcp254.containsIgnoringLayoutWhitespace(extracted, it) }) {\n          "PDF read-back does not contain the requested text."\n        }\n'''
    text = replace_once(text, old_pdf_verify, new_pdf_verify, "PDF layout-insensitive verification")

    verify_conversion = '''  private fun verifyConversion(\n    context: Context,\n    root: DocumentFile,\n    outputBytes: ByteArray,\n    request: JSONObject,\n  ): Verification {\n    val inputPath = request.optString("input_path").ifBlank { request.optString("path") }\n    require(inputPath.isNotBlank()) { "Conversion input path is missing during verification." }\n    val sourceFile = resolveExisting(root, inputPath) ?: error("Conversion source disappeared: $inputPath")\n    val sourceBytes = readDocumentBytes(context, sourceFile)\n    val inputFormat = AgentDocumentConversionMcp254.canonicalInputFormat(inputPath.substringAfterLast('.', ""))\n    val outputFormat =\n      AgentDocumentConversionMcp254.canonicalOutputFormat(\n        request.optString("output_format").ifBlank { request.optString("format") }\n      )\n    val plan = AgentDocumentConversionMcp254.conversionPlan(inputFormat, outputFormat)\n\n    if (plan == AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY) {\n      require(outputBytes.contentEquals(sourceBytes)) {\n        "Same-format conversion changed source bytes instead of preserving the document."\n      }\n      return Verification(\n        true,\n        "Same-format conversion was reopened and exact binary preservation was confirmed.",\n        JSONObject()\n          .put("bytes_read_back", outputBytes.size)\n          .put("source_bytes", sourceBytes.size)\n          .put("conversion_mode", "binary_copy")\n          .put("binary_equal", true),\n      )\n    }\n\n    val sourceText = extractSemanticText(context, inputPath, sourceBytes)\n    val outputName = "verified.$outputFormat"\n    val outputText = extractSemanticText(context, outputName, outputBytes)\n    val sourceCompact = AgentDocumentConversionMcp254.compactSemantic(sourceText)\n    val outputCompact = AgentDocumentConversionMcp254.compactSemantic(outputText)\n\n    if (sourceCompact.isNotBlank()) {\n      require(outputCompact.isNotBlank()) {\n        "Converted output has no readable text although the source contains text."\n      }\n      require(AgentDocumentConversionMcp254.preservesSemanticContent(sourceText, outputText)) {\n        "Converted output failed layout-insensitive semantic source-content preservation checks."\n      }\n    }\n\n    return Verification(\n      true,\n      "Converted file was reopened and layout-insensitive source text preservation was confirmed.",\n      JSONObject()\n        .put("bytes_read_back", outputBytes.size)\n        .put("source_content_chars", sourceText.length)\n        .put("output_content_chars", outputText.length)\n        .put("source_compact_chars", sourceCompact.length)\n        .put("output_compact_chars", outputCompact.length)\n        .put("conversion_mode", "text_round_trip"),\n    )\n  }\n'''
    text = replace_function(
        text,
        "  private fun verifyConversion(\n",
        "  private fun captureInputSemanticText(",
        verify_conversion,
        "verifyConversion",
    )

    old_extract = '''    return WorkspaceDocumentTextExtractor.extract(path.substringAfterLast('/'), bytes, 500_000, context).content\n'''
    new_extract = '''    return WorkspaceDocumentTextExtractor.extract(\n      path.substringAfterLast('/'),\n      bytes,\n      AgentDocumentConversionMcp254.MAX_CONVERSION_TEXT_BYTES * 2,\n      context,\n    ).content\n'''
    text = replace_once(text, old_extract, new_extract, "verification extraction budget")
    return text


def patch_office_backend(text: str) -> str:
    replacement = '''  private fun convertDocument(context: Context, root: DocumentFile, request: JSONObject): String {\n    // MCP254_DOCUMENT_CONVERSION_MATRIX\n    val input =\n      normalizeInputPath(\n        AgentDocumentConversionMcp254.normalizeWorkspaceInputPath(\n          request.optString("input_path").ifBlank { request.optString("path") }\n        )\n      )\n    val outputFormat =\n      AgentDocumentConversionMcp254.canonicalOutputFormat(\n        request.optString("output_format").ifBlank { request.optString("format") }\n      )\n    require(AgentDocumentConversionMcp254.supportedOutput(outputFormat)) {\n      "Supported output formats: txt, docx, pdf, html."\n    }\n\n    val rawInputExt = input.substringAfterLast('.', "").lowercase(Locale.US)\n    val inputFormat = AgentDocumentConversionMcp254.canonicalInputFormat(rawInputExt)\n    require(AgentDocumentConversionMcp254.supportedInput(inputFormat)) {\n      "Supported input formats: txt, docx, pdf, html, htm."\n    }\n\n    val sourceBytes = readBytes(context, root, input)\n    val base = input.substringAfterLast('/').substringBeforeLast('.').ifBlank { "converted" }\n    val output = normalizeOutputPath(request.optString("output_path"), outputFormat, base)\n    require(!AgentDocumentConversionMcp254.sameWorkspacePath(input, output)) {\n      "Conversion output must not overwrite the source file: $input"\n    }\n\n    val plan = AgentDocumentConversionMcp254.conversionPlan(inputFormat, outputFormat)\n    var sourceText: String? = null\n    val outputBytes =\n      when (plan) {\n        AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY -> sourceBytes\n        AgentDocumentConversionMcp254.ConversionPlan.TEXT_ROUND_TRIP -> {\n          val text =\n            when (inputFormat) {\n              "txt" -> sourceBytes.toString(Charsets.UTF_8)\n              "html" -> Jsoup.parse(sourceBytes.toString(Charsets.UTF_8)).text()\n              "docx", "pdf" -> {\n                val extracted =\n                  WorkspaceDocumentTextExtractor.extract(\n                    fileName = input.substringAfterLast('/'),\n                    bytes = sourceBytes,\n                    maxBytes = AgentDocumentConversionMcp254.MAX_CONVERSION_TEXT_BYTES,\n                    context = context,\n                  )\n                require(!extracted.truncated) {\n                  "Source document text exceeds the ${AgentDocumentConversionMcp254.MAX_CONVERSION_TEXT_BYTES}-byte conversion limit; refusing a partial conversion."\n                }\n                extracted.content\n              }\n              else -> error("Unsupported input format: $inputFormat")\n            }\n\n          require(AgentDocumentConversionMcp254.textFitsLimit(text)) {\n            "Source document text exceeds the ${AgentDocumentConversionMcp254.MAX_CONVERSION_TEXT_BYTES}-byte conversion limit; refusing a partial conversion."\n          }\n          if (inputFormat == "pdf" && sourceBytes.isNotEmpty()) {\n            require(AgentDocumentConversionMcp254.compactSemantic(text).isNotBlank()) {\n              "PDF contains no extractable text. Scanned/image-only PDF conversion requires OCR, which is not available in this text-oriented conversion path."\n            }\n          }\n          sourceText = text\n\n          when (outputFormat) {\n            "txt" -> text.toByteArray(Charsets.UTF_8)\n            "docx" -> buildDocx("", text)\n            "pdf" -> renderTextPdf("", text)\n            "html" -> {\n              val body =\n                text.replace("\\r\\n", "\\n")\n                  .split("\\n\\n")\n                  .filter { it.isNotBlank() }\n                  .joinToString("\\n") { "<p>${esc(it)}</p>" }\n              "<!DOCTYPE html><html><head><meta charset=\\"utf-8\\"><title>Converted Document</title></head><body>$body</body></html>"\n                .toByteArray(Charsets.UTF_8)\n            }\n            else -> error("Unsupported output format: $outputFormat")\n          }\n        }\n      }\n\n    val mime =\n      when (outputFormat) {\n        "txt" -> "text/plain"\n        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"\n        "pdf" -> "application/pdf"\n        else -> "text/html"\n      }\n    val written = writeBytes(context, root, output, mime, outputBytes)\n    return success(\n      "document_convert",\n      output,\n      written,\n      JSONObject()\n        .put("input_path", input)\n        .put("output_format", outputFormat)\n        .put(\n          "conversion_mode",\n          if (plan == AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY) "binary_copy" else "text_round_trip",\n        )\n        .apply { if (sourceText != null) put("source_text_chars", sourceText!!.length) }\n        .put(\n          "note",\n          if (plan == AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY) {\n            "Same-format conversion preserves the source bytes exactly."\n          } else {\n            "Text-oriented conversion may simplify complex layout, media, formulas, and advanced styling."\n          },\n        ),\n    )\n  }\n'''
    return replace_function(
        text,
        "  private fun convertDocument(context: Context, root: DocumentFile, request: JSONObject): String {",
        "  private fun updateContentTypes(",
        replacement,
        "convertDocument",
    )


def patch_agent_tooling(text: str) -> str:
    old = 'Converts TXT, DOCX, PDF, and HTML. output_path may be omitted; a collision-safe path is allocated. Success is returned only after reopening the output and verifying source-text preservation.'
    new = 'Converts TXT, DOCX, PDF, and HTML. A bare input filename is treated as file/<name>. output_path may be omitted for a collision-safe name. Same-format conversion makes a byte-preserving copy; cross-format conversion is text-oriented and refuses silent truncation. PDF output verification ignores layout line-wrap whitespace while still checking semantic preservation. Scanned/image-only PDF needs extractable text for cross-format conversion.'
    text = replace_once(text, old, new, "MCP253 conversion schema description")
    marker = '// MCP253_OFFICE_EVIDENCE_SCHEMA_GUIDANCE'
    require_count(text, marker, 1, "MCP253 schema marker")
    return text.replace(marker, marker + '\n  // MCP254_DOCUMENT_CONVERSION_SCHEMA_GUIDANCE', 1)


patch_file(AGENT / "AgentOfficeTruthGuard.kt", "MCP254_DOCUMENT_CONVERSION_SOURCE_PRESERVATION", patch_truth_guard)
patch_file(AGENT / "AgentOfficeDocumentSupport.kt", "MCP254_DOCUMENT_CONVERSION_MATRIX", patch_office_backend)
patch_file(AGENT / "AgentTooling.kt", "MCP254_DOCUMENT_CONVERSION_SCHEMA_GUIDANCE", patch_agent_tooling)

helper = AGENT / "AgentDocumentConversionMcp254.kt"
if not helper.exists():
    raise SystemExit("MCP254 fail-closed: AgentDocumentConversionMcp254.kt missing")
helper_text = helper.read_text(encoding="utf-8")
for marker in (
    "normalizeWorkspaceInputPath",
    "conversionPlan",
    "preservesSemanticContent",
    "BINARY_COPY",
    "MAX_CONVERSION_TEXT_BYTES",
):
    if marker not in helper_text:
        raise SystemExit(f"MCP254 fail-closed: helper marker missing: {marker}")

print("MCP254_DOCUMENT_CONVERSION_PATCH_PASS")
