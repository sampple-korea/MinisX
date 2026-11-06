package com.minisx.app.control

/**
 * Every action `android-control` can perform, in one list. Dispatch, `--help`, `android-control list`
 * and the tests all read from here, so what the model is told and what exists cannot drift apart.
 *
 * Pure (no Android types), so the JVM tests can walk it.
 */
object ControlCatalog {

    /** One action: `android-control <group> <name> <usage>`. */
    data class Entry(val group: String, val name: String, val usage: String, val summary: String) {
        val key: String get() = "$group $name"
    }

    val entries: List<Entry> = buildList {
        fun add(group: String, name: String, usage: String, summary: String) = add(Entry(group, name, usage, summary))

        // Flashlight: Camera2 setTorchMode needs no permission.
        add("flashlight", "on", "", "Turn the flashlight on")
        add("flashlight", "off", "", "Turn the flashlight off")
        add("flashlight", "toggle", "", "Flip the flashlight")
        add("flashlight", "status", "", "Is the flashlight on, and how many brightness levels it has")
        add("flashlight", "level", "<1-max>", "Turn it on at a brightness level (Android 13+, if the hardware allows)")
        add("flashlight", "blink", "[--count N] [--interval MS]", "Blink the flashlight N times, then leave it off")

        // Volume (percent of each stream's range; --raw N for the stream's own steps).
        add("volume", "get", "[stream]", "Volume of one stream, or of all of them")
        add("volume", "set", "<stream> <0-100> | <stream> --raw N", "Set a stream's volume in percent")
        add("volume", "up", "[stream] [--steps N]", "Raise a stream's volume (default stream: music)")
        add("volume", "down", "[stream] [--steps N]", "Lower a stream's volume (default stream: music)")
        add("volume", "mute", "[stream]", "Mute a stream (default: music)")
        add("volume", "unmute", "[stream]", "Unmute a stream")
        add("volume", "max", "[stream]", "Set a stream to its loudest")

        // Ringer, Do Not Disturb, vibration, tones.
        add("ringer", "get", "", "Ringer mode: normal, vibrate or silent")
        add("ringer", "set", "normal|vibrate|silent", "Set the ringer mode")
        add("dnd", "status", "", "Do Not Disturb state")
        add("dnd", "off", "", "Turn Do Not Disturb off")
        add("dnd", "priority", "", "Do Not Disturb: priority interruptions only")
        add("dnd", "alarms", "", "Do Not Disturb: alarms only")
        add("dnd", "silence", "", "Do Not Disturb: total silence")
        add("vibrate", "once", "[--ms N]", "Vibrate the phone once (default 400 ms)")
        add("vibrate", "pattern", "<ms,ms,...>", "Vibrate in an on/off pattern, in milliseconds")
        add("vibrate", "cancel", "", "Stop vibrating")
        add("sound", "beep", "[--ms N] [--stream S]", "Play a short beep")
        add("mic", "status", "", "Is the microphone muted")
        add("mic", "mute", "", "Mute the microphone")
        add("mic", "unmute", "", "Unmute the microphone")

        // Media keys.
        add("media", "play", "", "Send the play key to the active player")
        add("media", "pause", "", "Send the pause key")
        add("media", "toggle", "", "Send play/pause")
        add("media", "next", "", "Next track")
        add("media", "prev", "", "Previous track")
        add("media", "stop", "", "Stop playback")
        add("media", "forward", "", "Fast-forward")
        add("media", "rewind", "", "Rewind")

        // Screen and display.
        add("brightness", "get", "", "Screen brightness in percent, and whether it is automatic")
        add("brightness", "set", "<0-100>", "Set the screen brightness (turns automatic brightness off)")
        add("brightness", "auto", "on|off", "Automatic brightness")
        add("screen", "status", "", "Is the screen on, is the device locked")
        add("screen", "wake", "", "Turn the screen on")
        add("screen", "off", "", "Turn the screen off / lock")
        add("screen", "timeout", "[<seconds>]", "Read or set the screen-off timeout")
        add("screen", "rotation", "auto|portrait|landscape|reverse-portrait|reverse-landscape", "Auto-rotate, or lock the orientation")
        add("screen", "stay-awake", "on|off", "Keep the screen on while charging")
        add("screen", "screenshot", "[--path FILE]", "Take a screenshot (saved under Pictures/Screenshots by default)")
        add("display", "info", "", "Resolution, density, refresh rate")
        add("display", "font-scale", "[<0.85-2.0>]", "Read or set the system font scale")
        add("display", "density", "[<dpi>|reset]", "Read or set the display density (dpi)")
        add("display", "size", "[<WxH>|reset]", "Read or set the display resolution")
        add("display", "dark-mode", "on|off|auto", "System dark theme")
        add("display", "night-light", "on|off", "Night light / eye comfort shield")
        add("display", "invert", "on|off", "Colour inversion")
        add("display", "animations", "on|off|<scale>", "System animation speed (off = 0, on = 1)")

        // Connectivity.
        add("wifi", "status", "", "Wi-Fi on/off and the network joined")
        add("wifi", "on", "", "Turn Wi-Fi on")
        add("wifi", "off", "", "Turn Wi-Fi off")
        add("wifi", "scan", "", "Networks in range (needs location permission)")
        add("wifi", "connect", "<ssid> [password] [--security open|wpa2|wpa3]", "Join a Wi-Fi network (Android 11+, privileged)")
        add("wifi", "saved", "", "Saved networks (privileged)")
        add("data", "status", "", "Mobile data state")
        add("data", "on", "", "Turn mobile data on")
        add("data", "off", "", "Turn mobile data off")
        add("data", "saver", "on|off", "Data Saver")
        add("airplane", "status", "", "Airplane mode state")
        add("airplane", "on", "", "Turn airplane mode on")
        add("airplane", "off", "", "Turn airplane mode off")
        add("bluetooth", "status", "", "Bluetooth on/off and paired devices")
        add("bluetooth", "on", "", "Turn Bluetooth on")
        add("bluetooth", "off", "", "Turn Bluetooth off")
        add("bluetooth", "paired", "", "List paired Bluetooth devices")
        add("nfc", "status", "", "NFC state")
        add("nfc", "on", "", "Turn NFC on")
        add("nfc", "off", "", "Turn NFC off")
        add("location", "status", "", "Is location on, and the mode")
        add("location", "on", "", "Turn location on")
        add("location", "off", "", "Turn location off")
        add("network", "status", "", "Active network: type, validated, metered, addresses")

        // Power.
        add("battery", "saver", "on|off|status", "Battery saver")
        add("power", "reboot", "--yes", "Restart the phone")
        add("power", "shutdown", "--yes", "Power the phone off")
        add("power", "menu", "", "Open the power menu")

        // System UI and input.
        add("nav", "home", "", "Home")
        add("nav", "back", "", "Back")
        add("nav", "recents", "", "Recent apps")
        add("nav", "notifications", "", "Pull down the notification shade")
        add("nav", "quick-settings", "", "Pull down the quick settings panel")
        add("nav", "collapse", "", "Close the notification shade / quick settings")
        add("nav", "split-screen", "", "Toggle split screen")
        add("nav", "lock", "", "Lock the screen")
        add("nav", "screenshot", "", "System screenshot (as with the power+volume keys)")
        add("nav", "key", "<keycode name or number>", "Press a key (privileged)")
        add("nav", "tap", "<x> <y>", "Tap the screen (privileged)")
        add("nav", "swipe", "<x1> <y1> <x2> <y2> [--ms N]", "Swipe (privileged)")
        add("nav", "text", "<text>", "Type text into the focused field (privileged)")

        // Apps.
        add("app", "launch", "<package or name>", "Open an app")
        add("app", "list", "[query] [--max N]", "Installed apps you can launch")
        add("app", "info", "<package or name>", "Version, install time, enabled state")
        add("app", "settings", "<package or name>", "Open the app's system settings page")
        add("app", "current", "", "The app on screen right now (privileged or accessibility)")
        add("app", "stop", "<package or name>", "Force-stop an app (privileged)")
        add("app", "clear-data", "<package> --yes", "Erase an app's data (privileged)")
        add("app", "enable", "<package>", "Enable a disabled app (privileged)")
        add("app", "disable", "<package>", "Disable an app (privileged)")
        add("app", "uninstall", "<package>", "Ask Android to uninstall an app (the user confirms)")

        // Communication and sharing.
        add("phone", "dial", "<number>", "Open the dialer with a number (the user presses call)")
        add("phone", "call", "<number> --yes", "Place a call right away (asks Android for the Phone permission once)")
        add("phone", "find", "<name>", "Look a person up in the contacts: names and numbers")
        add("phone", "call-contact", "<name> [--pick N] --yes", "Call a contact by name (several matches: lists them, pick with --pick N)")
        add("phone", "answer", "", "Answer a ringing call")
        add("phone", "hangup", "", "End the current call")
        add("phone", "sms", "<number> [--text T]", "Open a text message ready to send (the user presses send)")
        add("phone", "sms-send", "<number> --text T --yes", "Send a text message right away")
        add("phone", "sms-contact", "<name> --text T [--pick N] --yes", "Send a text message to a contact by name")
        add("phone", "email", "<address> [--subject S] [--text T]", "Open an e-mail ready to send")
        add("share", "text", "<text>", "Open the share sheet with some text")
        add("camera", "photo", "", "Open the camera in photo mode")
        add("camera", "video", "", "Open the camera in video mode")
        add("wallpaper", "set", "<image path> [--target home|lock|both]", "Set the wallpaper from an image file")

        // Settings pages.
        add("settings", "open", "<page>", "Open a system settings page (`android-control settings pages` lists them)")
        add("settings", "pages", "", "The page names `settings open` knows")

        // Sensors and system information.
        add("sensor", "list", "", "Sensors this phone has")
        add("sensor", "read", "<light|accelerometer|gyroscope|proximity|pressure|magnetic|temperature|humidity|steps>", "One reading from a sensor")
        add("info", "memory", "", "RAM total, available, low-memory state")
        add("info", "thermal", "", "Thermal status and battery temperature")
        add("info", "uptime", "", "Time since boot")
        add("info", "locale", "", "Language, region, time zone, 24-hour setting")
        add("info", "sim", "", "Carrier and SIM state")
        add("info", "access", "", "Which control paths are open right now (direct, accessibility, Shizuku, root, special access)")
        add("timezone", "get", "", "Current time zone")
        add("timezone", "set", "<Area/City>", "Set the time zone (privileged)")
        add("time", "auto", "on|off", "Automatic date and time (privileged)")
    }

