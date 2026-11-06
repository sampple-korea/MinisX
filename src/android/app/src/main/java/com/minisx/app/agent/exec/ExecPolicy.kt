package com.minisx.app.agent.exec

import com.minisx.app.agent.ToolOutputBudget

/**
 * The timing and wording rules of Codex's unified exec (`exec_command` / `write_stdin`,
 * `core/src/unified_exec` and `tools/handlers/shell_spec.rs`), apart from any process handling so they
 * are unit-tested.
 *
 * A command that finishes inside the wait comes back with its output and exit code at once. One that
 * does not comes back with what it printed so far and a session id; the model then polls it or writes
 * to its stdin with the same id.
 */
object ExecPolicy {
    const val MIN_YIELD_MS = 250L
    const val MAX_YIELD_MS = 30_000L
    const val DEFAULT_YIELD_MS = 10_000L

    /** `write_stdin` with text to write waits only briefly for the reply. */
    const val WRITE_DEFAULT_YIELD_MS = 250L

    /** An empty `write_stdin` is a poll: it waits for more output, up to five minutes. */
    const val POLL_MIN_YIELD_MS = 5_000L
    const val POLL_MAX_YIELD_MS = 300_000L

    const val DEFAULT_MAX_OUTPUT_TOKENS = 10_000

    /** Sessions one conversation may have alive at once. */
    const val MAX_SESSIONS = 8

    /** A session nobody has touched for this long is killed. */
    const val IDLE_LIMIT_MS = 30 * 60_000L

    fun execYieldMs(requested: Long?): Long = (requested ?: DEFAULT_YIELD_MS).coerceIn(MIN_YIELD_MS, MAX_YIELD_MS)

    fun writeYieldMs(requested: Long?, hasChars: Boolean): Long =
        if (hasChars) (requested ?: WRITE_DEFAULT_YIELD_MS).coerceIn(MIN_YIELD_MS, MAX_YIELD_MS)
        else (requested ?: POLL_MIN_YIELD_MS).coerceIn(POLL_MIN_YIELD_MS, POLL_MAX_YIELD_MS)

    fun maxOutputTokens(requested: Long?): Int =
        (requested ?: DEFAULT_MAX_OUTPUT_TOKENS.toLong()).coerceIn(1, 100_000).toInt()

    /** What the model reads back. [sessionId] is set only while the process is still running. */
    fun format(wallMs: Long, sessionId: Int?, exitCode: Int?, output: String, maxTokens: Int): String {
        val seconds = String.format(java.util.Locale.US, "%.1f", wallMs / 1000.0)
        val original = ToolOutputBudget.approxTokens(output)
        val shown = ToolOutputBudget.truncateMiddle(output, maxTokens * 4)
        return buildString {
            append("Wall time: ").append(seconds).append(" seconds\n")
            when {
                exitCode != null -> append("Process exited with code ").append(exitCode).append('\n')
                sessionId != null -> append("Process running with session ID ").append(sessionId).append('\n')
            }
            append("Original token count: ").append(original).append('\n')
            append("Output:\n").append(shown)
        }
    }
}

/**
 * Output waiting to be handed to the model. Appended to by a reader thread and drained by the tool
 * call, so it is synchronized. Output nobody has read yet is capped; past the cap the middle is cut.
 */
class ExecOutputBuffer(private val maxUnreadChars: Int = 1_000_000) {
    private val text = StringBuilder()
    private var omitted = 0L

    @Volatile var lastWriteAt: Long = 0L
        private set

    @Synchronized fun append(chunk: CharSequence, now: Long = System.currentTimeMillis()) {
        if (chunk.isEmpty()) return
        text.append(chunk)
        lastWriteAt = now
        if (text.length > maxUnreadChars) {
            // Keep the beginning and the end of what is waiting; the middle is the least useful part.
            val keep = maxUnreadChars / 2
            omitted += (text.length - 2 * keep).toLong()
            val head = text.substring(0, keep)
            val tail = text.substring(text.length - keep)
            text.setLength(0)
            text.append(head).append(tail)
        }
    }

    /** Everything unread, now read. */
    @Synchronized fun drain(): String {
        val out = if (omitted > 0) {
            val half = text.length / 2
            text.substring(0, half) + "\n…$omitted characters of output were dropped…\n" + text.substring(half)
        } else {
            text.toString()
        }
        text.setLength(0)
        omitted = 0
        return out
    }

    @Synchronized fun isEmpty(): Boolean = text.isEmpty()
}
