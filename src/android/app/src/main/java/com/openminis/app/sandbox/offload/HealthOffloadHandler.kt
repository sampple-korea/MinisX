package com.openminis.app.sandbox.offload

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Volume
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * android-health — read and log health data through Health Connect.
 *
 * Android counterpart of iOS `apple-healthkit` (HealthKitOffload.m). The
 * subcommands, the date vocabulary (`--days N`, `--today`, `--start`, `--end`,
 * relative `-7d`) and the `{ok, tool, action, data}` envelope are the same, so
 * a skill written against one runs on the other. What differs is the data model:
 * Health Connect has no ECG / audiogram / vision-prescription / mental-health
 * assessment records, and reports HRV as RMSSD rather than SDNN. Those iOS
 * subcommands are not ported.
 *
 * Permissions are asked per data type, the first time a command needs one, via
 * the same system dialog the other CLIs use. Health Connect is built into
 * Android 14+; on older releases the Health Connect app must be installed, and
 * its permission screen is the only place to grant (the CLI says so).
 */
class HealthOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), BOOLEAN_FLAGS)
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)

        OffloadGate.enforce("health", "Health", args, request)?.let { return it }

        return try {
            runBlocking { dispatch(sub, args) }
        } catch (e: SecurityException) {
            OffloadEnvelope.error(
                TOOL, sub, "permission_denied",
                "Health Connect refused the read: ${e.message}. If the app is in the background, open Health Connect → " +
                    "App permissions → Minis and allow \"Access data in the background\".",
                args,
            )
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── Dispatch ────────────────────────────────────────────────────────────

    private suspend fun dispatch(sub: String, args: OffloadArgs): NativeOffloadResult {
        when (sub) {
            "status" -> return status(args)
            "types" -> return types(args)
        }
        val client = clientOrError(sub, args) ?: return lastClientError!!
        return when (sub) {
            "steps" -> steps(client, args)
            "heart-rate" -> heartRate(client, args)
            "resting-heart-rate" -> pointReader(client, args, sub, RestingHeartRateRecord::class) { r ->
                JSONObject().put("time", fmt(r.time)).put("bpm", r.beatsPerMinute)
            }
            "vo2-max" -> pointReader(client, args, sub, Vo2MaxRecord::class) { r ->
                JSONObject().put("time", fmt(r.time)).put("vo2_max", r.vo2MillilitersPerMinuteKilogram)
                    .put("unit", "mL/(kg·min)")
            }
            "weight" -> pointReader(client, args, sub, WeightRecord::class) { r ->
                JSONObject().put("time", fmt(r.time)).put("kg", round(r.weight.inKilograms))
            }
            "blood-oxygen" -> pointReader(client, args, sub, OxygenSaturationRecord::class) { r ->
                JSONObject().put("time", fmt(r.time)).put("percent", round(r.percentage.value))
            }
            "blood-glucose" -> pointReader(client, args, sub, BloodGlucoseRecord::class) { r ->
                JSONObject().put("time", fmt(r.time))
                    .put("mg_dl", round(r.level.inMilligramsPerDeciliter))
                    .put("mmol_l", round(r.level.inMillimolesPerLiter))
                    .put("specimen_source", r.specimenSource)
            }
            "hrv" -> pointReader(client, args, sub, HeartRateVariabilityRmssdRecord::class) { r ->
                JSONObject().put("time", fmt(r.time)).put("rmssd_ms", round(r.heartRateVariabilityMillis))
                    .put("note", "Health Connect reports RMSSD, not SDNN")
            }
            "sleep" -> sleep(client, args)
            "workouts" -> workouts(client, args)
            "nutrition" -> nutrition(client, args)
            "summary" -> summary(client, args)
            "batch" -> batch(client, args)
            "log" -> log(client, args)
            "log-blood-pressure" -> logBloodPressure(client, args)
            "delete" -> delete(client, args)
            else -> OffloadEnvelope.error(
                TOOL, sub, "unknown_command",
                "Unknown command '$sub'. Valid commands: status, types, steps, heart-rate, resting-heart-rate, vo2-max, " +
                    "sleep, workouts, weight, blood-oxygen, blood-glucose, hrv, nutrition, summary, batch, log, " +
                    "log-blood-pressure, delete. Use --help for details.",
                args, 2,
            )
        }
    }

    // ── Client / permissions ────────────────────────────────────────────────

    private var lastClientError: NativeOffloadResult? = null

    private fun clientOrError(action: String, args: OffloadArgs): HealthConnectClient? {
        lastClientError = null
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                lastClientError = OffloadEnvelope.error(
                    TOOL, action, "provider_update_required",
                    "The Health Connect app needs an update (Play Store → Health Connect).", args,
                ); null
            }
            else -> {
                lastClientError = OffloadEnvelope.error(
                    TOOL, action, "unavailable",
                    "Health Connect is not available on this device" +
                        if (Build.VERSION.SDK_INT < 34) " (install the Health Connect app from Google Play)." else ".",
                    args,
                ); null
            }
        }
    }

    /** Null when every read permission for [types] is granted (asking for any that are not). */
    private suspend fun ensureRead(
        client: HealthConnectClient,
        action: String,
        args: OffloadArgs,
        vararg types: KClass<out Record>,
    ): NativeOffloadResult? = ensure(client, action, args, types.map { HealthPermission.getReadPermission(it) })

    private suspend fun ensureWrite(
        client: HealthConnectClient,
        action: String,
        args: OffloadArgs,
        vararg types: KClass<out Record>,
    ): NativeOffloadResult? = ensure(client, action, args, types.map { HealthPermission.getWritePermission(it) })

    private suspend fun ensure(
        client: HealthConnectClient,
        action: String,
        args: OffloadArgs,
        needed: List<String>,
    ): NativeOffloadResult? {
        val controller = client.permissionController
        var missing = needed.toSet() - controller.getGrantedPermissions()
        if (missing.isEmpty()) return null

        if (Build.VERSION.SDK_INT >= 34) {
            // Health permissions are ordinary runtime permissions from Android 14,
            // so the shared system-dialog flow drives them.
            val r = OffloadPermissionManager.requestAndroidPermission(missing.toList())
            if (r == OffloadPermissionManager.AndroidPermissionResult.TIMEOUT) {
                return OffloadEnvelope.error(
                    TOOL, action, "timeout",
                    "The Health Connect permission dialog was not answered in time. Open the chat screen and retry.", args,
                )
            }
            missing = needed.toSet() - controller.getGrantedPermissions()
        }
        if (missing.isEmpty()) return null
        return OffloadEnvelope.error(
            TOOL, action, "permission_denied",
            "Health Connect access is not granted for: ${missing.joinToString { it.substringAfterLast('.') }}. " +
                "Open Health Connect → App permissions → Minis and allow it, then retry.",
            args, 126, JSONObject().put("missing", JSONArray(missing.map { it.substringAfterLast('.') })),
        )
    }

    // ── status / types ──────────────────────────────────────────────────────

    private suspend fun status(args: OffloadArgs): NativeOffloadResult {
        val sdk = HealthConnectClient.getSdkStatus(context)
        val data = JSONObject()
            .put("available", sdk == HealthConnectClient.SDK_AVAILABLE)
            .put("sdk_status", when (sdk) {
                HealthConnectClient.SDK_AVAILABLE -> "available"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "provider_update_required"
                else -> "unavailable"
            })
            .put("builtin", Build.VERSION.SDK_INT >= 34)
        if (sdk == HealthConnectClient.SDK_AVAILABLE) {
            val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            data.put("granted", JSONArray(granted.map { it.substringAfterLast('.') }.sorted()))
        }
        return OffloadEnvelope.ok(TOOL, "status", data, args)
    }

    private fun types(args: OffloadArgs): NativeOffloadResult {
        val arr = JSONArray()
        for (t in TYPE_TABLE) {
            arr.put(JSONObject().put("name", t.name).put("desc", t.desc).put("unit", t.unit)
                .put("read", t.read).put("log", t.log))
        }
        val data = JSONObject().put("quantity_types", arr)
            .put("note", "Health Connect has no ECG, audiogram, vision prescription or assessment records; " +
                "HRV is RMSSD.")
        return OffloadEnvelope.ok(TOOL, "types", data, args)
    }

    // ── Readers ─────────────────────────────────────────────────────────────

    private suspend fun steps(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(client, "steps", args, StepsRecord::class)?.let { return it }
        val (start, end) = OffloadDates.range(args)
        val zone = ZoneId.systemDefault()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    LocalDateTime.ofInstant(start, zone).toLocalDate().atStartOfDay(),
                    LocalDateTime.ofInstant(end, zone),
                ),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        var total = 0L
        val days = JSONArray()
        for (g in groups) {
            val n = g.result[StepsRecord.COUNT_TOTAL] ?: 0L
            total += n
            days.put(JSONObject().put("date", g.startTime.atZone(zone).toOffsetDateTime().toString()).put("steps", n))
        }
        val data = JSONObject().put("days", days).put("total", total).put("range", rangeJson(start, end))
        return OffloadEnvelope.ok(TOOL, "steps", data, args)
    }

    private suspend fun heartRate(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(client, "heart-rate", args, HeartRateRecord::class)?.let { return it }
        val (start, end) = OffloadDates.range(args)
        val limit = args.getInt("limit") ?: DEFAULT_LIMIT
        val samples = readAll(client, HeartRateRecord::class, start, end)
            .flatMap { rec -> rec.samples.map { it.time to it.beatsPerMinute } }
            .sortedByDescending { it.first }
        val arr = JSONArray()
        samples.take(limit).forEach { (t, bpm) -> arr.put(JSONObject().put("time", fmt(t)).put("bpm", bpm)) }
        val data = JSONObject().put("samples", arr).put("count", arr.length()).put("total_in_range", samples.size)
            .put("range", rangeJson(start, end))
        if (samples.isNotEmpty()) {
            data.put("min", samples.minOf { it.second }).put("max", samples.maxOf { it.second })
                .put("avg", round(samples.map { it.second }.average()))
        }
        return OffloadEnvelope.ok(TOOL, "heart-rate", data, args)
    }

    /** Newest-first list of instantaneous records (resting HR, weight, SpO2, …). */
    private suspend fun <T : Record> pointReader(
        client: HealthConnectClient,
        args: OffloadArgs,
        action: String,
        type: KClass<T>,
        row: (T) -> JSONObject,
    ): NativeOffloadResult {
        ensureRead(client, action, args, type)?.let { return it }
        val (start, end) = OffloadDates.range(args)
        val limit = args.getInt("limit") ?: DEFAULT_LIMIT
        val records = readAll(client, type, start, end)
        // Records come back in start order; the CLI contract is newest first.
        val rows = JSONArray().also { a -> records.asReversed().take(limit).forEach { a.put(row(it)) } }
        val data = JSONObject().put("samples", rows).put("count", rows.length())
            .put("total_in_range", records.size).put("range", rangeJson(start, end))
        return OffloadEnvelope.ok(TOOL, action, data, args)
    }

    private suspend fun sleep(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(client, "sleep", args, SleepSessionRecord::class)?.let { return it }
        val (start, end) = OffloadDates.range(args, defaultSpanSeconds = 7 * 86_400L)
        val sessions = readAll(client, SleepSessionRecord::class, start, end)
        val arr = JSONArray()
        var totalAsleepMin = 0L
        for (s in sessions.asReversed()) {
            val byStage = linkedMapOf<String, Long>()
            for (st in s.stages) {
                val name = STAGE_NAMES[st.stage] ?: "unknown"
                byStage[name] = (byStage[name] ?: 0L) + Duration.between(st.startTime, st.endTime).toMinutes()
            }
            val total = Duration.between(s.startTime, s.endTime).toMinutes()
            val awake = (byStage["awake"] ?: 0L) + (byStage["out_of_bed"] ?: 0L) + (byStage["awake_in_bed"] ?: 0L)
            // A session without stages is plain "asleep" time.
            val asleep = if (s.stages.isEmpty()) total else (total - awake).coerceAtLeast(0)
            totalAsleepMin += asleep
            val stages = JSONObject().also { o -> byStage.forEach { (k, v) -> o.put(k, v) } }
            arr.put(JSONObject().put("start", fmt(s.startTime)).put("end", fmt(s.endTime))
                .put("duration_minutes", total).put("asleep_minutes", asleep)
                .put("stages_minutes", stages).apply { s.title?.let { put("title", it) } })
        }
        val data = JSONObject().put("sessions", arr).put("count", arr.length())
            .put("total_asleep_minutes", totalAsleepMin).put("range", rangeJson(start, end))
        return OffloadEnvelope.ok(TOOL, "sleep", data, args)
    }

    private suspend fun workouts(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(client, "workouts", args, ExerciseSessionRecord::class)?.let { return it }
        val (start, end) = OffloadDates.range(args, defaultSpanSeconds = 30 * 86_400L)
        val filter = args.get("type")?.lowercase()?.replace(' ', '_')
        val limit = args.getInt("limit") ?: DEFAULT_LIMIT
        val arr = JSONArray()
        for (w in readAll(client, ExerciseSessionRecord::class, start, end).asReversed()) {
            val typeName = EXERCISE_NAMES[w.exerciseType] ?: "other"
            if (filter != null && !typeName.contains(filter)) continue
            if (arr.length() >= limit) break
            arr.put(JSONObject().put("type", typeName).put("start", fmt(w.startTime)).put("end", fmt(w.endTime))
                .put("duration_minutes", Duration.between(w.startTime, w.endTime).toMinutes())
                .apply { w.title?.let { put("title", it) }; w.notes?.let { put("notes", it) } })
        }
        val data = JSONObject().put("workouts", arr).put("count", arr.length()).put("range", rangeJson(start, end))
        return OffloadEnvelope.ok(TOOL, "workouts", data, args)
    }

    private suspend fun nutrition(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(client, "nutrition", args, NutritionRecord::class)?.let { return it }
        val (start, end) = OffloadDates.range(args)
        val want = args.get("type")?.lowercase()
        val limit = args.getInt("limit") ?: DEFAULT_LIMIT
        val records = readAll(client, NutritionRecord::class, start, end)
        fun JSONObject.nutrients(r: NutritionRecord): JSONObject {
            r.energy?.let { put("calories", round(it.inKilocalories)) }
            r.protein?.let { put("protein_g", round(it.inGrams)) }
            r.totalCarbohydrate?.let { put("carbs_g", round(it.inGrams)) }
            r.totalFat?.let { put("fat_g", round(it.inGrams)) }
            r.dietaryFiber?.let { put("fiber_g", round(it.inGrams)) }
            r.sugar?.let { put("sugar_g", round(it.inGrams)) }
            r.sodium?.let { put("sodium_mg", round(it.inGrams * 1000)) }
            return this
        }
        val totals = JSONObject()
            .put("calories", round(records.sumOf { it.energy?.inKilocalories ?: 0.0 }))
            .put("protein_g", round(records.sumOf { it.protein?.inGrams ?: 0.0 }))
            .put("carbs_g", round(records.sumOf { it.totalCarbohydrate?.inGrams ?: 0.0 }))
            .put("fat_g", round(records.sumOf { it.totalFat?.inGrams ?: 0.0 }))
            .put("fiber_g", round(records.sumOf { it.dietaryFiber?.inGrams ?: 0.0 }))
            .put("sugar_g", round(records.sumOf { it.sugar?.inGrams ?: 0.0 }))
        val items = JSONArray()
        for (r in records.asReversed().take(limit)) {
            val o = JSONObject().put("start", fmt(r.startTime)).put("end", fmt(r.endTime))
                .apply { r.name?.let { put("name", it) } }.nutrients(r)
            items.put(o)
        }
        val data = JSONObject().put("totals", totals).put("entries", items).put("count", items.length())
            .put("range", rangeJson(start, end))
        if (want != null) data.put("requested_type", want)
        return OffloadEnvelope.ok(TOOL, "nutrition", data, args)
    }

    private suspend fun summary(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        ensureRead(
            client, "summary", args,
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class, ExerciseSessionRecord::class,
        )?.let { return it }
        val (start, end) = OffloadDates.range(args, defaultSpanSeconds = 86_400L)
        val zone = ZoneId.systemDefault()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(
                    LocalDateTime.ofInstant(start, zone).toLocalDate().atStartOfDay(),
                    LocalDateTime.ofInstant(end, zone),
                ),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        val days = JSONArray()
        for (g in groups) {
            val r = g.result
            days.put(JSONObject()
                .put("date", g.startTime.toLocalDate().toString())
                .put("steps", r[StepsRecord.COUNT_TOTAL] ?: 0L)
                .put("distance_m", round(r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0))
                .put("active_kcal", round(r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0))
                .put("total_kcal", round(r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0))
                .put("exercise_minutes", r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: 0L))
        }
        val data = JSONObject().put("days", days).put("range", rangeJson(start, end))
        return OffloadEnvelope.ok(TOOL, "summary", data, args)
    }

    /** `batch --types steps,heart-rate,sleep …` — one call, one envelope. */
    private suspend fun batch(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        val names = args.get("types")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (names.isEmpty()) {
            return OffloadEnvelope.error(TOOL, "batch", "invalid_arguments", "Pass --types t1,t2,…", args, 2)
        }
        val results = JSONObject()
        val warnings = JSONArray()
        for (n in names) {
            val canonical = BATCH_ALIASES[n] ?: n
            if (canonical !in BATCHABLE) {
                warnings.put(JSONObject().put("code", "unsupported_in_batch").put("types", JSONArray().put(n)))
                continue
            }
            // Reuse the single-command readers; unwrap their envelope.
            val res = dispatch(canonical, args)
            val parsed = runCatching { JSONObject(res.output) }.getOrNull()
            if (parsed?.optBoolean("ok") == true) results.put(n, parsed.opt("data"))
            else warnings.put(JSONObject().put("code", "read_failed").put("types", JSONArray().put(n))
                .put("message", parsed?.optJSONObject("error")?.optString("message") ?: res.output.trim()))
        }
        val data = JSONObject().put("results", results).put("warnings", warnings)
        return OffloadEnvelope.ok(TOOL, "batch", data, args)
    }

    // ── Writers ─────────────────────────────────────────────────────────────

    private suspend fun log(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        val type = args.get("type")?.lowercase()
            ?: return OffloadEnvelope.error(TOOL, "log", "invalid_arguments", "--type is required. Run `types`.", args, 2)
        if (type.startsWith("blood-pressure")) {
            return OffloadEnvelope.error(
                TOOL, "log", "invalid_arguments",
                "blood-pressure-systolic/diastolic cannot be logged alone; use `log-blood-pressure`.", args, 2,
            )
        }
        val value = args.get("value")?.toDoubleOrNull()
            ?: return OffloadEnvelope.error(TOOL, "log", "invalid_arguments", "--value <number> is required.", args, 2)
        val zone = ZoneId.systemDefault()
        val at = OffloadDates.parse(args.get("date")) ?: Instant.now()
        val offset = zone.rules.getOffset(at)
        val record: Record = when (type) {
            "weight" -> WeightRecord(at, offset, Mass.kilograms(value), Metadata.manualEntry())
            "steps" -> StepsRecord(at.minusSeconds(60), offset, at, offset, value.toLong(), Metadata.manualEntry())
            "water", "hydration" -> HydrationRecord(
                at.minusSeconds(60), offset, at, offset,
                // --value is millilitres, as in the iOS `water` type.
                Volume.milliliters(value), Metadata.manualEntry(),
            )
            "oxygen-saturation", "blood-oxygen" -> OxygenSaturationRecord(at, offset, Percentage(value), Metadata.manualEntry())
            "blood-glucose" -> BloodGlucoseRecord(
                time = at, zoneOffset = offset, metadata = Metadata.manualEntry(),
                level = BloodGlucose.milligramsPerDeciliter(value),
            )
            "heart-rate" -> HeartRateRecord(
                at, offset, at.plusSeconds(1), offset,
                listOf(HeartRateRecord.Sample(at, value.toLong())), Metadata.manualEntry(),
            )
            "resting-heart-rate" -> RestingHeartRateRecord(at, offset, value.toLong(), Metadata.manualEntry())
            else -> return OffloadEnvelope.error(
                TOOL, "log", "unsupported_type",
                "Cannot log '$type'. Loggable: ${TYPE_TABLE.filter { it.log }.joinToString { it.name }}.", args, 2,
            )
        }
        ensureWrite(client, "log", args, record::class)?.let { return it }
        val ids = client.insertRecords(listOf(record)).recordIdsList
        val data = JSONObject().put("type", type).put("value", value).put("time", fmt(at)).put("id", ids.firstOrNull())
        return OffloadEnvelope.ok(TOOL, "log", data, args)
    }

    private suspend fun logBloodPressure(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        val sys = args.get("systolic")?.toDoubleOrNull()
        val dia = args.get("diastolic")?.toDoubleOrNull()
        if (sys == null || dia == null) {
            return OffloadEnvelope.error(TOOL, "log-blood-pressure", "invalid_arguments", "--systolic and --diastolic are required.", args, 2)
        }
        ensureWrite(client, "log-blood-pressure", args, BloodPressureRecord::class)?.let { return it }
        val at = OffloadDates.parse(args.get("date")) ?: Instant.now()
        val offset = ZoneId.systemDefault().rules.getOffset(at)
        val ids = client.insertRecords(
            listOf(
                BloodPressureRecord(
                    time = at, zoneOffset = offset, metadata = Metadata.manualEntry(),
                    systolic = Pressure.millimetersOfMercury(sys), diastolic = Pressure.millimetersOfMercury(dia),
                ),
            ),
        ).recordIdsList
        return OffloadEnvelope.ok(
            TOOL, "log-blood-pressure",
            JSONObject().put("systolic", sys).put("diastolic", dia).put("time", fmt(at)).put("id", ids.firstOrNull()), args,
        )
    }

    private suspend fun delete(client: HealthConnectClient, args: OffloadArgs): NativeOffloadResult {
        val type = args.get("type")?.lowercase()
            ?: return OffloadEnvelope.error(TOOL, "delete", "invalid_arguments", "--type is required.", args, 2)
        val cls = DELETABLE[type]
            ?: return OffloadEnvelope.error(TOOL, "delete", "unsupported_type", "Cannot delete '$type'. Supported: ${DELETABLE.keys.joinToString()}.", args, 2)
        val id = args.get("id")
        ensureWrite(client, "delete", args, cls)?.let { return it }
        if (id != null) {
            client.deleteRecords(cls, listOf(id), emptyList())
        } else {
            val (start, end) = OffloadDates.range(args)
            // Health Connect only ever deletes records this app wrote.
            client.deleteRecords(cls, TimeRangeFilter.between(start, end))
        }
        return OffloadEnvelope.ok(TOOL, "delete", JSONObject().put("type", type).put("deleted", true)
            .put("note", "Only records written by this app can be deleted."), args)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        var pages = 0
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token),
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null && ++pages < MAX_PAGES)
        return out
    }

    private fun fmt(i: Instant): String = OffloadDates.format(i)
    private fun round(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    private fun rangeJson(s: Instant, e: Instant) = JSONObject().put("start", fmt(s)).put("end", fmt(e))

    private class TypeInfo(val name: String, val desc: String, val unit: String, val read: Boolean, val log: Boolean)

    companion object {
        private const val TOOL = "android-health"
        private const val DEFAULT_LIMIT = 100
        private const val MAX_PAGES = 20
        private val BOOLEAN_FLAGS = setOf("today")

        private val BATCHABLE = setOf(
            "steps", "heart-rate", "resting-heart-rate", "vo2-max", "sleep", "workouts", "weight",
            "blood-oxygen", "blood-glucose", "hrv", "nutrition", "summary",
        )
        private val BATCH_ALIASES = mapOf(
            "hrv-sdnn" to "hrv", "oxygen-saturation" to "blood-oxygen", "active-energy" to "summary",
            "distance" to "summary", "body-mass" to "weight",
        )

        private val DELETABLE: Map<String, KClass<out Record>> = mapOf(
            "weight" to WeightRecord::class, "steps" to StepsRecord::class, "water" to HydrationRecord::class,
            "hydration" to HydrationRecord::class, "blood-oxygen" to OxygenSaturationRecord::class,
            "oxygen-saturation" to OxygenSaturationRecord::class, "blood-glucose" to BloodGlucoseRecord::class,
            "heart-rate" to HeartRateRecord::class, "resting-heart-rate" to RestingHeartRateRecord::class,
            "blood-pressure" to BloodPressureRecord::class,
        )

        private val TYPE_TABLE = listOf(
            TypeInfo("steps", "Step count, daily totals", "count", true, true),
            TypeInfo("heart-rate", "Heart rate samples", "bpm", true, true),
            TypeInfo("resting-heart-rate", "Resting heart rate", "bpm", true, true),
            TypeInfo("vo2-max", "Cardio fitness (VO2 max)", "mL/(kg·min)", true, false),
            TypeInfo("sleep", "Sleep sessions with stages", "minutes", true, false),
            TypeInfo("workouts", "Exercise sessions", "minutes", true, false),
            TypeInfo("weight", "Body mass", "kg", true, true),
            TypeInfo("blood-oxygen", "Oxygen saturation (SpO2)", "%", true, true),
            TypeInfo("blood-glucose", "Blood glucose", "mg/dL, mmol/L", true, true),
            TypeInfo("hrv", "Heart rate variability (RMSSD)", "ms", true, false),
            TypeInfo("nutrition", "Calories and macronutrients", "kcal, g", true, false),
            TypeInfo("water", "Hydration (log --value in millilitres)", "mL", false, true),
            TypeInfo("blood-pressure", "Blood pressure (use log-blood-pressure)", "mmHg", false, true),
            TypeInfo("summary", "Daily steps, distance, calories, exercise minutes", "various", true, false),
        )

        private val STAGE_NAMES = mapOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN to "unknown",
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake_in_bed",
        )

        private val EXERCISE_NAMES: Map<Int, String> = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP

        private val HELP = """android-health — read and log Health Connect data (JSON)

USAGE:
  android-health <command> [options]

COMMANDS:
  status               Is Health Connect available, which permissions are granted
  types                List the supported data types
  steps                Step count (daily buckets)
  heart-rate           Heart rate samples
  resting-heart-rate   Resting heart rate
  vo2-max              VO2 max
  sleep                Sleep sessions with stage minutes
  workouts             Exercise sessions   (--type running filters by name)
  weight               Body mass
  blood-oxygen         SpO2
  blood-glucose        Blood glucose
  hrv                  Heart rate variability (RMSSD)
  nutrition            Calories / macros (entries + totals)
  summary              Daily steps, distance, calories, exercise minutes
  batch                Several metrics in one call: --types steps,heart-rate,sleep
  log                  Write a sample: --type weight|steps|water|heart-rate|resting-heart-rate|
                       blood-oxygen|blood-glucose --value <n> [--date <datetime>]
  log-blood-pressure   --systolic <mmHg> --diastolic <mmHg> [--date <datetime>]
  delete               --type <type> [--id <id>] [--days N | --today | --start/--end]
                       (only records written by this app)

DATE OPTIONS:
  --start <datetime>   ISO 8601, YYYY-MM-DD or relative (-7d, -2h)
  --end <datetime>
  --days <N>           Last N days
  --today              Today
  --limit <N>          Max samples (default 100)

COMMON OPTIONS:
  --compact            Single-line JSON
  -q, --quiet          Only the data field

EXAMPLES:
  android-health steps --days 7
  android-health heart-rate --days 1 --limit 10
  android-health sleep --days 7 --compact -q
  android-health log --type water --value 250
  android-health batch --types steps,heart-rate,sleep --days 1

NOTES:
  Health Connect is built into Android 14+. On older Android, install the Health
  Connect app. Reads while the app is in the background need "Access data in the
  background" under Health Connect → App permissions → Minis.
"""
    }
}
