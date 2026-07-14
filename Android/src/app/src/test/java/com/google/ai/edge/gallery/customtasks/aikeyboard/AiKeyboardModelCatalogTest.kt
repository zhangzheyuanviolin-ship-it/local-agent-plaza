package com.google.ai.edge.gallery.customtasks.aikeyboard

import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeyboardModelCatalogTest {
  @Test
  fun catalogContainsCoreChineseAndEnglishModelsAndBundledChineseSmallModel() {
    assertEquals(3, AiKeyboardModelCatalog.modelsForLanguage(AiKeyboardModelCatalog.LANG_ZH).size)
    assertEquals(4, AiKeyboardModelCatalog.modelsForLanguage(AiKeyboardModelCatalog.LANG_EN).size)

    val zhSmall = AiKeyboardModelCatalog.byId("zh_small_cn_022")
    assertNotNull(zhSmall)
    assertEquals("models/zh_small_cn_022.zip", zhSmall?.bundledAssetZipPath)
  }

  @Test
  fun catalogContainsAdditionalOfficialMobileLanguages() {
    val expectedLanguages =
      listOf(
        AiKeyboardModelCatalog.LANG_ZH,
        AiKeyboardModelCatalog.LANG_EN,
        AiKeyboardModelCatalog.LANG_JA,
        AiKeyboardModelCatalog.LANG_KO,
        AiKeyboardModelCatalog.LANG_FR,
        AiKeyboardModelCatalog.LANG_DE,
        AiKeyboardModelCatalog.LANG_ES,
        AiKeyboardModelCatalog.LANG_RU,
        AiKeyboardModelCatalog.LANG_VI,
        AiKeyboardModelCatalog.LANG_PT,
      )

    assertEquals(expectedLanguages, AiKeyboardModelCatalog.supportedLanguages())
    expectedLanguages.forEach { language ->
      assertTrue(AiKeyboardModelCatalog.modelsForLanguage(language).isNotEmpty())
    }
    assertNotNull(AiKeyboardModelCatalog.byId("en_lgraph_us_022"))
    assertNotNull(AiKeyboardModelCatalog.byId("ja_small_022"))
    assertNotNull(AiKeyboardModelCatalog.byId("ko_small_022"))
  }
}
