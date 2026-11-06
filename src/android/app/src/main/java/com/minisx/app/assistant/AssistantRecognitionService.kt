package com.minisx.app.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import com.minisx.app.logging.AppLogger

/**
 * Android moves the phone's default speech recognizer to the one a digital assistant declares, so
 * once MinisX is the assistant this is what every `SpeechRecognizer.createSpeechRecognizer(context)`
 * reaches: MinisX's own dictation and `android-speech`, and other apps'. MinisX has no recognizer of
 * its own, so this forwards each request to another one installed on the phone (the Google one
 * first) and relays its answers. Without it, becoming the assistant would silently break dictation.
 */
class AssistantRecognitionService : RecognitionService() {

    private val main = Handler(Looper.getMainLooper())
    private var delegate: SpeechRecognizer? = null

    private fun target(): ComponentName? {
        val found = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != packageName }
        val pick = found.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" } ?: found.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        main.post {
            val component = target()
            if (component == null) {
                AppLogger.warning(TAG, "no other speech recognizer installed to forward to")
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                return@post
            }
            delegate?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(applicationContext, component)
            delegate = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.readyForSpeech(params ?: Bundle())
                override fun onBeginningOfSpeech() = listener.beginningOfSpeech()
                override fun onRmsChanged(rmsdB: Float) = listener.rmsChanged(rmsdB)
                override fun onBufferReceived(buffer: ByteArray?) = listener.bufferReceived(buffer ?: ByteArray(0))
                override fun onEndOfSpeech() = listener.endOfSpeech()
                override fun onError(error: Int) = listener.error(error)
                override fun onResults(results: Bundle?) = listener.results(results ?: Bundle())
                override fun onPartialResults(partialResults: Bundle?) = listener.partialResults(partialResults ?: Bundle())
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) {
        main.post { delegate?.stopListening() }
    }

    override fun onCancel(listener: Callback) {
        main.post {
            delegate?.cancel()
            delegate?.destroy()
            delegate = null
        }
    }

    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        if (Build.VERSION.SDK_INT < 33) return
        main.post {
            val component = target()
            if (component == null) { supportCallback.onError(SpeechRecognizer.ERROR_CLIENT); return@post }
            val r = SpeechRecognizer.createSpeechRecognizer(applicationContext, component)
            r.checkRecognitionSupport(recognizerIntent, { it.run() }, object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    supportCallback.onSupportResult(recognitionSupport)
                    r.destroy()
                }

                override fun onError(error: Int) {
                    supportCallback.onError(error)
                    r.destroy()
                }
            })
        }
    }

    override fun onDestroy() {
        main.post { delegate?.destroy(); delegate = null }
        super.onDestroy()
    }

    companion object { private const val TAG = "AssistantRecognition" }
}
