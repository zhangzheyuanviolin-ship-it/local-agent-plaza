package com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AiKeyboardPipelineService : Service() {
  private val repository by lazy { AiKeyboardTextModelRepository(this) }
  private val executor = Executors.newSingleThreadExecutor()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val running = AtomicBoolean(false)
  private val messenger = Messenger(IncomingHandler())
  private val idleUnloadRunnable =
    Runnable {
      if (!running.get()) {
        executor.execute {
          unloadActiveModel()
          System.gc()
        }
      }
    }

  @Volatile private var replyTo: Messenger? = null
  @Volatile private var activeModel: Model? = null
  @Volatile private var activeModelPath: String = ""

  override fun onBind(intent: Intent?): IBinder {
    return messenger.binder
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_RUNNING_LOW && !running.get()) {
      executor.execute { unloadActiveModel() }
    }
  }

  override fun onDestroy() {
    mainHandler.removeCallbacks(idleUnloadRunnable)
    executor.execute { unloadActiveModel() }
    executor.shutdown()
    super.onDestroy()
  }

  private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        MSG_RUN -> handleRun(msg)
        MSG_CANCEL -> handleCancel()
        else -> super.handleMessage(msg)
      }
    }
  }

  private fun handleRun(msg: Message) {
    if (!running.compareAndSet(false, true)) {
      sendError(msg.data.getString(KEY_REQUEST_ID).orEmpty(), "当前已有文本处理正在进行。")
      return
    }
    mainHandler.removeCallbacks(idleUnloadRunnable)

    replyTo = msg.replyTo
    val requestId = msg.data.getString(KEY_REQUEST_ID).orEmpty()
    val input = msg.data.getString(KEY_INPUT_TEXT).orEmpty()
    val presetId = msg.data.getString(KEY_PRESET_ID).orEmpty()
    val preset = repository.getPipelinePreset(presetId) ?: AiKeyboardPipelineCatalog.defaultPreset()
    val prompt = repository.buildPrompt(presetId = presetId, input = input)
    if (prompt.isBlank()) {
      running.set(false)
      scheduleIdleUnload()
      sendError(requestId, "当前文本框没有可处理文字。")
      return
    }

    val candidate = repository.getSelectedModel()
    if (candidate == null) {
      running.set(false)
      scheduleIdleUnload()
      sendError(requestId, "未找到已下载或已导入的本地文本模型。")
      return
    }

    executor.execute {
      ensureModelReady(
        candidate = candidate,
        requestId = requestId,
        onReady = { model ->
          runInference(
            requestId = requestId,
            model = model,
            modelPath = candidate.path,
            preset = preset,
            prompt = prompt,
          )
        },
      )
    }
  }

  private fun ensureModelReady(
    candidate: AiKeyboardTextModelCandidate,
    requestId: String,
    onReady: (Model) -> Unit,
  ) {
    val current = activeModel
    if (current != null && activeModelPath == candidate.path && current.instance != null) {
      onReady(current)
      return
    }

    unloadActiveModel()
    System.gc()
    runCatching { Thread.sleep(MODEL_RELOAD_SETTLE_MS) }
    val model = candidate.model
    LlmChatModelHelper.initialize(
      context = this,
      model = model,
      taskId = BuiltInTaskId.LLM_CHAT,
      supportImage = false,
      supportAudio = false,
      onDone = { error ->
        if (error.isNotBlank()) {
          running.set(false)
          scheduleIdleUnload()
          sendError(requestId, error)
          return@initialize
        }
        activeModel = model
        activeModelPath = candidate.path
        onReady(model)
      },
    )
  }

  private fun runInference(
    requestId: String,
    model: Model,
    modelPath: String,
    preset: AiKeyboardPipelinePreset,
    prompt: String,
  ) {
    var rawResponse = ""
    var cleanedResponse = ""
    val inferenceStartMs = System.currentTimeMillis()
    var firstTokenLatencyMs = 0L
    LlmChatModelHelper.resetConversation(model = model)
    LlmChatModelHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotEmpty()) {
          if (firstTokenLatencyMs == 0L) {
            firstTokenLatencyMs = System.currentTimeMillis() - inferenceStartMs
          }
          rawResponse += partialResult
          cleanedResponse = processLlmResponse(rawResponse)
        }
        if (done) {
          val inferenceDurationMs = System.currentTimeMillis() - inferenceStartMs
          val outputCharsPerSecond =
            if (inferenceDurationMs > 0L) {
              (cleanedResponse.length * 1000f) / inferenceDurationMs
            } else {
              0f
            }
          running.set(false)
          scheduleIdleUnload()
          sendResult(
            requestId = requestId,
            rawText = rawResponse.trim(),
            text = cleanedResponse.trim(),
            model = model,
            modelPath = modelPath,
            preset = preset,
            prompt = prompt,
            firstTokenLatencyMs = firstTokenLatencyMs,
            inferenceDurationMs = inferenceDurationMs,
            outputCharsPerSecond = outputCharsPerSecond,
          )
        }
      },
      cleanUpListener = {
        running.set(false)
        scheduleIdleUnload()
      },
      onError = { message ->
        running.set(false)
        scheduleIdleUnload()
        sendError(requestId = requestId, error = message)
      },
    )
  }

  private fun handleCancel() {
    val model = activeModel
    if (model != null && running.get()) {
      runCatching { LlmChatModelHelper.stopResponse(model) }
    }
    running.set(false)
    scheduleIdleUnload()
    sendCancelled()
  }

  private fun scheduleIdleUnload() {
    mainHandler.removeCallbacks(idleUnloadRunnable)
    mainHandler.postDelayed(idleUnloadRunnable, IDLE_MODEL_UNLOAD_DELAY_MS)
  }

  private fun unloadActiveModel() {
    val model = activeModel ?: return
    runCatching { LlmChatModelHelper.cleanUp(model) {} }
    activeModel = null
    activeModelPath = ""
  }

  private fun sendResult(
    requestId: String,
    rawText: String,
    text: String,
    model: Model,
    modelPath: String,
    preset: AiKeyboardPipelinePreset,
    prompt: String,
    firstTokenLatencyMs: Long,
    inferenceDurationMs: Long,
    outputCharsPerSecond: Float,
  ) {
    val contextWindow = model.getConfiguredContextWindow()
    val maxTokens = model.getIntConfigValue(ConfigKeys.MAX_TOKENS, DEFAULT_MAX_TOKEN)
    sendMessage(MSG_RESULT, Bundle().apply {
      putString(KEY_REQUEST_ID, requestId)
      putString(KEY_RAW_RESULT_TEXT, rawText)
      putString(KEY_RESULT_TEXT, text)
      putString(KEY_MODEL_NAME, model.displayName.ifBlank { model.name })
      putString(KEY_MODEL_PATH, modelPath)
      putString(KEY_PRESET_NAME, preset.displayName)
      putString(KEY_PROMPT_TEXT, prompt)
      putInt(KEY_CONTEXT_WINDOW, contextWindow)
      putInt(KEY_MAX_TOKENS, maxTokens)
      putLong(KEY_FIRST_TOKEN_LATENCY_MS, firstTokenLatencyMs)
      putLong(KEY_INFERENCE_DURATION_MS, inferenceDurationMs)
      putFloat(KEY_OUTPUT_CHARS_PER_SECOND, outputCharsPerSecond)
    })
  }

  private fun sendError(requestId: String, error: String) {
    sendMessage(MSG_ERROR, Bundle().apply {
      putString(KEY_REQUEST_ID, requestId)
      putString(KEY_ERROR_TEXT, error)
    })
  }

  private fun sendCancelled() {
    sendMessage(MSG_CANCELLED, Bundle.EMPTY)
  }

  private fun sendMessage(what: Int, data: Bundle) {
    val target = replyTo ?: return
    mainHandler.post {
      runCatching {
        target.send(Message.obtain(null, what).apply { this.data = data })
      }.onFailure {
        if (it !is RemoteException) {
          throw it
        }
      }
    }
  }

  companion object {
    const val MSG_RUN = 1
    const val MSG_CANCEL = 2
    const val MSG_RESULT = 3
    const val MSG_ERROR = 4
    const val MSG_CANCELLED = 5

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_INPUT_TEXT = "input_text"
    const val KEY_PRESET_ID = "preset_id"
    const val KEY_RESULT_TEXT = "result_text"
    const val KEY_RAW_RESULT_TEXT = "raw_result_text"
    const val KEY_ERROR_TEXT = "error_text"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_MODEL_PATH = "model_path"
    const val KEY_PRESET_NAME = "preset_name"
    const val KEY_PROMPT_TEXT = "prompt_text"
    const val KEY_CONTEXT_WINDOW = "context_window"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_FIRST_TOKEN_LATENCY_MS = "first_token_latency_ms"
    const val KEY_INFERENCE_DURATION_MS = "inference_duration_ms"
    const val KEY_OUTPUT_CHARS_PER_SECOND = "output_chars_per_second"
    private const val MODEL_RELOAD_SETTLE_MS = 600L
    private const val IDLE_MODEL_UNLOAD_DELAY_MS = 120_000L
  }
}
