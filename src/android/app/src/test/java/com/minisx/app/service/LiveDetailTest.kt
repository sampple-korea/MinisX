package com.minisx.app.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDetailTest {

    @Test
    fun `reasoning excerpt is the last paragraph without markdown`() {
        val raw = "**Planning the change**\n\nI need to look at the failing test first.\n\n**Reading the log**\n\nThe log says the **cache** is stale."
        assertEquals("The log says the cache is stale.", LiveDetails.reasoningExcerpt(raw))
        assertEquals("Reading the log", LiveDetails.reasoningExcerpt("Earlier thoughts.\n\n**Reading the log**"))
    }

    @Test
    fun `reasoning excerpt of a stream still being written keeps its newest words`() {
        val raw = "x ".repeat(200) + "and now I compare the two answers"
        val out = LiveDetails.reasoningExcerpt(raw)!!
        assertTrue(out.startsWith("…"))
        assertTrue(out.endsWith("and now I compare the two answers"))
        assertTrue(out.length <= LiveDetails.MAX_CHARS)
    }

    @Test
    fun `blank reasoning has no excerpt`() {
        assertNull(LiveDetails.reasoningExcerpt(""))
        assertNull(LiveDetails.reasoningExcerpt("  \n\n  "))
        assertNull(LiveDetails.reasoningExcerpt("**"))
    }

    @Test
    fun `a shell command shows its first meaningful line`() {
        val d = LiveDetails.forTool("shell_execute", JSONObject("""{"command":"# install\n  apk add py3-pip \\\n && pip install x"}"""))!!
        assertEquals(LiveDetailKind.COMMAND, d.kind)
        assertEquals("apk add py3-pip \\", d.text)
    }

    @Test
    fun `a long command is cut at a word with an ellipsis`() {
        val d = LiveDetails.forTool("shell_execute", JSONObject().put("command", "echo " + "word ".repeat(80)))!!
        assertTrue(d.text.endsWith("…"))
        assertTrue(d.text.length <= LiveDetails.MAX_CHARS)
    }

    @Test
    fun `file tools show the last two path components`() {
        val d = LiveDetails.forTool("file_edit", JSONObject("""{"path":"/root/project/src/main/App.kt"}"""))!!
        assertEquals(LiveDetailKind.TARGET, d.kind)
        assertEquals("…/main/App.kt", d.text)
        assertEquals("/a/b", LiveDetails.shortPath("/a/b/"))
    }

    @Test
    fun `browser shows the action and where`() {
        val d = LiveDetails.forTool("browser_use", JSONObject("""{"action":"navigate","url":"https://example.com"}"""))!!
        assertEquals("navigate https://example.com", d.text)
    }

    @Test
    fun `a sub agent shows its task`() {
        val d = LiveDetails.forTool("subagent_task", JSONObject("""{"task":"Summarize the three failing tests"}"""))!!
        assertEquals(LiveDetailKind.AGENT, d.kind)
        assertEquals("Summarize the three failing tests", d.text)
    }

    @Test
    fun `tools without arguments to show give nothing`() {
        assertNull(LiveDetails.forTool("shell_execute", JSONObject("{}")))
        assertNull(LiveDetails.forTool("memory_get", JSONObject("""{"key":"x"}""")))
        assertNull(LiveDetails.forTool("file_read", JSONObject("""{"path":"null"}""")))
    }
}
