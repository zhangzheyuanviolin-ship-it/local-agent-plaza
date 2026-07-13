package com.google.ai.edge.gallery.customtasks.aikeyboard.asr

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class VoskRecognizerSession(
    private val model: Model,
    private val onRecognizedText: (String) -> Unit,
    private val onError: (String) -> Unit
) : RecognitionListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)

    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var lastEmittedText: String = ""
    private val cleanupRunnable = Runnable { cleanup() }

    @Throws(IOException::class)
    fun start() {
        val rec = Recognizer(model, SAMPLE_RATE)
        recognizer = rec
        val service = SpeechService(rec, SAMPLE_RATE)
        speechService = service
        service.startListening(this)
    }

    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            speechService?.stop()
            // Give final callback a short window to dispatch before releasing resources.
            mainHandler.postDelayed(cleanupRunnable, FINAL_RESULT_GRACE_MS)
        }
    }

    override fun onResult(hypothesis: String?) {
        emitText(hypothesis)
    }

    override fun onPartialResult(hypothesis: String?) {
        // Intentionally ignored for the first usable version.
    }

    override fun onFinalResult(hypothesis: String?) {
        emitText(hypothesis)
        cleanup()
    }

    override fun onError(e: Exception?) {
        onError(e?.message ?: "unknown recognizer error")
        cleanup()
    }

    override fun onTimeout() {
        cleanup()
    }

    private fun emitText(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isBlank()) return
        if (text == lastEmittedText) return
        lastEmittedText = text
        onRecognizedText(text)
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(cleanupRunnable)
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun parseText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(hypothesis).optString("text", "").trim()
        }.getOrDefault("")
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
        private const val FINAL_RESULT_GRACE_MS = 350L
    }
}
