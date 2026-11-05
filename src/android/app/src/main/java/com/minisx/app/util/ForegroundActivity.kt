package com.minisx.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * The activity that is currently resumed, if any.
 *
 * Some Android APIs only work against a foreground [Activity] — NFC reader mode
 * ([android.nfc.NfcAdapter.enableReaderMode]) is the one that needs it today —
 * while the agent calls them from a background thread inside a shell command.
 * This is a weak reference, never kept past the activity's pause.
 */
object ForegroundActivity : Application.ActivityLifecycleCallbacks {

    @Volatile private var ref: WeakReference<Activity>? = null

    /** The resumed activity, or null when the app is in the background. */
    fun get(): Activity? = ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityResumed(activity: Activity) { ref = WeakReference(activity) }

    override fun onActivityPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
