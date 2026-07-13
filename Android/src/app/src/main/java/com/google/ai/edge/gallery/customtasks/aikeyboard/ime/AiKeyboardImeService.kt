package com.google.ai.edge.gallery.customtasks.aikeyboard.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AiKeyboardImeService : InputMethodService() {

    private lateinit var modelRepository: AiKeyboardModelRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadedModels = ConcurrentHashMap<String, Model>()
    private val loadingModels = Collections.synchronizedSet(mutableSetOf<String>())
    private val modelLoaderExecutor = Executors.newSingleThreadExecutor()
    private val isInputViewVisible = AtomicBoolean(false)
    private val modelLifecycleEpoch = AtomicInteger(0)
    private val unloadModelsRunnable = Runnable { unloadAllModelsNow() }

    private var activeSession: VoskRecognizerSession? = null
    private var isRecording = false

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

    override fun onCreate() {
        super.onCreate()
        modelRepository = AiKeyboardModelRepository(this)
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
        unloadAllModelsNow()
        modelLoaderExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ai_keyboard_ime_view, null)
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
        val next = if (current == AiKeyboardModelCatalog.LANG_ZH) AiKeyboardModelCatalog.LANG_EN else AiKeyboardModelCatalog.LANG_ZH
        modelRepository.setSelectedLanguage(next)
        refreshLanguageButton()
        ensureSelectedModelWarmup()
        announceForAccessibility(
            if (next == AiKeyboardModelCatalog.LANG_ZH) {
                getString(R.string.a11y_lang_switched_zh)
            } else {
                getString(R.string.a11y_lang_switched_en)
            }
        )
    }

    private fun refreshLanguageButton() {
        val button = languageButton ?: return
        val isZh = modelRepository.getSelectedLanguage() == AiKeyboardModelCatalog.LANG_ZH
        button.setText(if (isZh) R.string.btn_lang_zh else R.string.btn_lang_en)
        button.contentDescription = if (isZh) {
            getString(R.string.a11y_lang_now_zh)
        } else {
            getString(R.string.a11y_lang_now_en)
        }
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
            var model: Model? = null
            try {
                model = Model(modelRepository.getModelDir(modelId).absolutePath)
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
            toast(R.string.toast_recording)
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
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted?.text != null) {
            ic.setSelection(0, 0)
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
    }

    private fun jumpToEnd() {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
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
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
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

    private enum class PunctuationType {
        COMMA,
        PERIOD,
        QUESTION,
        EXCLAMATION
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val TEXT_READ_MAX_CHARS = 100_000
        private const val MODEL_UNLOAD_DELAY_MS = 600L
    }
}
