package com.minisx.app.agent.advisor

import android.content.Context
import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.AgentToolDefinition
import com.minisx.app.data.model.AgentToolParam
import com.minisx.app.data.model.LLMMessage
import com.minisx.app.tools.AgentToolSwitch

/**
 * The advisor: a second, stronger model the agent can ask for a review, like Claude Code's
 * `/advisor`. The agent calls the `advisor` tool with no arguments; the app forwards the whole
 * conversation so far (the task, every tool call and result) to the advisor model and returns its
 * answer as the tool result. The agent weighs the advice and carries on.
 *
 * Off until the user picks an advisor model (Settings > Agent tools > Advisor).
 */
object AdvisorSettings {
    private const val KEY_ENTRY = "agent.advisor.entry_id"

    /** The chosen model entry's id, or null while the advisor is off. */
    fun entryId(context: Context): String? =
        AgentToolSwitch.prefs(context).getString(KEY_ENTRY, null)?.takeIf { it.isNotBlank() }

    fun setEntryId(context: Context, id: String?) {
        AgentToolSwitch.prefs(context).edit().apply {
            if (id.isNullOrBlank()) remove(KEY_ENTRY) else putString(KEY_ENTRY, id)
        }.apply()
    }
}

object Advisor {
    const val TOOL_NAME = "advisor"

    /** The consultation is a read-only review; a few thousand tokens are plenty. */
    const val MAX_OUTPUT_TOKENS = 4096

    /** What the advisor sees of the conversation, newest part kept when it is longer. */
    const val MAX_TRANSCRIPT_CHARS = 90_000
    private const val TEXT_CHARS = 2_500
    private const val TOOL_ARGS_CHARS = 600
    private const val TOOL_RESULT_CHARS = 1_500

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Consult a stronger reviewer model that sees the whole conversation so far: the task, " +
            "every tool call you made and every result. It takes no arguments and answers with advice. " +
            "Use it before substantive work and before you declare a task done.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of why you are consulting the advisor, shown to the user. Use the same language as the user.",
            ),
        ),
        required = emptyList(),
        propertyOrdering = listOf("tool_title"),
    )

    /** The paragraph added to the agent's system prompt while the advisor is on. */
    val PROMPT_SECTION: String = """
Advisor:
You have an `advisor` tool backed by a stronger reviewer model. It takes no arguments: when you call it, your whole conversation so far is forwarded, so it sees the task, every tool call you made and every result you saw.
Call advisor BEFORE substantive work: before writing or editing, before committing to an interpretation, before building on an assumption. If the task needs orientation first (finding files, reading sources, seeing what is there), do that, then call advisor. Orientation is not substantive work; writing, editing and declaring an answer are.
Also call advisor when you believe the task is complete (make your deliverable durable first: save the file, commit the change), when you are stuck (errors recurring, an approach not converging, results that do not fit), and when you are considering a change of approach.
On a task longer than a few steps, call it at least once before committing to an approach and once before declaring done. Skip it for short reactive tasks where the next action is dictated by the tool output you just read.
Give the advice serious weight. If you follow a step and it fails empirically, or you have first-hand evidence (the file says X, the log says Y) that contradicts a specific claim, adapt. If your evidence points one way and the advisor points another, do not silently switch: say so, and call advisor again with the conflict ("I found X, you suggest Y, which constraint breaks the tie?").
""".trimIndent()

    val SYSTEM_PROMPT: String = """
You are the advisor: a senior reviewer consulted by another AI agent that is working on a task for a user. You see the agent's whole conversation so far (the transcript below the marker) and you cannot run tools yourself.

Reply with concise, concrete advice the agent can act on right now:
- Say whether the current plan or result is sound, and the single most important thing to change if it is not.
- Point out missed requirements, wrong assumptions, risks (data loss, destructive commands, security), and verification steps the agent has not done.
- If the agent is stuck, name the likely cause and the next experiment to try.
- If the work looks complete and correct, say so plainly and list any residual risk.
Quote file names, commands and identifiers exactly. Do not repeat the transcript, do not write the agent's code for it, and do not exceed about 300 words.
""".trimIndent()

    /** The user message for the advisor call: the transcript between clear markers. */
    fun userMessage(transcript: String): String =
        "BEGIN TRANSCRIPT\n$transcript\nEND TRANSCRIPT\n\nGive your advice now."

    /**
     * The conversation as plain text for the advisor: role-prefixed lines, long bodies shortened, the
     * newest part kept when the whole is longer than [maxChars] (the start is what gets cut, marked).
     */
    fun transcript(history: List<LLMMessage>, maxChars: Int = MAX_TRANSCRIPT_CHARS): String {
        val lines = ArrayList<String>()
        for (msg in history) {
            val role = msg.role.name.lowercase()
            if (msg.content.isNotBlank()) lines += "$role: ${clip(msg.content, TEXT_CHARS)}"
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> if (part.text.isNotBlank()) lines += "$role: ${clip(part.text, TEXT_CHARS)}"
                    is AgentContentPart.ToolUse -> lines += "$role [tool call ${part.name}]: ${clip(part.input.toString(), TOOL_ARGS_CHARS)}"
                    is AgentContentPart.ToolResult -> lines += "$role [result of ${part.name}]: ${clip(part.content, TOOL_RESULT_CHARS)}"
                    is AgentContentPart.ImageData -> lines += "$role [image ${part.mimeType}]"
                }
            }
        }
        var total = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            if (total + line.length + 1 > maxChars && kept.isNotEmpty()) break
            kept.addFirst(line)
            total += line.length + 1
        }
        val cut = lines.size - kept.size
        return (if (cut > 0) "[… $cut earlier lines omitted …]\n" else "") + kept.joinToString("\n")
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "… [+${text.length - max} chars]"
}
