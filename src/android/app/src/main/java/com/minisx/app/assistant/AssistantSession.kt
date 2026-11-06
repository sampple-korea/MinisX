package com.minisx.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import com.minisx.app.logging.AppLogger
import kotlin.concurrent.thread

/**
 * One invocation of the assistant. Gathers what the system offers about the screen (a screenshot if
 * the user allowed "use screenshot" for the assistant, the view structure, the page address), adds
 * its own screenshot when the system gave none, then hides itself and opens MinisX with it all.
 * It never shows a window of its own, so nothing of MinisX is in the picture.
 */
class AssistantSession(private val ctx: Context) : VoiceInteractionSession(ctx) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var systemShot: Bitmap? = null
    @Volatile private var ownShot: java.io.File? = null
    @Volatile private var ownDone = false
    private var lines: List<String> = emptyList()
    private var packageName: String? = null
    private var webUri: String? = null
    private var needAssist = false
    private var needShot = false
    private var gotAssist = false
    private var gotShot = false
    private var launched = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launched = false
        systemShot = null; ownShot = null; ownDone = false
        lines = emptyList(); packageName = null; webUri = null
        gotAssist = false; gotShot = false
        needAssist = (showFlags and SHOW_WITH_ASSIST) != 0
        needShot = (showFlags and SHOW_WITH_SCREENSHOT) != 0 && AssistantSettings.screenshot
        AssistantSettings.init(ctx)
        // Our own capture starts at once, while the previous app is still the only thing on screen.
        if (AssistantSettings.screenshot) {
            thread(name = "assistant-capture", isDaemon = true) {
                ownShot = runCatching { ScreenCapture.captureOwn(ctx, AssistantInvoker.shotDir(ctx)) }.getOrNull()
                ownDone = true
                main.post { maybeLaunch(force = false) }
            }
        } else ownDone = true
        main.postDelayed({ maybeLaunch(force = true) }, WAIT_MS)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        gotAssist = true
        if (structure != null) {
            packageName = runCatching { structure.activityComponent?.packageName }.getOrNull()
            if (AssistantSettings.screenText) lines = collectText(structure)
        }
        webUri = runCatching { content?.webUri?.toString() }.getOrNull()
        maybeLaunch(force = false)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        gotShot = true
        if (AssistantSettings.screenshot) systemShot = screenshot
        maybeLaunch(force = false)
    }

    private fun maybeLaunch(force: Boolean) {
        if (launched) return
        val ready = (!needAssist || gotAssist) && (!needShot || gotShot) && ownDoneOrNotNeeded()
        if (!ready && !force) return
        launched = true
        val dir = AssistantInvoker.shotDir(ctx)
        val shot = systemShot?.let { ScreenCapture.save(it, dir) } ?: ownShot
        val invocation = AssistantInvocation(
            screenshot = shot,
            screenText = if (AssistantSettings.screenText) AssistantContext.composeScreenText(lines) else null,
            packageName = packageName?.takeIf { it != ctx.packageName },
            webUri = webUri,
            source = "session",
        )
        AppLogger.info(TAG, "invocation: shot=${shot != null} text=${invocation.screenText?.length ?: 0} pkg=${invocation.packageName}")
        ScreenCapture.clean(dir)
        runCatching { startAssistantActivity(AssistantInvoker.intent(ctx, invocation)) }
            .onFailure { AppLogger.warning(TAG, "could not start MinisX: ${it.message}") }
        hide()
    }

    private fun ownDoneOrNotNeeded() = ownDone || systemShot != null

    /** Text a person can see, depth first, in reading order. */
    private fun collectText(structure: AssistStructure): List<String> {
        val out = ArrayList<String>()
        var budget = MAX_NODES
        fun walk(node: AssistStructure.ViewNode) {
            if (budget-- <= 0 || node.visibility != View.VISIBLE) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            if (window.title?.toString()?.contains(ctx.packageName) == true) continue
            walk(window.rootViewNode)
        }
        return out
    }

    companion object {
        private const val TAG = "AssistantSession"
        private const val WAIT_MS = 1_800L
        private const val MAX_NODES = 4_000
    }
}
