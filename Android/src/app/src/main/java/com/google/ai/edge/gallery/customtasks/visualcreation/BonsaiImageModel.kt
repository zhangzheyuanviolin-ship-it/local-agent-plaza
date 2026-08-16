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

const val BONSAI_IMAGE_MODEL_ID = "bonsai-image-ternary-4b-litert"

private const val BONSAI_HF_REPO = "litert-community/Bonsai-Image-ternary-4B"
private const val BONSAI_HF_REVISION = "cbfe141332618ed9c11ce1deff6c9757ad1436fa"

private const val BONSAI_DIT_FILE = "dit_int4b32.tflite"
private const val BONSAI_TEXT_ENCODER_FILE = "textenc_int4.tflite"
private const val BONSAI_VAE_FILE = "vae_dec_fp32.tflite"
const val BONSAI_PIPELINE_META_FILE = "pipeline_meta.json"
const val BONSAI_TOKENIZER_VOCAB_FILE = "vocab.json"
const val BONSAI_TOKENIZER_MERGES_FILE = "merges.txt"

// The downloader uses these values for aggregate progress reporting. Runtime integrity is guarded
// by successful LiteRT graph loading; the immutable HF revision keeps every file in one snapshot.
private const val BONSAI_DIT_SIZE = 2_265_600_000L
private const val BONSAI_TEXT_ENCODER_SIZE = 1_803_900_000L
private const val BONSAI_VAE_SIZE = 199_000_000L
private const val BONSAI_META_SIZE = 5_910L
private const val BONSAI_VOCAB_SIZE = 2_776_833L
private const val BONSAI_MERGES_SIZE = 1_671_853L

private fun bonsaiDownloadUrl(path: String): String =
  "https://huggingface.co/$BONSAI_HF_REPO/resolve/$BONSAI_HF_REVISION/$path?download=true"

val BONSAI_IMAGE_MODEL_INFO: ImageGenerationModelInfo =
  ImageGenerationModelInfo(
    modelId = BONSAI_IMAGE_MODEL_ID,
    displayName = "Bonsai Image 4B LiteRT",
    family = "Bonsai Image 4B",
    // MCP195 gives Bonsai its own Kotlin/LiteRT execution route. This legacy enum value is retained
    // only so the shared settings data class remains binary-compatible with the MCP194 UI model.
    backend = ImageGenerationBackend.STABLE_DIFFUSION_CPP,
    format = "LiteRT TFLite (CPU/XNNPACK)",
    requiredFiles =
      listOf(
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.DIFFUSION_MODEL,
          fileName = BONSAI_DIT_FILE,
          downloadUrl = bonsaiDownloadUrl(BONSAI_DIT_FILE),
          sizeInBytes = BONSAI_DIT_SIZE,
        ),
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.TEXT_ENCODER,
          fileName = BONSAI_TEXT_ENCODER_FILE,
          downloadUrl = bonsaiDownloadUrl(BONSAI_TEXT_ENCODER_FILE),
          sizeInBytes = BONSAI_TEXT_ENCODER_SIZE,
        ),
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.VAE,
          fileName = BONSAI_VAE_FILE,
          downloadUrl = bonsaiDownloadUrl(BONSAI_VAE_FILE),
          sizeInBytes = BONSAI_VAE_SIZE,
        ),
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.CONFIG,
          fileName = BONSAI_PIPELINE_META_FILE,
          downloadUrl = bonsaiDownloadUrl(BONSAI_PIPELINE_META_FILE),
          sizeInBytes = BONSAI_META_SIZE,
        ),
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.TOKENIZER,
          fileName = BONSAI_TOKENIZER_VOCAB_FILE,
          downloadUrl = bonsaiDownloadUrl("tokenizer/vocab.json"),
          sizeInBytes = BONSAI_VOCAB_SIZE,
        ),
        ImageGenerationModelFile(
          role = ImageGenerationModelFileRole.TOKENIZER,
          fileName = BONSAI_TOKENIZER_MERGES_FILE,
          downloadUrl = bonsaiDownloadUrl("tokenizer/merges.txt"),
          sizeInBytes = BONSAI_MERGES_SIZE,
        ),
      ),
    learnMoreUrl = "https://huggingface.co/$BONSAI_HF_REPO",
    localVersion = BONSAI_HF_REVISION,
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
      "Box 3.3.3同代Bonsai Image 4B端侧文生图路线。固定512 x 512，默认4步；" +
        "三张LiteRT图按文本编码器、DiT、VAE顺序加载并及时释放，CPU/XNNPACK执行。",
  )

fun isBonsaiImageModel(modelId: String): Boolean = modelId == BONSAI_IMAGE_MODEL_ID

fun findVisualCreationImageModelInfo(modelId: String): ImageGenerationModelInfo? =
  if (isBonsaiImageModel(modelId)) {
    BONSAI_IMAGE_MODEL_INFO
  } else {
    ImageGenerationModelRegistry.findModel(modelId)
  }

fun createBonsaiImageModel(): Model {
  val info = BONSAI_IMAGE_MODEL_INFO
  return Model(
      name = info.modelId,
      displayName = info.displayName,
      info =
        "${info.notes}\n\n模型格式：${info.format}；推理后端：LiteRT CPU/XNNPACK；" +
          "模型工作集约 ${info.totalSizeInBytes / 1_000_000_000.0} GB；" +
          "固定输出 ${info.recommendedWidth} x ${info.recommendedHeight}；默认4步；" +
          "许可证：${info.license}。",
      learnMoreUrl = info.learnMoreUrl,
      bestForTaskIds = listOf(TASK_ID_LOCAL_VISUAL_CREATION),
      minDeviceMemoryInGb = info.minMemoryGb,
      url = bonsaiDownloadUrl(BONSAI_DIT_FILE),
      sizeInBytes = BONSAI_DIT_SIZE,
      downloadFileName = BONSAI_DIT_FILE,
      version = BONSAI_HF_REVISION,
      isZip = false,
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = "text_encoder",
            url = bonsaiDownloadUrl(BONSAI_TEXT_ENCODER_FILE),
            downloadFileName = BONSAI_TEXT_ENCODER_FILE,
            sizeInBytes = BONSAI_TEXT_ENCODER_SIZE,
          ),
          ModelDataFile(
            name = "vae",
            url = bonsaiDownloadUrl(BONSAI_VAE_FILE),
            downloadFileName = BONSAI_VAE_FILE,
            sizeInBytes = BONSAI_VAE_SIZE,
          ),
          ModelDataFile(
            name = "pipeline_meta",
            url = bonsaiDownloadUrl(BONSAI_PIPELINE_META_FILE),
            downloadFileName = BONSAI_PIPELINE_META_FILE,
            sizeInBytes = BONSAI_META_SIZE,
          ),
          ModelDataFile(
            name = "tokenizer_vocab",
            url = bonsaiDownloadUrl("tokenizer/vocab.json"),
            downloadFileName = BONSAI_TOKENIZER_VOCAB_FILE,
            sizeInBytes = BONSAI_VOCAB_SIZE,
          ),
          ModelDataFile(
            name = "tokenizer_merges",
            url = bonsaiDownloadUrl("tokenizer/merges.txt"),
            downloadFileName = BONSAI_TOKENIZER_MERGES_FILE,
            sizeInBytes = BONSAI_MERGES_SIZE,
          ),
        ),
      isLlm = false,
      showRunAgainButton = false,
      showBenchmarkButton = false,
    )
    .apply { preProcess() }
}
