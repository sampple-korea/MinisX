package com.openminis.app.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Biometric lock state: per-chat locks and the app-level lock.
 *
 * Android port of iOS `SessionLockStore` (Agent/Session/SessionLockStore.swift),
 * with the same rules:
 *
 *  - Which chats are locked, and the settings, are persisted; the lock lives
 *    beside the chat, never in the chat database, so exports and backups do not
 *    carry it.
 *  - Unlocks are kept in memory only. A locked chat counts as unlocked while its
 *    last unlock is younger than the idle timeout (`0` = until the app process
 *    ends, `-1` = "lock on exit": dropped when the app goes to the background).
 *  - The app lock re-locks on a cold start, after the idle timeout, or — for
 *    "lock on exit" — after a background spell of at least
 *    [LOCK_ON_EXIT_GRACE_MS] (shorter blips such as a notification shade pull do
 *    not re-prompt).
 *
 * Compose reads [version], which changes on every state change, and then calls
 * the query functions; nothing here is a hidden dependency of a composable.
 */
object SessionLockStore {
    private const val PREFS = "session_lock"
    private const val KEY_ENABLED = "faceIDProtectionEnabled"
    private const val KEY_IDLE = "faceIDProtectionIdleSeconds"
    private const val KEY_LOCKED_IDS = "faceIDProtectionLockedSessionIds"
    private const val KEY_APP_ENABLED = "faceIDAppLockEnabled"
    private const val KEY_APP_IDLE = "faceIDAppLockIdleSeconds"

    const val LOCK_ON_EXIT_GRACE_MS = 15_000L

    /** Idle choices offered in Settings, in seconds. -1 = lock on exit, 0 = never. */
    val IDLE_OPTIONS = listOf(-1, 30, 60, 300, 600, 900, 3600, 0)

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val locked = MutableStateFlow<Set<String>>(emptySet())
    private val unlockedAt = HashMap<String, Long>()
    private val _version = MutableStateFlow(0)

