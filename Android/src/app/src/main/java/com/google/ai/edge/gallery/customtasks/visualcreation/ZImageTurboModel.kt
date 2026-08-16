/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.customtasks.visualcreation

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile

const val Z_IMAGE_TURBO_MODEL_ID = "z-image-turbo-6b-litert"

private const val Z_IMAGE_HF_REPO = "litert-community/Z-Image-Turbo-LiteRT"
private const val Z_IMAGE_HF_REVISION = "a5dac9669fd1315f07a5f0ca70f0a97a53feb3c9"
private const val Z_QWEN_ASSET_REPO = "litert-community/FLUX.2-klein-4B-LiteRT"
private const val Z_QWEN_ASSET_REVISION = "main"

const val Z_QWEN_ENCODER = "qwen_enc.tflite"
const val Z_EMBED_IMAGE = "z_embx.tflite"
const val Z_EMBED_CAPTION = "z_embc.tflite"
const val Z_REFINE_IMAGE = "z_refx.tflite"
const val Z_REFINE_CAPTION = "z_refc.tflite"
const val Z_MAIN_0 = "zc_main0.tflite"
const val Z_MAIN_1 = "zc_main1.tflite"
const val Z_MAIN_2 = "zc_main2.tflite"
const val Z_MAIN_3 = "zc_main3.tflite"
const val Z_MAIN_4 = "zc_main4.tflite"
const val Z_MAIN_5 = "zc_main5.tflite"
const val Z_FINAL = "zc_final.tflite"
const val Z_VAE = "zvae.tflite"
const val Z_QWEN_EMBED = "qwen_embed_fp16.bin"
const val Z_QWEN_VOCAB = "qwen_vocab.txt"
const val Z_QWEN_MERGES = "qwen_merges.txt"
const val Z_QWEN_SPECIAL = "qwen_special.txt"

private data class ZImageFile(
  val fileName: String,
  val sizeInBytes: Long,
  val remotePath: String,
  val fromQwenAssetRepo: Boolean = false,
)

/*
 * Sizes are intentionally approximate metadata used by the existing download progress UI.
 * Completion is determined by the downloader/final files and successful LiteRT graph loading.
 * The immutable Z-Image revision pins the actual graph bytes.
 */
private val Z_IMAGE_FILES =
  listOf(
    ZImageFile(Z_QWEN_ENCODER, 3_550_000_000L, Z_QWEN_ENCODER),
    ZImageFile(Z_EMBED_CAPTION, 9_910_000L, Z_EMBED_CAPTION),
    ZImageFile(Z_EMBED_IMAGE, 308_000L, Z_EMBED_IMAGE),
    ZImageFile(Z_REFINE_CAPTION, 355_000_000L, Z_REFINE_CAPTION),
    ZImageFile(Z_REFINE_IMAGE, 363_000_000L, Z_REFINE_IMAGE),
    ZImageFile(Z_MAIN_0, 908_000_000L, Z_MAIN_0),
    ZImageFile(Z_MAIN_1, 908_000_000L, Z_MAIN_1),
    ZImageFile(Z_MAIN_2, 908_000_000L, Z_MAIN_2),
    ZImageFile(Z_MAIN_3, 908_000_000L, Z_MAIN_3),
    ZImageFile(Z_MAIN_4, 908_000_000L, Z_MAIN_4),
    ZImageFile(Z_MAIN_5, 908_000_000L, Z_MAIN_5),
    ZImageFile(Z_FINAL, 1_300_000L, Z_FINAL),
    ZImageFile(Z_VAE, 50_100_000L, Z_VAE),
    ZImageFile(Z_QWEN_EMBED, 777_912_320L, "tokenizer/$Z_QWEN_EMBED", true),
    ZImageFile(Z_QWEN_VOCAB, 1_521_491L, "tokenizer/$Z_QWEN_VOCAB", true),
    ZImageFile(Z_QWEN_MERGES, 1_671_838L, "tokenizer/$Z_QWEN_MERGES", true),
    ZImageFile(Z_QWEN_SPECIAL, 547L, "tokenizer/$Z_QWEN_SPECIAL", true),
  )

