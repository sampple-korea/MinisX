package com.minisx.app.offload

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a command as root through `su` (KernelSU, Magisk, ...). Nothing is asked of the user here: the
 * root manager's own grant for MinisX is the permission, and MinisX's approval prompt
 * ([com.minisx.app.sandbox.offload.RootOffloadHandler]) decides, command by command, whether the agent
 * may use it.
 */
object RootShell {

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean)

    const val DEFAULT_TIMEOUT_SEC = 60
    const val MAX_TIMEOUT_SEC = 3600

    /** Where a root manager puts its `su`, most common first. */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/vendor/bin/su",
    )

    /** The `su` to run: the first one found, else plain `su` for the PATH to resolve. */
    fun suPath(exists: (String) -> Boolean = { File(it).exists() }): String =
        SU_CANDIDATES.firstOrNull(exists) ?: "su"

    /** Whether a `su` binary is there at all. Says nothing about whether MinisX has been granted it. */
    fun suPresent(exists: (String) -> Boolean = { File(it).exists() }): Boolean = SU_CANDIDATES.any(exists)

    fun argv(command: String, su: String = suPath()): List<String> = listOf(su, "-c", command)

    /** stdout and stderr together, as a shell would show them. */
    fun run(command: String, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): Result {
        val timeout = timeoutSec.coerceIn(1, MAX_TIMEOUT_SEC)
        val process = try {
            ProcessBuilder(argv(command)).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(127, "su could not be started: ${e.message}. Is root (KernelSU, Magisk) installed and granted to MinisX?", false)
        }
        val out = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
                // closed by the timeout kill
            }
        }, "RootShell-reader").apply { isDaemon = true; start() }

        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
        reader.join(1_000)
        val text = synchronized(out) { out.toString() }
        return if (finished) Result(process.exitValue(), text, false) else Result(124, text, true)
    }
}
