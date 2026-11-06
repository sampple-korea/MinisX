package com.minisx.app.assistant

import org.json.JSONObject

/**
 * Plain phone commands that need no model: "손전등 켜줘", "turn wifi off", "볼륨 50", "밝기 30%".
 * [parse] only answers when the whole message is one clear command about one thing; anything
 * longer, a question, a timed request ("10분 뒤에 ...") or a message touching two things returns null
 * and goes to the model as usual.
 *
 * Pure (no Android types) so the patterns are tested on the JVM.
 */
object FastCommands {

    /** An `android-control` call and what to say when it worked. */
    data class Command(val argv: List<String>, val ko: String, val en: String) {
        fun message(korean: Boolean) = if (korean) ko else en
    }

    private const val MAX_CHARS = 60

    private val QUESTION = Regex("\\?|어떻게|왜 |어디|뭐|뭘|언제|방법|할까|될까|있어|없어|되나|how |why |what |where |can you|could you")
    private val TIMED = Regex("\\d+\\s*(초|분|시간|시|sec|min|hour)|뒤에|후에|이따가|나중에|내일|오늘|매일|마다|after |later|tomorrow|every |그리고|다음에| and then")

    private val ON = Regex("켜|키워|킬래|켤|\\bon\\b|enable|활성화|turn on|switch on")
    private val OFF = Regex("꺼|끄|끌|끔|\\boff\\b|disable|비활성화|turn off|switch off|해제")

    private fun polite(text: String): String {
        var t = text.trim().lowercase()
        t = t.replace(Regex("[.!~…]+$"), "").trim()
        t = t.replace(Regex("\\s+"), " ")
        // 켜줘 → 켜, 꺼주세요 → 꺼, please
        var prev: String
        do {
            prev = t
            t = t.replace(Regex("\\s*(좀|조금|제발|please|pls|plz|빨리|지금|now)$"), "")
                .replace(Regex("(해줘|해 줘|해주세요|해 주세요|해라|해요|해줄래|해 줄래|부탁해|부탁합니다|주세요|줄래|줘|라|요)$"), "")
                .trim()
        } while (t != prev)
        return t
    }

    private fun onOff(t: String): Boolean? {
        val on = ON.containsMatchIn(t)
        val off = OFF.containsMatchIn(t)
        return when {
            on && !off -> true
            off && !on -> false
            else -> null
        }
    }

    private fun number(t: String): Int? = Regex("(\\d{1,3})\\s*(%|퍼센트|프로|percent)?").find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    /** 을/를 for a Korean noun by its last syllable. */
    internal fun obj(name: String): String {
        val last = name.trim().lastOrNull() ?: return "을(를)"
        if (last in '가'..'힣') return if ((last.code - 0xAC00) % 28 != 0) "을" else "를"
        // Latin names (Wi-Fi, Chrome, Gmail): by the sound of the last letter, good enough for a reply.
        if (last.lowercaseChar() in 'a'..'z') return if (last.lowercaseChar() in "aeiouycfhsxzjvw") "를" else "을"
        if (last in '0'..'9') return "을"
        return "을(를)"
    }

    private class Rule(val target: Regex, val build: (String) -> Command?)

    private fun toggle(group: String, nameKo: String, nameEn: String): (String) -> Command? = { t ->
        onOff(t)?.let { on ->
            Command(
                listOf(group, if (on) "on" else "off"),
                "$nameKo${obj(nameKo)} ${if (on) "켰어요" else "껐어요"}.",
                "$nameEn turned ${if (on) "on" else "off"}.",
            )
        }
    }

    private fun streamOf(t: String): String = when {
        Regex("벨소리|\\bring").containsMatchIn(t) -> "ring"
        Regex("알람|alarm").containsMatchIn(t) -> "alarm"
        Regex("알림음|알림 ?소리|notification").containsMatchIn(t) -> "notification"
        Regex("통화|call").containsMatchIn(t) -> "call"
        else -> "music"
    }

