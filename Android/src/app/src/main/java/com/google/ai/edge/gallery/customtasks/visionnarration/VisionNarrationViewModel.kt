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

package com.google.ai.edge.gallery.customtasks.visionnarration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "VisionNarrationVM"
private const val LEGACY_PROMPT_SECRET_KEY = "vision_narration_prompt"
private const val PROMPTS_SECRET_KEY = "vision_narration_prompts_json"
private const val SELECTED_PROMPT_SECRET_KEY = "vision_narration_selected_prompt_id"
private const val INTERVAL_SECRET_KEY = "vision_narration_interval_seconds"
private const val AUTO_SPEAK_SECRET_KEY = "vision_narration_auto_speak_enabled"
private const val SPEECH_MODE_SECRET_KEY = "vision_narration_speech_mode"
private const val MAX_HISTORY_SIZE = 20
private const val STREAMING_SPEECH_FALLBACK_CHARS = 24
private val INTERVAL_OPTIONS = setOf(1, 2, 3, 5, 10)
private val STREAMING_SPEECH_BOUNDARIES = charArrayOf('。', '！', '？', '.', '!', '?', ';', '；', ':', '：', '\n')

private enum class SpeechEnqueueResult {
  QUEUED,
  WAITING_FOR_INITIALIZATION,
  FAILED,
}

enum class VisionCaptureMode {
  AUTO,
  MANUAL,
}

enum class VisionSpeechMode {
  AFTER_COMPLETE,
  DURING_GENERATION,
}

data class VisionPromptPreset(
  val id: String,
  val title: String,
  val prompt: String,
)

data class VisionNarrationEntry(
  val timestampMs: Long,
  val mode: VisionCaptureMode,
  val description: String,
)

data class VisionNarrationUiState(
  val promptPresets: List<VisionPromptPreset> = listOf(),
  val selectedPromptId: String? = null,
  val intervalSeconds: Int,
  val autoRunning: Boolean = false,
  val inProgress: Boolean = false,
  val isSpeaking: Boolean = false,
  val autoSpeakEnabled: Boolean = false,
  val speechMode: VisionSpeechMode = VisionSpeechMode.AFTER_COMPLETE,
  val statusText: String,
  val latestStreamingDescription: String = "",
  val latestCompletedDescription: String = "",
  val history: List<VisionNarrationEntry> = listOf(),
)

