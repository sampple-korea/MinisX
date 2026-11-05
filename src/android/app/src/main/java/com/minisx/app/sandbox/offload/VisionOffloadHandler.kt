package com.minisx.app.sandbox.offload

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import com.minisx.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * android-vision — image analysis with on-device ML Kit models.
 *
 * Android counterpart of iOS `apple-vision` (VisionOffload.m): `ocr`,
 * `barcode`, `classify`, `detect`, `faces`, `analyze`, `similarity` and
 * `overlap`, with the same options and result shape. The models ship inside the
 * app, so nothing is downloaded and no image leaves the device.
 *
 * Differences from iOS, all in the engines rather than the interface:
 *  - `ocr` recognises Latin, Chinese, Japanese and Korean (one script per run;
 *    `--lang` picks it; `--level` is accepted and ignored).
 *  - `detect` finds objects (ML Kit object detection), not rectangles.
 *  - `similarity` compares a 16×16 colour/luma signature, not Apple's feature
 *    print, so its scores are comparable only with each other.
 *  - `overlap` uses the same anchor-row pixel search; there is no Vision
 *    translational-registration fallback, so a failed pixel match reports
 *    confidence 0.
 */
class VisionOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("vision", "Vision", args, request)?.let { return it }
        if (sub !in COMMANDS) {
            return OffloadEnvelope.error(
                TOOL, sub, "unknown_command",
                "Unknown command '$sub'. Valid commands: ${COMMANDS.joinToString()}. Use --help for details.", args, 2,
            )
        }
        val paths = args.positional.drop(1)
        val need = if (sub == "similarity" || sub == "overlap") 2 else 1
        if (paths.size < need) {
            return OffloadEnvelope.error(
                TOOL, sub, "invalid_arguments",
                if (need == 1) "Usage: android-vision $sub <image-path>" else "Usage: android-vision $sub <image> <image> [more…]", args, 2,
            )
        }
        val files = paths.map { resolve(it, request.cwd) }
        files.firstOrNull { !it.isFile }?.let {
            return OffloadEnvelope.error(TOOL, sub, "no_data", "Image not found: ${it.path}", args)
        }
        return try {
            val threshold = (args.getDouble("threshold") ?: 0.9).toFloat().coerceIn(0f, 1f)
            val data: JSONObject = when (sub) {
                "similarity" -> similarity(files, threshold)
                "overlap" -> overlap(files, threshold, args.getInt("skip-top") ?: 0, args.getInt("skip-bottom") ?: 0)
                else -> {
                    val bmp = decode(files[0]) ?: return OffloadEnvelope.error(TOOL, sub, "invalid_arguments", "Cannot decode image: ${files[0].path}", args)
                    try {
                        val img = InputImage.fromBitmap(bmp, 0)
                        when (sub) {
                            "ocr" -> ocr(img, args)
                            "barcode" -> barcode(img)
                            "classify" -> classify(img, args.getInt("limit") ?: 10)
                            "detect" -> detect(img, args.getInt("limit") ?: 20)
                            "faces" -> faces(img)
                            else -> JSONObject()
                                .put("ocr", ocr(img, args))
                                .put("classify", classify(img, args.getInt("limit") ?: 5))
                                .put("barcode", barcode(img))
                        }
                    } finally { bmp.recycle() }
                }
            }
            OffloadEnvelope.ok(TOOL, sub, data, args)
        } catch (e: OutOfMemoryError) {
            OffloadEnvelope.error(TOOL, sub, "internal", "Image too large to analyse.", args)
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── Engines ─────────────────────────────────────────────────────────────

    private fun ocr(img: InputImage, args: OffloadArgs): JSONObject {
        val langs = args.get("lang")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
        val recognizer: TextRecognizer = when {
            langs.any { it.startsWith("zh") } -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            langs.any { it.startsWith("ja") } -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            langs.any { it.startsWith("ko") } -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        try {
            val text = await(recognizer.process(img))
            val blocks = JSONArray()
            val full = StringBuilder()
            for (block in text.textBlocks) for (line in block.lines) {
                if (full.isNotEmpty()) full.append('\n')
                full.append(line.text)
                blocks.put(JSONObject().put("text", line.text)
                    .put("confidence", if (line.confidence > 0) line.confidence.toDouble() else JSONObject.NULL)
                    .put("bbox", bbox(line.boundingBox, img)))
            }
            return JSONObject().put("text", full.toString()).put("blocks", blocks)
                .put("languages", JSONArray(langs))
        } finally { recognizer.close() }
    }

    private fun barcode(img: InputImage): JSONObject {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        try {
            val arr = JSONArray()
            for (b in await(scanner.process(img))) {
                arr.put(JSONObject().put("payload", b.rawValue ?: JSONObject.NULL).put("symbology", formatName(b.format))
                    .put("value_type", typeName(b.valueType)).put("bbox", bbox(b.boundingBox, img)))
            }
            return JSONObject().put("barcodes", arr).put("count", arr.length())
        } finally { scanner.close() }
    }

    private fun classify(img: InputImage, limit: Int): JSONObject {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.3f).build())
        try {
            val arr = JSONArray()
            for (l in await(labeler.process(img)).sortedByDescending { it.confidence }.take(limit)) {
                arr.put(JSONObject().put("label", l.text).put("confidence", l.confidence.toDouble()))
            }
            return JSONObject().put("classifications", arr).put("count", arr.length())
        } finally { labeler.close() }
    }

    private fun detect(img: InputImage, limit: Int): JSONObject {
        val detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects().enableClassification().build(),
        )
        try {
            val arr = JSONArray()
            for (o in await(detector.process(img)).take(limit)) {
                val best = o.labels.maxByOrNull { it.confidence }
                arr.put(JSONObject().put("label", best?.text ?: JSONObject.NULL)
                    .put("confidence", best?.confidence?.toDouble() ?: JSONObject.NULL).put("bbox", bbox(o.boundingBox, img)))
            }
            return JSONObject().put("objects", arr).put("count", arr.length())
        } finally { detector.close() }
    }

    private fun faces(img: InputImage): JSONObject {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build(),
        )
        try {
            val arr = JSONArray()
            for (f in await(detector.process(img))) {
                arr.put(JSONObject().put("bbox", bbox(f.boundingBox, img))
                    .put("roll", f.headEulerAngleZ.toDouble()).put("yaw", f.headEulerAngleY.toDouble()))
            }
            return JSONObject().put("faces", arr).put("count", arr.length())
        } finally { detector.close() }
    }

    // ── Similarity ──────────────────────────────────────────────────────────

    /** 16×16 RGB signature, so the comparison is robust to scale and re-encoding. */
    private fun signature(f: File): FloatArray? {
        val bmp = decode(f, maxSide = 256) ?: return null
        try {
            val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            val out = FloatArray(16 * 16 * 3)
            var i = 0
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = small.getPixel(x, y)
                out[i++] = Color.red(p) / 255f; out[i++] = Color.green(p) / 255f; out[i++] = Color.blue(p) / 255f
            }
            if (small !== bmp) small.recycle()
            return out
        } finally { bmp.recycle() }
    }

    private fun similarity(files: List<File>, threshold: Float): JSONObject {
        val sigs = files.map { signature(it) }
        val infos = JSONArray()
        files.forEachIndexed { i, f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            infos.put(JSONObject().put("path", f.path).put("width", o.outWidth).put("height", o.outHeight).put("decoded", sigs[i] != null))
        }
        val parent = IntArray(files.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        val pairs = JSONArray()
        for (i in files.indices) for (j in i + 1 until files.size) {
            val a = sigs[i]; val b = sigs[j]
            if (a == null || b == null) continue
            var sum = 0f
            for (k in a.indices) sum += abs(a[k] - b[k])
            val sim = max(0f, 1f - sum / a.size * 3f) // mean abs error, ×3 so visibly different images land well below 1
            val dup = sim >= threshold
            if (dup) parent[find(i)] = find(j)
            pairs.put(JSONObject().put("a", files[i].path).put("b", files[j].path)
                .put("similarity", Math.round(sim * 10_000.0) / 10_000.0).put("is_duplicate", dup))
        }
        val groups = files.indices.groupBy { find(it) }.values.filter { it.size > 1 }
        val groupArr = JSONArray()
        groups.forEach { g -> groupArr.put(JSONArray(g.sorted().map { files[it].path })) }
        return JSONObject().put("images", infos).put("pairs", pairs).put("duplicate_groups", groupArr)
            .put("threshold", threshold.toDouble()).put("total_images", files.size).put("duplicate_group_count", groupArr.length())
    }

    // ── Overlap ─────────────────────────────────────────────────────────────

    private class Pixels(val w: Int, val h: Int, val px: IntArray)

    private fun pixels(f: File): Pixels? {
        val bmp = decode(f) ?: return null
        try {
            val arr = IntArray(bmp.width * bmp.height)
            bmp.getPixels(arr, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Pixels(bmp.width, bmp.height, arr)
        } finally { bmp.recycle() }
    }

    /** Fraction of pixels in row [ya] of [a] that match row [yb] of [b] within [tol] per channel. */
    private fun rowMatch(a: Pixels, ya: Int, b: Pixels, yb: Int, width: Int, tol: Int): Float {
        var matched = 0
        val oa = ya * a.w
        val ob = yb * b.w
        for (x in 0 until width) {
            val pa = a.px[oa + x]; val pb = b.px[ob + x]
            if (abs(Color.red(pa) - Color.red(pb)) <= tol && abs(Color.green(pa) - Color.green(pb)) <= tol &&
                abs(Color.blue(pa) - Color.blue(pb)) <= tol) matched++
        }
        return if (width > 0) matched.toFloat() / width else 0f
    }

    /** Overlap between the bottom of [a] and the top of [b]: anchor-row scan, then multi-row verification. */
    private fun findOverlap(a: Pixels, b: Pixels, threshold: Float, skipTop: Int, skipBottom: Int): JSONObject {
        val contentTop = skipTop
        val contentBotA = a.h - skipBottom
        val contentBotB = b.h - skipBottom
        if (contentBotA <= contentTop || contentBotB <= contentTop) {
            return JSONObject().put("overlap_px", 0).put("confidence", 0).put("method", "none")
                .put("error", "skip-top + skip-bottom exceeds image height")
        }
        val width = min(a.w, b.w)
        val tol = if (threshold >= 0.98f) 1 else if (threshold >= 0.90f) 3 else 8
        val contentHeightA = contentBotA - contentTop
        val anchorStart = contentBotA - contentHeightA / 4
        val anchorEnd = contentBotA - 1
        val step = max(1, (anchorEnd - anchorStart) / 5)
        val verifyRows = 30

        var best = 0
        var bestScore = -1f
        var ay = anchorStart
        while (ay <= anchorEnd) {
            for (by in contentTop until contentBotB) {
                if (rowMatch(a, ay, b, by, width, tol) < threshold) continue
                // A[ay] == B[by] ⇒ bottom N rows of A equal top N rows of B, N = hA - ay + by.
                val candidate = a.h - ay + by
                if (candidate < 10 || candidate > min(a.h, b.h)) continue
                var verified = 0
                var checked = 0
                for (k in -verifyRows / 2..verifyRows / 2) {
                    val ya = ay + k
                    val yb = by + k
                    if (ya < contentTop || ya >= contentBotA || yb < contentTop || yb >= contentBotB) continue
                    if (rowMatch(a, ya, b, yb, width, tol) >= threshold) verified++
                    checked++
                }
                val score = if (checked > 0) verified.toFloat() / checked else 0f
                if (score > bestScore) { bestScore = score; best = candidate }
                if (score > 0.95f) break
            }
            ay += step
        }
        val matched = bestScore >= threshold
        val method = when { matched && bestScore > 0.98f -> "pixel_exact"; matched -> "pixel_match"; else -> "none" }
        val overlap = if (matched) best else 0
        return JSONObject().put("overlap_px", overlap).put("confidence", if (matched) bestScore.toDouble() else 0.0)
            .put("threshold", threshold.toDouble()).put("method", method)
            .put("skip_top", skipTop).put("skip_bottom", skipBottom).put("pixel_tolerance", tol)
            .put("region_a", JSONObject().put("y", a.h - overlap).put("height", overlap).put("description", "bottom of image A"))
            .put("region_b", JSONObject().put("y", 0).put("height", overlap).put("description", "top of image B"))
    }

    private fun overlap(files: List<File>, threshold: Float, skipTop: Int, skipBottom: Int): JSONObject {
        val pairs = JSONArray()
        var prev = pixels(files[0]) ?: throw IllegalArgumentException("Cannot decode ${files[0].path}")
        for (i in 1 until files.size) {
            val next = pixels(files[i]) ?: throw IllegalArgumentException("Cannot decode ${files[i].path}")
            pairs.put(findOverlap(prev, next, threshold, skipTop, skipBottom)
                .put("index_a", i - 1).put("index_b", i).put("path_a", files[i - 1].path).put("path_b", files[i].path))
            prev = next
        }
        return JSONObject().put("pairs", pairs).put("total_images", files.size)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Guest path (e.g. `/var/minis/attachments/a.png`) or a path relative to the shell's cwd. */
    private fun resolve(p: String, cwd: String): File {
        val guest = if (p.startsWith("/")) p else "${cwd.trimEnd('/')}/$p"
        return runCatching { PRootKernel.resolveHostPath(guest) }.getOrNull() ?: File(guest)
    }

    private fun decode(f: File, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** `[x, y, w, h]` normalised to 0–1 with the origin at the top-left. */
    private fun bbox(r: Rect?, img: InputImage): JSONArray {
        if (r == null) return JSONArray()
        val w = img.width.toDouble(); val h = img.height.toDouble()
        return JSONArray(listOf(r.left / w, r.top / h, r.width() / w, r.height() / h))
    }

    private fun <T> await(t: Task<T>): T = Tasks.await(t, 60, TimeUnit.SECONDS)

    private fun formatName(f: Int): String = when (f) {
        Barcode.FORMAT_QR_CODE -> "QR"; Barcode.FORMAT_EAN_13 -> "EAN-13"; Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"; Barcode.FORMAT_UPC_E -> "UPC-E"; Barcode.FORMAT_CODE_128 -> "Code128"
        Barcode.FORMAT_CODE_39 -> "Code39"; Barcode.FORMAT_CODE_93 -> "Code93"; Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"; Barcode.FORMAT_DATA_MATRIX -> "DataMatrix"; Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"; else -> "unknown"
    }

    private fun typeName(t: Int): String = when (t) {
        Barcode.TYPE_URL -> "url"; Barcode.TYPE_WIFI -> "wifi"; Barcode.TYPE_EMAIL -> "email"; Barcode.TYPE_PHONE -> "phone"
        Barcode.TYPE_SMS -> "sms"; Barcode.TYPE_GEO -> "geo"; Barcode.TYPE_CONTACT_INFO -> "contact"
        Barcode.TYPE_CALENDAR_EVENT -> "calendar_event"; Barcode.TYPE_PRODUCT -> "product"; Barcode.TYPE_TEXT -> "text"
        else -> "unknown"
    }

    companion object {
        private const val TOOL = "android-vision"
        private const val MAX_SIDE = 4096
        private val COMMANDS = setOf("ocr", "barcode", "classify", "detect", "faces", "analyze", "similarity", "overlap")

        private val HELP = """android-vision — image analysis with on-device ML Kit (JSON)

USAGE:
  android-vision <command> <image-path> [options]

COMMANDS:
  ocr        Recognize text in an image
  barcode    Detect barcodes and QR codes
  classify   Classify image contents
  detect     Detect objects
  faces      Detect faces
  analyze    Combined analysis (ocr + classify + barcode)
  similarity Compare two or more images for similarity
  overlap    Detect overlapping regions between consecutive image pairs (scrolling screenshots)

OPTIONS:
  --lang <codes>      OCR script: zh*, ja*, ko* select Chinese/Japanese/Korean; anything else (or none) is Latin
  --limit <N>         Max results for classify/detect
  --threshold <F>     Match threshold 0.0-1.0 (default 0.9) for similarity and overlap
  --skip-top <px>     Rows to skip from the top (status bar) for overlap detection
  --skip-bottom <px>  Rows to skip from the bottom (nav bar) for overlap detection
  --compact, -q       Single-line JSON / data field only

NOTES:
  <image-path> may be a path inside the Linux shell (e.g. /var/minis/attachments/photo.jpg).
  Models run on the device; nothing is uploaded.

EXAMPLES:
  android-vision ocr /var/minis/attachments/photo.jpg
  android-vision ocr doc.png --lang zh
  android-vision barcode /var/minis/attachments/qr.png --compact -q
  android-vision classify photo.jpg --limit 5
  android-vision overlap top.png bottom.png --skip-top 80 --skip-bottom 120
"""
    }
}
