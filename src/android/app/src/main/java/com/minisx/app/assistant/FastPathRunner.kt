package com.minisx.app.assistant

import android.content.Context
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.offload.ControlOffloadHandler
import java.util.Locale

/** Runs a [FastCommands.Command] through the same handler `android-control` uses, in this process. */
object FastPathRunner {

    fun run(context: Context, command: FastCommands.Command, sessionId: String?): FastCommands.Outcome {
        val request = NativeOffloadRequest(
            pid = 0,
            argv = listOf("android-control") + command.argv,
            env = emptyMap(),
            cwd = "/",
            sessionId = sessionId,
        )
        val result = ControlOffloadHandler(context).handle(request)
        return FastCommands.interpret(result.exitCode, result.output, command, Locale.getDefault().language == "ko")
    }
}
