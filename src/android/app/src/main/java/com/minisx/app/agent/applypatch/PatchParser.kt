package com.minisx.app.agent.applypatch

/** Text of a patch to its hunks. Lenient the way Codex is: a `<<EOF` heredoc around it is accepted. */
object PatchParser {

    const val BEGIN = "*** Begin Patch"
    const val END = "*** End Patch"
    private const val ADD = "*** Add File: "
    private const val DELETE = "*** Delete File: "
    private const val UPDATE = "*** Update File: "
    private const val MOVE_TO = "*** Move to: "
    private const val EOF_MARKER = "*** End of File"
    private const val CONTEXT = "@@ "
    private const val EMPTY_CONTEXT = "@@"

    fun parse(patch: String): List<Hunk> {
        val lines = patch.trim().lines()
        return Machine().run(boundaries(lines))
    }

    private fun boundaries(lines: List<String>): List<String> {
        strictBoundaries(lines)?.let { error ->
            val first = lines.firstOrNull()
            val last = lines.lastOrNull()
            if (lines.size >= 4 && first != null && last != null &&
                (first == "<<EOF" || first == "<<'EOF'" || first == "<<\"EOF\"") && last.endsWith("EOF")
            ) {
                val inner = lines.subList(1, lines.size - 1)
                strictBoundaries(inner)?.let { throw PatchException(it) }
                return inner
            }
            throw PatchException(error)
        }
        return lines
    }

    /** Null when the first line is `*** Begin Patch` and the last `*** End Patch`, else the complaint. */
    private fun strictBoundaries(lines: List<String>): String? {
        val first = lines.firstOrNull()?.trim()
        val last = lines.lastOrNull()?.trim()
        return when {
            first != BEGIN -> "invalid patch: The first line of the patch must be '*** Begin Patch'"
            last != END -> "invalid patch: The last line of the patch must be '*** End Patch'"
            else -> null
        }
    }

    private class ChunkBuilder(val changeContexts: MutableList<String>) {
        val old = ArrayList<String>()
        val new = ArrayList<String>()
        val contextIndices = ArrayList<Pair<Int, Int>>()
        var endOfFile = false
        val isEmpty get() = old.isEmpty() && new.isEmpty()

        fun context(line: String) {
            contextIndices += old.size to new.size
            old += line
            new += line
        }

        fun build() = Chunk(changeContexts.toList(), old.toList(), new.toList(), contextIndices.toList(), endOfFile)
    }

    private class UpdateBuilder(val path: String, val line: Int) {
        var movePath: String? = null
        val chunks = ArrayList<ChunkBuilder>()
    }

    private enum class Mode { STARTED, ADD, DELETE, UPDATE, ENDED }

    private class Machine {
        private val hunks = ArrayList<Hunk>()
        private var mode = Mode.STARTED
        private var lineNumber = 0
        private var add: Pair<String, StringBuilder>? = null
        private var update: UpdateBuilder? = null

        fun run(lines: List<String>): List<Hunk> {
            // The boundaries were checked: the first line is the begin marker, the last the end marker.
            for (raw in lines.drop(1)) {
                lineNumber += 1
                if (mode == Mode.ENDED) break
                process(raw.removeSuffix("\r"))
            }
            if (mode != Mode.ENDED) throw PatchException("invalid patch: The last line of the patch must be '*** End Patch'")
            return hunks
        }

        private fun hunkError(message: String): Nothing =
            throw PatchException("invalid hunk at line ${lineNumber + 1}, $message")

        private fun flush(nextLine: String) {
            add?.let { (path, contents) -> hunks += Hunk.AddFile(path, contents.toString()) }
            add = null
            update?.let { u ->
                if (u.chunks.isEmpty()) {
                    throw PatchException("invalid hunk at line ${u.line + 1}, Update file hunk for path '${u.path}' is empty")
                }
                if (u.chunks.last().isEmpty) {
                    if (nextLine == END) hunkError("Update hunk does not contain any lines")
                    hunkError("Unexpected line found in update hunk: '$nextLine'. Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)")
                }
                hunks += Hunk.UpdateFile(u.path, u.movePath, u.chunks.map { it.build() })
            }
            update = null
        }

