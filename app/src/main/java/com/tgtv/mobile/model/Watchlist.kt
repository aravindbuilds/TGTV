package com.tgtv.mobile.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local "My List" of bookmarked videos, persisted in prefs as JSON. Items keep
 * their original chat title; playback may fail if Telegram later removed the
 * files, but the bookmark itself survives.
 */
object Watchlist {

    private const val KEY = "watchlist_items"

    fun all(context: Context): List<VideoItem> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { decode(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun contains(context: Context, video: VideoItem): Boolean =
        all(context).any { it.messageId == video.messageId && it.chatTitle == video.chatTitle }

    /** Adds if missing, removes if present. Returns true when the list ends up containing it. */
    fun toggle(context: Context, video: VideoItem): Boolean {
        val list = all(context).toMutableList()
        val had = list.any { it.messageId == video.messageId && it.chatTitle == video.chatTitle }
        if (had) {
            list.removeAll { it.messageId == video.messageId && it.chatTitle == video.chatTitle }
        } else {
            list.add(0, video)
        }
        val json = JSONArray()
        list.forEach { json.put(encode(it)) }
        prefs(context).edit().putString(KEY, json.toString()).apply()
        return !had
    }

    private fun encode(v: VideoItem) = JSONObject().apply {
        put("messageId", v.messageId)
        put("chatTitle", v.chatTitle)
        put("title", v.title)
        put("fileId", v.fileId)
        put("fileSize", v.fileSize)
        put("durationSeconds", v.durationSeconds)
        put("mimeType", v.mimeType)
        put("fileName", v.fileName)
        put("thumbnailFileId", v.thumbnailFileId)
    }

    private fun decode(o: JSONObject) = runCatching {
        VideoItem(
            o.getLong("messageId"),
            o.optString("chatTitle"),
            o.optString("title", o.optString("fileName")),
            o.getInt("fileId"),
            o.getLong("fileSize"),
            o.getInt("durationSeconds"),
            o.optString("mimeType"),
            o.optString("fileName", o.optString("title")),
            o.getInt("thumbnailFileId"),
        )
    }.getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences("tgvideotv", Context.MODE_PRIVATE)
}
