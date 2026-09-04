package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP253 evidence-driven Office compatibility layer.
 *
 * This class exists because the MCP252 field logs showed two concrete small-model dialects that
 * were rejected by the stricter Truth Guard:
 *  1. a root create directive wrapped in operations, and
 *  2. nested modify actions expressed with type/operation + direct content instead of action + params.
 *
 * It also removes fixed cross-task create filenames when output_path is omitted. Explicit paths
 * remain authoritative and are still protected by the Truth Guard's no-overwrite policy.
 */
object AgentOfficeMcp253Compat {
  const val META_AUTO_OUTPUT_PATH = "_mcp253_auto_output_path"
  const val META_NORMALIZED_OPERATIONS = "_mcp253_normalized_operations"
  const val META_PROMOTED_ROOT_OPERATION = "_mcp253_promoted_root_operation"

  fun normalizeBeforeRouting(skillName: String, request: JSONObject) {
    promoteWrappedRootOperation(skillName, request)
    normalizeModifyOperations(skillName, request)
  }

  /**
   * MCP252 logs 135415 and 135510 showed create requests as operations:["create"] and
   * operations:[{"operation":"create"}]. Promote those exact shapes to a root operation before
   * the Truth Guard decides that any operations array implies modify.
   */
  private fun promoteWrappedRootOperation(skillName: String, request: JSONObject) {
    if (firstNonBlank(request, "operation", "action", "mode", "command", "task").isNotBlank()) return
    val operations = request.optJSONArray("operations") ?: return
    if (operations.length() != 1) return

    val single = operations.opt(0)
    var directive = ""
    var objectPayload: JSONObject? = null
    when (single) {
      is String -> directive = single
      is JSONObject -> {
        objectPayload = single
        directive = firstNonBlank(single, "operation", "action", "type", "mode", "command", "task")
      }
    }
    val normalizedDirective = normalizeToken(directive)
    if (!isRootDirective(skillName, normalizedDirective)) return

    if (objectPayload != null) {
      val keys = objectPayload.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (key in setOf("operation", "action", "type", "mode", "command", "task")) continue
        if (!request.has(key) || request.isNull(key) || request.optString(key).isBlank()) {
          request.put(key, objectPayload.opt(key))
        }
      }
    }
    request.put("operation", normalizedDirective)
    request.remove("operations")
    request.put(META_PROMOTED_ROOT_OPERATION, true)
  }

  private fun isRootDirective(skillName: String, directive: String): Boolean {
    if (directive.isBlank()) return false
    return when (skillName) {
      WORD_DOCUMENT_SKILL_NAME,
      EXCEL_WORKBOOK_SKILL_NAME,
      POWERPOINT_PRESENTATION_SKILL_NAME ->
        directive in setOf("create", "new", "write", "read", "open", "get", "modify", "edit", "update")
      PDF_DOCUMENT_SKILL_NAME ->
        directive in setOf(
          "create", "new", "write", "read", "open", "get", "merge", "combine", "extract",
          "extract_pages", "split", "reorder", "reorder_pages", "delete", "delete_pages",
          "remove_pages", "rotate", "rotate_pages",
        )
      DOCUMENT_CONVERT_SKILL_NAME -> directive in setOf("convert", "document_convert")
      else -> false
    }
  }

  /**
   * Canonicalize the nested action envelope used by Word/Excel/PPTX backends. Unknown actions are
   * retained by name so backend errors remain diagnostic instead of becoming a blank action.
   */
  private fun normalizeModifyOperations(skillName: String, request: JSONObject) {
    if (skillName !in setOf(WORD_DOCUMENT_SKILL_NAME, EXCEL_WORKBOOK_SKILL_NAME, POWERPOINT_PRESENTATION_SKILL_NAME)) return
    val source = request.optJSONArray("operations") ?: return
    if (source.length() == 0) return
    val normalized = JSONArray()
    var changed = false
    for (i in 0 until source.length()) {
      val raw = source.opt(i)
      val op =
        when (raw) {
          is JSONObject -> normalizeNestedOperation(skillName, raw)
          is String -> normalizeNestedOperation(skillName, JSONObject().put("action", raw))
          else -> null
        }
      if (op != null) {
        normalized.put(op)
        if (raw !is JSONObject || raw.toString() != op.toString()) changed = true
      } else {
        normalized.put(raw)
      }
    }
    if (changed) {
      request.put("operations", normalized)
      request.put(META_NORMALIZED_OPERATIONS, true)
    }
  }

  private fun normalizeNestedOperation(skillName: String, raw: JSONObject): JSONObject {
    val rawAction = firstNonBlank(raw, "action", "type", "operation", "op", "command", "mode")
    val action = canonicalAction(skillName, normalizeToken(rawAction))
    val params = JSONObject(raw.optJSONObject("params")?.toString() ?: "{}")

    when (skillName) {
      WORD_DOCUMENT_SKILL_NAME -> normalizeWordParams(action, raw, params)
      EXCEL_WORKBOOK_SKILL_NAME -> normalizeExcelParams(action, raw, params)
      POWERPOINT_PRESENTATION_SKILL_NAME -> normalizePptParams(action, raw, params)
    }
    return JSONObject().put("action", action).put("params", params)
  }

  private fun canonicalAction(skillName: String, action: String): String {
    return when (skillName) {
      WORD_DOCUMENT_SKILL_NAME -> when (action) {
        "append", "add", "append_paragraph", "add_paragraph", "paragraph" -> "add_paragraph"
        "heading", "append_heading", "add_heading" -> "add_heading"
        "replace", "replace_text", "find_replace" -> "replace_text"
        "page_break", "add_page_break", "break_page" -> "add_page_break"
        "table", "add_table", "insert_table" -> "add_table"
        "update_cell", "update_table_cell", "set_table_cell" -> "update_table_cell"
        else -> action
      }
      EXCEL_WORKBOOK_SKILL_NAME -> when (action) {
        "cell", "set", "write_cell", "set_cell" -> "set_cell"
        "formula", "write_formula", "set_formula" -> "set_formula"
        "row", "append", "append_row", "add_row" -> "add_row"
        "sheet", "new_sheet", "add_sheet" -> "add_sheet"
        "remove_sheet", "delete_sheet" -> "delete_sheet"
        "rename", "rename_sheet" -> "rename_sheet"
        else -> action
      }
      POWERPOINT_PRESENTATION_SKILL_NAME -> when (action) {
        "replace", "replace_text" -> "replace_text"
        "update_text", "update_slide_text", "set_slide_text" -> "update_slide_text"
        "textbox", "text_box", "add_textbox", "add_text_box" -> "add_textbox"
        "slide", "append", "append_slide", "new_slide", "add_slide" -> "add_slide"
        "remove_slide", "delete_slide" -> "delete_slide"
        else -> action
      }
      else -> action
    }
  }

  private fun normalizeWordParams(action: String, raw: JSONObject, params: JSONObject) {
    when (action) {
      "add_paragraph", "add_heading" -> copyFirst(params, "text", raw, "text", "content", "body", "paragraph", "paragraph_text", "append_text", "text_to_append")
      "replace_text" -> {
        copyFirst(params, "old", raw, "old", "old_text", "find", "search", "search_text")
        copyFirst(params, "new", raw, "new", "new_text", "replace", "replacement", "content", "text")
      }
      "add_table" -> copyFirst(params, "rows", raw, "rows", "data", "table", "values")
      "update_table_cell" -> {
        copyFirst(params, "table", raw, "table", "table_index")
        copyFirst(params, "row", raw, "row", "row_index")
        copyFirst(params, "col", raw, "col", "column", "column_index")
        copyFirst(params, "text", raw, "text", "content", "value")
      }
    }
    if (action == "add_heading") copyFirst(params, "level", raw, "level", "heading_level")
  }

  private fun normalizeExcelParams(action: String, raw: JSONObject, params: JSONObject) {
    when (action) {
      "set_cell" -> {
        copyFirst(params, "cell", raw, "cell", "cell_ref", "range")
        copyFirst(params, "value", raw, "value", "content", "text")
        copyFirst(params, "sheet", raw, "sheet", "sheet_name", "worksheet")
      }
      "set_formula" -> {
        copyFirst(params, "cell", raw, "cell", "cell_ref", "range")
        copyFirst(params, "formula", raw, "formula", "value", "content")
        copyFirst(params, "sheet", raw, "sheet", "sheet_name", "worksheet")
      }
      "add_row" -> {
        copyFirst(params, "values", raw, "values", "row", "data", "content")
        copyFirst(params, "sheet", raw, "sheet", "sheet_name", "worksheet")
      }
      "add_sheet", "delete_sheet" -> copyFirst(params, "name", raw, "name", "sheet", "sheet_name", "worksheet")
      "rename_sheet" -> {
        copyFirst(params, "old_name", raw, "old_name", "old", "sheet", "sheet_name")
        copyFirst(params, "new_name", raw, "new_name", "new", "name")
      }
    }
  }

  private fun normalizePptParams(action: String, raw: JSONObject, params: JSONObject) {
    when (action) {
      "replace_text" -> {
        copyFirst(params, "old", raw, "old", "old_text", "find", "search_text")
        copyFirst(params, "new", raw, "new", "new_text", "replace", "replacement", "content", "text")
      }
      "update_slide_text" -> {
        copyFirst(params, "slide", raw, "slide", "slide_number", "page")
        copyFirst(params, "shape_name", raw, "shape_name", "shape", "name")
        copyFirst(params, "text", raw, "text", "content", "body")
      }
      "add_textbox" -> {
        copyFirst(params, "slide", raw, "slide", "slide_number", "page")
        copyFirst(params, "name", raw, "name", "shape_name")
        copyFirst(params, "text", raw, "text", "content", "body")
        for (key in listOf("left", "top", "width", "height", "font_size", "bold")) copyFirst(params, key, raw, key)
      }
      "add_slide" -> {
        copyFirst(params, "title", raw, "title", "heading", "name")
        copyFirst(params, "content", raw, "content", "text", "body")
      }
      "delete_slide" -> copyFirst(params, "slide", raw, "slide", "slide_number", "page")
    }
  }

  /**
   * Assign an output path only when the model/user omitted one. The name is deterministic from the
   * requested content, but collision-safe inside the mounted workspace.
   */
  fun ensureCollisionSafeOutputPath(
    root: DocumentFile,
    skillName: String,
    request: JSONObject,
    operation: String,
  ) {
    if (!shouldAutoName(operation)) return
    val explicitOutput = request.optString("output_path").trim()
    val explicitGeneric = request.optString("path").trim()
    if (explicitOutput.isNotBlank() || (operation.endsWith("_create") && explicitGeneric.isNotBlank())) return

    val suggested = suggestedAutoOutputPath(skillName, request, operation) ?: return
    val unique = chooseUniquePath(suggested) { path -> resolveExisting(root, path) != null }
    request.put("output_path", unique)
    request.put(META_AUTO_OUTPUT_PATH, true)
  }

  private fun shouldAutoName(operation: String): Boolean =
    operation in setOf("word_create", "pdf_create", "xlsx_create", "pptx_create", "pdf_merge", "document_convert")

  internal fun suggestedAutoOutputPath(skillName: String, request: JSONObject, operation: String): String? {
    return when (operation) {
      "word_create" -> "file/${safeBase(request.optString("title"), "document")}.docx"
      "pdf_create" -> "file/${safeBase(request.optString("title"), "document")}.pdf"
      "xlsx_create" -> {
        val title = request.optString("title")
        val sheetName = request.optString("sheet_name")
        val firstSheet = request.optJSONArray("sheets")?.optJSONObject(0)?.optString("name").orEmpty()
        "file/${safeBase(firstNonBlankValue(title, sheetName, firstSheet), "workbook")}.xlsx"
      }
      "pptx_create" -> {
        val title = request.optString("title")
        val firstSlideTitle = request.optJSONArray("slides")?.optJSONObject(0)?.optString("title").orEmpty()
        "file/${safeBase(firstNonBlankValue(title, firstSlideTitle), "presentation")}.pptx"
      }
      "pdf_merge" -> "file/merged.pdf"
      "document_convert" -> {
        val input = firstNonBlank(request, "input_path", "path")
        val format = normalizeFormat(request.optString("output_format").ifBlank { request.optString("format") })
        if (input.isBlank() || format.isBlank()) return null
        val fileName = input.substringAfterLast('/')
        val base = safeBase(fileName.substringBeforeLast('.', fileName), "converted")
        val inputExt = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val effectiveBase = if (inputExt == format) "$base-converted" else base
        "file/$effectiveBase.$format"
      }
      else -> null
    }
  }

  internal fun chooseUniquePath(candidate: String, exists: (String) -> Boolean): String {
    if (!exists(candidate)) return candidate
    val slash = candidate.lastIndexOf('/')
    val dir = if (slash >= 0) candidate.substring(0, slash + 1) else ""
    val name = if (slash >= 0) candidate.substring(slash + 1) else candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var index = 2
    while (index < 10_000) {
      val path = "$dir$base-$index$ext"
      if (!exists(path)) return path
      index++
    }
    error("Could not allocate a unique Office output path for $candidate")
  }

  private fun safeBase(raw: String, fallback: String): String {
    val cleaned = raw
      .trim()
      .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
      .replace(Regex("\\s+"), "_")
      .trim('.', ' ', '_')
      .take(60)
    return cleaned.ifBlank { fallback }
  }

  private fun normalizeFormat(raw: String): String {
    return raw.trim().lowercase(Locale.US).removePrefix(".").let {
      when (it) {
        "word" -> "docx"
        "text" -> "txt"
        "htm" -> "html"
        else -> it
      }
    }
  }

  private fun normalizeToken(raw: String): String =
    raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')

  private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
    for (key in keys) {
      val value = obj.optString(key).trim()
      if (value.isNotBlank()) return value
    }
    return ""
  }

  private fun firstNonBlankValue(vararg values: String): String = values.firstOrNull { it.trim().isNotBlank() }?.trim().orEmpty()

  private fun copyFirst(target: JSONObject, canonical: String, source: JSONObject, vararg aliases: String) {
    if (target.has(canonical) && !target.isNull(canonical)) {
      val existing = target.opt(canonical)
      if (existing !is String || existing.isNotBlank()) return
    }
    for (key in aliases) {
      if (!source.has(key) || source.isNull(key)) continue
      val value = source.opt(key)
      if (value is String && value.isBlank()) continue
      target.put(canonical, value)
      return
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
}
