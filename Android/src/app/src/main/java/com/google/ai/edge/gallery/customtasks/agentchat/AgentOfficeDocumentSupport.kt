/*
 * MCP251 Office skills backend.
 * Additive workspace-scoped document support for Local Agent Plaza.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document as XmlDocument
import org.w3c.dom.Element
import org.w3c.dom.Node

object AgentOfficeDocumentSupport {
  private const val MAX_DOCUMENT_BYTES = 40_000_000
  private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  private const val S_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
  private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
  private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

  private val supportedOperations =
    setOf(
      "word_create", "word_read", "word_modify",
      "pdf_create", "pdf_read", "pdf_merge", "pdf_extract_pages", "pdf_reorder_pages",
      "pdf_delete_pages", "pdf_rotate_pages",
      "xlsx_create", "xlsx_read", "xlsx_modify",
      "pptx_create", "pptx_read", "pptx_modify",
      "document_convert",
    )

  fun supports(operation: String): Boolean = operation.trim().lowercase(Locale.US) in supportedOperations

  fun execute(
    context: Context,
    root: DocumentFile,
    request: JSONObject,
  ): String {
    val operation = request.optString("operation").trim().lowercase(Locale.US)
    if (!supports(operation)) return failure(operation, "Unsupported Office operation: $operation")
    return try {
      when (operation) {
        "word_create" -> createWord(context, root, request)
        "word_read" -> readDocument(context, root, request, "docx")
        "word_modify" -> modifyWord(context, root, request)
        "pdf_create" -> createPdf(context, root, request)
        "pdf_read" -> readDocument(context, root, request, "pdf")
        "pdf_merge" -> mergePdf(context, root, request)
        "pdf_extract_pages" -> transformPdfPages(context, root, request, PdfPageMode.EXTRACT)
        "pdf_reorder_pages" -> transformPdfPages(context, root, request, PdfPageMode.REORDER)
        "pdf_delete_pages" -> transformPdfPages(context, root, request, PdfPageMode.DELETE)
        "pdf_rotate_pages" -> rotatePdfPages(context, root, request)
        "xlsx_create" -> createXlsx(context, root, request)
        "xlsx_read" -> readDocument(context, root, request, "xlsx")
        "xlsx_modify" -> modifyXlsx(context, root, request)
        "pptx_create" -> createPptx(context, root, request)
        "pptx_read" -> readPptx(context, root, request)
        "pptx_modify" -> modifyPptx(context, root, request)
        "document_convert" -> convertDocument(context, root, request)
        else -> failure(operation, "Unsupported Office operation: $operation")
      }
    } catch (e: Exception) {
      failure(operation, e.message ?: "Office operation failed.")
    }
  }

  private fun normalizeInputPath(path: String): String {
    val p = path.replace('\\', '/').trim().trimStart('/')
    require(p.isNotBlank()) { "Input path is required." }
    require(!path.trim().startsWith("/")) { "Absolute paths are not allowed." }
    require(p.split('/').none { it == ".." }) { "Parent traversal is not allowed." }
    return p
  }

  private fun normalizeOutputPath(path: String, extension: String, fallbackBase: String): String {
    val raw = path.replace('\\', '/').trim()
    require(!raw.startsWith("/")) { "Absolute paths are not allowed." }
    require(raw.split('/').none { it == ".." }) { "Parent traversal is not allowed." }
    var p = raw.ifBlank { "$fallbackBase.$extension" }.trimStart('/')
    if (!p.lowercase(Locale.US).endsWith(".$extension")) p += ".$extension"
    if (!p.startsWith("file/")) p = "file/$p"
    return p
  }

  private fun resolveExisting(root: DocumentFile, path: String): DocumentFile? {
    var current = root
    for (segment in normalizeInputPath(path).split('/').filter { it.isNotBlank() && it != "." }) {
      current = current.findFile(segment) ?: return null
    }
    return current
  }

  private fun ensureParent(root: DocumentFile, path: String): Pair<DocumentFile, String> {
    val normalized = path.replace('\\', '/').trim().trim('/')
    val parts = normalized.split('/').filter { it.isNotBlank() }
    require(parts.isNotEmpty()) { "Output path is required." }
    var current = root
    for (segment in parts.dropLast(1)) {
      val existing = current.findFile(segment)
      current =
        when {
          existing == null -> current.createDirectory(segment) ?: error("Failed to create directory: $segment")
          existing.isDirectory -> existing
          else -> error("Path component is not a directory: $segment")
        }
    }
    return current to parts.last()
  }

  private fun readBytes(context: Context, root: DocumentFile, path: String): ByteArray {
    val file = resolveExisting(root, path) ?: error("File not found: $path")
    require(file.isFile) { "Not a file: $path" }
    val size = file.length()
    require(size <= MAX_DOCUMENT_BYTES) { "Document is too large: $size bytes." }
    return context.contentResolver.openInputStream(file.uri)?.use { input ->
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      var total = 0
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_DOCUMENT_BYTES) { "Document exceeds $MAX_DOCUMENT_BYTES bytes." }
        out.write(buffer, 0, count)
      }
      out.toByteArray()
    } ?: error("Failed to open file: $path")
  }

  private fun writeBytes(
    context: Context,
    root: DocumentFile,
    path: String,
    mime: String,
    bytes: ByteArray,
  ): Int {
    val (parent, name) = ensureParent(root, path)
    var target = parent.findFile(name)
    if (target != null && target.isDirectory) error("Output path is a directory: $path")
    if (target == null) target = parent.createFile(mime, name) ?: error("Failed to create file: $path")
    context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
      ?: error("Failed to open output file: $path")
    return bytes.size
  }

  private fun success(operation: String, path: String, bytes: Int, extra: JSONObject? = null): String =
    JSONObject()
      .put("status", "succeeded")
      .put("operation", operation)
      .put("path", path)
      .put("bytes_written", bytes)
      .apply {
        if (extra != null) {
          val keys = extra.keys()
          while (keys.hasNext()) {
            val key = keys.next()
            put(key, extra.opt(key))
          }
        }
      }
      .toString()

  private fun failure(operation: String, message: String): String =
    JSONObject().put("status", "failed").put("operation", operation).put("error", message).toString()

  private fun readDocument(
    context: Context,
    root: DocumentFile,
    request: JSONObject,
    expectedExtension: String,
  ): String {
    val inputPath = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    require(inputPath.lowercase(Locale.US).endsWith(".$expectedExtension")) {
      "Expected a .$expectedExtension file."
    }
    val bytes = readBytes(context, root, inputPath)
    val extracted =
      WorkspaceDocumentTextExtractor.extract(
        fileName = inputPath.substringAfterLast('/'),
        bytes = bytes,
        maxBytes = request.optInt("max_bytes", 200_000).coerceIn(256, 500_000),
        context = context,
      )
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", request.optString("operation"))
      .put("path", inputPath)
      .put("detected_format", extracted.detectedFormat)
      .put("content", extracted.content)
      .put("truncated", extracted.truncated)
      .put("bytes_read", bytes.size)
      .toString()
  }

  private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
        zip.closeEntry()
      }
    }
    return entries
  }

  private fun zip(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun parseXml(bytes: ByteArray): XmlDocument {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
  }

  private fun xmlBytes(doc: XmlDocument): ByteArray {
    val out = ByteArrayOutputStream()
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
    transformer.transform(DOMSource(doc), StreamResult(out))
    return out.toByteArray()
  }

  private fun esc(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&apos;")

  private fun textNodes(doc: XmlDocument, namespace: String, localName: String): List<Node> {
    val nodes = doc.getElementsByTagNameNS(namespace, localName)
    return (0 until nodes.length).map { nodes.item(it) }
  }

  private fun createWord(context: Context, root: DocumentFile, request: JSONObject): String {
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { request.optString("path") }, "docx", "document")
    val title = request.optString("title")
    val content = request.optString("content")
    require(title.isNotBlank() || content.isNotBlank()) { "Word creation requires title or content." }
    val bytes = buildDocx(title, content)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes)
    return success("word_create", output, written)
  }

  private fun buildDocx(title: String, content: String): ByteArray {
    val paragraphs = mutableListOf<String>()
    if (title.isNotBlank()) {
      paragraphs += """<w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t xml:space="preserve">${esc(title)}</w:t></w:r></w:p>"""
    }
    content.replace("\r\n", "\n").split('\n').forEach { line ->
      paragraphs += """<w:p><w:r><w:t xml:space="preserve">${esc(line)}</w:t></w:r></w:p>"""
    }
    val entries = linkedMapOf<String, ByteArray>()
    entries["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray()
    entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$REL_NS">
<Relationship Id="rId1" Type="$R_NS/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray()
    entries["word/document.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W_NS"><w:body>${paragraphs.joinToString("")}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""".toByteArray()
    return zip(entries)
  }

  private fun modifyWord(context: Context, root: DocumentFile, request: JSONObject): String {
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    require(input.lowercase(Locale.US).endsWith(".docx")) { "Word input must be .docx." }
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { input }, "docx", "document")
    val entries = unzip(readBytes(context, root, input))
    val documentBytes = entries["word/document.xml"] ?: error("Invalid DOCX: word/document.xml is missing.")
    val doc = parseXml(documentBytes)
    val body = doc.getElementsByTagNameNS(W_NS, "body").item(0) as? Element ?: error("Invalid DOCX body.")
    val operations = request.optJSONArray("operations") ?: error("Word modify requires operations array.")
    require(operations.length() > 0) { "Word modify requires at least one operation." }
    var applied = 0
    for (i in 0 until operations.length()) {
      val op = operations.optJSONObject(i) ?: error("Word operation ${i + 1} must be an object.")
      val action = op.optString("action")
      val params = op.optJSONObject("params") ?: JSONObject()
      when (action) {
        "replace_text" -> {
          val old = params.optString("old")
          require(old.isNotEmpty()) { "replace_text requires params.old." }
          val new = params.optString("new")
          var replacements = 0
          for (node in textNodes(doc, W_NS, "t")) {
            val value = node.textContent ?: ""
            if (value.contains(old)) {
              replacements += value.windowed(old.length, 1).count { it == old }
              node.textContent = value.replace(old, new)
            }
          }
          require(replacements > 0) { "replace_text found no matches for: $old" }
        }
        "add_paragraph" -> appendWordParagraph(doc, body, params.optString("text"), false, 0)
        "add_heading" -> {
          val text = params.optString("text")
          require(text.isNotBlank()) { "add_heading requires params.text." }
          val level = params.optInt("level", 1).coerceIn(1, 6)
          appendWordParagraph(doc, body, text, true, (36 - level * 2).coerceAtLeast(24))
        }
        "add_page_break" -> {
          val p = doc.createElementNS(W_NS, "w:p")
          val r = doc.createElementNS(W_NS, "w:r")
          val br = doc.createElementNS(W_NS, "w:br")
          br.setAttributeNS(W_NS, "w:type", "page")
          r.appendChild(br); p.appendChild(r); insertBeforeSectPr(body, p)
        }
        "add_table" -> {
          val rows = params.optJSONArray("rows") ?: error("add_table requires params.rows.")
          require(rows.length() > 0) { "add_table requires non-empty rows." }
          val tbl = doc.createElementNS(W_NS, "w:tbl")
          for (rIndex in 0 until rows.length()) {
            val row = rows.optJSONArray(rIndex) ?: JSONArray().put(rows.opt(rIndex))
            val tr = doc.createElementNS(W_NS, "w:tr")
            for (cIndex in 0 until row.length()) {
              val tc = doc.createElementNS(W_NS, "w:tc")
              tc.appendChild(wordParagraphElement(doc, row.opt(cIndex)?.toString().orEmpty(), false, 0))
              tr.appendChild(tc)
            }
            tbl.appendChild(tr)
          }
          insertBeforeSectPr(body, tbl)
        }
        "update_table_cell" -> {
          val tableIndex = params.optInt("table", 0)
          val rowIndex = params.optInt("row", 0)
          val colIndex = params.optInt("col", 0)
          val tables = doc.getElementsByTagNameNS(W_NS, "tbl")
          require(tableIndex in 0 until tables.length) { "Table index out of range." }
          val rows = (tables.item(tableIndex) as Element).getElementsByTagNameNS(W_NS, "tr")
          require(rowIndex in 0 until rows.length) { "Row index out of range." }
          val cells = (rows.item(rowIndex) as Element).getElementsByTagNameNS(W_NS, "tc")
          require(colIndex in 0 until cells.length) { "Column index out of range." }
          val cell = cells.item(colIndex) as Element
          while (cell.hasChildNodes()) cell.removeChild(cell.firstChild)
          cell.appendChild(wordParagraphElement(doc, params.optString("text"), false, 0))
        }
        else -> error("Unsupported Word modify action: $action")
      }
      applied++
    }
    entries["word/document.xml"] = xmlBytes(doc)
    val bytes = zip(entries)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes)
    return success("word_modify", output, written, JSONObject().put("operations_applied", applied))
  }

  private fun appendWordParagraph(doc: XmlDocument, body: Element, text: String, bold: Boolean, size: Int) {
    require(text.isNotBlank()) { "Paragraph text is required." }
    insertBeforeSectPr(body, wordParagraphElement(doc, text, bold, size))
  }

  private fun wordParagraphElement(doc: XmlDocument, text: String, bold: Boolean, size: Int): Element {
    val p = doc.createElementNS(W_NS, "w:p")
    val r = doc.createElementNS(W_NS, "w:r")
    if (bold || size > 0) {
      val rPr = doc.createElementNS(W_NS, "w:rPr")
      if (bold) rPr.appendChild(doc.createElementNS(W_NS, "w:b"))
      if (size > 0) {
        val sz = doc.createElementNS(W_NS, "w:sz")
        sz.setAttributeNS(W_NS, "w:val", size.toString())
        rPr.appendChild(sz)
      }
      r.appendChild(rPr)
    }
    val t = doc.createElementNS(W_NS, "w:t")
    t.setAttribute("xml:space", "preserve")
    t.textContent = text
    r.appendChild(t); p.appendChild(r)
    return p
  }

  private fun insertBeforeSectPr(body: Element, node: Node) {
    val sect = body.getElementsByTagNameNS(W_NS, "sectPr").item(0)
    if (sect != null && sect.parentNode === body) body.insertBefore(node, sect) else body.appendChild(node)
  }

  private fun createPdf(context: Context, root: DocumentFile, request: JSONObject): String {
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { request.optString("path") }, "pdf", "document")
    val title = request.optString("title")
    val content = request.optString("content")
    require(title.isNotBlank() || content.isNotBlank()) { "PDF creation requires title or content." }
    val bytes = renderTextPdf(title, content)
    val written = writeBytes(context, root, output, "application/pdf", bytes)
    return success("pdf_create", output, written)
  }

  private fun renderTextPdf(title: String, content: String): ByteArray {
    val pdf = AndroidPdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val left = 52f
    val right = 52f
    val top = 58f
    val bottom = 58f
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = 12f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = 20f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val lines = mutableListOf<Pair<String, Boolean>>()
    if (title.isNotBlank()) {
      wrapText(title, titlePaint, pageWidth - left - right).forEach { lines += it to true }
      lines += "" to true
    }
    content.replace("\r\n", "\n").split('\n').forEach { paragraph ->
      if (paragraph.isBlank()) lines += "" to false
      else wrapText(paragraph, bodyPaint, pageWidth - left - right).forEach { lines += it to false }
    }
    var pageNumber = 1
    var page = pdf.startPage(AndroidPdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var y = top
    for ((line, isTitle) in lines) {
      val paint = if (isTitle) titlePaint else bodyPaint
      val lineHeight = if (isTitle) 29f else 18f
      if (y + lineHeight > pageHeight - bottom) {
        pdf.finishPage(page)
        pageNumber++
        page = pdf.startPage(AndroidPdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        y = top
      }
      if (line.isNotEmpty()) page.canvas.drawText(line, left, y, paint)
      y += lineHeight
    }
    pdf.finishPage(page)
    val out = ByteArrayOutputStream()
    pdf.writeTo(out)
    pdf.close()
    return out.toByteArray()
  }

  private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var count = paint.breakText(text, start, text.length, true, maxWidth, null)
      if (count <= 0) count = 1
      var end = start + count
      if (end < text.length) {
        val space = text.lastIndexOf(' ', end - 1)
        if (space > start + count / 2) end = space + 1
      }
      result += text.substring(start, end).trimEnd()
      start = end
      while (start < text.length && text[start] == ' ') start++
    }
    return result
  }

  private fun mergePdf(context: Context, root: DocumentFile, request: JSONObject): String {
    PDFBoxResourceLoader.init(context)
    val paths = jsonStrings(request.optJSONArray("input_paths"))
    require(paths.size >= 2) { "pdf_merge requires at least two input_paths." }
    val output = normalizeOutputPath(request.optString("output_path"), "pdf", "merged")
    val target = PDDocument()
    try {
      for (path in paths) {
        PDDocument.load(ByteArrayInputStream(readBytes(context, root, path))).use { source ->
          for (i in 0 until source.numberOfPages) target.importPage(source.getPage(i))
        }
      }
      val out = ByteArrayOutputStream()
      target.save(out)
      val bytes = out.toByteArray()
      val written = writeBytes(context, root, output, "application/pdf", bytes)
      return success("pdf_merge", output, written, JSONObject().put("page_count", target.numberOfPages))
    } finally {
      target.close()
    }
  }

  private enum class PdfPageMode { EXTRACT, REORDER, DELETE }

  private fun transformPdfPages(
    context: Context,
    root: DocumentFile,
    request: JSONObject,
    mode: PdfPageMode,
  ): String {
    PDFBoxResourceLoader.init(context)
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { input.substringAfterLast('/') }, "pdf", "document")
    PDDocument.load(ByteArrayInputStream(readBytes(context, root, input))).use { source ->
      val selected = parsePages(request, source.numberOfPages)
      val pageIndices =
        when (mode) {
          PdfPageMode.EXTRACT, PdfPageMode.REORDER -> selected
          PdfPageMode.DELETE -> (0 until source.numberOfPages).filter { it !in selected.toSet() }
        }
      require(pageIndices.isNotEmpty()) { "PDF operation would produce an empty document." }
      val target = PDDocument()
      try {
        pageIndices.forEach { target.importPage(source.getPage(it)) }
        val out = ByteArrayOutputStream()
        target.save(out)
        val bytes = out.toByteArray()
        val written = writeBytes(context, root, output, "application/pdf", bytes)
        val operation =
          when (mode) {
            PdfPageMode.EXTRACT -> "pdf_extract_pages"
            PdfPageMode.REORDER -> "pdf_reorder_pages"
            PdfPageMode.DELETE -> "pdf_delete_pages"
          }
        return success(operation, output, written, JSONObject().put("page_count", target.numberOfPages))
      } finally {
        target.close()
      }
    }
  }

  private fun rotatePdfPages(context: Context, root: DocumentFile, request: JSONObject): String {
    PDFBoxResourceLoader.init(context)
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { input.substringAfterLast('/') }, "pdf", "document")
    val degrees = request.optInt("degrees", request.optInt("rotation", 90))
    require(degrees % 90 == 0) { "PDF rotation must be a multiple of 90 degrees." }
    PDDocument.load(ByteArrayInputStream(readBytes(context, root, input))).use { document ->
      val pages = parsePages(request, document.numberOfPages, allowDefaultAll = true)
      for (index in pages) {
        val page = document.getPage(index)
        page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
      }
      val out = ByteArrayOutputStream()
      document.save(out)
      val bytes = out.toByteArray()
      val written = writeBytes(context, root, output, "application/pdf", bytes)
      return success("pdf_rotate_pages", output, written, JSONObject().put("page_count", document.numberOfPages))
    }
  }

  private fun parsePages(request: JSONObject, total: Int, allowDefaultAll: Boolean = false): List<Int> {
    val array = request.optJSONArray("pages")
    val raw = request.optString("pages")
    val result =
      when {
        array != null -> (0 until array.length()).map { array.optInt(it) - 1 }
        raw.isNotBlank() -> parsePageSpec(raw, total)
        allowDefaultAll -> (0 until total).toList()
        else -> error("pages is required.")
      }
    require(result.all { it in 0 until total }) { "Page number out of range. PDF has $total pages." }
    return result
  }

  private fun parsePageSpec(spec: String, total: Int): List<Int> {
    if (spec.trim().equals("all", true)) return (0 until total).toList()
    val pages = mutableListOf<Int>()
    for (part in spec.split(',')) {
      val token = part.trim()
      if ('-' in token) {
        val pair = token.split('-', limit = 2)
        val start = pair[0].trim().toInt()
        val end = pair[1].trim().toInt()
        val range = if (start <= end) start..end else start downTo end
        range.forEach { pages += it - 1 }
      } else if (token.isNotBlank()) {
        pages += token.toInt() - 1
      }
    }
    return pages
  }

  private fun createXlsx(context: Context, root: DocumentFile, request: JSONObject): String {
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { request.optString("path") }, "xlsx", "workbook")
    val sheets = request.optJSONArray("sheets")
    val normalizedSheets = mutableListOf<Pair<String, JSONArray>>()
    if (sheets != null && sheets.length() > 0) {
      for (i in 0 until sheets.length()) {
        val sheet = sheets.optJSONObject(i) ?: error("Each sheet must be an object.")
        normalizedSheets += sanitizeSheetName(sheet.optString("name").ifBlank { "Sheet${i + 1}" }) to
          (sheet.optJSONArray("rows") ?: JSONArray())
      }
    } else {
      normalizedSheets += sanitizeSheetName(request.optString("sheet_name").ifBlank { "Sheet1" }) to
        (request.optJSONArray("rows") ?: JSONArray())
    }
    val bytes = buildXlsx(normalizedSheets)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)
    return success("xlsx_create", output, written, JSONObject().put("sheet_count", normalizedSheets.size))
  }

  private fun buildXlsx(sheets: List<Pair<String, JSONArray>>): ByteArray {
    val entries = linkedMapOf<String, ByteArray>()
    val overrides = sheets.indices.joinToString("") { i ->
      """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
    }
    entries["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
$overrides
</Types>""".toByteArray()
    entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/officeDocument" Target="xl/workbook.xml"/></Relationships>""".toByteArray()
    val sheetTags = sheets.mapIndexed { i, (name, _) ->
      """<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
    }.joinToString("")
    entries["xl/workbook.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="$S_NS" xmlns:r="$R_NS"><sheets>$sheetTags</sheets></workbook>""".toByteArray()
    entries["xl/_rels/workbook.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$REL_NS">${sheets.indices.joinToString("") { i ->
      """<Relationship Id="rId${i + 1}" Type="$R_NS/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
    }}</Relationships>""".toByteArray()
    sheets.forEachIndexed { i, (_, rows) ->
      entries["xl/worksheets/sheet${i + 1}.xml"] = worksheetXml(rows).toByteArray()
    }
    return zip(entries)
  }

  private fun worksheetXml(rows: JSONArray): String {
    val rowXml = buildString {
      for (r in 0 until rows.length()) {
        val row = rows.optJSONArray(r) ?: JSONArray().put(rows.opt(r))
        append("""<row r="${r + 1}">""")
        for (c in 0 until row.length()) append(xlsxCellXml(columnName(c + 1) + (r + 1), row.opt(c), false))
        append("</row>")
      }
    }
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="$S_NS"><sheetData>$rowXml</sheetData></worksheet>"""
  }

  private fun xlsxCellXml(ref: String, value: Any?, formula: Boolean): String {
    if (formula) {
      val f = value?.toString().orEmpty().removePrefix("=")
      return """<c r="$ref"><f>${esc(f)}</f></c>"""
    }
    return when (value) {
      is Number -> """<c r="$ref"><v>${value}</v></c>"""
      is Boolean -> """<c r="$ref" t="b"><v>${if (value) 1 else 0}</v></c>"""
      JSONObject.NULL, null -> """<c r="$ref"/>"""
      else -> {
        val text = value.toString()
        if (text.startsWith("=")) """<c r="$ref"><f>${esc(text.removePrefix("="))}</f></c>"""
        else """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(text)}</t></is></c>"""
      }
    }
  }

  private fun modifyXlsx(context: Context, root: DocumentFile, request: JSONObject): String {
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    require(input.lowercase(Locale.US).endsWith(".xlsx")) { "Excel input must be .xlsx." }
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { input.substringAfterLast('/') }, "xlsx", "workbook")
    val entries = unzip(readBytes(context, root, input))
    val workbookPath = "xl/workbook.xml"
    val relsPath = "xl/_rels/workbook.xml.rels"
    val workbook = parseXml(entries[workbookPath] ?: error("Invalid XLSX workbook."))
    val rels = parseXml(entries[relsPath] ?: error("Invalid XLSX relationships."))
    val operations = request.optJSONArray("operations") ?: error("Excel modify requires operations array.")
    require(operations.length() > 0) { "Excel modify requires at least one operation." }
    var applied = 0
    for (i in 0 until operations.length()) {
      val op = operations.optJSONObject(i) ?: error("Excel operation ${i + 1} must be an object.")
      val action = op.optString("action")
      val params = op.optJSONObject("params") ?: JSONObject()
      when (action) {
        "set_cell", "set_formula", "add_row" -> {
          val sheetName = params.optString("sheet").ifBlank { firstSheetName(workbook) }
          val sheetPath = worksheetPath(workbook, rels, sheetName)
          val sheetDoc = parseXml(entries[sheetPath] ?: error("Worksheet file missing: $sheetPath"))
          when (action) {
            "set_cell" -> setXlsxCell(sheetDoc, params.optString("cell"), params.opt("value"), false)
            "set_formula" -> setXlsxCell(sheetDoc, params.optString("cell"), params.optString("formula"), true)
            "add_row" -> addXlsxRow(sheetDoc, params.optJSONArray("values") ?: error("add_row requires params.values."))
          }
          entries[sheetPath] = xmlBytes(sheetDoc)
        }
        "add_sheet" -> addXlsxSheet(entries, workbook, rels, params.optString("name"))
        "delete_sheet" -> deleteXlsxSheet(entries, workbook, rels, params.optString("name"))
        "rename_sheet" -> renameXlsxSheet(workbook, params.optString("old_name").ifBlank { params.optString("sheet") }, params.optString("new_name"))
        else -> error("Unsupported Excel modify action: $action")
      }
      applied++
    }
    entries[workbookPath] = xmlBytes(workbook)
    entries[relsPath] = xmlBytes(rels)
    val bytes = zip(entries)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes)
    return success("xlsx_modify", output, written, JSONObject().put("operations_applied", applied))
  }

  private fun firstSheetName(workbook: XmlDocument): String {
    val sheets = workbook.getElementsByTagNameNS(S_NS, "sheet")
    require(sheets.length > 0) { "Workbook has no sheets." }
    return (sheets.item(0) as Element).getAttribute("name")
  }

  private fun worksheetPath(workbook: XmlDocument, rels: XmlDocument, sheetName: String): String {
    val sheets = workbook.getElementsByTagNameNS(S_NS, "sheet")
    var rid = ""
    for (i in 0 until sheets.length) {
      val sheet = sheets.item(i) as Element
      if (sheet.getAttribute("name") == sheetName) {
        rid = sheet.getAttributeNS(R_NS, "id").ifBlank { sheet.getAttribute("r:id") }
        break
      }
    }
    require(rid.isNotBlank()) { "Sheet not found: $sheetName" }
    val relationships = rels.getElementsByTagNameNS(REL_NS, "Relationship")
    for (i in 0 until relationships.length) {
      val rel = relationships.item(i) as Element
      if (rel.getAttribute("Id") == rid) return "xl/" + rel.getAttribute("Target").trimStart('/')
    }
    error("Worksheet relationship not found for $sheetName")
  }

  private fun setXlsxCell(sheet: XmlDocument, cellRefRaw: String, value: Any?, formula: Boolean) {
    val cellRef = cellRefRaw.trim().uppercase(Locale.US)
    require(cellRef.matches(Regex("[A-Z]+[1-9][0-9]*"))) { "Invalid cell reference: $cellRefRaw" }
    val rowNumber = cellRef.dropWhile { it.isLetter() }.toInt()
    val sheetData = sheet.getElementsByTagNameNS(S_NS, "sheetData").item(0) as? Element ?: error("Invalid worksheet.")
    var row: Element? = null
    val rows = sheet.getElementsByTagNameNS(S_NS, "row")
    for (i in 0 until rows.length) {
      val candidate = rows.item(i) as Element
      if (candidate.getAttribute("r").toIntOrNull() == rowNumber) { row = candidate; break }
    }
    if (row == null) {
      row = sheet.createElementNS(S_NS, "row")
      row.setAttribute("r", rowNumber.toString())
      sheetData.appendChild(row)
    }
    var cell: Element? = null
    val cells = row.getElementsByTagNameNS(S_NS, "c")
    for (i in 0 until cells.length) {
      val candidate = cells.item(i) as Element
      if (candidate.getAttribute("r").equals(cellRef, true)) { cell = candidate; break }
    }
    val cellXml = parseXml("""<?xml version="1.0"?><worksheet xmlns="$S_NS"><sheetData><row r="$rowNumber">${xlsxCellXml(cellRef, value, formula)}</row></sheetData></worksheet>""".toByteArray())
    val newCell = cellXml.getElementsByTagNameNS(S_NS, "c").item(0)
    val imported = sheet.importNode(newCell, true)
    if (cell != null) row.replaceChild(imported, cell) else row.appendChild(imported)
  }

  private fun addXlsxRow(sheet: XmlDocument, values: JSONArray) {
    val rows = sheet.getElementsByTagNameNS(S_NS, "row")
    var maxRow = 0
    for (i in 0 until rows.length) maxRow = maxOf(maxRow, (rows.item(i) as Element).getAttribute("r").toIntOrNull() ?: 0)
    val rowNum = maxRow + 1
    val sheetData = sheet.getElementsByTagNameNS(S_NS, "sheetData").item(0) as Element
    val row = sheet.createElementNS(S_NS, "row")
    row.setAttribute("r", rowNum.toString())
    for (i in 0 until values.length()) {
      val fragment = parseXml("""<?xml version="1.0"?><worksheet xmlns="$S_NS"><sheetData><row>${xlsxCellXml(columnName(i + 1) + rowNum, values.opt(i), false)}</row></sheetData></worksheet>""".toByteArray())
      row.appendChild(sheet.importNode(fragment.getElementsByTagNameNS(S_NS, "c").item(0), true))
    }
    sheetData.appendChild(row)
  }

  private fun addXlsxSheet(entries: MutableMap<String, ByteArray>, workbook: XmlDocument, rels: XmlDocument, nameRaw: String) {
    val name = sanitizeSheetName(nameRaw)
    val sheetsElement = workbook.getElementsByTagNameNS(S_NS, "sheets").item(0) as Element
    val sheets = workbook.getElementsByTagNameNS(S_NS, "sheet")
    for (i in 0 until sheets.length) require((sheets.item(i) as Element).getAttribute("name") != name) { "Sheet already exists: $name" }
    var maxSheetId = 0
    var maxRid = 0
    for (i in 0 until sheets.length) {
      val e = sheets.item(i) as Element
      maxSheetId = maxOf(maxSheetId, e.getAttribute("sheetId").toIntOrNull() ?: 0)
      val rid = e.getAttributeNS(R_NS, "id").ifBlank { e.getAttribute("r:id") }
      maxRid = maxOf(maxRid, rid.removePrefix("rId").toIntOrNull() ?: 0)
    }
    val newId = maxSheetId + 1
    val newRid = "rId${maxRid + 1}"
    var index = 1
    while (entries.containsKey("xl/worksheets/sheet$index.xml")) index++
    val target = "worksheets/sheet$index.xml"
    val sheet = workbook.createElementNS(S_NS, "sheet")
    sheet.setAttribute("name", name)
    sheet.setAttribute("sheetId", newId.toString())
    sheet.setAttributeNS(R_NS, "r:id", newRid)
    sheetsElement.appendChild(sheet)
    val relRoot = rels.documentElement
    val rel = rels.createElementNS(REL_NS, "Relationship")
    rel.setAttribute("Id", newRid)
    rel.setAttribute("Type", "$R_NS/worksheet")
    rel.setAttribute("Target", target)
    relRoot.appendChild(rel)
    entries["xl/$target"] = worksheetXml(JSONArray()).toByteArray()
    updateContentTypes(entries, "/xl/$target", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml", true)
  }

  private fun deleteXlsxSheet(entries: MutableMap<String, ByteArray>, workbook: XmlDocument, rels: XmlDocument, name: String) {
    val sheets = workbook.getElementsByTagNameNS(S_NS, "sheet")
    require(sheets.length > 1) { "Cannot delete the only worksheet." }
    var targetSheet: Element? = null
    for (i in 0 until sheets.length) {
      val e = sheets.item(i) as Element
      if (e.getAttribute("name") == name) { targetSheet = e; break }
    }
    val sheet = targetSheet ?: error("Sheet not found: $name")
    val rid = sheet.getAttributeNS(R_NS, "id").ifBlank { sheet.getAttribute("r:id") }
    val relationships = rels.getElementsByTagNameNS(REL_NS, "Relationship")
    var relNode: Element? = null
    var target = ""
    for (i in 0 until relationships.length) {
      val rel = relationships.item(i) as Element
      if (rel.getAttribute("Id") == rid) { relNode = rel; target = rel.getAttribute("Target"); break }
    }
    sheet.parentNode.removeChild(sheet)
    if (relNode != null) relNode.parentNode.removeChild(relNode)
    if (target.isNotBlank()) {
      val part = "xl/${target.trimStart('/')}"
      entries.remove(part)
      updateContentTypes(entries, "/$part", "", false)
    }
  }

  private fun renameXlsxSheet(workbook: XmlDocument, oldName: String, newNameRaw: String) {
    val newName = sanitizeSheetName(newNameRaw)
    val sheets = workbook.getElementsByTagNameNS(S_NS, "sheet")
    var target: Element? = null
    for (i in 0 until sheets.length) {
      val e = sheets.item(i) as Element
      if (e.getAttribute("name") == oldName) target = e
      if (e.getAttribute("name") == newName && oldName != newName) error("Sheet already exists: $newName")
    }
    (target ?: error("Sheet not found: $oldName")).setAttribute("name", newName)
  }

  private fun sanitizeSheetName(raw: String): String {
    val value = raw.ifBlank { "Sheet1" }.replace(Regex("""[\\/*?:\[\]]"""), "_").take(31)
    require(value.isNotBlank()) { "Invalid sheet name." }
    return value
  }

  private fun columnName(index: Int): String {
    var n = index
    val out = StringBuilder()
    while (n > 0) {
      n--
      out.append(('A'.code + (n % 26)).toChar())
      n /= 26
    }
    return out.reverse().toString()
  }

  private fun createPptx(context: Context, root: DocumentFile, request: JSONObject): String {
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { request.optString("path") }, "pptx", "presentation")
    val slides = request.optJSONArray("slides") ?: JSONArray().put(
      JSONObject().put("title", request.optString("title")).put("content", request.optString("content"))
    )
    require(slides.length() > 0) { "PowerPoint creation requires at least one slide." }
    val bytes = buildPptx(slides)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.presentationml.presentation", bytes)
    return success("pptx_create", output, written, JSONObject().put("slide_count", slides.length()))
  }

  private fun buildPptx(slides: JSONArray): ByteArray {
    val entries = linkedMapOf<String, ByteArray>()
    val slideOverrides = (1..slides.length()).joinToString("") { i ->
      """<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
    }
    entries["[Content_Types].xml"] = pptContentTypes(slideOverrides).toByteArray()
    entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/officeDocument" Target="ppt/presentation.xml"/></Relationships>""".toByteArray()
    val sldIds = (1..slides.length()).joinToString("") { i -> """<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>""" }
    entries["ppt/presentation.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$sldIds</p:sldIdLst><p:sldSz cx="12192000" cy="6858000" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""".toByteArray()
    entries["ppt/_rels/presentation.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/slideMaster" Target="slideMasters/slideMaster1.xml"/>${(1..slides.length()).joinToString("") { i -> """<Relationship Id="rId${i + 1}" Type="$R_NS/slide" Target="slides/slide$i.xml"/>""" }}</Relationships>""".toByteArray()
    entries["ppt/slideMasters/slideMaster1.xml"] = pptMasterXml().toByteArray()
    entries["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$R_NS/theme" Target="../theme/theme1.xml"/></Relationships>""".toByteArray()
    entries["ppt/slideLayouts/slideLayout1.xml"] = pptLayoutXml().toByteArray()
    entries["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""".toByteArray()
    entries["ppt/theme/theme1.xml"] = pptThemeXml().toByteArray()
    for (i in 0 until slides.length()) {
      val slide = slides.optJSONObject(i) ?: JSONObject().put("content", slides.opt(i)?.toString().orEmpty())
      entries["ppt/slides/slide${i + 1}.xml"] = pptSlideXml(slide.optString("title"), slide.optString("content"), i + 1).toByteArray()
      entries["ppt/slides/_rels/slide${i + 1}.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>""".toByteArray()
    }
    return zip(entries)
  }

  private fun pptContentTypes(slideOverrides: String): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>$slideOverrides</Types>"""

  private fun pptMasterXml(): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""

  private fun pptLayoutXml(): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

  private fun pptThemeXml(): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="$A_NS" name="Office Theme"><a:themeElements><a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="4F81BD"/></a:accent1><a:accent2><a:srgbClr val="C0504D"/></a:accent2><a:accent3><a:srgbClr val="9BBB59"/></a:accent3><a:accent4><a:srgbClr val="8064A2"/></a:accent4><a:accent5><a:srgbClr val="4BACC6"/></a:accent5><a:accent6><a:srgbClr val="F79646"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="Office"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""

  private fun pptSlideXml(title: String, content: String, slideNumber: Int): String {
    val shapes = buildString {
      if (title.isNotBlank()) append(pptTextBoxXml(2, "Title", title, 650000, 350000, 10800000, 850000, 2800, true))
      if (content.isNotBlank()) append(pptTextBoxXml(3, "Content", content, 650000, 1400000, 10800000, 4500000, 1800, false))
    }
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS"><p:cSld name="Slide $slideNumber"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
  }

  private fun pptTextBoxXml(id: Int, name: String, text: String, x: Int, y: Int, cx: Int, cy: Int, size: Int, bold: Boolean): String {
    val paragraphs = text.replace("\r\n", "\n").split('\n').joinToString("") { line ->
      """<a:p><a:r><a:rPr lang="zh-CN" sz="$size"${if (bold) " b=\"1\"" else ""}/><a:t>${esc(line)}</a:t></a:r><a:endParaRPr lang="zh-CN" sz="$size"/></a:p>"""
    }
    return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/>$paragraphs</p:txBody></p:sp>"""
  }

  private fun readPptx(context: Context, root: DocumentFile, request: JSONObject): String {
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    require(input.lowercase(Locale.US).endsWith(".pptx")) { "PowerPoint input must be .pptx." }
    val entries = unzip(readBytes(context, root, input))
    val slidePaths = entries.keys.filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }.sortedBy {
      Regex("""slide(\d+)\.xml""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }
    val text = slidePaths.mapIndexed { index, path ->
      val doc = parseXml(entries[path]!!)
      val values = textNodes(doc, A_NS, "t").map { it.textContent.orEmpty() }.filter { it.isNotBlank() }
      "--- Slide ${index + 1} ---\n" + values.joinToString("\n")
    }.joinToString("\n\n")
    return JSONObject().put("status", "succeeded").put("operation", "pptx_read").put("path", input)
      .put("slide_count", slidePaths.size).put("content", text.take(500_000))
      .put("truncated", text.length > 500_000).toString()
  }

  private fun modifyPptx(context: Context, root: DocumentFile, request: JSONObject): String {
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    require(input.lowercase(Locale.US).endsWith(".pptx")) { "PowerPoint input must be .pptx." }
    val output = normalizeOutputPath(request.optString("output_path").ifBlank { input.substringAfterLast('/') }, "pptx", "presentation")
    val entries = unzip(readBytes(context, root, input))
    val operations = request.optJSONArray("operations") ?: error("PowerPoint modify requires operations array.")
    require(operations.length() > 0) { "PowerPoint modify requires at least one operation." }
    var applied = 0
    for (i in 0 until operations.length()) {
      val op = operations.optJSONObject(i) ?: error("PowerPoint operation ${i + 1} must be an object.")
      val action = op.optString("action")
      val params = op.optJSONObject("params") ?: JSONObject()
      when (action) {
        "replace_text" -> {
          val old = params.optString("old")
          require(old.isNotBlank()) { "replace_text requires params.old." }
          var count = 0
          for (path in slidePaths(entries)) {
            val doc = parseXml(entries[path]!!)
            for (node in textNodes(doc, A_NS, "t")) {
              val value = node.textContent.orEmpty()
              if (value.contains(old)) { node.textContent = value.replace(old, params.optString("new")); count++ }
            }
            entries[path] = xmlBytes(doc)
          }
          require(count > 0) { "replace_text found no matches for: $old" }
        }
        "update_slide_text" -> {
          val slide = params.optInt("slide", 1)
          val shapeName = params.optString("shape_name")
          require(shapeName.isNotBlank()) { "update_slide_text requires shape_name." }
          val path = slidePath(entries, slide)
          val doc = parseXml(entries[path]!!)
          require(updatePptShapeText(doc, shapeName, params.optString("text"))) { "Shape not found: $shapeName" }
          entries[path] = xmlBytes(doc)
        }
        "add_textbox" -> {
          val slide = params.optInt("slide", 1)
          val path = slidePath(entries, slide)
          val doc = parseXml(entries[path]!!)
          val spTree = doc.getElementsByTagNameNS(P_NS, "spTree").item(0) as Element
          val id = nextPptShapeId(doc)
          val fragment = parseXml("""<?xml version="1.0"?><p:sld xmlns:a="$A_NS" xmlns:p="$P_NS"><p:cSld><p:spTree>${pptTextBoxXml(id, params.optString("name").ifBlank { "TextBox$id" }, params.optString("text"), params.optInt("left", 650000), params.optInt("top", 1400000), params.optInt("width", 10800000), params.optInt("height", 1200000), params.optInt("font_size", 1800), params.optBoolean("bold", false))}</p:spTree></p:cSld></p:sld>""".toByteArray())
          spTree.appendChild(doc.importNode(fragment.getElementsByTagNameNS(P_NS, "sp").item(0), true))
          entries[path] = xmlBytes(doc)
        }
        "add_slide" -> addPptSlide(entries, params.optString("title"), params.optString("content"))
        "delete_slide" -> deletePptSlide(entries, params.optInt("slide", 1))
        else -> error("Unsupported PowerPoint modify action: $action")
      }
      applied++
    }
    val bytes = zip(entries)
    val written = writeBytes(context, root, output, "application/vnd.openxmlformats-officedocument.presentationml.presentation", bytes)
    return success("pptx_modify", output, written, JSONObject().put("operations_applied", applied).put("slide_count", slidePaths(entries).size))
  }

  private fun slidePaths(entries: Map<String, ByteArray>): List<String> =
    entries.keys.filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }.sortedBy {
      Regex("""slide(\d+)\.xml""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

  private fun slidePath(entries: Map<String, ByteArray>, slide: Int): String {
    val paths = slidePaths(entries)
    require(slide in 1..paths.size) { "Slide number out of range." }
    return paths[slide - 1]
  }

  private fun updatePptShapeText(doc: XmlDocument, shapeName: String, text: String): Boolean {
    val shapes = doc.getElementsByTagNameNS(P_NS, "sp")
    for (i in 0 until shapes.length) {
      val shape = shapes.item(i) as Element
      val nv = shape.getElementsByTagNameNS(P_NS, "cNvPr")
      if (nv.length == 0 || (nv.item(0) as Element).getAttribute("name") != shapeName) continue
      val textNodes = shape.getElementsByTagNameNS(A_NS, "t")
      if (textNodes.length == 0) return false
      textNodes.item(0).textContent = text
      for (j in textNodes.length - 1 downTo 1) textNodes.item(j).parentNode.removeChild(textNodes.item(j))
      return true
    }
    return false
  }

  private fun nextPptShapeId(doc: XmlDocument): Int {
    val ids = doc.getElementsByTagNameNS(P_NS, "cNvPr")
    var max = 1
    for (i in 0 until ids.length) max = maxOf(max, (ids.item(i) as Element).getAttribute("id").toIntOrNull() ?: 0)
    return max + 1
  }

  private fun addPptSlide(entries: MutableMap<String, ByteArray>, title: String, content: String) {
    val presentation = parseXml(entries["ppt/presentation.xml"] ?: error("Invalid PPTX presentation."))
    val rels = parseXml(entries["ppt/_rels/presentation.xml.rels"] ?: error("Invalid PPTX relationships."))
    var index = 1
    while (entries.containsKey("ppt/slides/slide$index.xml")) index++
    val relNodes = rels.getElementsByTagNameNS(REL_NS, "Relationship")
    var maxRid = 0
    for (i in 0 until relNodes.length) maxRid = maxOf(maxRid, (relNodes.item(i) as Element).getAttribute("Id").removePrefix("rId").toIntOrNull() ?: 0)
    val rid = "rId${maxRid + 1}"
    val rel = rels.createElementNS(REL_NS, "Relationship")
    rel.setAttribute("Id", rid); rel.setAttribute("Type", "$R_NS/slide"); rel.setAttribute("Target", "slides/slide$index.xml")
    rels.documentElement.appendChild(rel)
    val sldIdLst = presentation.getElementsByTagNameNS(P_NS, "sldIdLst").item(0) as Element
    val sldIds = presentation.getElementsByTagNameNS(P_NS, "sldId")
    var maxId = 255
    for (i in 0 until sldIds.length) maxId = maxOf(maxId, (sldIds.item(i) as Element).getAttribute("id").toIntOrNull() ?: 255)
    val sld = presentation.createElementNS(P_NS, "p:sldId")
    sld.setAttribute("id", (maxId + 1).toString())
    sld.setAttributeNS(R_NS, "r:id", rid)
    sldIdLst.appendChild(sld)
    entries["ppt/presentation.xml"] = xmlBytes(presentation)
    entries["ppt/_rels/presentation.xml.rels"] = xmlBytes(rels)
    entries["ppt/slides/slide$index.xml"] = pptSlideXml(title, content, index).toByteArray()
    entries["ppt/slides/_rels/slide$index.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$R_NS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>""".toByteArray()
    updateContentTypes(entries, "/ppt/slides/slide$index.xml", "application/vnd.openxmlformats-officedocument.presentationml.slide+xml", true)
  }

  private fun deletePptSlide(entries: MutableMap<String, ByteArray>, slideNumber: Int) {
    val presentation = parseXml(entries["ppt/presentation.xml"] ?: error("Invalid PPTX presentation."))
    val rels = parseXml(entries["ppt/_rels/presentation.xml.rels"] ?: error("Invalid PPTX relationships."))
    val sldIds = presentation.getElementsByTagNameNS(P_NS, "sldId")
    require(sldIds.length > 1) { "Cannot delete the only slide." }
    require(slideNumber in 1..sldIds.length) { "Slide number out of range." }
    val sld = sldIds.item(slideNumber - 1) as Element
    val rid = sld.getAttributeNS(R_NS, "id").ifBlank { sld.getAttribute("r:id") }
    val relNodes = rels.getElementsByTagNameNS(REL_NS, "Relationship")
    var relNode: Element? = null
    var target = ""
    for (i in 0 until relNodes.length) {
      val rel = relNodes.item(i) as Element
      if (rel.getAttribute("Id") == rid) { relNode = rel; target = rel.getAttribute("Target"); break }
    }
    sld.parentNode.removeChild(sld)
    if (relNode != null) relNode.parentNode.removeChild(relNode)
    entries["ppt/presentation.xml"] = xmlBytes(presentation)
    entries["ppt/_rels/presentation.xml.rels"] = xmlBytes(rels)
    if (target.isNotBlank()) {
      val part = "ppt/${target.trimStart('/')}"
      entries.remove(part)
      val file = part.substringAfterLast('/')
      entries.remove("ppt/slides/_rels/$file.rels")
      updateContentTypes(entries, "/$part", "", false)
    }
  }

  private fun convertDocument(context: Context, root: DocumentFile, request: JSONObject): String {
    val input = normalizeInputPath(request.optString("input_path").ifBlank { request.optString("path") })
    val format = request.optString("output_format").ifBlank { request.optString("format") }
      .trim().lowercase(Locale.US).removePrefix(".")
      .let { when (it) { "word" -> "docx"; "text" -> "txt"; "htm" -> "html"; else -> it } }
    require(format in setOf("txt", "docx", "pdf", "html")) { "Supported output formats: txt, docx, pdf, html." }
    val inputExt = input.substringAfterLast('.', "").lowercase(Locale.US)
    require(inputExt in setOf("txt", "docx", "pdf", "html", "htm")) { "Supported input formats: txt, docx, pdf, html, htm." }
    val sourceBytes = readBytes(context, root, input)
    var text =
      if (inputExt in setOf("html", "htm")) Jsoup.parse(sourceBytes.toString(Charsets.UTF_8)).text()
      else WorkspaceDocumentTextExtractor.extract(input.substringAfterLast('/'), sourceBytes, 500_000, context).content
    if (text.length > 500_000) text = text.take(500_000)
    val base = input.substringAfterLast('/').substringBeforeLast('.').ifBlank { "converted" }
    val output = normalizeOutputPath(request.optString("output_path"), format, base)
    val bytes =
      when (format) {
        "txt" -> text.toByteArray()
        "docx" -> buildDocx("", text)
        "pdf" -> renderTextPdf("", text)
        "html" -> {
          val body = text.replace("\r\n", "\n").split("\n\n").filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${esc(it)}</p>" }
          """<!DOCTYPE html><html><head><meta charset="utf-8"><title>Converted Document</title></head><body>$body</body></html>""".toByteArray()
        }
        else -> error("Unsupported format.")
      }
    val mime =
      when (format) {
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pdf" -> "application/pdf"
        else -> "text/html"
      }
    val written = writeBytes(context, root, output, mime, bytes)
    return success("document_convert", output, written, JSONObject().put("input_path", input).put("output_format", format)
      .put("note", "Text-oriented conversion may simplify complex layout, media, formulas, and advanced styling."))
  }

  private fun updateContentTypes(
    entries: MutableMap<String, ByteArray>,
    partName: String,
    contentType: String,
    add: Boolean,
  ) {
    val path = "[Content_Types].xml"
    val doc = parseXml(entries[path] ?: error("Missing [Content_Types].xml"))
    val root = doc.documentElement
    val nodes = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types", "Override")
    var existing: Element? = null
    for (i in 0 until nodes.length) {
      val e = nodes.item(i) as Element
      if (e.getAttribute("PartName") == partName) { existing = e; break }
    }
    if (add && existing == null) {
      val override = doc.createElementNS(root.namespaceURI, "Override")
      override.setAttribute("PartName", partName)
      override.setAttribute("ContentType", contentType)
      root.appendChild(override)
    } else if (!add && existing != null) {
      existing.parentNode.removeChild(existing)
    }
    entries[path] = xmlBytes(doc)
  }

  private fun jsonStrings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
  }
}
