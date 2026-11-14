package com.minisx.app.agent.advisor

/**
 * [T-android-advisor-auto-verify] The advisor asked to CHECK the result instead of being consulted
 * for advice along the way.
 *
 * [Advisor] is a tool: the model decides when to call it, and a model that believes it is done has
 * no reason to call one more tool before saying so. That is exactly the moment a review is worth
 * most, and exactly the moment a tool call cannot be relied on — so with Settings › Agent tools ›
 * Advisor › "Auto-verify the result" on, the app itself asks on the model's behalf: right before the
 * agent loop would end, the advisor reads the conversation and answers with a verdict.
 *
 * A FAIL does not end the turn. The verdict's reasoning is handed back as a user-role note and the
 * loop runs again, so the model fixes what the reviewer named and then finishes. That round trip is
 * capped by [MAX_ROUNDS] — an app-driven continuation that the user did not ask for must not be able
 * to keep a chat going on its own, and a reviewer that is wrong (or a model that keeps disagreeing
 * with it) would otherwise ping-pong until the turn ceiling.
 *
 * Pure (no Android types): the prompt text and the verdict parser are unit-tested on the JVM.
 */
object AdvisorAutoVerify {

    /** AUTO-VERIFY rounds one run may spend. Two reviews = one check plus one re-check after a fix. */
    const val MAX_ROUNDS = 2

    /**
     * The reviewer only writes a verdict and a handful of points, so this is generous. It is NOT
     * [Advisor.MAX_OUTPUT_TOKENS]: a thinking model may spend the first half of the budget before
     * its first text token, and a verdict cut off mid-sentence is useless here.
     */
    const val MAX_OUTPUT_TOKENS = 1_200

    /** Longer than this the critique is cut; the points that matter come first. */
    const val MAX_CRITIQUE_CHARS = 4_000

    enum class Verdict {
        /** The work satisfies the request. */
        PASS,

        /** Something is missing or wrong — the agent loop continues with the critique. */
        FAIL,

        /**
         * No verdict could be read (a refusal, a cut-off answer, an empty stream). Treated as PASS
         * by the caller: re-running the work because the REVIEW failed would punish the user for the
         * reviewer's silence.
         */
        UNKNOWN,
    }

    /** One review: the verdict and, for a FAIL, the reasoning handed back to the agent. */
    data class Review(val verdict: Verdict, val text: String) {
        val critique: String get() = text.trim().take(MAX_CRITIQUE_CHARS)
    }

    val SYSTEM_PROMPT: String = """
You are the reviewer consulted by an AI agent at the moment it is about to hand its answer to the user. You see the agent's whole conversation: the user's request, every tool call the agent made and every result it saw. You cannot run tools yourself.

Decide one thing: does the agent's final answer satisfy the user's request, with the work actually done?

Answer in exactly this shape, and nothing before the first line:
VERDICT: PASS
or
VERDICT: FAIL

Then, on the following lines, say why in at most four bullet points, quoting the file, command or identifier you mean:
- For PASS, one line naming the residual risk worth mentioning, or "no concerns".
- For FAIL, each point must be a concrete defect the agent can fix right now: a requirement it skipped, a step it claimed but never ran, an output that does not match what was asked, a file left unsaved, an error it worked around instead of fixing. Name the file or command.

FAIL only when the work is genuinely incomplete or wrong. Style, brevity, a different-but-valid approach and improvements you would have made yourself are a PASS. Do not ask the agent to redo work that is already done, and never write the agent's answer or code for it.
""".trimIndent()

    /** The user message for the review call: the transcript between clear markers. */
    fun userMessage(transcript: String): String =
        "BEGIN TRANSCRIPT\n$transcript\nEND TRANSCRIPT\n\n" +
            "The agent is about to end its turn. Review the work and answer with the verdict now."

    /** `VERDICT: PASS`, `verdict - fail`, `**Verdict:** PASS` — the label, a separator, the word. */
    private val VERDICT_LABEL = Regex("""(?i)\bverdict\b\W{0,4}(PASS|FAIL)\b""")

    /** A first line that IS the verdict, e.g. `PASS — the file was written`. */
    private val VERDICT_FIRST = Regex("""^\W{0,4}(PASS|FAIL)\b""")

    /**
     * Read the reviewer's answer. The label form is searched anywhere in the text (a thinking model
     * sometimes restates its verdict at the end); a bare PASS/FAIL counts only as the first line, so
     * a "FAIL" inside an explanation cannot be mistaken for the verdict.
     */
    fun review(answer: String): Review {
        val text = answer.trim()
        if (text.isEmpty()) return Review(Verdict.UNKNOWN, "")
        VERDICT_LABEL.find(text)?.let { m ->
            return Review(if (m.groupValues[1].equals("PASS", ignoreCase = true)) Verdict.PASS else Verdict.FAIL, text)
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        VERDICT_FIRST.find(firstLine)?.let { m ->
            return Review(if (m.groupValues[1].equals("PASS", ignoreCase = true)) Verdict.PASS else Verdict.FAIL, text)
        }
        return Review(Verdict.UNKNOWN, text)
    }

    /**
     * The note a FAIL is handed back as. A user-role message rather than a tool result: there is no
     * tool call to attach it to (the model did not make one), and a `<system-reminder>` row renders
     * as nothing in the transcript while still reaching the model on every later request — the same
     * shape the loop's other hidden nudges use.
     */
    fun feedback(critique: String): String = buildString {
        append(
            "<system-reminder>An independent reviewer read this conversation just before you were " +
                "about to finish and judged the work NOT complete. Address its points now, then give " +
                "your final answer again. Do not repeat this note to the user.",
        )
        append("\n\nReviewer's points:\n")
        append(critique.trim().take(MAX_CRITIQUE_CHARS))
        append("\n</system-reminder>")
    }
}
