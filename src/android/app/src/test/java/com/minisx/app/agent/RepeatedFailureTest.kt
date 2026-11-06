package com.minisx.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedFailureTest {

    private val err = "Error: questions[0] is a confirm: it takes no `options`"

    private fun fail(d: ToolLoopDetector, tool: String, args: Map<String, Any?>, msg: String = err) =
        d.record(tool, args, result = null, errorMessage = msg)

    @Test fun `the same error three times in a row warns, whatever the arguments`() {
        val d = ToolLoopDetector()
        assertEquals(Level.NONE, fail(d, "user_ask", mapOf("q" to "a")).level)
        assertEquals(Level.NONE, fail(d, "user_ask", mapOf("q" to "b")).level)
        val third = fail(d, "user_ask", mapOf("q" to "c"))
        assertEquals(Level.WARNING, third.level)
        assertTrue(third.message!!.contains("[REPEATED FAILURE]"))
        assertTrue(third.message!!.contains("same error 3 times"))
    }

    @Test fun `six in a row blocks the next call`() {
        val d = ToolLoopDetector()
        repeat(5) { fail(d, "user_ask", mapOf("q" to "$it")) }
        assertEquals(Level.NONE, d.check("user_ask", mapOf("q" to "x")).level)
        fail(d, "user_ask", mapOf("q" to "5"))
        val pre = d.check("user_ask", mapOf("q" to "new wording"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue(pre.message!!.contains("[LOOP BLOCKED]"))
    }

    @Test fun `a success or a different error starts the count again`() {
        val d = ToolLoopDetector()
        repeat(4) { fail(d, "file_edit", mapOf("n" to it)) }
        d.record("file_edit", mapOf("n" to 9), result = "ok")
        assertEquals(Level.NONE, fail(d, "file_edit", mapOf("n" to 10)).level)
        repeat(2) { fail(d, "file_edit", mapOf("n" to it), msg = "Error: other") }
        assertEquals(Level.NONE, d.check("file_edit", mapOf("n" to 11)).level)
    }

    @Test fun `other tools between do not hide the streak`() {
        val d = ToolLoopDetector()
        repeat(3) {
            fail(d, "user_ask", mapOf("q" to "$it"))
            d.record("file_read", mapOf("path" to "/x$it"), result = "ok")
        }
        assertEquals(Level.WARNING, fail(d, "user_ask", mapOf("q" to "z")).level)
    }

    @Test fun `a blocked attempt keeps the block in place`() {
        val d = ToolLoopDetector()
        repeat(6) { fail(d, "user_ask", mapOf("q" to "$it")) }
        val pre = d.check("user_ask", mapOf("q" to "x"))
        assertEquals(Level.CRITICAL, pre.level)
        d.record("user_ask", mapOf("q" to "x"), result = null, errorMessage = pre.message)
        assertEquals(Level.CRITICAL, d.check("user_ask", mapOf("q" to "y")).level)
    }
}
