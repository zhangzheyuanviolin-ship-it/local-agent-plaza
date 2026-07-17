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
        "工作表: $sheetName\n${extractSheetText(xml, sharedStrings)}"
      }
      .joinToString("\n\n")
    return (
        "XLSX 结构化抽取结果。请严格按每行的“列名=值”回答，保留原始单位和数值，不要把相邻行列的数字重新解释为其他指标。\n\n" +
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

  private fun extractSheetText(xml: String, sharedStrings: List<String>): String {
    val rows =
      Regex("<row([^>]*)>[\\s\\S]*?</row>")
      .findAll(xml)
      .map { row ->
        val rowNumber =
          Regex("r=\"(\\d+)\"").find(row.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()
        Regex("<c([^>]*)>[\\s\\S]*?</c>")
          .findAll(row.value)
          .map { cell ->
            val attrs = cell.groupValues[1]
            val ref = Regex("r=\"([A-Z]+\\d+)\"").find(attrs)?.groupValues?.get(1).orEmpty()
            val column = ref.takeWhile { it.isLetter() }
            val rawValue = Regex("<v[^>]*>([\\s\\S]*?)</v>").find(cell.value)?.groupValues?.get(1)
            val text =
              when {
              rawValue == null -> xmlText(cell.value)
              attrs.contains("t=\"s\"") ->
                rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
              else -> xmlUnescape(rawValue)
            }
            XlsxCell(ref = ref, column = column, text = text)
          }
          .filter { it.text.isNotBlank() }
          .toList() to rowNumber
      }
      .filter { (cells, _) -> cells.isNotEmpty() }
      .toList()
    val lines = mutableListOf<String>()
    var headerByColumn = emptyMap<String, String>()
    rows.forEachIndexed { index, (cells, rowNumber) ->
      val texts = cells.map { it.text }
      val rowLabel = "第 ${rowNumber ?: index + 1} 行"
      if (isLikelyHeaderRow(texts)) {
        headerByColumn = cells.associate { it.column to it.text }
        lines += "$rowLabel 表头: ${cells.joinToString("; ") { "${it.column.ifBlank { it.ref }}=${it.text}" }}"
      } else {
        val values =
          cells.joinToString("; ") { cell ->
            val label = headerByColumn[cell.column]?.takeIf { it.isNotBlank() } ?: cell.ref.ifBlank { cell.column }
            "$label=${cell.text}"
          }
        lines += "$rowLabel: $values"
      }
    }
    return lines.joinToString("\n")
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
