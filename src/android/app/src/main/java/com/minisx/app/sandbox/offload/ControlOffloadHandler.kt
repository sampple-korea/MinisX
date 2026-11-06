package com.minisx.app.sandbox.offload

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.WallpaperManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.minisx.app.accessibility.MinisAccessibilityService
import com.minisx.app.control.ControlCatalog
import com.minisx.app.control.ContactMatch
import com.minisx.app.control.ControlCommands
import com.minisx.app.logging.AppLogger
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.offload.RootCommandRisk
import com.minisx.app.offload.RootShell
import com.minisx.app.offload.ShizukuManager
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import com.minisx.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `android-control`: the assistant's hands on the phone. One CLI, about a hundred concrete actions
 * (see [ControlCatalog]): flashlight, volume, brightness, Wi-Fi, Bluetooth, Do Not Disturb, screen,
 * apps, calls, settings pages, sensors and more.
 *
 * Each action takes the least privileged path that works:
 *  1. the Android API itself (flashlight, volume, vibration, launching apps, ...);
 *  2. the accessibility service's global actions (home, back, quick settings, lock), when it is on;
 *  3. Shizuku, only when it is ready AND `android-shizuku-cli` is not set to "Not allowed";
 *  4. root through su, only through the same per-command approval `android-root-cli` uses;
 *  5. opening the right settings panel for the user, answered as `needs_user`.
 *
 * After a change it reads the state back and reports what the phone says, so "ok" means the switch
 * really moved. The commands run with privilege are built by [ControlCommands] from validated pieces.
 */
class ControlOffloadHandler(context: Context) : NativeOffloadHandler {

    private val ctx: Context = context.applicationContext

    private class Out(
        val ok: Boolean,
        val backend: String,
        val data: JSONObject = JSONObject(),
        val code: String? = null,
        val message: String? = null,
        val hint: String? = null,
    )

    private inner class Call(val entry: ControlCatalog.Entry, val args: OffloadArgs, val request: NativeOffloadRequest) {
        /** The words after `<group> <action>`. */
        val p: List<String> = args.positional.drop(2)
        fun arg(i: Int): String? = p.getOrNull(i)
        val yes: Boolean get() = args.hasFlag("yes", "y")
    }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        val args = OffloadArgs(argv, setOf("yes", "y", "help", "h"))
        val group = args.positional.getOrNull(0)
        val name = args.positional.getOrNull(1)
        if (group == null || group == "help") return NativeOffloadResult(0, help())
        if (group == "list") {
            val only = args.positional.getOrNull(1)
            return NativeOffloadResult(0, (if (only != null) ControlCatalog.groupListing(only).ifBlank { ControlCatalog.listing() } else ControlCatalog.listing()) + "\n")
        }
        OffloadGate.enforce("device_control", "android-control", args, request)?.let { return it }

