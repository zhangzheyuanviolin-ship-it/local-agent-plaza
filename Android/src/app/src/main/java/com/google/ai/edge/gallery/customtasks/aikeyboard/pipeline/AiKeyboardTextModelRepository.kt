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
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class AiKeyboardTextModelCandidate(
  val model: Model,
  val path: String,
) {
  val displayName: String
    get() = model.displayName.ifBlank { model.name }
}

data class AiKeyboardPipelineLogEntry(
  val id: String,
  val createdAtMillis: Long,
  val presetId: String,
  val presetName: String,
  val modelName: String,
  val modelPath: String,
  val inputText: String,
  val promptText: String,
  val rawOutputText: String,
  val outputText: String,
  val committedText: String,
  val inputLength: Int,
  val rawOutputLength: Int,
  val outputLength: Int,
  val committedLength: Int,
  val maxTokens: Int,
  val contextWindow: Int,
  val status: String,
  val errorText: String = "",
  val firstTokenLatencyMs: Long = 0L,
  val inferenceDurationMs: Long = 0L,
  val commitDurationMs: Long = 0L,
  val totalDurationMs: Long = 0L,
  val outputCharsPerSecond: Float = 0f,
)

class AiKeyboardTextModelRepository(
  private val context: Context,
  private val dataStoreRepository: DataStoreRepository? =
    (context.applicationContext as? GalleryApplication)?.dataStoreRepository,
) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val gson = Gson()

  fun listPipelinePresets(): List<AiKeyboardPipelinePreset> {
    val instructionOverrides = getInstructionOverrides()
    val builtIns =
      AiKeyboardPipelineCatalog.presets.map { preset ->
        instructionOverrides[preset.id]?.let { override ->
          preset.copy(instruction = override)
        } ?: preset
      }
    return builtIns + getCustomPipelines()
  }

  fun getPipelinePreset(id: String): AiKeyboardPipelinePreset? {
    return listPipelinePresets().firstOrNull { it.id == id }
  }

  fun getSelectedPipelineId(): String {
    val saved = prefs.getString(KEY_SELECTED_PIPELINE_ID, null)
    return saved?.takeIf { getPipelinePreset(it) != null }
      ?: AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID
  }

  fun setSelectedPipelineId(id: String) {
    val resolved = getPipelinePreset(id)?.id ?: AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID
    prefs.edit().putString(KEY_SELECTED_PIPELINE_ID, resolved).apply()
  }

  fun selectNextPipeline(): AiKeyboardPipelinePreset {
    val presets = listPipelinePresets()
    val index = presets.indexOfFirst { it.id == getSelectedPipelineId() }
    val next =
      if (index < 0) {
        presets.firstOrNull { it.id == AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID }
          ?: AiKeyboardPipelineCatalog.defaultPreset()
      } else {
        presets[(index + 1) % presets.size]
      }
    setSelectedPipelineId(next.id)
    return next
  }

  fun buildPrompt(presetId: String, input: String): String {
    val preset = getPipelinePreset(presetId) ?: AiKeyboardPipelineCatalog.defaultPreset()
    return AiKeyboardPipelineCatalog.buildPrompt(
      presetId = preset.id,
      input = input,
      presetOverride = preset,
      translationTargetLanguage = getTranslationTargetLanguage(),
    )
  }

  fun getTranslationTargetLanguage(): String {
    return prefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE, null)
      ?.takeIf { it.isNotBlank() }
      ?: DEFAULT_TRANSLATION_TARGET_LANGUAGE
  }

  fun setTranslationTargetLanguage(language: String) {
    val normalized = language.trim().ifBlank { DEFAULT_TRANSLATION_TARGET_LANGUAGE }
    prefs.edit().putString(KEY_TRANSLATION_TARGET_LANGUAGE, normalized).apply()
  }

  fun savePipelineInstruction(id: String, instruction: String) {
    val normalized = instruction.trim()
    if (normalized.isBlank()) return
    val builtIn = AiKeyboardPipelineCatalog.byId(id)
    if (builtIn != null) {
      val overrides = getInstructionOverrides().toMutableMap()
      overrides[id] = normalized
      saveInstructionOverrides(overrides)
      return
    }

    val custom = getCustomPipelines().toMutableList()
    val index = custom.indexOfFirst { it.id == id }
    if (index >= 0) {
      custom[index] = custom[index].copy(instruction = normalized)
      saveCustomPipelines(custom)
    }
  }

  fun resetPipelineInstruction(id: String) {
    val overrides = getInstructionOverrides().toMutableMap()
    if (overrides.remove(id) != null) {
      saveInstructionOverrides(overrides)
    }
  }

  fun addCustomPipeline(
    displayName: String,
    keyboardLabel: String,
    instruction: String,
  ): AiKeyboardPipelinePreset {
    val preset =
      AiKeyboardPipelinePreset(
        id = "custom_${UUID.randomUUID()}",
        displayName = displayName.trim().ifBlank { "自定义流水线" },
        keyboardLabel = keyboardLabel.trim().ifBlank { "自定" }.take(4),
        instruction = instruction.trim().ifBlank { AiKeyboardPipelineCatalog.defaultPreset().instruction },
        builtIn = false,
      )
    saveCustomPipelines(getCustomPipelines() + preset)
    return preset
  }

  fun deleteCustomPipeline(id: String): Boolean {
    if (AiKeyboardPipelineCatalog.byId(id) != null) return false
    val custom = getCustomPipelines()
    val updated = custom.filterNot { it.id == id }
    if (updated.size == custom.size) return false
    saveCustomPipelines(updated)
    if (getSelectedPipelineId() == id) {
      setSelectedPipelineId(AiKeyboardPipelineCatalog.DEFAULT_PRESET_ID)
    }
    return true
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

  fun setSelectedModelPath(path: String): AiKeyboardTextModelCandidate? {
    val selected = listAvailableModels().firstOrNull { it.path == path } ?: return null
    persistSelectedModel(selected)
    return selected
  }

  fun appendPipelineLog(entry: AiKeyboardPipelineLogEntry) {
    val file = pipelineLogFile()
    file.parentFile?.mkdirs()
    file.appendText("${gson.toJson(entry)}\n")
  }

  fun listPipelineLogs(): List<AiKeyboardPipelineLogEntry> {
    val file = pipelineLogFile()
    if (!file.exists()) return emptyList()
    return file
      .readLines()
      .mapNotNull { line ->
        runCatching { gson.fromJson(line, AiKeyboardPipelineLogEntry::class.java) }.getOrNull()
      }
      .sortedByDescending { it.createdAtMillis }
  }

  fun deletePipelineLog(id: String): Boolean {
    val logs = listPipelineLogs()
    val updated = logs.filterNot { it.id == id }
    if (updated.size == logs.size) return false
    rewritePipelineLogs(updated.sortedBy { it.createdAtMillis })
    return true
  }

  fun clearPipelineLogs() {
    val file = pipelineLogFile()
    if (file.exists()) {
      file.delete()
    }
  }

  private fun persistSelectedModel(candidate: AiKeyboardTextModelCandidate) {
    prefs
      .edit()
      .putString(KEY_SELECTED_TEXT_MODEL_NAME, candidate.model.name)
      .putString(KEY_SELECTED_TEXT_MODEL_PATH, candidate.path)
      .apply()
  }

  private fun pipelineLogFile(): File {
    return File(context.filesDir, PIPELINE_LOG_FILENAME)
  }

  private fun rewritePipelineLogs(entries: List<AiKeyboardPipelineLogEntry>) {
    val file = pipelineLogFile()
    file.parentFile?.mkdirs()
    file.writeText(entries.joinToString(separator = "\n") { gson.toJson(it) }.let {
      if (it.isBlank()) "" else "$it\n"
    })
  }

  private fun getInstructionOverrides(): Map<String, String> {
    val json = prefs.getString(KEY_PIPELINE_INSTRUCTION_OVERRIDES, null) ?: return emptyMap()
    val type = object : TypeToken<Map<String, String>>() {}.type
    return runCatching { gson.fromJson<Map<String, String>>(json, type) }.getOrNull().orEmpty()
  }

  private fun saveInstructionOverrides(overrides: Map<String, String>) {
    prefs.edit().putString(KEY_PIPELINE_INSTRUCTION_OVERRIDES, gson.toJson(overrides)).apply()
  }

  private fun getCustomPipelines(): List<AiKeyboardPipelinePreset> {
    val json = prefs.getString(KEY_CUSTOM_PIPELINES, null) ?: return emptyList()
    val type = object : TypeToken<List<AiKeyboardPipelinePreset>>() {}.type
    return runCatching { gson.fromJson<List<AiKeyboardPipelinePreset>>(json, type) }
      .getOrNull()
      .orEmpty()
      .map { it.copy(builtIn = false) }
      .filter { it.id.isNotBlank() && it.displayName.isNotBlank() && it.instruction.isNotBlank() }
  }

  private fun saveCustomPipelines(presets: List<AiKeyboardPipelinePreset>) {
    prefs.edit().putString(KEY_CUSTOM_PIPELINES, gson.toJson(presets.map { it.copy(builtIn = false) })).apply()
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
    private const val KEY_TRANSLATION_TARGET_LANGUAGE = "translation_target_language"
    private const val KEY_PIPELINE_INSTRUCTION_OVERRIDES = "pipeline_instruction_overrides"
    private const val KEY_CUSTOM_PIPELINES = "custom_pipelines"
    private const val DEFAULT_TRANSLATION_TARGET_LANGUAGE = "英文"
    private const val PIPELINE_LOG_FILENAME = "ai_keyboard_pipeline_logs.jsonl"
    private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
    private const val TMP_FILE_EXT = "tmp"
  }
}
