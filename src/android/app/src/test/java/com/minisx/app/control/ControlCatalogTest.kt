package com.minisx.app.control

import com.minisx.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCatalogTest {

    @Test fun `there are at least fifty actions`() {
        assertTrue("only ${ControlCatalog.entries.size}", ControlCatalog.entries.size >= 50)
    }

    @Test fun `every action is unique and has a summary`() {
        val keys = ControlCatalog.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        ControlCatalog.entries.forEach {
            assertTrue(it.key, it.summary.isNotBlank())
            assertTrue(it.key, it.group == it.group.lowercase() && it.name == it.name.lowercase())
            assertTrue(it.key, !it.group.contains(' ') && !it.name.contains(' '))
        }
    }

    @Test fun `the flashlight is covered`() {
        listOf("on", "off", "toggle", "status").forEach { assertNotNull("flashlight $it", ControlCatalog.find("flashlight", it)) }
    }

    @Test fun `the listing names every action and the overview every group`() {
        val listing = ControlCatalog.listing()
        ControlCatalog.entries.forEach { assertTrue(it.key, listing.contains("android-control ${it.key}")) }
        ControlCatalog.groups.forEach { assertTrue(it, ControlCatalog.overview().contains("  $it:")) }
    }

    @Test fun `an unknown action in a known group suggests that group`() {
        val near = ControlCatalog.suggest("wifi", "enable")
        assertTrue(near.isNotEmpty() && near.all { it.group == "wifi" })
        assertTrue(ControlCatalog.suggest(null, null).isEmpty())
    }

    @Test fun `the handler has a branch for every action and none the catalog lacks`() {
        val src = ProductionSources.read("sandbox/offload/ControlOffloadHandler.kt")
        ControlCatalog.entries.forEach { e ->
            // The media keys share one branch that looks the key up by name.
            if (e.group == "media") assertTrue("no media key for ${e.name}", ControlCommands.mediaKey(e.name) != null)
            else assertTrue("handler has no branch for '${e.key}'", src.contains("\"${e.key}\""))
        }
        // The reverse: a quoted "<group> <name>" in a branch that the catalog does not know is a typo.
        val known = ControlCatalog.entries.map { it.key }.toSet()
        val groups = ControlCatalog.groups.toSet()
        Regex("\"([a-z]+) ([a-z-]+)\"(?:,\\s*\"[a-z]+ [a-z-]+\")*\\s*->").findAll(src).forEach { m ->
            Regex("\"([a-z]+) ([a-z-]+)\"").findAll(m.value).forEach { k ->
                if (k.groupValues[1] in groups) assertTrue("handler branch ${k.value} is not in the catalog", k.value.trim('"') in known)
            }
        }
    }

    @Test fun `the handler is registered and gated`() {
        assertTrue(ProductionSources.read("MinisApp.kt").contains("\"android-control\""))
        assertTrue(ProductionSources.read("offload/OffloadPermissionManager.kt").contains("\"device_control\""))
        assertTrue(ProductionSources.read("ui/chat/ChatViewModel.kt").contains("- android-control"))
    }
}
