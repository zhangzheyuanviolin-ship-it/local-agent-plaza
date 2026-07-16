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
)

object WorkspaceDocumentTextExtractor {
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
    val sheetXml = mutableListOf<String>()
    zipEntries(bytes) { name, content ->
      when {
        name == "xl/sharedStrings.xml" -> sharedStrings += extractSharedStrings(content)
        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> sheetXml += content
      }
    }
    if (sheetXml.isEmpty()) return sharedStrings.joinToString("\n")
    return sheetXml
      .mapIndexed { index, xml -> "Sheet ${index + 1}\n${extractSheetText(xml, sharedStrings)}" }
      .joinToString("\n\n")
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

  private fun extractSheetText(xml: String, sharedStrings: List<String>): String {
    return Regex("<row[\\s\\S]*?</row>")
      .findAll(xml)
      .map { row ->
        Regex("<c([^>]*)>[\\s\\S]*?</c>")
          .findAll(row.value)
          .map { cell ->
            val attrs = cell.groupValues[1]
            val rawValue = Regex("<v[^>]*>([\\s\\S]*?)</v>").find(cell.value)?.groupValues?.get(1)
            when {
              rawValue == null -> xmlText(cell.value)
              attrs.contains("t=\"s\"") ->
                rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
              else -> xmlUnescape(rawValue)
            }
          }
          .filter { it.isNotBlank() }
          .joinToString("\t")
      }
      .filter { it.isNotBlank() }
      .joinToString("\n")
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