        /** A hunk header or the end marker: closes the hunk being read and starts the next. */
        private fun header(trimmed: String): Boolean {
            when {
                trimmed == END -> { flush(trimmed); mode = Mode.ENDED }
                trimmed.startsWith(ADD) -> { flush(trimmed); add = trimmed.removePrefix(ADD) to StringBuilder(); mode = Mode.ADD }
                trimmed.startsWith(DELETE) -> {
                    flush(trimmed)
                    hunks += Hunk.DeleteFile(trimmed.removePrefix(DELETE))
                    mode = Mode.DELETE
                }
                trimmed.startsWith(UPDATE) -> { flush(trimmed); update = UpdateBuilder(trimmed.removePrefix(UPDATE), lineNumber); mode = Mode.UPDATE }
                else -> return false
            }
            return true
        }

        private fun notAHeader(trimmed: String): Nothing =
            hunkError("'$trimmed' is not a valid hunk header. Valid hunk headers: '*** Add File: {path}', '*** Delete File: {path}', '*** Update File: {path}'")

        private fun process(line: String) {
            val trimmed = line.trim()
            when (mode) {
                Mode.STARTED, Mode.DELETE -> if (!header(trimmed)) notAHeader(trimmed)
                Mode.ADD -> {
                    if (header(trimmed)) return
                    if (line.startsWith("+")) add?.second?.append(line.substring(1))?.append('\n') else notAHeader(trimmed)
                }
                Mode.UPDATE -> processUpdate(line)
                Mode.ENDED -> Unit
            }
        }

        private fun processUpdate(line: String) {
            val updateLine = line.trimEnd()
            if (header(updateLine)) return
            val u = update ?: return
            val chunks = u.chunks
            if (chunks.lastOrNull()?.endOfFile == true) {
                if (updateLine.isEmpty()) return
                if (updateLine != EMPTY_CONTEXT && !updateLine.startsWith(CONTEXT)) {
                    hunkError("Expected update hunk to start with a @@ context marker, got: '$line'")
                }
            }
            if (chunks.isEmpty() && u.movePath == null && updateLine.startsWith(MOVE_TO)) {
                u.movePath = updateLine.removePrefix(MOVE_TO)
                return
            }
            if (updateLine == EMPTY_CONTEXT || updateLine.startsWith(CONTEXT)) {
                val context = updateLine.removePrefix(CONTEXT).takeIf { updateLine != EMPTY_CONTEXT }
                val open = chunks.lastOrNull()
                // A second `@@` before any change line adds to the contexts of the chunk being opened.
                if (open != null && open.isEmpty && !open.endOfFile) {
                    if (context != null) open.changeContexts += context
                } else {
                    chunks += ChunkBuilder(if (context != null) mutableListOf(context) else mutableListOf())
                }
                return
            }
            if (updateLine == EOF_MARKER) {
                if (chunks.lastOrNull()?.isEmpty != false) hunkError("Update hunk does not contain any lines")
                chunks.last().endOfFile = true
                return
            }
            fun chunk(): ChunkBuilder { if (chunks.isEmpty()) chunks += ChunkBuilder(mutableListOf()); return chunks.last() }
            when {
                line.isEmpty() -> chunk().context("")
                line.startsWith(" ") -> chunk().context(line.substring(1))
                line.startsWith("+") -> chunk().new += line.substring(1)
                line.startsWith("-") -> chunk().old += line.substring(1)
                else -> hunkError("Unexpected line found in update hunk: '$line'. Every line should start with ' ' (context line), '+' (added line), or '-' (removed line)")
            }
        }
    }
}
