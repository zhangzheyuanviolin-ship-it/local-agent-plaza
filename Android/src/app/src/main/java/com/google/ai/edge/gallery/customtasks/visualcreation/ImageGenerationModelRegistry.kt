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
  LOCAL_DREAM_QNN_MNN,
  STABLE_DIFFUSION_CPP,
}

enum class ImageGenerationModelFileRole {
  MODEL_ARCHIVE,
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
  val learnMoreUrl: String,
  val localVersion: String,
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
      absoluteRealityQnn8Gen2Model(),
      absoluteRealityMnnCpuModel(),
      zImageTurboModel("q2", "Q2_K", "z_image_turbo-Q2_K.gguf", 2_592_442_304L, 8),
      zImageTurboModel("q3", "Q3_K", "z_image_turbo-Q3_K.gguf", 3_143_559_104L, 10),
      zImageTurboModel("q4-0", "Q4_0", "z_image_turbo-Q4_0.gguf", 3_683_370_944L, 12),
      zImageTurboModel("q4-k", "Q4_K", "z_image_turbo-Q4_K.gguf", 3_864_250_304L, 12),
      zImageTurboModel("q5-0", "Q5_0", "z_image_turbo-Q5_0.gguf", 4_542_547_904L, 14),
      zImageTurboModel("q6-k", "Q6_K", "z_image_turbo-Q6_K.gguf", 5_263_239_104L, 16),
      zImageTurboModel("q8-0", "Q8_0", "z_image_turbo-Q8_0.gguf", 6_577_440_704L, 18),
      stableDiffusion15Model(
        modelId = "sd15-q4-0-gguf",
        quantLabel = "Q4_0",
        fileName = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
        sizeInBytes = 1_566_768_416L,
        minMemoryGb = 6,
      ),
      stableDiffusion15Model(
        modelId = "sd15-q5-0-gguf",
        quantLabel = "Q5_0",
        fileName = "stable-diffusion-v1-5-pruned-emaonly-Q5_0.gguf",
        sizeInBytes = 1_615_967_904L,
        minMemoryGb = 6,
      ),
      stableDiffusion15Model(
        modelId = "sd15-q8-0-gguf",
        quantLabel = "Q8_0",
        fileName = "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
        sizeInBytes = 1_763_578_176L,
        minMemoryGb = 8,
      ),
    )

  fun findModel(modelId: String): ImageGenerationModelInfo? {
    return recommendedModels.firstOrNull { it.modelId == modelId }
  }

  fun requireModel(modelId: String): ImageGenerationModelInfo {
    return findModel(modelId) ?: error("Image generation model not found: $modelId")
  }

  private fun zImageTurboModel(
    modelIdSuffix: String,
    quantLabel: String,
    fileName: String,
    sizeInBytes: Long,
    minMemoryGb: Int,
  ): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = "z-image-turbo-$modelIdSuffix-gguf",
      displayName = "Z-Image Turbo $quantLabel GGUF",
      family = "Z-Image",
      backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
      format = "GGUF",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.DIFFUSION_MODEL,
            fileName = fileName,
            downloadUrl = "https://huggingface.co/leejet/Z-Image-Turbo-GGUF/resolve/main/$fileName",
            sizeInBytes = sizeInBytes,
          )
        ) + zImageSharedFiles(),
      learnMoreUrl = "https://huggingface.co/leejet/Z-Image-Turbo-GGUF",
      localVersion =
        if (modelIdSuffix == "q2") {
          "z-image-turbo-q2-2025-12-02"
        } else {
          "z-image-turbo-$modelIdSuffix-2025-12-02"
        },
      license = "Apache-2.0",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = true,
      lowMemoryRecommended = modelIdSuffix == "q2" || modelIdSuffix == "q3",
      minMemoryGb = minMemoryGb,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes =
        "中文优先候选模型。主扩散权重来自 leejet/Z-Image-Turbo-GGUF，VAE 和 Qwen3 4B FP4 文本编码器来自 Comfy-Org/z_image_turbo；下载链接和文件大小已核验。",
    )
  }

  private fun absoluteRealityQnn8Gen2Model(): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = "absolute-reality-qnn-8gen2",
      displayName = "Absolute Reality QNN 8gen2",
      family = "Absolute Reality SD1.5",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      format = "QNN ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = "AbsoluteReality_qnn2.28_8gen2.zip",
            downloadUrl =
              "https://huggingface.co/xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_8gen2.zip",
            sizeInBytes = 1_128_267_776L,
          )
        ),
      learnMoreUrl = "https://huggingface.co/xororz/sd-qnn",
      localVersion = "absolute-reality-qnn-8gen2-2026-06-14",
      license = "CC BY-NC 4.0 / upstream model licenses",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = false,
      minMemoryGb = 6,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes =
        "主力高速候选后端。使用 Local Dream 架构和 Qualcomm QNN NPU 加速，适配 Snapdragon 8 Gen 2/3/Elite 类设备；本集成仅用于非商业测试。",
    )
  }

  private fun absoluteRealityMnnCpuModel(): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = "absolute-reality-mnn-cpu",
      displayName = "Absolute Reality MNN CPU",
      family = "Absolute Reality SD1.5",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      format = "MNN ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = "AbsoluteReality.zip",
            downloadUrl = "https://huggingface.co/xororz/sd-mnn/resolve/main/AbsoluteReality.zip",
            sizeInBytes = 1_288_490_188L,
          )
        ),
      learnMoreUrl = "https://huggingface.co/xororz/sd-mnn",
      localVersion = "absolute-reality-mnn-cpu-2026-06-14",
      license = "CC BY-NC 4.0 / upstream model licenses",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = true,
      minMemoryGb = 4,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes =
        "兼容性兜底的 Local Dream / MNN CPU 图像生成模型；速度慢于 QNN NPU，但比 stable-diffusion.cpp CPU 路线更接近 LLM-Hub。",
    )
  }

  private fun stableDiffusion15Model(
    modelId: String,
    quantLabel: String,
    fileName: String,
    sizeInBytes: Long,
    minMemoryGb: Int,
  ): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = modelId,
      displayName = "Stable Diffusion 1.5 $quantLabel GGUF",
      family = "Stable Diffusion 1.5",
      backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
      format = "GGUF",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.CHECKPOINT,
            fileName = fileName,
            downloadUrl =
              "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/$fileName",
            sizeInBytes = sizeInBytes,
          )
        ),
      learnMoreUrl = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF",
      localVersion = "$modelId-2024-11-22",
      license = "CreativeML Open RAIL-M",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = true,
      minMemoryGb = minMemoryGb,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes =
        "英文 Stable Diffusion 1.5 候选模型，来自 second-state/stable-diffusion-v1-5-GGUF；用于后续英文提示词和低内存文生图链路验证。",
    )
  }

  private fun zImageSharedFiles(): List<ImageGenerationModelFile> {
    return listOf(
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
    )
  }
}

fun createVisualCreationImageModels(): List<Model> {
  return ImageGenerationModelRegistry.recommendedModels.map { modelInfo ->
    val primaryFile =
      modelInfo.requiredFiles.firstOrNull {
        it.role == ImageGenerationModelFileRole.MODEL_ARCHIVE ||
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
        learnMoreUrl = modelInfo.learnMoreUrl,
        bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION),
        minDeviceMemoryInGb = modelInfo.minMemoryGb,
        url = primaryFile.downloadUrl,
        sizeInBytes = primaryFile.sizeInBytes,
        downloadFileName = primaryFile.fileName,
        version = modelInfo.localVersion,
        isZip = primaryFile.fileName.endsWith(".zip"),
        unzipDir = if (primaryFile.fileName.endsWith(".zip")) "model" else "",
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
