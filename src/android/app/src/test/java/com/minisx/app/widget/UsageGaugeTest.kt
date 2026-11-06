package com.minisx.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageGaugeTest {

    @Test fun `the fill is proportional to what is left`() {
        assertEquals(80f, UsageGauge.fillWidth(80.0, 100f, 7f), 0.001f)
        assertEquals(62f, UsageGauge.fillWidth(62.0, 100f, 7f), 0.001f)
        assertEquals(100f, UsageGauge.fillWidth(100.0, 100f, 7f), 0.001f)
    }

    @Test fun `nothing left is an empty bar, a sliver stays visible`() {
        assertEquals(0f, UsageGauge.fillWidth(0.0, 100f, 7f), 0f)
        assertEquals(0f, UsageGauge.fillWidth(-5.0, 100f, 7f), 0f)
        assertEquals(7f, UsageGauge.fillWidth(1.0, 100f, 7f), 0.001f)
        assertEquals(100f, UsageGauge.fillWidth(250.0, 100f, 7f), 0.001f)
    }

    @Test fun `a cap wider than the track never overflows it`() {
        assertEquals(5f, UsageGauge.fillWidth(1.0, 5f, 7f), 0.001f)
    }

    @Test fun `different amounts left give different bars`() {
        // The old segmented bar lost everything after its fifth block, so 80% and 62% looked the same.
        assertTrue(UsageGauge.fillWidth(80.0, 120f, 7f) > UsageGauge.fillWidth(62.0, 120f, 7f))
    }

    @Test fun `the bar gets what the fixed parts leave`() {
        assertEquals(126f, UsageGauge.barWidth(rowWidth = 200f, reserved = 70f), 0.001f)
        assertEquals(40f, UsageGauge.barWidth(rowWidth = 60f, reserved = 70f), 0.001f)
    }

    @Test fun `tone follows the amount left`() {
        assertEquals(UsageGauge.Tone.HEALTHY, UsageGauge.tone(80.0))
        assertEquals(UsageGauge.Tone.HEALTHY, UsageGauge.tone(20.5))
        assertEquals(UsageGauge.Tone.CAUTION, UsageGauge.tone(20.0))
        assertEquals(UsageGauge.Tone.CAUTION, UsageGauge.tone(8.5))
        assertEquals(UsageGauge.Tone.CRITICAL, UsageGauge.tone(8.0))
        assertEquals(UsageGauge.Tone.CRITICAL, UsageGauge.tone(0.0))
    }
}
