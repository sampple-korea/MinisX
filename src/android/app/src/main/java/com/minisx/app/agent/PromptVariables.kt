package com.minisx.app.agent

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `{{name}}` placeholders the user can write in the personality (SOUL.md) and in AGENTS.md, filled in
 * when the system prompt is built: `{{date}}`, `{{time}}`, `{{datetime}}`, `{{weekday}}`,
 * `{{timezone}}`, `{{model}}`, `{{device}}`, `{{language}}`. A name that is not known stays as typed, so
 * a literal `{{like this}}` in a template survives.
 */
object PromptVariables {
    val NAMES = listOf("date", "time", "datetime", "weekday", "timezone", "model", "device", "language")

    private val pattern = Regex("""\{\{\s*([A-Za-z_]+)\s*\}\}""")

    fun values(now: ZonedDateTime, model: String?, device: String?, language: String?): Map<String, String> = buildMap {
        put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
        put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")))
        put("datetime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        put("weekday", now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH))
        put("timezone", now.zone.id)
        model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        device?.takeIf { it.isNotBlank() }?.let { put("device", it) }
        language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
    }

    fun apply(text: String, values: Map<String, String>): String =
        if ("{{" !in text) text else pattern.replace(text) { m -> values[m.groupValues[1].lowercase()] ?: m.value }
}
