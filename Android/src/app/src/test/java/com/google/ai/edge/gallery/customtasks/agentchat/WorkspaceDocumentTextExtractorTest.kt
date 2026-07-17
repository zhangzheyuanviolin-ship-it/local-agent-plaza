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

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDocumentTextExtractorTest {
  @Test
  fun plainTextExtractionTruncatesAtMaxBytes() {
    val result =
      WorkspaceDocumentTextExtractor.extract(
        fileName = "notes.txt",
        bytes = "abcdef".toByteArray(),
        maxBytes = 3,
      )

    assertEquals("abc", result.content)
    assertEquals(true, result.truncated)
    assertEquals("text", result.detectedFormat)
  }

  @Test
  fun docxExtractionReadsDocumentXmlText() {
    val bytes =
      zipBytes(
        "word/document.xml" to
          "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t> world</w:t></w:r></w:p></w:body></w:document>"
      )

    val result =
      WorkspaceDocumentTextExtractor.extract(fileName = "sample.docx", bytes = bytes, maxBytes = 2000)

    assertTrue(result.content.contains("Hello world"))
    assertEquals("docx", result.detectedFormat)
  }

  @Test
  fun xlsxExtractionReadsSharedStringsAndNumbers() {
    val bytes =
      zipBytes(
        "xl/sharedStrings.xml" to
          "<sst><si><t>Name</t></si><si><t>Score</t></si></sst>",
        "xl/worksheets/sheet1.xml" to
          "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c><c><v>98</v></c></row></sheetData></worksheet>",
      )

    val result =
      WorkspaceDocumentTextExtractor.extract(fileName = "sample.xlsx", bytes = bytes, maxBytes = 2000)

    assertTrue(result.content.contains("Name"))
    assertTrue(result.content.contains("Score"))
    assertTrue(result.content.contains("98"))
    assertEquals("xlsx", result.detectedFormat)
  }

  @Test
  fun xlsxExtractionBindsDataRowsToHeaders() {
    val bytes =
      zipBytes(
        "xl/workbook.xml" to
          "<workbook><sheets><sheet name=\"执行摘要\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/worksheets/sheet1.xml" to
          """
          <worksheet><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>核心指标</t></is></c><c r="B1" t="inlineStr"><is><t>2025 年数值</t></is></c><c r="C1" t="inlineStr"><is><t>增长率</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>市场规模</t></is></c><c r="B2" t="inlineStr"><is><t>95 亿美元</t></is></c><c r="C2" t="inlineStr"><is><t>+53%</t></is></c></row>
          </sheetData></worksheet>
          """.trimIndent(),
      )

    val result =
      WorkspaceDocumentTextExtractor.extract(fileName = "sample.xlsx", bytes = bytes, maxBytes = 4000)

    assertTrue(result.content.contains("工作表: 执行摘要"))
    assertTrue(result.content.contains("第 2 行: 核心指标=市场规模; 2025 年数值=95 亿美元; 增长率=+53%"))
    assertTrue(result.content.contains("不要把相邻行列的数字重新解释为其他指标"))
  }

  private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }
}
