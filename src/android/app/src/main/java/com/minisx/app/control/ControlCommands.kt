package com.minisx.app.control

/**
 * What `android-control` runs, worked out without touching Android: shell command candidates per SDK
 * level, quoting, name-to-constant tables and the percent arithmetic. The handler picks the first
 * candidate that works.
 *
 * Every value that ends up in a shell command is validated or quoted here, so the handler never
 * builds a command from raw model input.
 */
object ControlCommands {

    /** POSIX single-quote quoting. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PHONE = Regex("^[0-9+*#,;]{1,32}$")
    private val ZONE = Regex("^[A-Za-z]+(/[A-Za-z0-9_+\\-]+){1,2}$|^UTC$|^GMT$")
    private val KEY = Regex("^(KEYCODE_[A-Z0-9_]+|[0-9]{1,3})$")

    fun isPackage(s: String?): Boolean = s != null && PACKAGE.matches(s)
    fun isPhone(s: String?): Boolean = s != null && PHONE.matches(s)
    fun isTimeZone(s: String?): Boolean = s != null && ZONE.matches(s)

    /** `HOME` and `home` and `3` and `KEYCODE_HOME` all name a key; anything else is rejected. */
    fun keyCode(s: String?): String? {
        val t = s?.trim()?.uppercase() ?: return null
        val candidate = if (t.all { it.isDigit() }) t else if (t.startsWith("KEYCODE_")) t else "KEYCODE_$t"
        return candidate.takeIf { KEY.matches(it) }
    }

    // ── streams and percentages ────────────────────────────────────────────

    const val STREAM_VOICE_CALL = 0
    const val STREAM_SYSTEM = 1
    const val STREAM_RING = 2
    const val STREAM_MUSIC = 3
    const val STREAM_ALARM = 4
    const val STREAM_NOTIFICATION = 5

    private val STREAMS = mapOf(
        "music" to STREAM_MUSIC, "media" to STREAM_MUSIC, "ring" to STREAM_RING, "ringtone" to STREAM_RING,
        "alarm" to STREAM_ALARM, "notification" to STREAM_NOTIFICATION, "notif" to STREAM_NOTIFICATION,
        "call" to STREAM_VOICE_CALL, "voice" to STREAM_VOICE_CALL, "system" to STREAM_SYSTEM,
    )

    /** The streams `volume get` reports, in order. */
    val REPORTED_STREAMS: List<Pair<String, Int>> = listOf(
        "music" to STREAM_MUSIC, "ring" to STREAM_RING, "alarm" to STREAM_ALARM,
        "notification" to STREAM_NOTIFICATION, "call" to STREAM_VOICE_CALL, "system" to STREAM_SYSTEM,
    )

    fun stream(name: String?): Int? = if (name == null) STREAM_MUSIC else STREAMS[name.lowercase()]

    fun streamName(stream: Int): String = REPORTED_STREAMS.firstOrNull { it.second == stream }?.first ?: "stream$stream"

    /** [percent] (0..100) of the range [min]..[max], rounded to the nearest step. */
    fun percentToLevel(percent: Int, min: Int, max: Int): Int {
        val p = percent.coerceIn(0, 100)
        return (min + Math.round((max - min) * p / 100.0).toInt()).coerceIn(min, max)
    }

    fun levelToPercent(level: Int, min: Int, max: Int): Int =
        if (max <= min) 0 else Math.round((level - min) * 100.0 / (max - min)).toInt().coerceIn(0, 100)

