package com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeyboardPipelineCatalogTest {
  @Test
  fun catalogContainsRequiredPipelinePresets() {
    val requiredIds =
      listOf(
        "polish",
        "proofread",
        "rewrite",
        "simplify",
        "professional",
        "casual",
        "shorten",
        "expand",
        "summarize",
        "bullets",
        "email",
        "chat",
        "twitter",
        "list",
        "table",
        "translate",
        "custom",
      )

    assertEquals(requiredIds, AiKeyboardPipelineCatalog.presets.map { it.id })
  }

  @Test
  fun nextAfterCyclesThroughPresetsAndWraps() {
    assertEquals("proofread", AiKeyboardPipelineCatalog.nextAfter("polish").id)
    assertEquals("polish", AiKeyboardPipelineCatalog.nextAfter("custom").id)
    assertEquals("polish", AiKeyboardPipelineCatalog.nextAfter("missing").id)
  }

  @Test
  fun promptRequiresOnlyProcessedTextAndIncludesOriginalInput() {
    val prompt = AiKeyboardPipelineCatalog.buildPrompt("polish", "今天会议记录有点乱。")

    assertTrue(prompt.contains("只输出处理后的正文"))
    assertTrue(prompt.contains("不要解释"))
    assertTrue(prompt.contains("今天会议记录有点乱。"))
  }

  @Test
  fun promptRejectsBlankInput() {
    val prompt = AiKeyboardPipelineCatalog.buildPrompt("polish", "   ")

    assertEquals("", prompt)
  }

  @Test
  fun presetsHaveShortKeyboardLabels() {
    AiKeyboardPipelineCatalog.presets.forEach { preset ->
      assertNotEquals("", preset.keyboardLabel)
      assertTrue(preset.keyboardLabel.length <= 4)
    }
  }
}
