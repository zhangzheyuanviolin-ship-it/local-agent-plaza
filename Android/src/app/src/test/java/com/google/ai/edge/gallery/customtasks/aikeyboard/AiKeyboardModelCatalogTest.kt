package com.google.ai.edge.gallery.customtasks.aikeyboard

import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiKeyboardModelCatalogTest {
  @Test
  fun catalogContainsThreeModelsPerLanguageAndBundledChineseSmallModel() {
    assertEquals(3, AiKeyboardModelCatalog.modelsForLanguage(AiKeyboardModelCatalog.LANG_ZH).size)
    assertEquals(3, AiKeyboardModelCatalog.modelsForLanguage(AiKeyboardModelCatalog.LANG_EN).size)

    val zhSmall = AiKeyboardModelCatalog.byId("zh_small_cn_022")
    assertNotNull(zhSmall)
    assertEquals("models/zh_small_cn_022.zip", zhSmall?.bundledAssetZipPath)
  }
}
