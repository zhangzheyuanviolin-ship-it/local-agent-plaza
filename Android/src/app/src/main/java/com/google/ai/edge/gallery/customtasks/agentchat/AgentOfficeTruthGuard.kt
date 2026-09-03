/*
 * MCP252 Office truth guard.
 *
 * Evidence-driven hardening for the five MCP251 Office skills. This layer keeps the existing
 * Skill / file_workspace protocol intact while adding tolerant argument normalization, safe
 * operation inference, transactional rollback, and semantic read-back verification.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

object AgentOfficeTruthGuard {
  private const val AUTO_OPERATION = "office_auto"
  private const val MAX_VERIFY_BYTES = 40_000_000
  private const val META_SKILL = "_mcp252_skill"
  private const val META_RESOLUTION = "_mcp252_resolution"
  private const val META_RAW_OPERATION = "_mcp252_raw_operation"

  fun prepareCompatRequest(
    skillName: String,
    rawArguments: JSONObject,
    forcedOperation: String = "",
  ): JSONObject {
    val request = flattenNestedArguments(rawArguments)
    normalizeCommonAliases(request)
    normalizeSkillAliases(skillName, request)

    val rawOperation =
      forcedOperation.ifBlank {
        firstString(request, "operation", "action", "mode", "command", "task")
      }.trim()
    val resolved = resolveExplicitOperation(skillName = skillName, rawOperation = rawOperation)
    request.put(META_SKILL, skillName)
    request.put(META_RAW_OPERATION, rawOperation)
    if (resolved != null) {
      request.put("operation", resolved)
      request.put(META_RESOLUTION, if (forcedOperation.isNotBlank()) "forced" else "explicit")
      materializeSimpleModifyOperations(skillName, request, resolved)
    } else {
      request.put("operation", AUTO_OPERATION)
      request.put(META_RESOLUTION, "deferred_safe_inference")
    }
    return request
  }

  fun prepareConfiguredParameters(skillName: String, parameters: String): String {
    if (!isOfficeWorkspaceSkill(skillName)) return parameters
    val raw = runCatching { JSONObject(parameters.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    return prepareCompatRequest(skillName = skillName, rawArguments = raw).toString()
  }

  fun executeVerified(
    context: Context,
    root: DocumentFile,
    rawRequest: JSONObject,
  ): String {
    val request = JSONObject(rawRequest.toString())
    val skillName = request.optString(META_SKILL).ifBlank { inferSkillFromOperation(request.optString("operation")) }
    normalizeCommonAliases(request)
    normalizeSkillAliases(skillName, request)

    val originalOperation = request.optString("operation").trim().lowercase(Locale.US)
    val operation =
      if (originalOperation == AUTO_OPERATION || !AgentOfficeDocumentSupport.supports(originalOperation)) {
        inferOperationWithWorkspace(root = root, skillName = skillName, request = request)
      } else {
        originalOperation
      }
    request.put("operation", operation)
    if (request.optString(META_RESOLUTION).isBlank()) request.put(META_RESOLUTION, "backend_explicit")
    materializeSimpleModifyOperations(skillName, request, operation)

    val outputPath = expectedOutputPath(operation, request)
    val writesOutput = isWriteOperation(operation)
    val existingOutput = outputPath?.let { resolveExisting(root, it) }

    // A create call must never silently destroy an existing document. This specifically closes
    // the MCP251 failure mode where an ambiguous append/read was defaulted to create.
    if (operation in setOf("word_create", "xlsx_create", "pptx_create", "pdf_create") &&
      existingOutput != null && !request.optBoolean("overwrite", false)
    ) {
      return failure(
        operation,
        "Refusing to overwrite existing file $outputPath with a create operation.",
        "Use the modify operation for an existing Office file, or set overwrite=true only when replacement is explicitly intended.",
        request,
      )
    }

    val backup = if (writesOutput && existingOutput?.isFile == true) readDocumentBytes(context, existingOutput) else null
    val beforeInputText = captureInputSemanticText(context, root, operation, request)

    val backendRaw = AgentOfficeDocumentSupport.execute(context = context, root = root, request = request)
    val backend = runCatching { JSONObject(backendRaw) }.getOrElse {
      rollback(context, root, outputPath, backup)
      return failure(operation, "Office backend returned invalid JSON.", recoveryHint(operation), request)
    }
    if (backend.optString("status") != "succeeded") {
      if (writesOutput) rollback(context, root, outputPath, backup)
      if (!backend.has("recovery_hint")) backend.put("recovery_hint", recoveryHint(operation))
      backend.put("verified", false)
      backend.put("resolved_operation", operation)
      backend.put("resolution", request.optString(META_RESOLUTION))
      return backend.toString()
    }

    val resultPath = backend.optString("path").ifBlank { outputPath.orEmpty() }
    val verification =
      runCatching {
        verifyResult(
          context = context,
          root = root,
          operation = operation,
          request = request,
          resultPath = resultPath,
          beforeInputText = beforeInputText,
          backend = backend,
        )
      }.getOrElse { Verification(false, it.message ?: "Unknown verification failure.", JSONObject()) }

    if (!verification.ok) {
      if (writesOutput) rollback(context, root, resultPath.ifBlank { outputPath }, backup)
      return JSONObject()
        .put("status", "failed")
        .put("operation", operation)
        .put("path", resultPath)
        .put("verified", false)
        .put("error", "Output verification failed: ${verification.message}")
        .put("recovery_hint", recoveryHint(operation))
        .put("rolled_back", writesOutput)
        .put("resolved_operation", operation)
        .put("resolution", request.optString(META_RESOLUTION))
        .put("verification", verification.details)
        .toString()
    }

    backend.put("verified", true)
    backend.put("resolved_operation", operation)
    backend.put("resolution", request.optString(META_RESOLUTION))
    backend.put("verification", verification.details.put("message", verification.message))
    return backend.toString()
  }

  private data class Verification(val ok: Boolean, val message: String, val details: JSONObject)

  private fun flattenNestedArguments(raw: JSONObject): JSONObject {
    val out = JSONObject(raw.toString())
    val nested =
      when (val value = raw.opt("parameters")) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
      }
    if (nested != null) {
      val keys = nested.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (!out.has(key) || out.isNull(key) || out.optString(key).isBlank()) out.put(key, nested.opt(key))
      }
    }
    return out
  }

  private fun normalizeCommonAliases(request: JSONObject) {
    copyAlias(request, "input_path", "inputPath", "source_path", "sourcePath", "source", "input", "existing_path")
    copyAlias(request, "output_path", "outputPath", "destination_path", "destinationPath", "dest_path", "target_path", "result_path", "save_path")
    copyAlias(request, "title", "heading", "document_title", "doc_title", "name")
    copyAlias(request, "content", "text", "body", "正文", "description", "paragraph", "paragraph_text")
    copyAlias(request, "operations", "ops", "edits", "changes")
    copyAlias(request, "rows", "data", "table", "values")
    copyAlias(request, "slides", "pages_data", "slide_data")
    copyAlias(request, "pages", "page_numbers", "page_range")
    copyAlias(request, "output_format", "outputFormat", "format", "target_format")

    val genericPath = firstString(request, "path", "file_path", "filePath", "filename", "file_name", "file")
    if (genericPath.isNotBlank() && !request.has("path")) request.put("path", genericPath)

    normalizeArrayString(request, "operations")
    normalizeArrayString(request, "rows")
    normalizeArrayString(request, "slides")
    normalizeArrayString(request, "input_paths")
  }

  private fun normalizeSkillAliases(skillName: String, request: JSONObject) {
    when (skillName) {
      WORD_DOCUMENT_SKILL_NAME -> {
        copyAlias(request, "content", "append_text", "append", "text_to_append")
        if (!request.has("operations")) {
          val oldText = firstString(request, "old", "old_text", "find", "search_text")
          val newText = firstString(request, "new", "new_text", "replace", "replacement")
          if (oldText.isNotBlank()) {
            request.put(
              "operations",
              JSONArray().put(JSONObject().put("action", "replace_text").put("params", JSONObject().put("old", oldText).put("new", newText))),
            )
          }
        }
      }
      EXCEL_WORKBOOK_SKILL_NAME -> {
        copyAlias(request, "sheet_name", "sheet", "sheetName", "worksheet")
      }
      POWERPOINT_PRESENTATION_SKILL_NAME -> {
        copyAlias(request, "content", "slide_content", "body")
      }
      PDF_DOCUMENT_SKILL_NAME -> {
        copyAlias(request, "degrees", "rotation", "angle")
        copyAlias(request, "input_paths", "files", "sources", "pdfs")
      }
    }
  }

  private fun resolveExplicitOperation(skillName: String, rawOperation: String): String? {
    if (rawOperation.isBlank()) return null
    val op = rawOperation.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
    return when (skillName) {
      WORD_DOCUMENT_SKILL_NAME -> when (op) {
        "create", "new", "write", "word_create", "create_docx" -> "word_create"
        "read", "open", "get", "word_read", "read_docx" -> "word_read"
        "modify", "edit", "update", "append", "add", "word_modify", "modify_docx" -> "word_modify"
        else -> null
      }
      EXCEL_WORKBOOK_SKILL_NAME -> when (op) {
        "create", "new", "write", "xlsx_create", "create_xlsx" -> "xlsx_create"
        "read", "open", "get", "xlsx_read", "read_xlsx" -> "xlsx_read"
        "modify", "edit", "update", "append", "add", "xlsx_modify", "modify_xlsx" -> "xlsx_modify"
        else -> null
      }
      POWERPOINT_PRESENTATION_SKILL_NAME -> when (op) {
        "create", "new", "write", "pptx_create", "create_pptx" -> "pptx_create"
        "read", "open", "get", "pptx_read", "read_pptx" -> "pptx_read"
        "modify", "edit", "update", "append", "add", "pptx_modify", "modify_pptx" -> "pptx_modify"
        else -> null
      }
      PDF_DOCUMENT_SKILL_NAME -> when (op) {
        "create", "new", "write", "pdf_create", "create_pdf" -> "pdf_create"
        "read", "open", "get", "pdf_read", "read_pdf" -> "pdf_read"
        "merge", "combine", "pdf_merge" -> "pdf_merge"
        "extract", "extract_pages", "split", "pdf_extract_pages" -> "pdf_extract_pages"
        "reorder", "reorder_pages", "pdf_reorder_pages" -> "pdf_reorder_pages"
        "delete", "delete_pages", "remove_pages", "pdf_delete_pages" -> "pdf_delete_pages"
        "rotate", "rotate_pages", "pdf_rotate_pages" -> "pdf_rotate_pages"
        else -> null
      }
      DOCUMENT_CONVERT_SKILL_NAME -> "document_convert"
      else -> null
    }
  }

  private fun inferOperationWithWorkspace(root: DocumentFile, skillName: String, request: JSONObject): String {
    if (skillName == DOCUMENT_CONVERT_SKILL_NAME) return "document_convert"
    val genericPath = firstString(request, "input_path", "path", "output_path")
    val exists = genericPath.isNotBlank() && resolveExisting(root, genericPath) != null
    val hasContent = request.optString("content").isNotBlank() || request.optString("title").isNotBlank()
    val hasOperations = request.optJSONArray("operations")?.length()?.let { it > 0 } == true
    val hasRows = request.optJSONArray("rows")?.length()?.let { it > 0 } == true
    val hasSlides = request.optJSONArray("slides")?.length()?.let { it > 0 } == true

    val operation = when (skillName) {
      WORD_DOCUMENT_SKILL_NAME -> when {
        hasOperations -> "word_modify"
        exists && hasContent -> "word_modify"
        exists -> "word_read"
        hasContent -> "word_create"
        else -> ""
      }
      EXCEL_WORKBOOK_SKILL_NAME -> when {
        hasOperations -> "xlsx_modify"
        exists && (hasRows || request.has("cell") || request.has("value")) -> "xlsx_modify"
        exists -> "xlsx_read"
        hasRows || request.optJSONArray("sheets") != null -> "xlsx_create"
        else -> ""
      }
      POWERPOINT_PRESENTATION_SKILL_NAME -> when {
        hasOperations -> "pptx_modify"
        exists && (hasSlides || hasContent) -> "pptx_modify"
        exists -> "pptx_read"
        hasSlides || hasContent -> "pptx_create"
        else -> ""
      }
      PDF_DOCUMENT_SKILL_NAME -> when {
        request.optJSONArray("input_paths")?.length()?.let { it >= 2 } == true -> "pdf_merge"
        request.has("degrees") && exists -> "pdf_rotate_pages"
        request.has("pages") && exists -> "pdf_extract_pages"
        exists && !hasContent -> "pdf_read"
        hasContent -> "pdf_create"
        else -> ""
      }
      else -> ""
    }
    require(operation.isNotBlank()) {
      "Could not safely infer the Office operation from the supplied arguments."
    }
    request.put(META_RESOLUTION, "workspace_safe_inference")
    return operation
  }

  private fun materializeSimpleModifyOperations(skillName: String, request: JSONObject, operation: String) {
    if (request.optJSONArray("operations")?.length()?.let { it > 0 } == true) return
    when {
      skillName == WORD_DOCUMENT_SKILL_NAME && operation == "word_modify" -> {
        val ops = JSONArray()
        val title = request.optString("title")
        val content = request.optString("content")
        if (title.isNotBlank()) ops.put(JSONObject().put("action", "add_heading").put("params", JSONObject().put("text", title).put("level", 1)))
        if (content.isNotBlank()) ops.put(JSONObject().put("action", "add_paragraph").put("params", JSONObject().put("text", content)))
        if (ops.length() > 0) request.put("operations", ops)
      }
      skillName == EXCEL_WORKBOOK_SKILL_NAME && operation == "xlsx_modify" -> {
        val ops = JSONArray()
        val rows = request.optJSONArray("rows")
        if (rows != null) {
          for (i in 0 until rows.length()) {
            val values = rows.optJSONArray(i) ?: JSONArray().put(rows.opt(i))
            ops.put(JSONObject().put("action", "add_row").put("params", JSONObject().put("values", values).apply {
              request.optString("sheet_name").takeIf { it.isNotBlank() }?.let { put("sheet", it) }
            }))
          }
        }
        val cell = firstString(request, "cell", "cell_ref", "range")
        if (cell.isNotBlank() && request.has("value")) {
          ops.put(JSONObject().put("action", "set_cell").put("params", JSONObject().put("cell", cell).put("value", request.opt("value"))))
        }
        if (ops.length() > 0) request.put("operations", ops)
      }
      skillName == POWERPOINT_PRESENTATION_SKILL_NAME && operation == "pptx_modify" -> {
        val ops = JSONArray()
        val slides = request.optJSONArray("slides")
        if (slides != null) {
          for (i in 0 until slides.length()) {
            val slide = slides.optJSONObject(i) ?: continue
            ops.put(JSONObject().put("action", "add_slide").put("params", JSONObject().put("title", slide.optString("title")).put("content", slide.optString("content"))))
          }
        } else if (request.optString("title").isNotBlank() || request.optString("content").isNotBlank()) {
          ops.put(JSONObject().put("action", "add_slide").put("params", JSONObject().put("title", request.optString("title")).put("content", request.optString("content"))))
        }
        if (ops.length() > 0) request.put("operations", ops)
      }
    }
  }

  private fun verifyResult(
    context: Context,
    root: DocumentFile,
    operation: String,
    request: JSONObject,
    resultPath: String,
    beforeInputText: String?,
    backend: JSONObject,
  ): Verification {
    if (!isWriteOperation(operation)) {
      if (operation.endsWith("_read")) {
        val content = backend.optString("content")
        return Verification(true, "Read operation completed and backend content was returned.", JSONObject().put("content_chars", content.length))
      }
      return Verification(true, "Non-writing operation completed.", JSONObject())
    }
    require(resultPath.isNotBlank()) { "Backend did not return an output path." }
    val file = resolveExisting(root, resultPath) ?: error("Output file does not exist after backend success: $resultPath")
    require(file.isFile) { "Output path is not a file: $resultPath" }
    val bytes = readDocumentBytes(context, file)
    require(bytes.isNotEmpty()) { "Output file is empty." }

    return when {
      operation.startsWith("word_") -> verifyWord(context, resultPath, bytes, request, beforeInputText)
      operation.startsWith("xlsx_") -> verifyXlsx(context, resultPath, bytes, request, beforeInputText)
      operation.startsWith("pptx_") -> verifyPptx(bytes, request, beforeInputText)
      operation.startsWith("pdf_") -> verifyPdf(context, bytes, operation, request, backend)
      operation == "document_convert" -> verifyConversion(context, root, bytes, request)
      else -> Verification(true, "Output exists and is non-empty.", JSONObject().put("bytes_read_back", bytes.size))
    }
  }

  private fun verifyWord(
    context: Context,
    path: String,
    bytes: ByteArray,
    request: JSONObject,
    beforeInputText: String?,
  ): Verification {
    val extracted = WorkspaceDocumentTextExtractor.extract(path.substringAfterLast('/'), bytes, 500_000, context).content
    val expected = mutableListOf<String>()
    request.optString("title").takeIf { it.isNotBlank() }?.let(expected::add)
    request.optString("content").takeIf { it.isNotBlank() }?.let(expected::add)
    collectWordOperationExpectedText(request.optJSONArray("operations")).forEach(expected::add)
    require(expected.isEmpty() || semanticContainsAll(extracted, expected)) { "DOCX read-back does not contain the requested text." }
    if (request.optString("operation") == "word_modify" && !beforeInputText.isNullOrBlank() && isAdditiveWordModify(request)) {
      require(semanticPreserves(extracted, beforeInputText)) { "DOCX modification lost pre-existing document content." }
    }
    return Verification(
      true,
      "DOCX reopened and requested semantic content was confirmed.",
      JSONObject().put("bytes_read_back", bytes.size).put("content_chars", extracted.length).put("expected_fragments", expected.size),
    )
  }

  private fun verifyXlsx(
    context: Context,
    path: String,
    bytes: ByteArray,
    request: JSONObject,
    beforeInputText: String?,
  ): Verification {
    val entries = unzipEntries(bytes)
    require(entries.containsKey("xl/workbook.xml")) { "XLSX workbook.xml is missing." }
    require(entries.keys.any { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }) { "XLSX has no worksheet XML." }
    val extracted = WorkspaceDocumentTextExtractor.extract(path.substringAfterLast('/'), bytes, 500_000, context).content
    val expected = collectXlsxExpectedText(request)
    val xml = entries.filterKeys { it.endsWith(".xml") }.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
    for (value in expected.filter { !it.startsWith("=") }) {
      require(semanticContains(extracted, value) || semanticContains(xml, value)) { "XLSX read-back is missing expected value: ${value.take(80)}" }
    }
    for (formula in expected.filter { it.startsWith("=") }) {
      require(xml.contains(formula.removePrefix("="))) { "XLSX read-back is missing expected formula: ${formula.take(80)}" }
    }
    if (request.optString("operation") == "xlsx_modify" && !beforeInputText.isNullOrBlank()) {
      require(extracted.isNotBlank()) { "XLSX modification produced no readable worksheet content." }
    }
    return Verification(true, "XLSX reopened and workbook/worksheet content was confirmed.", JSONObject().put("bytes_read_back", bytes.size).put("content_chars", extracted.length).put("expected_fragments", expected.size))
  }

  private fun verifyPptx(bytes: ByteArray, request: JSONObject, beforeInputText: String?): Verification {
    val entries = unzipEntries(bytes)
    require(entries.containsKey("ppt/presentation.xml")) { "PPTX presentation.xml is missing." }
    val slides = entries.filterKeys { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
    require(slides.isNotEmpty()) { "PPTX has no slide XML." }
    val text = slides.values.joinToString("\n") { xmlVisibleText(it.toString(Charsets.UTF_8)) }
    val expected = collectPptxExpectedText(request)
    require(expected.isEmpty() || semanticContainsAll(text, expected)) { "PPTX read-back does not contain the requested slide text." }
    if (request.optString("operation") == "pptx_modify" && !beforeInputText.isNullOrBlank() && isAdditivePptModify(request)) {
      require(semanticPreserves(text, beforeInputText)) { "PPTX modification lost pre-existing slide text." }
    }
    return Verification(true, "PPTX reopened and requested slide content was confirmed.", JSONObject().put("bytes_read_back", bytes.size).put("slide_count", slides.size).put("content_chars", text.length).put("expected_fragments", expected.size))
  }

  private fun verifyPdf(
    context: Context,
    bytes: ByteArray,
    operation: String,
    request: JSONObject,
    backend: JSONObject,
  ): Verification {
    PDFBoxResourceLoader.init(context)
    var pageCount = 0
    PDDocument.load(ByteArrayInputStream(bytes)).use { pageCount = it.numberOfPages }
    require(pageCount > 0) { "PDF reopened with zero pages." }
    val expectedCount = backend.optInt("page_count", 0)
    if (expectedCount > 0) require(pageCount == expectedCount) { "PDF page count mismatch: expected $expectedCount, got $pageCount." }
    if (operation == "pdf_create") {
      val expected = listOf(request.optString("title"), request.optString("content")).filter { it.isNotBlank() }
      if (expected.isNotEmpty()) {
        val extracted = WorkspaceDocumentTextExtractor.extract("verified.pdf", bytes, 500_000, context).content
        require(semanticContainsAll(extracted, expected)) { "PDF read-back does not contain the requested text." }
      }
    }
    return Verification(true, "PDF reopened successfully and page/content checks passed.", JSONObject().put("bytes_read_back", bytes.size).put("page_count", pageCount))
  }

  private fun verifyConversion(
    context: Context,
    root: DocumentFile,
    outputBytes: ByteArray,
    request: JSONObject,
  ): Verification {
    val inputPath = firstString(request, "input_path", "path")
    require(inputPath.isNotBlank()) { "Conversion input path is missing during verification." }
    val sourceFile = resolveExisting(root, inputPath) ?: error("Conversion source disappeared: $inputPath")
    val sourceBytes = readDocumentBytes(context, sourceFile)
    val sourceText = extractSemanticText(context, inputPath, sourceBytes)
    val format = request.optString("output_format").trim().lowercase(Locale.US).removePrefix(".").let {
      when (it) { "word" -> "docx"; "text" -> "txt"; "htm" -> "html"; else -> it }
    }
    val outputName = "verified.${if (format.isBlank()) "txt" else format}"
    val outputText = extractSemanticText(context, outputName, outputBytes)
    if (normalizeSemantic(sourceText).isNotBlank()) {
      require(normalizeSemantic(outputText).isNotBlank()) { "Converted output has no readable text although the source contains text." }
      require(semanticPreserves(outputText, sourceText)) { "Converted output failed semantic source-content preservation checks." }
    }
    return Verification(true, "Converted file was reopened and source text preservation was confirmed.", JSONObject().put("bytes_read_back", outputBytes.size).put("source_content_chars", sourceText.length).put("output_content_chars", outputText.length))
  }

  private fun captureInputSemanticText(context: Context, root: DocumentFile, operation: String, request: JSONObject): String? {
    if (!operation.endsWith("_modify")) return null
    val inputPath = firstString(request, "input_path", "path")
    if (inputPath.isBlank()) return null
    val file = resolveExisting(root, inputPath) ?: return null
    return runCatching { extractSemanticText(context, inputPath, readDocumentBytes(context, file)) }.getOrNull()
  }

  private fun extractSemanticText(context: Context, path: String, bytes: ByteArray): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
    if (ext == "pptx") return unzipEntries(bytes).filterKeys { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }.values.joinToString("\n") { xmlVisibleText(it.toString(Charsets.UTF_8)) }
    if (ext in setOf("html", "htm")) return org.jsoup.Jsoup.parse(bytes.toString(Charsets.UTF_8)).text()
    return WorkspaceDocumentTextExtractor.extract(path.substringAfterLast('/'), bytes, 500_000, context).content
  }

  private fun expectedOutputPath(operation: String, request: JSONObject): String? {
    val rawOutput = request.optString("output_path")
    val input = firstString(request, "input_path", "path")
    val ext = when {
      operation.startsWith("word_") -> "docx"
      operation.startsWith("xlsx_") -> "xlsx"
      operation.startsWith("pptx_") -> "pptx"
      operation.startsWith("pdf_") -> "pdf"
      operation == "document_convert" -> request.optString("output_format").removePrefix(".").let { if (it == "word") "docx" else if (it == "text") "txt" else it }
      else -> ""
    }
    val fallback = when {
      operation.endsWith("_modify") && input.isNotBlank() -> input
      operation == "document_convert" && input.isNotBlank() && ext.isNotBlank() -> "file/${input.substringAfterLast('/').substringBeforeLast('.')}.${ext}"
      operation == "word_create" -> "file/document.docx"
      operation == "xlsx_create" -> "file/workbook.xlsx"
      operation == "pptx_create" -> "file/presentation.pptx"
      operation == "pdf_create" -> "file/document.pdf"
      else -> input
    }
    val path = rawOutput.ifBlank { fallback }
    if (path.isBlank() || ext.isBlank()) return path.ifBlank { null }
    return normalizeExpectedPath(path, ext)
  }

  private fun normalizeExpectedPath(path: String, extension: String): String {
    var p = path.replace('\\', '/').trim().trimStart('/')
    if (!p.lowercase(Locale.US).endsWith(".$extension")) p += ".$extension"
    if (!p.startsWith("file/")) p = "file/$p"
    return p
  }

  private fun isWriteOperation(operation: String): Boolean =
    operation !in setOf("word_read", "xlsx_read", "pptx_read", "pdf_read")

  private fun rollback(context: Context, root: DocumentFile, path: String?, backup: ByteArray?) {
    if (path.isNullOrBlank()) return
    runCatching {
      val current = resolveExisting(root, path)
      if (backup == null) {
        current?.delete()
      } else if (current != null) {
        context.contentResolver.openOutputStream(current.uri, "wt")?.use { it.write(backup) }
      }
    }
  }

  private fun resolveExisting(root: DocumentFile, rawPath: String): DocumentFile? {
    val path = rawPath.replace('\\', '/').trim().trimStart('/')
    if (path.isBlank() || path.split('/').any { it == ".." }) return null
    var current = root
    for (segment in path.split('/').filter { it.isNotBlank() && it != "." }) {
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  private fun readDocumentBytes(context: Context, file: DocumentFile): ByteArray {
    require(file.length() <= MAX_VERIFY_BYTES) { "Verification file exceeds $MAX_VERIFY_BYTES bytes." }
    return context.contentResolver.openInputStream(file.uri)?.use { input ->
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      var total = 0
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_VERIFY_BYTES) { "Verification read exceeds $MAX_VERIFY_BYTES bytes." }
        out.write(buffer, 0, count)
      }
      out.toByteArray()
    } ?: error("Could not reopen output file for verification.")
  }

  private fun unzipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) result[entry.name] = zip.readBytes()
        zip.closeEntry()
      }
    }
    return result
  }

  private fun collectWordOperationExpectedText(operations: JSONArray?): List<String> {
    if (operations == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until operations.length()) {
      val op = operations.optJSONObject(i) ?: continue
      val params = op.optJSONObject("params") ?: JSONObject()
      when (op.optString("action")) {
        "add_paragraph", "add_heading" -> params.optString("text").takeIf { it.isNotBlank() }?.let(out::add)
        "replace_text" -> params.optString("new").takeIf { it.isNotBlank() }?.let(out::add)
        "update_table_cell" -> params.optString("text").takeIf { it.isNotBlank() }?.let(out::add)
        "add_table" -> flattenJsonArray(params.optJSONArray("rows")).forEach(out::add)
      }
    }
    return out
  }

  private fun collectXlsxExpectedText(request: JSONObject): List<String> {
    val out = mutableListOf<String>()
    flattenJsonArray(request.optJSONArray("rows")).forEach(out::add)
    val sheets = request.optJSONArray("sheets")
    if (sheets != null) for (i in 0 until sheets.length()) flattenJsonArray(sheets.optJSONObject(i)?.optJSONArray("rows")).forEach(out::add)
    val ops = request.optJSONArray("operations")
    if (ops != null) for (i in 0 until ops.length()) {
      val op = ops.optJSONObject(i) ?: continue
      val params = op.optJSONObject("params") ?: JSONObject()
      when (op.optString("action")) {
        "set_cell" -> params.opt("value")?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let(out::add)
        "set_formula" -> params.optString("formula").takeIf { it.isNotBlank() }?.let { out += if (it.startsWith("=")) it else "=$it" }
        "add_row" -> flattenJsonArray(params.optJSONArray("values")).forEach(out::add)
        "add_sheet", "rename_sheet" -> params.optString("name").takeIf { it.isNotBlank() }?.let(out::add)
      }
    }
    return out.distinct()
  }

  private fun collectPptxExpectedText(request: JSONObject): List<String> {
    val out = mutableListOf<String>()
    request.optString("title").takeIf { it.isNotBlank() }?.let(out::add)
    request.optString("content").takeIf { it.isNotBlank() }?.let(out::add)
    val slides = request.optJSONArray("slides")
    if (slides != null) for (i in 0 until slides.length()) {
      val slide = slides.optJSONObject(i) ?: continue
      slide.optString("title").takeIf { it.isNotBlank() }?.let(out::add)
      slide.optString("content").takeIf { it.isNotBlank() }?.let(out::add)
    }
    val ops = request.optJSONArray("operations")
    if (ops != null) for (i in 0 until ops.length()) {
      val op = ops.optJSONObject(i) ?: continue
      val params = op.optJSONObject("params") ?: JSONObject()
      when (op.optString("action")) {
        "replace_text", "update_shape_text", "add_text_box" -> firstString(params, "new", "text", "content").takeIf { it.isNotBlank() }?.let(out::add)
        "add_slide" -> {
          params.optString("title").takeIf { it.isNotBlank() }?.let(out::add)
          params.optString("content").takeIf { it.isNotBlank() }?.let(out::add)
        }
      }
    }
    return out.distinct()
  }

  private fun isAdditiveWordModify(request: JSONObject): Boolean {
    val ops = request.optJSONArray("operations") ?: return false
    for (i in 0 until ops.length()) {
      when (ops.optJSONObject(i)?.optString("action")) {
        "replace_text", "update_table_cell" -> return false
      }
    }
    return true
  }

  private fun isAdditivePptModify(request: JSONObject): Boolean {
    val ops = request.optJSONArray("operations") ?: return false
    for (i in 0 until ops.length()) {
      when (ops.optJSONObject(i)?.optString("action")) {
        "replace_text", "update_shape_text", "delete_slide" -> return false
      }
    }
    return true
  }

  private fun semanticContainsAll(actual: String, expected: List<String>): Boolean = expected.all { semanticContains(actual, it) }

  private fun semanticContains(actual: String, expected: String): Boolean {
    val a = normalizeSemantic(actual)
    val e = normalizeSemantic(expected)
    if (e.isBlank()) return true
    if (a.contains(e, ignoreCase = true)) return true
    return sampleFragments(e).all { a.contains(it, ignoreCase = true) }
  }

  private fun semanticPreserves(actual: String, before: String): Boolean {
    val b = normalizeSemantic(before)
    if (b.isBlank()) return true
    return sampleFragments(b).count { normalizeSemantic(actual).contains(it, ignoreCase = true) } >= sampleFragments(b).size.coerceAtMost(2)
  }

  private fun sampleFragments(text: String): List<String> {
    val t = normalizeSemantic(text)
    if (t.length <= 80) return listOf(t)
    val width = 48.coerceAtMost(t.length / 3)
    val middle = (t.length / 2 - width / 2).coerceAtLeast(0)
    return listOf(t.take(width), t.substring(middle, (middle + width).coerceAtMost(t.length)), t.takeLast(width)).filter { it.isNotBlank() }.distinct()
  }

  private fun normalizeSemantic(value: String): String = value.replace(Regex("\\s+"), " ").trim()

  private fun xmlVisibleText(xml: String): String =
    Regex("<(?:a:t|w:t|t)(?:\\s[^>]*)?>(.*?)</(?:a:t|w:t|t)>", setOf(RegexOption.DOT_MATCHES_ALL))
      .findAll(xml)
      .joinToString("\n") { xmlUnescape(it.groupValues[1].replace(Regex("<[^>]+>"), "")) }

  private fun xmlUnescape(value: String): String = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

  private fun flattenJsonArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
      when (val value = array.opt(i)) {
        is JSONArray -> out += flattenJsonArray(value)
        JSONObject.NULL, null -> Unit
        else -> value.toString().takeIf { it.isNotBlank() }?.let(out::add)
      }
    }
    return out
  }

  private fun normalizeArrayString(request: JSONObject, key: String) {
    val raw = request.optString(key)
    if (request.optJSONArray(key) == null && raw.trim().startsWith("[")) {
      runCatching { JSONArray(raw) }.getOrNull()?.let { request.put(key, it) }
    }
  }

  private fun copyAlias(request: JSONObject, canonical: String, vararg aliases: String) {
    if (request.has(canonical) && !request.isNull(canonical)) {
      val value = request.opt(canonical)
      if (value !is String || value.isNotBlank()) return
    }
    for (alias in aliases) {
      if (request.has(alias) && !request.isNull(alias)) {
        val value = request.opt(alias)
        if (value !is String || value.isNotBlank()) {
          request.put(canonical, value)
          return
        }
      }
    }
  }

  private fun firstString(request: JSONObject, vararg names: String): String {
    for (name in names) request.optString(name).trim().takeIf { it.isNotBlank() }?.let { return it }
    return ""
  }

  private fun inferSkillFromOperation(operation: String): String = when {
    operation.startsWith("word_") -> WORD_DOCUMENT_SKILL_NAME
    operation.startsWith("xlsx_") -> EXCEL_WORKBOOK_SKILL_NAME
    operation.startsWith("pptx_") -> POWERPOINT_PRESENTATION_SKILL_NAME
    operation.startsWith("pdf_") -> PDF_DOCUMENT_SKILL_NAME
    operation == "document_convert" -> DOCUMENT_CONVERT_SKILL_NAME
    else -> ""
  }

  private fun recoveryHint(operation: String): String = when {
    operation.startsWith("word_") -> "For create provide title/content; for read provide input_path; for edit use modify with input_path and operations, or provide content to append."
    operation.startsWith("xlsx_") -> "For create provide rows/sheets; for read provide input_path; for edit provide input_path and operations such as set_cell/add_row."
    operation.startsWith("pptx_") -> "For create provide slides or title/content; for read provide input_path; for edit provide input_path and operations or slide content to append."
    operation.startsWith("pdf_") -> "Provide the PDF input/output paths plus the fields required by the requested action; create requires content, page actions require pages/degrees as appropriate."
    operation == "document_convert" -> "Provide input_path and output_format; the converted file is reported successful only after source-content read-back verification passes."
    else -> "Check the Office arguments and retry with a workspace-relative file path."
  }

  private fun failure(operation: String, message: String, hint: String, request: JSONObject): String =
    JSONObject()
      .put("status", "failed")
      .put("operation", operation)
      .put("verified", false)
      .put("error", message)
      .put("recovery_hint", hint)
      .put("resolution", request.optString(META_RESOLUTION))
      .toString()
}
