package com.minisx.app.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.minisx.app.MainActivity
import com.minisx.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Ties the overlay, the voice input and the chat run together for one invocation. The host (the
 * voice-interaction session or the transparent activity) creates one per show and calls [begin] once
 * the screen has been captured, and [end] when it goes away.
 */
class AssistantOverlayController(
    private val ctx: Context,
    val overlay: AssistantOverlay,
    private val dismissHost: () -> Unit,
) {
    private var scope: CoroutineScope? = null
    private var runner: AssistantRunner? = null
    private var voice: AssistantVoice? = null

    fun begin(invocation: AssistantInvocation?) {
        end()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val r = AssistantRunner(ctx, s, object : AssistantRunner.Ui {
            override fun answer(text: String?, working: Boolean) = overlay.showAnswer(text, working)
            override fun note(resId: Int) = overlay.showNote(resId)
            override fun openApp(sessionId: String?) = openInApp(sessionId)
        })
        r.start(invocation)
        runner = r
        val v = AssistantVoice(ctx, object : AssistantVoice.Listener {
            override fun onPartial(text: String) = overlay.setText(text)
            override fun onFinal(text: String) { overlay.setText(""); overlay.hideKeyboard(); r.submit(text) }
            override fun onError(kind: String) {
                when (kind) {
                    "permission" -> overlay.showNote(R.string.assistant_overlay_no_mic)
                    "unavailable" -> overlay.showNote(R.string.assistant_overlay_no_recognizer)
                    "no_speech" -> overlay.showNote(R.string.assistant_overlay_no_speech)
                    else -> overlay.showNote(R.string.assistant_overlay_voice_error)
                }
                overlay.showKeyboard()
            }
            override fun onListening(on: Boolean) = overlay.setListening(on)
        })
        voice = v
        overlay.setScreenIncluded(invocation?.screenshot != null || !invocation?.screenText.isNullOrBlank())
        overlay.onSubmit = { r.submit(it) }
        overlay.onMic = { if (v.listening) v.stop() else v.start() }
        overlay.onOpenApp = { openInApp(r.sessionId) }
        overlay.onDismiss = { dismissHost() }
        overlay.reveal()
        // An assistant listens first; the keyboard is one tap on the field.
        if (v.hasPermission()) v.start() else overlay.showKeyboard()
    }

    fun end() {
        voice?.destroy(); voice = null
        runner?.stop(); runner = null
        scope?.cancel(); scope = null
    }

    private fun openInApp(sessionId: String?) {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) intent.data = Uri.parse("minis://session/$sessionId")
        runCatching { ctx.startActivity(intent) }
        dismissHost()
    }
}
