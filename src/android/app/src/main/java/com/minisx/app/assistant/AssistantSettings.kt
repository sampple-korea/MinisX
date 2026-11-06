package com.minisx.app.assistant

import android.content.Context
import android.content.SharedPreferences

/** Settings > Assistant. Everything defaults to on: the assistant is meant to see the screen. */
object AssistantSettings {
    private const val PREFS = "minisx_assistant"
    private const val K_SCREENSHOT = "screenshot"
    private const val K_SCREEN_TEXT = "screen_text"
    private const val K_FAST_PATH = "fast_path"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Capture the screen on every invocation and send it with the first message. */
    var screenshot: Boolean
        get() = prefs?.getBoolean(K_SCREENSHOT, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(K_SCREENSHOT, v)?.apply() }

    /** Send the text on the screen too (works for models that take no images). */
    var screenText: Boolean
        get() = prefs?.getBoolean(K_SCREEN_TEXT, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(K_SCREEN_TEXT, v)?.apply() }

    /** Run plain phone commands ("turn the flashlight on") without asking the model. */
    var fastPath: Boolean
        get() = prefs?.getBoolean(K_FAST_PATH, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(K_FAST_PATH, v)?.apply() }
}
