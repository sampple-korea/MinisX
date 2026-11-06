package com.minisx.app.assistant

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.minisx.app.logging.AppLogger
import kotlin.concurrent.thread

/**
 * `android.intent.action.ASSIST` (a Samsung side key set to "digital assistant", a headset button) and
 * the quick-settings tile. Transparent, so what it photographs is still the previous app; it captures
 * at once, and only then lets the assistant overlay appear (so the overlay is not in the picture).
 */
class AssistActivity : Activity() {
    companion object { const val EXTRA_DELAY_MS = "com.minisx.app.extra.CAPTURE_DELAY_MS" }

    private var controller: AssistantOverlayController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        AssistantSettings.init(this)
        val app = applicationContext
        val overlay = AssistantOverlay(this)
        setContentView(overlay.root)
        controller = AssistantOverlayController(this, overlay) { finish() }
        // From the quick-settings tile the shade is still closing: wait for it, or it is in the picture.
        val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L).coerceIn(0L, 1_500L)
        thread(name = "assist-activity", isDaemon = true) {
            if (delay > 0) Thread.sleep(delay)
            val shot = if (AssistantSettings.screenshot) runCatching { ScreenCapture.captureOwn(app, AssistantInvoker.shotDir(app)) }.getOrNull() else null
            AppLogger.info("AssistActivity", "invocation: shot=${shot != null}")
            val invocation = AssistantInvocation(shot, null, null, null, if (delay > 0) "tile" else "assist-intent")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (AssistantSettings.fullScreen) {
                    AssistantInvoker.launch(app, invocation)
                    finish()
                } else {
                    controller?.begin(invocation)
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.end()
        super.onDestroy()
    }
}
