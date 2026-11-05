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
import kotlin.math.ceil
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
        val frame = GlanceModifier.fillMaxSize().background(Palette.background).cornerRadius(22.dp)
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
                    UsageWidgetModel.State.READY -> Ready(context, model, wide, size.height)
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
                    val low = UsageWidgetModel.isLow(limit.remainingPercent)
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            limit.window, maxLines = 1,
                            style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                            modifier = GlanceModifier.width(28.dp),
                        )
                        Gauge(limit.remainingPercent, 10, 6.dp, GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            "${limit.remainingPercent.roundToInt()}%", maxLines = 1,
                            style = TextStyle(
                                color = if (low) Palette.warn else Palette.text,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            ),
                            modifier = GlanceModifier.width(36.dp),
                        )
                        if (wide) {
                            Text(
                                limit.resetsAt?.let { QuotaText.relative(context, it) }.orEmpty(), maxLines = 1,
                                style = TextStyle(color = Palette.muted, fontSize = 10.sp),
                                modifier = GlanceModifier.width(60.dp),
                            )
                        }
                        if (!titled && index == 0 && refresh != null) {
                            Text(
                                "↻",
                                style = TextStyle(color = Palette.muted, fontSize = 14.sp),
                                modifier = GlanceModifier.padding(start = 6.dp).clickable(refresh),
                            )
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
    private fun Ready(context: Context, model: UsageWidgetModel, wide: Boolean, height: Dp) {
        val top = model.tightest ?: return
        val low = UsageWidgetModel.isLow(top.remainingPercent)
        // The rows below the top window that fit: the top block needs about 130dp, each row about 30dp.
        val extraRows = ((height.value - 130f) / 30f).toInt().coerceIn(0, model.limits.size - 1)
        val roomy = height >= 200.dp
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "${top.remainingPercent.roundToInt()}%",
                style = TextStyle(
                    color = if (low) Palette.warn else Palette.text,
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
        Gauge(top.remainingPercent, if (wide) 10 else 8, if (roomy) 14.dp else 10.dp, GlanceModifier.fillMaxWidth())
        for (limit in model.limits.drop(1).take(extraRows)) {
            Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    limit.window, maxLines = 1,
                    style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                    modifier = GlanceModifier.width(40.dp),
                )
                Gauge(limit.remainingPercent, 10, 6.dp, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    "${limit.remainingPercent.roundToInt()}%", maxLines = 1,
                    style = TextStyle(color = Palette.muted, fontSize = 11.sp),
                )
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
     * A bar made of thin segments, so it reads as one continuous rounded bar in Glance (which
     * cannot weight two boxes unevenly). The part that is left fills from the start; it turns
     * amber when low and red when nearly gone.
     */
    @Composable
    private fun Gauge(remaining: Double, segments: Int, height: Dp, modifier: GlanceModifier) {
        val count = segments * 2
        val filled = if (remaining <= 0.0) 0 else ceil(remaining / 100.0 * count).toInt().coerceIn(1, count)
        val on = Palette.fill(remaining)
        Row(modifier) {
            for (i in 0 until count) {
                Box(
                    GlanceModifier.defaultWeight().height(height).cornerRadius(2.dp)
                        .background(if (i < filled) on else Palette.track),
                ) {}
                if (i < count - 1) Spacer(GlanceModifier.width(1.dp))
            }
        }
    }
}

/** The widgets' own palette: the app's violet on near-black, with a light twin for light mode. */
private object Palette {
    val background = ColorProvider(day = Color(0xFFF6F4FB), night = Color(0xFF141319))
    val text = ColorProvider(day = Color(0xFF1B1A22), night = Color(0xFFF2F0F8))
    val muted = ColorProvider(day = Color(0xFF6E6A7C), night = Color(0xFF9C98AD))
    val track = ColorProvider(day = Color(0xFFE2DEEE), night = Color(0xFF2B2A35))
    val accent = ColorProvider(day = Color(0xFF7A4DFF), night = Color(0xFF9B7BFF))
    val warn = ColorProvider(day = Color(0xFFB26A00), night = Color(0xFFFFB020))
    val danger = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFFF6B6B))

    fun fill(remainingPercent: Double): ColorProvider = when {
        remainingPercent <= 8.0 -> danger
        UsageWidgetModel.isLow(remainingPercent) -> warn
        else -> accent
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
