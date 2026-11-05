package com.minisx.app.sandbox.offload

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.minisx.app.offload.OffloadPermissionManager
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * android-bluetooth — Bluetooth Low Energy: scan, connect, discover, read,
 * write and subscribe to notifications.
 *
 * Android counterpart of iOS `apple-bluetooth` (BluetoothOffload.m): the same
 * subcommands, option names and envelope. A connection outlives the command
 * that opened it — `connect`, then `services`, `read` and `write` reuse it — and
 * drops itself after five idle minutes, as on iOS. A command that names a
 * `--uuid` which is not the connected device reconnects first.
 *
 * `--uuid` is the device's MAC address here (Android does not hand out the
 * per-install UUIDs CoreBluetooth does); `scan` prints it in the `uuid` field
 * so the same scan → connect → read flow works unchanged.
 *
 * One GATT operation is in flight at a time (an Android requirement), which is
 * why every operation below runs under [lock].
 */
class BluetoothOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("bluetooth", "Bluetooth", args, request)?.let { return it }

        return synchronized(lock) {
            try {
                dispatch(sub, args)
            } catch (e: SecurityException) {
                OffloadEnvelope.error(TOOL, sub, "permission_denied", "Bluetooth permission missing: ${e.message}", args, 126)
            } catch (e: Throwable) {
                OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun dispatch(sub: String, args: OffloadArgs): NativeOffloadResult {
        val valid = setOf("status", "scan", "connect", "disconnect", "services", "read", "write", "notify")
        if (sub !in valid) {
            return OffloadEnvelope.error(
                TOOL, sub, "unknown_command",
                "Unknown command '$sub'. Valid commands: ${valid.joinToString()}. Use --help for details.", args, 2,
            )
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return OffloadEnvelope.error(TOOL, sub, "not_available", "This device has no Bluetooth adapter.", args, 3)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return OffloadEnvelope.error(TOOL, sub, "not_available", "This device does not support Bluetooth Low Energy.", args, 3)
        }

        if (sub == "status") return status(adapter, args)
        if (sub == "disconnect") return disconnect(args)

        ensurePermissions(sub, args)?.let { return it }
        if (!adapter.isEnabled) {
            return OffloadEnvelope.error(TOOL, sub, "not_available", "Bluetooth is off. Ask the user to turn it on in Settings → Bluetooth.", args, 3)
        }
        return when (sub) {
            "scan" -> scan(adapter, args)
            "connect" -> connect(adapter, args)
            "services" -> services(adapter, args)
            "read" -> read(adapter, args)
            "write" -> write(adapter, args)
            else -> notify(adapter, args)
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION) // BLE scan results need location before Android 12

    private fun missing(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions(action: String, args: OffloadArgs): NativeOffloadResult? {
        if (missing().isEmpty()) return null
        val r = runBlocking { OffloadPermissionManager.requestAndroidPermission(requiredPermissions()) }
        if (r == OffloadPermissionManager.AndroidPermissionResult.TIMEOUT) {
            return OffloadEnvelope.error(TOOL, action, "timeout", "The Bluetooth permission dialog was not answered in time. Open the chat screen and retry.", args)
        }
        val still = missing()
        if (still.isEmpty()) return null
        return OffloadEnvelope.error(
            TOOL, action, "permission_denied",
            "Bluetooth permission denied (${still.joinToString { it.substringAfterLast('.') }}). " +
                "Ask the user to allow Nearby devices for Minis in Settings → Apps → Minis → Permissions.",
            args, 126,
        )
    }

    // ── Commands ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun status(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val granted = missing().isEmpty()
        val state = when {
            !granted -> "unauthorized"
            adapter.isEnabled -> "powered_on"
            else -> "powered_off"
        }
        val g = gatt
        val connected = g != null && connectedAddress != null
        val data = JSONObject()
            .put("state", state)
            .put("is_scanning", scanning.get())
            .put("has_connected_peripheral", connected)
        if (connected) {
            data.put("connected_uuid", connectedAddress)
            data.put("connected_name", runCatching { g!!.device.name }.getOrNull() ?: JSONObject.NULL)
        }
        data.put("hint", when {
            state == "powered_on" && connected -> "Peripheral connected. Use 'android-bluetooth services' to discover services, then 'read' or 'write' characteristics."
            state == "powered_on" -> "Bluetooth is ready. Use 'android-bluetooth scan' to discover nearby BLE peripherals."
            state == "powered_off" -> "Bluetooth is off. Ask the user to enable it in Settings → Bluetooth."
            else -> "Bluetooth permission not granted yet; any other command will ask for it."
        })
        return OffloadEnvelope.ok(TOOL, "status", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun scan(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val scanner = adapter.bluetoothLeScanner
            ?: return OffloadEnvelope.error(TOOL, "scan", "not_available", "Bluetooth is not powered on.", args, 3)
        var duration = args.getDouble("duration") ?: 5.0
        if (duration <= 0 || duration > 60) duration = 5.0
        val filters = args.get("service")?.let { s ->
            val u = parseUuid(s) ?: return OffloadEnvelope.error(TOOL, "scan", "invalid_arguments", "Invalid --service UUID: $s", args, 2)
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(u)).build())
        }

        val found = LinkedHashMap<String, JSONObject>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(found) { found[result.device.address] = describe(result) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                synchronized(found) { results.forEach { found[it.device.address] = describe(it) } }
            }
        }
        scanning.set(true)
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            Thread.sleep((duration * 1000).toLong())
        } finally {
            runCatching { scanner.stopScan(cb) }
            scanning.set(false)
        }
        val list = synchronized(found) { found.values.sortedByDescending { it.optInt("rssi", -127) } }
        val data = JSONObject()
            .put("count", list.size)
            .put("duration_seconds", duration)
            .put("peripherals", JSONArray(list))
            .put("hint", if (list.isNotEmpty())
                "Use 'android-bluetooth connect --uuid <peripheral-uuid>' to connect to a device, then 'services' to discover its capabilities."
            else "No devices found. Try 'android-bluetooth scan --duration 15' for a longer scan, or ensure the target device is advertising.")
        return OffloadEnvelope.ok(TOOL, "scan", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun connect(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val addr = args.get("uuid")
            ?: return OffloadEnvelope.error(TOOL, "connect", "invalid_arguments", "--uuid is required (from `scan`).", args, 2)
        val err = ensureConnected(adapter, addr)
        if (err != null) return OffloadEnvelope.error(TOOL, "connect", "no_data", err, args)
        val data = JSONObject()
            .put("uuid", connectedAddress)
            .put("name", runCatching { gatt?.device?.name }.getOrNull() ?: JSONObject.NULL)
            .put("connected", true)
            .put("hint", "Use 'android-bluetooth services --uuid $addr' to discover services. The connection closes after 5 minutes idle.")
        return OffloadEnvelope.ok(TOOL, "connect", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun disconnect(args: OffloadArgs): NativeOffloadResult {
        val was = connectedAddress
        closeGatt()
        val data = JSONObject().put("disconnected", was != null).apply { was?.let { put("uuid", it) } }
        return OffloadEnvelope.ok(TOOL, "disconnect", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun services(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val addr = args.get("uuid")
            ?: return OffloadEnvelope.error(TOOL, "services", "invalid_arguments", "--uuid is required.", args, 2)
        ensureConnected(adapter, addr)?.let { return OffloadEnvelope.error(TOOL, "services", "no_data", it, args) }
        ensureServices()?.let { return OffloadEnvelope.error(TOOL, "services", "internal", it, args) }
        val g = gatt!!
        val arr = JSONArray()
        for (s in g.services) {
            val chars = JSONArray()
            for (c in s.characteristics) {
                chars.put(JSONObject().put("uuid", shortUuid(c.uuid)).put("properties", propertyNames(c.properties)))
            }
            arr.put(JSONObject().put("uuid", shortUuid(s.uuid)).put("characteristics", chars))
        }
        val data = JSONObject().put("uuid", addr).put("name", runCatching { g.device.name }.getOrNull() ?: JSONObject.NULL)
            .put("services", arr)
            .put("hint", "Use 'android-bluetooth read --uuid <uuid> --service <service-uuid> --characteristic <char-uuid>' to read a value, or 'write' to send one.")
        touch()
        return OffloadEnvelope.ok(TOOL, "services", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun read(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val (c, err) = resolveCharacteristic("read", adapter, args)
        if (c == null) return err!!
        val ev = op { gatt!!.readCharacteristic(c) }
            ?: return OffloadEnvelope.error(TOOL, "read", "internal", "Could not start the read (is the characteristic readable?).", args)
        if (ev.status != BluetoothGatt.GATT_SUCCESS) {
            return OffloadEnvelope.error(TOOL, "read", "internal", "Read failed (GATT status ${ev.status}).", args)
        }
        val bytes = ev.value ?: ByteArray(0)
        val data = JSONObject()
            .put("service", args.get("service")).put("characteristic", args.get("characteristic"))
            .put("hex", hex(bytes)).put("bytes", bytes.size).put("utf8", utf8OrNull(bytes) ?: JSONObject.NULL)
        touch()
        return OffloadEnvelope.ok(TOOL, "read", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun write(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        val hexValue = args.get("value")
        val strValue = args.get("value-string")
        val payload: ByteArray = when {
            hexValue != null -> parseHex(hexValue)
                ?: return OffloadEnvelope.error(TOOL, "write", "invalid_arguments", "--value must be hex (e.g. 0100).", args, 2)
            strValue != null -> strValue.toByteArray(Charsets.UTF_8)
            else -> return OffloadEnvelope.error(TOOL, "write", "invalid_arguments", "--value <hex> or --value-string <text> is required.", args, 2)
        }
        val (c, err) = resolveCharacteristic("write", adapter, args)
        if (c == null) return err!!
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (withResponse) {
            op { writeCharacteristic(gatt!!, c, payload, type) }
        } else {
            val ok = writeCharacteristic(gatt!!, c, payload, type)
            if (ok) { Thread.sleep(100); GattEvent("write", BluetoothGatt.GATT_SUCCESS, null) } else null
        }
        if (started == null) return OffloadEnvelope.error(TOOL, "write", "internal", "Could not start the write.", args)
        if (started.status != BluetoothGatt.GATT_SUCCESS) {
            return OffloadEnvelope.error(TOOL, "write", "internal", "Write failed (GATT status ${started.status}).", args)
        }
        val data = JSONObject()
            .put("service", args.get("service")).put("characteristic", args.get("characteristic"))
            .put("bytes_written", payload.size)
            .put("write_type", if (withResponse) "with_response" else "without_response")
        touch()
        return OffloadEnvelope.ok(TOOL, "write", data, args)
    }

    @SuppressLint("MissingPermission")
    private fun notify(adapter: BluetoothAdapter, args: OffloadArgs): NativeOffloadResult {
        if (args.get("uuid") == null || args.get("service") == null || args.get("characteristic") == null) {
            return OffloadEnvelope.error(TOOL, "notify", "invalid_arguments", "--uuid, --service and --characteristic are required.", args, 2)
        }
        // iOS: default 10 s; 0 = until Ctrl-C. An offload call cannot be cancelled
        // from the shell on Android, so 0 is capped at MAX_NOTIFY_SECONDS.
        val raw = args.get("duration")
        val requested = raw?.toDoubleOrNull()
        val capped = requested == 0.0
        val duration = when {
            capped -> MAX_NOTIFY_SECONDS.toDouble()
            requested != null && requested > 0 -> requested.coerceAtMost(MAX_NOTIFY_SECONDS.toDouble())
            else -> 10.0
        }
        val (c, err) = resolveCharacteristic("notify", adapter, args)
        if (c == null) return err!!
        val canNotify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!canNotify && !canIndicate) {
            return OffloadEnvelope.error(TOOL, "notify", "invalid_arguments",
                "Characteristic does not support notify or indicate. Inspect its 'properties' list via 'android-bluetooth services'.", args, 2)
        }
        val g = gatt!!
        val cccd = c.getDescriptor(CCCD)
            ?: return OffloadEnvelope.error(TOOL, "notify", "internal", "Characteristic has no client configuration descriptor.", args)

        val samples = JSONArray()
        val started = System.currentTimeMillis()
        notifyTarget = c.uuid
        notifySink = { value ->
            synchronized(samples) {
                samples.put(JSONObject()
                    .put("timestamp", OffloadDates.format(java.time.Instant.now()))
                    .put("value_hex", hex(value))
                    .put("value_base64", Base64.getEncoder().encodeToString(value))
                    .put("value_string", utf8OrNull(value) ?: JSONObject.NULL))
            }
        }
        try {
            g.setCharacteristicNotification(c, true)
            val enable = if (canNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val ev = op { writeDescriptor(g, cccd, enable) }
                ?: return OffloadEnvelope.error(TOOL, "notify", "internal", "Timed out waiting for subscription to activate.", args)
            if (ev.status != BluetoothGatt.GATT_SUCCESS) {
                return OffloadEnvelope.error(TOOL, "notify", "internal", "Subscription failed (GATT status ${ev.status}).", args)
            }
            Thread.sleep((duration * 1000).toLong())
        } finally {
            notifySink = null
            notifyTarget = null
            runCatching {
                g.setCharacteristicNotification(c, false)
                op(timeoutMs = 2_000) { writeDescriptor(g, cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }
            }
        }
        touch()
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        val n = samples.length()
        val data = JSONObject()
            .put("uuid", args.get("uuid")).put("service", args.get("service")).put("characteristic", args.get("characteristic"))
            .put("duration_seconds", elapsed).put("count", n).put("samples", samples)
            .put("stopped_by", "duration")
            .put("hint", if (capped) "--duration 0 is capped at ${MAX_NOTIFY_SECONDS}s on Android; call again to keep listening."
                else if (n > 0) "Subscription closed. Re-run with a longer --duration to capture more samples."
                else "No samples received during the subscription window. Confirm the peripheral is producing notifications on this characteristic.")
        return OffloadEnvelope.ok(TOOL, "notify", data, args)
    }

    // ── Connection management ───────────────────────────────────────────────

    private class GattEvent(val kind: String, val status: Int, val value: ByteArray?)

    private val events = LinkedBlockingQueue<GattEvent>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                events.offer(GattEvent("connected", status, null))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                events.offer(GattEvent("disconnected", status, null))
                // Dropped by the peripheral: forget the stale handle.
                if (g === gatt) { connectedAddress = null; servicesReady = false }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            events.offer(GattEvent("services", status, null))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            events.offer(GattEvent("read", status, c.value))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.offer(GattEvent("read", status, value))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            events.offer(GattEvent("write", status, null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            events.offer(GattEvent("descriptor", status, null))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliverNotify(c.uuid, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliverNotify(c.uuid, value)
        }
    }

    private fun deliverNotify(uuid: UUID, value: ByteArray?) {
        if (value != null && uuid == notifyTarget) notifySink?.invoke(value)
    }

    /** Null on success, otherwise a message. Reconnects when [address] is not the live connection. */
    @SuppressLint("MissingPermission")
    private fun ensureConnected(adapter: BluetoothAdapter, address: String): String? {
        val addr = address.uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) {
            return "'$address' is not a Bluetooth address. Use the `uuid` value printed by `scan`."
        }
        if (gatt != null && connectedAddress.equals(addr, ignoreCase = true)) { touch(); return null }
        closeGatt()
        val device: BluetoothDevice = adapter.getRemoteDevice(addr)
        events.clear()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: return "Failed to start the connection."
        gatt = g
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) { closeGatt(); return "Timed out connecting to $addr. Make sure it is advertising and in range." }
            val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (ev.kind == "connected") break
            if (ev.kind == "disconnected") { closeGatt(); return "Connection to $addr failed (status ${ev.status})." }
        }
        connectedAddress = addr
        servicesReady = false
        touch()
        return null
    }

    @SuppressLint("MissingPermission")
    private fun ensureServices(): String? {
        if (servicesReady) return null
        val g = gatt ?: return "Not connected."
        events.clear()
        if (!g.discoverServices()) return "Could not start service discovery."
        val ev = waitFor("services", 15_000) ?: return "Timed out discovering services."
        if (ev.status != BluetoothGatt.GATT_SUCCESS) return "Service discovery failed (status ${ev.status})."
        servicesReady = true
        return null
    }

    private fun resolveCharacteristic(
        action: String,
        adapter: BluetoothAdapter,
        args: OffloadArgs,
    ): Pair<BluetoothGattCharacteristic?, NativeOffloadResult?> {
        val addr = args.get("uuid")
            ?: return null to OffloadEnvelope.error(TOOL, action, "invalid_arguments", "--uuid is required.", args, 2)
        val serviceStr = args.get("service")
        val charStr = args.get("characteristic")
        if (serviceStr == null || charStr == null) {
            return null to OffloadEnvelope.error(TOOL, action, "invalid_arguments", "--service and --characteristic are required.", args, 2)
        }
        val su = parseUuid(serviceStr)
        val cu = parseUuid(charStr)
        if (su == null || cu == null) {
            return null to OffloadEnvelope.error(TOOL, action, "invalid_arguments", "Invalid --service or --characteristic UUID.", args, 2)
        }
        ensureConnected(adapter, addr)?.let { return null to OffloadEnvelope.error(TOOL, action, "no_data", it, args) }
        ensureServices()?.let { return null to OffloadEnvelope.error(TOOL, action, "internal", it, args) }
        val c = gatt!!.getService(su)?.getCharacteristic(cu)
            ?: return null to OffloadEnvelope.error(
                TOOL, action, "no_data",
                "Characteristic not found. Run 'android-bluetooth services --uuid <uuid>' first to discover available characteristics.", args,
            )
        return c to null
    }

    /** Runs a GATT operation and waits for its callback; null if it could not start or timed out. */
    private fun op(timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Boolean): GattEvent? {
        events.clear()
        if (!start()) return null
        // A pending "disconnected" must not be mistaken for the op's own answer.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (ev.kind == "connected") continue
            return ev
        }
    }

    private fun waitFor(kind: String, timeoutMs: Long): GattEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (ev.kind == kind) return ev
            if (ev.kind == "disconnected") return null
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        idleTask?.cancel(false); idleTask = null
        val g = gatt
        gatt = null
        connectedAddress = null
        servicesReady = false
        notifySink = null
        if (g != null) runCatching { g.disconnect(); g.close() }
        events.clear()
    }

    private fun touch() {
        idleTask?.cancel(false)
        idleTask = idle.schedule({ synchronized(lock) { closeGatt() } }, IDLE_DISCONNECT_MS, TimeUnit.MILLISECONDS)
    }

    // ── Formatting ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun describe(r: ScanResult): JSONObject {
        val rec = r.scanRecord
        val o = JSONObject()
            .put("uuid", r.device.address)
            .put("name", (rec?.deviceName ?: runCatching { r.device.name }.getOrNull()) ?: JSONObject.NULL)
            .put("rssi", r.rssi)
            .put("connectable", if (Build.VERSION.SDK_INT >= 26) r.isConnectable else true)
        rec?.serviceUuids?.let { l -> o.put("service_uuids", JSONArray(l.map { shortUuid(it.uuid) })) }
        rec?.manufacturerSpecificData?.let { m ->
            if (m.size() > 0) {
                val id = m.keyAt(0)
                o.put("manufacturer_data", "%04x".format(id) + ":" + hex(m.valueAt(0)))
            }
        }
        rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { o.put("tx_power", it) }
        return o
    }

    private fun propertyNames(p: Int): JSONArray {
        val a = JSONArray()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) a.put("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) a.put("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) a.put("write_without_response")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) a.put("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) a.put("indicate")
        return a
    }

    companion object {
        private const val TOOL = "android-bluetooth"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val OP_TIMEOUT_MS = 10_000L
        private const val IDLE_DISCONNECT_MS = 300_000L
        private const val MAX_NOTIFY_SECONDS = 300

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        // The connection is process-wide: it must survive between shell commands.
        private val lock = Any()
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var connectedAddress: String? = null
        @Volatile private var servicesReady = false
        @Volatile private var notifyTarget: UUID? = null
        @Volatile private var notifySink: ((ByteArray) -> Unit)? = null
        private val scanning = AtomicBoolean(false)
        private var idleTask: java.util.concurrent.ScheduledFuture<*>? = null
        private val idle = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "ble-idle").apply { isDaemon = true } }

        /** `180D`, `0000180d-…` or a full 128-bit UUID. */
        internal fun parseUuid(s: String): UUID? {
            val t = s.trim()
            return runCatching {
                when (t.length) {
                    4 -> UUID.fromString("0000$t$BASE_SUFFIX")
                    8 -> UUID.fromString("$t$BASE_SUFFIX")
                    else -> UUID.fromString(t)
                }
            }.getOrNull()
        }

        /** 16-bit form for Bluetooth-SIG UUIDs (`180D`), else the full UUID. */
        internal fun shortUuid(u: UUID): String {
            val s = u.toString().lowercase()
            return if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) s.substring(4, 8).uppercase() else s.uppercase()
        }

        internal fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        internal fun parseHex(s: String): ByteArray? {
            val t = s.trim().removePrefix("0x").replace(" ", "")
            if (t.isEmpty() || t.length % 2 != 0 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /** The bytes as UTF-8 text when they are valid, printable UTF-8; otherwise null. */
        internal fun utf8OrNull(b: ByteArray): String? {
            if (b.isEmpty()) return ""
            val dec = Charsets.UTF_8.newDecoder()
            return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull()
                ?.takeIf { s -> s.none { it.isISOControl() && it !in "\n\r\t" } }
        }

        private val HELP = """android-bluetooth — Bluetooth Low Energy operations (JSON)

USAGE:
  android-bluetooth <command> [options]

COMMANDS:
  status         Bluetooth adapter and connection status
  scan           Scan for nearby BLE peripherals
  connect        Connect to a peripheral (--uuid from `scan`)
  disconnect     Disconnect the current peripheral
  services       List services and characteristics of a peripheral
  read           Read a characteristic value
  write          Write a value to a characteristic
  notify         Subscribe to a characteristic's notify/indicate updates

OPTIONS:
  --duration <seconds>    Scan / notify duration (scan default 5, max 60; notify default 10,
                          0 = longest allowed, 300 s)
  --uuid <peripheral>     Peripheral id as printed by scan (the MAC address on Android)
  --service <uuid>        Service UUID (16-bit like 180D, or full) — filter for scan
  --characteristic <uuid> Characteristic UUID (for read/write/notify)
  --value <hex>           Hex-encoded value to write
  --value-string <text>   UTF-8 string value to write
  --compact               Single-line JSON
  -q, --quiet             Only the data field

The connection stays open between commands and closes after 5 idle minutes.

EXAMPLES:
  android-bluetooth scan --duration 10
  android-bluetooth scan --service 180D
  android-bluetooth connect --uuid AA:BB:CC:DD:EE:FF
  android-bluetooth services --uuid AA:BB:CC:DD:EE:FF
  android-bluetooth read --uuid AA:BB:CC:DD:EE:FF --service 180D --characteristic 2A37
  android-bluetooth write --uuid AA:BB:CC:DD:EE:FF --service 180D --characteristic 2A39 --value 0100
  android-bluetooth notify --uuid AA:BB:CC:DD:EE:FF --service 180D --characteristic 2A37 --duration 15
"""
    }
}
