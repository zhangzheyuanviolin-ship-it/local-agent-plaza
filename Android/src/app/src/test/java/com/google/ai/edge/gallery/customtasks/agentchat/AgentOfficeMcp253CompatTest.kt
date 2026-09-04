package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class AgentOfficeMcp253CompatTest {
  @Test
  fun evidence135415_wrappedStringCreate_isPromotedToWordCreate() {
    val raw =
      JSONObject()
        .put("title", "自我介绍")
        .put("content", "真实正文")
        .put("operations", JSONArray().put("create"))

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("word_create")
    assertThat(normalized.has("operations")).isFalse()
    assertThat(normalized.optBoolean(AgentOfficeMcp253Compat.META_PROMOTED_ROOT_OPERATION)).isTrue()
  }

  @Test
  fun evidence135510_wrappedObjectCreate_isPromotedToWordCreate() {
    val raw =
      JSONObject()
        .put("title", "自我介绍")
        .put("content", "真实正文")
        .put("operations", JSONArray().put(JSONObject().put("operation", "create")))

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("word_create")
    assertThat(normalized.has("operations")).isFalse()
  }

  @Test
  fun evidence135740_typeAppendWithDirectContent_becomesCanonicalParagraph() {
    val raw =
      JSONObject()
        .put("input_path", "file/self_intro.docx")
        .put(
          "operations",
          JSONArray().put(
            JSONObject()
              .put("type", "append")
              .put("content", "大语言模型的运行原理介绍")
          ),
        )

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    assertThat(normalized.getString("operation")).isEqualTo("office_auto")
    val op = normalized.getJSONArray("operations").getJSONObject(0)
    assertThat(op.getString("action")).isEqualTo("add_paragraph")
    assertThat(op.getJSONObject("params").getString("text")).isEqualTo("大语言模型的运行原理介绍")
  }

  @Test
  fun evidence135032_operationAppendWithDirectContent_becomesCanonicalParagraph() {
    val raw =
      JSONObject()
        .put("input_path", "file/document.docx")
        .put(
          "operations",
          JSONArray().put(
            JSONObject()
              .put("operation", "append")
              .put("content", "追加内容")
          ),
        )

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        rawArguments = raw,
      )

    val op = normalized.getJSONArray("operations").getJSONObject(0)
    assertThat(op.getString("action")).isEqualTo("add_paragraph")
    assertThat(op.getJSONObject("params").getString("text")).isEqualTo("追加内容")
  }

  @Test
  fun wordAutoName_usesTitleAndDeduplicatesInsteadOfDocumentDocx() {
    val request = JSONObject().put("title", "自我介绍")
    val suggested =
      AgentOfficeMcp253Compat.suggestedAutoOutputPath(
        skillName = WORD_DOCUMENT_SKILL_NAME,
        request = request,
        operation = "word_create",
      )

    assertThat(suggested).isEqualTo("file/自我介绍.docx")
    val existing = setOf("file/自我介绍.docx", "file/自我介绍-2.docx")
    val unique = AgentOfficeMcp253Compat.chooseUniquePath(suggested!!) { it in existing }
    assertThat(unique).isEqualTo("file/自我介绍-3.docx")
  }

  @Test
  fun sourceSharedExcelEnvelope_appendRowAlias_isCanonicalized() {
    val raw =
      JSONObject()
        .put("input_path", "file/data.xlsx")
        .put(
          "operations",
          JSONArray().put(
            JSONObject()
              .put("type", "append_row")
              .put("values", JSONArray().put("A").put("B"))
          ),
        )

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = EXCEL_WORKBOOK_SKILL_NAME,
        rawArguments = raw,
      )
    val op = normalized.getJSONArray("operations").getJSONObject(0)
    assertThat(op.getString("action")).isEqualTo("add_row")
    assertThat(op.getJSONObject("params").getJSONArray("values").length()).isEqualTo(2)
  }

  @Test
  fun sourceSharedPptEnvelope_appendSlideAlias_isCanonicalized() {
    val raw =
      JSONObject()
        .put("input_path", "file/slides.pptx")
        .put(
          "operations",
          JSONArray().put(
            JSONObject()
              .put("operation", "append_slide")
              .put("title", "新增页")
              .put("content", "正文")
          ),
        )

    val normalized =
      AgentOfficeTruthGuard.prepareCompatRequest(
        skillName = POWERPOINT_PRESENTATION_SKILL_NAME,
        rawArguments = raw,
      )
    val op = normalized.getJSONArray("operations").getJSONObject(0)
    assertThat(op.getString("action")).isEqualTo("add_slide")
    assertThat(op.getJSONObject("params").getString("title")).isEqualTo("新增页")
    assertThat(op.getJSONObject("params").getString("content")).isEqualTo("正文")
  }

  @Test
  fun sourceSharedCreateDefaults_areSemanticAcrossOfficeSkills() {
    assertThat(
      AgentOfficeMcp253Compat.suggestedAutoOutputPath(
        PDF_DOCUMENT_SKILL_NAME,
        JSONObject().put("title", "项目说明"),
        "pdf_create",
      )
    ).isEqualTo("file/项目说明.pdf")

    assertThat(
      AgentOfficeMcp253Compat.suggestedAutoOutputPath(
        EXCEL_WORKBOOK_SKILL_NAME,
        JSONObject().put("sheet_name", "销售数据"),
        "xlsx_create",
      )
    ).isEqualTo("file/销售数据.xlsx")

    assertThat(
      AgentOfficeMcp253Compat.suggestedAutoOutputPath(
        POWERPOINT_PRESENTATION_SKILL_NAME,
        JSONObject().put("title", "课程介绍"),
        "pptx_create",
      )
    ).isEqualTo("file/课程介绍.pptx")
  }

  @Test
  fun conversionWithoutOutputPath_avoidsSameExtensionSelfOverwrite() {
    val suggested =
      AgentOfficeMcp253Compat.suggestedAutoOutputPath(
        DOCUMENT_CONVERT_SKILL_NAME,
        JSONObject().put("input_path", "file/report.docx").put("output_format", "docx"),
        "document_convert",
      )
    assertThat(suggested).isEqualTo("file/report-converted.docx")
  }
}
