package com.minisx.app.tools

import android.content.Context
import com.minisx.app.agent.exec.ExecPolicy
import com.minisx.app.data.model.AgentToolDefinition
import com.minisx.app.data.model.AgentToolParam
import com.minisx.app.sandbox.ExecSession
import com.minisx.app.sandbox.ExecSessionStore
import com.minisx.app.sandbox.ExecutionCoordinator
import org.json.JSONObject

/**
 * `exec_command` and `write_stdin`: Codex's unified exec. A command that is still running when the wait
 * ends hands back a session id; the model polls it or writes to its stdin with that id. Unlike
 * `shell_execute`, which waits for the command to finish, these suit servers, watchers, long builds
 * and programs that ask questions.
 */
object ExecCommandTool {
    const val EXEC_NAME = "exec_command"
    const val STDIN_NAME = "write_stdin"

    fun definitions(): List<AgentToolDefinition> = listOf(execDefinition(), stdinDefinition())

    private fun execDefinition() = AgentToolDefinition(
        name = EXEC_NAME,
        description = "Runs a command in the Linux sandbox and returns its output, or a session ID when it is still running " +
            "after the wait, so you can keep interacting with it. Commands that finish sooner return at once. Use it for " +
            "servers, watchers, long builds and programs that read stdin; use shell_execute for ordinary commands. " +
            "stdin and stdout are plain pipes (no terminal), stderr is merged into the output. A session left alone for " +
            "30 minutes is killed.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "cmd" to AgentToolParam("string", "Shell command to execute (run with /bin/sh -c)."),
            "workdir" to AgentToolParam("string", "Working directory for the command. Defaults to /root."),
            "yield_time_ms" to AgentToolParam("integer", "How long to wait for output before returning. Defaults to 10000 ms; effective range is 250-30000 ms."),
            "max_output_tokens" to AgentToolParam("integer", "Output token budget. Defaults to 10000 tokens."),
        ),
        required = listOf("tool_title", "cmd"),
        propertyOrdering = listOf("tool_title", "cmd", "workdir", "yield_time_ms", "max_output_tokens"),
    )

    private fun stdinDefinition() = AgentToolDefinition(
        name = STDIN_NAME,
        description = "Writes characters to the stdin of a session started by exec_command and returns the output that followed. " +
            "With no characters it only polls for more output. Send \"\\u0004\" (Ctrl-D) to close stdin.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "session_id" to AgentToolParam("integer", "The session ID exec_command returned."),
            "chars" to AgentToolParam("string", "Characters to write to stdin (include the newline). Empty or omitted: just poll."),
            "yield_time_ms" to AgentToolParam("integer", "How long to wait for output. With characters: default 250 ms, at most 30000. Polling: 5000-300000 ms, default 5000."),
            "max_output_tokens" to AgentToolParam("integer", "Output token budget. Defaults to 10000 tokens."),
        ),
        required = listOf("tool_title", "session_id"),
        propertyOrdering = listOf("tool_title", "session_id", "chars", "yield_time_ms", "max_output_tokens"),
    )

    private fun String.shellQuoted() = "'" + replace("'", "'\\''") + "'"

    suspend fun exec(argsJson: String, sessionId: String, fsSessionId: String?, @Suppress("UNUSED_PARAMETER") context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: the arguments are not valid JSON: ${e.message}", false)
        }
        val title = args.optString("tool_title", EXEC_NAME)
        val cmd = args.optString("cmd", "").ifBlank { args.optString("command", "") }
        if (cmd.isBlank()) return ToolExecutionResult("Error: 'cmd' is required", false, toolTitle = title)
        val workdir = args.optString("workdir", "").trim()
        val command = if (workdir.isNotEmpty()) "cd ${workdir.shellQuoted()} && $cmd" else cmd
        val session = ExecutionCoordinator.startExecSession(sessionId, fsSessionId, command)
            ?: return ToolExecutionResult("Error: the command could not be started", false, toolTitle = title)
        return report(session, yieldMs = ExecPolicy.execYieldMs(args.optLongOrNull("yield_time_ms")), args = args, title = title)
    }

    suspend fun writeStdin(argsJson: String, sessionId: String): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: the arguments are not valid JSON: ${e.message}", false)
        }
        val title = args.optString("tool_title", STDIN_NAME)
        val id = args.optInt("session_id", -1)
        val session = ExecSessionStore.get(sessionId, id)
            ?: return ToolExecutionResult("Error: no running session with ID $id (it may have finished; start a new one with exec_command).", false, toolTitle = title)
        val chars = args.optString("chars", "")
        if (chars.isNotEmpty() && !session.write(chars)) {
            // The command ended or closed its input: still return what it printed.
            val last = session.collect(ExecPolicy.MIN_YIELD_MS)
            if (!session.isRunning) ExecSessionStore.remove(session.id)
            return ToolExecutionResult(
                ExecPolicy.format(last.wallMs, null, last.exitCode ?: session.exitCode, last.output, ExecPolicy.maxOutputTokens(args.optLongOrNull("max_output_tokens"))) +
                    "\n(Writing to stdin failed: the process is not running or its stdin is closed.)",
                false, toolTitle = title,
            )
        }
        return report(session, ExecPolicy.writeYieldMs(args.optLongOrNull("yield_time_ms"), chars.isNotEmpty()), args, title)
    }

    private suspend fun report(session: ExecSession, yieldMs: Long, args: JSONObject, title: String): ToolExecutionResult {
        val chunk = session.collect(yieldMs)
        val maxTokens = ExecPolicy.maxOutputTokens(args.optLongOrNull("max_output_tokens"))
        val running = session.isRunning
        if (!running) ExecSessionStore.remove(session.id)
        val text = ExecPolicy.format(chunk.wallMs, if (running) session.id else null, chunk.exitCode, chunk.output, maxTokens)
        val ok = running || (chunk.exitCode ?: 0) == 0
        return ToolExecutionResult(text, ok, toolTitle = title)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
}
