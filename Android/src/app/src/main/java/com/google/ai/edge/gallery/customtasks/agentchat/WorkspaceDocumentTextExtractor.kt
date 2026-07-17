/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class WorkspaceExtractedText(
  val content: String,
  val detectedFormat: String,
  val contentType: String,
  val truncated: Boolean,
  val bytesRead: Int,
  val contentChars: Int,
  val contentBytes: Int,
)

object WorkspaceDocumentTextExtractor {
  private data class XlsxCell(val ref: String, val column: String, val text: String)
  private data class XlsxRow(val number: Int?, val cells: List<XlsxCell>)

  fun extract(
    fileName: String,
    bytes: ByteArray,
    maxBytes: Int,
    context: Context? = null,
  ): WorkspaceExtractedText {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    val extracted =
      when (extension) {
        "txt", "md", "csv", "json", "xml", "log", "html", "htm" ->
          bytes.toString(Charsets.UTF_8) to "text"
        "docx" -> extractDocx(bytes) to "docx"
        "xlsx" -> extractXlsx(bytes) to "xlsx"
        "pdf" -> extractPdf(bytes, context) to "pdf"
        else -> bytes.toString(Charsets.UTF_8) to extension.ifBlank { "text" }
      }
    val capped = capUtf8(extracted.first, maxBytes)
    return WorkspaceExtractedText(
      content = capped.first,
      detectedFormat = extracted.second,
      contentType = contentTypeForFormat(extracted.second),
      truncated = capped.second,
      bytesRead = bytes.size,
      contentChars = capped.first.length,
      contentBytes = capped.first.toByteArray(Charsets.UTF_8).size,
    )
  }

  private fun extractDocx(bytes: ByteArray): String {
    val chunks = mutableListOf<String>()
    zipEntries(bytes) { name, content ->
      if (
        name == "word/document.xml" ||
          name.startsWith("word/header") ||
          name.startsWith("word/footer")
      ) {
        chunks += xmlText(content)
      }
    }
    return chunks.joinToString("\n").trim()
  }

  private fun extractXlsx(bytes: ByteArray): String {
    val sharedStrings = mutableListOf<String>()
    var workbookXml = ""
    val sheetXml = mutableListOf<Pair<String, String>>()
    zipEntries(bytes) { name, content ->
      when {
        name == "xl/workbook.xml" -> workbookXml = content
        name == "xl/sharedStrings.xml" -> sharedStrings += extractSharedStrings(content)
        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> sheetXml += name to content
      }
    }
    if (sheetXml.isEmpty()) return sharedStrings.joinToString("\n")
    val sheetNames = extractSheetNames(workbookXml)
    val sortedSheets =
      sheetXml.sortedBy { (name, _) ->
        Regex("sheet(\\d+)\\.xml").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
      }
    val body =
      sortedSheets
        .mapIndexed { index, (_, xml) ->
          val sheetName = sheetNames.getOrNull(index) ?: "Sheet ${index + 1}"
          "工作表: $sheetName\n${extractSheetText(sheetName, xml, sharedStrings)}"
        }
        .joinToString("\n\n")
    return (
        "XLSX 结构化抽取结果。只根据下面的单元格事实和表格回答；保留原始单位、数值、年份和列名；不要把相邻行列的数字重新解释为其他指标；不要根据文件名或常识补全表格中没有的数据。\n\n" +
          body
        )
      .trim()
  }

