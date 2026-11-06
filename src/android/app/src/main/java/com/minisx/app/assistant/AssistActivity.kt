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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantSettings.init(this)
        val app = applicationContext
        thread(name = "assist-activity", isDaemon = true) {
            val shot = if (AssistantSettings.screenshot) runCatching { ScreenCapture.captureOwn(app, AssistantInvoker.shotDir(app)) }.getOrNull() else null
            AppLogger.info("AssistActivity", "invocation: shot=${shot != null}")
            runOnUiThread {
                AssistantInvoker.launch(app, AssistantInvocation(shot, null, null, null, "assist-intent"))
                finish()
            }
        }
    }
}
