package com.minisx.app.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecPolicyTest {

    @Test fun `the first wait defaults to ten seconds and stays within 250 ms to 30 s`() {
        assertEquals(10_000L, ExecPolicy.execYieldMs(null))
        assertEquals(250L, ExecPolicy.execYieldMs(1))
        assertEquals(30_000L, ExecPolicy.execYieldMs(600_000))
        assertEquals(4_000L, ExecPolicy.execYieldMs(4_000))
    }

    @Test fun `writing waits briefly, polling waits long`() {
        assertEquals(250L, ExecPolicy.writeYieldMs(null, hasChars = true))
        assertEquals(30_000L, ExecPolicy.writeYieldMs(99_000, hasChars = true))
        assertEquals(5_000L, ExecPolicy.writeYieldMs(null, hasChars = false))
        assertEquals(5_000L, ExecPolicy.writeYieldMs(100, hasChars = false))
        assertEquals(300_000L, ExecPolicy.writeYieldMs(9_999_999, hasChars = false))
    }

    @Test fun `the output budget defaults to ten thousand tokens`() {
        assertEquals(10_000, ExecPolicy.maxOutputTokens(null))
        assertEquals(1, ExecPolicy.maxOutputTokens(0))
        assertEquals(100_000, ExecPolicy.maxOutputTokens(10_000_000))
    }

    @Test fun `a finished command reports its exit code and no session`() {
        val out = ExecPolicy.format(1_234, sessionId = null, exitCode = 0, output = "hello\n", maxTokens = 100)
        assertTrue(out.startsWith("Wall time: 1.2 seconds\nProcess exited with code 0\n"))
        assertTrue(out.endsWith("Output:\nhello\n"))
        assertTrue(out.contains("Original token count: 2"))
    }

    @Test fun `a running command reports its session id`() {
        val out = ExecPolicy.format(10_000, sessionId = 7, exitCode = null, output = "", maxTokens = 100)
        assertTrue(out.contains("Process running with session ID 7"))
        assertFalse(out.contains("exited"))
    }

    @Test fun `long output keeps its head and tail within the token budget`() {
        val big = "a".repeat(2_000) + "b".repeat(2_000)
        val out = ExecPolicy.format(1, null, 0, big, maxTokens = 100)
        assertTrue(out.contains("tokens truncated"))
        assertTrue(out.contains("Original token count: 1000"))
        assertTrue(out.length < big.length)
    }

    @Test fun `the buffer hands out what is unread, once`() {
        val b = ExecOutputBuffer()
        b.append("one ")
        b.append("two")
        assertEquals("one two", b.drain())
        assertEquals("", b.drain())
        assertTrue(b.isEmpty())
    }

    @Test fun `the buffer remembers when it was last written`() {
        val b = ExecOutputBuffer()
        assertEquals(0L, b.lastWriteAt)
        b.append("x", now = 42)
        assertEquals(42L, b.lastWriteAt)
    }

    @Test fun `unread output past the cap loses its middle`() {
        val b = ExecOutputBuffer(maxUnreadChars = 100)
        b.append("H".repeat(60))
        b.append("M".repeat(100))
        b.append("T".repeat(60))
        val out = b.drain()
        assertTrue(out.startsWith("H"))
        assertTrue(out.endsWith("T"))
        assertTrue(out.contains("characters of output were dropped"))
        assertTrue(out.length < 220)
    }
}
