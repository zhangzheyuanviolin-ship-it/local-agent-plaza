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

enum class ImageGenerationBackend {
  STABLE_DIFFUSION_CPP,
}

enum class ImageGenerationModelFileRole {
  CHECKPOINT,
  DIFFUSION_MODEL,
  VAE,
  TEXT_ENCODER,
  TOKENIZER,
  CONFIG,
}

data class ImageGenerationModelFile(
  val role: ImageGenerationModelFileRole,
  val fileName: String,
  val downloadUrl: String,
  val sizeInBytes: Long,
  val required: Boolean = true,
)

data class ImageGenerationModelInfo(
  val modelId: String,
  val displayName: String,
  val family: String,
  val backend: ImageGenerationBackend,
  val format: String,
  val requiredFiles: List<ImageGenerationModelFile>,
  val license: String,
  val supportsTextToImage: Boolean,
  val supportsImageToImage: Boolean,
  val supportsImageEditing: Boolean,
  val supportsChineseText: Boolean,
  val lowMemoryRecommended: Boolean,
  val minMemoryGb: Int,
  val recommendedWidth: Int,
  val recommendedHeight: Int,
  val notes: String,
) {
  val totalSizeInBytes: Long
    get() = requiredFiles.sumOf { it.sizeInBytes }
}

object ImageGenerationModelRegistry {
  val recommendedModels: List<ImageGenerationModelInfo> =
    listOf(
      ImageGenerationModelInfo(
        modelId = "sd15-q4-engineering",
        displayName = "Stable Diffusion 1.5 Q4 工程验证",
        family = "Stable Diffusion 1.x",
        backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
        format = "GGUF",
        requiredFiles =
          listOf(
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.CHECKPOINT,
              fileName = "sd-v1-5-pruned-emaonly-q4_0.gguf",
              downloadUrl = "",
              sizeInBytes = 1_100_000_000L,
            )
          ),
        license = "OpenRAIL-M",
        supportsTextToImage = true,
        supportsImageToImage = false,
        supportsImageEditing = false,
        supportsChineseText = false,
        lowMemoryRecommended = true,
        minMemoryGb = 6,
        recommendedWidth = 512,
        recommendedHeight = 512,
        notes = "第一阶段工程验证候选，用于优先跑通端侧文生图链路。",
      ),
      ImageGenerationModelInfo(
        modelId = "z-image-turbo-q3-gguf",
        displayName = "Z-Image Turbo Q3 GGUF",
        family = "Z-Image",
        backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
        format = "GGUF",
        requiredFiles =
          listOf(
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.DIFFUSION_MODEL,
              fileName = "z_image_turbo-Q3_K.gguf",
              downloadUrl =
                "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/main/z_image_turbo-Q3_K.gguf",
              sizeInBytes = 2_592_442_304L,
            ),
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.VAE,
              fileName = "ae.sft",
              downloadUrl = "https://huggingface.co/black-forest-labs/FLUX.1-schnell/resolve/main/ae.sft",
              sizeInBytes = 335_000_000L,
            ),
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.TEXT_ENCODER,
              fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
              downloadUrl =
                "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
              sizeInBytes = 2_600_000_000L,
            ),
          ),
        license = "Apache-2.0",
        supportsTextToImage = true,
        supportsImageToImage = false,
        supportsImageEditing = false,
        supportsChineseText = true,
        lowMemoryRecommended = true,
        minMemoryGb = 8,
        recommendedWidth = 512,
        recommendedHeight = 512,
        notes = "中文能力候选，stable-diffusion.cpp文档列为Z-Image Turbo GGUF运行路径。",
      ),
    )

  fun findModel(modelId: String): ImageGenerationModelInfo? {
    return recommendedModels.firstOrNull { it.modelId == modelId }
  }

  fun requireModel(modelId: String): ImageGenerationModelInfo {
    return findModel(modelId) ?: error("Image generation model not found: $modelId")
  }
}
