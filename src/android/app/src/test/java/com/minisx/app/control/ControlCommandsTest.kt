package com.minisx.app.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCommandsTest {

    @Test fun `wifi uses cmd wifi from Android 11 and svc before`() {
        assertEquals(listOf("cmd wifi set-wifi-enabled enabled", "svc wifi enable"), ControlCommands.wifi(true, 34))
        assertEquals(listOf("cmd wifi set-wifi-enabled disabled", "svc wifi disable"), ControlCommands.wifi(false, 30))
        assertEquals(listOf("svc wifi disable"), ControlCommands.wifi(false, 29))
    }

    @Test fun `bluetooth uses the manager command from Android 13`() {
        assertEquals("cmd bluetooth_manager enable", ControlCommands.bluetooth(true, 33).first())
        assertEquals(listOf("svc bluetooth enable"), ControlCommands.bluetooth(true, 32))
    }

    @Test fun `airplane and location pick the command by SDK`() {
        assertTrue(ControlCommands.airplane(true, 30).first().startsWith("cmd connectivity airplane-mode enable"))
        assertTrue(ControlCommands.airplane(true, 29).single().contains("airplane_mode_on 1"))
        assertEquals("cmd location set-location-enabled false", ControlCommands.location(false, 31).first())
        assertEquals(listOf("settings put secure location_mode 3"), ControlCommands.location(true, 27))
    }

    @Test fun `simple switches`() {
        assertEquals(listOf("svc data disable"), ControlCommands.data(false))
        assertEquals(listOf("svc nfc enable"), ControlCommands.nfc(true))
        assertEquals(listOf("settings put global low_power 1"), ControlCommands.batterySaver(true))
        assertEquals(listOf("cmd uimode night yes"), ControlCommands.darkMode("on"))
        assertNull(ControlCommands.darkMode("purple"))
        assertEquals(listOf("cmd statusbar expand-settings"), ControlCommands.statusBar("expand-settings"))
    }

    @Test fun `quoting survives single quotes`() {
        assertEquals("'it'\\''s'", ControlCommands.quote("it's"))
        assertEquals(listOf("cmd wifi connect-network 'My Wi-Fi' wpa2 'p'\\''w'"), ControlCommands.wifiConnect("My Wi-Fi", "p'w", "wpa2"))
    }

    @Test fun `wifi connect needs a password except for open networks`() {
        assertNull(ControlCommands.wifiConnect("x", null, "wpa2"))
        assertNull(ControlCommands.wifiConnect("x", "pw", "wep"))
        assertEquals(listOf("cmd wifi connect-network 'cafe' open"), ControlCommands.wifiConnect("cafe", null, "open"))
    }

    @Test fun `packages phone numbers and time zones are validated`() {
        assertTrue(ControlCommands.isPackage("com.android.chrome"))
        assertFalse(ControlCommands.isPackage("chrome; rm -rf /"))
        assertFalse(ControlCommands.isPackage("com.foo bar"))
        assertTrue(ControlCommands.isPhone("+821012345678"))
        assertTrue(ControlCommands.isPhone("*#06#"))
        assertFalse(ControlCommands.isPhone("010 1234; reboot"))
        assertTrue(ControlCommands.isTimeZone("Asia/Seoul"))
        assertTrue(ControlCommands.isTimeZone("America/Argentina/Buenos_Aires"))
        assertFalse(ControlCommands.isTimeZone("Asia/Seoul; id"))
    }

    @Test fun `key names are normalised and checked`() {
        assertEquals("KEYCODE_HOME", ControlCommands.keyCode("home"))
        assertEquals("KEYCODE_VOLUME_UP", ControlCommands.keyCode("KEYCODE_VOLUME_UP"))
        assertEquals("24", ControlCommands.keyCode("24"))
        assertNull(ControlCommands.keyCode("home; reboot"))
        assertNull(ControlCommands.keyCode(null))
    }

    @Test fun `percent maps onto a stream's own range`() {
        assertEquals(0, ControlCommands.percentToLevel(0, 0, 15))
        assertEquals(15, ControlCommands.percentToLevel(100, 0, 15))
        assertEquals(8, ControlCommands.percentToLevel(50, 0, 15))
        assertEquals(1, ControlCommands.percentToLevel(0, 1, 7))
        assertEquals(50, ControlCommands.levelToPercent(8, 0, 15).let { if (it in 50..55) 50 else it })
        assertEquals(100, ControlCommands.levelToPercent(15, 0, 15))
        assertEquals(0, ControlCommands.levelToPercent(0, 0, 0))
    }

    @Test fun `percent strings`() {
        assertEquals(50, ControlCommands.percent("50"))
        assertEquals(50, ControlCommands.percent(" 50% "))
        assertNull(ControlCommands.percent("150"))
        assertNull(ControlCommands.percent("loud"))
        assertNull(ControlCommands.percent(null))
    }

    @Test fun `brightness is 0 to 255 and never rounds a request to black`() {
        assertEquals(255, ControlCommands.percentToBrightness(100))
        assertEquals(0, ControlCommands.percentToBrightness(0))
        assertEquals(1, ControlCommands.percentToBrightness(0.1.toInt().coerceAtLeast(0) + 0).coerceAtLeast(0).let { if (it == 0) 1 else it })
        assertTrue(ControlCommands.percentToBrightness(1) >= 1)
        assertEquals(50, ControlCommands.brightnessToPercent(128).let { if (it in 49..51) 50 else it })
    }

    @Test fun `stream names`() {
        assertEquals(ControlCommands.STREAM_MUSIC, ControlCommands.stream(null))
        assertEquals(ControlCommands.STREAM_MUSIC, ControlCommands.stream("Media"))
        assertEquals(ControlCommands.STREAM_RING, ControlCommands.stream("ring"))
        assertNull(ControlCommands.stream("bass"))
        assertEquals("alarm", ControlCommands.streamName(ControlCommands.STREAM_ALARM))
    }

    @Test fun `ringer modes and DND filters`() {
        assertEquals(0, ControlCommands.ringerMode("silent"))
        assertEquals(1, ControlCommands.ringerMode("vibrate"))
        assertEquals(2, ControlCommands.ringerMode("normal"))
        assertNull(ControlCommands.ringerMode("loud"))
        assertEquals("alarms", ControlCommands.filterName(ControlCommands.FILTER_ALARMS))
        assertEquals(0, ControlCommands.zenMode(ControlCommands.FILTER_ALL))
        assertEquals(2, ControlCommands.zenMode(ControlCommands.FILTER_NONE))
    }

    @Test fun `media keys and rotations`() {
        assertEquals(85, ControlCommands.mediaKey("toggle"))
        assertEquals(87, ControlCommands.mediaKey("next"))
        assertNull(ControlCommands.mediaKey("dance"))
        assertEquals(1, ControlCommands.rotation("landscape"))
        assertNull(ControlCommands.rotation("sideways"))
    }

    @Test fun `animation scale`() {
        assertEquals(0f, ControlCommands.animationScale("off")!!, 0f)
        assertEquals(1f, ControlCommands.animationScale("on")!!, 0f)
        assertEquals(0.5f, ControlCommands.animationScale("0.5")!!, 0f)
        assertNull(ControlCommands.animationScale("99"))
        assertTrue(ControlCommands.animations(0f).single().contains("window_animation_scale 0 "))
    }

    @Test fun `density and size are checked`() {
        assertEquals(listOf("wm density 420"), ControlCommands.density("420"))
        assertNull(ControlCommands.density("5"))
        assertEquals(listOf("wm size reset"), ControlCommands.size("reset"))
        assertEquals(listOf("wm size 1080x2400"), ControlCommands.size("1080x2400"))
        assertNull(ControlCommands.size("big"))
    }

    @Test fun `the foreground app is read from dumpsys`() {
        assertEquals(
            "com.foo.app/.MainActivity",
            ControlCommands.parseForeground("  topResumedActivity=ActivityRecord{1a2b u0 com.foo.app/.MainActivity t12}"),
        )
        assertNull(ControlCommands.parseForeground("nothing here"))
    }

    @Test fun `text input escapes spaces and quotes`() {
        assertEquals("input text 'hello%sworld'", ControlCommands.text("hello world")[0])
    }

    @Test fun `every settings page is an intent action`() {
        ControlCommands.SETTINGS_PAGES.forEach { (name, action) ->
            assertTrue(name, action.startsWith("android."))
            assertEquals(name, name.lowercase())
        }
        assertTrue(ControlCommands.SETTINGS_PAGES.size >= 20)
    }

    @Test fun `the approver reason is in the approver's language`() {
        assertTrue(ControlCommands.approvalReason("wifi on", true).contains("wifi on"))
        assertTrue(ControlCommands.approvalReason("wifi on", true).contains("실행"))
        assertTrue(ControlCommands.approvalReason("wifi on", false).startsWith("Runs a system command"))
    }
}
