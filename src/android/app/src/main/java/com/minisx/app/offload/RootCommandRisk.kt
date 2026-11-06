package com.minisx.app.offload

/**
 * What an approver should be warned about in a root command, worked out by the app itself from the
 * command text. The agent's own explanation can be wrong or misleading; this cannot be argued with.
 *
 * Deliberately simple pattern checks over each part of the command (split on `;`, `&&`, `||`, `|`
 * and newlines). Not a security boundary - the approval is - only a prompt for the person reading it.
 */
object RootCommandRisk {

    enum class Risk(val high: Boolean) {
        WIPES_DATA(true),
        DELETES_FILES(true),
        REBOOT_OR_SHUTDOWN(true),
        DOWNLOADS_AND_RUNS(true),
        REMOUNTS_SYSTEM(true),
        EDITS_SECURITY(true),
        RECURSIVE_PERMISSIONS(true),
        REMOVES_APPS(false),
        CHANGES_SETTINGS(false),
        STOPS_PROCESSES(false),
        WRITES_SYSTEM_FILES(false),
        CHANGES_NETWORK(false),
    }

    private val PARTS = Regex("\\|\\||&&|[;|&\\n]")

    private val RULES: List<Pair<Risk, Regex>> = listOf(
        Risk.WIPES_DATA to Regex("""(^|\s)(dd\s+[^|;&]*of=/dev/|mkfs(\.\w+)?\b|wipe\b|fastboot\s+(erase|format)|(rm|find)\s+[^|;&]*\s/(data|sdcard|storage)?\s*($|\*))"""),
        Risk.DELETES_FILES to Regex("""(^|\s)(rm|rmdir|unlink|shred|truncate)\s"""),
        Risk.REBOOT_OR_SHUTDOWN to Regex("""(^|\s)(reboot|poweroff|shutdown|halt)\b|svc\s+power\s+(reboot|shutdown)"""),
        Risk.DOWNLOADS_AND_RUNS to Regex("""(curl|wget)\s[^|;&]*\|\s*(sh|bash|su|ash)\b|(sh|bash)\s+<\(\s*(curl|wget)"""),
        Risk.REMOUNTS_SYSTEM to Regex("""mount\s[^|;&]*(remount|rw)\b|(^|\s)(umount|mount)\s+[^|;&]*/(system|vendor|product|odm)\b"""),
        Risk.EDITS_SECURITY to Regex("""(^|\s)(setenforce|magiskpolicy|supolicy|resetprop|chcon|restorecon)\b|selinux"""),
        Risk.RECURSIVE_PERMISSIONS to Regex("""(chmod|chown|chgrp)\s+(-\w*R\w*|--recursive)\s"""),
        Risk.REMOVES_APPS to Regex("""(^|\s)pm\s+(uninstall|disable(-user)?|clear|suspend|hide)\b|cmd\s+package\s+(uninstall|disable|clear)\b"""),
        Risk.CHANGES_SETTINGS to Regex("""(^|\s)(settings\s+(put|delete)|cmd\s+settings\s+(put|delete)|setprop|svc\s+(wifi|data|bluetooth|nfc)\s+disable|content\s+(insert|update|delete))\b"""),
        Risk.STOPS_PROCESSES to Regex("""(^|\s)(kill|killall|pkill)\b|am\s+(force-stop|kill|kill-all)\b"""),
        Risk.WRITES_SYSTEM_FILES to Regex("""(>|>>|\btee\b)\s*/(system|vendor|etc|sys|proc|data/(system|misc|adb))/|\b(cp|mv|ln|sed\s+-i)\s[^|;&]*\s/(system|vendor|etc|data/(system|misc|adb))/"""),
        Risk.CHANGES_NETWORK to Regex("""(^|\s)(iptables|ip6tables|nft|ip\s+(route|rule|link\s+set)|ifconfig|route\s+(add|del))\b"""),
    )

    /** The risks found in [command], most serious first, each once. */
    fun assess(command: String): List<Risk> {
        val found = LinkedHashSet<Risk>()
        // The whole line first (a pipe into `sh` spans two parts), then each part on its own.
        val pieces = listOf(command) + command.split(PARTS).map { it.trim() }.filter { it.isNotEmpty() }
        for (piece in pieces) for ((risk, rx) in RULES) if (rx.containsMatchIn(piece)) found += risk
        return found.sortedByDescending { it.high }
    }
}
