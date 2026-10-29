package com.tgtv.mobile.model

import java.io.Serializable

/**
 * A playable video item, mapped from any Telegram message content that carries video:
 * [TdApi.MessageVideo], [TdApi.MessageVideoNote], [TdApi.MessageAnimation] or a
 * [TdApi.MessageDocument] whose MIME type / file extension looks like a video
 * (most large videos on Telegram are sent as documents).
 *
 * [remoteFileId] is TDLib's stable remote id: TDLib file ids go stale when the
 * process restarts, and bot sessions cannot re-read history - the remote id
 * re-resolves to a live file (TdApi.GetRemoteFile) for restored rows.
 */
data class VideoItem(
    val messageId: Long,
    val chatTitle: String,
    val title: String,
    val fileId: Int,
    val fileSize: Long,
    val durationSeconds: Int,
    val mimeType: String,
    val fileName: String,
    val thumbnailFileId: Int,
    val chatId: Long = 0L,
    val remoteFileId: String = "",
    val fileKind: String = "video",
) : Serializable {
    fun prettySize(): String {
        val s = fileSize.toFloat()
        return when {
            fileSize <= 0L -> ""
            s >= 1024f * 1024f * 1024f -> "%.1f GB".format(s / (1024f * 1024f * 1024f))
            else -> "%.1f MB".format(s / (1024f * 1024f))
        }
    }

    fun prettyDuration(): String {
        val d = durationSeconds
        if (d <= 0) return ""
        return "%02d:%02d".format(d / 60, d % 60)
    }

    /** "1h 28m" style runtime line for the details screen. */
    fun prettyRuntime(): String {
        val d = durationSeconds
        if (d <= 0) return ""
        return (if (d / 3600 > 0) "${d / 3600}h " else "") + "%02dm".format(d % 3600 / 60)
    }
}