@HiltViewModel
class VisionNarrationViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  private val captureGate = AtomicBoolean(false)
  private val requestIdGenerator = AtomicLong(0L)
  private var activeRequestId: Long = 0L
  private var pendingCaptureMode: VisionCaptureMode? = null
  private var nextCaptureAllowedAtMs: Long = 0L
  private val gson = Gson()

  private var textToSpeech: TextToSpeech? = null
  private val ttsReady = AtomicBoolean(false)
  private val ttsInitializationInFlight = AtomicBoolean(false)
  private var activeSpeechSessionId: Long = 0L
  private var speechQueuedCount = 0
  private var pendingSpeechRequestId: Long? = null
  private var pendingSpeechMode: VisionCaptureMode? = null
  private var pendingSpeechDescription: String? = null
  private var pendingSpeechAwaitingInitialization = false
  private var pendingManualSpeechText: String? = null
  private var pendingManualSpeechAwaitingInitialization = false
  private var completedInferenceRequestId: Long? = null
  private var streamingSpeechConsumedLength = 0
  private var streamingSpeechRemainder = ""

  private val formatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState = _uiState.asStateFlow()

  init {
    initializeTextToSpeech()
  }

  fun updateIntervalSeconds(seconds: Int) {
    val value = if (INTERVAL_OPTIONS.contains(seconds)) seconds else 2
    _uiState.update { it.copy(intervalSeconds = value) }
    dataStoreRepository.saveSecret(INTERVAL_SECRET_KEY, value.toString())
  }

  fun updateAutoSpeakEnabled(enabled: Boolean) {
    _uiState.update { it.copy(autoSpeakEnabled = enabled) }
    dataStoreRepository.saveSecret(AUTO_SPEAK_SECRET_KEY, enabled.toString())
    if (enabled) {
      initializeTextToSpeech(forceRecreate = textToSpeech == null || !ttsReady.get())
      return
    }
    stopSpeechPlayback()
    finalizePendingSpeechIfNeeded()
  }

  fun updateSpeechMode(mode: VisionSpeechMode) {
    _uiState.update { it.copy(speechMode = mode) }
    dataStoreRepository.saveSecret(SPEECH_MODE_SECRET_KEY, mode.name)
  }

  fun addPromptPreset(title: String, prompt: String) {
    val cleanedTitle = title.trim()
    val cleanedPrompt = prompt.trim()
    if (cleanedTitle.isEmpty() || cleanedPrompt.isEmpty()) {
      return
    }
    val newPreset =
      VisionPromptPreset(id = UUID.randomUUID().toString(), title = cleanedTitle, prompt = cleanedPrompt)
    val newList = uiState.value.promptPresets + newPreset
    savePromptPresets(newList, selectedPromptId = newPreset.id)
  }

  fun updatePromptPreset(id: String, title: String, prompt: String) {
    val cleanedTitle = title.trim()
    val cleanedPrompt = prompt.trim()
    if (cleanedTitle.isEmpty() || cleanedPrompt.isEmpty()) {
      return
    }
    val newList =
      uiState.value.promptPresets.map { preset ->
        if (preset.id == id) {
          preset.copy(title = cleanedTitle, prompt = cleanedPrompt)
        } else {
          preset
        }
      }
    savePromptPresets(newList, selectedPromptId = uiState.value.selectedPromptId)
  }

  fun deletePromptPreset(id: String) {
    val newList = uiState.value.promptPresets.filterNot { it.id == id }
    val selectedId =
      when {
        uiState.value.selectedPromptId != id -> uiState.value.selectedPromptId
        newList.isNotEmpty() -> newList.first().id
        else -> null
      }
    savePromptPresets(newList, selectedPromptId = selectedId)
  }

  fun selectPromptPreset(id: String?) {
    val resolvedId = id?.takeIf { selectedId -> uiState.value.promptPresets.any { it.id == selectedId } }
    _uiState.update { it.copy(selectedPromptId = resolvedId) }
    if (resolvedId == null) {
      dataStoreRepository.deleteSecret(SELECTED_PROMPT_SECRET_KEY)
    } else {
      dataStoreRepository.saveSecret(SELECTED_PROMPT_SECRET_KEY, resolvedId)
    }
  }

  fun getSelectedPromptPreset(): VisionPromptPreset? {
    val selectedId = uiState.value.selectedPromptId
    return uiState.value.promptPresets.firstOrNull { it.id == selectedId }
  }

  fun getSelectedPromptDisplayName(): String {
    return getSelectedPromptPreset()?.title
      ?: context.getString(R.string.vision_narration_default_prompt_name)
  }

  fun getSelectedPromptText(): String {
    return getSelectedPromptPreset()?.prompt.orEmpty()
  }

  fun canCaptureFrame(): Boolean {
    if (pendingCaptureMode == null || uiState.value.inProgress || uiState.value.isSpeaking) {
      return false
    }
    if (System.currentTimeMillis() < nextCaptureAllowedAtMs) {
      return false
    }
    return captureGate.compareAndSet(false, true)
  }

  fun requestSingleCapture() {
    if (uiState.value.inProgress || uiState.value.isSpeaking) {
      return
    }
    scheduleCapture(mode = VisionCaptureMode.MANUAL, delayMs = 0L, keepAutoRunning = false)
  }

  fun startAutoNarration() {
    if ((uiState.value.inProgress || uiState.value.isSpeaking) && uiState.value.autoRunning) {
      return
    }
    scheduleCapture(mode = VisionCaptureMode.AUTO, delayMs = 0L, keepAutoRunning = true)
  }

  fun stopNarration(model: Model?) {
    activeRequestId = requestIdGenerator.incrementAndGet()
    pendingCaptureMode = null
    nextCaptureAllowedAtMs = 0L
    captureGate.set(false)
    clearCompletedInferenceRequest()
    clearPendingSpeechState()
    stopSpeechPlayback()
    if (model != null && model.name.isNotEmpty()) {
      model.runtimeHelper.stopResponse(model)
    }
    _uiState.update {
      it.copy(
        autoRunning = false,
        inProgress = false,
        isSpeaking = false,
        latestStreamingDescription = "",
        statusText = context.getString(R.string.vision_narration_status_stopped),
      )
    }
  }

  fun onCapturedFrame(model: Model, bitmap: Bitmap) {
    val mode = pendingCaptureMode ?: run {
      captureGate.set(false)
      return
    }
    pendingCaptureMode = null
    nextCaptureAllowedAtMs = 0L
    val requestId = requestIdGenerator.incrementAndGet()
    activeRequestId = requestId
    resetSpeechStateForRequest(requestId)
    _uiState.update {
      it.copy(
        inProgress = true,
        isSpeaking = false,
        statusText = context.getString(R.string.vision_narration_status_describing),
        latestStreamingDescription = "",
      )
    }

    viewModelScope.launch(Dispatchers.Default) {
      try {
        if (model.name.isEmpty()) {
          setErrorState(requestId, context.getString(R.string.vision_narration_status_no_model))
          return@launch
        }

        var waitedMs = 0L
        while (model.instance == null && waitedMs < 30_000L) {
          delay(100L)
          waitedMs += 100L
        }
        if (activeRequestId != requestId) {
          return@launch
        }
        if (model.instance == null) {
          setErrorState(requestId, context.getString(R.string.vision_narration_status_no_model))
          return@launch
        }

        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = true,
          supportAudio = false,
        )
        delay(200L)

        var rawResponse = ""
        model.runtimeHelper.runInference(
          model = model,
          input = resolvePrompt(),
          images = listOf(bitmap),
          resultListener = { partialResult, done, _ ->
            if (activeRequestId == requestId) {
              rawResponse += partialResult
              val cleanedResponse = sanitizeNarrationText(processLlmResponse(rawResponse))
              _uiState.update {
                it.copy(
                  latestStreamingDescription = cleanedResponse,
                  statusText =
                    if (done) {
                      context.getString(R.string.vision_narration_status_ready)
                    } else {
                      context.getString(R.string.vision_narration_status_describing)
                  },
                )
              }

              if (done) {
                completedInferenceRequestId = requestId
              }
              if (uiState.value.autoSpeakEnabled) {
                handleSpeechUpdate(
                  requestId = requestId,
                  mode = mode,
                  cleanedResponse = cleanedResponse,
                  done = done,
                )
              } else if (done) {
                finalizeRequest(requestId = requestId, mode = mode, description = cleanedResponse)
              }
            }
          },
          cleanUpListener = {
            if (
              activeRequestId == requestId &&
                completedInferenceRequestId != requestId &&
                (_uiState.value.inProgress || _uiState.value.isSpeaking)
            ) {
              finalizeStoppedRequest(requestId)
            }
          },
          onError = { message ->
            if (activeRequestId == requestId) {
              setErrorState(
                requestId,
                context.getString(R.string.vision_narration_error_prefix, message),
              )
            }
          },
          coroutineScope = viewModelScope,
        )
      } finally {
        bitmap.recycle()
      }
    }
  }

  fun exportHistory() {
    val history = uiState.value.history
    if (history.isEmpty()) {
      return
    }
    try {
      val fileName = "vision_narration_${System.currentTimeMillis()}.txt"
      val file = File(context.cacheDir, fileName)
      file.writeText(buildHistoryExportText(), Charsets.UTF_8)
      val uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
      val chooserIntent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_STREAM, uri)
              putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.vision_narration_export_subject))
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            context.getString(R.string.vision_narration_export_chooser),
          )
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooserIntent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to export narration history", e)
      _uiState.update {
        it.copy(
          statusText = context.getString(R.string.vision_narration_export_error),
        )
      }
    }
  }

  fun getDefaultPrompt(): String {
    return context.getString(R.string.vision_narration_default_prompt)
  }

  fun copyDescription(text: String) {
    val description = text.trim()
    if (description.isBlank()) {
      return
    }
    val clipboardManager = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboardManager.setPrimaryClip(
      ClipData.newPlainText(context.getString(R.string.vision_narration_export_subject), description)
    )
  }

  fun readDescriptionAloud(text: String) {
    val description = sanitizeNarrationText(text)
    if (description.isBlank()) {
      return
    }
    pendingManualSpeechText = description
    pendingManualSpeechAwaitingInitialization = false
    stopSpeechPlayback()
    activeSpeechSessionId = requestIdGenerator.incrementAndGet()
    when (enqueueSpeech(description, flush = true)) {
      SpeechEnqueueResult.QUEUED -> {
        pendingManualSpeechText = null
        pendingManualSpeechAwaitingInitialization = false
      }
      SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
        pendingManualSpeechAwaitingInitialization = true
      }
      SpeechEnqueueResult.FAILED -> {
        pendingManualSpeechText = null
        pendingManualSpeechAwaitingInitialization = false
        reportTtsUnavailable()
      }
    }
  }

  fun formatTimestamp(timestampMs: Long): String {
    return formatter.format(Instant.ofEpochMilli(timestampMs))
  }

  override fun onCleared() {
    super.onCleared()
    stopSpeechPlayback()
    textToSpeech?.shutdown()
    textToSpeech = null
  }

  private fun initializeTextToSpeech(forceRecreate: Boolean = false) {
    if (forceRecreate) {
      textToSpeech?.stop()
      textToSpeech?.shutdown()
      textToSpeech = null
      ttsReady.set(false)
    } else if (textToSpeech != null && (ttsReady.get() || ttsInitializationInFlight.get())) {
      return
    }
    if (!ttsInitializationInFlight.compareAndSet(false, true)) {
      return
    }
    val defaultEngine =
      Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val listener: (Int) -> Unit = { status ->
        ttsInitializationInFlight.set(false)
        if (status == TextToSpeech.SUCCESS) {
          val tts = textToSpeech
          val languageConfigured = configurePreferredTtsLanguage(tts)
          ttsReady.set(tts != null)
          textToSpeech?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {
                val sessionId = utteranceId?.substringBefore(':')?.toLongOrNull() ?: return
                if (sessionId != activeSpeechSessionId) {
                  return
                }
                viewModelScope.launch {
                  _uiState.update {
                    it.copy(
                      isSpeaking = true,
                      statusText = context.getString(R.string.vision_narration_status_speaking),
                    )
                  }
                }
              }

              override fun onDone(utteranceId: String?) {
                handleSpeechUtteranceFinished(utteranceId)
              }

              @Deprecated("Deprecated in Java")
              override fun onError(utteranceId: String?) {
                handleSpeechUtteranceFinished(utteranceId)
              }

              override fun onError(utteranceId: String?, errorCode: Int) {
                handleSpeechUtteranceFinished(utteranceId)
              }
            }
          )
          Log.i(
            TAG,
            "TextToSpeech initialized. engine=$defaultEngine languageConfigured=$languageConfigured locale=${Locale.getDefault()}",
          )
          if (uiState.value.autoSpeakEnabled) {
            _uiState.update {
              it.copy(
                statusText =
                  when {
                    it.isSpeaking -> context.getString(R.string.vision_narration_status_speaking)
                    it.inProgress -> context.getString(R.string.vision_narration_status_describing)
                    else -> context.getString(R.string.vision_narration_status_ready)
                  },
                )
            }
          }
          maybeSpeakPendingTextAfterInitialization()
        } else {
          ttsReady.set(false)
          Log.w(TAG, "TextToSpeech initialization failed with status=$status engine=$defaultEngine")
          handlePendingSpeechInitializationFailure()
        }
      }
    try {
      textToSpeech =
        if (defaultEngine != null) {
          TextToSpeech(context, listener, defaultEngine)
        } else {
          TextToSpeech(context, listener)
        }
    } catch (e: Exception) {
      ttsInitializationInFlight.set(false)
      ttsReady.set(false)
      Log.e(TAG, "Failed to create TextToSpeech instance", e)
      handlePendingSpeechInitializationFailure()
    }
  }

  private fun configurePreferredTtsLanguage(tts: TextToSpeech?): Boolean {
    if (tts == null) {
      return false
    }
    val localeCandidates =
      linkedSetOf(
        Locale.getDefault(),
        Locale.SIMPLIFIED_CHINESE,
        Locale.CHINESE,
        Locale.US,
      )
    localeCandidates.forEach { locale ->
      runCatching {
          val status = tts.setLanguage(locale)
          Log.d(TAG, "TextToSpeech setLanguage($locale) -> $status")
          if (status != TextToSpeech.LANG_MISSING_DATA &&
            status != TextToSpeech.LANG_NOT_SUPPORTED
          ) {
            return true
          }
        }
        .onFailure { error ->
          Log.w(TAG, "TextToSpeech setLanguage($locale) failed", error)
        }
    }
    return false
  }

  private fun handleSpeechUtteranceFinished(utteranceId: String?) {
    val sessionId = utteranceId?.substringBefore(':')?.toLongOrNull() ?: return
    if (sessionId != activeSpeechSessionId) {
      return
    }
    speechQueuedCount = (speechQueuedCount - 1).coerceAtLeast(0)
    if (speechQueuedCount == 0) {
      viewModelScope.launch {
        _uiState.update { it.copy(isSpeaking = false) }
      }
      finalizePendingSpeechIfNeeded()
    }
  }

  private fun resolvePrompt(): String {
    return getSelectedPromptPreset()?.prompt?.ifBlank { null } ?: getDefaultPrompt()
  }

  private fun savePromptPresets(presets: List<VisionPromptPreset>, selectedPromptId: String?) {
    val encoded = gson.toJson(presets)
    dataStoreRepository.saveSecret(PROMPTS_SECRET_KEY, encoded)
    _uiState.update { it.copy(promptPresets = presets) }
    selectPromptPreset(selectedPromptId)
  }

  private fun scheduleCapture(
    mode: VisionCaptureMode,
    delayMs: Long,
    keepAutoRunning: Boolean,
  ) {
    pendingCaptureMode = mode
    nextCaptureAllowedAtMs = System.currentTimeMillis() + delayMs
    captureGate.set(false)
    _uiState.update {
      it.copy(
        autoRunning = keepAutoRunning,
        inProgress = false,
        latestStreamingDescription = "",
        statusText =
          if (delayMs > 0L) {
            context.getString(
              R.string.vision_narration_status_countdown,
              (delayMs / 1000L).toInt(),
            )
          } else {
            context.getString(R.string.vision_narration_status_waiting_for_frame)
          },
      )
    }
  }

  private fun handleSpeechUpdate(
    requestId: Long,
    mode: VisionCaptureMode,
    cleanedResponse: String,
    done: Boolean,
  ) {
    when (uiState.value.speechMode) {
      VisionSpeechMode.AFTER_COMPLETE -> {
        if (done) {
          pendingSpeechRequestId = requestId
          pendingSpeechMode = mode
          pendingSpeechDescription = cleanedResponse
          when (enqueueSpeech(cleanedResponse, flush = true)) {
            SpeechEnqueueResult.QUEUED -> {
              pendingSpeechAwaitingInitialization = false
            }
            SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
              pendingSpeechAwaitingInitialization = true
            }
            SpeechEnqueueResult.FAILED -> {
              pendingSpeechAwaitingInitialization = false
              reportTtsUnavailable()
              finalizePendingSpeechIfNeeded()
            }
          }
        }
      }
      VisionSpeechMode.DURING_GENERATION -> {
        val appendedText =
          cleanedResponse.substring(
            startIndex = streamingSpeechConsumedLength.coerceAtMost(cleanedResponse.length),
          )
        streamingSpeechConsumedLength = cleanedResponse.length
        if (appendedText.isNotEmpty()) {
          streamingSpeechRemainder += appendedText
          val splitIndex = resolveStreamingSpeakableSplitIndex(streamingSpeechRemainder)
          if (splitIndex > 0) {
            val speakable = streamingSpeechRemainder.substring(0, splitIndex)
            when (enqueueSpeech(speakable, flush = false)) {
              SpeechEnqueueResult.QUEUED -> {
                streamingSpeechRemainder = streamingSpeechRemainder.substring(splitIndex)
              }
              SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
                pendingSpeechAwaitingInitialization = true
              }
              SpeechEnqueueResult.FAILED -> {
                reportTtsUnavailable()
              }
            }
          }
        }
        if (done) {
          pendingSpeechRequestId = requestId
          pendingSpeechMode = mode
          pendingSpeechDescription = cleanedResponse
          if (streamingSpeechRemainder.isNotBlank()) {
            when (enqueueSpeech(streamingSpeechRemainder, flush = false)) {
              SpeechEnqueueResult.QUEUED -> {
                streamingSpeechRemainder = ""
                pendingSpeechAwaitingInitialization = false
              }
              SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
                pendingSpeechAwaitingInitialization = true
              }
              SpeechEnqueueResult.FAILED -> {
                reportTtsUnavailable()
              }
            }
          }
          finalizePendingSpeechIfNeeded()
        }
      }
    }
  }

  private fun enqueueSpeech(text: String, flush: Boolean): SpeechEnqueueResult {
    val normalized = text.trim()
    if (normalized.isEmpty()) {
      return SpeechEnqueueResult.FAILED
    }
    val tts = textToSpeech
    if (tts == null || !ttsReady.get()) {
      initializeTextToSpeech(forceRecreate = tts == null)
      return SpeechEnqueueResult.WAITING_FOR_INITIALIZATION
    }
    val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
    if (flush) {
      speechQueuedCount = 0
    }
    val utteranceId = "${activeSpeechSessionId}:${speechQueuedCount + 1}"
    val result = tts.speak(normalized, queueMode, Bundle(), utteranceId)
    if (result == TextToSpeech.SUCCESS) {
      speechQueuedCount += 1
      _uiState.update {
        it.copy(
          isSpeaking = true,
          statusText = context.getString(R.string.vision_narration_status_speaking),
        )
      }
      return SpeechEnqueueResult.QUEUED
    }
    Log.w(TAG, "TextToSpeech speak returned non-success result=$result")
    initializeTextToSpeech(forceRecreate = true)
    return SpeechEnqueueResult.WAITING_FOR_INITIALIZATION
  }

  private fun maybeSpeakPendingTextAfterInitialization() {
    if (!ttsReady.get()) {
      return
    }
    if (speechQueuedCount > 0 || uiState.value.isSpeaking) {
      return
    }
    val manualText = pendingManualSpeechText
    if (manualText != null) {
      when (enqueueSpeech(manualText, flush = true)) {
        SpeechEnqueueResult.QUEUED -> {
          pendingManualSpeechText = null
          pendingManualSpeechAwaitingInitialization = false
        }
        SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
          pendingManualSpeechAwaitingInitialization = true
          return
        }
        SpeechEnqueueResult.FAILED -> {
          pendingManualSpeechText = null
          pendingManualSpeechAwaitingInitialization = false
          reportTtsUnavailable()
        }
      }
    }
    if (!uiState.value.autoSpeakEnabled) {
      return
    }
    val pendingDescription = pendingSpeechDescription ?: return
    when (enqueueSpeech(pendingDescription, flush = true)) {
      SpeechEnqueueResult.QUEUED -> {
        pendingSpeechAwaitingInitialization = false
      }
      SpeechEnqueueResult.WAITING_FOR_INITIALIZATION -> {
        pendingSpeechAwaitingInitialization = true
      }
      SpeechEnqueueResult.FAILED -> {
        pendingSpeechAwaitingInitialization = false
        reportTtsUnavailable()
        finalizePendingSpeechIfNeeded()
      }
    }
  }

  private fun finalizePendingSpeechIfNeeded() {
    val requestId = pendingSpeechRequestId ?: return
    val mode = pendingSpeechMode ?: return
    val description = pendingSpeechDescription ?: return
    if (pendingSpeechAwaitingInitialization || ttsInitializationInFlight.get()) {
      return
    }
    if (speechQueuedCount > 0 || uiState.value.isSpeaking) {
      return
    }
    clearPendingSpeechState()
    finalizeRequest(requestId = requestId, mode = mode, description = description)
  }

  private fun clearPendingSpeechState() {
    pendingSpeechRequestId = null
    pendingSpeechMode = null
    pendingSpeechDescription = null
    pendingSpeechAwaitingInitialization = false
    streamingSpeechConsumedLength = 0
    streamingSpeechRemainder = ""
  }

  private fun resetSpeechStateForRequest(requestId: Long) {
    stopSpeechPlayback()
    activeSpeechSessionId = requestId
    speechQueuedCount = 0
    clearCompletedInferenceRequest()
    clearPendingSpeechState()
  }

  private fun stopSpeechPlayback() {
    activeSpeechSessionId += 1
    speechQueuedCount = 0
    textToSpeech?.stop()
    _uiState.update { it.copy(isSpeaking = false) }
  }

  private fun handlePendingSpeechInitializationFailure() {
    pendingManualSpeechText = null
    pendingManualSpeechAwaitingInitialization = false
    reportTtsUnavailable()
    finalizePendingSpeechIfNeeded()
  }

  private fun findLastSpeakableBoundary(text: String): Int {
    for (index in text.length - 1 downTo 0) {
      if (STREAMING_SPEECH_BOUNDARIES.contains(text[index])) {
        return index + 1
      }
    }
    return 0
  }

  private fun resolveStreamingSpeakableSplitIndex(text: String): Int {
    val boundaryIndex = findLastSpeakableBoundary(text)
    if (boundaryIndex > 0) {
      return boundaryIndex
    }
    return if (text.length >= STREAMING_SPEECH_FALLBACK_CHARS) {
      STREAMING_SPEECH_FALLBACK_CHARS
    } else {
      0
    }
  }

  private fun finalizeRequest(requestId: Long, mode: VisionCaptureMode, description: String) {
    if (activeRequestId != requestId) {
      return
    }
    val now = System.currentTimeMillis()
    captureGate.set(false)
    clearCompletedInferenceRequest()
    val newEntry = VisionNarrationEntry(timestampMs = now, mode = mode, description = description)
    _uiState.update { current ->
      current.copy(
        inProgress = false,
        isSpeaking = false,
        latestStreamingDescription = "",
        latestCompletedDescription = description,
        history = listOf(newEntry) + current.history.take(MAX_HISTORY_SIZE - 1),
        statusText = context.getString(R.string.vision_narration_status_ready),
      )
    }
    if (uiState.value.autoRunning) {
      scheduleCapture(
        mode = VisionCaptureMode.AUTO,
        delayMs = uiState.value.intervalSeconds * 1000L,
        keepAutoRunning = true,
      )
    }
  }

  private fun finalizeStoppedRequest(requestId: Long) {
    if (activeRequestId != requestId) {
      return
    }
    captureGate.set(false)
    clearCompletedInferenceRequest()
    stopSpeechPlayback()
    clearPendingSpeechState()
    _uiState.update {
      it.copy(
        inProgress = false,
        latestStreamingDescription = "",
        statusText =
          if (it.autoRunning) {
            context.getString(R.string.vision_narration_status_waiting_for_frame)
          } else {
            context.getString(R.string.vision_narration_status_stopped)
          },
      )
    }
  }

  private fun setErrorState(requestId: Long, message: String) {
    if (activeRequestId != requestId) {
      return
    }
    captureGate.set(false)
    clearCompletedInferenceRequest()
    stopSpeechPlayback()
    clearPendingSpeechState()
    _uiState.update {
      it.copy(
        autoRunning = false,
        inProgress = false,
        latestStreamingDescription = "",
        statusText = message,
      )
    }
  }

  private fun reportTtsUnavailable() {
    ttsReady.set(false)
    _uiState.update {
      it.copy(
        isSpeaking = false,
        statusText = context.getString(R.string.vision_narration_status_tts_unavailable),
      )
    }
  }

  private fun clearCompletedInferenceRequest() {
    completedInferenceRequestId = null
  }

  private fun buildHistoryExportText(): String {
    val generatedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(System.currentTimeMillis()))
    val header =
      buildString {
        append(context.getString(R.string.vision_narration_export_title))
        append('\n')
        append(context.getString(R.string.vision_narration_export_generated_at, generatedAt))
        append('\n')
        append(context.getString(R.string.vision_narration_export_model_prompt, getSelectedPromptDisplayName()))
        append("\n\n")
      }
    val body =
      uiState.value.history.joinToString(separator = "\n\n") { entry ->
        val modeText =
          if (entry.mode == VisionCaptureMode.AUTO) {
            context.getString(R.string.vision_narration_capture_mode_auto)
          } else {
            context.getString(R.string.vision_narration_capture_mode_manual)
          }
        buildString {
          append(context.getString(R.string.vision_narration_export_record_time, formatTimestamp(entry.timestampMs)))
          append('\n')
          append(context.getString(R.string.vision_narration_export_record_mode, modeText))
          append('\n')
          append(entry.description)
        }
      }
    return header + body
  }

  private fun sanitizeNarrationText(text: String): String {
    return text
      .replace("**", "")
      .replace("__", "")
      .replace("`", "")
      .replace(Regex("(?m)^\\s*#+\\s*"), "")
      .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex("\\n{3,}"), "\n\n")
      .replace(Regex("[ ]*\\n[ ]*"), "\n")
      .trim()
  }

  private fun createInitialState(): VisionNarrationUiState {
    val migratedData = loadPromptPresetsWithMigration()
    val savedInterval =
      dataStoreRepository.readSecret(INTERVAL_SECRET_KEY)?.toIntOrNull()?.takeIf {
        INTERVAL_OPTIONS.contains(it)
      } ?: 2
    val autoSpeakEnabled =
      dataStoreRepository.readSecret(AUTO_SPEAK_SECRET_KEY)?.toBooleanStrictOrNull() ?: false
    val speechMode =
      dataStoreRepository.readSecret(SPEECH_MODE_SECRET_KEY)
        ?.let { saved -> VisionSpeechMode.entries.firstOrNull { it.name == saved } }
        ?: VisionSpeechMode.AFTER_COMPLETE
    return VisionNarrationUiState(
      promptPresets = migratedData.first,
      selectedPromptId = migratedData.second,
      intervalSeconds = savedInterval,
      autoSpeakEnabled = autoSpeakEnabled,
      speechMode = speechMode,
      statusText = context.getString(R.string.vision_narration_status_ready),
    )
  }

  private fun loadPromptPresetsWithMigration(): Pair<List<VisionPromptPreset>, String?> {
    val storedJson = dataStoreRepository.readSecret(PROMPTS_SECRET_KEY)
    val presets =
      if (!storedJson.isNullOrBlank()) {
        parsePromptPresets(storedJson)
      } else {
        listOf()
      }
    if (presets.isNotEmpty()) {
      val selectedId =
        dataStoreRepository.readSecret(SELECTED_PROMPT_SECRET_KEY)?.takeIf { selectedId ->
          presets.any { it.id == selectedId }
        }
      return presets to selectedId
    }

    val legacyPrompt = dataStoreRepository.readSecret(LEGACY_PROMPT_SECRET_KEY).orEmpty().trim()
    if (legacyPrompt.isNotEmpty()) {
      val migratedPreset =
        VisionPromptPreset(
          id = UUID.randomUUID().toString(),
          title = context.getString(R.string.vision_narration_migrated_prompt_name),
          prompt = legacyPrompt,
        )
      val migratedList = listOf(migratedPreset)
      dataStoreRepository.saveSecret(PROMPTS_SECRET_KEY, gson.toJson(migratedList))
      dataStoreRepository.saveSecret(SELECTED_PROMPT_SECRET_KEY, migratedPreset.id)
      dataStoreRepository.deleteSecret(LEGACY_PROMPT_SECRET_KEY)
      return migratedList to migratedPreset.id
    }
    return listOf<VisionPromptPreset>() to null
  }

  private fun parsePromptPresets(encoded: String): List<VisionPromptPreset> {
    return try {
      val type = object : TypeToken<List<VisionPromptPreset>>() {}.type
      gson.fromJson<List<VisionPromptPreset>>(encoded, type)
        ?.filter { it.title.isNotBlank() && it.prompt.isNotBlank() }
        ?: listOf()
    } catch (_: Exception) {
      listOf()
    }
  }
}
