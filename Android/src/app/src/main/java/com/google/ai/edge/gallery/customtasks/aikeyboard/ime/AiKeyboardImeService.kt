package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

import android.Manifest
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.aikeyboard.asr.VoskRecognizerSession
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelCatalog
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelRepository
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardPipelineCatalog
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardPipelineLogEntry
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardPipelineService
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardTextModelRepository
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model as VoskModel

class AiKeyboardImeService : InputMethodService() {

    private lateinit var modelRepository: AiKeyboardModelRepository
    private lateinit var textModelRepository: AiKeyboardTextModelRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadedModels = ConcurrentHashMap<String, VoskModel>()
    private val loadingModels = Collections.synchronizedSet(mutableSetOf<String>())
    private val modelLoaderExecutor = Executors.newSingleThreadExecutor()
    private val isInputViewVisible = AtomicBoolean(false)
    private val isPipelineRunning = AtomicBoolean(false)
    private val modelLifecycleEpoch = AtomicInteger(0)
    private val unloadModelsRunnable = Runnable { unloadAllModelsNow() }
    private val pipelineReplyMessenger = Messenger(PipelineReplyHandler())

    private var activeSession: VoskRecognizerSession? = null
    private var isRecording = false
    private var pipelineService: Messenger? = null
    private var pipelineBound = false
    private var pendingPipelineRequest: PipelineRequest? = null
    private var activePipelineRequestId: String? = null
    private var activePipelineInputText: String = ""
    private var activePipelineStartedAtMs: Long = 0L
    private var lastPipelineOriginalText: String? = null

    private var pipelineRunButton: Button? = null
    private var pipelineTypeButton: Button? = null
    private var pipelineUndoButton: Button? = null
    private var pipelineModelButton: Button? = null
    private var languageButton: Button? = null
    private var punctuationCommaButton: Button? = null
    private var punctuationPeriodButton: Button? = null
    private var punctuationQuestionButton: Button? = null
    private var punctuationExclamationButton: Button? = null
    private var cursorLeftButton: Button? = null
    private var cursorRightButton: Button? = null
    private var deleteBeforeCursorButton: Button? = null
    private var deleteAfterCursorButton: Button? = null
    private var jumpStartButton: Button? = null
    private var jumpEndButton: Button? = null
    private var pasteButton: Button? = null
    private var copyAllButton: Button? = null

    private val pipelineConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service ?: return
                pipelineService = Messenger(binder)
                pipelineBound = true
                val request = pendingPipelineRequest
                pendingPipelineRequest = null
                if (request != null) {
                    sendPipelineRequest(request)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                pipelineService = null
                pipelineBound = false
                if (isPipelineRunning.get()) {
                    finishPipelineRun()
                    toast("AI 键盘文本处理服务已断开，请重试。")
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        modelRepository = AiKeyboardModelRepository(this)
        textModelRepository = AiKeyboardTextModelRepository(this)
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        modelLoaderExecutor.execute {
            runCatching {
                modelRepository.ensureBundledModelsInstalled()
            }
        }
    }

    override fun onDestroy() {
        isInputViewVisible.set(false)
        mainHandler.removeCallbacks(unloadModelsRunnable)
        stopRecognition()
        cancelPipeline()
        unloadAllModelsNow()
        if (pipelineBound) {
            runCatching { unbindService(pipelineConnection) }
            pipelineBound = false
            pipelineService = null
        }
        modelLoaderExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ai_keyboard_ime_view, null)
        pipelineRunButton = root.findViewById(R.id.btnPipelineRun)
        pipelineTypeButton = root.findViewById(R.id.btnPipelineType)
        pipelineUndoButton = root.findViewById(R.id.btnPipelineUndo)
        pipelineModelButton = root.findViewById(R.id.btnPipelineModel)
        val holdToTalkButton = root.findViewById<Button>(R.id.btnHoldToTalk)
        val deleteButton = root.findViewById<Button>(R.id.btnDelete)
        languageButton = root.findViewById(R.id.btnLang)
        punctuationCommaButton = root.findViewById(R.id.btnPuncComma)
        punctuationPeriodButton = root.findViewById(R.id.btnPuncPeriod)
        punctuationQuestionButton = root.findViewById(R.id.btnPuncQuestion)
        punctuationExclamationButton = root.findViewById(R.id.btnPuncExclamation)
        cursorLeftButton = root.findViewById(R.id.btnCursorLeft)
        cursorRightButton = root.findViewById(R.id.btnCursorRight)
        deleteBeforeCursorButton = root.findViewById(R.id.btnDeleteBeforeCursor)
        deleteAfterCursorButton = root.findViewById(R.id.btnDeleteAfterCursor)
        jumpStartButton = root.findViewById(R.id.btnJumpStart)
        jumpEndButton = root.findViewById(R.id.btnJumpEnd)
        pasteButton = root.findViewById(R.id.btnPaste)
        copyAllButton = root.findViewById(R.id.btnCopyAll)
        val hideButton = root.findViewById<Button>(R.id.btnHideKeyboard)

        refreshLanguageButton()
        refreshPunctuationButtons()
        refreshPipelineButtons()

        pipelineRunButton?.setOnClickListener {
            runPipeline()
        }
        pipelineTypeButton?.setOnClickListener {
            selectNextPipeline()
        }
        pipelineUndoButton?.setOnClickListener {
            if (isPipelineRunning.get()) {
                cancelPipeline()
                toast(R.string.toast_pipeline_cancelled)
            } else {
                restoreLastPipelineText()
            }
        }
        pipelineModelButton?.setOnClickListener {
            selectNextTextModel()
        }

        holdToTalkButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRecognition()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopRecognition()
                    true
                }

