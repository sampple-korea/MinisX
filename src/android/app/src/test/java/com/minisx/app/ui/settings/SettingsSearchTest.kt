package com.minisx.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun `every word must appear in the title or the subtitle`() {
        assertTrue(settingsRowMatches("memory", "Memory", "Persistent knowledge across sessions"))
        assertTrue(settingsRowMatches("persistent knowledge", "Memory", "Persistent knowledge across sessions"))
        assertFalse(settingsRowMatches("memory soul", "Memory", "Persistent knowledge across sessions"))
    }

    @Test fun `case and surrounding spaces do not matter`() {
        assertTrue(settingsRowMatches("  MCP ", "MCP Integrations", null))
        assertTrue(settingsRowMatches("메모리", "메모리", "세션 간에 유지되는 영구적 지식"))
    }

    @Test fun `a blank query matches everything`() {
        assertTrue(settingsRowMatches("", "Anything", null))
    }
}
