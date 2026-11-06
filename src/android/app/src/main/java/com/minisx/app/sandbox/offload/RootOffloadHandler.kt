package com.minisx.app.sandbox.offload

import com.minisx.app.logging.AppLogger
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.offload.RootShell
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking

/**
 * `android-root-cli`: run a command as root, straight through `su`. The root manager (KernelSU,
 * Magisk) only has to have granted MinisX root; no Shizuku in between.
 *
 * Every `exec` goes through MinisX's own approval first, and the prompt shows the command itself. The
 * policy is the "android-root-cli" entry in Settings > Permissions: ask (the default), always allow, or
 * never. `status` and `help` run nothing as root and need no approval.
 */
class RootOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val sub = argv.firstOrNull()
        if (sub == null || sub == "help" || sub == "--help" || sub == "-h") return ok(HELP)
        if (sub == "--version") return ok("android-root-cli 1.0\n")
        if (sub == "status") return status()
        if (sub != "exec") {
            return NativeOffloadResult(2, "android-root-cli: unknown subcommand '$sub'\n$HELP")
        }

        val parsed = parseExec(argv.drop(1)) ?: return NativeOffloadResult(2, "android-root-cli exec: a command is required\n$HELP")
        val sessionId = request.sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        val allowed = runBlocking {
            OffloadPermissionManager.checkPermission(
                "root_cli", "android-root-cli", sessionId,
                detail = parsed.command,
                explanation = parsed.reason,
                risks = com.minisx.app.offload.RootCommandRisk.assess(parsed.command),
            )
        }
        if (!allowed) {
            AppLogger.info(TAG, "root command refused: ${parsed.command.take(200)}")
            return NativeOffloadResult(
                126,
                "android-root-cli: the user did not allow this root command (or root is set to \"not allowed\" in Settings → Permissions). Do not retry it unchanged.\n",
            )
        }
        AppLogger.info(TAG, "root exec (timeout=${parsed.timeoutSec}s): ${parsed.command.take(200)}")
        val result = RootShell.run(parsed.command, parsed.timeoutSec)
        val tail = when {
            result.timedOut -> "\n[android-root-cli: timed out after ${parsed.timeoutSec}s and was killed]\n"
            result.exitCode == 127 && result.output.contains("su could not be started") -> "\n"
            else -> ""
        }
        return NativeOffloadResult(result.exitCode, result.output + tail)
    }

    private fun status(): NativeOffloadResult {
        val present = RootShell.suPresent()
        val level = OffloadPermissionManager.getLevel("root_cli")
        return ok(
            "su: ${if (present) "found (${RootShell.suPath()})" else "not found - root is not installed, or not exposed to apps"}\n" +
                "agent policy: ${level.name.lowercase()}\n" +
                "Root itself is granted to MinisX in the root manager (KernelSU, Magisk); the first `exec` makes it ask.\n",
        )
    }

    private fun ok(text: String) = NativeOffloadResult(0, text)

    class Exec(val command: String, val timeoutSec: Int, val reason: String? = null)

    companion object {
        private const val TAG = "RootOffloadHandler"

        /**
         * `[--reason TEXT] [--timeout SECONDS] <command...>`; a command given as several words is joined with spaces.
         * `--reason` is the agent's explanation for the person approving the command.
         */
        fun parseExec(args: List<String>): Exec? {
            var timeout = RootShell.DEFAULT_TIMEOUT_SEC
            var reason: String? = null
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a == "--reason" || a == "-r") {
                    reason = args.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                    i += 2
                } else if (a.startsWith("--reason=")) {
                    reason = a.removePrefix("--reason=").trim().takeIf { it.isNotEmpty() } ?: return null
                    i += 1
                } else if (a == "--timeout" || a == "-t") {
                    timeout = args.getOrNull(i + 1)?.toIntOrNull()?.coerceIn(1, RootShell.MAX_TIMEOUT_SEC) ?: return null
                    i += 2
                } else if (a.startsWith("--timeout=")) {
                    timeout = a.removePrefix("--timeout=").toIntOrNull()?.coerceIn(1, RootShell.MAX_TIMEOUT_SEC) ?: return null
                    i += 1
                } else if (a == "--") {
                    i += 1
                    break
                } else {
                    break
                }
            }
            val command = args.drop(i).joinToString(" ").trim()
            return if (command.isEmpty()) null else Exec(command, timeout, reason)
        }

        const val HELP = """android-root-cli - run commands as root through su (KernelSU, Magisk)

  android-root-cli exec --reason "<explanation for the approver>" [--timeout SECONDS] <command...>
                                                            run a command as root (default timeout 60 s, at most 3600)
  android-root-cli status                                   is su there, and what is the agent policy

Every exec asks the user first, command by command, and shows the command, your --reason and any risks the app found.
Always give --reason: one or two sentences, in the user's language (Korean for a Korean user), saying what the command does and what it changes. Output is stdout and stderr together; the exit
code is the command's.
"""
    }
}
