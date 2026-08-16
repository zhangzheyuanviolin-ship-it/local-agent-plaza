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

const val FLUX_KLEIN_IMAGE_MODEL_ID = "flux-2-klein-4b-litert"

private const val FLUX_HF_REPO = "litert-community/FLUX.2-klein-4B-LiteRT"
private const val FLUX_HF_REVISION = "main"

private const val FLUX_ENC0 = "ke_enc0.tflite"
private const val FLUX_ENC1 = "ke_enc1.tflite"
private const val FLUX_ENC2 = "ke_enc2.tflite"
private const val FLUX_PREP = "kc_prep.tflite"
private const val FLUX_DOUBLE0 = "kc_double0.tflite"
private const val FLUX_DOUBLE1 = "kc_double1.tflite"
private const val FLUX_SINGLE0 = "kc_single0.tflite"
private const val FLUX_SINGLE1 = "kc_single1.tflite"
private const val FLUX_SINGLE2 = "kc_single2.tflite"
private const val FLUX_SINGLE3 = "kc_single3.tflite"
private const val FLUX_FINAL = "kc_final.tflite"
private const val FLUX_VAE = "kv_vae.tflite"
const val FLUX_QWEN_EMBED = "qwen_embed_fp16.bin"
const val FLUX_QWEN_VOCAB = "qwen_vocab.txt"
const val FLUX_QWEN_MERGES = "qwen_merges.txt"
const val FLUX_QWEN_SPECIAL = "qwen_special.txt"

private val FLUX_FILES =
  listOf(
    Triple(FLUX_ENC0, 912_190_032L, FLUX_ENC0),
    Triple(FLUX_ENC1, 912_190_032L, FLUX_ENC1),
    Triple(FLUX_ENC2, 912_190_032L, FLUX_ENC2),
    Triple(FLUX_PREP, 166_174_032L, FLUX_PREP),
    Triple(FLUX_DOUBLE0, 738_688_720L, FLUX_DOUBLE0),
    Triple(FLUX_DOUBLE1, 492_460_800L, FLUX_DOUBLE1),
    Triple(FLUX_SINGLE0, 615_367_264L, FLUX_SINGLE0),
    Triple(FLUX_SINGLE1, 615_367_264L, FLUX_SINGLE1),
    Triple(FLUX_SINGLE2, 615_367_264L, FLUX_SINGLE2),
    Triple(FLUX_SINGLE3, 615_367_264L, FLUX_SINGLE3),
    Triple(FLUX_FINAL, 19_348_608L, FLUX_FINAL),
    Triple(FLUX_VAE, 50_207_984L, FLUX_VAE),
    Triple(FLUX_QWEN_EMBED, 777_912_320L, "tokenizer/$FLUX_QWEN_EMBED"),
    Triple(FLUX_QWEN_VOCAB, 1_521_491L, "tokenizer/$FLUX_QWEN_VOCAB"),
    Triple(FLUX_QWEN_MERGES, 1_671_838L, "tokenizer/$FLUX_QWEN_MERGES"),
    Triple(FLUX_QWEN_SPECIAL, 547L, "tokenizer/$FLUX_QWEN_SPECIAL"),
  )

private fun fluxDownloadUrl(remotePath: String): String =
  "https://huggingface.co/$FLUX_HF_REPO/resolve/$FLUX_HF_REVISION/$remotePath?download=true"

val FLUX_KLEIN_IMAGE_MODEL_INFO: ImageGenerationModelInfo =
  ImageGenerationModelInfo(
    modelId = FLUX_KLEIN_IMAGE_MODEL_ID,
    displayName = "FLUX.2 Klein 4B LiteRT",
    family = "FLUX.2 Klein 4B",
    backend = ImageGenerationBackend.FLUX_LITERT_GPU_COMPILED_MODEL,
    format = "LiteRT CompiledModel int8 chunks (GPU FP32)",
    requiredFiles =
      FLUX_FILES.mapIndexed { index, (name, size, remote) ->
        ImageGenerationModelFile(
          role =
            when {
              name.startsWith("ke_enc") || name == FLUX_QWEN_EMBED -> ImageGenerationModelFileRole.TEXT_ENCODER
              name.startsWith("kc_") -> ImageGenerationModelFileRole.DIFFUSION_MODEL
              name == FLUX_VAE -> ImageGenerationModelFileRole.VAE
              else -> ImageGenerationModelFileRole.TOKENIZER
            },
          fileName = name,
          downloadUrl = fluxDownloadUrl(remote),
          sizeInBytes = size,
        )
      },
    learnMoreUrl = "https://huggingface.co/$FLUX_HF_REPO",
    localVersion = FLUX_HF_REVISION,
    license = "Apache-2.0",
    supportsTextToImage = true,
    supportsImageToImage = false,
    supportsImageEditing = false,
    supportsChineseText = true,
    lowMemoryRecommended = true,
    minMemoryGb = 10,
    recommendedWidth = 256,
    recommendedHeight = 256,
    notes =
      "FLUX.2 Klein 4B 的 LiteRT 端侧文生图路线。固定 256 x 256、4 步；" +
        "Qwen3-4B 文本编码器和 DiT 均拆分为顺序驻留图，使用共享 LiteRT Environment，" +
        "优先 GPU CompiledModel FP32，GPU 编译失败时回退 CPU。总下载约 7.45 GB。",
  )

fun isFluxKleinImageModel(modelId: String): Boolean = modelId == FLUX_KLEIN_IMAGE_MODEL_ID

fun createFluxKleinImageModel(): Model {
  val primary = FLUX_FILES.first()
  return Model(
      name = FLUX_KLEIN_IMAGE_MODEL_ID,
      displayName = FLUX_KLEIN_IMAGE_MODEL_INFO.displayName,
      info =
        "${FLUX_KLEIN_IMAGE_MODEL_INFO.notes}\n\n模型格式：${FLUX_KLEIN_IMAGE_MODEL_INFO.format}；" +
          "推理后端：LiteRT GPU / CompiledModel FP32；输出 256 x 256；许可证：Apache-2.0。",
      learnMoreUrl = FLUX_KLEIN_IMAGE_MODEL_INFO.learnMoreUrl,
      bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION, TASK_ID_FLUX_KLEIN_IMAGE),
      minDeviceMemoryInGb = FLUX_KLEIN_IMAGE_MODEL_INFO.minMemoryGb,
      url = fluxDownloadUrl(primary.third),
      sizeInBytes = primary.second,
      downloadFileName = primary.first,
      version = FLUX_HF_REVISION,
      isZip = false,
      extraDataFiles =
        FLUX_FILES.drop(1).map { (name, size, remote) ->
          ModelDataFile(
            name = name.substringBeforeLast('.'),
            url = fluxDownloadUrl(remote),
            downloadFileName = name,
            sizeInBytes = size,
          )
        },
      isLlm = false,
      showRunAgainButton = false,
      showBenchmarkButton = false,
    )
    .apply { preProcess() }
}
