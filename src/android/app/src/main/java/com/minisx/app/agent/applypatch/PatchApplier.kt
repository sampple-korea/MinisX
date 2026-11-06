package com.minisx.app.agent.applypatch

/** What a patch leaves behind: the new text of each file it touches, null for one it removes. */
class PatchPlan(val files: LinkedHashMap<String, String?>, val summary: PatchSummary)

object PatchApplier {

    /**
     * Works the whole patch out in memory. [read] returns a file's text or null when it does not
     * exist. Nothing is written here: if any hunk does not fit, the patch fails as a whole and no file
     * is touched (Codex applies hunk by hunk and can stop half way; this does not).
     */
    fun plan(hunks: List<Hunk>, read: (String) -> String?): PatchPlan {
        val files = LinkedHashMap<String, String?>()
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val deleted = ArrayList<String>()
        fun current(path: String): String? = if (files.containsKey(path)) files[path] else read(path)

        for (hunk in hunks) {
            when (hunk) {
                is Hunk.AddFile -> {
                    files[hunk.path] = hunk.contents
                    added += hunk.path
                }
                is Hunk.DeleteFile -> {
                    if (current(hunk.path) == null) throw PatchException("Failed to delete file ${hunk.path}: it does not exist")
                    files[hunk.path] = null
                    deleted += hunk.path
                }
                is Hunk.UpdateFile -> {
                    val original = current(hunk.path)
                        ?: throw PatchException("Failed to read file to update ${hunk.path}: it does not exist")
                    val updated = update(original, hunk.path, hunk.chunks)
                    val target = hunk.movePath ?: hunk.path
                    files[target] = updated
                    if (target != hunk.path) files[hunk.path] = null
                    modified += target
                }
            }
        }
        return PatchPlan(files, PatchSummary(added, modified, deleted))
    }

    /** [original] with [chunks] applied, keeping the file's own line endings. */
    fun update(original: String, path: String, chunks: List<Chunk>): String {
        val source = SourceFile.parse(original)
        val replacements = computeReplacements(source.lineTexts(), path, chunks)
        source.applyReplacements(replacements)
        return source.contents()
    }

    private data class Replacement(val start: Int, val oldLength: Int, val newLines: List<String>)

    private fun computeReplacements(lines: List<String>, path: String, chunks: List<Chunk>): List<Replacement> {
        val replacements = ArrayList<Replacement>()
        var lineIndex = 0
        for (chunk in chunks) {
            for (context in chunk.changeContexts) {
                val at = SeekSequence.find(lines, listOf(context), lineIndex, false)
                    ?: throw PatchException("Failed to find context '$context' in $path")
                lineIndex = at + 1
            }
            if (chunk.oldLines.isEmpty()) {
                // Pure addition: at the end of the file.
                replacements += Replacement(lines.size, 0, chunk.newLines)
                continue
            }
            var pattern = chunk.oldLines
            var newSlice = chunk.newLines
            var found = SeekSequence.find(lines, pattern, lineIndex, chunk.isEndOfFile)
            // A trailing empty line stands for the newline at the end of the file: retry without it.
            if (found == null && pattern.last().isEmpty()) {
                pattern = pattern.dropLast(1)
                if (newSlice.lastOrNull()?.isEmpty() == true) newSlice = newSlice.dropLast(1)
                found = SeekSequence.find(lines, pattern, lineIndex, chunk.isEndOfFile)
            }
            val start = found ?: throw PatchException(
                "Failed to find expected lines in $path:\n" + chunk.oldLines.joinToString("\n"),
            )
            // Unchanged lines stay as they are in the file (they may differ in spacing from the patch's).
            var oldStart = 0
            var newStart = 0
            for ((oldContext, newContext) in chunk.contextIndices) {
                if (oldContext >= pattern.size || newContext >= newSlice.size) break
                if (oldStart != oldContext || newStart != newContext) {
                    replacements += Replacement(start + oldStart, oldContext - oldStart, newSlice.subList(newStart, newContext))
                }
                oldStart = oldContext + 1
                newStart = newContext + 1
            }
            if (oldStart != pattern.size || newStart != newSlice.size) {
                replacements += Replacement(start + oldStart, pattern.size - oldStart, newSlice.subList(newStart, newSlice.size))
            }
            lineIndex = start + pattern.size
        }
        return replacements.sortedBy { it.start }
    }

    /** A file as lines with the ending each had, so an edit keeps CRLF files CRLF. */
    private class SourceFile(private var lines: List<Line>, private val preferred: String) {
        class Line(val text: String, val ending: String?)

        fun lineTexts(): List<String> = lines.map { it.text }

        fun applyReplacements(replacements: List<Replacement>) {
            val out = ArrayList<Line>()
            var sourceIndex = 0
            for (r in replacements) {
                while (sourceIndex < r.start && sourceIndex < lines.size) out += lines[sourceIndex++]
                sourceIndex += r.oldLength
                r.newLines.forEach { out += Line(it, preferred) }
            }
            while (sourceIndex < lines.size) out += lines[sourceIndex++]
            lines = out.map { if (it.ending == null) Line(it.text, preferred) else it }
        }

        fun contents(): String = buildString { lines.forEach { append(it.text).append(it.ending ?: "") } }

        companion object {
            fun parse(text: String): SourceFile {
                val lines = ArrayList<Line>()
                var preferred: String? = null
                var lineStart = 0
                var i = 0
                while (i < text.length) {
                    val ending = when {
                        text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> "\r\n"
                        text[i] == '\r' -> "\r"
                        text[i] == '\n' -> "\n"
                        else -> { i++; continue }
                    }
                    if (preferred == null) preferred = ending
                    lines += Line(text.substring(lineStart, i), ending)
                    i += ending.length
                    lineStart = i
                }
                if (lineStart < text.length) lines += Line(text.substring(lineStart), null)
                return SourceFile(lines, preferred ?: "\n")
            }
        }
    }
}

/** Finding a run of lines in a file, gradually less strict (Codex `seek_sequence`). */
object SeekSequence {

    fun find(lines: List<String>, pattern: List<String>, start: Int, endOfFile: Boolean): Int? {
        if (pattern.isEmpty()) return start
        if (pattern.size > lines.size) return null
        val searchStart = if (endOfFile && lines.size >= pattern.size) maxOf(lines.size - pattern.size, start) else start
        val last = lines.size - pattern.size
        val comparisons: List<(String, String) -> Boolean> = listOf(
            { a, b -> a == b },
            { a, b -> a.trimEnd() == b.trimEnd() },
            { a, b -> a.trim() == b.trim() },
            { a, b -> normalise(a) == normalise(b) },
        )
        for (same in comparisons) {
            for (i in searchStart..last) {
                if (pattern.indices.all { same(lines[i + it], pattern[it]) }) return i
            }
        }
        return null
    }

    /** Typographic dashes, quotes and spaces folded to the plain character a patch would use. */
    private fun normalise(s: String): String = buildString {
        for (c in s.trim()) {
            append(
                when (c) {
                    '‐', '‑', '‒', '–', '—', '―', '−' -> '-'
                    '‘', '’', '‚', '‛' -> '\''
                    '“', '”', '„', '‟' -> '"'
                    ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
                    ' ', ' ', ' ', ' ', '　' -> ' '
                    else -> c
                },
            )
        }
    }
}