        val entry = ControlCatalog.find(group, name)
        if (entry == null) {
            val near = ControlCatalog.suggest(group, name)
            val body = JSONObject().put("ok", false)
                .put("error", JSONObject().put("code", "unknown_action")
                    .put("message", "No action '${args.positional.take(2).joinToString(" ")}'. " +
                        (if (near.isNotEmpty()) "Actions in that area:\n" + near.joinToString("\n") { ControlCatalog.line(it) }
                        else "Run `android-control list` for every action.")))
            return NativeOffloadResult(2, OffloadOutput.formatBody(body.toString(2), args) + "\n")
        }
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, ControlCatalog.line(entry) + "\n")

        val call = Call(entry, args, request)
        val out = try {
            dispatch(call)
        } catch (e: SecurityException) {
            fail("permission_missing", "Android refused: ${e.message}", "A permission or special access is missing; run `android-control info access`.")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "${entry.key} failed: ${e.message}")
            fail("internal", e.message ?: e.javaClass.simpleName)
        }
        return reply(entry, args, out)
    }

    private fun reply(entry: ControlCatalog.Entry, args: OffloadArgs, out: Out): NativeOffloadResult {
        val obj = JSONObject().put("ok", out.ok).put("action", entry.key).put("backend", out.backend)
        if (out.ok) obj.put("data", out.data)
        else {
            obj.put("error", JSONObject().put("code", out.code ?: "failed").put("message", out.message ?: "failed"))
            if (out.data.length() > 0) obj.put("data", out.data)
            out.hint?.let { obj.put("hint", it) }
        }
        return NativeOffloadResult(if (out.ok) 0 else 1, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
    }

    private fun dispatch(c: Call): Out = when (c.entry.group) {
        "flashlight" -> flashlight(c)
        "volume" -> volume(c)
        "ringer", "dnd", "vibrate", "sound", "mic" -> soundGroup(c)
        "media" -> media(c)
        "brightness", "screen", "display" -> displayGroup(c)
        "wifi", "data", "airplane", "bluetooth", "nfc", "location", "network" -> connectivity(c)
        "battery", "power" -> power(c)
        "nav" -> nav(c)
        "app" -> app(c)
        "phone", "share", "camera", "wallpaper" -> communication(c)
        "settings" -> settingsPages(c)
        "sensor", "info", "timezone", "time" -> system(c)
        else -> fail("unsupported", "No handler for ${c.entry.key}")
    }

    // ─── Flashlight ─────────────────────────────────────────────────────────

    private object TorchWatch {
        private val thread by lazy { HandlerThread("control-torch").apply { start() } }
        val states = ConcurrentHashMap<String, Boolean>()
        private var registered = false
        private val first = CountDownLatch(1)

        fun ensure(cm: CameraManager) {
            val register = synchronized(this) { if (registered) false else { registered = true; true } }
            if (register) {
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        states[cameraId] = enabled
                        first.countDown()
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        states.remove(cameraId)
                        first.countDown()
                    }
                }, Handler(thread.looper))
            }
            first.await(800, TimeUnit.MILLISECONDS)
        }
    }

    private fun torchId(cm: CameraManager): String? {
        val withFlash = cm.cameraIdList.filter {
            runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }.getOrDefault(false)
        }
        return withFlash.firstOrNull {
            runCatching { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }.getOrDefault(false)
        } ?: withFlash.firstOrNull()
    }

    private fun flashlight(c: Call): Out {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return fail("unavailable", "No camera service on this device.")
        val id = torchId(cm) ?: return fail("no_flash", "This phone has no flashlight (no camera reports a flash unit).")
        TorchWatch.ensure(cm)
        return when (c.entry.key) {
            "flashlight status" -> good("camera2") {
                put("on", TorchWatch.states[id] ?: false)
                put("camera_id", id)
                if (Build.VERSION.SDK_INT >= 33) {
                    val ch = cm.getCameraCharacteristics(id)
                    put("max_level", ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1)
                    put("default_level", ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1)
                    if (TorchWatch.states[id] == true) put("level", runCatching { cm.getTorchStrengthLevel(id) }.getOrNull() ?: JSONObject.NULL)
                }
            }
            "flashlight on" -> setTorch(cm, id, true)
            "flashlight off" -> setTorch(cm, id, false)
            "flashlight toggle" -> setTorch(cm, id, TorchWatch.states[id] != true)
            "flashlight level" -> {
                val want = c.arg(0)?.toIntOrNull() ?: return fail("bad_args", "flashlight level needs a number, e.g. `flashlight level 3`.")
                if (Build.VERSION.SDK_INT < 33) return fail("unsupported", "Flashlight brightness levels need Android 13 or newer; use `flashlight on`.")
                val max = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                if (max <= 1) return fail("unsupported", "This flashlight has a single brightness; use `flashlight on`.")
                setTorch(cm, id, true, want.coerceIn(1, max))
            }
            "flashlight blink" -> {
                val count = (c.args.getInt("count") ?: 3).coerceIn(1, 20)
                val interval = (c.args.getInt("interval") ?: 300).coerceIn(80, 2000).toLong()
                try {
                    repeat(count) {
                        cm.setTorchMode(id, true); Thread.sleep(interval)
                        cm.setTorchMode(id, false); Thread.sleep(interval)
                    }
                } catch (e: CameraAccessException) {
                    return fail("camera_in_use", "The camera is busy, so the flashlight cannot blink: ${e.message}")
                } finally {
                    runCatching { cm.setTorchMode(id, false) }
                }
                good("camera2") { put("blinks", count); put("interval_ms", interval) }
            }
            else -> unsupported(c)
        }
    }

    private fun setTorch(cm: CameraManager, id: String, on: Boolean, level: Int? = null): Out {
        try {
            if (on && level != null && Build.VERSION.SDK_INT >= 33) cm.turnOnTorchWithStrengthLevel(id, level) else cm.setTorchMode(id, on)
        } catch (e: CameraAccessException) {
            return fail("camera_in_use", "The camera is busy (another app is using it), so the flashlight cannot be switched: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return fail("bad_args", e.message ?: "invalid flashlight request")
        }
        val after = settle({ TorchWatch.states[id] }) { it == on }
        return if (after == on) good("camera2") { put("on", on); level?.let { put("level", it) } }
        else fail("state_unchanged", "The flashlight did not report ${if (on) "on" else "off"} after the request.")
    }

    // ─── Volume ─────────────────────────────────────────────────────────────

    private val audio: AudioManager get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamState(stream: Int): JSONObject {
        val am = audio
        val max = am.getStreamMaxVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        val cur = am.getStreamVolume(stream)
        return JSONObject().put("stream", ControlCommands.streamName(stream)).put("level", cur).put("min", min).put("max", max)
            .put("percent", ControlCommands.levelToPercent(cur, min, max)).put("muted", am.isStreamMute(stream))
    }

    private fun volume(c: Call): Out {
        val am = audio
        // `volume set 50` means the music stream.
        val first = c.arg(0)
        val numberFirst = first != null && ControlCommands.percent(first) != null && ControlCommands.stream(first) == null
        val streamArg = if (numberFirst) null else first
        val stream = ControlCommands.stream(streamArg)
            ?: return fail("bad_args", "Unknown stream '$streamArg'. Use music, ring, alarm, notification, call or system.")
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        val max = am.getStreamMaxVolume(stream)
        return try {
            when (c.entry.key) {
                "volume get" -> if (first == null) good("audio") {
                    put("streams", JSONArray().also { arr -> ControlCommands.REPORTED_STREAMS.forEach { arr.put(streamState(it.second)) } })
                } else good("audio") { put("volume", streamState(stream)) }
                "volume set" -> {
                    val raw = c.args.getInt("raw")
                    val pct = ControlCommands.percent(if (numberFirst) first else c.arg(1))
                    val level = raw?.coerceIn(min, max)
                        ?: pct?.let { ControlCommands.percentToLevel(it, min, max) }
                        ?: return fail("bad_args", "volume set needs a percent (0-100), e.g. `volume set music 40`, or --raw N.")
                    am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
                    verifyVolume(stream) { it.getInt("level") == level }
                }
                "volume max" -> { am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI); verifyVolume(stream) { it.getInt("level") == max } }
                "volume up", "volume down" -> {
                    val steps = (c.args.getInt("steps") ?: 1).coerceIn(1, 30)
                    val dir = if (c.entry.name == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    repeat(steps) { am.adjustStreamVolume(stream, dir, if (it == steps - 1) AudioManager.FLAG_SHOW_UI else 0) }
                    good("audio") { put("volume", streamState(stream)) }
                }
                "volume mute", "volume unmute" -> {
                    val mute = c.entry.name == "mute"
                    am.adjustStreamVolume(stream, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    verifyVolume(stream) { it.getBoolean("muted") == mute }
                }
                else -> unsupported(c)
            }
        } catch (e: SecurityException) {
            needsDndAccess(c, "Android does not let apps change the ${ControlCommands.streamName(stream)} volume while Do Not Disturb rules apply: ${e.message}")
        }
    }

    private fun verifyVolume(stream: Int, ok: (JSONObject) -> Boolean): Out {
        val s = settle({ streamState(stream) }) { it != null && ok(it) } ?: streamState(stream)
        return if (ok(s)) good("audio") { put("volume", s) }
        else Out(false, "audio", JSONObject().put("volume", s), "state_unchanged", "The volume did not change as asked (the phone may be limiting it).")
    }

    // ─── Ringer, DND, vibration, tones, microphone ──────────────────────────

    private val notificationManager: NotificationManager get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun dndAccessGranted(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    private fun needsDndAccess(c: Call, message: String): Out {
        val granted = runBlocking {
            OffloadPermissionManager.requestSettingsGate(
                OffloadPermissionManager.SettingsGateRequest(
                    id = "dnd_access",
                    title = ko("Do Not Disturb access", "방해금지 모드 접근"),
                    message = ko("To change Do Not Disturb and silent mode, allow MinisX in the next screen.", "방해금지·무음 모드를 바꾸려면 다음 화면에서 MinisX를 허용해 주세요."),
                    settingsAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                    requiresPackageUri = false,
                    positiveLabel = ko("Open settings", "설정 열기"),
                    negativeLabel = ko("Cancel", "취소"),
                ),
            ) { dndAccessGranted() }
        } == OffloadPermissionManager.AndroidPermissionResult.GRANTED
        return if (granted) fail("retry", "Do Not Disturb access was just granted; run the command again.")
        else Out(false, "settings", JSONObject(), "needs_user", message, ENABLE_HINT)
    }

    private fun filterState(): Int = notificationManager.currentInterruptionFilter

    private fun soundGroup(c: Call): Out = when (c.entry.key) {
        "ringer get" -> good("audio") { put("mode", ControlCommands.ringerName(audio.ringerMode)) }
        "ringer set" -> {
            val mode = ControlCommands.ringerMode(c.arg(0)) ?: return fail("bad_args", "ringer set needs normal, vibrate or silent.")
            try {
                audio.ringerMode = mode
                val after = settle({ audio.ringerMode }) { it == mode }
                if (after == mode) good("audio") { put("mode", ControlCommands.ringerName(mode)) }
                else fail("state_unchanged", "The ringer mode did not change (Do Not Disturb may be overriding it).")
            } catch (e: SecurityException) {
                needsDndAccess(c, "Android needs Do Not Disturb access to set the ringer to ${ControlCommands.ringerName(mode)}.")
            }
        }
        "dnd status" -> good("notification-manager") {
            put("mode", ControlCommands.filterName(filterState()))
            put("access_granted", dndAccessGranted())
        }
        "dnd off", "dnd priority", "dnd alarms", "dnd silence" -> setDnd(c)
        "vibrate once" -> vibrate(longArrayOf(0, (c.args.getInt("ms") ?: 400).coerceIn(20, 5000).toLong()), c)
        "vibrate pattern" -> {
            val parts = (c.arg(0) ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }
            if (parts.size < 2 || parts.any { it < 0 } || parts.sum() > 10_000) fail("bad_args", "vibrate pattern needs milliseconds like 0,200,100,200 (at most 10 s in all).")
            else vibrate(parts.toLongArray(), c)
        }
        "vibrate cancel" -> { vibrator()?.cancel(); good("vibrator") { put("vibrating", false) } }
        "sound beep" -> {
            val ms = (c.args.getInt("ms") ?: 300).coerceIn(50, 3000)
            val stream = ControlCommands.stream(c.args.get("stream")) ?: ControlCommands.STREAM_MUSIC
            val tg = ToneGenerator(stream, 90)
            try {
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, ms)
                Thread.sleep(ms.toLong() + 100)
            } finally { tg.release() }
            good("tone-generator") { put("ms", ms) }
        }
        "mic status" -> good("audio") { put("muted", audio.isMicrophoneMute) }
        "mic mute", "mic unmute" -> {
            val mute = c.entry.name == "mute"
            audio.isMicrophoneMute = mute
            val after = settle({ audio.isMicrophoneMute }) { it == mute }
            if (after == mute) good("audio") { put("muted", mute) } else fail("state_unchanged", "The microphone mute did not change.")
        }
        else -> unsupported(c)
    }

    private fun setDnd(c: Call): Out {
        val want = when (c.entry.name) { "off" -> ControlCommands.FILTER_ALL; "priority" -> ControlCommands.FILTER_PRIORITY; "alarms" -> ControlCommands.FILTER_ALARMS; else -> ControlCommands.FILTER_NONE }
        if (filterState() == want) return good("none") { put("mode", ControlCommands.filterName(want)); put("changed", false) }
        if (dndAccessGranted()) {
            notificationManager.setInterruptionFilter(want)
            val after = settle({ filterState() }) { it == want }
            return if (after == want) good("notification-manager") { put("mode", ControlCommands.filterName(want)) }
            else fail("state_unchanged", "Do Not Disturb did not change.")
        }
        val word = when (want) { ControlCommands.FILTER_ALL -> "off"; ControlCommands.FILTER_PRIORITY -> "priority"; ControlCommands.FILTER_ALARMS -> "alarms"; else -> "none" }
        val p = priv(c, listOf("cmd notification set_dnd $word", "settings put global zen_mode ${ControlCommands.zenMode(want)}"))
        if (p.ran && p.ok) {
            val after = settle({ filterState() }) { it == want }
            return if (after == want) good(p.backend) { put("mode", ControlCommands.filterName(want)) } else fail("state_unchanged", "Do Not Disturb did not change after the command.")
        }
        return needsDndAccess(c, "Do Not Disturb needs access for MinisX. ${p.reason}")
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun vibrate(timings: LongArray, c: Call): Out {
        val v = vibrator()
        if (v == null || !v.hasVibrator()) return fail("unsupported", "This phone has no vibrator.")
        v.vibrate(VibrationEffect.createWaveform(timings, -1))
        return good("vibrator") { put("total_ms", timings.sum()) }
    }

    // ─── Media keys ─────────────────────────────────────────────────────────

    private fun media(c: Call): Out {
        val key = ControlCommands.mediaKey(c.entry.name) ?: return unsupported(c)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        return good("media-key") { put("key", c.entry.name); put("music_active", audio.isMusicActive) }
    }

    // ─── Display ────────────────────────────────────────────────────────────

    private val resolver get() = ctx.contentResolver

    private fun sysInt(key: String, def: Int): Int = Settings.System.getInt(resolver, key, def)

    /** Writes a `Settings.System` value: directly with the write-settings grant, else privileged, else by asking the user for the grant. */
    private fun setSystem(c: Call, vararg pairs: Pair<String, String>): Out? {
        fun direct(): Boolean = pairs.all { (k, v) -> Settings.System.putString(resolver, k, v) }
        if (Settings.System.canWrite(ctx) && direct()) return null
        val p = priv(c, listOf(pairs.joinToString(" && ") { (k, v) -> "settings put system $k $v" }))
        if (p.ran && p.ok) return null
        val granted = runBlocking {
            OffloadPermissionManager.requestSettingsGate(
                OffloadPermissionManager.SettingsGateRequest(
                    id = "write_settings",
                    title = ko("Modify system settings", "시스템 설정 변경"),
                    message = ko("To change brightness, screen timeout and rotation, allow MinisX to modify system settings in the next screen.", "밝기·화면 꺼짐 시간·회전을 바꾸려면 다음 화면에서 MinisX의 시스템 설정 변경을 허용해 주세요."),
                    settingsAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    requiresPackageUri = true,
                    positiveLabel = ko("Open settings", "설정 열기"),
                    negativeLabel = ko("Cancel", "취소"),
                ),
            ) { Settings.System.canWrite(ctx) }
        } == OffloadPermissionManager.AndroidPermissionResult.GRANTED
        if (granted && direct()) return null
        return Out(false, "settings", JSONObject(), "needs_user", "Changing this needs the 'Modify system settings' special access for MinisX (or Shizuku/root). ${p.reason}", ENABLE_HINT)
    }

    private fun displayGroup(c: Call): Out {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (c.entry.key) {
            "brightness get" -> good("settings") {
                put("percent", ControlCommands.brightnessToPercent(sysInt(Settings.System.SCREEN_BRIGHTNESS, 128)))
                put("auto", sysInt(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1)
            }
            "brightness set" -> {
                val pct = ControlCommands.percent(c.arg(0)) ?: return fail("bad_args", "brightness set needs a percent 0-100, e.g. `brightness set 50`.")
                val value = ControlCommands.percentToBrightness(pct)
                setSystem(c, Settings.System.SCREEN_BRIGHTNESS_MODE to "0", Settings.System.SCREEN_BRIGHTNESS to value.toString())?.let { return it }
                val after = settle({ sysInt(Settings.System.SCREEN_BRIGHTNESS, -1) }) { it == value }
                if (after == value) good("settings") { put("percent", pct); put("auto", false) } else fail("state_unchanged", "The brightness setting did not change.")
            }
            "brightness auto" -> {
                val on = onOff(c.arg(0)) ?: return fail("bad_args", "brightness auto needs on or off.")
                setSystem(c, Settings.System.SCREEN_BRIGHTNESS_MODE to if (on) "1" else "0")?.let { return it }
                good("settings") { put("auto", sysInt(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) }
            }
            "screen status" -> {
                val kg = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                good("power-manager") { put("on", pm.isInteractive); put("locked", kg.isKeyguardLocked); put("device_secure", kg.isDeviceSecure) }
            }
            "screen wake" -> {
                if (pm.isInteractive) return good("none") { put("on", true); put("changed", false) }
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "minisx:control-wake")
                wl.acquire(3000)
                var backend = "power-manager"
                if (settle({ pm.isInteractive }) { it == true } != true) {
                    val p = priv(c, ControlCommands.wake())
                    if (!p.ran || !p.ok) return fail("state_unchanged", "The screen did not turn on. ${p.reason}", ENABLE_HINT)
                    backend = p.backend
                }
                good(backend) { put("on", pm.isInteractive) }
            }
            "screen off" -> lockScreen(c, pm)
            "screen timeout" -> {
                val secs = c.arg(0)
                if (secs == null) return good("settings") { put("seconds", sysInt(Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000) }
                val s = secs.toIntOrNull()?.takeIf { it in 5..3600 } ?: return fail("bad_args", "screen timeout takes seconds between 5 and 3600.")
                setSystem(c, Settings.System.SCREEN_OFF_TIMEOUT to (s * 1000).toString())?.let { return it }
                good("settings") { put("seconds", sysInt(Settings.System.SCREEN_OFF_TIMEOUT, 0) / 1000) }
            }
            "screen rotation" -> {
                val mode = c.arg(0)?.lowercase()
                if (mode == "auto") {
                    setSystem(c, Settings.System.ACCELEROMETER_ROTATION to "1")?.let { return it }
                } else {
                    val rot = ControlCommands.rotation(mode) ?: return fail("bad_args", "screen rotation takes auto, portrait, landscape, reverse-portrait or reverse-landscape.")
                    setSystem(c, Settings.System.ACCELEROMETER_ROTATION to "0", Settings.System.USER_ROTATION to rot.toString())?.let { return it }
                }
                good("settings") { put("auto", sysInt(Settings.System.ACCELEROMETER_ROTATION, 0) == 1); put("user_rotation", sysInt(Settings.System.USER_ROTATION, 0)) }
            }
            "screen stay-awake" -> {
                val on = onOff(c.arg(0)) ?: return fail("bad_args", "screen stay-awake needs on or off.")
                privOnly(c, ControlCommands.stayAwake(on), ENABLE_HINT, DEVELOPER_PANEL) {
                    good(it) { put("stay_awake_while_charging", Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0) }
                }
            }
            "screen screenshot" -> screenshot(c)
            "display info" -> {
                val dm = ctx.resources.displayMetrics
                val display = ctx.getSystemService(Context.DISPLAY_SERVICE).let { (it as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY) }
                good("display") {
                    put("width_px", dm.widthPixels); put("height_px", dm.heightPixels); put("density_dpi", dm.densityDpi)
                    put("refresh_rate_hz", display?.refreshRate ?: 0f)
                    put("font_scale", ctx.resources.configuration.fontScale)
                    put("dark_mode", (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                }
            }
            "display font-scale" -> {
                val v = c.arg(0) ?: return good("settings") { put("font_scale", ctx.resources.configuration.fontScale) }
                val f = v.toFloatOrNull()?.takeIf { it in 0.5f..2.0f } ?: return fail("bad_args", "display font-scale takes a number between 0.5 and 2.0 (1.0 is normal).")
                setSystem(c, Settings.System.FONT_SCALE to f.toString())?.let { return it }
                good("settings") { put("font_scale", Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f)) }
            }
            "display density" -> {
                val v = c.arg(0) ?: return good("display") { put("density_dpi", ctx.resources.displayMetrics.densityDpi) }
                val cmds = ControlCommands.density(v) ?: return fail("bad_args", "display density takes a dpi between 72 and 960, or `reset`.")
                privOnly(c, cmds, ENABLE_HINT, null) { good(it) { put("requested", v) } }
            }
            "display size" -> {
                val v = c.arg(0) ?: return good("display") { put("width_px", ctx.resources.displayMetrics.widthPixels); put("height_px", ctx.resources.displayMetrics.heightPixels) }
                val cmds = ControlCommands.size(v) ?: return fail("bad_args", "display size takes WIDTHxHEIGHT (e.g. 1080x2400) or `reset`.")
                privOnly(c, cmds, ENABLE_HINT, null) { good(it) { put("requested", v) } }
            }
            "display dark-mode" -> {
                val mode = c.arg(0)?.lowercase() ?: return fail("bad_args", "display dark-mode needs on, off or auto.")
                val cmds = ControlCommands.darkMode(mode) ?: return fail("bad_args", "display dark-mode needs on, off or auto.")
                privOnly(c, cmds, ENABLE_HINT, "android.settings.DISPLAY_SETTINGS") {
                    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                    good(it) { put("night_mode", when (ui.nightMode) { UiModeManager.MODE_NIGHT_YES -> "on"; UiModeManager.MODE_NIGHT_NO -> "off"; else -> "auto" }) }
                }
            }
            "display night-light" -> {
                val on = onOff(c.arg(0)) ?: return fail("bad_args", "display night-light needs on or off.")
                privOnly(c, ControlCommands.nightLight(on), ENABLE_HINT, "android.settings.DISPLAY_SETTINGS") {
                    good(it) { put("night_light", Settings.Secure.getInt(resolver, "night_display_activated", 0) == 1) }
                }
            }
            "display invert" -> {
                val on = onOff(c.arg(0)) ?: return fail("bad_args", "display invert needs on or off.")
                privOnly(c, ControlCommands.invert(on), ENABLE_HINT, "android.settings.ACCESSIBILITY_SETTINGS") {
                    good(it) { put("inverted", Settings.Secure.getInt(resolver, "accessibility_display_inversion_enabled", 0) == 1) }
                }
            }
            "display animations" -> {
                val scale = ControlCommands.animationScale(c.arg(0))
                if (scale == null) {
                    if (c.arg(0) == null) return good("settings") { put("animator_scale", Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
                    return fail("bad_args", "display animations takes on, off or a number from 0 to 10.")
                }
                privOnly(c, ControlCommands.animations(scale), ENABLE_HINT, DEVELOPER_PANEL) {
                    good(it) { put("animator_scale", Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
                }
            }
            else -> unsupported(c)
        }
    }

    private fun lockScreen(c: Call, pm: PowerManager): Out {
        if (!pm.isInteractive) return good("none") { put("on", false); put("changed", false) }
        var backend: String? = null
        if (Build.VERSION.SDK_INT >= 28 && globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) backend = "accessibility"
        if (backend == null) {
            val p = priv(c, ControlCommands.sleep())
            if (!p.ran || !p.ok) return Out(false, "none", JSONObject(), "needs_user", "Turning the screen off needs the accessibility service, Shizuku or root. ${p.reason}", ENABLE_HINT)
            backend = p.backend
        }
        val after = settle({ pm.isInteractive }) { it == false }
        return if (after == false) good(backend) { put("on", false) } else fail("state_unchanged", "The screen is still on.")
    }

    private fun screenshot(c: Call): Out {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val guestPath = c.args.get("path") ?: "/var/minis/workspace/screenshots/screen-$stamp.png"
        val dest = (runCatching { PRootKernel.resolveHostPath(guestPath) }.getOrNull() ?: File(guestPath)).also { it.parentFile?.mkdirs() }
        var backend: String? = null
        val svc = a11y()
        if (svc != null && Build.VERSION.SDK_INT >= 30) {
            val shot = svc.captureScreenshot()
            val bmp = shot.bitmap
            if (bmp != null) {
                try {
                    dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    backend = "accessibility"
                } finally { bmp.recycle() }
            }
        }
        if (backend == null) {
            val shared = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots/MinisX-$stamp.png")
            val p = priv(c, listOf("mkdir -p ${ControlCommands.quote(shared.parent ?: "/sdcard/Pictures")} && " + ControlCommands.screencap(shared.path)[0]))
            if (!p.ran || !p.ok || !shared.exists()) return Out(false, "none", JSONObject(), "needs_user", "A screenshot needs the accessibility service (Android 11+), Shizuku or root. ${p.reason}", ENABLE_HINT)
            runCatching { shared.copyTo(dest, overwrite = true) }
            backend = p.backend
        }
        return if (dest.exists() && dest.length() > 0) good(backend) { put("path", guestPath); put("bytes", dest.length()) }
        else fail("write_failed", "The screenshot could not be saved to $guestPath.")
    }

    // ─── Connectivity ───────────────────────────────────────────────────────

    private class Toggle(
        val label: String,
        val read: () -> Boolean?,
        val cmds: (Boolean) -> List<String>,
        val panel: String?,
        val direct: ((Boolean) -> Boolean)? = null,
    )

    private fun setToggle(c: Call, t: Toggle, want: Boolean): Out {
        val before = t.read()
        if (before == want) return good("none") { put("on", want); put("changed", false) }
        t.direct?.let { d ->
            if (runCatching { d(want) }.getOrDefault(false) && settle({ t.read() }) { it == want } == want) return good("direct") { put("on", want) }
        }
        val p = priv(c, t.cmds(want))
        if (p.ran) {
            if (!p.ok) return fail("command_failed", p.output.ifBlank { "the command failed" }, ENABLE_HINT)
            val after = settle({ t.read() }) { it == null || it == want }
            return if (after == null || after == want) good(p.backend) { put("on", want); if (after == null) put("verified", false) }
            else fail("state_unchanged", "${t.label} did not switch ${if (want) "on" else "off"} after the command.")
        }
        return needsUser(t.panel, "${t.label} cannot be switched by an app on this Android version. ${p.reason}")
    }

    private fun wifiManager() = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private fun connectivityManager() = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun bluetoothAdapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureRuntime(vararg perms: String): Boolean {
        if (perms.all { hasPermission(it) }) return true
        return runBlocking { OffloadPermissionManager.requestAndroidPermission(perms.toList()) } == OffloadPermissionManager.AndroidPermissionResult.GRANTED &&
            perms.all { hasPermission(it) }
    }

    private fun connectivity(c: Call): Out {
        val sdk = Build.VERSION.SDK_INT
        return when (c.entry.key) {
            "wifi status" -> {
                val wm = wifiManager()
                good("wifi-manager") {
                    put("enabled", wm.isWifiEnabled)
                    @Suppress("DEPRECATION") val info = wm.connectionInfo
                    if (wm.isWifiEnabled && info != null && info.networkId != -1) {
                        put("ssid", info.ssid?.trim('"') ?: JSONObject.NULL); put("rssi_dbm", info.rssi); put("link_speed_mbps", info.linkSpeed); put("frequency_mhz", info.frequency)
                    }
                    if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) put("note", "Network name is hidden until MinisX has location permission.")
                }
            }
            "wifi on", "wifi off" -> setToggle(c, Toggle("Wi-Fi", { wifiManager().isWifiEnabled }, { ControlCommands.wifi(it, sdk) }, "android.settings.panel.action.WIFI",
                direct = if (sdk < 29) ({ wifiSet(it) }) else null), c.entry.name == "on")
            "wifi scan" -> {
                if (!ensureRuntime(Manifest.permission.ACCESS_FINE_LOCATION)) return fail("permission_missing", "Listing Wi-Fi networks needs location permission.", "Ask the user to allow location for MinisX.")
                val wm = wifiManager()
                if (!wm.isWifiEnabled) return fail("wifi_off", "Wi-Fi is off; run `android-control wifi on` first.")
                @Suppress("DEPRECATION") wm.startScan()
                Thread.sleep(2500)
                val results = wm.scanResults.sortedByDescending { it.level }.take(30)
                good("wifi-manager") {
                    put("count", results.size)
                    put("networks", JSONArray().also { arr -> results.forEach { r ->
                        @Suppress("DEPRECATION") arr.put(JSONObject().put("ssid", r.SSID ?: "").put("signal_dbm", r.level).put("frequency_mhz", r.frequency).put("security", r.capabilities ?: ""))
                    } })
                }
            }
            "wifi connect" -> {
                val ssid = c.arg(0) ?: return fail("bad_args", "wifi connect needs <ssid> [password].")
                val security = c.args.get("security") ?: if (c.arg(1) == null) "open" else "wpa2"
                val cmds = ControlCommands.wifiConnect(ssid, c.arg(1), security) ?: return fail("bad_args", "wifi connect: use --security open|wpa2|wpa3, and give a password for wpa2/wpa3.")
                if (sdk < 30) return fail("unsupported", "Joining a network from a command needs Android 11 or newer; use `settings open wifi`.")
                privOnly(c, cmds, ENABLE_HINT, "android.settings.panel.action.WIFI") { b ->
                    Thread.sleep(3000)
                    good(b) { put("requested_ssid", ssid); put("enabled", wifiManager().isWifiEnabled) }
                }
            }
            "wifi saved" -> privOnly(c, ControlCommands.wifiSaved(), ENABLE_HINT, "android.settings.WIFI_SETTINGS", capture = true) { b -> good(b) { put("networks", lastPrivOutput) } }
            "data status" -> good("settings") { put("enabled", Settings.Global.getInt(resolver, "mobile_data", -1).let { if (it < 0) JSONObject.NULL else it == 1 }) }
            "data on", "data off" -> setToggle(c, Toggle("Mobile data", { Settings.Global.getInt(resolver, "mobile_data", -1).let { if (it < 0) null else it == 1 } }, { ControlCommands.data(it) }, "android.settings.DATA_USAGE_SETTINGS"), c.entry.name == "on")
            "data saver" -> {
                val on = onOff(c.arg(0)) ?: return fail("bad_args", "data saver needs on or off.")
                privOnly(c, ControlCommands.dataSaver(on), ENABLE_HINT, "android.settings.DATA_USAGE_SETTINGS") {
                    good(it) { put("data_saver_status", connectivityManager().restrictBackgroundStatus) }
                }
            }
            "airplane status" -> good("settings") { put("enabled", Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) }
            "airplane on", "airplane off" -> setToggle(c, Toggle("Airplane mode", { Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }, { ControlCommands.airplane(it, sdk) }, "android.settings.AIRPLANE_MODE_SETTINGS"), c.entry.name == "on")
            "bluetooth status" -> {
                val ad = bluetoothAdapter() ?: return fail("unsupported", "This phone has no Bluetooth.")
                good("bluetooth") { put("enabled", btEnabled(ad)) }
            }
            "bluetooth on", "bluetooth off" -> {
                val ad = bluetoothAdapter() ?: return fail("unsupported", "This phone has no Bluetooth.")
                setToggle(c, Toggle("Bluetooth", { btEnabled(ad) }, { ControlCommands.bluetooth(it, sdk) }, "android.settings.BLUETOOTH_SETTINGS",
                    direct = if (sdk < 33) ({ btSet(ad, it) }) else null), c.entry.name == "on")
            }
            "bluetooth paired" -> {
                val ad = bluetoothAdapter() ?: return fail("unsupported", "This phone has no Bluetooth.")
                if (sdk >= 31 && !ensureRuntime(Manifest.permission.BLUETOOTH_CONNECT)) return fail("permission_missing", "Listing paired devices needs the Nearby devices permission.", "Ask the user to allow it.")
                @Suppress("MissingPermission") val bonded = ad.bondedDevices.orEmpty()
                good("bluetooth") { put("devices", JSONArray().also { arr -> bonded.forEach { arr.put(JSONObject().put("name", it.name ?: "").put("address", it.address)) } }) }
            }
            "nfc status" -> good("nfc") { put("supported", NfcAdapter.getDefaultAdapter(ctx) != null); put("enabled", NfcAdapter.getDefaultAdapter(ctx)?.isEnabled ?: false) }
            "nfc on", "nfc off" -> {
                if (NfcAdapter.getDefaultAdapter(ctx) == null) return fail("unsupported", "This phone has no NFC.")
                setToggle(c, Toggle("NFC", { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled }, { ControlCommands.nfc(it) }, "android.settings.panel.action.NFC"), c.entry.name == "on")
            }
            "location status" -> good("location-manager") {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                put("enabled", LocationManagerCompat.isLocationEnabled(lm))
                put("gps", runCatching { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }.getOrDefault(false))
                put("network", runCatching { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }.getOrDefault(false))
            }
            "location on", "location off" -> {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                setToggle(c, Toggle("Location", { LocationManagerCompat.isLocationEnabled(lm) }, { ControlCommands.location(it, sdk) }, "android.settings.LOCATION_SOURCE_SETTINGS"), c.entry.name == "on")
            }
            "network status" -> {
                val cm = connectivityManager()
                val net = cm.activeNetwork
                val caps = net?.let { cm.getNetworkCapabilities(it) }
                if (net == null || caps == null) return good("connectivity") { put("connected", false) }
                val lp = cm.getLinkProperties(net)
                good("connectivity") {
                    put("connected", true)
                    put("transport", listOfNotNull(
                        "wifi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }, "cellular".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                        "ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }, "vpn".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                        "bluetooth".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
                    ).joinToString(","))
                    put("internet_validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    put("down_kbps", caps.linkDownstreamBandwidthKbps); put("up_kbps", caps.linkUpstreamBandwidthKbps)
                    lp?.let { l -> put("addresses", JSONArray(l.linkAddresses.map { it.address.hostAddress })); put("dns", JSONArray(l.dnsServers.map { it.hostAddress })) }
                }
            }
            else -> unsupported(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiSet(on: Boolean): Boolean = wifiManager().setWifiEnabled(on)

    @Suppress("DEPRECATION", "MissingPermission")
    private fun btSet(ad: android.bluetooth.BluetoothAdapter, on: Boolean): Boolean = if (on) ad.enable() else ad.disable()

    private fun btEnabled(ad: android.bluetooth.BluetoothAdapter): Boolean? =
        runCatching { ad.isEnabled }.getOrNull() ?: Settings.Global.getInt(resolver, "bluetooth_on", -1).let { if (it < 0) null else it == 1 }

    // ─── Power ──────────────────────────────────────────────────────────────

    private fun power(c: Call): Out {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (c.entry.key) {
            "battery saver" -> {
                val mode = c.arg(0)?.lowercase() ?: "status"
                if (mode == "status") return good("power-manager") { put("enabled", pm.isPowerSaveMode) }
                val on = onOff(mode) ?: return fail("bad_args", "battery saver takes on, off or status.")
                setToggle(c, Toggle("Battery saver", { pm.isPowerSaveMode }, { ControlCommands.batterySaver(it) }, "android.settings.BATTERY_SAVER_SETTINGS"), on)
            }
            "power reboot", "power shutdown" -> {
                if (!c.yes) return fail("confirm_required", "This ${c.entry.name}s the phone and ends the session. Ask the user first, then repeat with --yes.")
                val cmds = if (c.entry.name == "reboot") ControlCommands.reboot() else ControlCommands.shutdown()
                privOnly(c, cmds, ENABLE_HINT, null) { good(it) { put("requested", c.entry.name) } }
            }
            "power menu" -> navAction(c, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, null, "power menu")
            else -> unsupported(c)
        }
    }

    // ─── System UI and input ────────────────────────────────────────────────

    private fun a11y(): MinisAccessibilityService? = MinisAccessibilityService.getInstance()

    private fun globalAction(action: Int): Boolean = a11y()?.performGlobalAction(action) == true

    private fun navAction(c: Call, action: Int?, fallback: List<String>?, label: String): Out {
        if (action != null && globalAction(action)) return good("accessibility") { put("done", label) }
        if (fallback == null) return Out(false, "none", JSONObject(), "needs_user", "'$label' needs the accessibility service to be switched on.", "Settings → Accessibility → MinisX, or Shizuku/root for hands-free use.")
        val p = priv(c, fallback)
        if (p.ran && p.ok) return good(p.backend) { put("done", label) }
        return Out(false, "none", JSONObject(), "needs_user", "'$label' needs the accessibility service, Shizuku or root. ${p.reason}", ENABLE_HINT)
    }

    private fun nav(c: Call): Out = when (c.entry.key) {
        "nav home" -> navAction(c, AccessibilityService.GLOBAL_ACTION_HOME, ControlCommands.key("3"), "home")
        "nav back" -> navAction(c, AccessibilityService.GLOBAL_ACTION_BACK, ControlCommands.key("4"), "back")
        "nav recents" -> navAction(c, AccessibilityService.GLOBAL_ACTION_RECENTS, ControlCommands.key("187"), "recents")
        "nav notifications" -> navAction(c, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, ControlCommands.statusBar("expand-notifications"), "notification shade")
        "nav quick-settings" -> navAction(c, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, ControlCommands.statusBar("expand-settings"), "quick settings")
        "nav collapse" -> {
            val p = priv(c, ControlCommands.statusBar("collapse"))
            if (p.ran && p.ok) good(p.backend) { put("done", "collapse") } else navAction(c, AccessibilityService.GLOBAL_ACTION_BACK, null, "collapse")
        }
        "nav split-screen" -> navAction(c, AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, null, "split screen")
        "nav lock" -> navAction(c, if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else null, ControlCommands.sleep(), "lock screen")
        "nav screenshot" -> navAction(c, if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else null, ControlCommands.key("120"), "screenshot")
        "nav key" -> {
            val code = ControlCommands.keyCode(c.arg(0)) ?: return fail("bad_args", "nav key needs a key name or number, e.g. `nav key VOLUME_UP` or `nav key 24`.")
            privOnly(c, ControlCommands.key(code), ENABLE_HINT, null) { good(it) { put("key", code) } }
        }
        "nav tap" -> {
            val x = c.arg(0)?.toIntOrNull(); val y = c.arg(1)?.toIntOrNull()
            if (x == null || y == null || x < 0 || y < 0) fail("bad_args", "nav tap needs pixel coordinates: `nav tap 540 1200`.")
            else privOnly(c, ControlCommands.tap(x, y), ENABLE_HINT, null) { good(it) { put("x", x); put("y", y) } }
        }
        "nav swipe" -> {
            val n = (0..3).map { c.arg(it)?.toIntOrNull() }
            if (n.any { it == null || it < 0 }) fail("bad_args", "nav swipe needs x1 y1 x2 y2 in pixels.")
            else privOnly(c, ControlCommands.swipe(n[0]!!, n[1]!!, n[2]!!, n[3]!!, (c.args.getInt("ms") ?: 300).coerceIn(50, 5000)), ENABLE_HINT, null) { good(it) { put("swiped", true) } }
        }
        "nav text" -> {
            val t = c.p.joinToString(" ")
            if (t.isEmpty() || t.length > 500 || t.any { it.code > 126 || it.code < 32 }) fail("bad_args", "nav text types plain ASCII text (up to 500 characters) into the focused field; for other languages use `android-a11y-cli input text`.")
            else privOnly(c, ControlCommands.text(t), ENABLE_HINT, null) { good(it) { put("typed_chars", t.length) } }
        }
        else -> unsupported(c)
    }

    // ─── Apps ───────────────────────────────────────────────────────────────

    private class AppRef(val pkg: String, val label: String)

    private fun launchables(): List<AppRef> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { AppRef(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /** A package name as given, or the launchable app whose label matches (exact first, then contains). */
    private fun resolveApp(query: String?): AppRef? {
        val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val apps = launchables()
        apps.firstOrNull { it.pkg == q }?.let { return it }
        apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.label.contains(q, ignoreCase = true) }?.let { return it }
        if (ControlCommands.isPackage(q)) return runCatching { ctx.packageManager.getApplicationInfo(q, 0); AppRef(q, q) }.getOrNull()
        return null
    }

    private fun app(c: Call): Out {
        val query = c.p.joinToString(" ").trim().ifEmpty { null }
        if (c.entry.key == "app list") {
            val q = query?.lowercase()
            val max = (c.args.getInt("max") ?: 50).coerceIn(1, 300)
            val found = launchables().filter { q == null || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            return good("package-manager") {
                put("count", found.size)
                put("apps", JSONArray().also { arr -> found.take(max).forEach { arr.put(JSONObject().put("name", it.label).put("package", it.pkg)) } })
            }
        }
        if (c.entry.key == "app current") {
            a11y()?.foregroundPackage()?.first?.let { pkg -> return good("accessibility") { put("package", pkg) } }
            return privOnly(c, ControlCommands.foreground(), ENABLE_HINT, null, capture = true) { b ->
                good(b) { put("package", ControlCommands.parseForeground(lastPrivOutput)?.substringBefore('/') ?: JSONObject.NULL); put("activity", ControlCommands.parseForeground(lastPrivOutput) ?: JSONObject.NULL) }
            }
        }
        val target = resolveApp(query) ?: return fail("app_not_found", "No installed app matches '${query ?: ""}'. Run `android-control app list` to see names and packages.")
        val pkg = target.pkg
        return when (c.entry.key) {
            "app launch" -> {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return fail("not_launchable", "$pkg has no launcher activity.")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                ctx.startActivity(intent)
                val hint = if (!Settings.canDrawOverlays(ctx)) "If nothing opened, Android blocked a background launch; allow 'Display over other apps' for MinisX (`settings open overlay`)." else null
                good("package-manager") { put("launched", target.label); put("package", pkg); hint?.let { put("note", it) } }
            }
            "app info" -> {
                val pi = ctx.packageManager.getPackageInfo(pkg, 0)
                val ai = pi.applicationInfo
                good("package-manager") {
                    put("package", pkg); put("name", target.label); put("version", pi.versionName ?: ""); put("version_code", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
                    put("installed", Date(pi.firstInstallTime).toString()); put("updated", Date(pi.lastUpdateTime).toString())
                    if (ai != null) { put("enabled", ai.enabled); put("system_app", (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0); put("target_sdk", ai.targetSdkVersion) }
                }
            }
            "app settings" -> {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                if (startIntent(i)) good("intent") { put("opened", "app settings"); put("package", pkg) } else fail("open_failed", "Could not open the settings page.")
            }
            "app stop" -> privOnly(c, ControlCommands.forceStop(pkg), ENABLE_HINT, null) { good(it) { put("stopped", pkg) } }
            "app clear-data" -> {
                if (!c.yes) return fail("confirm_required", "This erases ALL data of $pkg (logins, saves). Ask the user first, then repeat with --yes.")
                privOnly(c, ControlCommands.clearData(pkg), ENABLE_HINT, null) { good(it) { put("cleared", pkg) } }
            }
            "app enable" -> privOnly(c, ControlCommands.enableApp(pkg), ENABLE_HINT, null) { good(it) { put("enabled", pkg) } }
            "app disable" -> privOnly(c, ControlCommands.disableApp(pkg), ENABLE_HINT, null) { good(it) { put("disabled", pkg) } }
            "app uninstall" -> {
                if (!ControlCommands.isPackage(pkg)) return fail("bad_args", "Not a package name: $pkg")
                if (pkg == ctx.packageName) return fail("refused", "MinisX will not uninstall itself.")
                if (startIntent(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))) good("intent") { put("asked_user_to_confirm", pkg) }
                else fail("open_failed", "Could not open the uninstall dialog.")
            }
            else -> unsupported(c)
        }
    }

    // ─── Phone, sharing, camera, wallpaper ──────────────────────────────────

    private fun communication(c: Call): Out = when (c.entry.key) {
        "phone dial" -> {
            val n = c.arg(0)
            if (!ControlCommands.isPhone(n)) fail("bad_args", "phone dial needs a phone number such as 01012345678 or +821012345678.")
            else if (startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))) good("intent") { put("dialer_opened_with", n) } else fail("open_failed", "No dialer app could be opened.")
        }
        "phone call" -> {
            val n = c.arg(0)
            if (!ControlCommands.isPhone(n)) fail("bad_args", "phone call needs a phone number.")
            else if (!c.yes) fail("confirm_required", "This places a real call to $n. Ask the user to confirm, then repeat with --yes.")
            else placeCall(c, n!!, null)
        }
        "phone find" -> {
            val q = c.p.joinToString(" ").trim()
            if (q.isEmpty()) fail("bad_args", "phone find needs a name.")
            else withContacts {
                val found = findContacts(q)
                if (found.isEmpty()) fail("not_found", "No contact matches '$q'.")
                else good("contacts") { put("matches", contactsJson(found)) }
            }
        }
        "phone call-contact" -> {
            val q = c.p.joinToString(" ").trim()
            if (q.isEmpty()) fail("bad_args", "phone call-contact needs a name.")
            else withContacts {
                when (val pick = ContactMatch.choose(q, findContacts(q), c.args.getInt("pick"))) {
                    ContactMatch.Pick.None -> fail("not_found", "No contact matches '$q'.")
                    is ContactMatch.Pick.Many -> Out(false, "contacts", JSONObject().put("candidates", contactsJson(pick.candidates)), "ambiguous",
                        "More than one match for '$q'. Ask the user which, then repeat with --pick N (1-based) or use `phone call <number>`.")
                    is ContactMatch.Pick.One ->
                        if (!c.yes) fail("confirm_required", "This calls ${pick.contact.name} (${pick.contact.number}). Ask the user to confirm, then repeat with --yes.")
                        else placeCall(c, pick.contact.number, pick.contact.name)
                }
            }
        }
        "phone answer" -> {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            val viaTelecom = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS) && runCatching { tm?.acceptRingingCall(); true }.getOrDefault(false)
            if (viaTelecom) good("telecom") { put("answered", true) }
            else privOnly(c, ControlCommands.answerCall(), ENABLE_HINT + " Or allow the Phone permission (and 'Allow restricted settings') for MinisX.", null) { good(it) { put("answered", true) } }
        }
        "phone hangup" -> {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            val viaTelecom = Build.VERSION.SDK_INT >= 28 && hasPermission(Manifest.permission.ANSWER_PHONE_CALLS) && runCatching { tm?.endCall() == true }.getOrDefault(false)
            if (viaTelecom) good("telecom") { put("ended", true) }
            else privOnly(c, ControlCommands.endCall(), ENABLE_HINT + " Or allow the Phone permission (and 'Allow restricted settings') for MinisX.", null) { good(it) { put("ended", true) } }
        }
        "phone sms-send" -> {
            val n = c.arg(0)
            val text = c.args.get("text")
            if (!ControlCommands.isPhone(n)) fail("bad_args", "phone sms-send needs a phone number.")
            else if (text.isNullOrBlank()) fail("bad_args", "phone sms-send needs --text \"<message>\".")
            else if (!c.yes) fail("confirm_required", "This sends \"$text\" to $n as the user. Ask the user to confirm the text, then repeat with --yes.")
            else sendSms(n!!, text, null)
        }
        "phone sms-contact" -> {
            val q = c.p.joinToString(" ").trim()
            val text = c.args.get("text")
            if (q.isEmpty()) fail("bad_args", "phone sms-contact needs a name.")
            else if (text.isNullOrBlank()) fail("bad_args", "phone sms-contact needs --text \"<message>\".")
            else withContacts {
                when (val pick = ContactMatch.choose(q, findContacts(q), c.args.getInt("pick"))) {
                    ContactMatch.Pick.None -> fail("not_found", "No contact matches '$q'.")
                    is ContactMatch.Pick.Many -> Out(false, "contacts", JSONObject().put("candidates", contactsJson(pick.candidates)), "ambiguous",
                        "More than one match for '$q'. Ask the user which, then repeat with --pick N (1-based).")
                    is ContactMatch.Pick.One ->
                        if (!c.yes) fail("confirm_required", "This sends \"$text\" to ${pick.contact.name} (${pick.contact.number}) as the user. Ask the user to confirm the text, then repeat with --yes.")
                        else sendSms(pick.contact.number, text, pick.contact.name)
                }
            }
        }
        "phone sms" -> {
            val n = c.arg(0)
            if (!ControlCommands.isPhone(n)) fail("bad_args", "phone sms needs a phone number.")
            else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).apply { c.args.get("text")?.let { putExtra("sms_body", it) } }
                if (startIntent(i)) good("intent") { put("message_composer_opened_for", n); put("sent", false) } else fail("open_failed", "No messaging app could be opened.")
            }
        }
        "phone email" -> {
            val to = c.arg(0)
            if (to == null || !to.contains('@')) fail("bad_args", "phone email needs an e-mail address.")
            else {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).apply {
                    c.args.get("subject")?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    c.args.get("text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
                if (startIntent(i)) good("intent") { put("composer_opened_for", to); put("sent", false) } else fail("open_failed", "No e-mail app could be opened.")
            }
        }
        "share text" -> {
            val t = c.p.joinToString(" ")
            if (t.isBlank()) fail("bad_args", "share text needs the text to share.")
            else {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                if (startIntent(Intent.createChooser(send, null))) good("intent") { put("share_sheet_opened", true) } else fail("open_failed", "Could not open the share sheet.")
            }
        }
        "camera photo" -> if (startIntent(Intent("android.media.action.STILL_IMAGE_CAMERA"))) good("intent") { put("opened", "camera") } else fail("open_failed", "No camera app could be opened.")
        "camera video" -> if (startIntent(Intent("android.media.action.VIDEO_CAMERA"))) good("intent") { put("opened", "video camera") } else fail("open_failed", "No camera app could be opened.")
        "wallpaper set" -> setWallpaper(c)
        else -> unsupported(c)
    }

    private val RESTRICTED_HINT =
        "On Android 13+ an app installed outside the Play Store cannot be granted Phone/SMS permissions until the user allows restricted settings: Settings > Apps > MinisX > (menu) > Allow restricted settings. Then allow the permission when asked."

    private fun withContacts(block: () -> Out): Out =
        if (ensureRuntime(Manifest.permission.READ_CONTACTS)) block()
        else fail("permission_missing", "Looking people up needs the Contacts permission.", "Ask the user to allow Contacts for MinisX.")

    private fun findContacts(query: String): List<ContactMatch.Contact> {
        val out = ArrayList<ContactMatch.Contact>()
        val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        ctx.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cur ->
            while (cur.moveToNext() && out.size < 200) out += ContactMatch.Contact(cur.getString(0) ?: "", cur.getString(1) ?: "")
        }
        // A LIKE with the exact characters misses "Mom" for "mom"-style spacing differences; rank decides, so keep all.
        return out
    }

    private fun contactsJson(list: List<ContactMatch.Contact>): JSONArray =
        JSONArray().also { arr -> list.forEachIndexed { i, ct -> arr.put(JSONObject().put("pick", i + 1).put("name", ct.name).put("number", ct.number)) } }

    private fun placeCall(c: Call, number: String, name: String?): Out {
        val clean = number.filter { it.isDigit() || it in "+*#,;" }
        if (!ControlCommands.isPhone(clean)) return fail("bad_args", "'$number' is not a dialable number.")
        if (ensureRuntime(Manifest.permission.CALL_PHONE)) {
            if (startIntent(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(clean))))) {
                return good("intent") { put("calling", clean); name?.let { put("name", it) } }
            }
        }
        val p = priv(c, ControlCommands.call(clean))
        if (p.ran && p.ok) return good(p.backend) { put("calling", clean); name?.let { put("name", it) } }
        // Last resort: the dialer with the number filled in, for the user to press call.
        val dialed = startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(clean))))
        return Out(false, "intent", JSONObject().put("dialer_opened", dialed), "needs_user",
            "Calling directly is not allowed yet, so the dialer is open with $clean filled in for the user to press call. $RESTRICTED_HINT", ENABLE_HINT)
    }

    private fun sendSms(number: String, text: String, name: String?): Out {
        val clean = number.filter { it.isDigit() || it in "+*#" }
        if (!ControlCommands.isPhone(clean)) return fail("bad_args", "'$number' is not a phone number.")
        if (text.length > 1000) return fail("bad_args", "The text is longer than 1000 characters.")
        if (!ensureRuntime(Manifest.permission.SEND_SMS)) {
            val composed = startIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(clean))).putExtra("sms_body", text))
            return Out(false, "intent", JSONObject().put("composer_opened", composed).put("sent", false), "needs_user",
                "Sending texts directly needs the SMS permission, so the message app is open with the text filled in for the user to press send. $RESTRICTED_HINT", ENABLE_HINT)
        }
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
        else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        if (parts.size > 1) sm.sendMultipartTextMessage(clean, null, parts, null, null) else sm.sendTextMessage(clean, null, text, null, null)
        return good("sms-manager") { put("sent_to", clean); name?.let { put("name", it) }; put("parts", parts.size) }
    }

    private fun setWallpaper(c: Call): Out {
        val path = c.arg(0) ?: return fail("bad_args", "wallpaper set needs an image path.")
        val file = runCatching { PRootKernel.resolveHostPath(if (path.startsWith("/")) path else "${c.request.cwd.trimEnd('/')}/$path") }.getOrNull()
            ?: File(path)
        if (!file.isFile) return fail("file_not_found", "No image at $path.")
        val bmp = BitmapFactory.decodeFile(file.path) ?: return fail("bad_image", "$path is not an image Android can decode.")
        val which = when (c.args.get("target")?.lowercase()) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, which)
        bmp.recycle()
        return good("wallpaper-manager") { put("set", path) }
    }

    // ─── Settings pages ─────────────────────────────────────────────────────

    private fun settingsPages(c: Call): Out = when (c.entry.key) {
        "settings pages" -> good("none") { put("pages", JSONArray(ControlCommands.SETTINGS_PAGES.keys.toList())) }
        "settings open" -> {
            val page = c.arg(0)?.lowercase()
            val action = ControlCommands.SETTINGS_PAGES[page]
            if (action == null) fail("bad_args", "Unknown page '${page ?: ""}'. Pages: ${ControlCommands.SETTINGS_PAGES.keys.joinToString(", ")}")
            else {
                val i = Intent(action).apply { if (page == "write-settings" || page == "overlay") data = Uri.parse("package:${ctx.packageName}") }
                if (startIntent(i)) good("intent") { put("opened", page) }
                else if (startIntent(Intent(Settings.ACTION_SETTINGS))) good("intent") { put("opened", "main settings"); put("note", "The page '$page' does not exist on this phone, so the main settings opened.") }
                else fail("open_failed", "Could not open settings.")
            }
        }
        else -> unsupported(c)
    }

    // ─── Sensors and system information ─────────────────────────────────────

    private fun system(c: Call): Out = when (c.entry.key) {
        "sensor list" -> {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val all = sm.getSensorList(Sensor.TYPE_ALL)
            good("sensor-manager") { put("count", all.size); put("sensors", JSONArray().also { arr -> all.take(80).forEach { arr.put(JSONObject().put("name", it.name).put("type", it.stringType ?: it.type.toString()).put("vendor", it.vendor)) } }) }
        }
        "sensor read" -> readSensor(c)
        "info memory" -> {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            good("activity-manager") { put("total_mb", mi.totalMem / 1048576); put("available_mb", mi.availMem / 1048576); put("low_memory", mi.lowMemory); put("threshold_mb", mi.threshold / 1048576) }
        }
        "info thermal" -> {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            good("power-manager") {
                if (Build.VERSION.SDK_INT >= 29) put("thermal_status", listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown").getOrElse(pm.currentThermalStatus) { "unknown" })
                bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { put("battery_celsius", it / 10.0) }
            }
        }
        "info uptime" -> good("system") { put("uptime_minutes", SystemClock.elapsedRealtime() / 60000); put("awake_minutes", SystemClock.uptimeMillis() / 60000) }
        "info locale" -> good("system") {
            put("language", Locale.getDefault().toLanguageTag()); put("time_zone", TimeZone.getDefault().id)
            put("hour_24", android.text.format.DateFormat.is24HourFormat(ctx)); put("now", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        }
        "info sim" -> {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            good("telephony") {
                put("sim_state", listOf("unknown", "absent", "pin_required", "puk_required", "network_locked", "ready", "not_ready", "perm_disabled", "card_io_error", "card_restricted").getOrElse(tm?.simState ?: 0) { "unknown" })
                put("sim_operator", tm?.simOperatorName ?: ""); put("network_operator", tm?.networkOperatorName ?: ""); put("country", tm?.networkCountryIso ?: "")
            }
        }
        "info access" -> accessReport()
        "timezone get" -> good("system") { put("time_zone", TimeZone.getDefault().id) }
        "timezone set" -> {
            val z = c.arg(0)
            if (!ControlCommands.isTimeZone(z)) fail("bad_args", "timezone set needs an id like Asia/Seoul.")
            else privOnly(c, ControlCommands.setTimeZone(z!!), ENABLE_HINT, "android.settings.DATE_SETTINGS") { good(it) { put("time_zone", z) } }
        }
        "time auto" -> {
            val on = onOff(c.arg(0))
            if (on == null) fail("bad_args", "time auto needs on or off.")
            else privOnly(c, ControlCommands.autoTime(on), ENABLE_HINT, "android.settings.DATE_SETTINGS") { good(it) { put("auto_time", Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 0) == 1) } }
        }
        else -> unsupported(c)
    }

    private fun readSensor(c: Call): Out {
        val name = c.arg(0)?.lowercase()
        val type = ControlCommands.SENSORS[name] ?: return fail("bad_args", "Unknown sensor '${name ?: ""}'. Use ${ControlCommands.SENSORS.keys.joinToString(", ")}.")
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(type) ?: return fail("unsupported", "This phone has no $name sensor.")
        if (type == Sensor.TYPE_STEP_COUNTER && Build.VERSION.SDK_INT >= 29 && !ensureRuntime(Manifest.permission.ACTIVITY_RECOGNITION)) return fail("permission_missing", "Step counting needs the Physical activity permission.")
        val latch = CountDownLatch(1)
        var values: FloatArray? = null
        val thread = HandlerThread("control-sensor").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values = e.values.clone(); latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
            latch.await(3, TimeUnit.SECONDS)
        } finally {
            sm.unregisterListener(listener)
            thread.quitSafely()
        }
        val v = values ?: return fail("no_reading", "The $name sensor gave no reading in 3 seconds.")
        return good("sensor-manager") { put("sensor", name); put("name", sensor.name); put("values", JSONArray(v.map { it.toDouble() })) }
    }

    private fun accessReport(): Out {
        ShizukuManager.refresh()
        val shizuku = ShizukuManager.snapshot.value.state
        val shizukuLevel = OffloadPermissionManager.getLevel("shizuku_cli")
        val rootLevel = OffloadPermissionManager.getLevel("root_cli")
        val suPresent = RootShell.suPresent()
        val privileged = (shizuku == ShizukuManager.State.READY && shizukuLevel != OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) ||
            (suPresent && rootLevel != OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        return good("none") {
            put("direct_api", "flashlight, volume, vibration, apps, dialer, sensors, settings pages")
            put("accessibility_service", a11y() != null)
            put("shizuku", JSONObject().put("state", shizuku.name).put("agent_policy", shizukuLevel.name.lowercase()))
            put("root", JSONObject().put("su_found", suPresent).put("agent_policy", rootLevel.name.lowercase()))
            put("write_settings", Settings.System.canWrite(ctx))
            put("do_not_disturb_access", dndAccessGranted())
            put("display_over_other_apps", Settings.canDrawOverlays(ctx))
            put("hands_free_toggles", privileged)
            put("advice", if (privileged) "Wi-Fi, Bluetooth, mobile data, airplane mode, location, NFC and similar switches can be changed hands-free."
            else "Without Shizuku (policy not 'Not allowed') or root, Wi-Fi/Bluetooth/data/airplane/NFC/location switches cannot be flipped by an app on Android 10+; the control opens the right settings panel instead. $ENABLE_HINT")
        }
    }

    // ─── Privileged execution ───────────────────────────────────────────────

    private class Priv(val ran: Boolean, val ok: Boolean, val backend: String, val output: String, val reason: String)

    @Volatile private var lastPrivOutput: String = ""

    /**
     * Runs the first of [cmds] that exits 0: through Shizuku when it is ready and the user's policy for
     * `android-shizuku-cli` is not "Not allowed" (an "Ask" policy asks), else through root, command by
     * command with the in-app approval. [Priv.ran] is false when neither could even be tried; [Priv.reason]
     * then says why, for the reply.
     */
    private fun priv(c: Call, cmds: List<String>): Priv {
        val why = mutableListOf<String>()
        var attempted = false
        var lastOut = ""
        ShizukuManager.refresh()
        val st = ShizukuManager.snapshot.value.state
        if (st == ShizukuManager.State.READY) {
            val level = OffloadPermissionManager.getLevel("shizuku_cli")
            if (level == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) {
                why += "Shizuku is running, but Settings → Permissions has android-shizuku-cli on 'Not allowed'; set it to Ask or Allow for hands-free control."
            } else if (!OffloadGate.allow("shizuku_cli", "android-shizuku-cli", c.request)) {
                why += "The user declined the Shizuku request."
            } else {
                for (cmd in cmds) {
                    attempted = true
                    val r = ShizukuManager.runProcess(arrayOf("sh", "-c", cmd), timeoutMs = 15_000L)
                    lastOut = r.combined
                    if (r.exitCode == 0) { lastPrivOutput = r.stdout; return Priv(true, true, "shizuku", r.stdout, "") }
                }
            }
        } else {
            why += when (st) {
                ShizukuManager.State.NOT_INSTALLED -> "Shizuku is not installed."
                ShizukuManager.State.NOT_RUNNING -> "Shizuku is not running."
                else -> "MinisX is not authorized in Shizuku."
            }
        }
        if (RootShell.suPresent()) {
            val level = OffloadPermissionManager.getLevel("root_cli")
            if (level == OffloadPermissionManager.PermissionLevel.NOT_ALLOWED) {
                why += "Root is set to 'Not allowed' in Settings → Permissions."
            } else {
                val sessionId = c.request.sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
                for (cmd in cmds) {
                    val allowed = runBlocking {
                        OffloadPermissionManager.checkPermission(
                            "root_cli", "android-root-cli", sessionId,
                            detail = cmd,
                            explanation = ControlCommands.approvalReason(c.entry.key, Locale.getDefault().language == "ko"),
                            risks = RootCommandRisk.assess(cmd),
                        )
                    }
                    if (!allowed) { why += "The user did not approve the root command."; break }
                    attempted = true
                    val r = RootShell.run(cmd, 20)
                    lastOut = r.output
                    if (r.exitCode == 0) { lastPrivOutput = r.output; return Priv(true, true, "root", r.output, "") }
                }
            }
        } else if (st != ShizukuManager.State.READY) {
            why += "No root (su) found."
        }
        lastPrivOutput = lastOut
        return Priv(attempted, false, "none", lastOut, why.joinToString(" "))
    }

    /** A control that has no non-privileged path: run [cmds], then [done] builds the reply. */
    private fun privOnly(c: Call, cmds: List<String>, hint: String, panel: String?, capture: Boolean = false, done: (String) -> Out): Out {
        val p = priv(c, cmds)
        if (p.ran && p.ok) {
            Thread.sleep(if (capture) 0 else 400)
            return done(p.backend)
        }
        if (p.ran) return Out(false, "none", JSONObject(), "command_failed", p.output.ifBlank { "the command failed" }, hint)
        return needsUser(panel, "This needs privilege that is not available. ${p.reason}")
    }

    // ─── Small helpers ──────────────────────────────────────────────────────

    private fun needsUser(panel: String?, message: String): Out {
        val opened = panel != null && startIntent(Intent(panel))
        return Out(
            false, "settings", JSONObject().put("settings_opened", opened), "needs_user",
            message + if (opened) " I opened the matching settings screen; the user has to flip it." else "",
            ENABLE_HINT,
        )
    }

    private fun startIntent(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        AppLogger.warning(TAG, "startActivity failed: ${e.message}")
        false
    }

    private fun good(backend: String, build: JSONObject.() -> Unit = {}): Out = Out(true, backend, JSONObject().apply(build))

    private fun fail(code: String, message: String, hint: String? = null): Out = Out(false, "none", JSONObject(), code, message, hint)

    private fun unsupported(c: Call): Out = fail("unsupported", "${c.entry.key} is in the catalog but this build has no handler for it.")

    private fun onOff(s: String?): Boolean? = when (s?.lowercase()) { "on", "true", "1", "yes", "enable" -> true; "off", "false", "0", "no", "disable" -> false; else -> null }

    private fun ko(en: String, korean: String): String = if (Locale.getDefault().language == "ko") korean else en

    /** Polls [probe] until [done] says so or [timeoutMs] passes; returns the last reading. */
    private fun <T> settle(probe: () -> T?, timeoutMs: Long = 2500, done: (T?) -> Boolean): T? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var v = probe()
        while (!done(v) && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(150)
            v = probe()
        }
        return v
    }

    private fun help(): String =
        "android-control - control this phone: flashlight, volume, screen, Wi-Fi, Bluetooth, apps, calls and more.\n\n" +
            "  android-control <group> <action> [args]\n" +
            "  android-control list [group]        every action with its arguments\n" +
            "  android-control info access         which control paths are open right now\n\n" +
            "Groups and actions:\n" + ControlCatalog.overview() + "\n\n" +
            "Replies are JSON: {ok, action, backend, data | error{code,message}, hint}. After a change the state is read back.\n" +
            "Error code needs_user means a switch only the user can flip (the matching settings screen was opened).\n"

    companion object {
        private const val TAG = "ControlOffload"
        private const val DEVELOPER_PANEL = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
        const val ENABLE_HINT = "For hands-free control: Settings → Permissions → Shizuku (and set android-shizuku-cli to Ask or Allow), or grant root."
    }
}
