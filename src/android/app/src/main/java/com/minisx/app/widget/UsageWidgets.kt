package com.minisx.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.minisx.app.MinisApp
import com.minisx.app.R
import com.minisx.app.quota.QuotaStore
import com.minisx.app.quota.QuotaVendor
import com.minisx.app.quota.QuotaVendorDetector
import com.minisx.app.ui.quota.QuotaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Subscription-usage widgets: one per service, showing how much of the tightest
 * allowance window is left, a segmented gauge, the next windows, and when it resets.
 *
 * Counterpart of Mobile Codex's usage widgets. The numbers are the ones the
 * in-app quota section already holds ([QuotaStore]); a widget adds no new network
 * path of its own beyond asking the store to refresh when it is due.
 *
 * Tapping the body opens that provider's settings; the refresh glyph forces a
 * refresh of just this service (the store still applies its own rate gates).
 */
abstract class UsageWidget(
    private val vendor: QuotaVendor,
    private val nameRes: Int,
) : GlanceAppWidget() {

    // Exact: the widget is laid out for the size the launcher gives it, and redrawn whenever the user
    // resizes it. (Responsive picked one of four fixed layouts, so a resized widget kept the look of
    // the nearest one - it did not follow the frame down to a smaller size.)
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = UsageWidgets.load(context, vendor)
        // Ask for fresh numbers when the store thinks they are due; the result
        // arrives through QuotaStore.state and re-renders every usage widget.
        model.instanceId?.let { QuotaStore.refreshIfDue(it, "widget") }
        val name = context.getString(nameRes)
        provideContent { GlanceTheme { Content(context, name, model) } }
    }

    @Composable
    private fun Content(context: Context, name: String, model: UsageWidgetModel) {
        val size = LocalSize.current
        val wide = size.width >= 200.dp
        val link = when {
            model.instanceId != null -> "minis://settings/providers/${model.instanceId}"
            else -> "minis://settings/providers"
        }
        val refresh = model.instanceId?.let {
            actionRunCallback<RefreshUsageAction>(actionParametersOf(RefreshUsageAction.INSTANCE to it))
        }
        // Frosted glass (drawable / drawable-night) instead of an opaque card, so the wallpaper shows
        // through; text and the gauge use the wallpaper-derived Material You colours.
        val frame = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_glass)).cornerRadius(CORNER)
            .clickable(actionStartActivity(UsageWidgets.deepLink(context, link)))
        // Under ~160dp the full layout (title, big number, gauge, extra rows, footer) does not fit. The
        // compact layout shows every window (5h and weekly) as one gauge row each instead.
        if (size.height < 160.dp && model.state == UsageWidgetModel.State.READY) {
            CompactReady(context, frame, model, name, refresh, size.width, size.height)
            return
        }
        Column(frame.padding(14.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, maxLines = 1,
                    style = TextStyle(color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (refresh != null) {
                    Text(
                        "↻",
                        style = TextStyle(color = Palette.muted, fontSize = 16.sp),
                        modifier = GlanceModifier.padding(horizontal = 4.dp).clickable(refresh),
                    )
                }
            }
            // The body takes the space that is left and sits in the middle of it, so a tall widget
            // does not end up with its content stuck at the top and an empty block underneath.
            Column(
                GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (model.state) {
                    UsageWidgetModel.State.NO_PROVIDER -> Message(context.getString(R.string.usage_widget_no_provider, name))
                    UsageWidgetModel.State.NO_DATA -> Message(context.getString(R.string.usage_widget_no_data))
                    UsageWidgetModel.State.READY -> Ready(context, model, size.width, size.height)
                }
            }
        }
    }

    /**
     * The small layouts: one gauge row per window (the 5-hour and the weekly one, shortest first), a
     * title row when there is room, and a reset line when there is more room.
     */
    @Composable
    private fun CompactReady(
        context: Context,
        frame: GlanceModifier,
        model: UsageWidgetModel,
        name: String,
        refresh: androidx.glance.action.Action?,
        width: Dp,
        height: Dp,
    ) {
        val rows = model.limits.sortedBy { UsageWidgetModel.windowMinutes(it.window) }.take(if (height < 90.dp) 2 else 3)
        val titled = height >= 90.dp
        val wide = width >= 200.dp
        val glyphInRow = !titled && refresh != null
        // Everything in a row but the bar has a fixed width, so the bar's width is what is left.
        val bar = UsageGauge.barWidth(
            rowWidth = width.value - 2 * 12f,
            reserved = LABEL_W + GAP + PERCENT_W + (if (wide) RESET_W else 0f) + (if (glyphInRow) GLYPH_W else 0f),
        ).dp
        Column(frame.padding(horizontal = 12.dp, vertical = if (height < 70.dp) 5.dp else 10.dp)) {
            if (titled) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val pooled = rows.firstOrNull { it.accounts > 1 }?.accounts
                    Text(
                        if (pooled != null) "$name · ${context.getString(R.string.usage_widget_accounts, pooled)}" else name,
                        maxLines = 1,
                        style = TextStyle(color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    if (refresh != null) {
                        Text(
                            "↻",
                            style = TextStyle(color = Palette.muted, fontSize = 16.sp),
                            modifier = GlanceModifier.padding(horizontal = 4.dp).clickable(refresh),
                        )
                    }
                }
            }
            Column(
                GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rows.forEachIndexed { index, limit ->
                    if (index > 0) Spacer(GlanceModifier.height(if (height < 70.dp) 3.dp else 6.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            limit.window, maxLines = 1,
                            style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                            modifier = GlanceModifier.width(LABEL_W.dp),
                        )
                        Gauge(limit.remainingPercent, bar, 7.dp)
                        Spacer(GlanceModifier.width(GAP.dp))
                        Text(
                            "${limit.remainingPercent.roundToInt()}%", maxLines = 1,
                            style = TextStyle(
                                color = Palette.value(limit.remainingPercent),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            ),
                            modifier = GlanceModifier.width(PERCENT_W.dp),
                        )
                        if (wide) {
                            Text(
                                limit.resetsAt?.let { QuotaText.relative(context, it) }.orEmpty(), maxLines = 1,
                                style = TextStyle(color = Palette.muted, fontSize = 10.sp),
                                modifier = GlanceModifier.width(RESET_W.dp),
                            )
                        }
                        if (glyphInRow) {
                            // Every row keeps the glyph's width so the bars line up; only the first shows it.
                            if (index == 0) {
                                Text(
                                    "↻",
                                    style = TextStyle(color = Palette.muted, fontSize = 14.sp),
                                    modifier = GlanceModifier.width(GLYPH_W.dp).padding(start = 6.dp).clickable(refresh),
                                )
                            } else {
                                Spacer(GlanceModifier.width(GLYPH_W.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun windowText(context: Context, limit: UsageLimit): String =
        if (limit.accounts > 1) "${limit.window} · ${context.getString(R.string.usage_widget_accounts, limit.accounts)}" else limit.window

    @Composable
    private fun Message(text: String) {
        Text(text, style = TextStyle(color = Palette.muted, fontSize = 13.sp), maxLines = 4)
    }

    @Composable
    private fun Ready(context: Context, model: UsageWidgetModel, width: Dp, height: Dp) {
        val top = model.tightest ?: return
        val inner = width.value - 2 * 14f
        // The rows below the top window that fit: the top block needs about 130dp, each row about 30dp.
        // A Glance Row/Column keeps only its first 10 children: the extra rows share one inner column, capped at 4.
        val extraRows = ((height.value - 130f) / 30f).toInt().coerceIn(0, minOf(model.limits.size - 1, 4))
        val roomy = height >= 200.dp
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "${top.remainingPercent.roundToInt()}%",
                style = TextStyle(
                    color = Palette.value(top.remainingPercent),
                    fontSize = if (roomy) 36.sp else 28.sp, fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                windowText(context, top), maxLines = 1,
                style = TextStyle(color = Palette.muted, fontSize = 12.sp),
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )
        }
        Spacer(GlanceModifier.height(if (roomy) 8.dp else 4.dp))
        Gauge(top.remainingPercent, UsageGauge.barWidth(inner, reserved = 0f).dp, if (roomy) 12.dp else 9.dp)
        if (extraRows > 0) Column(GlanceModifier.fillMaxWidth()) {
            for (limit in model.limits.drop(1).take(extraRows)) {
                Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        limit.window, maxLines = 1,
                        style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                        modifier = GlanceModifier.width(LABEL_W.dp),
                    )
                    Gauge(limit.remainingPercent, UsageGauge.barWidth(inner, LABEL_W + GAP + PERCENT_W).dp, 7.dp)
                    Spacer(GlanceModifier.width(GAP.dp))
                    Text(
                        "${limit.remainingPercent.roundToInt()}%", maxLines = 1,
                        style = TextStyle(color = Palette.value(limit.remainingPercent), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.width(PERCENT_W.dp),
                    )
                }
            }
        }
        Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
        val resets = top.resetsAt?.let { context.getString(R.string.usage_widget_resets, QuotaText.relative(context, it)) }
        val updated = model.updatedAt.takeIf { it > 0 }?.let { context.getString(R.string.usage_widget_updated, QuotaText.relative(context, it)) }
        Text(
            listOfNotNull(resets, updated).joinToString(" · "), maxLines = 1,
            style = TextStyle(color = Palette.muted, fontSize = 10.sp),
        )
    }

    /**
     * One rounded bar: a track of exactly [width] with the part that is left drawn over it, green-ish
     * (the wallpaper accent) while there is plenty, amber when low and red when nearly gone.
     */
    @Composable
    private fun Gauge(remaining: Double, width: Dp, height: Dp) {
        val fill = UsageGauge.fillWidth(remaining, width.value, height.value)
        Box(GlanceModifier.width(width).height(height).cornerRadius(height / 2).background(Palette.track)) {
            if (fill > 0f) {
                Box(GlanceModifier.width(fill.dp).height(height).cornerRadius(height / 2).background(Palette.fill(remaining))) {}
            }
        }
    }

    private companion object {
        val CORNER = 28.dp
        // Fixed widths (dp) of the parts of a gauge row, so the bar can be given exactly what is left.
        const val LABEL_W = 28f
        const val GAP = 6f
        const val PERCENT_W = 36f
        const val RESET_W = 60f
        const val GLYPH_W = 24f
    }
}

/**
 * The widgets' colours. Text and the healthy fill come from the Material You scheme (they follow the
 * wallpaper); the warning colours are the app's quota colours. The card itself is `widget_glass`.
 */
private object Palette {
    val text: ColorProvider @Composable get() = GlanceTheme.colors.onSurface
    val muted: ColorProvider @Composable get() = GlanceTheme.colors.onSurfaceVariant
    val accent: ColorProvider @Composable get() = GlanceTheme.colors.primary
    val track = ColorProvider(day = Color(0x26000000), night = Color(0x33FFFFFF))
    val warn = ColorProvider(day = Color(0xFFC77700), night = Color(0xFFFF9F0A))
    val danger = ColorProvider(day = Color(0xFFD92D20), night = Color(0xFFFF453A))

    @Composable
    fun fill(remainingPercent: Double): ColorProvider = when (UsageGauge.tone(remainingPercent)) {
        UsageGauge.Tone.CRITICAL -> danger
        UsageGauge.Tone.CAUTION -> warn
        UsageGauge.Tone.HEALTHY -> accent
    }

    /** The percentage text: plain while healthy, coloured like the bar once it is low. */
    @Composable
    fun value(remainingPercent: Double): ColorProvider = when (UsageGauge.tone(remainingPercent)) {
        UsageGauge.Tone.CRITICAL -> danger
        UsageGauge.Tone.CAUTION -> warn
        UsageGauge.Tone.HEALTHY -> text
    }
}

class CodexUsageWidget : UsageWidget(QuotaVendor.codexSubscription, R.string.usage_widget_name_codex)
class ClaudeUsageWidget : UsageWidget(QuotaVendor.claudeSubscription, R.string.usage_widget_name_claude)
class KimiUsageWidget : UsageWidget(QuotaVendor.kimiCode, R.string.usage_widget_name_kimi)

class CodexUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexUsageWidget()
}

class ClaudeUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClaudeUsageWidget()
}

class KimiUsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KimiUsageWidget()
}

/** The refresh glyph: force-refresh this service, then redraw. */
class RefreshUsageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val instanceId = parameters[INSTANCE]
        // The store also asks the provider's extra ChatGPT accounts, so the pooled figure moves too.
        if (instanceId != null) QuotaStore.refresh(instanceId, forced = true, reason = "widget-refresh")
        UsageWidgets.updateAll(context)
    }

    companion object {
        val INSTANCE = ActionParameters.Key<String>("instance")
    }
}

object UsageWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Redraw the widgets whenever the quota numbers change (a refresh, a response
     * header, a reset), batched so a burst is one redraw.
     */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch {
            QuotaStore.state.map { it.pools }.distinctUntilChanged().debounce(3_000).collect {
                runCatching { updateAll(app) }
            }
        }
    }

    /** Redraws every placed usage widget (cheap when none is placed). */
    suspend fun updateAll(context: Context) {
        CodexUsageWidget().updateAll(context)
        ClaudeUsageWidget().updateAll(context)
        KimiUsageWidget().updateAll(context)
    }

    /** The model for [vendor]: the enabled provider of that kind with the most usable windows. */
    suspend fun load(context: Context, vendor: QuotaVendor): UsageWidgetModel {
        val app = context.applicationContext as MinisApp
        QuotaStore.awaitLoaded()
        app.providerRepository.awaitConfigLoaded()
        val instances = app.providerRepository.config.value.instances.filter {
            it.isEnabled && QuotaVendorDetector.vendor(QuotaStore.descriptor(it)) == vendor
        }
        if (instances.isEmpty()) return UsageWidgetModel(UsageWidgetModel.State.NO_PROVIDER)

        val now = System.currentTimeMillis()
        val st = QuotaStore.state.value
        val best = instances
            .map { inst ->
                val sid = st.instanceSubjects[inst.id]
                val pools = sid?.let { st.poolsOf(it) }.orEmpty()
                // [T-codex-account-pool] The store's pools are already pooled across the provider's
                // ChatGPT accounts; say how many.
                Triple(inst, UsageWidgetModel.limitsOf(pools, now, QuotaStore.freshnessPolicy, st.pooledAccounts[sid] ?: 1), pools)
            }
            .maxByOrNull { it.second.size } ?: return UsageWidgetModel(UsageWidgetModel.State.NO_PROVIDER)
        val (inst, limits, pools) = best
        if (limits.isEmpty()) return UsageWidgetModel(UsageWidgetModel.State.NO_DATA, instanceId = inst.id)
        val updated = pools.flatMap { it.windows }.maxOfOrNull { it.observedAt } ?: 0L
        return UsageWidgetModel(UsageWidgetModel.State.READY, limits, updated, inst.id)
    }

    fun deepLink(context: Context, link: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(context.packageName, "com.minisx.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
