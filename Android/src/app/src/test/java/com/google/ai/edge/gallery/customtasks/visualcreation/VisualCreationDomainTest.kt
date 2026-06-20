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
    assertEquals(28, settings.steps)
    assertEquals(7.0f, settings.cfgScale)
    assertEquals("euler", settings.sampler)
    assertEquals("PNG", settings.outputFormat)
    assertTrue(settings.randomSeed)
    assertTrue(settings.lowMemoryMode)
    assertFalse(settings.vaeTiling)
  }

  @Test
  fun fastCpuVerificationSettingsUseNormalResolutionForUserTesting() {
    val settings = ImageGenerationSettings.fastCpuVerification()

    assertEquals(512, settings.width)
    assertEquals(512, settings.height)
    assertEquals(28, settings.steps)
    assertEquals(7.0f, settings.cfgScale)
    assertTrue(settings.lowMemoryMode)
    assertFalse(settings.vaeTiling)
  }

  @Test
  fun imageGenerationSettingsClampUserEditableValues() {
    assertEquals(128, sanitizeGenerationDimension(1))
    assertEquals(512, sanitizeGenerationDimension(513))
    assertEquals(1024, sanitizeGenerationDimension(5000))
    assertEquals(1, sanitizeGenerationSteps(-1))
    assertEquals(50, sanitizeGenerationSteps(100))
    assertEquals(1.0f, sanitizeCfgScale(0.2f))
    assertEquals(12.0f, sanitizeCfgScale(30.0f))
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
        selectedImageGenerationModelId = "dreamshaper-v8-qnn-8gen2",
      )

    assertEquals("session-1", session.sessionId)
    assertEquals(1_786_176_000_000L, session.createdAtMs)
    assertEquals(1_786_176_000_000L, session.updatedAtMs)
    assertEquals("雨夜街灯下的小提琴家", session.imagePrompt)
    assertEquals("模糊，低质量", session.negativePrompt)
    assertEquals(768, session.generationSettings.width)
    assertEquals("dreamshaper-v8-qnn-8gen2", session.selectedImageGenerationModelId)
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
        name = "dreamshaper-v8-qnn-8gen2",
        displayName = "DreamShaper V8 QNN 8gen2",
        downloadFileName = "DreamShaperV8_qnn2.28_8gen2.zip",
      )
    )

    assertEquals("dreamshaper-v8-qnn-8gen2", viewModel.uiState.value.selectedImageGenerationModelId)
    assertEquals(
      "当前图像生成模型：DreamShaper V8 QNN 8gen2",
      viewModel.uiState.value.statusText,
    )
    assertEquals(512, viewModel.uiState.value.settings.width)
    assertEquals(512, viewModel.uiState.value.settings.height)
    assertEquals(28, viewModel.uiState.value.settings.steps)
  }

  @Test
  fun registryPrefersLocalDreamQnnBackendBeforeCpuFallbacks() {
    val first = ImageGenerationModelRegistry.recommendedModels.first()

    assertEquals("absolute-reality-qnn-8gen2", first.modelId)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, first.backend)
    assertEquals("AbsoluteReality_qnn2.28_8gen2.zip", first.requiredFiles.single().fileName)
  }

  @Test
  fun visualCreationImageModelsExposeLocalDreamArchivesAsZipDownloads() {
    val model = createVisualCreationImageModels().first { it.name == "dreamshaper-v8-qnn-8gen2" }

    assertTrue(model.isZip)
    assertEquals("model", model.unzipDir)
    assertEquals("DreamShaperV8_qnn2.28_8gen2.zip", model.downloadFileName)
  }

  @Test
  fun promptOptimizationModesHaveDistinctSystemPrompts() {
    assertTrue(
      defaultPromptOptimizerSystemPrompt(PromptOptimizationMode.ORIGINAL)
        .contains("unchanged")
    )
    assertTrue(
      defaultPromptOptimizerSystemPrompt(PromptOptimizationMode.ENGLISH_DEFAULT)
        .contains("English text-to-image prompt")
    )
  }

  @Test
  fun viewModelSyncsQnnModelWithLocalDreamDefaults() {
    val viewModel = VisualCreationViewModel()

    viewModel.syncSelectedImageGenerationModel(
      Model(
        name = "realistic-vision-hyper-qnn-8gen2",
        displayName = "Realistic Vision Hyper QNN 8gen2",
        downloadFileName = "RealisticVisionHyper_qnn2.28_8gen2.zip",
      )
    )

    assertEquals("realistic-vision-hyper-qnn-8gen2", viewModel.uiState.value.selectedImageGenerationModelId)
    assertEquals(512, viewModel.uiState.value.settings.width)
    assertEquals(512, viewModel.uiState.value.settings.height)
    assertEquals(28, viewModel.uiState.value.settings.steps)
    assertEquals(7.0f, viewModel.uiState.value.settings.cfgScale)
  }

  @Test
  fun viewModelSyncsSdxlModelWithLargeCanvasDefaults() {
    val viewModel = VisualCreationViewModel()

    viewModel.syncSelectedImageGenerationModel(
      Model(
        name = "sdxl-base-qnn-8gen3",
        displayName = "SDXL Base 1.0 QNN 8gen3",
        downloadFileName = "sdxl_base_qnn2.28_8gen3.zip",
      )
    )

    assertEquals("sdxl-base-qnn-8gen3", viewModel.uiState.value.selectedImageGenerationModelId)
    assertEquals(1024, viewModel.uiState.value.settings.width)
    assertEquals(1024, viewModel.uiState.value.settings.height)
    assertEquals(28, viewModel.uiState.value.settings.steps)
    assertEquals(7.0f, viewModel.uiState.value.settings.cfgScale)
  }

  @Test
  fun resolveNativeFileNamesLeavesLocalDreamArchiveToDirectoryBackend() {
    val modelInfo = ImageGenerationModelRegistry.requireModel("dreamshaper-v8-qnn-8gen2")

    val files = resolveNativeImageGenerationFileNames(modelInfo)

    assertEquals("", files.modelFileName)
    assertEquals("", files.diffusionModelFileName)
    assertEquals("", files.vaeFileName)
    assertEquals("", files.llmFileName)
  }

  @Test
  fun localDreamStartupPolicyReusesHealthyBackendInsteadOfRestartingIt() {
    val policy =
      LocalDreamBackendStartupPolicy.decide(
        healthCheckSuccessful = true,
        currentModelPath = "/models/absolute",
        requestedModelPath = "/models/absolute",
        currentTextEmbeddingSize = 768,
        requestedTextEmbeddingSize = 768,
      )

    assertEquals(LocalDreamBackendStartupAction.REUSE_RUNNING_BACKEND, policy)
  }

  @Test
  fun localDreamStartupPolicyRestartsWhenModelChangesOrBackendIsUnhealthy() {
    assertEquals(
      LocalDreamBackendStartupAction.START_OR_RESTART_BACKEND,
      LocalDreamBackendStartupPolicy.decide(
        healthCheckSuccessful = false,
        currentModelPath = "/models/absolute",
        requestedModelPath = "/models/absolute",
        currentTextEmbeddingSize = 768,
        requestedTextEmbeddingSize = 768,
      ),
    )
    assertEquals(
      LocalDreamBackendStartupAction.START_OR_RESTART_BACKEND,
      LocalDreamBackendStartupPolicy.decide(
        healthCheckSuccessful = true,
        currentModelPath = "/models/absolute",
        requestedModelPath = "/models/dreamshaper",
        currentTextEmbeddingSize = 768,
        requestedTextEmbeddingSize = 768,
      ),
    )
    assertEquals(
      LocalDreamBackendStartupAction.START_OR_RESTART_BACKEND,
      LocalDreamBackendStartupPolicy.decide(
        healthCheckSuccessful = true,
        currentModelPath = "/models/sdxl",
        requestedModelPath = "/models/sdxl",
        currentTextEmbeddingSize = 768,
        requestedTextEmbeddingSize = 1280,
      ),
    )
  }
}