    /** Bumped on every change; collect it, then re-query. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _appIsLocked = MutableStateFlow(false)
    val appIsLocked: StateFlow<Boolean> = _appIsLocked.asStateFlow()

    private val _showPrivacyScreen = MutableStateFlow(false)
    val showPrivacyScreen: StateFlow<Boolean> = _showPrivacyScreen.asStateFlow()

    private var appUnlockedAt: Long? = null
    private var appBackgroundedAt: Long? = null

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        locked.value = loadLocked()
        // A cold start with the app lock on begins locked.
        _appIsLocked.value = appLockEnabled && BiometricAuth.isAvailable(appContext)
        // Anything that already filtered by lock state (shortcuts, widget) re-reads now.
        bump()
    }

    private fun bump() { _version.value = _version.value + 1 }
    private fun now() = SystemClock.elapsedRealtime()

    // ── Preferences ─────────────────────────────────────────────────────────

    /** Master switch for per-chat locks; the locked set itself is kept while it is off. */
    var globalEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); bump() }

    var idleTimeoutSeconds: Int
        get() = prefs.getInt(KEY_IDLE, 300)
        set(v) { prefs.edit().putInt(KEY_IDLE, v).apply(); bump() }

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_ENABLED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_APP_ENABLED, v).apply()
            if (!v) { _appIsLocked.value = false; appUnlockedAt = null }
            bump()
        }

    var appLockIdleSeconds: Int
        get() = prefs.getInt(KEY_APP_IDLE, -1)
        set(v) { prefs.edit().putInt(KEY_APP_IDLE, v).apply(); bump() }

    // ── Chat locks ──────────────────────────────────────────────────────────

    fun isLocked(sessionId: String): Boolean = sessionId in locked.value

    /** True when the chat is not locked, or was unlocked and the idle window has not elapsed. */
    fun isCurrentlyUnlocked(sessionId: String): Boolean {
        if (!isLocked(sessionId)) return true
        val stamp = unlockedAt[sessionId] ?: return false
        val idle = idleTimeoutSeconds
        if (idle <= 0) return true // 0 = never re-lock this run; -1 is cleared on background
        return now() - stamp < idle * 1000L
    }

    /** The single "render this chat as locked" test shared by the list row and the chat gate. */
    fun isVisuallyLocked(sessionId: String): Boolean {
        if (!::prefs.isInitialized || !globalEnabled) return false
        if (!isLocked(sessionId)) return false
        return !isCurrentlyUnlocked(sessionId)
    }

    fun lock(sessionId: String) {
        if (sessionId.isEmpty()) return
        if (locked.value.contains(sessionId)) return
        locked.value = locked.value + sessionId
        unlockedAt.remove(sessionId)
        persistLocked(); bump()
    }

    /** Removes the lock for good. The caller must already have passed the prompt. */
    fun unlockPermanently(sessionId: String) {
        if (!locked.value.contains(sessionId)) return
        locked.value = locked.value - sessionId
        unlockedAt.remove(sessionId)
        persistLocked(); bump()
    }

    /** A successful prompt: keep the lock, start the idle clock. */
    fun noteUnlock(sessionId: String) {
        if (!isLocked(sessionId)) return
        unlockedAt[sessionId] = now()
        bump()
    }

    fun clearUnlock(sessionId: String) {
        if (unlockedAt.remove(sessionId) != null) bump()
    }

    fun clearAllUnlocks() {
        if (unlockedAt.isEmpty()) return
        unlockedAt.clear(); bump()
    }

    /** Re-lock chats whose idle window ran out; call on returning to the foreground. */
    fun evictIdleExpired() {
        val idle = idleTimeoutSeconds
        if (idle <= 0 || unlockedAt.isEmpty()) return
        val cutoff = now() - idle * 1000L
        if (unlockedAt.values.removeAll { it <= cutoff }) bump()
    }

    // ── App lock ────────────────────────────────────────────────────────────

    fun evaluateAppLock() {
        if (!::prefs.isInitialized) return
        _showPrivacyScreen.value = false
        val backgroundedFor = appBackgroundedAt?.let { now() - it }
        appBackgroundedAt = null

        if (!appLockEnabled || !BiometricAuth.isAvailable(appContext)) { _appIsLocked.value = false; return }
        val stamp = appUnlockedAt
        if (stamp == null) { _appIsLocked.value = true; return }
        val idle = appLockIdleSeconds
        when {
            idle < 0 -> if (backgroundedFor != null && backgroundedFor >= LOCK_ON_EXIT_GRACE_MS) {
                appUnlockedAt = null
                _appIsLocked.value = true
            } else _appIsLocked.value = false
            idle == 0 -> _appIsLocked.value = false
            else -> _appIsLocked.value = now() - stamp >= idle * 1000L
        }
    }

    fun noteAppUnlock() { appUnlockedAt = now(); _appIsLocked.value = false }

    /** The app went to the background: remember when, and cover the task-switcher snapshot. */
    fun noteAppBackgrounded() {
        if (!::prefs.isInitialized) return
        appBackgroundedAt = now()
        if (appLockEnabled) _showPrivacyScreen.value = true
        if (idleTimeoutSeconds < 0) clearAllUnlocks() // chat "lock on exit"
    }

    /** The activity paused (task switcher, a dialog activity): cover the snapshot the system takes now. */
    fun notePaused() {
        if (!::prefs.isInitialized) return
        if (appLockEnabled) _showPrivacyScreen.value = true
    }

    /** The activity resumed: re-evaluate the app lock and let expired chat unlocks lapse. */
    fun onForeground() {
        if (!::prefs.isInitialized) return
        evaluateAppLock()
        evictIdleExpired()
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun loadLocked(): Set<String> {
        val raw = prefs.getString(KEY_LOCKED_IDS, null) ?: return emptySet()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun persistLocked() {
        prefs.edit().putString(KEY_LOCKED_IDS, JSONArray(locked.value.toList()).toString()).apply()
    }
}
