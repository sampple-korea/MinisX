package com.minisx.app.agent

import org.json.JSONObject

/**
 * The argument names other tool-calling conventions use, mapped to the ones MinisX's tools declare.
 *
 * Models are trained on different harnesses (Claude Code's `file_path`, `old_str`, `limit`; Codex's
 * `cmd`; plain `text`), and a call that uses the wrong spelling used to fail with "missing required
 * parameter" - and then fail the same way again. They are renamed here, once, before the call is
 * checked, so the intent gets through. A canonical name that is already present always wins; an alias
 * is only copied when its canonical name is absent, and is removed either way so the tool sees one
 * spelling.
 *
 * Pure (`JSONObject` only) so every row is unit-tested.
 */
object ToolArgAliases {

    private val COMMON = mapOf(
        "title" to "tool_title",
        "description" to "tool_title",
    )

    private val BY_TOOL: Map<String, Map<String, String>> = mapOf(
        "file_read" to mapOf(
            "file_path" to "path", "filepath" to "path", "filename" to "path", "file" to "path", "target_file" to "path",
            "limit" to "lines", "line_count" to "lines", "num_lines" to "lines",
            "start_line" to "offset", "line_offset" to "offset",
        ),
        "file_write" to mapOf(
            "file_path" to "path", "filepath" to "path", "filename" to "path", "file" to "path",
            "text" to "content", "contents" to "content", "file_text" to "content", "data" to "content", "body" to "content",
        ),
        "file_edit" to mapOf(
            "file_path" to "path", "filepath" to "path", "filename" to "path", "file" to "path",
            "old_str" to "old_string", "old_text" to "old_string", "old" to "old_string", "search" to "old_string", "find" to "old_string",
            "new_str" to "new_string", "new_text" to "new_string", "new" to "new_string", "replace" to "new_string", "replacement" to "new_string",
        ),
        "apply_patch" to mapOf("patch" to "input", "diff" to "input", "patch_text" to "input", "text" to "input"),
        "shell_execute" to mapOf("cmd" to "command", "script" to "command", "bash_command" to "command", "timeout_sec" to "timeout", "timeout_seconds" to "timeout"),
        "exec_command" to mapOf("command" to "cmd", "script" to "cmd", "wait_ms" to "yield_time_ms"),
        "write_stdin" to mapOf("id" to "session_id", "input" to "chars", "text" to "chars", "stdin" to "chars", "wait_ms" to "yield_time_ms"),
        "read_image" to mapOf("file_path" to "path", "image_path" to "path", "filepath" to "path", "file" to "path"),
        "memory_get" to mapOf("query" to "keywords", "search" to "keywords", "q" to "keywords"),
        "memory_write" to mapOf("text" to "content", "memory" to "content", "note" to "content", "fact" to "content", "memory_id" to "id"),
    )

    /** Renames aliases in [args] for [toolName]. Returns the aliases that were renamed (for the log). */
    fun apply(toolName: String, args: JSONObject): List<String> {
        val own = BY_TOOL[toolName] ?: return emptyList()
        val renamed = ArrayList<String>()
        for ((alias, canonical) in own + COMMON) {
            if (!args.has(alias) || alias == canonical) continue
            val value = args.opt(alias)
            args.remove(alias)
            if (!args.has(canonical) || args.isNull(canonical)) {
                args.put(canonical, value)
                renamed += "$alias->$canonical"
            }
        }
        return renamed
    }
}
