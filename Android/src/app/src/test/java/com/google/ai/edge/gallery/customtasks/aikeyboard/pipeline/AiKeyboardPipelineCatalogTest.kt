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
  fun translatePromptUsesConfiguredTargetLanguage() {
    val prompt =
      AiKeyboardPipelineCatalog.buildPrompt(
        presetId = "translate",
        input = "今天晚上要不要一起吃饭？",
        translationTargetLanguage = "英文",
      )

    assertTrue(prompt.contains("翻译为英文"))
    assertTrue(prompt.contains("今天晚上要不要一起吃饭？"))
  }

  @Test
  fun translatePromptSupportsCustomInstructionWithTargetLanguageVariable() {
    val preset =
      AiKeyboardPipelineCatalog.defaultPreset().copy(
        id = "translate",
        instruction = "请翻成{target_language}，保留礼貌语气。",
      )

    val prompt =
      AiKeyboardPipelineCatalog.buildPrompt(
        presetId = "translate",
        input = "最近忙不忙？",
        presetOverride = preset,
        translationTargetLanguage = "日文",
      )

    assertTrue(prompt.contains("请翻成日文"))
    assertTrue(prompt.contains("最近忙不忙？"))
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

  @Test
  fun pipelineOutputBudgetRaisesLowModelDefaultsForTranslationAndLongOutputs() {
    assertEquals(4096, AiKeyboardTextModelRepository.resolvePipelineMaxTokens(1024, 8192))
    assertEquals(2048, AiKeyboardTextModelRepository.resolvePipelineMaxTokens(1024, 2048))
    assertEquals(6000, AiKeyboardTextModelRepository.resolvePipelineMaxTokens(6000, 8192))
  }
}
