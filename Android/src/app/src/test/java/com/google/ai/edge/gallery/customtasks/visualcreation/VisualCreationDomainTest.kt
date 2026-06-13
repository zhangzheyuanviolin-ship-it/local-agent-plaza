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

import com.google.ai.edge.gallery.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
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
  fun fastCpuVerificationSettingsUseSmallImageAndFewerSteps() {
    val settings = ImageGenerationSettings.fastCpuVerification()

    assertEquals(256, settings.width)
    assertEquals(256, settings.height)
    assertEquals(20, settings.steps)
    assertEquals(7.0f, settings.cfgScale)
    assertTrue(settings.lowMemoryMode)
    assertFalse(settings.vaeTiling)
  }

  @Test
  fun nativeRgbToArgbPixelsUsesNativeDimensionsAndChannels() {
    val pixels =
      nativeRgbToArgbPixels(
        NativeImageGenerationResult(
          width = 2,
          height = 1,
          channels = 3,
          bytes = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0),
        )
      )

    assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), pixels)
  }

  @Test(expected = IllegalArgumentException::class)
  fun nativeRgbToArgbPixelsRejectsBuffersThatDoNotMatchNativeDimensions() {
    nativeRgbToArgbPixels(
      NativeImageGenerationResult(
        width = 2,
        height = 2,
        channels = 3,
        bytes = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0),
      )
    )
  }

  @Test
  fun normalizeSamplingProgressKeepsOnlyExpectedSamplingSteps() {
    val progress = normalizeSamplingProgress(callbackStep = 5, callbackSteps = 20, expectedSteps = 20)

    assertEquals(SamplingProgress(step = 5, steps = 20), progress)
  }

  @Test
  fun normalizeSamplingProgressFiltersVaeTileProgress() {
    val progress = normalizeSamplingProgress(callbackStep = 1131, callbackSteps = 1131, expectedSteps = 20)

    assertEquals(null, progress)
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
        selectedImageGenerationModelId = "z-image-turbo-q2-gguf",
      )

    assertEquals("session-1", session.sessionId)
    assertEquals(1_786_176_000_000L, session.createdAtMs)
    assertEquals(1_786_176_000_000L, session.updatedAtMs)
    assertEquals("雨夜街灯下的小提琴家", session.imagePrompt)
    assertEquals("模糊，低质量", session.negativePrompt)
    assertEquals(768, session.generationSettings.width)
    assertEquals("z-image-turbo-q2-gguf", session.selectedImageGenerationModelId)
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

  @Test
  fun viewModelSyncsSelectedModelFromModelManagerInsteadOfDefaultingToFirstModel() {
    val viewModel = VisualCreationViewModel()

    viewModel.syncSelectedImageGenerationModel(
      Model(
        name = "sd15-q4-0-gguf",
        displayName = "Stable Diffusion 1.5 Q4_0 GGUF",
        downloadFileName = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
      )
    )

    assertEquals("sd15-q4-0-gguf", viewModel.uiState.value.selectedImageGenerationModelId)
    assertEquals(
      "当前图像生成模型：Stable Diffusion 1.5 Q4_0 GGUF",
      viewModel.uiState.value.statusText,
    )
    assertEquals(256, viewModel.uiState.value.settings.width)
    assertEquals(256, viewModel.uiState.value.settings.height)
    assertEquals(20, viewModel.uiState.value.settings.steps)
  }
}