    private fun streamKo(s: String) = when (s) { "ring" -> "벨소리"; "alarm" -> "알람"; "notification" -> "알림음"; "call" -> "통화"; else -> "미디어" }

    private val rules: List<Rule> = listOf(
        Rule(Regex("손전등|플래시|후레쉬|후레시|랜턴|flashlight|torch|\\bflash\\b")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("flashlight", if (on) "on" else "off"), "손전등을 ${if (on) "켰어요" else "껐어요"}.", "Flashlight ${if (on) "on" else "off"}.")
            }
        },
        Rule(Regex("와이파이|와이 파이|wi-?fi|무선 ?랜"), toggle("wifi", "Wi-Fi", "Wi-Fi")),
        Rule(Regex("블루투스|bluetooth|\\bbt\\b"), toggle("bluetooth", "블루투스", "Bluetooth")),
        Rule(Regex("모바일 ?데이터|셀룰러|mobile data|cellular|^데이터"), toggle("data", "모바일 데이터", "Mobile data")),
        Rule(Regex("비행기 ?모드|에어플레인|airplane|flight mode"), toggle("airplane", "비행기 모드", "Airplane mode")),
        Rule(Regex("\\bnfc\\b|엔에프씨"), toggle("nfc", "NFC", "NFC")),
        Rule(Regex("\\bgps\\b|지피에스|위치 ?(서비스|정보)?|\\blocation\\b"), toggle("location", "위치", "Location")),
        Rule(Regex("방해 ?금지|do not disturb|\\bdnd\\b|집중 ?모드")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("dnd", if (on) "priority" else "off"), "방해 금지 모드를 ${if (on) "켰어요" else "껐어요"}.", "Do Not Disturb ${if (on) "on" else "off"}.")
            }
        },
        Rule(Regex("다크 ?모드|dark mode|어두운 ?모드")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("display", "dark-mode", if (on) "on" else "off"), "다크 모드를 ${if (on) "켰어요" else "껐어요"}.", "Dark mode ${if (on) "on" else "off"}.")
            }
        },
        Rule(Regex("절전 ?모드|배터리 ?세이버|battery saver|power saving|저전력")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("battery", "saver", if (on) "on" else "off"), "절전 모드를 ${if (on) "켰어요" else "껐어요"}.", "Battery saver ${if (on) "on" else "off"}.")
            }
        },
        Rule(Regex("야간 ?조명|블루라이트|눈 ?보호|night light|eye comfort")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("display", "night-light", if (on) "on" else "off"), "야간 조명을 ${if (on) "켰어요" else "껐어요"}.", "Night light ${if (on) "on" else "off"}.")
            }
        },
        Rule(Regex("자동 ?회전|화면 ?회전|auto.?rotate|rotation")) { t ->
            onOff(t)?.let { on ->
                Command(listOf("screen", "rotation", if (on) "auto" else "portrait"), "화면 회전을 ${if (on) "켰어요" else "세로로 고정했어요"}.", "Auto-rotate ${if (on) "on" else "locked to portrait"}.")
            }
        },
        // Ringer modes ("무음 모드") before the volume rule.
        Rule(Regex("무음|silent mode")) { _ ->
            Command(listOf("ringer", "set", "silent"), "무음 모드로 바꿨어요.", "Silent mode on.")
        },
        Rule(Regex("진동 ?(모드|으로)|vibrate mode")) { _ ->
            Command(listOf("ringer", "set", "vibrate"), "진동 모드로 바꿨어요.", "Vibrate mode on.")
        },
        Rule(Regex("(벨소리|소리) ?모드|normal mode|sound mode")) { _ ->
            Command(listOf("ringer", "set", "normal"), "소리 모드로 바꿨어요.", "Sound mode on.")
        },
        Rule(Regex("음소거|\\bmute\\b|unmute")) { t ->
            val un = Regex("해제|풀어|unmute|취소").containsMatchIn(t)
            Command(listOf("volume", if (un) "unmute" else "mute"), if (un) "음소거를 풀었어요." else "음소거했어요.", if (un) "Unmuted." else "Muted.")
        },
        Rule(Regex("볼륨|음량|소리|volume")) { t ->
            val s = streamOf(t)
            val n = number(t)
            when {
                n != null -> Command(listOf("volume", "set", s, n.toString()), "${streamKo(s)} 볼륨을 $n%로 맞췄어요.", "${s.replaceFirstChar { it.uppercase() }} volume set to $n%.")
                Regex("최대|가장 크게|풀 ?볼륨|\\bmax").containsMatchIn(t) -> Command(listOf("volume", "max", s), "${streamKo(s)} 볼륨을 최대로 올렸어요.", "${s.replaceFirstChar { it.uppercase() }} volume at maximum.")
                Regex("올려|높여|키워|크게|\\bup\\b|louder|increase").containsMatchIn(t) -> Command(listOf("volume", "up", s), "${streamKo(s)} 볼륨을 올렸어요.", "Volume up.")
                Regex("내려|낮춰|줄여|작게|\\bdown\\b|quieter|lower|decrease").containsMatchIn(t) -> Command(listOf("volume", "down", s), "${streamKo(s)} 볼륨을 내렸어요.", "Volume down.")
                else -> null
            }
        },
        Rule(Regex("밝기|brightness")) { t ->
            val n = number(t)
            when {
                Regex("자동|auto").containsMatchIn(t) -> onOff(t)?.let { on ->
                    Command(listOf("brightness", "auto", if (on) "on" else "off"), "자동 밝기를 ${if (on) "켰어요" else "껐어요"}.", "Auto brightness ${if (on) "on" else "off"}.")
                }
                n != null -> Command(listOf("brightness", "set", n.toString()), "밝기를 $n%로 맞췄어요.", "Brightness set to $n%.")
                Regex("최대|가장 밝게|\\bmax").containsMatchIn(t) -> Command(listOf("brightness", "set", "100"), "밝기를 최대로 올렸어요.", "Brightness at maximum.")
                Regex("최소|가장 어둡게|\\bmin").containsMatchIn(t) -> Command(listOf("brightness", "set", "5"), "밝기를 최소로 낮췄어요.", "Brightness at minimum.")
                else -> null
            }
        },
        Rule(Regex("^(홈|홈 ?화면|홈 ?으로|홈 ?화면 ?으로)( ?(가|이동|가기))?$|^go home$|^home$")) { _ ->
            Command(listOf("nav", "home"), "홈 화면으로 갔어요.", "Went home.")
        },
        Rule(Regex("^(뒤로|뒤로 ?가|뒤로 ?가기|go back|back)$")) { _ ->
            Command(listOf("nav", "back"), "뒤로 갔어요.", "Went back.")
        },
        Rule(Regex("최근 ?앱|멀티태스킹|recent apps|recents")) { _ ->
            Command(listOf("nav", "recents"), "최근 앱을 열었어요.", "Opened recent apps.")
        },
        Rule(Regex("알림 ?(창|패널|바)|notification (shade|panel)")) { t ->
            if (Regex("열|내려|보여|open|show|pull").containsMatchIn(t)) Command(listOf("nav", "notifications"), "알림창을 열었어요.", "Opened the notification shade.") else null
        },
        Rule(Regex("빠른 ?설정|퀵 ?패널|퀵 ?설정|quick settings")) { _ ->
            Command(listOf("nav", "quick-settings"), "빠른 설정을 열었어요.", "Opened quick settings.")
        },
        Rule(Regex("스크린샷|화면 ?캡처|screenshot")) { t ->
            if (Regex("찍|캡처|해|take|capture").containsMatchIn(t)) Command(listOf("nav", "screenshot"), "스크린샷을 찍었어요.", "Took a screenshot.") else null
        },
        Rule(Regex("화면 ?(꺼|끄|잠|잠가)|잠금 ?화면|lock (the )?screen|screen off")) { _ ->
            Command(listOf("screen", "off"), "화면을 껐어요.", "Screen off.")
        },
        Rule(Regex("화면 ?(켜|깨워)|screen on|wake up the screen")) { _ ->
            Command(listOf("screen", "wake"), "화면을 켰어요.", "Screen on.")
        },
        Rule(Regex("사진 ?(찍|한 ?장)|take a photo|take a picture")) { _ ->
            Command(listOf("camera", "photo"), "카메라를 열었어요.", "Opened the camera.")
        },
        Rule(Regex("카메라|camera")) { t ->
            if (Regex("열|켜|실행|open|start|launch").containsMatchIn(t)) Command(listOf("camera", "photo"), "카메라를 열었어요.", "Opened the camera.") else null
        },
    )

    private val APP_STOP = setOf("파일", "이거", "그거", "저거", "링크", "창", "문서", "사진", "이미지", "이", "그", "저", "it", "this", "that", "file", "link")

    private val APP_LAUNCH = Regex("^(.{1,24}?)\\s*(앱|어플|애플리케이션|app)?\\s*(열어|열기|실행|켜|틀어|시작|open|launch|start)$")
    private val APP_LAUNCH_EN = Regex("^(open|launch|start)\\s+(.{1,24}?)(\\s+app)?$")

    fun parse(raw: String): Command? {
        if (raw.isBlank() || raw.length > MAX_CHARS) return null
        val lowered = raw.lowercase()
        if (QUESTION.containsMatchIn(lowered) || TIMED.containsMatchIn(lowered)) return null
        val t = polite(raw)
        if (t.isEmpty()) return null

        val hits = rules.filter { it.target.containsMatchIn(t) }
        // A message about two things ("와이파이랑 블루투스 꺼") is left to the model.
        if (hits.size > 1) {
            // The ringer rules and the volume rule share words ("무음 모드", "소리 모드"): the first is the intent.
            val ringer = hits.firstOrNull { it.target.pattern.contains("무음") || it.target.pattern.contains("진동") || it.target.pattern.contains("(벨소리|소리) ?모드") }
            if (ringer != null && hits.all { it === ringer || it.target.pattern.contains("볼륨") || it.target.pattern.contains("음소거") }) return ringer.build(t)
            return null
        }
        hits.singleOrNull()?.let { return it.build(t) }

        // No known target: a bare "<app> 열어" opens an app. An unknown name falls back to the model.
        val name = (APP_LAUNCH_EN.find(t)?.groupValues?.get(2) ?: APP_LAUNCH.find(t)?.groupValues?.get(1) ?: return null)
            .trim().removeSuffix("좀").trim()
        if (name.isEmpty() || name in APP_STOP || name.contains(' ') && name.length > 18) return null
        return Command(listOf("app", "launch", name), "$name${obj(name)} 열었어요.", "Opened $name.")
    }

    // ── what the control answered ──────────────────────────────────────────

    sealed class Outcome {
        data class Done(val text: String) : Outcome()
        data class NeedsUser(val text: String) : Outcome()
        /** Not handled here: send the original message to the model. */
        object Fallback : Outcome()
    }

    /** Reads an `android-control` reply (exit code and JSON body) into what to tell the user. */
    fun interpret(exitCode: Int, output: String, cmd: Command, korean: Boolean): Outcome {
        val json = runCatching { JSONObject(output.trim()) }.getOrNull() ?: return Outcome.Fallback
        if (exitCode == 0 && json.optBoolean("ok", false)) return Outcome.Done(cmd.message(korean))
        val error = json.optJSONObject("error")
        if (error?.optString("code") == "needs_user") {
            val text = if (korean) "이 스위치는 앱이 직접 바꿀 수 없어서 해당 설정 화면을 열어 뒀어요. 직접 바꿔 주세요. (Shizuku나 root를 연결하면 앞으로는 바로 바꿀 수 있어요.)"
            else "Only you can flip this switch on this phone, so I opened the matching settings screen. (With Shizuku or root connected I can do it directly.)"
            return Outcome.NeedsUser(text)
        }
        return Outcome.Fallback
    }
}
