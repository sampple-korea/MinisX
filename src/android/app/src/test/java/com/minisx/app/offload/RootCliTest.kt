package com.minisx.app.offload

import com.minisx.app.sandbox.offload.RootOffloadHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCliTest {

    @Test fun `su is run with -c and the command as one argument`() {
        assertEquals(listOf("/system/bin/su", "-c", "pm list packages | head"), RootShell.argv("pm list packages | head", "/system/bin/su"))
    }

    @Test fun `the first su that exists is used, else plain su`() {
        assertEquals("/data/adb/ksu/bin/su", RootShell.suPath { it == "/data/adb/ksu/bin/su" })
        assertEquals("su", RootShell.suPath { false })
        assertTrue(RootShell.suPresent { it == "/sbin/su" })
        assertTrue(!RootShell.suPresent { false })
    }

    @Test fun `exec takes the command as given`() {
        val e = RootOffloadHandler.parseExec(listOf("id"))!!
        assertEquals("id", e.command)
        assertEquals(60, e.timeoutSec)
    }

    @Test fun `words of one command are joined with spaces`() {
        assertEquals("ls -l /data", RootOffloadHandler.parseExec(listOf("ls", "-l", "/data"))!!.command)
    }

    @Test fun `timeout can come first, in either spelling, and is capped`() {
        assertEquals(5, RootOffloadHandler.parseExec(listOf("--timeout", "5", "id"))!!.timeoutSec)
        assertEquals(7, RootOffloadHandler.parseExec(listOf("--timeout=7", "id"))!!.timeoutSec)
        assertEquals(3600, RootOffloadHandler.parseExec(listOf("-t", "99999", "sleep", "1"))!!.timeoutSec)
    }

    @Test fun `a command that starts with a dash can follow --`() {
        assertEquals("-x", RootOffloadHandler.parseExec(listOf("--", "-x"))!!.command)
    }

    @Test fun `no command, or a bad timeout, is not accepted`() {
        assertNull(RootOffloadHandler.parseExec(emptyList()))
        assertNull(RootOffloadHandler.parseExec(listOf("--timeout", "x", "id")))
        assertNull(RootOffloadHandler.parseExec(listOf("--timeout", "5")))
        assertNotNull(RootOffloadHandler.parseExec(listOf("id")))
    }

    @Test fun `root is registered as ask by default`() {
        val info = OffloadPermissionManager.toolRegistry.single { it.toolName == "root_cli" }
        assertEquals(OffloadPermissionManager.PermissionLevel.ASK_ONCE, info.defaultLevel)
    }
}
