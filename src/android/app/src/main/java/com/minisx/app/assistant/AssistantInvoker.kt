package com.minisx.app.assistant

import android.content.Context
import android.content.Intent
import com.minisx.app.MainActivity
import java.io.File

/** Brings MinisX up as the assistant: a new chat holding the invocation. */
object AssistantInvoker {

    fun shotDir(context: Context): File = File(context.cacheDir, "assistant")

    fun intent(context: Context, invocation: AssistantInvocation): Intent {
        val token = AssistantInvocations.register(invocation)
        return Intent(context, MainActivity::class.java)
            .putExtra(AssistantInvocations.EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun launch(context: Context, invocation: AssistantInvocation) {
        context.startActivity(intent(context, invocation))
    }
}
