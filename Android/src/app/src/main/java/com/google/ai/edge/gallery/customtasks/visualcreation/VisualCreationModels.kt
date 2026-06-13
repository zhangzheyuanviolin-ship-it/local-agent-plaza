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

enum class VisualCreationStatus {
  IDLE,
  IMAGE_MODEL_MISSING,
  IMAGE_MODEL_DOWNLOADING,
  IMAGE_MODEL_DOWNLOADED,
  IMAGE_MODEL_LOADING,
  IMAGE_MODEL_LOADED,
  GENERATING_IMAGE,
  IMAGE_GENERATED,
  SAVING_IMAGE,
  IMAGE_SAVED,
  UNLOADING_IMAGE_MODEL,
  READY_FOR_VISUAL_PROCESSING,
  VLM_MODEL_SELECTING,
  VLM_MODEL_LOADING,
  VLM_PROCESSING,
  VLM_RESULT_READY,
  REGENERATING,
  ERROR,
}

enum class VisualProcessMode(val label: String) {
  DESCRIBE_IMAGE("描述图片"),
  REVIEW_IMAGE("评审图片"),
  EXPAND_TO_STORY("基于图片创作文字"),
  EXPAND_TO_CAPTION("生成社交媒体配文"),
  EXPAND_TO_SCRIPT("生成画面解说"),
  CUSTOM_PROMPT("自定义处理"),
}

data class ImageGenerationSettings(
  val width: Int,
  val height: Int,
  val steps: Int,
  val cfgScale: Float,
  val seed: Long,
  val randomSeed: Boolean,
  val sampler: String,
  val outputFormat: String,
  val lowMemoryMode: Boolean,
  val vaeTiling: Boolean,
  val threadCount: Int,
  val backend: ImageGenerationBackend,
) {
  fun resolveSeed(randomSeedProvider: () -> Long = { System.currentTimeMillis() }): Long {
    return if (randomSeed) randomSeedProvider() else seed
  }

  companion object {
    fun default(): ImageGenerationSettings =
      ImageGenerationSettings(
        width = 512,
        height = 512,
        steps = 20,
        cfgScale = 7.0f,
        seed = 0L,
        randomSeed = true,
        sampler = "euler",
        outputFormat = "PNG",
        lowMemoryMode = true,
        vaeTiling = true,
        threadCount = 4,
        backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
      )

    fun fastCpuVerification(): ImageGenerationSettings =
      default().copy(width = 256, height = 256, steps = 8)
  }
}

data class VisualCreationSession(
  val sessionId: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
  val imagePrompt: String,
  val negativePrompt: String,
  val generationSettings: ImageGenerationSettings,
  val selectedImageGenerationModelId: String?,
  val generatedImagePath: String? = null,
  val generatedImageMetadataPath: String? = null,
  val referenceImagePath: String? = null,
  val selectedVlmModelName: String? = null,
  val visualProcessMode: VisualProcessMode = VisualProcessMode.DESCRIBE_IMAGE,
  val visualProcessPrompt: String = "",
  val visualProcessResult: String = "",
  val status: VisualCreationStatus = VisualCreationStatus.IDLE,
  val errorMessage: String = "",
) {
  val hasGeneratedImage: Boolean
    get() = !generatedImagePath.isNullOrBlank()

  companion object {
    fun create(
      sessionId: String,
      nowMs: Long,
      imagePrompt: String,
      negativePrompt: String,
      generationSettings: ImageGenerationSettings,
      selectedImageGenerationModelId: String?,
    ): VisualCreationSession =
      VisualCreationSession(
        sessionId = sessionId,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
        imagePrompt = imagePrompt,
        negativePrompt = negativePrompt,
        generationSettings = generationSettings,
        selectedImageGenerationModelId = selectedImageGenerationModelId,
      )
  }
}

enum class VisualCreationMessageRole {
  USER,
  ASSISTANT,
  SYSTEM,
}

enum class VisualCreationMessageType {
  USER_PROMPT,
  IMAGE_GENERATION_STATUS,
  GENERATED_IMAGE,
  USER_ACTION,
  VLM_OUTPUT,
  ERROR,
  SYSTEM_INFO,
}

data class VisualCreationMessage(
  val messageId: String,
  val role: VisualCreationMessageRole,
  val type: VisualCreationMessageType,
  val text: String,
  val imagePath: String? = null,
  val timestampMs: Long,
  val metadata: Map<String, String> = emptyMap(),
)
