package com.tgtv.mobile.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-title watch progress ("Resume"), persisted in prefs as JSON - the same
 * pattern as [Watchlist]; the mobile app has no SQLite. Ported from the TV
 * version's per-title progress table.
 *
 * Keyed by the Telegram coordinates (chatId:messageId); items without
 * coordinates fall back to the file name, so restored bot-library rows and
 * live-chat rows of the same message share one progress entry.
 */
object WatchProgress {

    private const val PREFS = "tgtvmobile"
    private const val KEY = "watch_progress"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyOf(chatId: Long, messageId: Long, fileName: String): String =
        if (chatId != 0L || messageId != 0L) "$chatId:$messageId"
        else "file:${fileName.lowercase()}"

    /** Persists the last playback position (ms). Skipped before ~10 s in. */
    fun save(
        context: Context,
        chatId: Long,
        messageId: Long,
        fileName: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (positionMs <= 10_000L) return
        val store = runCatching { JSONObject(prefs(context).getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        store.put(
            keyOf(chatId, messageId, fileName),
            JSONObject()
                .put("position_ms", positionMs)
                .put("duration_ms", durationMs)
                .put("updated_ms", System.currentTimeMillis()),
        )
        prefs(context).edit().putString(KEY, store.toString()).apply()
    }

    /** Stored (positionMs, durationMs) or null when nothing was ever saved. */
    fun get(context: Context, chatId: Long, messageId: Long, fileName: String): Pair<Long, Long>? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        val o = runCatching {
            JSONObject(raw).optJSONObject(keyOf(chatId, messageId, fileName))
        }.getOrNull() ?: return null
        val pos = o.optLong("position_ms", 0L)
        if (pos <= 0L) return null
        return pos to o.optLong("duration_ms", 0L)
    }

    /** Drops the row: watched through, or a Play Again started from 0. */
    fun clear(context: Context, chatId: Long, messageId: Long, fileName: String) {
        val raw = prefs(context).getString(KEY, null) ?: return
        runCatching {
            val store = JSONObject(raw)
            store.remove(keyOf(chatId, messageId, fileName))
            prefs(context).edit().putString(KEY, store.toString()).apply()
        }
    }

    /** True when [positionMs] is far enough into [durationMs] to offer Resume. */
    fun resumable(positionMs: Long, durationMs: Long): Boolean =
        positionMs > 20_000L && (durationMs <= 0L || positionMs * 100L < durationMs * 97L)

    /** "1:23:45" / "23:45" for the Resume label. */
    fun prettyMs(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
