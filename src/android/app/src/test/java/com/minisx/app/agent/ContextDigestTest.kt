package com.minisx.app.agent

import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-digest] The summary compaction falls back to when no model can write one.
 */
class ContextDigestTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun `a digest keeps the request, the commands and the errors`() {
        val history = listOf(
            user("fix the failing build and save the report to notes.md"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT, content = "Running the build.",
                contentParts = listOf(
                    AgentContentPart.ToolUse("1", "shell_execute", JSONObject().put("command", "./gradlew build")),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        "1", "shell_execute",
                        "error: unresolved reference: Foo\n  at /home/user/app/Main.kt",
                    ),
                ),
            ),
        )
        val d = ContextDigest.digest(history)
        assertTrue(d.startsWith(ContextDigest.HEADER))
        assertTrue(d.contains("notes.md"))
        assertTrue(d.contains("./gradlew build"))
        assertTrue(d.contains("unresolved reference"))
        assertTrue("a failing path must survive for the next turn", d.contains("/home/user/app/Main.kt"))
    }

    @Test
    fun `a digest puts the user's request before the work`() {
        val d = ContextDigest.digest(listOf(user("first request"), user("later instruction")))
        assertTrue(d.indexOf("first request") < d.indexOf("later instruction"))
    }

    @Test
    fun `runtime notes are not mistaken for the user's request`() {
        val d = ContextDigest.digest(
            listOf(
                user("write the file"),
                user("<system-reminder>a note the app added for the model</system-reminder>"),
            ),
        )
        assertTrue(d.contains("write the file"))
        assertFalse(d.contains("a note the app added"))
    }

    @Test
    fun `an earlier summary is carried forward`() {
        val d = ContextDigest.digest(listOf(user("next step")), previousSummary = "they were building a cache")
        assertTrue(d.contains("they were building a cache"))
    }

    @Test
    fun `a digest of a long run stays within its budget and keeps the newest steps`() {
        val history = (1..200).map { i ->
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT, content = "step $i " + "y".repeat(300),
                contentParts = listOf(
                    AgentContentPart.ToolUse("$i", "shell_execute", JSONObject().put("command", "echo $i")),
                ),
            )
        }
        val d = ContextDigest.digest(history, maxChars = 4_000)
        assertTrue("the newest step survives", d.contains("echo 200"))
        assertTrue("the cut is declared", d.contains("earlier steps left out"))
        assertTrue("the budget holds: ${d.length}", d.length < 6_000)
    }

    @Test
    fun `an empty history is still a usable summary line`() {
        assertTrue(ContextDigest.digest(emptyList()).startsWith(ContextDigest.HEADER))
    }

    @Test
    fun `error lines are read out of a result`() {
        val hits = ContextDigest.errorLines(
            "OK\nError: connection refused\nmore output\nTraceback (most recent call last):\n",
        )
        assertEquals(2, hits.size)
        assertTrue(hits[0].contains("connection refused"))
    }

    @Test
    fun `paths are collected without trailing punctuation`() {
        val paths = ContextDigest.pathsIn("wrote /home/user/app/main.kt, then read src/main/App.kt and ~/.config/x.")
        assertTrue(paths.contains("/home/user/app/main.kt"))
        assertTrue(paths.contains("~/.config/x"))
        // A relative path with no leading slash is not a path this collector claims.
        assertFalse(paths.contains("src/main/App.kt"))
    }
}
