package com.minisx.app.agent

/**
 * Caps what one tool result may cost the model, the way Codex does when it records a tool output
 * into the history it samples from (`codex-rs/core/src/context_manager/history.rs`,
 * `utils/output-truncation`):
 *
 *  - the budget is 10,000 tokens (the default of every bundled model entry) plus a 20 % allowance
 *    for serialization, with a token being 4 UTF-8 bytes;
 *  - an output over the budget keeps its beginning and its end, half the budget each, cut on
 *    character boundaries, with a `…N tokens truncated…` marker between them.
 *
 * Unlike Codex, which drops the middle, the full text is saved to disk (when [capped] is given a
 * way to do so) and the model is told where, so it can read the part it needs with `file_read`.
 *
 * Pure (no Android types): the caller supplies the writer, and tests run on the JVM.
 */
object ToolOutputBudget {

    const val TOKEN_LIMIT = 10_000
    const val ALLOWANCE = 1.2
    private const val BYTES_PER_TOKEN = 4

    /** Bytes one result may keep: 10,000 tokens x 1.2 x 4. */
    const val MAX_BYTES: Int = (TOKEN_LIMIT * ALLOWANCE * BYTES_PER_TOKEN).toInt()

    /**
     * Tools that bound their own output with an explicit window the model asked for (`file_read`
     * takes `max_length` up to 80,000 characters). Capping those again would fight the window, and
     * reading the offloaded copy back would be cut again.
     */
    val SELF_BOUNDED = setOf("file_read", "read_image")

    fun approxTokens(text: String): Int = (utf8Length(text) + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN

    fun fits(text: String, maxBytes: Int = MAX_BYTES): Boolean = utf8Length(text) <= maxBytes

    /**
     * [text] cut to at most [maxBytes] of retained text (marker and note excluded, as in Codex), or
     * [text] itself when it already fits.
     */
    fun truncateMiddle(text: String, maxBytes: Int = MAX_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        val left = maxBytes / 2
        val right = maxBytes - left
        val removedFrom = floorBoundary(bytes, left)
        val removedTo = ceilBoundary(bytes, bytes.size - right)
        val removedTokens = (bytes.size - maxBytes + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN
        val head = String(bytes, 0, removedFrom, Charsets.UTF_8)
        val tail = String(bytes, removedTo, bytes.size - removedTo, Charsets.UTF_8)
        return head + "…$removedTokens tokens truncated…" + tail
    }

    /**
     * What goes into the model's history for [text] returned by [toolName]. Results of tools that
     * bound themselves, and results already replaced by an offload stub, pass through.
     *
     * [save] writes the full text somewhere the model can read and returns that path (null when it
     * could not); it is called only for an output that is actually cut.
     */
    fun capped(
        toolName: String,
        text: String,
        maxBytes: Int = MAX_BYTES,
        alreadyOffloaded: (String) -> Boolean = { false },
        save: (String) -> String? = { null },
    ): String {
        if (toolName in SELF_BOUNDED || fits(text, maxBytes) || alreadyOffloaded(text)) return text
        val cut = truncateMiddle(text, maxBytes)
        val path = save(text)
        val note = if (path != null) {
            "\n\n[Output truncated: ${approxTokens(text)} tokens in all, the middle was cut. " +
                "The full output is saved at $path; read the part you need with file_read (offset, lines, max_length).]"
        } else {
            "\n\n[Output truncated: ${approxTokens(text)} tokens in all, the middle was cut.]"
        }
        return cut + note
    }

    private fun utf8Length(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    private fun isBoundary(bytes: ByteArray, index: Int): Boolean =
        index <= 0 || index >= bytes.size || (bytes[index].toInt() and 0xC0) != 0x80

    private fun floorBoundary(bytes: ByteArray, index: Int): Int {
        var i = index.coerceIn(0, bytes.size)
        while (!isBoundary(bytes, i)) i--
        return i
    }

    private fun ceilBoundary(bytes: ByteArray, index: Int): Int {
        var i = index.coerceIn(0, bytes.size)
        while (!isBoundary(bytes, i)) i++
        return i
    }
}
