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

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PROMPT_SECRET_KEY = "vision_narration_prompt"
private const val INTERVAL_SECRET_KEY = "vision_narration_interval_seconds"
private const val MAX_HISTORY_SIZE = 20
private val INTERVAL_OPTIONS = setOf(1, 2, 3, 5, 10)

enum class VisionCaptureMode {
  AUTO,
  MANUAL,
}

data class VisionNarrationEntry(
  val timestampMs: Long,
  val mode: VisionCaptureMode,
  val description: String,
)

data class VisionNarrationUiState(
  val prompt: String,
  val intervalSeconds: Int,
  val autoRunning: Boolean = false,
  val inProgress: Boolean = false,
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

  private val formatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(ZoneId.systemDefault())

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState = _uiState.asStateFlow()

  fun updatePrompt(prompt: String) {
    _uiState.update { it.copy(prompt = prompt) }
    dataStoreRepository.saveSecret(PROMPT_SECRET_KEY, prompt)
  }

  fun updateIntervalSeconds(seconds: Int) {
    val value = if (INTERVAL_OPTIONS.contains(seconds)) seconds else 2
    _uiState.update { it.copy(intervalSeconds = value) }
    dataStoreRepository.saveSecret(INTERVAL_SECRET_KEY, value.toString())
  }

  fun canCaptureFrame(): Boolean {
    if (pendingCaptureMode == null || uiState.value.inProgress) {
      return false
    }
    if (System.currentTimeMillis() < nextCaptureAllowedAtMs) {
      return false
    }
    return captureGate.compareAndSet(false, true)
  }

  fun requestSingleCapture() {
    if (uiState.value.inProgress) {
      return
    }
    scheduleCapture(mode = VisionCaptureMode.MANUAL, delayMs = 0L, keepAutoRunning = false)
  }

  fun startAutoNarration() {
    if (uiState.value.inProgress && uiState.value.autoRunning) {
      return
    }
    scheduleCapture(mode = VisionCaptureMode.AUTO, delayMs = 0L, keepAutoRunning = true)
  }

  fun stopNarration(model: Model?) {
    activeRequestId = requestIdGenerator.incrementAndGet()
    pendingCaptureMode = null
    nextCaptureAllowedAtMs = 0L
    captureGate.set(false)
    if (model != null && model.name.isNotEmpty()) {
      model.runtimeHelper.stopResponse(model)
    }
    _uiState.update {
      it.copy(
        autoRunning = false,
        inProgress = false,
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
    _uiState.update {
      it.copy(
        inProgress = true,
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

        var response = ""
        model.runtimeHelper.runInference(
          model = model,
          input = resolvePrompt(),
          images = listOf(bitmap),
          resultListener = { partialResult, done, _ ->
            if (activeRequestId == requestId) {
              response = processLlmResponse("$response$partialResult")
              _uiState.update {
                it.copy(
                  latestStreamingDescription = response,
                  statusText =
                    if (done) {
                      context.getString(R.string.vision_narration_status_ready)
                    } else {
                      context.getString(R.string.vision_narration_status_describing)
                    },
                )
              }
              if (done) {
                finalizeRequest(requestId = requestId, mode = mode, description = response)
              }
            }
          },
          cleanUpListener = {
            if (activeRequestId == requestId) {
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

  fun getDefaultPrompt(): String {
    return context.getString(R.string.vision_narration_default_prompt)
  }

  fun formatTimestamp(timestampMs: Long): String {
    return formatter.format(Instant.ofEpochMilli(timestampMs))
  }

  private fun resolvePrompt(): String {
    return uiState.value.prompt.ifBlank { getDefaultPrompt() }
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

  private fun finalizeRequest(requestId: Long, mode: VisionCaptureMode, description: String) {
    if (activeRequestId != requestId) {
      return
    }
    val now = System.currentTimeMillis()
    captureGate.set(false)
    val newEntry = VisionNarrationEntry(timestampMs = now, mode = mode, description = description)
    _uiState.update { current ->
      current.copy(
        inProgress = false,
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
    _uiState.update {
      it.copy(
        autoRunning = false,
        inProgress = false,
        latestStreamingDescription = "",
        statusText = message,
      )
    }
  }

  private fun createInitialState(): VisionNarrationUiState {
    val savedPrompt = dataStoreRepository.readSecret(PROMPT_SECRET_KEY).orEmpty()
    val savedInterval =
      dataStoreRepository.readSecret(INTERVAL_SECRET_KEY)?.toIntOrNull()?.takeIf {
        INTERVAL_OPTIONS.contains(it)
      } ?: 2
    return VisionNarrationUiState(
      prompt = savedPrompt,
      intervalSeconds = savedInterval,
      statusText = context.getString(R.string.vision_narration_status_ready),
    )
  }
}
