package com.minisx.app.agent.jobs

import com.minisx.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shared-notes] The scratchpad a conversation shares with the sub agents it delegates to.
 */
class AgentSharedNotesTest {

    @Test
    fun `one board is shared by a parent and its sub agent`() {
        val board = "parent-session-test"
        AgentSharedNotes.clear(board)
        assertTrue(AgentSharedNotes.write(board, "api-paths", "src/api/client.ts", "agent"))
        assertTrue(AgentSharedNotes.write(board, "build", "./gradlew :app:assembleDebug", "sub agent"))
        assertEquals("src/api/client.ts", AgentSharedNotes.read(board, "api-paths")?.value)
        assertEquals(2, AgentSharedNotes.list(board).size)
        assertTrue(AgentSharedNotes.render(board).contains("api-paths"))
        AgentSharedNotes.clear(board)
        assertTrue(AgentSharedNotes.list(board).isEmpty())
    }

    @Test
    fun `write replaces a note instead of duplicating the key`() {
        val board = "replace-test"
        AgentSharedNotes.clear(board)
        assertTrue(AgentSharedNotes.write(board, "api-paths", "v1", "agent"))
        assertTrue(AgentSharedNotes.write(board, "api-paths", "v2", "sub agent"))
        assertEquals(1, AgentSharedNotes.list(board).count { it.key == "api-paths" })
        assertEquals("v2", AgentSharedNotes.read(board, "api-paths")?.value)
        AgentSharedNotes.clear(board)
    }

    @Test
    fun `a full board refuses a new key instead of evicting a note someone relies on`() {
        val board = "full-board-test"
        AgentSharedNotes.clear(board)
        for (i in 0 until AgentSharedNotes.MAX_ENTRIES) {
            assertTrue(AgentSharedNotes.write(board, "k$i", "v$i", "agent"))
        }
        assertFalse(AgentSharedNotes.write(board, "one-too-many", "v", "agent"))
        // An existing key is still updatable — correcting a note is not an addition.
        assertTrue(AgentSharedNotes.write(board, "k0", "changed", "agent"))
        AgentSharedNotes.clear(board)
    }

    @Test
    fun `a blank key or board is refused`() {
        assertFalse(AgentSharedNotes.write("board-x", "  ", "v", "agent"))
        assertFalse(AgentSharedNotes.write("  ", "k", "v", "agent"))
        AgentSharedNotes.clear("board-x")
    }

    @Test
    fun `boards do not leak into each other`() {
        AgentSharedNotes.clear("board-a")
        AgentSharedNotes.clear("board-b")
        assertTrue(AgentSharedNotes.write("board-a", "k", "only in a", "agent"))
        assertFalse(AgentSharedNotes.hasEntries("board-b"))
        AgentSharedNotes.clear("board-a")
        AgentSharedNotes.clear("board-b")
    }

    @Test
    fun `the tool rides the agents switch and reaches helpers too`() {
        fun names(delegate: Boolean, helper: Boolean) =
            AgentTools.makeAgentTools(delegateEnabled = delegate, isHelper = helper).map { it.name }
        assertTrue(AgentSharedNotes.TOOL_NAME in names(delegate = true, helper = false))
        assertTrue("a sub agent must read the same board", AgentSharedNotes.TOOL_NAME in names(delegate = true, helper = true))
        assertFalse(AgentSharedNotes.TOOL_NAME in names(delegate = false, helper = false))
    }

    @Test
    fun `the schema names its actions and needs no arguments beyond a title`() {
        val def = AgentSharedNotes.definition()
        assertEquals(listOf("tool_title"), def.required)
        assertEquals(listOf("list", "read", "write"), def.parameters["action"]?.enumValues)
    }
}
