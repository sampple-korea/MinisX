package com.minisx.app.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/** Speech input for the assistant overlay: the phone's default recognizer, partial results as it goes. */
class AssistantVoice(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** `permission`, `unavailable`, `no_speech` or `error`. */
        fun onError(kind: String)
        fun onListening(on: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (listening) return
        if (!hasPermission()) { listener.onError("permission"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { listener.onError("unavailable"); return }
        try {
            destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true; listener.onListening(true) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listening = false; listener.onListening(false)
                    listener.onError(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "no_speech" else "error")
                }
                override fun onResults(results: Bundle?) {
                    listening = false; listener.onListening(false)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) listener.onError("no_speech") else listener.onFinal(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName),
            )
        } catch (e: Throwable) {
            listening = false
            listener.onError("error")
        }
    }

    fun stop() { runCatching { recognizer?.stopListening() } }

    fun destroy() {
        listening = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