                else -> false
            }
        }

        // Accessibility fallback: with screen reader, click action is more reliable than hold gesture.
        holdToTalkButton.setOnClickListener {
            if (isRecording) {
                stopRecognition()
            } else {
                startRecognition()
            }
        }

        deleteButton.setOnClickListener {
            deleteSingleChar()
        }

        deleteButton.setOnLongClickListener {
            clearAllText()
            true
        }

        languageButton?.setOnClickListener {
            toggleLanguage()
        }

        punctuationCommaButton?.setOnClickListener { insertPunctuation(PunctuationType.COMMA) }
        punctuationPeriodButton?.setOnClickListener { insertPunctuation(PunctuationType.PERIOD) }
        punctuationQuestionButton?.setOnClickListener { insertPunctuation(PunctuationType.QUESTION) }
        punctuationExclamationButton?.setOnClickListener { insertPunctuation(PunctuationType.EXCLAMATION) }
        cursorLeftButton?.setOnClickListener { moveCursorLeft() }
        cursorRightButton?.setOnClickListener { moveCursorRight() }
        deleteBeforeCursorButton?.setOnClickListener { deleteBeforeCursorAll() }
        deleteAfterCursorButton?.setOnClickListener { deleteAfterCursorAll() }
        jumpStartButton?.setOnClickListener { jumpToStart() }
        jumpEndButton?.setOnClickListener { jumpToEnd() }
        pasteButton?.setOnClickListener { pasteFromClipboard() }
        copyAllButton?.setOnClickListener { copyAllText() }

        hideButton.setOnClickListener {
            requestHideSelf(0)
        }

        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isInputViewVisible.set(true)
        mainHandler.removeCallbacks(unloadModelsRunnable)
        refreshLanguageButton()
        refreshPipelineButtons()
        ensureSelectedModelWarmup()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handleInputViewHidden()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        handleInputViewHidden()
        super.onWindowHidden()
    }

    private fun toggleLanguage() {
        val current = modelRepository.getSelectedLanguage()
        val languages = AiKeyboardModelCatalog.supportedLanguages()
        val index = languages.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = languages[(index + 1) % languages.size]
        modelRepository.setSelectedLanguage(next)
        refreshLanguageButton()
        ensureSelectedModelWarmup()
        announceForAccessibility("已切换到${AiKeyboardModelCatalog.languageDisplayName(next)}语音模型")
    }

    private fun refreshLanguageButton() {
        val button = languageButton ?: return
        val language = modelRepository.getSelectedLanguage()
        button.text = AiKeyboardModelCatalog.languageKeyboardLabel(language)
        button.contentDescription = "当前语音模型语言为${AiKeyboardModelCatalog.languageDisplayName(language)}，点按切换语言"
        refreshPunctuationButtons()
    }

    private fun refreshPunctuationButtons() {
        val isZh = modelRepository.getSelectedLanguage() == AiKeyboardModelCatalog.LANG_ZH

        punctuationCommaButton?.text = getString(if (isZh) R.string.punc_comma_zh else R.string.punc_comma_en)
        punctuationPeriodButton?.text = getString(if (isZh) R.string.punc_period_zh else R.string.punc_period_en)
        punctuationQuestionButton?.text = getString(if (isZh) R.string.punc_question_zh else R.string.punc_question_en)
        punctuationExclamationButton?.text =
            getString(if (isZh) R.string.punc_exclamation_zh else R.string.punc_exclamation_en)

        punctuationCommaButton?.contentDescription =
            getString(if (isZh) R.string.a11y_insert_comma_zh else R.string.a11y_insert_comma_en)
        punctuationPeriodButton?.contentDescription =
            getString(if (isZh) R.string.a11y_insert_period_zh else R.string.a11y_insert_period_en)
        punctuationQuestionButton?.contentDescription =
            getString(if (isZh) R.string.a11y_insert_question_zh else R.string.a11y_insert_question_en)
        punctuationExclamationButton?.contentDescription =
            getString(if (isZh) R.string.a11y_insert_exclamation_zh else R.string.a11y_insert_exclamation_en)
    }

    private fun ensureSelectedModelWarmup() {
        val language = modelRepository.getSelectedLanguage()
        val selectedModel = modelRepository.getSelectedModel(language)
        if (!modelRepository.hasModel(selectedModel.id)) return
        ensureModelWarmup(selectedModel.id, announceWhenReady = false)
    }

    private fun ensureModelWarmup(modelId: String, announceWhenReady: Boolean) {
        if (!isInputViewVisible.get()) return
        if (loadedModels.containsKey(modelId)) return
        if (loadingModels.contains(modelId)) return
        if (!modelRepository.hasModel(modelId)) return

        val scheduleEpoch = modelLifecycleEpoch.get()
        loadingModels.add(modelId)
        modelLoaderExecutor.execute {
            var model: VoskModel? = null
            try {
                model = VoskModel(modelRepository.getModelDir(modelId).absolutePath)
                if (!isInputViewVisible.get() || modelLifecycleEpoch.get() != scheduleEpoch) {
                    return@execute
                }
                val readyModel = model ?: return@execute
                val old = loadedModels.put(modelId, readyModel)
                model = null
                old?.close()
                if (announceWhenReady && isInputViewVisible.get()) {
                    mainHandler.post { toast(R.string.toast_model_ready) }
                }
            } catch (e: Exception) {
                if (announceWhenReady) {
                    mainHandler.post { toast(e.message ?: "model warmup failed") }
                }
            } finally {
                model?.close()
                loadingModels.remove(modelId)
            }
        }
    }

    private fun handleInputViewHidden() {
        val wasVisible = isInputViewVisible.getAndSet(false)
        stopRecognition()
        mainHandler.removeCallbacks(unloadModelsRunnable)
        if (wasVisible || loadedModels.isNotEmpty() || loadingModels.isNotEmpty()) {
            mainHandler.postDelayed(unloadModelsRunnable, MODEL_UNLOAD_DELAY_MS)
        }
    }

    private fun unloadAllModelsNow() {
        modelLifecycleEpoch.incrementAndGet()
        loadingModels.clear()
        loadedModels.keys.toList().forEach { id ->
            loadedModels.remove(id)?.close()
        }
    }

    private fun unloadNonTargetModels(targetModelId: String) {
        loadedModels.keys.toList().forEach { id ->
            if (id != targetModelId) {
                loadedModels.remove(id)?.close()
            }
        }
    }

    private fun startRecognition() {
        if (isRecording) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.toast_no_permission)
            return
        }

        val language = modelRepository.getSelectedLanguage()
        val selectedModel = modelRepository.getSelectedModel(language)
        if (!modelRepository.hasModel(selectedModel.id)) {
            toast(R.string.toast_model_not_ready)
            return
        }

        unloadNonTargetModels(selectedModel.id)

        val model = loadedModels[selectedModel.id] ?: run {
            ensureModelWarmup(selectedModel.id, announceWhenReady = true)
            toast(R.string.toast_model_loading)
            return
        }

        val session = VoskRecognizerSession(
            model = model,
            onRecognizedText = { text ->
                mainHandler.post {
                    commitText(text)
                }
            },
            onError = { err ->
                mainHandler.post {
                    toast(err)
                }
            }
        )

        try {
            session.start()
            activeSession = session
            isRecording = true
            triggerMicFeedback()
        } catch (e: Exception) {
            toast(e.message ?: "recognition start failed")
        }
    }

    private fun stopRecognition() {
        if (!isRecording) return
        isRecording = false
        activeSession?.stop()
        activeSession = null
    }

    private fun refreshPipelineButtons() {
        val preset = textModelRepository.getPipelinePreset(textModelRepository.getSelectedPipelineId())
            ?: AiKeyboardPipelineCatalog.defaultPreset()
        pipelineTypeButton?.text = preset.keyboardLabel
        pipelineTypeButton?.contentDescription = getString(R.string.a11y_pipeline_type, preset.displayName)
        pipelineRunButton?.text = if (isPipelineRunning.get()) {
            getString(R.string.btn_pipeline_running)
        } else {
            getString(R.string.btn_pipeline_run)
        }
        pipelineRunButton?.contentDescription = getString(R.string.a11y_pipeline_run)
        pipelineUndoButton?.text = getString(R.string.btn_pipeline_undo)
        pipelineUndoButton?.contentDescription =
            if (isPipelineRunning.get()) {
                getString(R.string.a11y_pipeline_cancel)
            } else {
                getString(R.string.a11y_pipeline_undo)
            }
        val selectedModel = textModelRepository.getSelectedModel()
        pipelineModelButton?.text = selectedModel?.displayName?.shortKeyboardModelLabel()
            ?: getString(R.string.btn_pipeline_model_none)
        pipelineModelButton?.contentDescription =
            selectedModel?.let { getString(R.string.a11y_pipeline_model, it.displayName) }
                ?: getString(R.string.a11y_pipeline_model_none)
    }

    private fun selectNextPipeline() {
        val preset = textModelRepository.selectNextPipeline()
        refreshPipelineButtons()
        announcePipelineAction(getString(R.string.toast_pipeline_type_selected, preset.displayName))
    }

    private fun selectNextTextModel() {
        val model = textModelRepository.selectNextModel()
        refreshPipelineButtons()
        if (model == null) {
            toast(R.string.toast_pipeline_no_text_model)
        } else {
            announcePipelineAction(getString(R.string.toast_pipeline_model_selected, model.displayName))
        }
    }

    private fun runPipeline() {
        if (!isPipelineRunning.compareAndSet(false, true)) {
            toast(R.string.toast_pipeline_running)
            return
        }

        val ic = currentInputConnection
        if (ic == null) {
            finishPipelineRun()
            return
        }
        val originalText = readAllText(ic)
        if (originalText.isBlank()) {
            finishPipelineRun()
            toast(R.string.toast_pipeline_no_text)
            return
        }

        lastPipelineOriginalText = originalText
        val request =
            PipelineRequest(
                requestId = UUID.randomUUID().toString(),
                presetId = textModelRepository.getSelectedPipelineId(),
                inputText = originalText,
            )
        activePipelineRequestId = request.requestId
        activePipelineInputText = originalText
        activePipelineStartedAtMs = System.currentTimeMillis()
        refreshPipelineButtons()
        toast(R.string.toast_pipeline_started)
        if (!sendPipelineRequest(request)) {
            pendingPipelineRequest = request
            bindPipelineService()
        }
    }

    private fun cancelPipeline() {
        pendingPipelineRequest = null
        val service = pipelineService
        if (service != null) {
            runCatching {
                service.send(
                    Message.obtain(null, AiKeyboardPipelineService.MSG_CANCEL).apply {
                        replyTo = pipelineReplyMessenger
                    }
                )
            }
        }
        finishPipelineRun()
    }

    private fun finishPipelineRun() {
        isPipelineRunning.set(false)
        activePipelineRequestId = null
        activePipelineInputText = ""
        activePipelineStartedAtMs = 0L
        refreshPipelineButtons()
    }

    private fun bindPipelineService() {
        val intent = Intent(this, AiKeyboardPipelineService::class.java)
        val bound = bindService(intent, pipelineConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            pendingPipelineRequest = null
            finishPipelineRun()
            toast("无法启动 AI 键盘文本处理服务。")
        }
    }

    private fun sendPipelineRequest(request: PipelineRequest): Boolean {
        val service = pipelineService ?: return false
        return try {
            service.send(
                Message.obtain(null, AiKeyboardPipelineService.MSG_RUN).apply {
                    replyTo = pipelineReplyMessenger
                    data =
                        Bundle().apply {
                            putString(AiKeyboardPipelineService.KEY_REQUEST_ID, request.requestId)
                            putString(AiKeyboardPipelineService.KEY_INPUT_TEXT, request.inputText)
                            putString(AiKeyboardPipelineService.KEY_PRESET_ID, request.presetId)
                        }
                }
            )
            true
        } catch (e: RemoteException) {
            pipelineService = null
            pipelineBound = false
            false
        }
    }

    private inner class PipelineReplyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AiKeyboardPipelineService.MSG_RESULT -> {
                    val requestId = msg.data.getString(AiKeyboardPipelineService.KEY_REQUEST_ID)
                    if (requestId != activePipelineRequestId) return
                    val text = msg.data.getString(AiKeyboardPipelineService.KEY_RESULT_TEXT).orEmpty().trim()
                    var committedText = ""
                    var commitDurationMs = 0L
                    if (text.isBlank()) {
                        toast(R.string.toast_pipeline_empty_result)
                        appendPipelineLog(
                            data = msg.data,
                            outputText = text,
                            committedText = committedText,
                            status = "success",
                            commitDurationMs = commitDurationMs,
                        )
                        finishPipelineRun()
                    } else if (isPipelineRunning.get()) {
                        val commitStartMs = System.currentTimeMillis()
                        val outcome = replaceAllText(text)
                        commitDurationMs = System.currentTimeMillis() - commitStartMs
                        committedText = outcome.committedText
                        appendEditorInfo(msg.data)
                        msg.data.putString(KEY_COMMIT_STRATEGY, outcome.strategy)
                        msg.data.putInt(KEY_DIRECT_COMMITTED_LENGTH, outcome.directCommittedText.length)
                        msg.data.putBoolean(KEY_CLIPBOARD_FALLBACK_USED, outcome.clipboardFallbackUsed)
                        msg.data.putBoolean(KEY_CLIPBOARD_PASTE_ACCEPTED, outcome.clipboardPasteAccepted)
                        msg.data.putBoolean(KEY_DIRECT_COMMIT_ACCEPTED, outcome.directCommitAccepted)
                        msg.data.putInt(KEY_CLIPBOARD_COMMITTED_LENGTH, outcome.clipboardCommittedText.length)
                        toast(R.string.toast_pipeline_done)
                        appendPipelineLogAfterCommitSettles(
                            data = Bundle(msg.data),
                            outputText = text,
                            immediateCommittedText = committedText,
                            commitDurationMs = commitDurationMs,
                        )
                    } else {
                        appendPipelineLog(
                            data = msg.data,
                            outputText = text,
                            committedText = committedText,
                            status = "success",
                            commitDurationMs = commitDurationMs,
                        )
                        finishPipelineRun()
                    }
                }

                AiKeyboardPipelineService.MSG_ERROR -> {
                    val requestId = msg.data.getString(AiKeyboardPipelineService.KEY_REQUEST_ID)
                    if (requestId != activePipelineRequestId) return
                    toast(msg.data.getString(AiKeyboardPipelineService.KEY_ERROR_TEXT).orEmpty())
                    appendPipelineLog(
                        data = msg.data,
                        outputText = "",
                        committedText = "",
                        status = "error",
                        errorText = msg.data.getString(AiKeyboardPipelineService.KEY_ERROR_TEXT).orEmpty(),
                    )
                    finishPipelineRun()
                }

                AiKeyboardPipelineService.MSG_CANCELLED -> {
                    finishPipelineRun()
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private fun appendPipelineLog(
        data: Bundle,
        outputText: String,
        committedText: String,
        status: String,
        errorText: String = "",
        commitDurationMs: Long = 0L,
    ) {
        val presetId = textModelRepository.getSelectedPipelineId()
        val preset = textModelRepository.getPipelinePreset(presetId) ?: AiKeyboardPipelineCatalog.defaultPreset()
        val rawOutput = data.getString(AiKeyboardPipelineService.KEY_RAW_RESULT_TEXT).orEmpty()
        runCatching {
            textModelRepository.appendPipelineLog(
                AiKeyboardPipelineLogEntry(
                    id = UUID.randomUUID().toString(),
                    createdAtMillis = System.currentTimeMillis(),
                    presetId = presetId,
                    presetName = data.getString(AiKeyboardPipelineService.KEY_PRESET_NAME).orEmpty()
                        .ifBlank { preset.displayName },
                    modelName = data.getString(AiKeyboardPipelineService.KEY_MODEL_NAME).orEmpty(),
                    modelPath = data.getString(AiKeyboardPipelineService.KEY_MODEL_PATH).orEmpty(),
                    inputText = activePipelineInputText,
                    promptText = data.getString(AiKeyboardPipelineService.KEY_PROMPT_TEXT).orEmpty(),
                    rawOutputText = rawOutput,
                    outputText = outputText,
                    committedText = committedText,
                    inputLength = activePipelineInputText.length,
                    rawOutputLength = rawOutput.length,
                    outputLength = outputText.length,
                    committedLength = committedText.length,
                    maxTokens = data.getInt(AiKeyboardPipelineService.KEY_MAX_TOKENS, 0),
                    contextWindow = data.getInt(AiKeyboardPipelineService.KEY_CONTEXT_WINDOW, 0),
                    status = status,
                    errorText = errorText,
                    firstTokenLatencyMs = data.getLong(AiKeyboardPipelineService.KEY_FIRST_TOKEN_LATENCY_MS, 0L),
                    inferenceDurationMs = data.getLong(AiKeyboardPipelineService.KEY_INFERENCE_DURATION_MS, 0L),
                    commitDurationMs = commitDurationMs,
                    totalDurationMs =
                        activePipelineStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L,
                    outputCharsPerSecond = data.getFloat(AiKeyboardPipelineService.KEY_OUTPUT_CHARS_PER_SECOND, 0f),
                    commitStrategy = data.getString(KEY_COMMIT_STRATEGY).orEmpty(),
                    directCommittedLength = data.getInt(KEY_DIRECT_COMMITTED_LENGTH, 0),
                    clipboardFallbackUsed = data.getBoolean(KEY_CLIPBOARD_FALLBACK_USED, false),
                    clipboardPasteAccepted = data.getBoolean(KEY_CLIPBOARD_PASTE_ACCEPTED, false),
                    directCommitAccepted = data.getBoolean(KEY_DIRECT_COMMIT_ACCEPTED, false),
                    clipboardCommittedLength = data.getInt(KEY_CLIPBOARD_COMMITTED_LENGTH, 0),
                    delayedCommittedLength = data.getInt(KEY_DELAYED_COMMITTED_LENGTH, 0),
                    editorPackageName = data.getString(KEY_EDITOR_PACKAGE_NAME).orEmpty(),
                    editorFieldId = data.getInt(KEY_EDITOR_FIELD_ID, 0),
                    editorInputType = data.getInt(KEY_EDITOR_INPUT_TYPE, 0),
                    editorImeOptions = data.getInt(KEY_EDITOR_IME_OPTIONS, 0),
                )
            )
        }
    }

    private fun appendPipelineLogAfterCommitSettles(
        data: Bundle,
        outputText: String,
        immediateCommittedText: String,
        commitDurationMs: Long,
    ) {
        mainHandler.postDelayed(
            {
                val delayedCommittedText = currentInputConnection?.let { readAllText(it) }.orEmpty()
                data.putInt(KEY_DELAYED_COMMITTED_LENGTH, delayedCommittedText.length)
                appendPipelineLog(
                    data = data,
                    outputText = outputText,
                    committedText = delayedCommittedText.ifBlank { immediateCommittedText },
                    status = "success",
                    commitDurationMs = commitDurationMs,
                )
                finishPipelineRun()
            },
            POST_COMMIT_READBACK_DELAY_MS,
        )
    }

    private fun appendEditorInfo(data: Bundle) {
        val info = currentInputEditorInfo ?: return
        data.putString(KEY_EDITOR_PACKAGE_NAME, info.packageName.orEmpty())
        data.putInt(KEY_EDITOR_FIELD_ID, info.fieldId)
        data.putInt(KEY_EDITOR_INPUT_TYPE, info.inputType)
        data.putInt(KEY_EDITOR_IME_OPTIONS, info.imeOptions)
    }

    private fun restoreLastPipelineText() {
        val original = lastPipelineOriginalText
        if (original == null) {
            toast(R.string.toast_pipeline_no_undo)
            return
        }
        replaceAllText(original)
        lastPipelineOriginalText = null
        toast(R.string.toast_pipeline_restored)
    }

    private fun replaceAllText(text: String): PipelineCommitOutcome {
        val ic = currentInputConnection ?: return PipelineCommitOutcome(committedText = "", strategy = "none")
        val directCommit = replaceAllTextDirect(ic, text)
        val directCommittedText = directCommit.committedText
        if (!AiKeyboardCommitVerifier.needsClipboardFallback(text, directCommittedText)) {
            return PipelineCommitOutcome(
                committedText = directCommittedText,
                strategy = "direct",
                directCommittedText = directCommittedText,
                directCommitAccepted = directCommit.accepted,
            )
        }

        val clipboardOutcome = replaceAllTextViaClipboard(text, directCommit)
        if (clipboardOutcome.committedText.isNotBlank()) {
            return clipboardOutcome
        }
        return PipelineCommitOutcome(
            committedText = directCommittedText,
            strategy = "direct_truncated",
            directCommittedText = directCommittedText,
            directCommitAccepted = directCommit.accepted,
        )
    }

    private fun replaceAllTextDirect(ic: InputConnection, text: String): DirectCommitResult {
        var accepted = false
        ic.beginBatchEdit()
        try {
            clearBeforeCursor(ic)
            clearAfterCursor(ic)
            accepted = ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
        return DirectCommitResult(
            accepted = accepted,
            committedText = readAllText(ic),
        )
    }

    private fun replaceAllTextViaClipboard(text: String, directCommit: DirectCommitResult): PipelineCommitOutcome {
        val directCommittedText = directCommit.committedText
        val ic = currentInputConnection ?: return PipelineCommitOutcome(
            committedText = directCommittedText,
            strategy = "direct_truncated",
            directCommittedText = directCommittedText,
            directCommitAccepted = directCommit.accepted,
        )
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return PipelineCommitOutcome(
            committedText = directCommittedText,
            strategy = "direct_truncated",
            directCommittedText = directCommittedText,
            directCommitAccepted = directCommit.accepted,
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("ai-keyboard-pipeline-result", text))
        ic.beginBatchEdit()
        val pasteAccepted =
            try {
                clearBeforeCursor(ic)
                clearAfterCursor(ic)
                ic.performContextMenuAction(android.R.id.paste)
            } finally {
                ic.endBatchEdit()
            }
        val pastedText = readAllText(ic)
        return PipelineCommitOutcome(
            committedText = pastedText,
            strategy = "clipboard_paste",
            directCommittedText = directCommittedText,
            clipboardFallbackUsed = true,
            clipboardPasteAccepted = pasteAccepted,
            directCommitAccepted = directCommit.accepted,
            clipboardCommittedText = pastedText,
        )
    }

    private fun extractedTextRequest(): ExtractedTextRequest {
        return ExtractedTextRequest().apply {
            hintMaxChars = TEXT_READ_MAX_CHARS
            hintMaxLines = TEXT_READ_MAX_LINES
        }
    }

    private fun announcePipelineAction(message: String) {
        pipelineTypeButton?.announceForAccessibility(message)
        toast(message)
    }

    private fun triggerMicFeedback() {
        languageButton?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        vibrateShort()
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort() {
        runCatching {
            val durationMs = 28L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                return@runCatching
            }

            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                vibrator.vibrate(durationMs)
            }
        }
    }

    private fun commitText(text: String) {
        val language = modelRepository.getSelectedLanguage()
        val normalized = normalizeRecognizedText(text, language)
        if (normalized.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.commitText(normalized, 1)
    }

    private fun normalizeRecognizedText(text: String, language: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return if (language == AiKeyboardModelCatalog.LANG_ZH) {
            trimmed.replace(WHITESPACE_REGEX, "")
        } else {
            trimmed.replace(WHITESPACE_REGEX, " ")
        }
    }

    private fun insertPunctuation(type: PunctuationType) {
        val isZh = modelRepository.getSelectedLanguage() == AiKeyboardModelCatalog.LANG_ZH
        val symbol = when (type) {
            PunctuationType.COMMA -> getString(if (isZh) R.string.punc_comma_zh else R.string.punc_comma_en)
            PunctuationType.PERIOD -> getString(if (isZh) R.string.punc_period_zh else R.string.punc_period_en)
            PunctuationType.QUESTION -> getString(if (isZh) R.string.punc_question_zh else R.string.punc_question_en)
            PunctuationType.EXCLAMATION ->
                getString(if (isZh) R.string.punc_exclamation_zh else R.string.punc_exclamation_en)
        }
        val ic = currentInputConnection ?: return
        ic.commitText(symbol, 1)
    }

    private fun moveCursorLeft() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
    }

    private fun moveCursorRight() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun deleteBeforeCursorAll() {
        val ic = currentInputConnection ?: return
        clearBeforeCursor(ic)
    }

    private fun deleteAfterCursorAll() {
        val ic = currentInputConnection ?: return
        clearAfterCursor(ic)
    }

    private fun jumpToStart() {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(extractedTextRequest(), 0)
        if (extracted?.text != null) {
            ic.setSelection(0, 0)
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
    }

    private fun jumpToEnd() {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(extractedTextRequest(), 0)
        val textLength = extracted?.text?.length
        if (textLength != null) {
            ic.setSelection(textLength, textLength)
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip
        val pasted = clip?.let { c ->
            if (c.itemCount <= 0) return@let null
            c.getItemAt(0)?.coerceToText(this)?.toString()
        }?.trim()
        if (pasted.isNullOrEmpty()) {
            toast(R.string.toast_clipboard_empty)
            return
        }
        val ic = currentInputConnection ?: return
        ic.commitText(pasted, 1)
    }

    private fun copyAllText() {
        val ic = currentInputConnection ?: return
        val fullText = readAllText(ic)
        if (fullText.isBlank()) {
            toast(R.string.toast_no_text_to_copy)
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("voice-ime-all-text", fullText))
        toast(R.string.toast_copied_all_text)
    }

    private fun readAllText(ic: InputConnection): String {
        val extracted = ic.getExtractedText(extractedTextRequest(), 0)
        val extractedText = extracted?.text?.toString().orEmpty()
        if (extractedText.isNotEmpty()) return extractedText

        val before = ic.getTextBeforeCursor(TEXT_READ_MAX_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(TEXT_READ_MAX_CHARS, 0)?.toString().orEmpty()
        return before + after
    }

    private fun deleteSingleChar() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(1, 0)
    }

    private fun clearAllText() {
        val ic = currentInputConnection ?: return
        clearBeforeCursor(ic)
        clearAfterCursor(ic)
    }

    private fun clearBeforeCursor(ic: InputConnection) {
        while (true) {
            val before = ic.getTextBeforeCursor(1024, 0) ?: ""
            if (before.isEmpty()) break
            ic.deleteSurroundingText(before.length, 0)
        }
    }

    private fun clearAfterCursor(ic: InputConnection) {
        while (true) {
            val after = ic.getTextAfterCursor(1024, 0) ?: ""
            if (after.isEmpty()) break
            ic.deleteSurroundingText(0, after.length)
        }
    }

    private fun toast(messageRes: Int) {
        toast(getString(messageRes))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun announceForAccessibility(message: String) {
        languageButton?.announceForAccessibility(message)
    }

    private fun String.shortKeyboardModelLabel(): String {
        val trimmed = trim()
        if (trimmed.isEmpty()) return getString(R.string.btn_pipeline_model_none)
        return trimmed.take(4)
    }

    private enum class PunctuationType {
        COMMA,
        PERIOD,
        QUESTION,
        EXCLAMATION
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val TEXT_READ_MAX_CHARS = 100_000
        private const val TEXT_READ_MAX_LINES = 10_000
        private const val MODEL_UNLOAD_DELAY_MS = 600L
        private const val POST_COMMIT_READBACK_DELAY_MS = 300L
        private const val KEY_COMMIT_STRATEGY = "ai_keyboard_commit_strategy"
        private const val KEY_DIRECT_COMMITTED_LENGTH = "ai_keyboard_direct_committed_length"
        private const val KEY_CLIPBOARD_FALLBACK_USED = "ai_keyboard_clipboard_fallback_used"
        private const val KEY_CLIPBOARD_PASTE_ACCEPTED = "ai_keyboard_clipboard_paste_accepted"
        private const val KEY_DIRECT_COMMIT_ACCEPTED = "ai_keyboard_direct_commit_accepted"
        private const val KEY_CLIPBOARD_COMMITTED_LENGTH = "ai_keyboard_clipboard_committed_length"
        private const val KEY_DELAYED_COMMITTED_LENGTH = "ai_keyboard_delayed_committed_length"
        private const val KEY_EDITOR_PACKAGE_NAME = "ai_keyboard_editor_package_name"
        private const val KEY_EDITOR_FIELD_ID = "ai_keyboard_editor_field_id"
        private const val KEY_EDITOR_INPUT_TYPE = "ai_keyboard_editor_input_type"
        private const val KEY_EDITOR_IME_OPTIONS = "ai_keyboard_editor_ime_options"
    }
}

private data class PipelineRequest(
    val requestId: String,
    val presetId: String,
    val inputText: String,
)

private data class PipelineCommitOutcome(
    val committedText: String,
    val strategy: String,
    val directCommittedText: String = committedText,
    val clipboardFallbackUsed: Boolean = false,
    val clipboardPasteAccepted: Boolean = false,
    val directCommitAccepted: Boolean = false,
    val clipboardCommittedText: String = "",
)

private data class DirectCommitResult(
    val accepted: Boolean,
    val committedText: String,
)
