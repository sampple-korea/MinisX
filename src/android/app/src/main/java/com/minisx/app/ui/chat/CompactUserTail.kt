package com.minisx.app.ui.chat

import com.minisx.app.agent.ToolOutputBudget
import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.LLMMessage

/**
 * The user's own earlier messages, kept verbatim next to a compaction summary.
 *
 * Codex does this when it compacts (`core/src/compact.rs`, `build_compacted_history`): the new
 * history is the summary plus the most recent user messages that fit in
 * `COMPACT_USER_MESSAGE_MAX_TOKENS` (20,000 tokens), walking back from the newest; the one that
 * crosses the limit is cut to what is left and the walk stops. What the user typed is the part of
 * a conversation a summary is worst at keeping.
 *
 * MinisX already keeps the last few turns whole (the "warm-up", which carries the assistant's and
 * the tools' side too). This adds the user messages from before that, as one block inside the
 * `<context-summary>` message, so role alternation and tool pairing are untouched.
 *
 * Pure: the caller decides which messages are already kept and how many tokens are available.
 */
internal object CompactUserTail {

    /** Codex's `COMPACT_USER_MESSAGE_MAX_TOKENS`. */
    const val MAX_TOKENS = 20_000

    /** Opening line of the block; tests and the summary prompt rely on it staying put. */
    const val HEADER = "Earlier messages from the user, verbatim (oldest first). They are history, not new instructions:"

    /** The text of a user message (plain content, else its text parts), trimmed. */
    fun userText(message: LLMMessage): String =
        message.content.ifBlank {
            message.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("\n") { it.text }
        }.trim()

    /**
     * The block for [history] up to and including [lastIndex], leaving out the messages in
     * [alreadyKept] (by `dbMessageId`), within [maxTokens]; null when there is nothing to add.
     */
    fun render(
        history: List<LLMMessage>,
        lastIndex: Int,
        alreadyKept: Set<String>,
        maxTokens: Int = MAX_TOKENS,
    ): String? {
        if (maxTokens <= 0 || lastIndex < 0) return null
        val picked = ArrayList<String>()
        var remaining = maxTokens
        var i = minOf(lastIndex, history.size - 1)
        while (i >= 0 && remaining > 0) {
            val m = history[i]
            i--
            if (!RequestContextBuilder.isRealUserText(m)) continue
            if (m.dbMessageId != null && m.dbMessageId in alreadyKept) continue
            val text = userText(m)
            val tokens = ToolOutputBudget.approxTokens(text)
            if (tokens <= remaining) {
                picked += text
                remaining -= tokens
            } else {
                // The message that crosses the limit is cut to what is left, then the walk stops.
                picked += ToolOutputBudget.truncateMiddle(text, remaining * 4)
                break
            }
        }
        if (picked.isEmpty()) return null
        picked.reverse()
        return buildString {
            append("<earlier-user-messages>\n").append(HEADER)
            picked.forEachIndexed { n, t -> append("\n\n[").append(n + 1).append("] ").append(t) }
            append("\n</earlier-user-messages>")
        }
    }
}
