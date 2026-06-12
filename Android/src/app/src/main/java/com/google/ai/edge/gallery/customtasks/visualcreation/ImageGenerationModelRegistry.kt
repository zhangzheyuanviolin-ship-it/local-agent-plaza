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
import com.google.ai.edge.gallery.data.ModelDataFile

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
        modelId = "z-image-turbo-q2-gguf",
        displayName = "Z-Image Turbo Q2_K GGUF",
        family = "Z-Image",
        backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
        format = "GGUF",
        requiredFiles =
          listOf(
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.DIFFUSION_MODEL,
              fileName = "z_image_turbo-Q2_K.gguf",
              downloadUrl =
                "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/main/z_image_turbo-Q2_K.gguf",
              sizeInBytes = 2_592_442_304L,
            ),
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.VAE,
              fileName = "ae.safetensors",
              downloadUrl =
                "https://huggingface.co/Comfy-Org/z_image_turbo/resolve/main/split_files/vae/ae.safetensors",
              sizeInBytes = 335_304_388L,
            ),
            ImageGenerationModelFile(
              role = ImageGenerationModelFileRole.TEXT_ENCODER,
              fileName = "qwen_3_4b_fp4_mixed.safetensors",
              downloadUrl =
                "https://huggingface.co/Comfy-Org/z_image_turbo/resolve/main/split_files/text_encoders/qwen_3_4b_fp4_mixed.safetensors",
              sizeInBytes = 3_479_416_193L,
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
        notes =
          "已核验公开可下载的第一阶段模型包：主扩散权重来自 leejet/Z-Image-Turbo-GGUF，VAE 和 Qwen3 4B FP4 文本编码器来自 Comfy-Org/z_image_turbo。",
      ),
    )

  fun findModel(modelId: String): ImageGenerationModelInfo? {
    return recommendedModels.firstOrNull { it.modelId == modelId }
  }

  fun requireModel(modelId: String): ImageGenerationModelInfo {
    return findModel(modelId) ?: error("Image generation model not found: $modelId")
  }
}

fun createVisualCreationImageModels(): List<Model> {
  return ImageGenerationModelRegistry.recommendedModels.map { modelInfo ->
    val primaryFile =
      modelInfo.requiredFiles.firstOrNull {
        it.role == ImageGenerationModelFileRole.DIFFUSION_MODEL ||
          it.role == ImageGenerationModelFileRole.CHECKPOINT
      }
        ?: error("Image generation model '${modelInfo.modelId}' has no primary model file")
    Model(
        name = modelInfo.modelId,
        displayName = modelInfo.displayName,
        info =
          "${modelInfo.notes}\n\n模型格式：${modelInfo.format}；推理后端：stable-diffusion.cpp；" +
            "文件总大小约 ${modelInfo.totalSizeInBytes / 1_000_000_000.0} GB；" +
            "推荐尺寸 ${modelInfo.recommendedWidth} x ${modelInfo.recommendedHeight}；" +
            "许可证：${modelInfo.license}。",
        learnMoreUrl = "https://huggingface.co/leejet/Z-Image-Turbo-GGUF",
        bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION),
        minDeviceMemoryInGb = modelInfo.minMemoryGb,
        url = primaryFile.downloadUrl,
        sizeInBytes = primaryFile.sizeInBytes,
        downloadFileName = primaryFile.fileName,
        version = "z-image-turbo-q2-2025-12-02",
        extraDataFiles =
          modelInfo.requiredFiles
            .filterNot { it == primaryFile }
            .map { file ->
              ModelDataFile(
                name = file.role.name.lowercase(),
                url = file.downloadUrl,
                downloadFileName = file.fileName,
                sizeInBytes = file.sizeInBytes,
              )
            },
        isLlm = false,
        showRunAgainButton = false,
        showBenchmarkButton = false,
      )
      .apply { preProcess() }
  }
}
