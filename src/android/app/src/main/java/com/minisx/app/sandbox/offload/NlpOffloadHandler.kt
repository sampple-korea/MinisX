package com.minisx.app.sandbox.offload

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.minisx.app.sandbox.NativeOffloadHandler
import com.minisx.app.sandbox.NativeOffloadRequest
import com.minisx.app.sandbox.NativeOffloadResult
import com.minisx.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * android-nlp — language detection, tokenizing and entity extraction.
 *
 * Android counterpart of iOS `apple-nlp` (NLPOffload.m), covering the parts
 * Android has an on-device engine for:
 *
 *  - `language`  ML Kit language identification (bundled model)
 *  - `tokenize`  ICU break iterators (word / sentence / paragraph)
 *  - `ner`       ML Kit entity extraction. This finds structured entities —
 *                dates, addresses, e-mail, phone, URL, money, flight and
 *                tracking numbers, IBAN, ISBN — not person / place /
 *                organisation names the way Apple's tagger does. The language
 *                model (~5 MB) downloads the first time it is used.
 *  - `analyze`   language + tokenize counts + ner
 *
 * `pos`, `sentiment` and `embed` have no on-device Android counterpart and
 * answer `not_supported` rather than inventing a result.
 *
 * iOS accepts the text on stdin; an offload call has no stdin, so the text
 * comes from `--text` or `--file <path>`.
 */
class NlpOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1), setOf("per-sentence"))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return OffloadEnvelope.error(TOOL, "help", "invalid_arguments", "No command specified. Use --help for usage.", args, 2)
        OffloadGate.enforce("nlp", "Language", args, request)?.let { return it }

        if (sub in UNSUPPORTED) {
            return OffloadEnvelope.error(
                TOOL, sub, "not_supported", UNSUPPORTED.getValue(sub), args, 3,
            )
        }
        if (sub !in setOf("language", "tokenize", "ner", "analyze")) {
            return OffloadEnvelope.error(
                TOOL, sub, "unknown_command",
                "Unknown command '$sub'. Valid commands: language, tokenize, ner, analyze (pos, sentiment, embed are not supported on Android).",
                args, 2,
            )
        }
        val text = args.get("text") ?: args.get("file")?.let { path ->
            val guest = if (path.startsWith("/")) path else "${request.cwd.trimEnd('/')}/$path"
            val f = runCatching { PRootKernel.resolveHostPath(guest) }.getOrNull() ?: File(guest)
            if (!f.isFile) return OffloadEnvelope.error(TOOL, sub, "no_data", "File not found: $path", args)
            f.readText()
        }
        if (text.isNullOrBlank()) {
            return OffloadEnvelope.error(TOOL, sub, "invalid_arguments", "Pass the input with --text <string> or --file <path>.", args, 2)
        }
        return try {
            val data = when (sub) {
                "language" -> language(text)
                "tokenize" -> tokenize(text, args.get("unit") ?: "word")
                "ner" -> ner(text)
                else -> JSONObject().put("language", language(text))
                    .put("tokens", tokenize(text, "word").let { JSONObject().put("count", it.optInt("count")) })
                    .put("sentences", tokenize(text, "sentence").let { JSONObject().put("count", it.optInt("count")) })
                    .put("entities", runCatching { ner(text) }.getOrElse { JSONObject().put("error", it.message) })
            }
            OffloadEnvelope.ok(TOOL, sub, data, args)
        } catch (e: IllegalArgumentException) {
            OffloadEnvelope.error(TOOL, sub, "invalid_arguments", e.message ?: "invalid arguments", args, 2)
        } catch (e: Throwable) {
            OffloadEnvelope.error(TOOL, sub, "internal", e.message ?: e.javaClass.simpleName, args)
        }
    }

    // ── language ────────────────────────────────────────────────────────────

    private fun language(text: String): JSONObject {
        val client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build())
        try {
            val all = await(client.identifyPossibleLanguages(text)).filter { it.languageTag != "und" }
            val arr = JSONArray()
            all.sortedByDescending { it.confidence }.take(5).forEach {
                arr.put(JSONObject().put("language", it.languageTag).put("confidence", it.confidence.toDouble())
                    .put("name", Locale.forLanguageTag(it.languageTag).getDisplayLanguage(Locale.ENGLISH)))
            }
            val top = all.maxByOrNull { it.confidence }
            return JSONObject().put("language", top?.languageTag ?: "und").put("confidence", top?.confidence?.toDouble() ?: 0.0)
                .put("hypotheses", arr)
        } finally { client.close() }
    }

    // ── tokenize ────────────────────────────────────────────────────────────

    private fun tokenize(text: String, unit: String): JSONObject {
        val items = JSONArray()
        when (unit) {
            "word" -> breaks(BreakIterator.getWordInstance(), text) { tok ->
                if (tok.any { it.isLetterOrDigit() }) items.put(tok)
            }
            "sentence" -> breaks(BreakIterator.getSentenceInstance(), text) { s -> s.trim().takeIf { it.isNotEmpty() }?.let { items.put(it) } }
            "paragraph" -> text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { items.put(it) }
            else -> throw IllegalArgumentException("--unit must be word, sentence or paragraph.")
        }
        return JSONObject().put("unit", unit).put("count", items.length()).put("tokens", items)
    }

    private fun breaks(it: BreakIterator, text: String, emit: (String) -> Unit) {
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            emit(text.substring(start, end))
            start = end
            end = it.next()
        }
    }

    // ── ner ─────────────────────────────────────────────────────────────────

    private fun ner(text: String): JSONObject {
        val tag = language(text).optString("language").substringBefore('-')
        val lang = ENTITY_LANGUAGES[tag]
            ?: throw IllegalArgumentException(
                "Entity extraction does not support the detected language '$tag'. Supported: ${ENTITY_LANGUAGES.keys.sorted().joinToString()}.",
            )
        val extractor = EntityExtraction.getClient(EntityExtractorOptions.Builder(lang).build())
        try {
            await(extractor.downloadModelIfNeeded(), 120)
            val out = JSONArray()
            for (a in await(extractor.annotate(EntityExtractionParams.Builder(text).build()))) {
                for (e in a.entities) {
                    out.put(JSONObject().put("text", a.annotatedText).put("start", a.start).put("end", a.end)
                        .put("kind", entityName(e.type)).apply { details(e)?.let { put("value", it) } })
                }
            }
            return JSONObject().put("language", tag).put("entities", out).put("count", out.length())
        } finally { extractor.close() }
    }

    private fun entityName(t: Int): String = when (t) {
        Entity.TYPE_ADDRESS -> "address"; Entity.TYPE_DATE_TIME -> "date_time"; Entity.TYPE_EMAIL -> "email"
        Entity.TYPE_FLIGHT_NUMBER -> "flight_number"; Entity.TYPE_IBAN -> "iban"; Entity.TYPE_ISBN -> "isbn"
        Entity.TYPE_MONEY -> "money"; Entity.TYPE_PAYMENT_CARD -> "payment_card"; Entity.TYPE_PHONE -> "phone"
        Entity.TYPE_TRACKING_NUMBER -> "tracking_number"; Entity.TYPE_URL -> "url"; else -> "unknown"
    }

    private fun details(e: Entity): String? = when (e.type) {
        Entity.TYPE_DATE_TIME -> e.asDateTimeEntity()?.timestampMillis?.let { OffloadDates.format(java.time.Instant.ofEpochMilli(it)) }
        Entity.TYPE_MONEY -> e.asMoneyEntity()?.let { "${it.unnormalizedCurrency} ${it.integerPart}.${"%02d".format(it.fractionalPart)}" }
        else -> null
    }

    private fun <T> await(t: Task<T>, seconds: Long = 30): T = Tasks.await(t, seconds, TimeUnit.SECONDS)

    companion object {
        private const val TOOL = "android-nlp"

        private val UNSUPPORTED = mapOf(
            "pos" to "Part-of-speech tagging has no on-device engine on Android.",
            "sentiment" to "Sentiment analysis has no on-device engine on Android. Use the chat model for sentiment.",
            "embed" to "Text embeddings have no on-device engine on Android. Use an embeddings API through a configured provider.",
        )

        private val ENTITY_LANGUAGES = mapOf(
            "ar" to EntityExtractorOptions.ARABIC, "zh" to EntityExtractorOptions.CHINESE, "nl" to EntityExtractorOptions.DUTCH,
            "en" to EntityExtractorOptions.ENGLISH, "fr" to EntityExtractorOptions.FRENCH, "de" to EntityExtractorOptions.GERMAN,
            "it" to EntityExtractorOptions.ITALIAN, "ja" to EntityExtractorOptions.JAPANESE, "ko" to EntityExtractorOptions.KOREAN,
            "pl" to EntityExtractorOptions.POLISH, "pt" to EntityExtractorOptions.PORTUGUESE, "ru" to EntityExtractorOptions.RUSSIAN,
            "es" to EntityExtractorOptions.SPANISH, "th" to EntityExtractorOptions.THAI, "tr" to EntityExtractorOptions.TURKISH,
        )

        private val HELP = """android-nlp — language detection, tokenizing, entity extraction (JSON)

USAGE:
  android-nlp <command> --text <string>       (or --file <path>)

COMMANDS:
  language    Detect the language of the text (with alternatives)
  tokenize    Split into words, sentences or paragraphs   (--unit word|sentence|paragraph, default word)
  ner         Extract entities: dates, addresses, e-mail, phone, URL, money, flight/tracking numbers, IBAN, ISBN
              (not person/organisation names; the language model downloads on first use)
  analyze     language + token/sentence counts + ner

NOT SUPPORTED ON ANDROID (iOS-only):  pos, sentiment, embed

OPTIONS:
  --text <string>   Input text
  --file <path>     Read the input from a file (the shell has no stdin for this tool)
  --compact, -q     Single-line JSON / data field only

EXAMPLES:
  android-nlp language --text "Bonjour le monde"
  android-nlp tokenize --text "Hello world. How are you?" --unit sentence
  android-nlp ner --text "Meet me at 5 pm tomorrow, call 010-1234-5678"
"""
    }
}
