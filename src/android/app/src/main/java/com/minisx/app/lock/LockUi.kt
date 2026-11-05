package com.minisx.app.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.minisx.app.R
import kotlinx.coroutines.launch

/** The [FragmentActivity] behind a Compose [Context], or null (the prompt needs one). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Full-screen cover shown while the app lock is engaged (and, briefly, while the
 * app is in the task switcher). Mount it last in the root so it sits on top of
 * everything, dialogs of the root excepted.
 */
@Composable
fun AppLockOverlay() {
    val locked by SessionLockStore.appIsLocked.collectAsState()
    val privacy by SessionLockStore.showPrivacyScreen.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticating by remember { mutableStateOf(false) }

    val unlock: () -> Unit = {
        val activity = context.findFragmentActivity()
        if (activity != null && !authenticating) {
            authenticating = true
            scope.launch {
                val ok = BiometricAuth.authenticate(
                    activity,
                    title = context.getString(R.string.biolock_prompt_app),
                    cancelLabel = context.getString(R.string.biolock_cancel),
                )
                if (ok) SessionLockStore.noteAppUnlock()
                authenticating = false
            }
        }
    }

    if (locked) {
        // Prompt as soon as the cover appears; the button covers a dismissed prompt.
        LaunchedEffect(Unit) { unlock() }
        LockCover(
            title = stringResource(R.string.biolock_app_locked_title),
            buttonLabel = stringResource(R.string.biolock_unlock),
            enabled = !authenticating,
            onUnlock = unlock,
        )
    } else if (privacy) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } })
    }
}

/**
 * Wraps a chat. While the chat is locked and not unlocked, shows the lock cover
 * (and prompts) instead of [content]; the chat behind it is not composed, so it
 * neither renders nor leaks its content to accessibility services.
 */
@Composable
fun SessionLockGate(sessionId: String, content: @Composable () -> Unit) {
    val version by SessionLockStore.version.collectAsState()
    val locked = remember(version, sessionId) { SessionLockStore.isVisuallyLocked(sessionId) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticating by remember { mutableStateOf(false) }

    val unlock: () -> Unit = {
        val activity = context.findFragmentActivity()
        if (activity != null && !authenticating) {
            authenticating = true
            scope.launch {
                val ok = BiometricAuth.authenticate(
                    activity,
                    title = context.getString(R.string.biolock_prompt_chat),
                    cancelLabel = context.getString(R.string.biolock_cancel),
                )
                if (ok) SessionLockStore.noteUnlock(sessionId)
                authenticating = false
            }
        }
    }

    // Leaving the chat lets an expired idle window re-lock it (iOS onDisappear).
    DisposableEffect(sessionId) {
        onDispose { SessionLockStore.evictIdleExpired() }
    }

    if (locked) {
        LaunchedEffect(sessionId) { unlock() }
        LockCover(
            title = stringResource(R.string.biolock_chat_locked_title),
            buttonLabel = stringResource(R.string.biolock_unlock),
            enabled = !authenticating,
            onUnlock = unlock,
        )
    } else {
        content()
    }
}

@Composable
private fun LockCover(title: String, buttonLabel: String, enabled: Boolean, onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches so nothing underneath can be reached.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                Icons.Filled.Lock, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock, enabled = enabled, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(buttonLabel)
            }
        }
    }
}
