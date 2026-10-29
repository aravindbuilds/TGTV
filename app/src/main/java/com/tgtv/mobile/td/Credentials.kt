package com.tgtv.mobile.td

import android.content.Context
import android.content.SharedPreferences
import com.tgtv.mobile.baked.BakedApi

/** Holds one-time API credentials and per-app preferences for the mobile app. */
object Credentials {
    private const val PREFS = "tgtvmobile"
    const val KEY_API_ID = "api_id"
    const val KEY_API_HASH = "api_hash"
    const val KEY_PHONE = "phone"
    const val KEY_BOT_TOKEN = "bot_token"
    const val KEY_TMDB_KEY = "tmdb_key"
    const val KEY_TMDB_DISABLED = "tmdb_disabled"
    const val KEY_SUBS_ON = "subs_on"
    const val KEY_SUBS_LANGS = "subs_langs"
    const val KEY_OPENSUBS_KEY = "opensubtitles_key"
    const val KEY_SUBS_TEXT_SIZE = "subs_text_size"
    const val KEY_STREAM_DIR = "stream_dir"
    const val KEY_CACHE_RETENTION = "cache_retention_minutes"
    const val KEY_DOWNLOAD_DIR = "download_dir"
    const val KEY_HOME_SORT = "home_sort"
    const val KEY_HOME_GROUP = "home_group_chats"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, apiId: Int, apiHash: String, phone: String) {
        prefs(context).edit()
            .putInt(KEY_API_ID, apiId)
            .putString(KEY_API_HASH, apiHash)
            .putString(KEY_PHONE, phone)
            .apply()
    }

    fun hasApi(context: Context): Boolean =
        apiId(context) > 0 && !apiHash(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * TDLib app credentials: compiled-in obfuscated values win, prefs (manual
     * API-entry screen) are the fallback. Baked values return 0/"" when unset,
     * so prefs behaviour is unchanged for un-baked builds.
     */
    fun apiId(context: Context): Int = BakedApi.apiId().takeIf { it > 0 } ?: prefs(context).getInt(KEY_API_ID, 0)

    fun apiHash(context: Context): String? =
        BakedApi.apiHash().takeIf { it.isNotBlank() } ?: prefs(context).getString(KEY_API_HASH, null)

    /** True when the api keys are baked at build time and no user entry is needed. */
    fun bakedApiPresent(): Boolean = BakedApi.present
    fun phone(context: Context): String? = prefs(context).getString(KEY_PHONE, null)

    /** BotFather token for bot login; cleared with [clear] on sign out. */
    fun botToken(context: Context): String? = prefs(context).getString(KEY_BOT_TOKEN, null)

    fun setBotToken(context: Context, token: String?) {
        prefs(context).edit()
            .putString(KEY_BOT_TOKEN, token?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    // ------------------------------------------------------------------
    // Streaming folder
    // ------------------------------------------------------------------

    /**
     * Where TDLib stores streamed/downloaded bytes. Null = the app default
     * (internal cache). Candidates come from [] no - see
     * SettingsActivity.streamCandidates().
     */
    fun streamDir(context: Context): String? = prefs(context).getString(KEY_STREAM_DIR, null)

    fun setStreamDir(context: Context, path: String?) {
        prefs(context).edit()
            .putString(KEY_STREAM_DIR, path?.takeIf { it.isNotBlank() })
            .apply()
    }

    // ------------------------------------------------------------------
    // Cache retention
    // ------------------------------------------------------------------

    /**
     * How long streamed-cache bytes are kept after a video is closed, in
     * minutes. 0 = wipe on exit (default, lean storage); -1 = keep until the
     * user clears them manually. Longer retention keeps already-streamed
     * parts on disk so replays / seeks into watched ranges play instantly.
     */
    fun cacheRetentionMinutes(context: Context): Int =
        prefs(context).getInt(KEY_CACHE_RETENTION, 0)

    fun setCacheRetentionMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_CACHE_RETENTION, minutes).apply()
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /**
     * Where finished downloads are saved. Null = the public gallery
     * (Movies/TgTV via MediaStore); otherwise an app-writable folder chosen
     * in Settings (internal storage / SD card app folder).
     */
    fun downloadDir(context: Context): String? =
        prefs(context).getString(KEY_DOWNLOAD_DIR, null)
            ?.takeIf { it.isNotBlank() }

    fun setDownloadDir(context: Context, path: String?) {
        prefs(context).edit()
            .putString(KEY_DOWNLOAD_DIR, path?.takeIf { it.isNotBlank() })
            .apply()
    }

    // ------------------------------------------------------------------
    // Home sort
    // ------------------------------------------------------------------

    /**
     * Library ordering on the home screen: 0 newest first, 1 oldest first,
     * 2 title A-Z, 3 largest first. (A build once stored 4 = "group by
     * chats" here; that value migrates to the separate group toggle.)
     */
    fun homeSort(context: Context): Int {
        val v = prefs(context).getInt(KEY_HOME_SORT, 0)
        if (v == 4) { // legacy single-pref "group by chats"
            prefs(context).edit()
                .putInt(KEY_HOME_SORT, 0)
                .putBoolean(KEY_HOME_GROUP, true)
                .apply()
            return 0
        }
        return v.coerceIn(0, 3)
    }

    fun setHomeSort(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_HOME_SORT, mode.coerceIn(0, 3)).apply()
    }

    /**
     * "Group by chats" is an independent on/off toggle (not a sort mode):
     * when on, the library is grouped per chat with swipeable card rows.
     */
    fun homeGroup(context: Context): Boolean {
        homeSort(context) // triggers the legacy 4 -> toggle migration
        return prefs(context).getBoolean(KEY_HOME_GROUP, false)
    }

    fun setHomeGroup(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_HOME_GROUP, on).apply()
    }

    // ------------------------------------------------------------------
    // TMDB
    // ------------------------------------------------------------------

    fun tmdbKey(context: Context): String? = prefs(context).getString(KEY_TMDB_KEY, null)

    fun setTmdbKey(context: Context, key: String?) {
        prefs(context).edit()
            .putString(KEY_TMDB_KEY, key?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun tmdbDisabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TMDB_DISABLED, false)

    fun setTmdbDisabled(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TMDB_DISABLED, disabled).apply()
    }

    // ------------------------------------------------------------------
    // Subtitles
    // ------------------------------------------------------------------

    fun subsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SUBS_ON, true)

    fun setSubsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBS_ON, on).apply()
    }

    /**
     * Subtitle text size profile: 0 small (matches the old letterboxed look),
     * 1 medium, 2 large.
     */
    fun subsTextSize(context: Context): Int = prefs(context).getInt(KEY_SUBS_TEXT_SIZE, 0)

    fun setSubsTextSize(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_SUBS_TEXT_SIZE, index.coerceIn(0, 2)).apply()
    }

    fun subsLanguages(context: Context): String =
        prefs(context).getString(KEY_SUBS_LANGS, "en")?.ifBlank { "en" } ?: "en"

    fun setSubsLanguages(context: Context, langs: String) {
        prefs(context).edit()
            .putString(KEY_SUBS_LANGS, langs.trim().lowercase().takeIf { it.isNotBlank() } ?: "en")
            .apply()
    }

    /** User-entered OpenSubtitles key wins; the baked key is the fallback. */
    fun openSubtitlesKey(context: Context): String? =
        prefs(context).getString(KEY_OPENSUBS_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: BakedApi.openSubtitles().takeIf { it.isNotBlank() }

    fun setOpenSubtitlesKey(context: Context, key: String?) {
        prefs(context).edit()
            .putString(KEY_OPENSUBS_KEY, key?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }
}
