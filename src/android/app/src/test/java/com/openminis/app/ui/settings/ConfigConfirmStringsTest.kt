package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-config-confirm-i18n] The minis-config approval dialog reads in the app
 * language, like iOS ConfigConfirmSheet did. Found on the Pixel 4a in Chinese:
 * the title, Cancel / Apply / Reject All and the risk hints were English
 * literals. The English wording is pinned here, and the config screens must
 * not grow new literals.
 */
class ConfigConfirmStringsTest {

    private val main = ProductionSources.mainRoot()!!
    private val res = File(main.parentFile.parentFile.parentFile.parentFile, "res")

    /** Android resource → the English wording of the dialog. */
    private val keys = mapOf(
        "config_confirm_title_one" to "Confirm change",
        "config_confirm_title_many" to "Confirm %lld changes",
        "config_confirm_cancel" to "Cancel",
        "config_confirm_apply" to "Apply",
        "config_confirm_reject_all" to "Reject All",
        "config_confirm_risk_destructive" to "This may affect later tool calls.",
        "config_confirm_risk_sensitive" to "Reversible, but worth a quick look.",
    )

    private val locales = mapOf(
        "zh-Hans" to "values-zh", "zh-Hant" to "values-zh-rTW", "hr" to "values-hr", "ja" to "values-ja",
        "ko" to "values-ko", "de" to "values-de", "es" to "values-es", "fr" to "values-fr",
        "pt-BR" to "values-pt-rBR", "ru" to "values-ru", "fil" to "values-fil", "id" to "values-in",
        "ms" to "values-ms", "pl" to "values-pl", "ro" to "values-ro", "th" to "values-th", "tr" to "values-tr",
    )

    private fun strings(dir: String): Map<String, String> {
        val f = File(res, "$dir/strings_config_confirm.xml")
        if (!f.exists()) return emptyMap()
        return Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }
    }

    @Test
    fun `the English dialog strings are the published wording`() {
        val problems = ArrayList<String>()
        val base = strings("values")
        for ((name, key) in keys) {
            if (base[name] != key.replace("%lld", "%1\$d")) problems += "values/$name = ${base[name]}"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the change count survives every translation`() {
        for (dir in listOf("values") + locales.values) {
            val v = strings(dir)["config_confirm_title_many"] ?: continue
            assertTrue("$dir: $v", v.contains("%1\$d"))
        }
    }

    @Test
    fun `no English literal left on the dialog or the Config Changes screens`() {
        val literal = Regex("""(?:Text\(|text = |applyText = if \([^)]*\) )"[A-Za-z][^"]*"""")
        for (f in listOf("ConfigConfirmDialog.kt", "ConfigAuditScreen.kt", "ConfigAuditDetailScreen.kt", "ConfigReadableUi.kt")) {
            val src = ProductionSources.read("ui/settings/$f")
            val hits = literal.findAll(src).map { it.value }.toList()
            assertEquals("$f has hardcoded UI text", emptyList<String>(), hits)
        }
    }

    @Test
    fun `rows keep iOS's verbatim parts and fade when switched off`() {
        val dialog = ProductionSources.read("ui/settings/ConfigConfirmDialog.kt")
        // iOS renders the verb badge and the field display name verbatim
        // (Text(item.verb.uppercased()) / Text(item.displayName)).
        assertTrue(dialog.contains("text = verb.uppercase()"))
        assertTrue(dialog.contains("text = item.displayName"))
        assertTrue(dialog.contains(".alpha(if (item.isApproved) 1f else 0.45f)"))
    }
}
