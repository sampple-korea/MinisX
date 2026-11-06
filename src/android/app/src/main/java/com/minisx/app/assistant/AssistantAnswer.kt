package com.minisx.app.assistant

/**
 * What the overlay shows of the chat: the reply to the last thing the user said. Pure, so it is
 * tested on the JVM.
 */
object AssistantAnswer {

    data class Item(val role: String, val content: String, val error: String? = null)

    /**
     * The newest non-empty assistant text after the user's last message, else that turn's error, else
     * null (nothing to show yet).
     */
    fun latest(items: List<Item>): String? {
        val lastUser = items.indexOfLast { it.role == "user" }
        val turn = if (lastUser >= 0) items.drop(lastUser + 1) else items
        turn.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.let { return it.content.trim() }
        return turn.lastOrNull { !it.error.isNullOrBlank() }?.error
    }

    /** The overlay keeps its "working" state while a turn streams, and briefly while one is starting. */
    fun working(streaming: Boolean, haveText: Boolean, sinceSubmitMs: Long, graceMs: Long = 4_000L): Boolean =
        streaming || (!haveText && sinceSubmitMs < graceMs)
}
