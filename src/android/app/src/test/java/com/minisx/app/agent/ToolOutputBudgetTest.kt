package com.minisx.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputBudgetTest {

    @Test fun `the budget is ten thousand tokens plus the serialization allowance`() {
        assertEquals(48_000, ToolOutputBudget.MAX_BYTES)
    }

    @Test fun `a token is four bytes rounded up, counted in UTF-8`() {
        assertEquals(0, ToolOutputBudget.approxTokens(""))
        assertEquals(1, ToolOutputBudget.approxTokens("abc"))
        assertEquals(1, ToolOutputBudget.approxTokens("abcd"))
        assertEquals(2, ToolOutputBudget.approxTokens("abcde"))
        // Three Hangul syllables are 9 bytes.
        assertEquals(3, ToolOutputBudget.approxTokens("가나다"))
        // One emoji is 4 bytes.
        assertEquals(1, ToolOutputBudget.approxTokens("😀"))
    }

    @Test fun `text within the budget is returned as it is`() {
        val s = "x".repeat(1000)
        assertSame(s, ToolOutputBudget.truncateMiddle(s, 1000))
    }

    @Test fun `an oversized text keeps its head and tail with a marker between`() {
        val s = "H".repeat(500) + "M".repeat(1000) + "T".repeat(500)
        val out = ToolOutputBudget.truncateMiddle(s, 400)
        assertTrue(out.startsWith("H".repeat(200)))
        assertTrue(out.endsWith("T".repeat(200)))
        // 2000 - 400 = 1600 bytes removed = 400 tokens.
        assertTrue(out.contains("…400 tokens truncated…"))
        assertFalse(out.contains("MMMM"))
    }

    @Test fun `a cut never splits a multi-byte character`() {
        val out = ToolOutputBudget.truncateMiddle("가".repeat(1000), 100)
        assertFalse(out.contains('�'))
        val head = out.substringBefore("…")
        val tail = out.substringAfterLast("…")
        assertTrue(head.all { it == '가' })
        assertTrue(tail.all { it == '가' })
        assertTrue(head.toByteArray().size <= 50)
    }

    @Test fun `a cut never splits a surrogate pair`() {
        val out = ToolOutputBudget.truncateMiddle("😀".repeat(500), 100)
        assertFalse(out.contains('�'))
    }

    @Test fun `capped leaves small results and self bounded tools alone`() {
        val big = "x".repeat(ToolOutputBudget.MAX_BYTES + 10)
        assertSame("ok", ToolOutputBudget.capped("shell_execute", "ok"))
        assertSame(big, ToolOutputBudget.capped("file_read", big))
        assertSame(big, ToolOutputBudget.capped("shell_execute", big, alreadyOffloaded = { true }))
    }

    @Test fun `capped saves the whole output and names the path`() {
        val big = "a".repeat(30_000) + "b".repeat(30_000)
        var saved: String? = null
        val out = ToolOutputBudget.capped("shell_execute", big, save = { saved = it; "/var/minis/offloads/tools/x.txt" })
        assertEquals(big, saved)
        assertTrue(out.contains("tokens truncated"))
        assertTrue(out.contains("/var/minis/offloads/tools/x.txt"))
        assertTrue(out.length < big.length)
    }

    @Test fun `capped without a writer still cuts and says so`() {
        val big = "z".repeat(ToolOutputBudget.MAX_BYTES * 2)
        val out = ToolOutputBudget.capped("web_search", big, save = { null })
        assertTrue(out.contains("the middle was cut"))
        assertFalse(out.contains("saved at"))
    }
}
