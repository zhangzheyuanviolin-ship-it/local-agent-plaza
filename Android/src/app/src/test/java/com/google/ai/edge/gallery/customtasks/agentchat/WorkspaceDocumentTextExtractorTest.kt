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
    assertTrue(result.content.contains("FACT|执行摘要!R2|A[核心指标]=市场规模|B[2025 年数值]=95 亿美元|C[增长率]=+53%"))
    assertTrue(result.content.contains("行事实|执行摘要!R2|核心指标 是 市场规模；2025 年数值 是 95 亿美元；增长率 是 +53%。"))
    assertTrue(result.content.contains("| 行号 | A 核心指标 | B 2025 年数值 | C 增长率 |"))
    assertTrue(result.content.contains("| 2 | 市场规模 | 95 亿美元 | +53% |"))
    assertTrue(result.content.contains("优先根据“行事实”回答"))
  }

  @Test
  fun xlsxExtractionFormatsStyledPercentCells() {
    val bytes =
      zipBytes(
        "xl/workbook.xml" to
          "<workbook><sheets><sheet name=\"指标\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/styles.xml" to
          """
          <styleSheet>
            <cellXfs count="2">
              <xf numFmtId="0"/>
              <xf numFmtId="10"/>
            </cellXfs>
          </styleSheet>
          """.trimIndent(),
        "xl/worksheets/sheet1.xml" to
          """
          <worksheet><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>指标</t></is></c><c r="B1" t="inlineStr"><is><t>增长率</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>流媒体</t></is></c><c r="B2" s="1"><v>0.53</v></c></row>
          </sheetData></worksheet>
          """.trimIndent(),
      )

    val result =
      WorkspaceDocumentTextExtractor.extract(fileName = "sample.xlsx", bytes = bytes, maxBytes = 4000)

    assertTrue(result.content.contains("FACT|指标!R2|A[指标]=流媒体|B[增长率]=53%"))
    assertTrue(result.content.contains("行事实|指标!R2|指标 是 流媒体；增长率 是 53%。"))
  }

  @Test
  fun xlsxExtractionEmitsNarrativeRowsWithoutHeaders() {
    val bytes =
      zipBytes(
        "xl/workbook.xml" to
          "<workbook><sheets><sheet name=\"说明\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/worksheets/sheet1.xml" to
          """
          <worksheet><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>五大关键发现</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>1. 市场增长</t></is></c><c r="B2" t="inlineStr"><is><t>2025 年达 95 亿美元</t></is></c></row>
          </sheetData></worksheet>
          """.trimIndent(),
      )

    val result =
      WorkspaceDocumentTextExtractor.extract(fileName = "sample.xlsx", bytes = bytes, maxBytes = 4000)

    assertTrue(result.content.contains("行事实|说明!R2|A列 是 1. 市场增长；B列 是 2025 年达 95 亿美元。"))
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
