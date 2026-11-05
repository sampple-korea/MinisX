package com.minisx.app.agent

import com.minisx.app.agent.advisor.Advisor
import com.minisx.app.data.model.AgentContentPart
import com.minisx.app.data.model.LLMMessage
import com.minisx.app.tools.AgentTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTest {

    private fun user(text: String) = LLMMessage(role = LLMMessage.Role.USER, content = text)

    @Test
    fun `the tool exists only when the advisor is on and never for a sub agent`() {
        fun names(advisor: Boolean, helper: Boolean) =
            AgentTools.makeAgentTools(advisorEnabled = advisor, isHelper = helper).map { it.name }
        assertTrue(Advisor.TOOL_NAME in names(advisor = true, helper = false))
        assertFalse(Advisor.TOOL_NAME in names(advisor = false, helper = false))
        assertFalse(Advisor.TOOL_NAME in names(advisor = true, helper = true))
    }

    @Test
    fun `the tool takes no required arguments`() {
        val def = Advisor.definition()
        assertTrue(def.required.isEmpty())
        assertEquals(setOf("tool_title"), def.parameters.keys)
    }

    @Test
    fun `the transcript carries messages, tool calls and results in order`() {
        val history = listOf(
            user("fix the build"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT, content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("1", "shell_execute", JSONObject().put("command", "make")),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = listOf(AgentContentPart.ToolResult("1", "shell_execute", "error: missing header")),
            ),
        )
        val t = Advisor.transcript(history)
        val lines = t.lines()
        assertEquals("user: fix the build", lines[0])
        assertTrue(lines[1].startsWith("assistant [tool call shell_execute]: "))
        assertTrue(lines[1].contains("make"))
        assertEquals("user [result of shell_execute]: error: missing header", lines[2])
    }

    @Test
    fun `a long transcript keeps its newest part and says what was cut`() {
        val history = (1..400).map { user("message number $it " + "x".repeat(100)) }
        val t = Advisor.transcript(history, maxChars = 5_000)
        assertTrue(t.startsWith("[… "))
        assertTrue(t.contains("message number 400"))
        assertFalse(t.contains("message number 1 "))
        assertTrue(t.length < 5_300)
    }

    @Test
    fun `one huge tool result is clipped`() {
        val t = Advisor.transcript(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER, content = "",
                    contentParts = listOf(AgentContentPart.ToolResult("1", "shell_execute", "y".repeat(50_000))),
                ),
            ),
        )
        assertTrue(t.length < 2_000)
        assertTrue(t.contains("chars]"))
    }

    @Test
    fun `the advisor is told to give advice, not to continue the conversation`() {
        assertTrue(Advisor.userMessage("a").startsWith("BEGIN TRANSCRIPT"))
        assertTrue(Advisor.userMessage("a").contains("END TRANSCRIPT"))
        assertTrue(Advisor.SYSTEM_PROMPT.contains("advice"))
        assertTrue(Advisor.PROMPT_SECTION.contains("BEFORE substantive work"))
    }
}
