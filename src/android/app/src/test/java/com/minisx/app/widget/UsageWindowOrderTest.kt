package com.minisx.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** The small widget lists the 5-hour window before the weekly one, whichever is tighter. */
class UsageWindowOrderTest {
    @Test
    fun `windows are ordered shortest first`() {
        val windows = listOf("7d", "5h", "30m", "month")
        assertEquals(listOf("30m", "5h", "7d", "month"), windows.sortedBy { UsageWidgetModel.windowMinutes(it) })
        assertEquals(300, UsageWidgetModel.windowMinutes("5h"))
        assertEquals(10080, UsageWidgetModel.windowMinutes("7d"))
    }
}
