package com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline

import android.content.Context
import com.google.ai.edge.gallery.GalleryApplication
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DEFAULT_CONTEXT_WINDOW
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.data.normalizeContextWindowAndMaxTokens
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.gson.Gson
import java.io.File

data class AiKeyboardTextModelCandidate(
  val model: Model,
  val path: String,
) {
  val displayName: String
    get() = model.displayName.ifBlank { model.name }
}

class AiKeyboardTextModelRepository(
  private val context: Context,
  private val dataStoreRepository: DataStoreRepository? =
    (context.applicationContext as? GalleryApplication)?.dataStoreRepository,
) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val gson = Gson()

  fun getSelectedPipelineId(): String {
    val saved = prefs.getString(KEY_SELECTED_PIPELINE_ID, null)
    return saved?.takeIf { AiKeyboardPipelineCatalog.byId(it) != null }
      ?: AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID
  }

  fun setSelectedPipelineId(id: String) {
    val resolved = AiKeyboardPipelineCatalog.byId(id)?.id ?: AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID
    prefs.edit().putString(KEY_SELECTED_PIPELINE_ID, resolved).apply()
  }

  fun selectNextPipeline(): AiKeyboardPipelinePreset {
    val next = AiKeyboardPipelineCatalog.nextAfter(getSelectedPipelineId())
    setSelectedPipelineId(next.id)
    return next
  }

  fun listAvailableModels(): List<AiKeyboardTextModelCandidate> {
    val candidates = (readDownloadedAllowlistModels() + readImportedModels())
      .filter { it.model.runtimeType == RuntimeType.LITERT_LM && it.model.isLlm }
      .filter { File(it.path).exists() }

    return candidates.distinctBy { it.path }
  }

  fun getSelectedModel(): AiKeyboardTextModelCandidate? {
    val candidates = listAvailableModels()
    if (candidates.isEmpty()) return null
    val selectedPath = prefs.getString(KEY_SELECTED_TEXT_MODEL_PATH, null)
    val selectedName = prefs.getString(KEY_SELECTED_TEXT_MODEL_NAME, null)
    val selected =
      candidates.firstOrNull { it.path == selectedPath } ?: candidates.firstOrNull {
        it.model.name == selectedName
      }
    val resolved = selected ?: candidates.first()
    persistSelectedModel(resolved)
    return resolved
  }

  fun selectNextModel(): AiKeyboardTextModelCandidate? {
    val candidates = listAvailableModels()
    if (candidates.isEmpty()) return null
    val selected = getSelectedModel()
    val index = candidates.indexOfFirst { it.path == selected?.path }
    val next = candidates[(index + 1).coerceAtLeast(0) % candidates.size]
    persistSelectedModel(next)
    return next
  }

  private fun persistSelectedModel(candidate: AiKeyboardTextModelCandidate) {
    prefs
      .edit()
      .putString(KEY_SELECTED_TEXT_MODEL_NAME, candidate.model.name)
      .putString(KEY_SELECTED_TEXT_MODEL_PATH, candidate.path)
      .apply()
  }

  private fun readDownloadedAllowlistModels(): List<AiKeyboardTextModelCandidate> {
    val externalFilesDir = context.getExternalFilesDir(null) ?: return emptyList()
    val allowlistFile = File(externalFilesDir, MODEL_ALLOWLIST_FILENAME)
    if (!allowlistFile.exists()) return emptyList()

    val allowlist =
      runCatching { gson.fromJson(allowlistFile.readText(), ModelAllowlist::class.java) }.getOrNull()
        ?: return emptyList()
    return allowlist.models
      .asSequence()
      .filter { it.disabled != true }
      .filter {
        it.taskTypes.contains(BuiltInTaskId.LLM_CHAT) ||
          it.taskTypes.contains(BuiltInTaskId.LLM_PROMPT_LAB) ||
          it.taskTypes.contains(BuiltInTaskId.LLM_AGENT_CHAT)
      }
      .map { it.toModel() }
      .filter { it.runtimeType == RuntimeType.LITERT_LM }
      .mapNotNull { model ->
        model.preProcess()
        val path = model.getPath(context)
        val file = File(path)
        if (file.exists() && !isPartialDownload(model)) {
          AiKeyboardTextModelCandidate(model = model, path = path)
        } else {
          null
        }
      }
      .toList()
  }

  private fun isPartialDownload(model: Model): Boolean {
    val externalFilesDir = context.getExternalFilesDir(null) ?: return false
    val tempFile =
      File(
        externalFilesDir,
        listOf(model.normalizedName, model.version, "${model.downloadFileName}.$TMP_FILE_EXT")
          .joinToString(File.separator),
      )
    return tempFile.exists()
  }

  private fun readImportedModels(): List<AiKeyboardTextModelCandidate> {
    val importedModels = runCatching { dataStoreRepository?.readImportedModels().orEmpty() }
      .getOrDefault(emptyList())
    return importedModels.mapNotNull { info ->
      val model = createModelFromImportedModel(info)
      val path = model.getPath(context)
      if (File(path).exists()) {
        AiKeyboardTextModelCandidate(model = model, path = path)
      } else {
        null
      }
    }
  }

  private fun createModelFromImportedModel(info: ImportedModel): Model {
    val accelerators =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { label ->
          when (label.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU
            Accelerator.TPU.label -> Accelerator.TPU
            else -> null
          }
        }
        .ifEmpty { listOf(Accelerator.CPU) }
    val maxTokens = info.llmConfig.defaultMaxTokens.coerceAtLeast(1)
    val maxContextLength =
      if (info.llmConfig.maxContextLength > 0) {
        info.llmConfig.maxContextLength
      } else {
        maxOf(maxTokens, DEFAULT_CONTEXT_WINDOW)
      }
    val configs =
      createLlmChatConfigs(
        defaultMaxToken = maxTokens.coerceAtMost(maxContextLength),
        defaultMaxContextLength = maxContextLength,
        defaultTopK = info.llmConfig.defaultTopk,
        defaultTopP = info.llmConfig.defaultTopp,
        defaultTemperature = info.llmConfig.defaultTemperature,
        accelerators = accelerators,
        supportThinking = info.llmConfig.supportThinking,
        supportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding,
        contextWindowEditable = true,
      )
    val capabilities = mutableListOf<ModelCapability>()
    val capabilityToTaskTypes = mutableMapOf<ModelCapability, List<String>>()
    if (info.llmConfig.supportThinking) {
      capabilities.add(ModelCapability.LLM_THINKING)
    }
    if (info.llmConfig.supportSpeculativeDecoding) {
      capabilities.add(ModelCapability.SPECULATIVE_DECODING)
      capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
        listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_PROMPT_LAB)
    }
    return Model(
        name = info.fileName,
        url = "",
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = "$IMPORTS_DIR/${info.fileName}",
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = info.llmConfig.supportImage,
        llmSupportAudio = info.llmConfig.supportAudio,
        llmSupportTinyGarden = info.llmConfig.supportTinyGarden,
        llmSupportMobileActions = info.llmConfig.supportMobileActions,
        capabilities = capabilities,
        capabilityToTaskTypes = capabilityToTaskTypes,
        llmMaxToken = maxTokens,
        llmMaxContextLength = maxContextLength,
        accelerators = accelerators,
        isLlm = true,
        runtimeType = RuntimeType.LITERT_LM,
      )
      .also { model ->
        model.preProcess()
        model.configValues =
          normalizeContextWindowAndMaxTokens(
            values = model.configValues,
            defaultContextWindow = maxContextLength,
          )
      }
  }

  companion object {
    private const val PREFS_NAME = "ai_keyboard_pipeline_prefs"
    private const val KEY_SELECTED_PIPELINE_ID = "selected_pipeline_id"
    private const val KEY_SELECTED_TEXT_MODEL_NAME = "selected_text_model_name"
    private const val KEY_SELECTED_TEXT_MODEL_PATH = "selected_text_model_path"
    private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
    private const val TMP_FILE_EXT = "tmp"
  }
}
