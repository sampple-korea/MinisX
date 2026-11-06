package com.minisx.app.sandbox

import android.util.Log
import com.minisx.app.agent.exec.ExecOutputBuffer
import com.minisx.app.agent.exec.ExecPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A command started by `exec_command` that may outlive the call: its output is collected as it
 * arrives and its stdin stays open (plain pipes, no terminal), like Codex's unified exec.
 */
internal class ExecSession(
    val id: Int,
    val owner: String,
    val command: String,
    private val shell: FreshProcessShell,
    private val process: Process,
) {
    private val buffer = ExecOutputBuffer()
    @Volatile private var eof = false
    @Volatile var exitCode: Int? = null
        private set
    private var statusSeenAt = 0L

    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
        private set

    init {
        Thread({ pump() }, "ExecSession-$id").apply { isDaemon = true }.start()
    }

    private fun pump() {
        try {
            val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                buffer.append(String(buf, 0, n))
            }
        } catch (_: Exception) {
            // The stream was closed (kill, or the command finished and its output settled).
        } finally {
            eof = true
        }
    }

    val isRunning: Boolean get() = exitCode == null

    class Chunk(val output: String, val exitCode: Int?, val wallMs: Long)

    /**
     * Waits until the command has exited and its output has settled, or [yieldMs] has passed, and
     * returns what was printed since the last call.
     */
    suspend fun collect(yieldMs: Long): Chunk {
        lastUsedAt = System.currentTimeMillis()
        val started = System.currentTimeMillis()
        val deadline = started + yieldMs
        while (exitCode == null) {
            val now = System.currentTimeMillis()
            val status = shell.interactiveExitStatus()
            if (status != null) {
                if (statusSeenAt == 0L) statusSeenAt = now
                if (FreshProcessShell.outputSettled(now, statusSeenAt, buffer.lastWriteAt)) {
                    finish(status)
                    break
                }
            } else if (eof && !process.isAlive) {
                finish(runCatching { process.exitValue() }.getOrDefault(0))
                break
            }
            if (now >= deadline) break
            delay(25)
        }
        val text = TerminalSanitizer.sanitize(buffer.drain())
        lastUsedAt = System.currentTimeMillis()
        return Chunk(text, exitCode, System.currentTimeMillis() - started)
    }

    private fun finish(code: Int) {
        exitCode = code
        runCatching { process.outputStream.close() }
        // A detached daemon can keep proot (and so the pipe) alive; the output is complete, stop reading.
        runCatching { process.inputStream.close() }
    }

    /** Writes [chars] to the command's stdin. False when the command is gone or its stdin is closed. */
    suspend fun write(chars: String): Boolean = withContext(Dispatchers.IO) {
        lastUsedAt = System.currentTimeMillis()
        if (exitCode != null) return@withContext false
        try {
            if (chars == EOF_CHAR) {
                process.outputStream.close()
            } else {
                process.outputStream.write(chars.toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$owner] exec $id stdin write failed: ${e.message}")
            false
        }
    }

    fun kill() {
        if (exitCode == null) exitCode = SIGKILL_EXIT
        // The kill waits for proot to let go; never on the caller's thread (it may be the main one).
        kotlin.concurrent.thread(name = "ExecSessionKill-$id", isDaemon = true) {
            runCatching { shell.killInteractive() }
        }
    }

    companion object {
        private const val TAG = "ExecSession"

        /** Ctrl-D: closes the command's stdin (there is no terminal to turn it into EOF). */
        const val EOF_CHAR = "\u0004"

        /** What a killed session reports, as a shell would for SIGKILL. */
        const val SIGKILL_EXIT = 137
    }
}

/** The live exec sessions, by id. An id is only valid for the conversation that started it. */
internal object ExecSessionStore {
    private val sessions = ConcurrentHashMap<Int, ExecSession>()
    private val nextId = AtomicInteger(1)

    fun add(build: (Int) -> ExecSession?, owner: String): ExecSession? {
        prune(owner)
        val id = nextId.getAndIncrement()
        val session = build(id) ?: return null
        sessions[id] = session
        return session
    }

    fun get(owner: String, id: Int): ExecSession? = sessions[id]?.takeIf { it.owner == owner }

    fun remove(id: Int) { sessions.remove(id) }

    /** Kills every session of [owner] (the conversation was closed). */
    fun killAll(owner: String): Int {
        val mine = sessions.values.filter { it.owner == owner }
        mine.forEach { it.kill(); sessions.remove(it.id) }
        return mine.size
    }

    /**
     * Makes room: drops finished sessions and ones nobody touched for [ExecPolicy.IDLE_LIMIT_MS], and
     * if [owner] is still at the limit, the least recently used one.
     */
    private fun prune(owner: String) {
        val now = System.currentTimeMillis()
        sessions.values.filter { !it.isRunning || now - it.lastUsedAt > ExecPolicy.IDLE_LIMIT_MS }.forEach {
            it.kill(); sessions.remove(it.id)
        }
        val mine = sessions.values.filter { it.owner == owner }.sortedBy { it.lastUsedAt }
        if (mine.size >= ExecPolicy.MAX_SESSIONS) {
            mine.first().let { it.kill(); sessions.remove(it.id) }
        }
    }
}