private fun zImageDownloadUrl(file: ZImageFile): String {
  val repo = if (file.fromQwenAssetRepo) Z_QWEN_ASSET_REPO else Z_IMAGE_HF_REPO
  val revision = if (file.fromQwenAssetRepo) Z_QWEN_ASSET_REVISION else Z_IMAGE_HF_REVISION
  return "https://huggingface.co/$repo/resolve/$revision/${file.remotePath}?download=true"
}

val Z_IMAGE_TURBO_MODEL_INFO: ImageGenerationModelInfo =
  ImageGenerationModelInfo(
    modelId = Z_IMAGE_TURBO_MODEL_ID,
    displayName = "Z-Image Turbo 6B LiteRT",
    family = "Alibaba Tongyi-MAI Z-Image-Turbo 6B",
    backend = ImageGenerationBackend.Z_IMAGE_LITERT_GPU_COMPILED_MODEL,
    format = "LiteRT CompiledModel int8 chunks (GPU FP32)",
    requiredFiles =
      Z_IMAGE_FILES.map { file ->
        ImageGenerationModelFile(
          role =
            when {
              file.fileName == Z_QWEN_ENCODER || file.fileName == Z_QWEN_EMBED ->
                ImageGenerationModelFileRole.TEXT_ENCODER
              file.fileName == Z_VAE -> ImageGenerationModelFileRole.VAE
              file.fileName.startsWith("z_") || file.fileName.startsWith("zc_") ->
                ImageGenerationModelFileRole.DIFFUSION_MODEL
              else -> ImageGenerationModelFileRole.TOKENIZER
            },
          fileName = file.fileName,
          downloadUrl = zImageDownloadUrl(file),
          sizeInBytes = file.sizeInBytes,
        )
      },
    learnMoreUrl = "https://huggingface.co/$Z_IMAGE_HF_REPO",
    localVersion = Z_IMAGE_HF_REVISION,
    license = "Apache-2.0",
    supportsTextToImage = true,
    supportsImageToImage = false,
    supportsImageEditing = false,
    supportsChineseText = true,
    lowMemoryRecommended = true,
    minMemoryGb = 12,
    recommendedWidth = 256,
    recommendedHeight = 256,
    notes =
      "Alibaba Tongyi-MAI Z-Image-Turbo 6B 的 LiteRT 端侧文生图路线。固定 256 x 256、默认 8 步；" +
        "Qwen3-4B 文本编码器、图像/文本 refiner 与 30 层 S3-DiT 被拆分为顺序驻留图，" +
        "使用单一共享 LiteRT Environment，GPU CompiledModel 强制 FP32。默认 Turbo guidance 为 0，" +
        "总下载约 10.6 GB。",
  )

fun isZImageTurboModel(modelId: String): Boolean = modelId == Z_IMAGE_TURBO_MODEL_ID

fun createZImageTurboModel(): Model {
  val primary = Z_IMAGE_FILES.first()
  return Model(
      name = Z_IMAGE_TURBO_MODEL_ID,
      displayName = Z_IMAGE_TURBO_MODEL_INFO.displayName,
      info =
        "${Z_IMAGE_TURBO_MODEL_INFO.notes}\n\n模型格式：${Z_IMAGE_TURBO_MODEL_INFO.format}；" +
          "推理后端：LiteRT GPU / CompiledModel FP32；输出 256 x 256；许可证：Apache-2.0。",
      learnMoreUrl = Z_IMAGE_TURBO_MODEL_INFO.learnMoreUrl,
      bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION, TASK_ID_Z_IMAGE_TURBO),
      minDeviceMemoryInGb = Z_IMAGE_TURBO_MODEL_INFO.minMemoryGb,
      url = zImageDownloadUrl(primary),
      sizeInBytes = primary.sizeInBytes,
      downloadFileName = primary.fileName,
      version = Z_IMAGE_HF_REVISION,
      isZip = false,
      extraDataFiles =
        Z_IMAGE_FILES.drop(1).map { file ->
          ModelDataFile(
            name = file.fileName.substringBeforeLast('.'),
            url = zImageDownloadUrl(file),
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
