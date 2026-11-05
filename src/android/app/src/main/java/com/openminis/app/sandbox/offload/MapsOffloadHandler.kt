package com.openminis.app.sandbox.offload

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * android-maps — place search, directions and travel-time estimates.
 *
 * Android counterpart of iOS `apple-maps` (MapsOffload.m): `search`, `route`
 * and `eta` with the same options and result fields. Android has no built-in
 * place-search or routing engine, so this uses OpenStreetMap services:
 *
 *  - address → coordinates: the platform [Geocoder] when present, otherwise
 *    Nominatim;
 *  - `search`: Nominatim, bounded to a box around `--lat/--lon`;
 *  - `route` / `eta`: the public OSRM demo server (driving, walking, cycling).
 *    Public transit is not available (OSRM has no timetables) and says so.
 *
 * Those requests carry the query text or the coordinates to the service named in
 * each result's `provider` field, and both servers are volunteer-run with fair
 * use limits: this is for occasional lookups, not bulk. Results are not
 * GCJ-02-shifted (iOS flags that for mainland China).
 */
class MapsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    private val baseClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Through Settings > Network (proxy / Tailscale), like every other client in the app. */
    private val http: OkHttpClient
        get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseClient)

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("maps", "Maps", args, request)?.let { return it }
        return try {
            when (sub) {
                "search" -> search(args)
                "route" -> route(args, etaOnly = false)
                "eta" -> route(args, etaOnly = true)
                else -> OffloadEnvelope.error(TOOL, sub, "unknown_command", "Unknown command '$sub'. Valid commands: search, route, eta.", args, 2)
            }
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── search ──────────────────────────────────────────────────────────────

    private fun search(args: OffloadArgs): NativeOffloadResult {
        val query = args.get("query") ?: return OffloadEnvelope.error(TOOL, "search", "invalid_arguments", "Required: --query", args, 2)
        val lat = args.getDouble("lat", "latitude")
        val lon = args.getDouble("lon", "longitude")
        if (lat == null || lon == null) return OffloadEnvelope.error(TOOL, "search", "invalid_arguments", "Required: --lat and --lon", args, 2)
        val radius = args.getDouble("radius") ?: 1000.0
        val limit = (args.getInt("limit") ?: 10).let { if (it <= 0) 10 else it }

        // Bounding box of ±radius around the centre.
        val dLat = radius / 111_320.0
        val dLon = radius / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val url = "$NOMINATIM/search?format=jsonv2&addressdetails=1&limit=$limit&bounded=1" +
            "&viewbox=${lon - dLon},${lat + dLat},${lon + dLon},${lat - dLat}&q=${enc(query)}"
        val arr = JSONArray(get(url))
        val results = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val plat = o.getString("lat").toDouble()
            val plon = o.getString("lon").toDouble()
            results.put(JSONObject()
                .put("name", o.optString("name").ifEmpty { o.optString("display_name").substringBefore(",") })
                .put("address", o.optString("display_name"))
                .put("lat", plat).put("lon", plon)
                .put("category", o.optString("type"))
                .put("distance_m", Math.round(haversine(lat, lon, plat, plon))))
        }
        val data = JSONObject().put("query", query).put("count", results.length()).put("results", results)
            .put("provider", "OpenStreetMap Nominatim")
        return OffloadEnvelope.ok(TOOL, "search", data, args)
    }

    // ── route / eta ─────────────────────────────────────────────────────────

    private fun route(args: OffloadArgs, etaOnly: Boolean): NativeOffloadResult {
        val action = if (etaOnly) "eta" else "route"
        val fromStr = args.get("from")
        val toStr = args.get("to")
        if (fromStr == null || toStr == null) return OffloadEnvelope.error(TOOL, action, "invalid_arguments", "Required: --from and --to", args, 2)
        val mode = (args.get("mode") ?: "driving").lowercase()
        val profile = when (mode) {
            "driving", "car" -> "driving"
            "walking", "walk" -> "walking"
            "cycling", "bike", "bicycle" -> "cycling"
            "transit" -> return OffloadEnvelope.error(TOOL, action, "not_supported",
                "Public-transit routing is not available on Android (no timetable source). Use --mode driving, walking or cycling.", args, 3)
            else -> return OffloadEnvelope.error(TOOL, action, "invalid_arguments", "--mode must be driving, walking or cycling.", args, 2)
        }
        val from = resolve(fromStr) ?: return OffloadEnvelope.error(TOOL, action, "no_data", "Could not find the origin: $fromStr", args)
        val to = resolve(toStr) ?: return OffloadEnvelope.error(TOOL, action, "no_data", "Could not find the destination: $toStr", args)

        val url = "$OSRM/route/v1/$profile/${from.second},${from.first};${to.second},${to.first}" +
            "?overview=false&steps=${!etaOnly}&alternatives=false"
        val body = JSONObject(get(url))
        if (body.optString("code") != "Ok") {
            return OffloadEnvelope.error(TOOL, action, "no_data", "No route found (${body.optString("code")}).", args)
        }
        val r = body.getJSONArray("routes").getJSONObject(0)
        val seconds = r.getDouble("duration")
        val meters = r.getDouble("distance")
        val data = JSONObject()
            .put("from", JSONObject().put("input", fromStr).put("lat", from.first).put("lon", from.second))
            .put("to", JSONObject().put("input", toStr).put("lat", to.first).put("lon", to.second))
            .put("mode", mode)
            .put("distance_m", Math.round(meters)).put("distance_km", Math.round(meters / 100.0) / 10.0)
            .put("duration_s", Math.round(seconds)).put("duration_minutes", Math.round(seconds / 60.0))
            .put("provider", "OSRM (OpenStreetMap)")
        if (!etaOnly) {
            val steps = JSONArray()
            val legs = r.optJSONArray("legs")
            if (legs != null) for (li in 0 until legs.length()) {
                val ss = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until ss.length()) {
                    val st = ss.getJSONObject(si)
                    val man = st.optJSONObject("maneuver")
                    steps.put(JSONObject()
                        .put("instruction", listOfNotNull(man?.optString("type"), man?.optString("modifier")?.takeIf { it.isNotEmpty() }, st.optString("name").takeIf { it.isNotEmpty() }).joinToString(" "))
                        .put("distance_m", Math.round(st.optDouble("distance")))
                        .put("duration_s", Math.round(st.optDouble("duration"))))
                }
            }
            data.put("steps", steps)
        }
        return OffloadEnvelope.ok(TOOL, action, data, args)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** `lat,lon` literal or an address; (lat, lon) or null. */
    @Suppress("DEPRECATION")
    private fun resolve(s: String): Pair<Double, Double>? {
        val parts = s.split(',')
        if (parts.size == 2) {
            val a = parts[0].trim().toDoubleOrNull()
            val b = parts[1].trim().toDoubleOrNull()
            if (a != null && b != null && a in -90.0..90.0 && b in -180.0..180.0) return a to b
        }
        if (Geocoder.isPresent()) {
            val hit: Address? = runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(s, 1)?.firstOrNull() }.getOrNull()
            if (hit != null) return hit.latitude to hit.longitude
        }
        val arr = runCatching { JSONArray(get("$NOMINATIM/search?format=jsonv2&limit=1&q=${enc(s)}")) }.getOrNull()
        val o = arr?.optJSONObject(0) ?: return null
        return o.getString("lat").toDouble() to o.getString("lon").toDouble()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} from ${req.url.host}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val TOOL = "android-maps"
        private const val NOMINATIM = "https://nominatim.openstreetmap.org"
        private const val OSRM = "https://router.project-osrm.org"
        // Nominatim's usage policy requires an identifying User-Agent.
        private const val USER_AGENT = "Minis-Android/1.0 (android-maps CLI)"

        private val HELP = """android-maps — search places, get directions, estimate travel times (JSON)

USAGE:
  android-maps <command> [options]

COMMANDS:
  search      Search for places near a point
  route       Directions between two points
  eta         Travel time and distance only

SEARCH OPTIONS:
  --query <text>       Search query (required)
  --lat <degrees>      Centre latitude (required)
  --lon <degrees>      Centre longitude (required)
  --radius <m>         Search radius in metres (default 1000)
  --limit <N>          Maximum results (default 10)

ROUTE / ETA OPTIONS:
  --from <addr|lat,lon>  Origin (required)
  --to <addr|lat,lon>    Destination (required)
  --mode <mode>          driving, walking or cycling (default driving). Transit is not available.

COMMON OPTIONS:
  --compact, -q          Single-line JSON / data field only

DATA SOURCES:
  Queries and coordinates are sent to OpenStreetMap Nominatim (search, geocoding) and the public
  OSRM demo server (route, eta). Both are volunteer-run; use for occasional lookups.

EXAMPLES:
  android-maps search --query "coffee shops" --lat 37.5665 --lon 126.9780
  android-maps route --from "Seoul Station" --to "Gangnam Station" --mode walking
  android-maps eta --from 37.5665,126.9780 --to 35.1796,129.0756
"""
    }
}
