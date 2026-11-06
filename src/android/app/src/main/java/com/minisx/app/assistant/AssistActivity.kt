package com.minisx.app.assistant

import android.app.Activity
import android.os.Bundle
import com.minisx.app.logging.AppLogger
import kotlin.concurrent.thread

/**
 * `android.intent.action.ASSIST`: what a Samsung side key set to "digital assistant", a headset button
 * or another app sends when no voice-interaction session is involved. Transparent, so the screen it
 * photographs is still the previous app's; it captures at once, opens MinisX and goes away.
 */
class AssistActivity : Activity() {
    companion object { const val EXTRA_DELAY_MS = "com.minisx.app.extra.CAPTURE_DELAY_MS" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantSettings.init(this)
        val app = applicationContext
        // From the quick-settings tile the shade is still closing: wait for it, or it is in the picture.
        val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L).coerceIn(0L, 1_500L)
        thread(name = "assist-activity", isDaemon = true) {
            if (delay > 0) Thread.sleep(delay)
            val shot = if (AssistantSettings.screenshot) runCatching { ScreenCapture.captureOwn(app, AssistantInvoker.shotDir(app)) }.getOrNull() else null
            AppLogger.info("AssistActivity", "invocation: shot=${shot != null}")
            runOnUiThread {
                AssistantInvoker.launch(app, AssistantInvocation(shot, null, null, null, if (delay > 0) "tile" else "assist-intent"))
                finish()
            }
        }
    }
}
