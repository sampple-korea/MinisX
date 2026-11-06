package com.minisx.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCommandsTest {

    private fun argv(text: String) = FastCommands.parse(text)?.argv

    @Test fun `flashlight in Korean and English`() {
        assertEquals(listOf("flashlight", "on"), argv("손전등 켜줘"))
        assertEquals(listOf("flashlight", "off"), argv("손전등 꺼줘"))
        assertEquals(listOf("flashlight", "on"), argv("플래시 켜"))
        assertEquals(listOf("flashlight", "on"), argv("랜턴 좀 켜주세요"))
        assertEquals(listOf("flashlight", "on"), argv("turn on the flashlight"))
        assertEquals(listOf("flashlight", "off"), argv("flashlight off please"))
        assertEquals(listOf("flashlight", "on"), argv("손전등 앱 켜줘!"))
    }

    @Test fun `switches`() {
        assertEquals(listOf("wifi", "off"), argv("와이파이 꺼줘"))
        assertEquals(listOf("wifi", "on"), argv("wifi 켜"))
        assertEquals(listOf("bluetooth", "on"), argv("블루투스 켜줘"))
        assertEquals(listOf("airplane", "on"), argv("비행기 모드 켜줘"))
        assertEquals(listOf("data", "off"), argv("모바일 데이터 꺼줘"))
        assertEquals(listOf("data", "off"), argv("데이터 꺼"))
        assertEquals(listOf("nfc", "on"), argv("NFC 켜줘"))
        assertEquals(listOf("location", "off"), argv("위치 꺼줘"))
        assertEquals(listOf("dnd", "priority"), argv("방해금지 켜줘"))
        assertEquals(listOf("dnd", "off"), argv("방해 금지 꺼"))
        assertEquals(listOf("display", "dark-mode", "on"), argv("다크 모드 켜줘"))
        assertEquals(listOf("battery", "saver", "on"), argv("절전 모드 켜줘"))
        assertEquals(listOf("screen", "rotation", "auto"), argv("자동 회전 켜줘"))
    }

    @Test fun `volume`() {
        assertEquals(listOf("volume", "set", "music", "50"), argv("볼륨 50"))
        assertEquals(listOf("volume", "set", "music", "30"), argv("볼륨 30%로 맞춰줘"))
        assertEquals(listOf("volume", "set", "ring", "70"), argv("벨소리 볼륨 70"))
        assertEquals(listOf("volume", "set", "alarm", "100"), argv("알람 볼륨 100으로"))
        assertEquals(listOf("volume", "up", "music"), argv("볼륨 올려줘"))
        assertEquals(listOf("volume", "down", "music"), argv("소리 줄여"))
        assertEquals(listOf("volume", "max", "music"), argv("볼륨 최대로"))
        assertEquals(listOf("volume", "mute"), argv("음소거 해줘"))
        assertEquals(listOf("volume", "unmute"), argv("음소거 해제"))
        assertEquals(listOf("volume", "set", "music", "20"), argv("set volume to 20"))
    }

    @Test fun `ringer modes win over the volume words they share`() {
        assertEquals(listOf("ringer", "set", "silent"), argv("무음 모드로 바꿔줘"))
        assertEquals(listOf("ringer", "set", "vibrate"), argv("진동 모드로"))
        assertEquals(listOf("ringer", "set", "normal"), argv("소리 모드로 바꿔줘"))
    }

    @Test fun `brightness`() {
        assertEquals(listOf("brightness", "set", "30"), argv("밝기 30%"))
        assertEquals(listOf("brightness", "set", "100"), argv("밝기 최대로"))
        assertEquals(listOf("brightness", "auto", "on"), argv("자동 밝기 켜줘"))
        assertNull(argv("밝기"))
    }

    @Test fun `navigation and screen`() {
        assertEquals(listOf("nav", "home"), argv("홈으로 가줘"))
        assertEquals(listOf("nav", "back"), argv("뒤로 가"))
        assertEquals(listOf("nav", "recents"), argv("최근 앱 보여줘"))
        assertEquals(listOf("nav", "notifications"), argv("알림창 열어줘"))
        assertEquals(listOf("nav", "quick-settings"), argv("빠른 설정 열어줘"))
        assertEquals(listOf("nav", "screenshot"), argv("스크린샷 찍어줘"))
        assertEquals(listOf("screen", "off"), argv("화면 꺼줘"))
        assertEquals(listOf("screen", "wake"), argv("화면 켜줘"))
        assertEquals(listOf("camera", "photo"), argv("카메라 열어줘"))
    }

    @Test fun `an app name opens the app`() {
        assertEquals(listOf("app", "launch", "유튜브"), argv("유튜브 열어줘"))
        assertEquals(listOf("app", "launch", "카카오톡"), argv("카카오톡 켜줘"))
        assertEquals(listOf("app", "launch", "chrome"), argv("open chrome"))
        assertNull(argv("파일 열어줘"))
        assertNull(argv("이거 열어줘"))
    }

    @Test fun `two things in one message go to the model`() {
        assertNull(argv("와이파이랑 블루투스 꺼줘"))
        assertNull(argv("와이파이 켜고 블루투스 꺼줘"))
        assertNull(argv("손전등 켜고 와이파이 꺼"))
    }

    @Test fun `questions, timed and long requests go to the model`() {
        assertNull(argv("와이파이 끄면 어떻게 돼?"))
        assertNull(argv("손전등 켜는 방법 알려줘"))
        assertNull(argv("10분 뒤에 손전등 꺼줘"))
        assertNull(argv("내일 아침에 와이파이 꺼줘"))
        assertNull(argv("5분 후에 볼륨 줄여줘"))
        assertNull(argv("손전등 ".repeat(20) + "켜줘"))
        assertNull(argv(""))
        assertNull(argv("안녕"))
        assertNull(argv("내 위치 알려줘"))
    }

    @Test fun `messages carry what was done`() {
        val c = FastCommands.parse("손전등 켜줘")!!
        assertEquals("손전등을 켰어요.", c.message(true))
        assertEquals("Flashlight on.", c.message(false))
        assertEquals("Wi-Fi를 껐어요.", FastCommands.parse("와이파이 꺼")!!.message(true))
        assertEquals("블루투스를 켰어요.", FastCommands.parse("블루투스 켜")!!.message(true))
        assertEquals("유튜브를 열었어요.", FastCommands.parse("유튜브 열어")!!.message(true))
        assertEquals("카카오톡을 열었어요.", FastCommands.parse("카카오톡 열어")!!.message(true))
        assertEquals("미디어 볼륨을 50%로 맞췄어요.", FastCommands.parse("볼륨 50")!!.message(true))
    }

    @Test fun `the control's answer is read`() {
        val cmd = FastCommands.parse("손전등 켜줘")!!
        val ok = """{"ok":true,"action":"flashlight on","backend":"camera2","data":{"on":true}}"""
        assertEquals(FastCommands.Outcome.Done("손전등을 켰어요."), FastCommands.interpret(0, ok, cmd, true))
        val needs = """{"ok":false,"action":"wifi off","error":{"code":"needs_user","message":"x"}}"""
        assertTrue(FastCommands.interpret(1, needs, cmd, true) is FastCommands.Outcome.NeedsUser)
        val failed = """{"ok":false,"action":"flashlight on","error":{"code":"camera_in_use","message":"busy"}}"""
        assertEquals(FastCommands.Outcome.Fallback, FastCommands.interpret(1, failed, cmd, true))
        assertEquals(FastCommands.Outcome.Fallback, FastCommands.interpret(1, "not json", cmd, true))
    }

    @Test fun `every command it can produce exists in the control catalog`() {
        val samples = listOf(
            "손전등 켜", "와이파이 꺼", "블루투스 켜", "모바일 데이터 꺼", "비행기 모드 켜", "NFC 켜", "위치 꺼", "방해금지 켜", "방해금지 꺼",
            "다크 모드 켜", "절전 모드 켜", "야간 조명 켜", "자동 회전 켜", "자동 회전 꺼", "무음 모드로", "진동 모드로", "소리 모드로", "음소거", "음소거 해제",
            "볼륨 50", "볼륨 올려", "볼륨 내려", "볼륨 최대로", "밝기 30", "밝기 최대로", "밝기 최소로", "자동 밝기 켜", "홈으로", "뒤로 가", "최근 앱 열어",
            "알림창 열어", "빠른 설정 열어", "스크린샷 찍어", "화면 꺼", "화면 켜", "사진 찍어", "카메라 열어", "유튜브 열어",
        )
        for (s in samples) {
            val c = FastCommands.parse(s)
            assertNotNull("'$s' was not understood", c)
            val key = c!!.argv.take(2).joinToString(" ")
            assertNotNull("'$s' -> '$key' is not an android-control action", com.minisx.app.control.ControlCatalog.find(c.argv[0], c.argv.getOrNull(1)))
        }
    }
}
