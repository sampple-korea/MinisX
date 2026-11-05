package com.minisx.app.data

import android.content.Context

/**
 * The models the user starred in the model picker, newest first; they are listed at the top of the picker.
 * Stored as entry ids, so a model that disappears simply stops showing.
 */
object ModelFavorites {
    private const val PREFS = "model_picker"
    private const val KEY = "favorite_entry_ids"
    private const val SEP = "\n"
    const val MAX = 30

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split(SEP).filter { it.isNotBlank() }

    fun save(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, ids.joinToString(SEP)).apply()
    }

    /** [ids] with [id] added at the front, or removed when it was already there. */
    fun toggled(ids: List<String>, id: String): List<String> =
        if (id in ids) ids - id else (listOf(id) + ids).take(MAX)
}
