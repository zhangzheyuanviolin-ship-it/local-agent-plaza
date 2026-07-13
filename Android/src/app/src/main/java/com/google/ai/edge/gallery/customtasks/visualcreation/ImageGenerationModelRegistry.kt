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
  val localDreamTextEmbeddingSize: Int = 768,
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
      qnn8Gen2Model(
        modelId = "dreamshaper-v8-qnn-8gen2",
        displayName = "DreamShaper V8 QNN 8gen2",
        fileName = "DreamShaperV8_qnn2.28_8gen2.zip",
        sizeInBytes = 1_032_290_626L,
        notes = "Local Dream QNN 8gen2候选模型，偏通用幻想、写实和插画场景；适合测试比Absolute Reality更强的复杂画面理解。",
      ),
      qnn8Gen2Model(
        modelId = "realistic-vision-hyper-qnn-8gen2",
        displayName = "Realistic Vision Hyper QNN 8gen2",
        fileName = "RealisticVisionHyper_qnn2.28_8gen2.zip",
        sizeInBytes = 1_068_141_522L,
        notes = "Local Dream QNN 8gen2候选模型，偏写实摄影风格；适合人物、动物、室内外真实场景验证。",
      ),
      qnn8Gen2Model(
        modelId = "majicmix-realistic-v7-qnn-8gen2",
        displayName = "MajicMix Realistic V7 QNN 8gen2",
        fileName = "MajicmixRealisticV7_qnn2.28_8gen2.zip",
        sizeInBytes = 1_055_975_131L,
        notes = "Local Dream QNN 8gen2候选模型，偏亚洲写实和人像风格；用于测试人物与装饰细节生成。",
      ),
      qnn8Gen2Model(
        modelId = "anything-v5-qnn-8gen2",
        displayName = "Anything V5 QNN 8gen2",
        fileName = "AnythingV5_qnn2.28_8gen2.zip",
        sizeInBytes = 1_057_820_237L,
        notes = "Local Dream QNN 8gen2候选模型，偏二次元和插画风格；适合测试非写实画风。",
      ),
      qnn8Gen2Model(
        modelId = "meina-mix-v12-qnn-8gen2",
        displayName = "MeinaMix V12 QNN 8gen2",
        fileName = "MeinaMixV12_qnn2.28_8gen2.zip",
        sizeInBytes = 1_031_765_133L,
        notes = "Local Dream QNN 8gen2候选模型，偏动漫插画和角色创作；适合测试风格化提示词。",
      ),
      abyssOrangeMix3Qnn8Gen2Model(),
      absoluteRealityMnnCpuModel(),
      mnnCpuModel("anything-v5-mnn-cpu", "Anything V5 MNN CPU", "AnythingV5.zip", 1_191_044_427L),
      mnnCpuModel("chillout-mix-mnn-cpu", "ChilloutMix MNN CPU", "ChilloutMix.zip", 1_203_917_293L),
      mnnCpuModel("cute-yuki-mix-mnn-cpu", "CuteYukiMix MNN CPU", "CuteYukiMix.zip", 1_188_112_898L),
      mnnCpuModel("qtea-mix-mnn-cpu", "QteaMix MNN CPU", "QteaMix.zip", 1_191_096_924L),
      sdxlQnn8Gen3Model(
        modelId = "sdxl-base-qnn-8gen3",
        displayName = "SDXL Base 1.0 QNN 8gen3",
        fileName = "sdxl_base_qnn2.28_8gen3.zip",
        sizeInBytes = 3_753_226_114L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3基础通用模型，推荐高内存设备测试1024 x 1024文生图质量和复杂提示词遵循能力。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "illustrious-v16-qnn-8gen3",
        displayName = "Illustrious v16 QNN 8gen3",
        fileName = "illustrious_v16_qnn2.28_8gen3.zip",
        sizeInBytes = 3_726_876_852L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Illustrious / NoAI style licenses; verify upstream use terms",
        notes = "Local Dream SDXL QNN 8gen3高质量插画候选模型，适合复杂角色、场景和风格化画面测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "illustrious-v16-dmd2-qnn-8gen3",
        displayName = "Illustrious v16 DMD2 QNN 8gen3",
        fileName = "illustrious_v16_dmd2_qnn2.28_8gen3.zip",
        sizeInBytes = 3_722_127_035L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Illustrious / DMD2 converted weights; verify upstream use terms",
        notes = "Local Dream SDXL QNN 8gen3 DMD2蒸馏候选模型，优先用于测试更少步数下的插画出图速度和质量。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "realvis-xl-v5-qnn-8gen3",
        displayName = "RealVisXL V5 QNN 8gen3",
        fileName = "realvis_xl_v5_qnn2.28_8gen3.zip",
        sizeInBytes = 3_499_694_289L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3写实摄影候选模型，适合人物、动物、产品和真实场景测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "juggernaut-xl-qnn-8gen3",
        displayName = "Juggernaut XL QNN 8gen3",
        fileName = "juggernaut_qnn2.28_8gen3.zip",
        sizeInBytes = 3_747_687_306L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3通用高质量候选模型，适合复杂写实、电影感和多元素画面测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "cyber-realistic-v10-qnn-8gen3",
        displayName = "CyberRealistic V10 QNN 8gen3",
        fileName = "cyber_realistic_v10_qnn2.28_8gen3.zip",
        sizeInBytes = 3_745_235_842L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3写实和电影风格候选模型，适合测试清晰主体、复杂光照和材质表现。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "wai-illustrious-v170-dmd2-qnn-8gen3",
        displayName = "WAI Illustrious SDXL v170 DMD2 QNN 8gen3",
        fileName = "waiIllustriousSDXL_v170DMD2_qnn2.28_8gen3.zip",
        sizeInBytes = 3_724_793_979L,
        sourceRepo = "YuuiKurata/waiIllustriousSDXL_qnn2.28",
        license = "CreativeML Open RAIL-M / upstream WAI Illustrious terms",
        notes = "Local Dream SDXL QNN 8gen3高质量插画模型，Hugging Face模型卡标注Local Dream和Snapdragon 8 Gen 3/Elite方向。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "intorealism-ultra-v11-qnn-8gen3",
        displayName = "IntoRealism Ultra V11 QNN 8gen3",
        fileName = "IntoRealism_Ultra_V11_qnn2.28_8gen3.zip",
        sizeInBytes = 3_789_403_504L,
        sourceRepo = "Mr-J-369/intorealismUltra_v11-SDXL-qnn2.28",
        license = "Apache-2.0 / upstream converted weights",
        notes = "Local Dream SDXL QNN 8gen3写实高质量候选模型，适合测试复杂自然语言提示词下的真实感和主体稳定性。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "dreamshaper-sdxl-qnn-8gen3",
        displayName = "DreamShaper SDXL QNN 8gen3",
        fileName = "dreamshaper_qnn2.28_8gen3.zip",
        sizeInBytes = 3_753_755_544L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3通用创作候选模型，适合复杂幻想、写实和插画混合场景测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "epic-realism-sdxl-qnn-8gen3",
        displayName = "Epic Realism SDXL QNN 8gen3",
        fileName = "epic_realism_qnn2.28_8gen3.zip",
        sizeInBytes = 3_502_991_005L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3写实候选模型，适合人物、动物、室内外摄影和真实质感测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "perfect-deliberate-v9-qnn-8gen3",
        displayName = "Perfect Deliberate V9 QNN 8gen3",
        fileName = "perfect_deliberate_v9_qnn2.28_8gen3.zip",
        sizeInBytes = 3_724_959_205L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3通用高质量候选模型，适合测试复杂主体、环境和构图稳定性。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "perfection-realistic-v8-qnn-8gen3",
        displayName = "Perfection Realistic V8 QNN 8gen3",
        fileName = "perfection_realistic_v8_qnn2.28_8gen3.zip",
        sizeInBytes = 3_735_902_139L,
        sourceRepo = "xororz/sdxl-qnn",
        notes = "Local Dream SDXL QNN 8gen3写实摄影候选模型，适合清晰主体、真实光照和材质细节测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "pony-diffusion-v6xl-qnn-8gen3",
        displayName = "Pony Diffusion V6XL QNN 8gen3",
        fileName = "ponydiffusion_v6xl_qnn2.28_8gen3.zip",
        sizeInBytes = 3_725_876_252L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Pony Diffusion / local-dream converted weights",
        notes = "Local Dream SDXL QNN 8gen3风格化和角色创作候选模型，适合复杂角色、插画和非写实风格测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "animagine-v4-qnn-8gen3",
        displayName = "Animagine V4 QNN 8gen3",
        fileName = "animagine_v4_qnn2.28_8gen3.zip",
        sizeInBytes = 3_752_469_362L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Animagine / local-dream converted weights",
        notes = "Local Dream SDXL QNN 8gen3动漫插画候选模型，适合角色、服装、姿态和复杂二次元画面测试。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "anikawa-v4-qnn-8gen3",
        displayName = "Anikawa V4 QNN 8gen3",
        fileName = "anikawa_v4_qnn2.28_8gen3.zip",
        sizeInBytes = 3_729_112_783L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Anikawa / local-dream converted weights",
        notes = "Local Dream SDXL QNN 8gen3动漫和插画候选模型，适合测试风格化人物、场景和色彩表现。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "illustrious-v17-qnn-8gen3",
        displayName = "Illustrious v17 QNN 8gen3",
        fileName = "illustrious_v17_qnn2.28_8gen3.zip",
        sizeInBytes = 3_723_557_978L,
        sourceRepo = "xororz/sdxl-qnn",
        license = "Upstream Illustrious / NoAI style licenses; verify upstream use terms",
        notes = "Local Dream SDXL QNN 8gen3插画高质量候选模型，作为Illustrious v16之外的新版风格化测试项。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "wai-illustrious-v170-qnn-8gen3",
        displayName = "WAI Illustrious SDXL v170 QNN 8gen3",
        fileName = "waiIllustriousSDXL_v170_qnn2.28_8gen3.zip",
        sizeInBytes = 3_724_602_407L,
        sourceRepo = "YuuiKurata/waiIllustriousSDXL_qnn2.28",
        license = "CreativeML Open RAIL-M / upstream WAI Illustrious terms",
        notes = "Local Dream SDXL QNN 8gen3高质量插画候选模型，作为v170 DMD2之外的标准版本测试项。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "mopmix-omnia-sdxl-qnn-8gen3",
        displayName = "MopMix Omnia SDXL QNN 8gen3",
        fileName = "mopMix_omnia_qnn2.28_8gen3.zip",
        sizeInBytes = 3_649_301_625L,
        sourceRepo = "Mr-J-369/mopMix_omnia-SDXL-qnn2.28",
        notes = "Local Dream SDXL QNN 8gen3通用风格候选模型，适合测试比现有基础模型更复杂的多元素画面。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "mopmix-epic-realism-sdxl-qnn-8gen3",
        displayName = "MopMix EpicRealism SDXL QNN 8gen3",
        fileName = "mopMix_epicRealismPure_qnn2.28_8gen3.zip",
        sizeInBytes = 3_652_426_869L,
        sourceRepo = "Mr-J-369/mopMix_EpicRealism-SDXL-qnn2.28",
        notes = "Local Dream SDXL QNN 8gen3写实融合候选模型，适合测试人物、动物、产品和电影感复杂场景。",
      ),
      sdxlQnn8Gen3Model(
        modelId = "jrd-renderspec-xl-turbo-qnn-8gen3",
        displayName = "JRD Renderspec XL Turbo QNN 8gen3",
        fileName = "jrdRenderspecXLTURBO_qnn2.28_8gen3.zip",
        sizeInBytes = 3_402_957_762L,
        sourceRepo = "Mr-J-369/jrdRenderspecXLTURBO-SDXL-qnn2.28",
        notes = "Local Dream SDXL QNN 8gen3 Turbo候选模型，适合测试较少步数下的速度和质量平衡。",
      ),
    )

  fun findModel(modelId: String): ImageGenerationModelInfo? {
    return recommendedModels.firstOrNull { it.modelId == modelId }
  }

  fun requireModel(modelId: String): ImageGenerationModelInfo {
    return findModel(modelId) ?: error("Image generation model not found: $modelId")
  }

  private fun qnn8Gen2Model(
    modelId: String,
    displayName: String,
    fileName: String,
    sizeInBytes: Long,
    notes: String,
  ): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = modelId,
      displayName = displayName,
      family = "Local Dream SD1.5 QNN",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      format = "QNN ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = fileName,
            downloadUrl = "https://huggingface.co/xororz/sd-qnn/resolve/main/$fileName",
            sizeInBytes = sizeInBytes,
          )
        ),
      learnMoreUrl = "https://huggingface.co/xororz/sd-qnn",
      localVersion = "$modelId-2026-06-20",
      license = "Upstream model licenses / local-dream converted weights",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = false,
      minMemoryGb = 6,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes = notes,
    )
  }

  private fun abyssOrangeMix3Qnn8Gen2Model(): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = "abyss-orange-mix3-qnn-8gen2",
      displayName = "AbyssOrangeMix3 QNN 8gen2",
      family = "Local Dream SD1.5 QNN",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      format = "QNN ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = "abyssorangemix3AOM3_aom3a1b_qnn2.28_8gen2.zip",
            downloadUrl =
              "https://huggingface.co/Mr-J-369/AbyssOrangeMix3-SD1.5-qnn2.28/resolve/main/abyssorangemix3AOM3_aom3a1b_qnn2.28_8gen2.zip",
            sizeInBytes = 990_581_965L,
          )
        ),
      learnMoreUrl = "https://huggingface.co/Mr-J-369/AbyssOrangeMix3-SD1.5-qnn2.28",
      localVersion = "abyss-orange-mix3-qnn-8gen2-2026-06-20",
      license = "Upstream model licenses / local-dream converted weights",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = false,
      minMemoryGb = 6,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes = "第三方Local Dream QNN 8gen2社区转换模型，偏二次元和插画；ZIP内包含QNN格式UNet/VAE与MNN文本编码器文件。",
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
    return mnnCpuModel(
      modelId = "absolute-reality-mnn-cpu",
      displayName = "Absolute Reality MNN CPU",
      fileName = "AbsoluteReality.zip",
      sizeInBytes = 1_288_490_188L,
      notes = "兼容性兜底的Local Dream / MNN CPU图像生成模型；速度慢于QNN NPU，但适合QNN不可用时验证。",
      localVersion = "absolute-reality-mnn-cpu-2026-06-14",
    )
  }

  private fun mnnCpuModel(
    modelId: String,
    displayName: String,
    fileName: String,
    sizeInBytes: Long,
    notes: String = "Local Dream / MNN CPU候选模型，作为兼容性兜底；速度通常慢于QNN NPU版本。",
    localVersion: String = "$modelId-2026-06-20",
  ): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = modelId,
      displayName = displayName,
      family = "Local Dream SD1.5 MNN",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      format = "MNN ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = fileName,
            downloadUrl = "https://huggingface.co/xororz/sd-mnn/resolve/main/$fileName",
            sizeInBytes = sizeInBytes,
          )
        ),
      learnMoreUrl = "https://huggingface.co/xororz/sd-mnn",
      localVersion = localVersion,
      license = "Upstream model licenses / local-dream converted weights",
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = true,
      minMemoryGb = 4,
      recommendedWidth = 512,
      recommendedHeight = 512,
      notes = notes,
    )
  }

  private fun sdxlQnn8Gen3Model(
    modelId: String,
    displayName: String,
    fileName: String,
    sizeInBytes: Long,
    sourceRepo: String,
    notes: String,
    license: String = "Upstream model licenses / local-dream converted weights",
  ): ImageGenerationModelInfo {
    return ImageGenerationModelInfo(
      modelId = modelId,
      displayName = displayName,
      family = "Local Dream SDXL QNN",
      backend = ImageGenerationBackend.LOCAL_DREAM_QNN_MNN,
      localDreamTextEmbeddingSize = 1280,
      format = "QNN SDXL ZIP",
      requiredFiles =
        listOf(
          ImageGenerationModelFile(
            role = ImageGenerationModelFileRole.MODEL_ARCHIVE,
            fileName = fileName,
            downloadUrl = "https://huggingface.co/$sourceRepo/resolve/main/$fileName",
            sizeInBytes = sizeInBytes,
          )
        ),
      learnMoreUrl = "https://huggingface.co/$sourceRepo",
      localVersion = "$modelId-2026-06-20",
      license = license,
      supportsTextToImage = true,
      supportsImageToImage = false,
      supportsImageEditing = false,
      supportsChineseText = false,
      lowMemoryRecommended = false,
      minMemoryGb = 16,
      recommendedWidth = 1024,
      recommendedHeight = 1024,
      notes = notes,
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
          "${modelInfo.notes}\n\n模型格式：${modelInfo.format}；推理后端：Local Dream QNN/MNN；" +
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
