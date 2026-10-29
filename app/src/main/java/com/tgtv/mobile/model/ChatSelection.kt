package com.tgtv.mobile.model

import android.content.Context

/**
 * The set of Telegram chats the library scans for movies/series. Chosen on
 * the first-run picker (after login) and editable from Settings. An unset
 * selection keeps the legacy behaviour: Saved Messages for user sessions,
 * the whole bot library for bot sessions.
 */
object ChatSelection {
    private const val PREFS = "chat_selection"
    private const val KEY_IDS = "chat_ids"
    private const val KEY_CONFIGURED = "configured"

    /** True once the user completed a pick at least once. */
    fun isConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONFIGURED, false)

    /** Selected chat ids (empty until [save]). */
    fun selected(context: Context): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun save(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_IDS, ids.map { it.toString() }.toSet())
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
    }
}
