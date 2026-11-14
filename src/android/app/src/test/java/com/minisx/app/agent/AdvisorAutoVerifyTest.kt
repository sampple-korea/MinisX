package com.minisx.app.agent

import com.minisx.app.agent.advisor.AdvisorAutoVerify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorAutoVerifyTest {

    @Test
    fun `a labelled verdict is read in either case and with any separator`() {
        for (answer in listOf(
            "VERDICT: PASS\n- no concerns",
            "verdict: pass",
            "**Verdict:** PASS",
            "Verdict - FAIL\n- the report was never saved",
            "Here is my review.\n\nVERDICT: FAIL\n- missing the second file",
        )) {
            val expected = if (answer.contains("PASS", ignoreCase = true)) {
                AdvisorAutoVerify.Verdict.PASS
            } else {
                AdvisorAutoVerify.Verdict.FAIL
            }
            assertEquals(answer, expected, AdvisorAutoVerify.review(answer).verdict)
        }
    }

    @Test
    fun `a bare verdict counts only as the first line`() {
        assertEquals(
            AdvisorAutoVerify.Verdict.PASS,
            AdvisorAutoVerify.review("PASS — the file was written to /tmp/a.txt").verdict,
        )
        // "FAIL" mentioned inside an explanation is not the verdict.
        assertEquals(
            AdvisorAutoVerify.Verdict.PASS,
            AdvisorAutoVerify.review("PASS\n- I would have used FAIL-safe handling, but that is style").verdict,
        )
    }

    @Test
    fun `silence is UNKNOWN, not a failed verdict`() {
        // Re-running the work because the REVIEW failed would punish the user for the reviewer.
        assertEquals(AdvisorAutoVerify.Verdict.UNKNOWN, AdvisorAutoVerify.review("").verdict)
        assertEquals(AdvisorAutoVerify.Verdict.UNKNOWN, AdvisorAutoVerify.review("I cannot help with that.").verdict)
    }

    @Test
    fun `the critique handed back reaches the model and is hidden from the user`() {
        val note = AdvisorAutoVerify.feedback("- the report was never saved to notes.md")
        // A <system-reminder>-only user row renders as nothing; it must therefore BE one.
        assertTrue(note.startsWith("<system-reminder>"))
        assertTrue(note.endsWith("</system-reminder>"))
        assertTrue(note.contains("notes.md"))
        assertTrue(note.contains("NOT complete"))
    }

    @Test
    fun `a runaway critique is cut rather than pinned into every later request`() {
        val note = AdvisorAutoVerify.feedback("x".repeat(50_000))
        assertTrue(note.length < AdvisorAutoVerify.MAX_CRITIQUE_CHARS + 1_000)
    }

    @Test
    fun `the review prompt asks for a verdict and forbids doing the work`() {
        assertTrue(AdvisorAutoVerify.SYSTEM_PROMPT.contains("VERDICT: PASS"))
        assertTrue(AdvisorAutoVerify.SYSTEM_PROMPT.contains("VERDICT: FAIL"))
        assertTrue(AdvisorAutoVerify.userMessage("t").startsWith("BEGIN TRANSCRIPT"))
        assertTrue(AdvisorAutoVerify.userMessage("t").contains("END TRANSCRIPT"))
    }

    @Test
    fun `the round cap keeps an app-driven continuation finite`() {
        assertEquals(2, AdvisorAutoVerify.MAX_ROUNDS)
    }
}