    /** `50`, `50%` and ` 50 ` all mean 50; anything else is null. */
    fun percent(s: String?): Int? = s?.trim()?.removeSuffix("%")?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }

    /** System brightness is 0..255 in `Settings.System`. */
    fun percentToBrightness(percent: Int): Int = Math.round(percent.coerceIn(0, 100) * 255 / 100.0).toInt().coerceAtLeast(if (percent > 0) 1 else 0)

    fun brightnessToPercent(value: Int): Int = Math.round(value.coerceIn(0, 255) * 100 / 255.0).toInt()

    // ── names to constants ─────────────────────────────────────────────────

    /** `Settings.System.USER_ROTATION` values. */
    fun rotation(name: String?): Int? = when (name?.lowercase()) {
        "portrait" -> 0
        "landscape" -> 1
        "reverse-portrait", "upside-down" -> 2
        "reverse-landscape" -> 3
        else -> null
    }

    /** `AudioManager` ringer modes: 2 normal, 1 vibrate, 0 silent. */
    fun ringerMode(name: String?): Int? = when (name?.lowercase()) {
        "normal", "sound", "ring" -> 2
        "vibrate" -> 1
        "silent", "mute" -> 0
        else -> null
    }

    fun ringerName(mode: Int): String = when (mode) { 2 -> "normal"; 1 -> "vibrate"; 0 -> "silent"; else -> "unknown" }

    /** `NotificationManager` interruption filters. */
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    fun filterName(f: Int): String = when (f) {
        FILTER_ALL -> "off"; FILTER_PRIORITY -> "priority"; FILTER_NONE -> "silence"; FILTER_ALARMS -> "alarms"; else -> "unknown"
    }

    /** `settings put global zen_mode` values, for when the notification-policy grant is missing. */
    fun zenMode(filter: Int): Int = when (filter) { FILTER_PRIORITY -> 1; FILTER_NONE -> 2; FILTER_ALARMS -> 3; else -> 0 }

    /** `KeyEvent` keycodes `media` sends. */
    fun mediaKey(action: String): Int? = when (action) {
        "play" -> 126; "pause" -> 127; "toggle" -> 85; "next" -> 87; "prev" -> 88
        "stop" -> 86; "forward" -> 90; "rewind" -> 89
        else -> null
    }

    /** `adb`'s name for the sensor keys `sensor read` accepts. */
    val SENSORS = mapOf(
        "light" to 5, "accelerometer" to 1, "gyroscope" to 4, "proximity" to 8, "pressure" to 6,
        "magnetic" to 2, "temperature" to 13, "humidity" to 12, "steps" to 19,
    )

    /** Pages `settings open` knows: name to intent action. */
    val SETTINGS_PAGES: Map<String, String> = linkedMapOf(
        "main" to "android.settings.SETTINGS",
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "battery-saver" to "android.settings.BATTERY_SAVER_SETTINGS",
        "battery-optimization" to "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
        "apps" to "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
        "default-apps" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "notifications" to "android.settings.NOTIFICATION_LISTENER_SETTINGS",
        "dnd-access" to "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "developer" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "keyboard" to "android.settings.INPUT_METHOD_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "security" to "android.settings.SECURITY_SETTINGS",
        "privacy" to "android.settings.PRIVACY_SETTINGS",
        "about" to "android.settings.DEVICE_INFO_SETTINGS",
        "nfc" to "android.settings.NFC_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "wireless" to "android.settings.WIRELESS_SETTINGS",
        "data-usage" to "android.settings.DATA_USAGE_SETTINGS",
        "vpn" to "android.settings.VPN_SETTINGS",
        "cast" to "android.settings.CAST_SETTINGS",
        "home" to "android.settings.HOME_SETTINGS",
        "write-settings" to "android.settings.action.MANAGE_WRITE_SETTINGS",
        "overlay" to "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        "all-files" to "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION",
        "exact-alarms" to "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
        "panel-internet" to "android.settings.panel.action.INTERNET_CONNECTIVITY",
        "panel-wifi" to "android.settings.panel.action.WIFI",
        "panel-volume" to "android.settings.panel.action.VOLUME",
        "panel-nfc" to "android.settings.panel.action.NFC",
    )

    // ── privileged command candidates (first that exits 0 wins) ───────────

    private fun on(on: Boolean, yes: String, no: String) = if (on) yes else no

    fun wifi(on: Boolean, sdk: Int): List<String> = buildList {
        if (sdk >= 30) add("cmd wifi set-wifi-enabled ${on(on, "enabled", "disabled")}")
        add("svc wifi ${on(on, "enable", "disable")}")
    }

    fun bluetooth(on: Boolean, sdk: Int): List<String> = buildList {
        if (sdk >= 33) add("cmd bluetooth_manager ${on(on, "enable", "disable")}")
        add("svc bluetooth ${on(on, "enable", "disable")}")
    }

    fun data(on: Boolean): List<String> = listOf("svc data ${on(on, "enable", "disable")}")

    fun airplane(on: Boolean, sdk: Int): List<String> = buildList {
        if (sdk >= 30) add("cmd connectivity airplane-mode ${on(on, "enable", "disable")}")
        add("settings put global airplane_mode_on ${if (on) 1 else 0} && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on")
    }

    fun location(on: Boolean, sdk: Int): List<String> = buildList {
        if (sdk >= 28) add("cmd location set-location-enabled $on")
        add("settings put secure location_mode ${if (on) 3 else 0}")
    }

    fun nfc(on: Boolean): List<String> = listOf("svc nfc ${on(on, "enable", "disable")}")

    fun batterySaver(on: Boolean): List<String> = listOf("settings put global low_power ${if (on) 1 else 0}")

    fun dataSaver(on: Boolean): List<String> = listOf("cmd netpolicy set restrict-background $on")

    fun darkMode(mode: String): List<String>? = when (mode) {
        "on" -> listOf("cmd uimode night yes")
        "off" -> listOf("cmd uimode night no")
        "auto" -> listOf("cmd uimode night auto")
        else -> null
    }

    fun nightLight(on: Boolean): List<String> = listOf(
        "settings put secure night_display_activated ${if (on) 1 else 0}",
    )

    fun invert(on: Boolean): List<String> = listOf(
        "settings put secure accessibility_display_inversion_enabled ${if (on) 1 else 0}",
    )

    fun stayAwake(on: Boolean): List<String> = listOf("settings put global stay_on_while_plugged_in ${if (on) 7 else 0}")

    fun animations(scale: Float): List<String> {
        val s = if (scale == scale.toLong().toFloat()) scale.toLong().toString() else scale.toString()
        return listOf(
            "settings put global window_animation_scale $s && settings put global transition_animation_scale $s && settings put global animator_duration_scale $s",
        )
    }

    /** `on`, `off` or a number in 0..10; the handler turns it into a scale. */
    fun animationScale(s: String?): Float? = when (s?.lowercase()) {
        "on" -> 1f
        "off" -> 0f
        null -> null
        else -> s.toFloatOrNull()?.takeIf { it in 0f..10f }
    }

    fun statusBar(what: String): List<String> = listOf("cmd statusbar $what")

    fun wake(): List<String> = listOf("input keyevent 224")
    fun sleep(): List<String> = listOf("input keyevent 223")
    fun key(code: String): List<String> = listOf("input keyevent $code")
    fun tap(x: Int, y: Int): List<String> = listOf("input tap $x $y")
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): List<String> = listOf("input swipe $x1 $y1 $x2 $y2 $ms")
    fun text(t: String): List<String> = listOf("input text ${quote(t.replace(" ", "%s"))}")
    fun screencap(path: String): List<String> = listOf("screencap -p ${quote(path)}")
    fun density(arg: String): List<String>? = if (arg == "reset") listOf("wm density reset") else arg.toIntOrNull()?.takeIf { it in 72..960 }?.let { listOf("wm density $it") }
    fun size(arg: String): List<String>? =
        if (arg == "reset") listOf("wm size reset") else Regex("^(\\d{3,5})x(\\d{3,5})$").matchEntire(arg)?.let { listOf("wm size $arg") }

    fun forceStop(pkg: String): List<String> = listOf("am force-stop $pkg")
    fun clearData(pkg: String): List<String> = listOf("pm clear $pkg")
    fun enableApp(pkg: String): List<String> = listOf("pm enable $pkg")
    fun disableApp(pkg: String): List<String> = listOf("pm disable-user --user 0 $pkg")
    fun foreground(): List<String> = listOf("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")

    fun reboot(): List<String> = listOf("svc power reboot")
    fun shutdown(): List<String> = listOf("svc power shutdown")

    fun call(number: String): List<String> = listOf("am start -a android.intent.action.CALL -d ${quote("tel:$number")}")
    fun answerCall(): List<String> = listOf("input keyevent 5")
    fun endCall(): List<String> = listOf("input keyevent 6")

    fun setTimeZone(zone: String): List<String> = listOf("cmd alarm set-timezone ${quote(zone)}")
    fun autoTime(on: Boolean): List<String> = listOf(
        "settings put global auto_time ${if (on) 1 else 0} && settings put global auto_time_zone ${if (on) 1 else 0}",
    )

    fun wifiConnect(ssid: String, password: String?, security: String): List<String>? {
        val sec = security.lowercase()
        if (sec !in setOf("open", "wpa2", "wpa3")) return null
        if (sec != "open" && password.isNullOrEmpty()) return null
        val pw = if (sec == "open") "" else " ${quote(password!!)}"
        return listOf("cmd wifi connect-network ${quote(ssid)} $sec$pw")
    }

    fun wifiSaved(): List<String> = listOf("cmd wifi list-networks")

    /**
     * A `dumpsys activity` line such as `topResumedActivity=ActivityRecord{... u0 com.foo/.Main t12}`
     * to `com.foo/.Main`.
     */
    fun parseForeground(line: String): String? =
        Regex("\\s([A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+)").find(line)?.groupValues?.get(1)

    /** The user's own Korean/English wording of what a privileged control command does, for the approver. */
    fun approvalReason(entryKey: String, korean: Boolean): String =
        if (korean) "기기 제어 요청(\"$entryKey\")을 수행하려고 시스템 명령을 실행합니다. 아래에 표시된 명령만 실행됩니다."
        else "Runs a system command to carry out the device-control request \"$entryKey\". Only the command shown is run."
}
