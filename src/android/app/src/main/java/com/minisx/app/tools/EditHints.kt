package com.minisx.app.tools

/**
 * Helpers that make `file_edit` forgiving where the intent is clear and specific where it is not.
 * Pure, so each rule is unit-tested.
 */
internal object EditHints {

    /** The line-number prefix `file_read` puts on every line: right-aligned number, then a tab. */
    private val NUMBERED = Regex("^ *\\d+\\t")

    /** [text] without `file_read`'s line numbers when every non-blank line carries one; else null. */
    fun stripLineNumbers(text: String): String? {
        val lines = text.split("\n")
        val nonBlank = lines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty() || !nonBlank.all { NUMBERED.containsMatchIn(it) }) return null
        return lines.joinToString("\n") { it.replaceFirst(NUMBERED, "") }
    }

    /** 1-based line numbers where [needle] starts (at most [max]). */
    fun matchLines(content: String, needle: String, max: Int = 5): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var from = 0
        while (out.size < max) {
            val i = content.indexOf(needle, from)
            if (i < 0) break
            out += lineOf(content, i)
            from = i + needle.length.coerceAtLeast(1)
        }
        return out
    }

    fun lineOf(content: String, index: Int): Int {
        var n = 1
        for (i in 0 until index.coerceAtMost(content.length)) if (content[i] == '\n') n++
        return n
    }

    /** Whitespace squeezed: runs of blanks become one space, ends trimmed. */
    private fun squeeze(s: String) = s.trim().replace(Regex("[ \\t]+"), " ")

    /**
     * Why [oldString] was not found, as one sentence for the model: the place where it matches when blanks are
     * ignored, else the closest line of the file, else just the instruction to read the file again.
     */
    fun notFoundHint(content: String, oldString: String): String {
        val fileLines = content.split("\n")
        val oldLines = oldString.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (oldLines.isNotEmpty()) {
            val want = oldLines.map { squeeze(it) }
            for (start in 0..(fileLines.size - oldLines.size)) {
                if (oldLines.indices.all { squeeze(fileLines[start + it].trimEnd('\r')) == want[it] }) {
                    return "It matches at line ${start + 1} when indentation and trailing spaces are ignored: copy those lines exactly as the file has them " +
                        "(tabs vs spaces, indentation), without file_read's line-number prefix."
                }
            }
            // Closest single line to the first line of old_string.
            val first = squeeze(oldLines.first())
            if (first.length >= 4) {
                var best = -1
                var bestScore = 0.0
                for ((i, line) in fileLines.withIndex()) {
                    val score = similarity(first, squeeze(line))
                    if (score > bestScore) { bestScore = score; best = i }
                }
                if (best >= 0 && bestScore >= 0.6) {
                    return "The closest line is line ${best + 1}: `${fileLines[best].trimEnd('\r').take(160)}`. file_read that area and copy the text exactly."
                }
            }
        }
        return "Read the file again (file_read) and copy the text to replace exactly, including indentation; it may have changed."
    }

    /** 0..1: the share of the longer string's characters that the shorter one keeps in order (a cheap, edit-distance-like score). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val (s, l) = if (a.length <= b.length) a to b else b to a
        // Longest common subsequence length, two rows.
        var prev = IntArray(s.length + 1)
        var cur = IntArray(s.length + 1)
        for (c in l) {
            for (j in 1..s.length) cur[j] = if (s[j - 1] == c) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            val t = prev; prev = cur; cur = t
        }
        return prev[s.length].toDouble() / l.length
    }

    /** A few numbered lines around the changed region of [content], so the model need not read the file again. */
    fun snippet(content: String, startLine: Int, changedLines: Int, context: Int = 2, maxLines: Int = 14): String {
        val lines = content.split("\n")
        val from = (startLine - context).coerceAtLeast(1)
        val to = minOf(lines.size, startLine + changedLines - 1 + context, from + maxLines - 1)
        return (from..to).joinToString("\n") { n -> String.format(java.util.Locale.US, "%6d\t%s", n, lines[n - 1].trimEnd('\r')) }
    }
}
