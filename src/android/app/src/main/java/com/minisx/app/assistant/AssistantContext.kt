package com.minisx.app.assistant

/**
 * The text side of an assistant invocation: what is said about the screen, kept pure so it can be
 * tested on the JVM.
 */
object AssistantContext {
    const val MAX_SCREEN_CHARS = 6_000
    private const val MIN_LINE = 1

    /** Visible screen lines to one block: trimmed, without repeats, cut at [maxChars]. */
    fun composeScreenText(lines: List<String>, maxChars: Int = MAX_SCREEN_CHARS): String? {
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        var total = 0
        for (raw in lines) {
            val line = raw.replace(Regex("\\s+"), " ").trim()
            if (line.length < MIN_LINE || line in seen) continue
            if (total + line.length + 1 > maxChars) break
            seen += line
            kept += line
            total += line.length + 1
        }
        return kept.joinToString("\n").ifEmpty { null }
    }

    /**
     * The text file attached next to the screenshot. Starts with what it is, so the model does not
     * mistake the screen of the app the user was in for MinisX itself.
     */
    fun screenDocument(inv: AssistantInvocation, hasImage: Boolean): String = buildString {
        appendLine("The user opened you as their phone assistant. This is the screen they were looking at just before, NOT the MinisX chat.")
        inv.packageName?.let { appendLine("App: $it") }
        inv.webUri?.let { appendLine("Page: $it") }
        if (hasImage) appendLine("A screenshot of it is attached as an image.")
        else appendLine("No screenshot is available (the app may block capture, or the model takes no images).")
        if (inv.screenText.isNullOrBlank()) appendLine("Text on screen: none could be read.")
        else {
            appendLine("Text on screen:")
            appendLine(inv.screenText)
        }
    }.trimEnd()

    /** Longest side a screenshot is sent at, and the size it fits into. */
    const val MAX_SIDE = 1600

    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
