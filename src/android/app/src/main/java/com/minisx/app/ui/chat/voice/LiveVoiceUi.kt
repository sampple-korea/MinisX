package com.minisx.app.ui.chat.voice

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.minisx.app.R
import com.minisx.app.data.repository.ProviderRepository
import com.minisx.app.live.LiveCall
import com.minisx.app.live.LiveSession
import com.minisx.app.live.LiveSetup
import com.minisx.app.live.RealtimeProtocol
import com.minisx.app.ui.chat.ChatViewModel
import com.minisx.app.ui.chat.runLiveTask
import com.minisx.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The Live button next to the microphone: starts a spoken conversation with the
 * model (tap), re-opens the call panel while one is running (tap), and picks the
 * voice for the next call (long press).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveVoiceButton(
    sessionId: String,
    viewModel: ChatViewModel,
    providerRepository: ProviderRepository,
    ensureMicPermission: suspend () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by LiveCall.current.collectAsState()
    val active = current != null
    var voiceMenu by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(LiveSetup.voice(context)) }

    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else ChatColors.inputIconBg
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(bg, CircleShape)
                .border(0.5.dp, if (active) Color.Transparent else ChatColors.inputIconBorder, CircleShape)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        if (active) {
                            LiveCall.setMinimized(false)
                        } else {
                            scope.launch {
                                if (!ensureMicPermission()) return@launch
                                when (val r = LiveSetup.resolve(context, providerRepository)) {
                                    LiveSetup.Resolved.NoProvider ->
                                        Toast.makeText(context, R.string.live_error_no_provider, Toast.LENGTH_LONG).show()
                                    is LiveSetup.Resolved.Ok -> {
                                        val started = LiveCall.start(context, r.config, sessionId) { viewModel.runLiveTask(it) }
                                        if (started is LiveCall.StartResult.Refused) {
                                            Toast.makeText(context, started.reason, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onLongClick = { voiceMenu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = stringResource(R.string.live_button_desc),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
            Text(
                stringResource(R.string.live_voice_menu_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            RealtimeProtocol.VOICES.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.replaceFirstChar { it.uppercase() }) },
                    trailingIcon = { if (v == voice) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        voice = v
                        LiveSetup.setVoice(context, v)
                        voiceMenu = false
                    },
                )
            }
        }
    }
}

/** The call panel for the chat that owns the running call, plus its one-shot error toast. */
@Composable
internal fun LiveVoiceHost(sessionId: String) {
    val context = LocalContext.current
    val session by LiveCall.current.collectAsState()
    val minimized by LiveCall.minimized.collectAsState()
    val error by LiveCall.lastError.collectAsState()

    LaunchedEffect(error) {
        LiveCall.consumeError()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    val s = session
    if (s != null && !minimized && LiveCall.ownerSessionId == sessionId) LiveVoicePanel(s)
}

@Composable
private fun LiveVoicePanel(session: LiveSession) {
    val state by session.state.collectAsState()
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            elapsedSeconds = if (state.startedAt > 0) (System.currentTimeMillis() - state.startedAt) / 1000 else 0
            delay(1000)
        }
    }

    Dialog(
        onDismissRequest = { LiveCall.setMinimized(true) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { LiveCall.setMinimized(true) }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.live_minimize))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Orb(state)
                }

                Text(
                    when {
                        state.phase == LiveSession.Phase.CONNECTING -> stringResource(R.string.live_connecting)
                        state.working -> stringResource(R.string.live_working)
                        state.muted -> stringResource(R.string.live_muted)
                        else -> stringResource(R.string.live_listening)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.TopCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.userCaption.isNotBlank()) {
                            Text(
                                state.userCaption, textAlign = TextAlign.Center, maxLines = 2,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.assistantCaption.isNotBlank()) {
                            Text(
                                state.assistantCaption, textAlign = TextAlign.Center, maxLines = 4,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { session.setMuted(!state.muted) },
                        modifier = Modifier.size(60.dp),
                        enabled = state.phase == LiveSession.Phase.CONNECTED,
                    ) {
                        Icon(
                            if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = stringResource(if (state.muted) R.string.live_unmute else R.string.live_mute),
                        )
                    }
                    FilledIconButton(
                        onClick = { LiveCall.end() },
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD93025), contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = stringResource(R.string.live_end), modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** A circle that swells with whoever is making sound: you, the model, or the agent at work. */
@Composable
private fun Orb(state: LiveSession.State) {
    val speaking = max(state.inputLevel, state.outputLevel)
    val level by animateFloatAsState(speaking, tween(90), label = "orb-level")
    val pulse by rememberInfiniteTransition(label = "orb-pulse").animateFloat(
        0.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orb-pulse-value",
    )
    val color = when {
        state.phase != LiveSession.Phase.CONNECTED -> MaterialTheme.colorScheme.outline
        state.working -> MaterialTheme.colorScheme.secondary
        state.outputLevel > state.inputLevel -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val idleScale = if (state.working || state.phase != LiveSession.Phase.CONNECTED) pulse else 1f
    Canvas(Modifier.size(260.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.28f
        val r = base * idleScale * (1f + 0.55f * level)
        drawCircle(color.copy(alpha = 0.10f), radius = r * 1.9f, center = center)
        drawCircle(color.copy(alpha = 0.20f), radius = r * 1.45f, center = center)
        drawCircle(color.copy(alpha = if (state.muted) 0.35f else 0.85f), radius = r, center = center)
    }
}
