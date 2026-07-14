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
        onReady = { model -> runInference(requestId = requestId, model = model, prompt = prompt) },
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

  private fun runInference(requestId: String, model: Model, prompt: String) {
    var response = ""
    LlmChatModelHelper.resetConversation(model = model)
    LlmChatModelHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotEmpty()) {
          response = processLlmResponse("$response$partialResult")
        }
        if (done) {
          running.set(false)
          scheduleIdleUnload()
          sendResult(requestId = requestId, text = response.trim())
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

  private fun sendResult(requestId: String, text: String) {
    sendMessage(MSG_RESULT, Bundle().apply {
      putString(KEY_REQUEST_ID, requestId)
      putString(KEY_RESULT_TEXT, text)
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
    const val KEY_ERROR_TEXT = "error_text"
    private const val MODEL_RELOAD_SETTLE_MS = 600L
    private const val IDLE_MODEL_UNLOAD_DELAY_MS = 120_000L
  }
}
