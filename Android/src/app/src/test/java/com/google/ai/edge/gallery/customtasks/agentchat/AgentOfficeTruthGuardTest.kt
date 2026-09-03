package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class AgentOfficeTruthGuardTest {
  @Test
  fun wordMissingOperation_doesNotDefaultToCreate() {
    val raw =
      JSONObject()
        .put("input_path", "file/self_introduction.docx")
        .put("text", "追加一段关于大语言模型运作原理的介绍。")

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("office_auto")
    assertThat(normalized.getString("content")).contains("大语言模型")
    assertThat(normalized.getString("_mcp252_resolution")).isEqualTo("deferred_safe_inference")
  }

  @Test
  fun wordReadMissingOperation_doesNotBecomeCreate() {
    val raw = JSONObject().put("path", "file/self_introduction.docx")

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("office_auto")
  }

  @Test
  fun wordCreate_acceptsBodyAlias() {
    val raw =
      JSONObject()
        .put("operation", "create")
        .put("output_path", "file/self_introduction.docx")
        .put("body", "这是一段真实正文。")

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("word_create")
    assertThat(normalized.getString("content")).isEqualTo("这是一段真实正文。")
  }

  @Test
  fun wordAppendExplicitAlias_becomesModifyWithOperationArray() {
    val raw =
      JSONObject()
        .put("action", "append")
        .put("file_path", "file/self_introduction.docx")
        .put("text_to_append", "新增段落")

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("word_modify")
    assertThat(normalized.getJSONArray("operations").getJSONObject(0).getString("action"))
      .isEqualTo("add_paragraph")
  }

  @Test
  fun excelAndPowerPointMissingOperation_doNotDefaultToCreate() {
    val excel =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = EXCEL_WORKBOOK_SKILL_NAME,
        rawArguments = JSONObject().put("path", "file/data.xlsx").put("rows", org.json.JSONArray().put(org.json.JSONArray().put("x"))),
      )
    val ppt =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = POWERPOINT_PRESENTATION_SKILL_NAME,
        rawArguments = JSONObject().put("path", "file/slides.pptx").put("text", "新增内容"),
      )

    assertThat(excel.getString("operation")).isEqualTo("office_auto")
    assertThat(ppt.getString("operation")).isEqualTo("office_auto")
  }

  @Test
  fun conversionAliases_areCanonicalized() {
    val raw =
      JSONObject()
        .put("source_path", "file/source.txt")
        .put("target_format", "docx")

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = DOCUMENT_CONVERT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("document_convert")
    assertThat(normalized.getString("input_path")).isEqualTo("file/source.txt")
    assertThat(normalized.getString("output_format")).isEqualTo("docx")
  }
}
