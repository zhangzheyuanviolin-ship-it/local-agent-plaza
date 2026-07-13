package com.google.ai.edge.gallery.customtasks.aikeyboard.model

import java.text.NumberFormat
import java.util.Locale

data class ModelDownloadSource(
    val id: String,
    val displayName: String,
    val url: String
)

data class AiKeyboardModelDescriptor(
    val id: String,
    val language: String,
    val tierRank: Int,
    val displayName: String,
    val fileSizeBytes: Long,
    val downloadSources: List<ModelDownloadSource>,
    val bundledAssetZipPath: String? = null,
    val archiveSha256: String? = null
)

object AiKeyboardModelCatalog {
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    private const val TIER_SMALL = 1
    private const val TIER_MEDIUM = 2
    private const val TIER_LARGE = 3
    private const val OFFICIAL_BASE = "https://alphacephei.com/vosk/models"

    val models: List<AiKeyboardModelDescriptor> = listOf(
        AiKeyboardModelDescriptor(
            id = "zh_small_cn_022",
            language = LANG_ZH,
            tierRank = TIER_SMALL,
            displayName = "中文小模型 Vosk small-cn-0.22",
            fileSizeBytes = 43_898_754L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "official",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-small-cn-0.22.zip"
                )
            ),
            bundledAssetZipPath = "models/zh_small_cn_022.zip",
            archiveSha256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
        ),
        AiKeyboardModelDescriptor(
            id = "zh_medium_cn_022",
            language = LANG_ZH,
            tierRank = TIER_MEDIUM,
            displayName = "中文中模型 Vosk cn-0.22",
            fileSizeBytes = 1_358_736_686L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "hf_mirror_cn",
                    displayName = "HF中国镜像",
                    url = "https://hf-mirror.com/LiangJingyi/vosk-model-cn-0.22/resolve/main/model-cn.zip"
                ),
                ModelDownloadSource(
                    id = "hf_origin_cn",
                    displayName = "HuggingFace源",
                    url = "https://huggingface.co/LiangJingyi/vosk-model-cn-0.22/resolve/main/model-cn.zip"
                ),
                ModelDownloadSource(
                    id = "official_cn",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-cn-0.22.zip"
                )
            )
        ),
        AiKeyboardModelDescriptor(
            id = "zh_large_multicn_015",
            language = LANG_ZH,
            tierRank = TIER_LARGE,
            displayName = "中文大模型 Vosk cn-kaldi-multicn-0.15",
            fileSizeBytes = 1_678_260_145L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "hf_mirror_multicn",
                    displayName = "HF中国镜像",
                    url = "https://hf-mirror.com/xushunbin/vosk-model-cn-kaldi-multicn-0.15/resolve/main/vosk-model-cn-kaldi-multicn-0.15.zip"
                ),
                ModelDownloadSource(
                    id = "hf_origin_multicn",
                    displayName = "HuggingFace源",
                    url = "https://huggingface.co/xushunbin/vosk-model-cn-kaldi-multicn-0.15/resolve/main/vosk-model-cn-kaldi-multicn-0.15.zip"
                ),
                ModelDownloadSource(
                    id = "official_multicn",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-cn-kaldi-multicn-0.15.zip"
                )
            )
        ),
        AiKeyboardModelDescriptor(
            id = "en_small_us_015",
            language = LANG_EN,
            tierRank = TIER_SMALL,
            displayName = "英文小模型 Vosk small-en-us-0.15",
            fileSizeBytes = 41_205_931L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "hf_mirror_en_small",
                    displayName = "HF中国镜像",
                    url = "https://hf-mirror.com/mychen76/vosk-models/resolve/main/en/vosk-model-small-en-us-0.15.zip"
                ),
                ModelDownloadSource(
                    id = "hf_origin_en_small",
                    displayName = "HuggingFace源",
                    url = "https://huggingface.co/mychen76/vosk-models/resolve/main/en/vosk-model-small-en-us-0.15.zip"
                ),
                ModelDownloadSource(
                    id = "official_en_small",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-small-en-us-0.15.zip"
                )
            )
        ),
        AiKeyboardModelDescriptor(
            id = "en_medium_us_022",
            language = LANG_EN,
            tierRank = TIER_MEDIUM,
            displayName = "英文中模型 Vosk en-us-0.22",
            fileSizeBytes = 1_913_365_522L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "hf_mirror_en_medium",
                    displayName = "HF中国镜像",
                    url = "https://hf-mirror.com/mychen76/vosk-models/resolve/main/en/vosk-model-en-us-0.22.zip"
                ),
                ModelDownloadSource(
                    id = "hf_origin_en_medium",
                    displayName = "HuggingFace源",
                    url = "https://huggingface.co/mychen76/vosk-models/resolve/main/en/vosk-model-en-us-0.22.zip"
                ),
                ModelDownloadSource(
                    id = "official_en_medium",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-en-us-0.22.zip"
                )
            )
        ),
        AiKeyboardModelDescriptor(
            id = "en_large_gigaspeech_042",
            language = LANG_EN,
            tierRank = TIER_LARGE,
            displayName = "英文大模型 Vosk en-us-0.42-gigaspeech",
            fileSizeBytes = 2_423_807_363L,
            downloadSources = listOf(
                ModelDownloadSource(
                    id = "hf_mirror_en_large",
                    displayName = "HF中国镜像",
                    url = "https://hf-mirror.com/mychen76/vosk-models/resolve/main/en/vosk-model-en-us-0.42-gigaspeech.zip"
                ),
                ModelDownloadSource(
                    id = "hf_origin_en_large",
                    displayName = "HuggingFace源",
                    url = "https://huggingface.co/mychen76/vosk-models/resolve/main/en/vosk-model-en-us-0.42-gigaspeech.zip"
                ),
                ModelDownloadSource(
                    id = "official_en_large",
                    displayName = "官方源",
                    url = "$OFFICIAL_BASE/vosk-model-en-us-0.42-gigaspeech.zip"
                )
            )
        )
    )

    fun modelsForLanguage(language: String): List<AiKeyboardModelDescriptor> {
        return models.filter { it.language == language }.sortedBy { it.tierRank }
    }

    fun byId(modelId: String): AiKeyboardModelDescriptor? {
        return models.firstOrNull { it.id == modelId }
    }

    fun defaultModelId(language: String): String {
        return modelsForLanguage(language).firstOrNull()?.id.orEmpty()
    }

    fun formatSizeLabel(bytes: Long): String {
        val nf = NumberFormat.getNumberInstance(Locale.CHINA)
        val byteText = "${nf.format(bytes)} 字节"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val approx = if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
        return "$byteText，约 $approx"
    }

    fun formatSpeedLabel(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/s"
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB/s", mb)
        } else {
            String.format(Locale.US, "%.1f KB/s", kb)
        }
    }
}
