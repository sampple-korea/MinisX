package com.minisx.app.ui.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-mc-live] Runs one spoken request through this chat's agent, the way a typed
 * message would run (same tools, permissions and history), and returns the final
 * answer for the voice to read out. The request and the answer land in the chat
 * like any other turn, so what the voice did can be read afterwards.
 */
internal suspend fun ChatViewModel.runLiveTask(request: String): String {
    when (val outcome = submitPrompt(request)) {
        is ChatViewModel.SubmitOutcome.Rejected -> return "The agent could not take the request (${outcome.reason})."
        else -> Unit
    }
    // A turn that started, or a queued one that starts when the running turn ends:
    // wait for the chat to go idle, and once more in case a queued prompt drained into a new turn.
    val finished = withTimeoutOrNull(LIVE_TASK_TIMEOUT_MS) {
        withTimeoutOrNull(LIVE_START_WAIT_MS) { isStreaming.first { it } }
        isStreaming.first { !it }
        delay(LIVE_DRAIN_GRACE_MS)
        if (isStreaming.value) isStreaming.first { !it }
        true
    }
    val reply = messages.value.lastOrNull { it.role == "assistant" && !it.isStreaming }
    return when {
        finished == null -> "The task is still running in the chat; check there for the result."
        reply == null -> "The agent finished without a reply."
        !reply.error.isNullOrBlank() -> "The agent hit an error: ${reply.error}"
        reply.content.isBlank() -> "The agent finished; the result is in the chat."
        else -> reply.content
    }
}

private const val LIVE_TASK_TIMEOUT_MS = 20 * 60_000L
private const val LIVE_START_WAIT_MS = 5_000L
private const val LIVE_DRAIN_GRACE_MS = 1_500L
