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
        steps = 28,
        cfgScale = 7.0f,
        seed = 0L,
        randomSeed = true,
        sampler = "euler",
        outputFormat = "PNG",
        lowMemoryMode = true,
        vaeTiling = false,
        threadCount = 4,
        backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      )

    fun fastCpuVerification(): ImageGenerationSettings =
      default().copy(width = 512, height = 512, steps = 28, vaeTiling = false)
  }
}

data class NativeImageGenerationFileNames(
  val modelFileName: String,
  val diffusionModelFileName: String,
  val vaeFileName: String,
  val llmFileName: String,
)

enum class PromptOptimizationMode(val label: String) {
  ORIGINAL("原文直发"),
  ENGLISH_DEFAULT("默认英文优化"),
  ENGLISH_CUSTOM("自定义英文优化"),
}

fun resolveNativeImageGenerationFileNames(
  modelInfo: ImageGenerationModelInfo
): NativeImageGenerationFileNames {
  val checkpoint =
    modelInfo.requiredFiles.firstOrNull { it.role == ImageGenerationModelFileRole.CHECKPOINT }
  val diffusion =
    modelInfo.requiredFiles.firstOrNull { it.role == ImageGenerationModelFileRole.DIFFUSION_MODEL }
  val vae = modelInfo.requiredFiles.firstOrNull { it.role == ImageGenerationModelFileRole.VAE }
  val textEncoder =
    modelInfo.requiredFiles.firstOrNull { it.role == ImageGenerationModelFileRole.TEXT_ENCODER }

  return NativeImageGenerationFileNames(
    modelFileName = checkpoint?.fileName ?: "",
    diffusionModelFileName = diffusion?.fileName ?: "",
    vaeFileName = vae?.fileName ?: "",
    llmFileName = textEncoder?.fileName ?: "",
  )
}

fun sanitizeGenerationDimension(value: Int): Int {
  val coerced = value.coerceIn(128, 1024)
  return ((coerced + 31) / 64 * 64).coerceIn(128, 1024)
}

fun sanitizeGenerationSteps(value: Int): Int = value.coerceIn(1, 50)

fun sanitizeCfgScale(value: Float): Float = value.coerceIn(1.0f, 12.0f)

fun sanitizeThreadCount(value: Int): Int = value.coerceIn(1, 8)

internal fun defaultPromptOptimizerSystemPrompt(mode: PromptOptimizationMode): String {
  return when (mode) {
    PromptOptimizationMode.ORIGINAL ->
      "Return the user's image prompt unchanged. Do not translate, rewrite, explain, or add labels."
    PromptOptimizationMode.ENGLISH_DEFAULT,
    PromptOptimizationMode.ENGLISH_CUSTOM ->
      "You are a prompt translator for offline Stable Diffusion image generation. Translate Chinese or multilingual user descriptions into a vivid, concise English text-to-image prompt. Preserve concrete subjects, scene layout, style, mood, lighting, colors, and camera details. Return only the final English prompt, without markdown, labels, quotes, explanations, or Chinese text."
  }
}

internal fun defaultVisualProcessSystemPrompt(
  mode: VisualProcessMode,
  originalImagePrompt: String,
  customPrompt: String,
): String {
  return when (mode) {
    VisualProcessMode.DESCRIBE_IMAGE ->
      "请用中文详细描述这张图片中的所有画面元素、主体、背景、颜色、光影、构图、风格、质感和可见细节。如果图片中出现文字、数字、标志或界面信息，也请逐项转写并说明其位置。只基于图片内容回答，不要编造看不见的信息。"
    VisualProcessMode.REVIEW_IMAGE ->
      """
      请扮演严谨的 AI 图像评审员，用中文评审这张生成图片与用户原始提示词的匹配程度。
      用户原始提示词：$originalImagePrompt
      请输出：一，总分 0 到 100 分；二，提示词遵循度；三，主体和构图是否准确；四，画面质量、清晰度、畸变、文字错误和明显瑕疵；五，最需要改进的提示词建议。请直接给出评审，不要输出无关寒暄。
      """
        .trimIndent()
    VisualProcessMode.EXPAND_TO_STORY ->
      "请以这张图片的整体画面、人物或物体关系、场景气氛和潜在叙事为起点，用中文创作一篇约 800 字的短篇故事。题材请根据画面随机选择现实、科幻、奇幻、都市、悬疑、历史、穿越、冒险或温情等方向之一。故事要有标题、开端、转折和结尾，不要解释你看到了什么。"
    VisualProcessMode.CUSTOM_PROMPT -> customPrompt.ifBlank { "请根据用户自定义要求处理这张图片，并用中文回答。" }
    VisualProcessMode.EXPAND_TO_CAPTION ->
      "请根据这张图片生成一段中文社交媒体配文，包含画面亮点、情绪氛围和适合发布的简短文案。"
    VisualProcessMode.EXPAND_TO_SCRIPT ->
      "请根据这张图片生成一段中文画面解说词，适合用于短视频旁白，语气自然、细节具体。"
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
