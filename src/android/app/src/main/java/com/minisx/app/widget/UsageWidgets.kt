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
        // Under ~118dp the full layout (title row, number, gauge, extra rows, footer) does not fit and
        // the footer was cut off; the one-line layout below is built for that.
        val tiny = size.height < 118.dp && model.state == UsageWidgetModel.State.READY
        val wide = size.width >= 200.dp
        val link = when {
            model.instanceId != null -> "minis://settings/providers/${model.instanceId}"
            else -> "minis://settings/providers"
        }
        val refresh = model.instanceId?.let {
            actionRunCallback<RefreshUsageAction>(actionParametersOf(RefreshUsageAction.INSTANCE to it))
        }
        val frame = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp)
            .clickable(actionStartActivity(UsageWidgets.deepLink(context, link)))
        if (tiny) {
            Row(frame.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TinyReady(context, model, name, refresh, showReset = size.height >= 60.dp)
            }
            return
        }
        Column(frame.padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (refresh != null) {
                    Text(
                        "↻",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
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

    /** One line for a 2x1 widget: the number, the gauge, and the refresh glyph. */
    @Composable
    private fun RowScope.TinyReady(
        context: Context, model: UsageWidgetModel, name: String, refresh: androidx.glance.action.Action?, showReset: Boolean,
    ) {
        val top = model.tightest ?: return
        val low = UsageWidgetModel.isLow(top.remainingPercent)
        Text(
            "${top.remainingPercent.roundToInt()}%",
            style = TextStyle(
                color = if (low) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$name · ${top.window}", maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
            Spacer(GlanceModifier.height(3.dp))
            Gauge(top.remainingPercent, 8, 6.dp, GlanceModifier.fillMaxWidth())
            val resets = top.resetsAt?.takeIf { showReset }?.let { context.getString(R.string.usage_widget_resets, QuotaText.relative(context, it)) }
            if (resets != null) {
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    resets, maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                )
            }
        }
        if (refresh != null) {
            Text(
                "↻",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 16.sp),
                modifier = GlanceModifier.padding(start = 8.dp).clickable(refresh),
            )
        }
    }

    @Composable
    private fun Message(text: String) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 4)
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
                    color = if (low) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                    fontSize = if (roomy) 36.sp else 28.sp, fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                top.window, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
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
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    modifier = GlanceModifier.width(40.dp),
                )
                Gauge(limit.remainingPercent, 10, 6.dp, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    "${limit.remainingPercent.roundToInt()}%", maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
        }
        Spacer(GlanceModifier.height(if (roomy) 10.dp else 6.dp))
        val resets = top.resetsAt?.let { context.getString(R.string.usage_widget_resets, QuotaText.relative(context, it)) }
        val updated = model.updatedAt.takeIf { it > 0 }?.let { context.getString(R.string.usage_widget_updated, QuotaText.relative(context, it)) }
        Text(
            listOfNotNull(resets, updated).joinToString(" · "), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
        )
    }

    /** Segmented gauge; the segments that are left fill from the start. */
    @Composable
    private fun Gauge(remaining: Double, segments: Int, height: Dp, modifier: GlanceModifier) {
        val filled = if (remaining <= 0.0) 0 else ceil(remaining / 100.0 * segments).toInt().coerceIn(1, segments)
        val on = if (UsageWidgetModel.isLow(remaining)) GlanceTheme.colors.error else GlanceTheme.colors.primary
        Row(modifier) {
            for (i in 0 until segments) {
                Box(
                    GlanceModifier.defaultWeight().height(height).cornerRadius(3.dp)
                        .background(if (i < filled) on else GlanceTheme.colors.surfaceVariant),
                ) {}
                if (i < segments - 1) Spacer(GlanceModifier.width(2.dp))
            }
        }
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
                val pools = st.instanceSubjects[inst.id]?.let { st.poolsOf(it) }.orEmpty()
                Triple(inst, UsageWidgetModel.limitsOf(pools, now, QuotaStore.freshnessPolicy), pools)
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