  private fun extractPdf(bytes: ByteArray, context: Context?): String {
    if (context != null) {
      PDFBoxResourceLoader.init(context)
    }
    PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
      return PDFTextStripper().getText(document).trim()
    }
  }

  private fun zipEntries(bytes: ByteArray, onEntry: (String, String) -> Unit) {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) {
          val content = zip.readBytes().toString(Charsets.UTF_8)
          onEntry(entry.name, content)
        }
        zip.closeEntry()
      }
    }
  }

  private fun extractSharedStrings(xml: String): List<String> {
    return Regex("<si[\\s\\S]*?</si>").findAll(xml).map { xmlText(it.value) }.toList()
  }

  private fun extractSheetNames(workbookXml: String): List<String> {
    if (workbookXml.isBlank()) {
      return emptyList()
    }
    return Regex("<sheet\\b[^>]*name=\"([^\"]+)\"")
      .findAll(workbookXml)
      .map { xmlUnescape(it.groupValues[1]) }
      .toList()
  }

  private fun extractSheetText(sheetName: String, xml: String, sharedStrings: List<String>): String {
    val rows =
      Regex("<row([^>]*)>[\\s\\S]*?</row>")
        .findAll(xml)
        .map { row ->
          val rowNumber =
            Regex("r=\"(\\d+)\"").find(row.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()
          XlsxRow(
            number = rowNumber,
            cells =
              Regex("<c([^>]*)>[\\s\\S]*?</c>")
                .findAll(row.value)
                .map { cell -> extractXlsxCell(cell.groupValues[1], cell.value, sharedStrings) }
                .filter { it.text.isNotBlank() }
                .toList(),
          )
        }
        .filter { it.cells.isNotEmpty() }
        .toList()
    val lines = mutableListOf<String>()
    var currentHeader = emptyMap<String, String>()
    val currentTableRows = mutableListOf<XlsxRow>()

    fun flushTable() {
      if (currentHeader.isNotEmpty() && currentTableRows.isNotEmpty()) {
        lines += markdownTable(currentHeader, currentTableRows)
      }
      currentTableRows.clear()
    }

    rows.forEachIndexed { index, row ->
      val cells = row.cells
      val texts = cells.map { it.text }
      val rowNumber = row.number ?: index + 1
      val rowLabel = "R$rowNumber"
      val previousRowNumber = rows.getOrNull(index - 1)?.number
      val hasGapBefore =
        row.number != null && previousRowNumber != null && row.number - previousRowNumber > 1

      if (isLikelyHeaderRow(texts)) {
        flushTable()
        currentHeader = cells.associate { it.column to it.text }
        lines +=
          "HEADER|$sheetName!$rowLabel|" +
            cells.joinToString("|") { "${it.column.ifBlank { it.ref }}=${it.text}" }
      } else if (hasGapBefore && cells.size == 1) {
        flushTable()
        currentHeader = emptyMap()
        lines += "NOTE|$sheetName!${cells.first().ref}|${cells.first().text}"
      } else if (currentHeader.isNotEmpty()) {
        lines += factLine(sheetName, row, currentHeader)
        currentTableRows += row
      } else {
        lines +=
          "ROW|$sheetName!$rowLabel|" +
            cells.joinToString("|") { "${it.ref.ifBlank { it.column }}=${it.text}" }
      }
    }
    flushTable()
    return lines.joinToString("\n")
  }

  private fun extractXlsxCell(attrs: String, xml: String, sharedStrings: List<String>): XlsxCell {
    val ref = Regex("r=\"([A-Z]+\\d+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
    val column = ref.takeWhile { it.isLetter() }
    val rawValue = Regex("<v[^>]*>([\\s\\S]*?)</v>").find(xml)?.groupValues?.get(1)
    val inlineString = Regex("<is[\\s\\S]*?</is>").find(xml)?.value
    val text =
      when {
        attrs.contains("t=\"s\"") && rawValue != null ->
          rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
        inlineString != null -> xmlText(inlineString)
        rawValue != null -> xmlUnescape(rawValue)
        else -> xmlText(xml)
      }
    return XlsxCell(ref = ref, column = column, text = text)
  }

  private fun factLine(sheetName: String, row: XlsxRow, headerByColumn: Map<String, String>): String {
    val rowLabel = "R${row.number ?: "?"}"
    return "FACT|$sheetName!$rowLabel|" +
      row.cells.joinToString("|") { cell ->
        val header = headerByColumn[cell.column]?.takeIf { it.isNotBlank() }
        val label = if (header != null) "${cell.column}[$header]" else cell.ref.ifBlank { cell.column }
        "$label=${cell.text}"
      }
  }

  private fun markdownTable(headerByColumn: Map<String, String>, rows: List<XlsxRow>): String {
    val columns =
      headerByColumn.keys
        .toMutableSet()
        .apply { rows.flatMap { row -> row.cells.map { it.column } }.forEach(::add) }
        .sortedWith(compareBy(::columnIndex))
    val header =
      "| 行号 | ${columns.joinToString(" | ") { "${it} ${headerByColumn[it].orEmpty()}".trim() }} |"
    val divider = "| --- | ${columns.joinToString(" | ") { "---" }} |"
    val body =
      rows.joinToString("\n") { row ->
        val byColumn = row.cells.associateBy { it.column }
        "| ${row.number ?: ""} | " +
          columns.joinToString(" | ") { escapeMarkdownCell(byColumn[it]?.text.orEmpty()) } +
          " |"
      }
    return listOf("TABLE", header, divider, body).joinToString("\n")
  }

  private fun columnIndex(column: String): Int {
    var result = 0
    for (char in column) {
      if (char in 'A'..'Z') {
        result = result * 26 + (char - 'A' + 1)
      }
    }
    return result
  }

  private fun escapeMarkdownCell(text: String): String {
    return text.replace("|", "\\|").replace("\n", " ").trim()
  }

  private fun isLikelyHeaderRow(texts: List<String>): Boolean {
    if (texts.size < 2) {
      return false
    }
    val headerKeywords =
      listOf("类别", "指标", "数据", "来源", "备注", "年份", "平台", "区域", "市场份额", "增长率", "趋势", "数值", "可信度")
    return texts.any { text -> headerKeywords.any { keyword -> text.contains(keyword) } }
  }

  private fun xmlText(xml: String): String {
    return xml
      .replace(Regex("</w:p>|</p>|</row>|</si>"), "\n")
      .replace(Regex("<[^>]+>"), " ")
      .let(::xmlUnescape)
      .replace(Regex("[ \\t\\r]+"), " ")
      .replace(Regex(" *\\n+ *"), "\n")
      .trim()
  }

  private fun xmlUnescape(text: String): String {
    return text
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&amp;", "&")
  }

  private fun capUtf8(text: String, maxBytes: Int): Pair<String, Boolean> {
    val safeMaxBytes = maxBytes.coerceAtLeast(1)
    var usedBytes = 0
    val builder = StringBuilder()
    for (char in text) {
      val charBytes = char.toString().toByteArray(Charsets.UTF_8).size
      if (usedBytes + charBytes > safeMaxBytes) {
        return builder.toString() to true
      }
      builder.append(char)
      usedBytes += charBytes
    }
    return text to false
  }

  private fun contentTypeForFormat(format: String): String {
    return when (format) {
      "pdf" -> "application/pdf"
      "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      else -> "text/plain"
    }
  }
}
