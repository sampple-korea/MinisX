package com.minisx.app.service

import org.json.JSONObject

/**
 * What the agent is doing right now, in one short line, for the ongoing
 * notification / Android 16 Live Update: the tail of its reasoning while it
 * thinks, the command while a script runs, the file or page a tool works on,
 * the task a sub agent was handed.
 *
 * Pure (no Android types) so it is unit-tested; the tracker only stores it and
 * the notification only prints it.
 */
internal enum class LiveDetailKind { REASONING, COMMAND, TARGET, AGENT }

internal data class LiveDetail(val kind: LiveDetailKind, val text: String)

internal object LiveDetails {

    /** Longest line we keep; the collapsed notification row ellipsizes anyway. */
    const val MAX_CHARS = 140

    /**
     * Minimum gap between two reasoning excerpts. A model streams reasoning
     * token by token; the notification is re-posted about once a second, so a
     * faster excerpt would only cost string work.
     */
    const val REASONING_MIN_INTERVAL_MS = 700L

    private val whitespace = Regex("\\s+")
    private val markdownNoise = Regex("""(\*\*|__|`+|^#{1,6}\s+|^\s*[-*]\s+|^\s*\d+\.\s+)""", RegexOption.MULTILINE)

    /** Collapses whitespace and keeps at most [max] characters, cutting at a word boundary. */
    fun squash(text: String, max: Int = MAX_CHARS): String {
        val flat = text.replace(whitespace, " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.take(max - 1)
        val space = cut.lastIndexOf(' ')
        val head = if (space > max / 2) cut.substring(0, space) else cut
        return head.trimEnd(' ', ',', ';', ':', '.') + "…"
    }

    /**
     * The newest part of a reasoning stream: its last paragraph with the
     * markdown the models like to add (bold headings, bullets) removed, tail
     * kept when it is long. Null while there is nothing readable yet.
     */
    fun reasoningExcerpt(raw: String, max: Int = MAX_CHARS): String? {
        if (raw.isBlank()) return null
        val paragraph = raw.trimEnd().split(Regex("\n\\s*\n")).lastOrNull { it.isNotBlank() } ?: return null
        val plain = paragraph.replace(markdownNoise, "").replace(whitespace, " ").trim()
        if (plain.isEmpty()) return null
        if (plain.length <= max) return plain
        // Keep the END: that is what the model is thinking about now.
        val tail = plain.takeLast(max - 1)
        val space = tail.indexOf(' ')
        val body = if (space in 1..(max / 3)) tail.substring(space + 1) else tail
        return "…$body"
    }

    /**
     * The line for a tool call from its arguments, or null when the tool has
     * nothing worth showing beyond its title.
     */
    fun forTool(toolName: String, args: JSONObject): LiveDetail? {
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> args.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" } }

        return when (toolName) {
            "shell_execute", "exec_command" -> str("command", "cmd")?.let { cmd ->
                // First meaningful line: a heredoc or a long pipeline is noise in a notification.
                val line = cmd.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: cmd
                LiveDetail(LiveDetailKind.COMMAND, squash(line))
            }
            "apply_patch" -> str("input")?.let { com.minisx.app.tools.ApplyPatchTool.firstPath(it) }
                ?.let { LiveDetail(LiveDetailKind.TARGET, squash(shortPath(it))) }
            "file_read", "file_write", "file_edit", "read_image" ->
                str("path", "file_path")?.let { LiveDetail(LiveDetailKind.TARGET, squash(shortPath(it))) }
            "browser_use" -> {
                val action = str("action")
                val where = str("url", "selector", "text", "query")
                val line = listOfNotNull(action, where).joinToString(" ")
                line.takeIf { it.isNotEmpty() }?.let { LiveDetail(LiveDetailKind.TARGET, squash(it)) }
            }
            "web_search" -> str("query", "q")?.let { LiveDetail(LiveDetailKind.TARGET, squash(it)) }
            "subagent_task", "delegate_task" ->
                str("task", "description", "prompt")?.let { LiveDetail(LiveDetailKind.AGENT, squash(it)) }
            else -> null
        }
    }

    /** The line as shown: a command reads like a shell prompt. */
    fun display(detail: LiveDetail): String =
        if (detail.kind == LiveDetailKind.COMMAND) "$ " + detail.text else detail.text

    /** `/a/very/long/path/to/file.kt` → `…/to/file.kt`: the last two components say enough. */
    fun shortPath(path: String): String {
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 2) path.trimEnd('/') else "…/" + parts.takeLast(2).joinToString("/")
    }
}
