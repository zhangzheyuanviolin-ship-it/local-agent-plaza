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
import java.text.DecimalFormat
import java.time.LocalDate
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
  private data class XlsxStyles(val numFmtByStyleIndex: Map<Int, String>)

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
    var stylesXml = ""
    val sheetXml = mutableListOf<Pair<String, String>>()
    zipEntries(bytes) { name, content ->
      when {
        name == "xl/workbook.xml" -> workbookXml = content
        name == "xl/styles.xml" -> stylesXml = content
        name == "xl/sharedStrings.xml" -> sharedStrings += extractSharedStrings(content)
        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> sheetXml += name to content
      }
    }
    if (sheetXml.isEmpty()) return sharedStrings.joinToString("\n")
    val sheetNames = extractSheetNames(workbookXml)
    val styles = extractStyles(stylesXml)
    val sortedSheets =
      sheetXml.sortedBy { (name, _) ->
        Regex("sheet(\\d+)\\.xml").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
      }
    val body =
      sortedSheets
        .mapIndexed { index, (_, xml) ->
          val sheetName = sheetNames.getOrNull(index) ?: "Sheet ${index + 1}"
          "工作表: $sheetName\n${extractSheetText(sheetName, xml, sharedStrings, styles)}"
        }
        .joinToString("\n\n")
    return (
        "XLSX 结构化抽取结果。优先根据“行事实”回答；保留原始单位、数值、年份和列名；不要换算、推导、四舍五入、补全或把相邻行列的数字重新解释为其他指标；不要根据文件名或常识补全表格中没有的数据。\n\n" +
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

  private fun extractStyles(xml: String): XlsxStyles {
    if (xml.isBlank()) {
      return XlsxStyles(emptyMap())
    }
    val customNumFmts =
      Regex("<numFmt\\b([^>]*)/>")
        .findAll(xml)
        .mapNotNull { match ->
          val attrs = match.groupValues[1]
          val id = Regex("numFmtId=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
          val code = Regex("formatCode=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
          if (id != null && code != null) id to xmlUnescape(code) else null
        }
        .toMap()
    val builtInNumFmts =
      mapOf(
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        12 to "# ?/?",
        13 to "# ??/??",
        14 to "m/d/yy",
        15 to "d-mmm-yy",
        16 to "d-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        22 to "m/d/yy h:mm",
        37 to "#,##0 ;(#,##0)",
        38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)",
        40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mmss.0",
        48 to "##0.0E+0",
        49 to "@",
      )
    val numFmtById = builtInNumFmts + customNumFmts
    val cellXfs = Regex("<cellXfs\\b[^>]*>([\\s\\S]*?)</cellXfs>").find(xml)?.groupValues?.get(1)
    val styles =
      cellXfs
        ?.let {
          Regex("<xf\\b([^>]*)/?>")
            .findAll(it)
            .mapIndexedNotNull { index, match ->
              val numFmtId =
                Regex("numFmtId=\"(\\d+)\"")
                  .find(match.groupValues[1])
                  ?.groupValues
                  ?.get(1)
                  ?.toIntOrNull()
              val formatCode = numFmtId?.let(numFmtById::get)
              if (formatCode == null) null else index to formatCode
            }
            .toMap()
        }
        .orEmpty()
    return XlsxStyles(styles)
  }

  private fun extractSheetText(
    sheetName: String,
    xml: String,
    sharedStrings: List<String>,
    styles: XlsxStyles,
  ): String {
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
                .map { cell -> extractXlsxCell(cell.groupValues[1], cell.value, sharedStrings, styles) }
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
        lines += narrativeLine(sheetName, row, currentHeader)
      } else if (hasGapBefore && cells.size == 1) {
        flushTable()
        currentHeader = emptyMap()
        lines += "NOTE|$sheetName!${cells.first().ref}|${cells.first().text}"
        lines += narrativeLine(sheetName, row, currentHeader)
      } else if (currentHeader.isNotEmpty()) {
        lines += factLine(sheetName, row, currentHeader)
        lines += narrativeLine(sheetName, row, currentHeader)
        currentTableRows += row
      } else {
        lines +=
          "ROW|$sheetName!$rowLabel|" +
            cells.joinToString("|") { "${it.ref.ifBlank { it.column }}=${it.text}" }
        lines += narrativeLine(sheetName, row, currentHeader)
      }
    }
    flushTable()
    return lines.joinToString("\n")
  }

  private fun extractXlsxCell(
    attrs: String,
    xml: String,
    sharedStrings: List<String>,
    styles: XlsxStyles,
  ): XlsxCell {
    val ref = Regex("r=\"([A-Z]+\\d+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
    val column = ref.takeWhile { it.isLetter() }
    val rawValue = Regex("<v[^>]*>([\\s\\S]*?)</v>").find(xml)?.groupValues?.get(1)
    val inlineString = Regex("<is[\\s\\S]*?</is>").find(xml)?.value
    val styleIndex = Regex("s=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
    val numberFormat = styleIndex?.let(styles.numFmtByStyleIndex::get)
    val text =
      when {
        attrs.contains("t=\"s\"") && rawValue != null ->
          rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
        inlineString != null -> xmlText(inlineString)
        rawValue != null -> formatXlsxValue(xmlUnescape(rawValue), numberFormat)
        else -> xmlText(xml)
      }
    return XlsxCell(ref = ref, column = column, text = text)
  }

  private fun formatXlsxValue(rawValue: String, numberFormat: String?): String {
    val value = rawValue.toDoubleOrNull() ?: return rawValue
    val format = numberFormat.orEmpty().lowercase(Locale.US)
    return when {
      format.contains("%") -> formatPercent(value, format)
      isDateFormat(format) -> formatExcelDate(value)
      rawValue.matches(Regex("-?\\d+\\.0+")) -> rawValue.substringBefore(".")
      else -> rawValue
    }
  }

  private fun formatPercent(value: Double, format: String): String {
    val decimals = Regex("0\\.(0+)%").find(format)?.groupValues?.get(1)?.length ?: 0
    val pattern = if (decimals > 0) "0.${"0".repeat(decimals)}%" else "0%"
    return DecimalFormat(pattern)
      .format(value)
      .replace(Regex("(\\.\\d*?)0+%$"), "\$1%")
      .replace(".%", "%")
  }

  private fun isDateFormat(format: String): Boolean {
    if (format.isBlank() || format == "@") {
      return false
    }
    val cleaned = format.replace(Regex("\\[[^]]+]"), "")
    return cleaned.any { it in "ymdhHsS" } && !cleaned.contains("%")
  }

  private fun formatExcelDate(serial: Double): String {
    if (serial < 1.0) {
      return serial.toString()
    }
    val date = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
    return date.toString()
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

  private fun narrativeLine(
    sheetName: String,
    row: XlsxRow,
    headerByColumn: Map<String, String>,
  ): String {
    val rowLabel = "R${row.number ?: "?"}"
    val facts =
      row.cells.joinToString("；") { cell ->
        val header = headerByColumn[cell.column]?.takeIf { it.isNotBlank() }
        val label = header ?: "${cell.column.ifBlank { cell.ref }}列"
        "$label 是 ${cell.text}"
      }
    return "行事实|$sheetName!$rowLabel|$facts。"
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
      listOf(
        "类别",
        "指标",
        "数据",
        "来源",
        "备注",
        "年份",
        "平台",
        "区域",
        "市场份额",
        "增长率",
        "趋势",
        "数值",
        "可信度",
        "category",
        "metric",
        "indicator",
        "value",
        "growth",
        "trend",
        "source",
        "region",
        "year",
        "note",
      )
    if (texts.any { text -> headerKeywords.any { keyword -> text.lowercase(Locale.US).contains(keyword) } }) {
      return true
    }
    val compactTextCells = texts.count { text -> text.length <= 32 && !text.any(Char::isDigit) }
    return compactTextCells == texts.size && texts.sumOf { it.length } <= 96
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
