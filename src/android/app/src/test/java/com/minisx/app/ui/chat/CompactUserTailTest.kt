package com.minisx.app.ui.chat

import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactUserTailTest {

    private fun user(id: String, text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text, dbMessageId = id)
    private fun assistant(id: String, text: String) = LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, dbMessageId = id)

    @Test fun `user messages come back oldest first with the newest last`() {
        val h = listOf(user("u1", "first"), assistant("a1", "ok"), user("u2", "second"), assistant("a2", "ok"), user("u3", "third"))
        val out = CompactUserTail.render(h, lastIndex = 4, alreadyKept = emptySet())!!
        assertTrue(out.indexOf("first") < out.indexOf("second"))
        assertTrue(out.indexOf("second") < out.indexOf("third"))
        assertFalse("assistant text is not carried", out.contains("ok"))
        assertTrue(out.startsWith("<earlier-user-messages>"))
        assertTrue(out.endsWith("</earlier-user-messages>"))
    }

    @Test fun `messages already kept whole are left out`() {
        val h = listOf(user("u1", "old"), user("u2", "kept"))
        val out = CompactUserTail.render(h, 1, alreadyKept = setOf("u2"))!!
        assertTrue(out.contains("old"))
        assertFalse(out.contains("kept"))
    }

    @Test fun `tool results and system reminders are not user messages`() {
        val toolResult = LLMMessage(
            role = LLMMessage.Role.USER, content = "",
            contentParts = listOf(AgentContentPart.ToolResult(id = "t", name = "shell_execute", content = "output")),
            dbMessageId = "r",
        )
        val h = listOf(toolResult, user("s", "<system-reminder>x</system-reminder>"), user("u", "real"))
        val out = CompactUserTail.render(h, 2, emptySet())!!
        assertTrue(out.contains("real"))
        assertFalse(out.contains("output"))
        assertFalse(out.contains("system-reminder>x"))
    }

    @Test fun `the walk stops at the budget and cuts the message that crosses it`() {
        val big = "w".repeat(4_000)   // 1000 tokens
        val h = listOf(user("u1", "ancient"), user("u2", big), user("u3", "newest"))
        val out = CompactUserTail.render(h, 2, emptySet(), maxTokens = 600)!!
        assertTrue(out.contains("newest"))
        assertFalse("older than the cut message is dropped", out.contains("ancient"))
        assertTrue("the crossing message is cut", out.contains("tokens truncated"))
    }

    @Test fun `nothing to add gives null`() {
        assertNull(CompactUserTail.render(emptyList(), -1, emptySet()))
        assertNull(CompactUserTail.render(listOf(assistant("a", "x")), 0, emptySet()))
        assertNull(CompactUserTail.render(listOf(user("u", "x")), 0, emptySet(), maxTokens = 0))
    }

    @Test fun `only messages up to the anchor are considered`() {
        val h = listOf(user("u1", "before"), user("u2", "after the anchor"))
        val out = CompactUserTail.render(h, lastIndex = 0, alreadyKept = emptySet())!!
        assertTrue(out.contains("before"))
        assertFalse(out.contains("after the anchor"))
        assertEquals(1, Regex("""\[\d+]""").findAll(out).count())
    }
}
