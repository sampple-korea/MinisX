package com.minisx.app.agent

import java.io.File

/**
 * Project instructions the user keeps in an `AGENTS.md` file, read the way Codex reads them
 * (openai/codex, core/src/agents_md.rs): in each directory `AGENTS.override.md` wins over `AGENTS.md`,
 * the user-wide file comes first, the files of the project (the chat's workspace) follow after a
 * separator, and the total is cut to a byte budget. The result goes into the system prompt inside
 * `<user_instructions>`.
 */
object AgentsMd {
    const val OVERRIDE_NAME = "AGENTS.override.md"
    const val NAME = "AGENTS.md"
    const val SEPARATOR = "\n\n--- project-doc ---\n\n"
    const val MAX_BYTES = 32 * 1024

    /** The instruction file of one directory, or null when it has none (or an empty one). */
    fun readDir(dir: File?): String? {
        if (dir == null || !dir.isDirectory) return null
        for (name in listOf(OVERRIDE_NAME, NAME)) {
            val f = File(dir, name)
            if (f.isFile) {
                val text = runCatching { f.readText() }.getOrNull()?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    /** User-wide text first, then the project's, cut at [maxBytes] (UTF-8, on a character boundary). */
    fun combine(userText: String?, projectText: String?, maxBytes: Int = MAX_BYTES): String? {
        val joined = when {
            userText != null && projectText != null -> userText + SEPARATOR + projectText
            userText != null -> userText
            projectText != null -> projectText
            else -> return null
        }
        val bytes = joined.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return joined
        var end = maxBytes
        // Step back to the start of a character: continuation bytes are 10xxxxxx.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun fragment(text: String): String = "<user_instructions>\n$text\n</user_instructions>"

    fun load(globalDir: File?, workspaceDir: File?): String? =
        combine(readDir(globalDir), readDir(workspaceDir))?.let { fragment(it) }
}
