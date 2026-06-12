/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.visualcreation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCreationDomainTest {
  @Test
  fun defaultImageGenerationSettingsMatchFirstStageRequirements() {
    val settings = ImageGenerationSettings.default()

    assertEquals(512, settings.width)
    assertEquals(512, settings.height)
    assertEquals(20, settings.steps)
    assertEquals(7.0f, settings.cfgScale)
    assertEquals("euler", settings.sampler)
    assertEquals("PNG", settings.outputFormat)
    assertTrue(settings.randomSeed)
    assertTrue(settings.lowMemoryMode)
    assertTrue(settings.vaeTiling)
  }

  @Test
  fun resolveSeedUsesConfiguredSeedWhenRandomSeedIsDisabled() {
    val settings = ImageGenerationSettings.default().copy(randomSeed = false, seed = 42L)

    assertEquals(42L, settings.resolveSeed { 99L })
  }

  @Test
  fun resolveSeedUsesProviderWhenRandomSeedIsEnabled() {
    val settings = ImageGenerationSettings.default().copy(randomSeed = true, seed = 42L)

    assertEquals(99L, settings.resolveSeed { 99L })
  }

  @Test
  fun newSessionCapturesPromptModelAndDefaultVisualProcessingMode() {
    val settings = ImageGenerationSettings.default().copy(width = 768)

    val session =
      VisualCreationSession.create(
        sessionId = "session-1",
        nowMs = 1_786_176_000_000L,
        imagePrompt = "雨夜街灯下的小提琴家",
        negativePrompt = "模糊，低质量",
        generationSettings = settings,
        selectedImageGenerationModelId = "sd15-q4-engineering",
      )

    assertEquals("session-1", session.sessionId)
    assertEquals(1_786_176_000_000L, session.createdAtMs)
    assertEquals(1_786_176_000_000L, session.updatedAtMs)
    assertEquals("雨夜街灯下的小提琴家", session.imagePrompt)
    assertEquals("模糊，低质量", session.negativePrompt)
    assertEquals(768, session.generationSettings.width)
    assertEquals("sd15-q4-engineering", session.selectedImageGenerationModelId)
    assertEquals(VisualProcessMode.DESCRIBE_IMAGE, session.visualProcessMode)
    assertEquals(VisualCreationStatus.IDLE, session.status)
    assertFalse(session.hasGeneratedImage)
  }

  @Test
  fun visualProcessModesExposeChineseLabels() {
    assertEquals("描述图片", VisualProcessMode.DESCRIBE_IMAGE.label)
    assertEquals("评审图片", VisualProcessMode.REVIEW_IMAGE.label)
    assertEquals("基于图片创作文字", VisualProcessMode.EXPAND_TO_STORY.label)
    assertEquals("自定义处理", VisualProcessMode.CUSTOM_PROMPT.label)
  }
}
