package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class AgentDocumentConversionMcp254Test {
  @Test
  fun evidenceBareDocxInput_isNormalizedIntoFileFolder() {
    assertThat(
      AgentDocumentConversionMcp254.normalizeWorkspaceInputPath("artistic_freedom_reflection.docx")
    ).isEqualTo("file/artistic_freedom_reflection.docx")
  }

  @Test
  fun evidenceBareChineseTxtInput_isNormalizedIntoFileFolder() {
    assertThat(
      AgentDocumentConversionMcp254.normalizeWorkspaceInputPath("2026世界杯决赛新闻.txt")
    ).isEqualTo("file/2026世界杯决赛新闻.txt")
  }

  @Test
  fun existingWorkspaceRelativeFolder_isPreserved() {
    assertThat(AgentDocumentConversionMcp254.normalizeWorkspaceInputPath("file/report.txt"))
      .isEqualTo("file/report.txt")
    assertThat(AgentDocumentConversionMcp254.normalizeWorkspaceInputPath("download/report.txt"))
      .isEqualTo("download/report.txt")
  }

  @Test
  fun allSixteenCanonicalFormatPairs_haveExplicitPlans() {
    val formats = listOf("txt", "docx", "pdf", "html")
    var binaryCopies = 0
    var textRoundTrips = 0
    for (input in formats) {
      for (output in formats) {
        when (AgentDocumentConversionMcp254.conversionPlan(input, output)) {
          AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY -> binaryCopies++
          AgentDocumentConversionMcp254.ConversionPlan.TEXT_ROUND_TRIP -> textRoundTrips++
        }
      }
    }
    assertThat(binaryCopies).isEqualTo(4)
    assertThat(textRoundTrips).isEqualTo(12)
  }

  @Test
  fun sameFormatRoutes_areBinaryPreservingCopies() {
    for (format in listOf("txt", "docx", "pdf", "html")) {
      assertThat(AgentDocumentConversionMcp254.conversionPlan(format, format))
        .isEqualTo(AgentDocumentConversionMcp254.ConversionPlan.BINARY_COPY)
    }
  }

  @Test
  fun htmlHtmInputAlias_isCanonicalButLegacyDocIsNotPretendedToBeDocx() {
    assertThat(AgentDocumentConversionMcp254.canonicalInputFormat("htm")).isEqualTo("html")
    assertThat(AgentDocumentConversionMcp254.supportedInput("htm")).isTrue()
    assertThat(AgentDocumentConversionMcp254.supportedInput("doc")).isFalse()
  }

  @Test
  fun outputAliases_areCanonicalizedWithoutChangingSupportedMatrix() {
    assertThat(AgentDocumentConversionMcp254.canonicalOutputFormat("word")).isEqualTo("docx")
    assertThat(AgentDocumentConversionMcp254.canonicalOutputFormat("text")).isEqualTo("txt")
    assertThat(AgentDocumentConversionMcp254.canonicalOutputFormat("htm")).isEqualTo("html")
  }

  @Test
  fun pdfLayoutWhitespace_doesNotInvalidateChineseSemanticContent() {
    val source = ("大语言模型通过注意力机制理解上下文并逐步生成文本。人工智能系统需要保持事实一致性与上下文连贯性。".repeat(18))
    val pdfExtracted = source.chunked(19).joinToString(" \n")

    assertThat(AgentDocumentConversionMcp254.preservesSemanticContent(source, pdfExtracted)).isTrue()
  }

  @Test
  fun semanticVerifier_rejectsMaterialTailLoss() {
    val source = ("文档转换必须验证首部中部和尾部内容，防止静默截断导致假成功。".repeat(30))
    val truncated = source.take((source.length * 0.68).toInt())

    assertThat(AgentDocumentConversionMcp254.preservesSemanticContent(source, truncated)).isFalse()
  }

  @Test
  fun directPdfExpectedFragmentComparison_ignoresLayoutWhitespace() {
    val expected = "2026世界杯决赛新闻包含比赛过程、关键进球以及赛后评论。"
    val extracted = "2026世界杯决赛新闻包含比赛\n过程、关键进球以及赛后评论。"

    assertThat(AgentDocumentConversionMcp254.containsIgnoringLayoutWhitespace(extracted, expected)).isTrue()
  }

  @Test
  fun sameWorkspacePath_handlesSlashAndLeadingSlashForms() {
    assertThat(
      AgentDocumentConversionMcp254.sameWorkspacePath(
        "file\\artistic_freedom_reflection.docx",
        "/file/artistic_freedom_reflection.docx",
      )
    ).isTrue()
  }

  @Test
  fun conversionTextLimit_isFailClosedAtUtf8ByteBoundary() {
    val exact = "a".repeat(AgentDocumentConversionMcp254.MAX_CONVERSION_TEXT_BYTES)
    val over = exact + "b"
    assertThat(AgentDocumentConversionMcp254.textFitsLimit(exact)).isTrue()
    assertThat(AgentDocumentConversionMcp254.textFitsLimit(over)).isFalse()
  }

  @Test
  fun conversionNormalizeRequest_appliesEvidencePathAndOutputAliases() {
    val request = JSONObject().put("input_path", "2026世界杯决赛新闻.txt").put("output_format", "word")
    AgentDocumentConversionMcp254.normalizeRequest(DOCUMENT_CONVERT_SKILL_NAME, request)

    assertThat(request.getString("input_path")).isEqualTo("file/2026世界杯决赛新闻.txt")
    assertThat(request.getString("output_format")).isEqualTo("docx")
    assertThat(request.getBoolean(AgentDocumentConversionMcp254.META_INPUT_PATH_NORMALIZED)).isTrue()
  }

  @Test
  fun pdfMergeBareInputArray_usesSameWorkspacePathTolerance() {
    val request =
      JSONObject().put(
        "input_paths",
        JSONArray().put("a.pdf").put("file/b.pdf").put("download/c.pdf"),
      )
    AgentDocumentConversionMcp254.normalizeOfficeInputArrays(PDF_DOCUMENT_SKILL_NAME, request)
    val paths = request.getJSONArray("input_paths")
    assertThat(paths.getString(0)).isEqualTo("file/a.pdf")
    assertThat(paths.getString(1)).isEqualTo("file/b.pdf")
    assertThat(paths.getString(2)).isEqualTo("download/c.pdf")
  }
}
