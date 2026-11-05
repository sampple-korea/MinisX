package com.minisx.app.sandbox.offload

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import com.minisx.app.util.ForegroundActivity
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * android-nfc — read and write NFC tags.
 *
 * Android counterpart of iOS `apple-nfc` (NFCOffload.m): NDEF (`scan`, `write`),
 * raw tag access (`tag-info`, `tag-read`, `tag-write`), ISO 7816 `apdu` and
 * FeliCa. Each command that touches a tag waits for one to be held to the
 * phone (`--timeout`, default 30 s) using NFC reader mode.
 *
 * Reader mode only works while an activity is in the foreground, so the app
 * must be on screen when the command runs; otherwise the command says so
 * instead of hanging. Android needs no entitlement beyond the NFC permission.
 *
 * Not ported from iOS: `read-emv` (bank-card scraping) and the offline EMV
 * tag/AID dictionaries — the generic `apdu` command with `--select-aid`
 * aliases covers the same ground without baking payment-card parsing in.
 */
class NfcOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("nfc", "NFC", args, request)?.let { return it }

        return try {
            when (sub) {
                "status" -> status(args)
                "lookup-aid" -> lookupAid(args)
                "search" -> search(args)
                "scan", "write", "tag-info", "tag-read", "tag-write", "apdu", "felica" -> withTag(sub, args)
                else -> OffloadEnvelope.error(
                    TOOL, sub, "unknown_command",
                    "Unknown command '$sub'. Valid commands: status, scan, write, tag-info, tag-read, tag-write, apdu, " +
                        "felica, lookup-aid, search. Use --help for details.", args, 2,
                )
            }
        } catch (e: SecurityException) {
            OffloadEnvelope.error(TOOL, sub, "permission_denied", e.message ?: "NFC permission denied", args, 126)
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── status / lookups ────────────────────────────────────────────────────

    private fun status(args: OffloadArgs): NativeOffloadResult {
        val has = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        val adapter = if (has) NfcAdapter.getDefaultAdapter(context) else null
        val data = JSONObject()
            .put("available", adapter != null)
            .put("enabled", adapter?.isEnabled == true)
            .put("host_card_emulation", context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            .put("hint", when {
                adapter == null -> "This device has no NFC."
                !adapter.isEnabled -> "NFC is off. Ask the user to turn it on in Settings → Connected devices → NFC."
                else -> "NFC is ready. Keep Minis on screen, run `android-nfc scan` and hold a tag to the back of the phone."
            })
        return OffloadEnvelope.ok(TOOL, "status", data, args)
    }

    private fun lookupAid(args: OffloadArgs): NativeOffloadResult {
        val q = args.positional.getOrNull(1)
            ?: return OffloadEnvelope.error(TOOL, "lookup-aid", "invalid_arguments", "Usage: lookup-aid <hex or alias>", args, 2)
        val hex = resolveAid(q)
        val alias = AID_ALIASES.entries.firstOrNull { it.value.equals(hex ?: q, ignoreCase = true) }?.key
        val data = JSONObject().put("input", q).put("aid", hex ?: JSONObject.NULL).put("alias", alias ?: JSONObject.NULL)
        return OffloadEnvelope.ok(TOOL, "lookup-aid", data, args)
    }

    private fun search(args: OffloadArgs): NativeOffloadResult {
        val needle = args.positional.getOrNull(1)?.lowercase()
        val arr = JSONArray()
        for ((alias, aid) in AID_ALIASES) {
            if (needle == null || alias.contains(needle) || aid.lowercase().contains(needle)) {
                arr.put(JSONObject().put("alias", alias).put("aid", aid))
            }
        }
        return OffloadEnvelope.ok(TOOL, "search", JSONObject().put("aliases", arr).put("count", arr.length()), args)
    }

    // ── Tag session ─────────────────────────────────────────────────────────

    private class Hold(val tag: Tag, val release: () -> Unit)

    /** Waits for a tag in reader mode, runs [action] on it, and releases the radio. */
    private fun withTag(sub: String, args: OffloadArgs): NativeOffloadResult {
        val adapter = (if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) NfcAdapter.getDefaultAdapter(context) else null)
            ?: return OffloadEnvelope.error(TOOL, sub, "not_available", "This device has no NFC.", args, 3)
        if (!adapter.isEnabled) {
            return OffloadEnvelope.error(TOOL, sub, "not_available", "NFC is off. Ask the user to turn it on in Settings → Connected devices → NFC.", args, 3)
        }
        // Validate before making the user hold a tag to the phone.
        val plan = plan(sub, args)
        if (plan.error != null) return plan.error
        val activity = ForegroundActivity.get()
            ?: return OffloadEnvelope.error(
                TOOL, sub, "not_available",
                "NFC reading needs Minis on screen. Ask the user to open the app (keep it visible), then retry.", args, 3,
            )
        val timeout = (args.getDouble("timeout") ?: 30.0).coerceIn(3.0, 120.0)

        val found = AtomicReference<Tag?>(null)
        val latch = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val enabled = CountDownLatch(1)
        main.post {
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    { tag -> found.compareAndSet(null, tag); latch.countDown() },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
                )
            }
            enabled.countDown()
        }
        enabled.await(2, TimeUnit.SECONDS)
        val release = {
            val done = CountDownLatch(1)
            main.post { runCatching { adapter.disableReaderMode(activity) }; done.countDown() }
            done.await(2, TimeUnit.SECONDS)
        }
        try {
            if (!latch.await((timeout * 1000).toLong(), TimeUnit.MILLISECONDS)) {
                return OffloadEnvelope.error(TOOL, sub, "timeout", "No tag detected within ${timeout.toInt()} s. Hold a tag to the back of the phone and retry.", args, 4)
            }
            return plan.exec!!(found.get()!!)
        } finally {
            release()
        }
    }

    private class Plan(val exec: ((Tag) -> NativeOffloadResult)? = null, val error: NativeOffloadResult? = null)

    private fun plan(sub: String, args: OffloadArgs): Plan {
        fun bad(msg: String) = Plan(error = OffloadEnvelope.error(TOOL, sub, "invalid_arguments", msg, args, 2))
        return when (sub) {
            "scan" -> Plan({ tag -> scanTag(tag, args) })
            "tag-info" -> Plan({ tag -> tagInfo(tag, args) })
            "write" -> {
                val type = (args.get("type") ?: "text").lowercase()
                val payload = args.get("payload") ?: return bad("--payload is required.")
                val record = ndefRecord(type, payload, args.get("mime"), args.get("locale") ?: "en")
                    ?: return bad("--type must be text, url or mime (mime needs --mime).")
                Plan({ tag -> writeNdef(tag, NdefMessage(arrayOf(record)), type, args) })
            }
            "tag-read" -> Plan({ tag -> tagRead(tag, args) })
            "tag-write" -> {
                val data = BluetoothOffloadHandler.parseHex(args.get("data") ?: return bad("--data <hex> is required."))
                    ?: return bad("--data must be hex.")
                Plan({ tag -> tagWrite(tag, data, args) })
            }
            "apdu" -> {
                val apdu = buildApdu(args) ?: return bad(
                    "Pass --apdu <hex> (CLA INS P1 P2 …), or --select-aid <hex|alias>, or --select-name <ascii>, " +
                        "or --cla/--ins/--p1/--p2. See `android-nfc search` for aliases.",
                )
                Plan({ tag -> apdu(tag, apdu, args) })
            }
            "felica" -> Plan({ tag -> felica(tag, args) })
            else -> bad("Unsupported command.")
        }
    }

    // ── NDEF ────────────────────────────────────────────────────────────────

    private fun scanTag(tag: Tag, args: OffloadArgs): NativeOffloadResult {
        val ndef = Ndef.get(tag)
            ?: return OffloadEnvelope.ok(
                TOOL, "scan",
                JSONObject().put("uid", hex(tag.id)).put("ndef", false).put("records", JSONArray())
                    .put("tech", JSONArray(tag.techList.map { it.substringAfterLast('.') }))
                    .put("hint", "The tag has no NDEF data. Try `tag-info` or `tag-read`."),
                args,
            )
        val msg = try { ndef.connect(); ndef.ndefMessage ?: ndef.cachedNdefMessage } finally { runCatching { ndef.close() } }
        val records = JSONArray()
        msg?.records?.forEach { records.put(describeRecord(it)) }
        val data = JSONObject()
            .put("uid", hex(tag.id)).put("ndef", true).put("type", ndef.type)
            .put("max_size", ndef.maxSize).put("writable", ndef.isWritable)
            .put("records", records).put("count", records.length())
        return OffloadEnvelope.ok(TOOL, "scan", data, args)
    }

    private fun describeRecord(r: NdefRecord): JSONObject {
        val o = JSONObject().put("tnf", tnfName(r.tnf)).put("type", String(r.type, Charsets.US_ASCII))
            .put("payload_hex", hex(r.payload))
        when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                runCatching {
                    val p = r.payload
                    val lang = p[0].toInt() and 0x3F
                    val cs: Charset = if (p[0].toInt() and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
                    o.put("kind", "text").put("locale", String(p, 1, lang, Charsets.US_ASCII))
                        .put("text", String(p, 1 + lang, p.size - 1 - lang, cs))
                }
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) ->
                r.toUri()?.let { o.put("kind", "url").put("url", it.toString()) }
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                o.put("kind", "mime").put("mime", String(r.type, Charsets.US_ASCII))
                BluetoothOffloadHandler.utf8OrNull(r.payload)?.let { o.put("text", it) }
            }
        }
        return o
    }

    private fun tnfName(t: Short): String = when (t) {
        NdefRecord.TNF_EMPTY -> "empty"
        NdefRecord.TNF_WELL_KNOWN -> "well_known"
        NdefRecord.TNF_MIME_MEDIA -> "mime"
        NdefRecord.TNF_ABSOLUTE_URI -> "absolute_uri"
        NdefRecord.TNF_EXTERNAL_TYPE -> "external"
        else -> "unknown"
    }

    private fun ndefRecord(type: String, payload: String, mime: String?, locale: String): NdefRecord? = when (type) {
        "text" -> NdefRecord.createTextRecord(locale, payload)
        "url", "uri" -> NdefRecord.createUri(payload)
        "mime" -> mime?.let { NdefRecord.createMime(it, payload.toByteArray(Charsets.UTF_8)) }
        else -> null
    }

    private fun writeNdef(tag: Tag, msg: NdefMessage, type: String, args: OffloadArgs): NativeOffloadResult {
        val size = msg.toByteArray().size
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return OffloadEnvelope.error(TOOL, "write", "not_writable", "The tag is read-only.", args)
                if (ndef.maxSize < size) {
                    return OffloadEnvelope.error(TOOL, "write", "too_large", "Message is $size bytes; the tag holds ${ndef.maxSize}.", args)
                }
                ndef.writeNdefMessage(msg)
            } finally { runCatching { ndef.close() } }
        } else {
            val fmt = NdefFormatable.get(tag)
                ?: return OffloadEnvelope.error(TOOL, "write", "unsupported", "This tag cannot hold NDEF data.", args)
            try { fmt.connect(); fmt.format(msg) } finally { runCatching { fmt.close() } }
        }
        return OffloadEnvelope.ok(TOOL, "write", JSONObject().put("uid", hex(tag.id)).put("type", type).put("bytes_written", size), args)
    }

    // ── Raw tag ─────────────────────────────────────────────────────────────

    private fun tagInfo(tag: Tag, args: OffloadArgs): NativeOffloadResult {
        val o = JSONObject().put("uid", hex(tag.id)).put("tech", JSONArray(tag.techList.map { it.substringAfterLast('.') }))
        NfcA.get(tag)?.let { o.put("atqa", hex(it.atqa)).put("sak", "%02x".format(it.sak.toInt() and 0xFF)) }
        NfcB.get(tag)?.let { o.put("application_data", hex(it.applicationData)).put("protocol_info", hex(it.protocolInfo)) }
        IsoDep.get(tag)?.let { d ->
            o.put("iso_dep", true)
            d.historicalBytes?.let { o.put("historical_bytes", hex(it)) }
            d.hiLayerResponse?.let { o.put("hi_layer_response", hex(it)) }
            o.put("extended_length", d.isExtendedLengthApduSupported).put("max_transceive", d.maxTransceiveLength)
        }
        MifareUltralight.get(tag)?.let { o.put("mifare_ultralight_type", if (it.type == MifareUltralight.TYPE_ULTRALIGHT_C) "ultralight_c" else "ultralight") }
        MifareClassic.get(tag)?.let { o.put("mifare_classic_size", it.size).put("mifare_classic_sectors", it.sectorCount) }
        NfcF.get(tag)?.let { o.put("felica_system_code", hex(it.systemCode)).put("felica_manufacturer", hex(it.manufacturer)) }
        NfcV.get(tag)?.let { o.put("iso15693_dsf_id", it.dsfId.toInt() and 0xFF).put("iso15693_response_flags", it.responseFlags.toInt() and 0xFF) }
        Ndef.get(tag)?.let { o.put("ndef", JSONObject().put("type", it.type).put("max_size", it.maxSize).put("writable", it.isWritable)) }
        return OffloadEnvelope.ok(TOOL, "tag-info", o, args)
    }

    private fun tagRead(tag: Tag, args: OffloadArgs): NativeOffloadResult {
        MifareUltralight.get(tag)?.let { mu ->
            val page = args.getInt("page") ?: 0
            val count = args.getInt("pages") ?: 4
            val rows = JSONArray()
            try {
                mu.connect()
                var p = page
                // readPages returns 4 pages (16 bytes) per call.
                while (p < page + count) {
                    val chunk = mu.readPages(p)
                    for (i in 0 until 4) {
                        if (p + i >= page + count) break
                        rows.put(JSONObject().put("page", p + i).put("hex", hex(chunk.copyOfRange(i * 4, i * 4 + 4))))
                    }
                    p += 4
                }
            } catch (e: Exception) {
                return OffloadEnvelope.error(TOOL, "tag-read", "read_failed", "Read failed at page ${page + rows.length()}: ${e.message}", args)
            } finally { runCatching { mu.close() } }
            return OffloadEnvelope.ok(TOOL, "tag-read", JSONObject().put("uid", hex(tag.id)).put("kind", "mifare_ultralight").put("pages", rows), args)
        }
        NfcV.get(tag)?.let { nv ->
            val block = args.getInt("block") ?: 0
            val count = args.getInt("blocks") ?: 4
            val rows = JSONArray()
            try {
                nv.connect()
                for (b in block until block + count) {
                    val resp = nv.transceive(byteArrayOf(0x02, 0x20, b.toByte()))
                    if (resp.isEmpty() || resp[0].toInt() != 0) {
                        return OffloadEnvelope.error(TOOL, "tag-read", "read_failed", "Block $b: error response ${hex(resp)}", args)
                    }
                    rows.put(JSONObject().put("block", b).put("hex", hex(resp.copyOfRange(1, resp.size))))
                }
            } catch (e: Exception) {
                return OffloadEnvelope.error(TOOL, "tag-read", "read_failed", e.message ?: "read failed", args)
            } finally { runCatching { nv.close() } }
            return OffloadEnvelope.ok(TOOL, "tag-read", JSONObject().put("uid", hex(tag.id)).put("kind", "iso15693").put("blocks", rows), args)
        }
        return OffloadEnvelope.error(TOOL, "tag-read", "unsupported", "tag-read supports MIFARE Ultralight/NTAG and ISO 15693 tags (this one: ${tag.techList.joinToString { it.substringAfterLast('.') }}).", args)
    }

    private fun tagWrite(tag: Tag, data: ByteArray, args: OffloadArgs): NativeOffloadResult {
        MifareUltralight.get(tag)?.let { mu ->
            val page = args.getInt("page") ?: 4
            if (page < 4) {
                return OffloadEnvelope.error(TOOL, "tag-write", "invalid_arguments", "Pages 0–3 hold the UID and lock bytes; start at page 4.", args, 2)
            }
            val padded = data.copyOf(((data.size + 3) / 4) * 4)
            try {
                mu.connect()
                for (i in 0 until padded.size / 4) mu.writePage(page + i, padded.copyOfRange(i * 4, i * 4 + 4))
            } catch (e: Exception) {
                return OffloadEnvelope.error(TOOL, "tag-write", "write_failed", e.message ?: "write failed", args)
            } finally { runCatching { mu.close() } }
            return OffloadEnvelope.ok(TOOL, "tag-write", JSONObject().put("uid", hex(tag.id)).put("page", page).put("bytes_written", padded.size), args)
        }
        NfcV.get(tag)?.let { nv ->
            val block = args.getInt("block") ?: 0
            try {
                nv.connect()
                val resp = nv.transceive(byteArrayOf(0x02, 0x21, block.toByte()) + data)
                if (resp.isEmpty() || resp[0].toInt() != 0) {
                    return OffloadEnvelope.error(TOOL, "tag-write", "write_failed", "Error response ${hex(resp)}", args)
                }
            } catch (e: Exception) {
                return OffloadEnvelope.error(TOOL, "tag-write", "write_failed", e.message ?: "write failed", args)
            } finally { runCatching { nv.close() } }
            return OffloadEnvelope.ok(TOOL, "tag-write", JSONObject().put("uid", hex(tag.id)).put("block", block).put("bytes_written", data.size), args)
        }
        return OffloadEnvelope.error(TOOL, "tag-write", "unsupported", "tag-write supports MIFARE Ultralight/NTAG and ISO 15693 tags.", args)
    }

    // ── ISO 7816 / FeliCa ───────────────────────────────────────────────────

    private fun buildApdu(args: OffloadArgs): ByteArray? {
        args.get("apdu")?.let { return BluetoothOffloadHandler.parseHex(it)?.takeIf { b -> b.size >= 4 } }
        val aidHex = args.get("select-aid")?.let { resolveAid(it) }
        val nameHex = args.get("select-name")?.let { hex(it.toByteArray(Charsets.US_ASCII)) }
        if (args.get("select-aid") != null && aidHex == null) return null
        val selectData = aidHex ?: nameHex
        if (selectData != null) {
            val d = BluetoothOffloadHandler.parseHex(selectData) ?: return null
            // SELECT by DF name / AID: P1 = 04.
            return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, d.size.toByte()) + d + byteArrayOf(0x00)
        }
        val ins = args.get("ins")?.toIntOrNull(16) ?: return null
        val cla = args.get("cla")?.toIntOrNull(16) ?: 0
        val p1 = args.get("p1")?.toIntOrNull(16) ?: 0
        val p2 = args.get("p2")?.toIntOrNull(16) ?: 0
        val le = args.getInt("le") ?: 0
        return byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), le.toByte())
    }

    private fun apdu(tag: Tag, command: ByteArray, args: OffloadArgs): NativeOffloadResult {
        val iso = IsoDep.get(tag)
            ?: return OffloadEnvelope.error(TOOL, "apdu", "unsupported", "The tag does not speak ISO 7816 (ISO-DEP).", args)
        return try {
            iso.connect(); iso.timeout = 5_000
            val resp = iso.transceive(command)
            val o = JSONObject().put("uid", hex(tag.id)).put("command", hex(command)).put("response", hex(resp))
            if (resp.size >= 2) {
                val sw1 = resp[resp.size - 2].toInt() and 0xFF
                val sw2 = resp[resp.size - 1].toInt() and 0xFF
                o.put("data", hex(resp.copyOfRange(0, resp.size - 2)))
                    .put("sw", "%02X%02X".format(sw1, sw2)).put("sw1", "%02X".format(sw1)).put("sw2", "%02X".format(sw2))
                    .put("success", sw1 == 0x90 && sw2 == 0x00)
            }
            OffloadEnvelope.ok(TOOL, "apdu", o, args)
        } catch (e: Exception) {
            OffloadEnvelope.error(TOOL, "apdu", "io_error", e.message ?: "transceive failed", args)
        } finally { runCatching { iso.close() } }
    }

    private fun felica(tag: Tag, args: OffloadArgs): NativeOffloadResult {
        val f = NfcF.get(tag)
            ?: return OffloadEnvelope.error(TOOL, "felica", "unsupported", "The tag is not FeliCa (NFC-F).", args)
        val sc = BluetoothOffloadHandler.parseHex(args.get("sc") ?: "0B00")
            ?.takeIf { it.size == 2 } ?: return OffloadEnvelope.error(TOOL, "felica", "invalid_arguments", "--sc must be 2 bytes of hex (little endian).", args, 2)
        val bc = args.getInt("bc") ?: 1
        val bl = BluetoothOffloadHandler.parseHex(args.get("bl") ?: "8000")
            ?: return OffloadEnvelope.error(TOOL, "felica", "invalid_arguments", "--bl must be hex block list elements.", args, 2)
        return try {
            f.connect()
            val idm = tag.id
            // Read Without Encryption: len, 0x06, IDm, #services, service code, #blocks, block list.
            val body = byteArrayOf(0x06) + idm + byteArrayOf(0x01) + sc + byteArrayOf(bc.toByte()) + bl
            val resp = f.transceive(byteArrayOf((body.size + 1).toByte()) + body)
            OffloadEnvelope.ok(TOOL, "felica", JSONObject().put("idm", hex(idm)).put("response", hex(resp)), args)
        } catch (e: Exception) {
            OffloadEnvelope.error(TOOL, "felica", "io_error", e.message ?: "transceive failed", args)
        } finally { runCatching { f.close() } }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun hex(b: ByteArray?): String = b?.joinToString("") { "%02X".format(it) } ?: ""

    /** AID given as hex, or as an alias from [AID_ALIASES]. */
    private fun resolveAid(input: String): String? {
        val t = input.trim()
        if (t.length >= 4 && t.length % 2 == 0 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return t.uppercase()
        return AID_ALIASES[t.lowercase(Locale.ROOT)]
    }

    companion object {
        private const val TOOL = "android-nfc"

        private val AID_ALIASES: Map<String, String> = linkedMapOf(
            "ppse" to "325041592E5359532E4444463031", "pse" to "315041592E5359532E4444463031",
            "ndef" to "D2760000850101",
            "visa" to "A0000000031010", "visa-electron" to "A0000000032010", "visa-debit" to "A0000000031010",
            "mastercard" to "A0000000041010", "maestro" to "A0000000043060",
            "amex" to "A00000002501", "american-express" to "A00000002501",
            "jcb" to "A0000000651010", "discover" to "A0000001524010", "interac" to "A0000002771010",
            "unionpay" to "A000000333010101", "unionpay-debit" to "A000000333010101",
            "unionpay-credit" to "A000000333010102", "unionpay-quasi" to "A000000333010103",
            "cb" to "A0000000421010", "cartes-bancaires" to "A0000000421010", "dankort" to "A0000001211010",
            "girocard" to "A00000005945430100", "rupay" to "A0000005241010", "mir" to "A0000006581010",
            "troy" to "A0000006723010",
            "yubikey-piv" to "A000000308", "yubikey-oath" to "A0000005272101", "yubikey-otp" to "A000000527200101",
            "yubikey-mgmt" to "A000000527471117", "fido2" to "A0000006472F0001", "u2f" to "A0000006472F0001",
            "piv" to "A000000308", "openpgp" to "D27600012401", "tangem" to "A000000812010208",
            "epassport" to "A0000002471001", "passport" to "A0000002471001", "mdl" to "A0000002480400",
            "tmoney" to "D4100000030001", "t-money" to "D4100000030001", "cashbee" to "D4100000140001",
            "cepas" to "A000000341000101", "navigo" to "A0000004040125090101", "t-union" to "A000000632010105",
            "itso" to "A0000002164954534F2D31",
            "orca" to "A00000039656434103F213F000000000", "presto" to "A00000039656434103F2142000000000",
            "myki" to "A00000F21100", "octopus" to "A0000005500011",
        )

        private val HELP = """android-nfc — NFC tag reading and writing (JSON)

USAGE:
  android-nfc <command> [options]

Commands that touch a tag wait for one to be held to the phone. Minis must be on
screen (NFC reader mode needs a foreground app).

NDEF:
  status         NFC availability
  scan           Read the NDEF records of a tag
  write          Write one NDEF record:  --type text|url|mime --payload <content> [--mime <type>] [--locale en]

RAW TAG:
  tag-info       UID, technologies, capabilities
  tag-read       MIFARE Ultralight/NTAG: --page N --pages N    ISO 15693: --block N --blocks N
  tag-write      --data <hex>  with --page N (default 4) or --block N
  apdu           ISO 7816:  --apdu <hex> | --select-aid <hex|alias> | --select-name <ascii> |
                 --cla/--ins/--p1/--p2 <hex> --le N
  felica         --sc <hex, 2 bytes LE> --bc <count> --bl <hex block list>

LOOKUP (offline, no tag):
  lookup-aid <hex|alias>    Resolve an AID or alias
  search [query]            List/search the built-in AID aliases (ppse, visa, piv, openpgp, passport, tmoney, …)

OPTIONS:
  --timeout <seconds>   How long to wait for a tag (default 30, max 120)
  --compact, -q         Single-line JSON / data field only

EXAMPLES:
  android-nfc scan
  android-nfc write --type url --payload "https://example.com"
  android-nfc tag-read --page 4 --pages 8
  android-nfc apdu --select-aid ppse
"""
    }
}
