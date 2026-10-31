package com.tgtv.mobile.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk snapshot of the library so the Home screen opens instantly:
 * the previous scan's items are hydrated from JSON before Telegram is even
 * queried, and the live scan then refreshes in the background. Without this,
 * every launch re-walks chat history and shows "Loading your videos."
 */
object LibrarySnapshot {

    private const val FILE = "library_snapshot.json"

    fun save(context: Context, items: List<VideoItem>) {
        runCatching {
            val arr = JSONArray()
            for (v in items) {
                arr.put(
                    JSONObject()
                        .put("messageId", v.messageId)
                        .put("chatTitle", v.chatTitle)
                        .put("title", v.title)
                        .put("fileId", v.fileId)
                        .put("fileSize", v.fileSize)
                        .put("durationSeconds", v.durationSeconds)
                        .put("mimeType", v.mimeType)
                        .put("fileName", v.fileName)
                        .put("thumbnailFileId", v.thumbnailFileId)
                        .put("chatId", v.chatId)
                        .put("remoteFileId", v.remoteFileId)
                        .put("fileKind", v.fileKind),
                )
            }
            val json = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("items", arr)
                .toString(2)
            file(context).writeText(json)
        }
    }

    /** Restores the last scan's items, or an empty list on any failure. */
    fun load(context: Context): List<VideoItem> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val obj = JSONObject(f.readText())
        val arr = obj.optJSONArray("items") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            runCatching {
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
                    o.optLong("chatId"),
                    o.optString("remoteFileId"),
                    o.optString("fileKind", "video"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun file(context: Context) = File(context.filesDir, FILE)
}
