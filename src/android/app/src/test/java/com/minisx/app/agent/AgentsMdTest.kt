package com.minisx.app.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentsMdTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `override file wins over the plain one`() {
        val d = tmp.newFolder()
        File(d, "AGENTS.md").writeText("plain")
        File(d, "AGENTS.override.md").writeText("override")
        assertEquals("override", AgentsMd.readDir(d))
    }

    @Test fun `an empty file counts as no file`() {
        val d = tmp.newFolder()
        File(d, "AGENTS.override.md").writeText("  \n")
        File(d, "AGENTS.md").writeText("plain")
        assertEquals("plain", AgentsMd.readDir(d))
    }

    @Test fun `user text comes first and the project follows after the separator`() {
        assertEquals("a" + AgentsMd.SEPARATOR + "b", AgentsMd.combine("a", "b"))
        assertEquals("a", AgentsMd.combine("a", null))
        assertEquals("b", AgentsMd.combine(null, "b"))
        assertNull(AgentsMd.combine(null, null))
    }

    @Test fun `the budget cuts on a character boundary`() {
        val text = "가".repeat(100) // 3 bytes each
        val cut = AgentsMd.combine(text, null, maxBytes = 10)!!
        assertEquals("가".repeat(3), cut)
    }

    @Test fun `the fragment is wrapped for the prompt`() {
        val d = tmp.newFolder()
        File(d, "AGENTS.md").writeText("Use tabs.")
        assertEquals("<user_instructions>\nUse tabs.\n</user_instructions>", AgentsMd.load(d, null))
        assertTrue(AgentsMd.load(null, null) == null)
    }
}