    val groups: List<String> = entries.map { it.group }.distinct()

    fun find(group: String?, name: String?): Entry? =
        entries.firstOrNull { it.group == group && it.name == name }

    fun inGroup(group: String): List<Entry> = entries.filter { it.group == group }

    /** The actions closest to what was typed, for an "unknown action" reply. */
    fun suggest(group: String?, name: String?): List<Entry> {
        if (group == null) return emptyList()
        val sameGroup = inGroup(group)
        if (sameGroup.isNotEmpty()) return sameGroup
        val prefix = groups.filter { it.startsWith(group.take(3)) || group.startsWith(it.take(3)) }
        return prefix.flatMap { inGroup(it) }.take(8)
    }

    fun line(e: Entry): String =
        "  android-control ${e.group} ${e.name}" + (if (e.usage.isNotEmpty()) " ${e.usage}" else "") + "  - ${e.summary}"

    /** The full list, grouped. */
    fun listing(): String = buildString {
        for (g in groups) {
            for (e in inGroup(g)) appendLine(line(e))
        }
    }

    fun groupListing(group: String): String = inGroup(group).joinToString("\n") { line(it) }

    /** One line per group, for the top-level help. */
    fun overview(): String = groups.joinToString("\n") { g ->
        "  $g: " + inGroup(g).joinToString(", ") { it.name }
    }
}
