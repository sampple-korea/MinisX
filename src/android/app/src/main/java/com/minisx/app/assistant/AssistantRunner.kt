package com.minisx.app.assistant

import android.content.Context
import com.minisx.app.R
import com.minisx.app.debug.HeadlessChatRunner
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Runs what the user says to the assistant overlay in an ordinary MinisX chat, with no MinisX screen:
 * the same chat view model the app uses, so the conversation is stored, the model and tools are the
 * user's own, and "open in app" continues it. The first message takes the screen of the invocation.
 *
 * Anything that needs the app's own dialogs (a permission or approval, a system permission, a settings
 * trip) cannot be answered from an overlay, so the run hands over to the app at that moment.
 */
class AssistantRunner(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val ui: Ui,
) {
    interface Ui {
        /** [text] is the newest reply (or null); [working] while a turn is under way. */
        fun answer(text: String?, working: Boolean)
        fun note(resId: Int)
        /** Continue in the app, at [sessionId] when the chat exists. */
        fun openApp(sessionId: String?)
    }

    private var invocation: AssistantInvocation? = null
    private var vm: ChatViewModel? = null
    private val draftId = "__new__" + UUID.randomUUID()
    private var watch: Job? = null

    fun start(inv: AssistantInvocation?) { invocation = inv }

    val sessionId: String? get() = vm?.currentSessionId?.takeIf { !it.startsWith("__new__") }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch { run(t) }
    }

    private suspend fun run(text: String) {
        ui.answer(null, true)
        val v = vm ?: HeadlessChatRunner.viewModelFor(ctx, draftId).also { created ->
            vm = created
            invocation?.let(created::setAssistantInvocation)
        }
        val ready = withContext(Dispatchers.Default) { withTimeoutOrNull(5_000L) { v.activeEntryId.first { it != null } } }
        if (ready == null) {
            ui.note(R.string.assistant_overlay_no_model)
            ui.answer(null, false)
            return
        }
        v.sendMessage(text)
        watch(v)
    }

    private fun watch(v: ChatViewModel) {
        watch?.cancel()
        val startedAt = System.currentTimeMillis()
        watch = scope.launch {
            launch {
                combine(v.messages, v.isStreaming) { m, s -> m to s }.collect { (messages, streaming) ->
                    val text = AssistantAnswer.latest(messages.map { AssistantAnswer.Item(it.role, it.content, it.error) })
                    ui.answer(text, AssistantAnswer.working(streaming, text != null, System.currentTimeMillis() - startedAt))
                }
            }
            // Dialogs live in the app: an approval, a system permission or a settings trip moves the run there.
            launch {
                combine(
                    OffloadPermissionManager.pendingRequest,
                    OffloadPermissionManager.pendingSettingsGate,
                    OffloadPermissionManager.pendingAndroidPermission,
                ) { a, b, c -> a != null || b != null || c != null }.first { it }
                ui.note(R.string.assistant_overlay_need_app)
                ui.openApp(v.currentSessionId.takeIf { !it.startsWith("__new__") })
            }
        }
    }

    fun stop() { watch?.cancel(); watch = null }
}
