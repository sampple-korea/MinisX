package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The `{ok, tool, action, data|error, timestamp}` envelope the cross-platform
 * agent tools answer with (ported from iOS `noff_json_envelope` /
 * `noff_json_error`), plus the date vocabulary the tools share (ISO 8601,
 * `YYYY-MM-DD`, relative `-7d` / `-2h` / `-30m`, `--days N`, `--today`).
 *
 * The older `android-*` handlers predate this and answer with ad-hoc JSON; the
 * newer ones (health, bluetooth, nfc, vision, nlp, maps, media) use it so an
 * agent can rely on one shape. `--compact` / `-q` are applied by
 * [OffloadOutput.formatBody], which already understands `{ok, data, error}`.
 */
internal object OffloadEnvelope {

    fun ok(tool: String, action: String, data: Any?, args: OffloadArgs): NativeOffloadResult {
        val body = JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("action", action)
            .put("data", data ?: JSONObject.NULL)
            .put("timestamp", OffloadDates.format(Instant.now()))
            .toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    fun error(
        tool: String,
        action: String,
        code: String,
        message: String,
        args: OffloadArgs,
        exitCode: Int = 1,
        extra: JSONObject? = null,
    ): NativeOffloadResult {
        val err = JSONObject().put("code", code).put("message", message)
        extra?.keys()?.forEach { err.put(it, extra.get(it)) }
        val body = JSONObject()
            .put("ok", false)
            .put("tool", tool)
            .put("action", action)
            .put("error", err)
            .put("timestamp", OffloadDates.format(Instant.now()))
            .toString(2)
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body, args) + "\n")
    }
}

internal object OffloadDates {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** ISO 8601 with offset, e.g. `2026-11-05T09:30:00+09:00`. */
    fun format(i: Instant): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(i.atZone(zone).withNano(0))

    /**
     * `-7d` / `-2h` / `-30m`, full ISO 8601 (with or without offset or
     * fractional seconds), `YYYY-MM-DDTHH:mm[:ss]` or `YYYY-MM-DD` (local
     * time). Null when [s] is none of those.
     */
    fun parse(s: String?): Instant? {
        val str = s?.trim().orEmpty()
        if (str.isEmpty()) return null
        if (str.length >= 2 && str.startsWith("-")) {
            val num = str.substring(1, str.length - 1).toLongOrNull()
            if (num != null && num > 0) {
                val secs = when (str.last()) {
                    'd' -> num * 86_400
                    'h' -> num * 3_600
                    'm' -> num * 60
                    else -> 0L
                }
                if (secs > 0) return Instant.now().minusSeconds(secs)
            }
        }
        runCatching { return OffsetDateTime.parse(str).toInstant() }
        runCatching { return ZonedDateTime.parse(str).toInstant() }
        runCatching { return Instant.parse(str) }
        runCatching { return LocalDateTime.parse(str).atZone(zone).toInstant() }
        runCatching {
            if (str.length == 16) return LocalDateTime.parse("$str:00").atZone(zone).toInstant()
        }
        runCatching { return LocalDate.parse(str).atStartOfDay(zone).toInstant() }
        return null
    }

    /**
     * `--today`, `--days N` (from the start of the day N days ago), or
     * `--start` / `--end`. Defaults to the last 24 hours — iOS `resolve_date_range`.
     */
    fun range(args: OffloadArgs, defaultSpanSeconds: Long = 86_400): Pair<Instant, Instant> {
        if (args.hasFlag("today")) {
            val start = LocalDate.now(zone).atStartOfDay(zone)
            return start.toInstant() to start.plusDays(1).toInstant()
        }
        args.get("days")?.let { raw ->
            val days = (raw.toLongOrNull() ?: 7).let { if (it <= 0) 7 else it }
            val start = LocalDate.now(zone).minusDays(days).atStartOfDay(zone)
            return start.toInstant() to Instant.now()
        }
        val end = parse(args.get("end")) ?: Instant.now()
        val start = parse(args.get("start")) ?: end.minusSeconds(defaultSpanSeconds)
        return start to end
    }
}

internal fun jsonArrayOf(items: Iterable<Any?>): JSONArray = JSONArray().also { a -> items.forEach { a.put(it) } }
