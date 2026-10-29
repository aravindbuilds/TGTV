package com.tgtv.mobile.model

import org.drinkless.tdlib.TdApi

/**
 * Maps any TdApi.Message carrying video content into a [VideoItem].
 * Most videos in Telegram travel as generic documents, so include those
 * by MIME type or extension.
 */
object VideoMapper {

    private val VIDEO_EXTENSIONS = setOf(
        ".mkv", ".mp4", ".mov", ".webm", ".avi", ".m4v", ".mpeg", ".mpg", ".wmv",
        ".flv", ".3gp", ".3g2", ".ts", ".m2ts", ".mxf", ".f4v", ".vob", ".ogv",
        ".rm", ".rmvb",
    )

    /** Any message content type that should be playable. */
    fun isVideoMessage(content: TdApi.MessageContent): Boolean = when (content) {
        is TdApi.MessageVideo -> true
        is TdApi.MessageVideoNote -> true
        is TdApi.MessageAnimation -> true
        is TdApi.MessageDocument, is TdApi.MessageAudio -> isVideoDocOrAudio(content)
        else -> false
    }

    private fun isVideoDocOrAudio(content: TdApi.MessageContent): Boolean {
        return when (content) {
            is TdApi.MessageDocument -> isVideoFile(content.document?.mimeType, content.document?.fileName)
            is TdApi.MessageAudio -> isVideoFile(content.audio?.mimeType, content.audio?.fileName)
            else -> false
        }
    }

    val filters = listOf(
        TdApi.SearchMessagesFilterVideo(),
        TdApi.SearchMessagesFilterVideoNote(),
        TdApi.SearchMessagesFilterAnimation(),
        TdApi.SearchMessagesFilterDocument(),
    )

    fun mapMessage(message: TdApi.Message, chatTitle: String): VideoItem? = when (val c = message.content) {
        is TdApi.MessageVideo -> c.video?.let { v ->
            v.video?.let { file ->
                VideoItem(
                    messageId = message.id,
                    chatTitle = chatTitle,
                    title = titleOf(c.caption?.text, v.fileName, "Video"),
                    fileId = file.id,
                    fileSize = file.size,
                    durationSeconds = v.duration,
                    mimeType = v.mimeType,
                    fileName = fileNameOf(v.fileName, "video.mp4"),
                    thumbnailFileId = v.thumbnail?.file?.id ?: 0,
                    chatId = message.chatId,
                    remoteFileId = file.remote?.id?.toString() ?: "",
                    fileKind = "video",
                )
            }
        }
        is TdApi.MessageVideoNote -> c.videoNote?.let { vn ->
            vn.video?.let { file ->
                VideoItem(
                    messageId = message.id,
                    chatTitle = chatTitle,
                    title = "Video note",
                    fileId = file.id,
                    fileSize = file.size,
                    durationSeconds = vn.duration,
                    mimeType = "",
                    fileName = "video_note.mp4",
                    thumbnailFileId = vn.thumbnail?.file?.id ?: 0,
                    chatId = message.chatId,
                    remoteFileId = file.remote?.id?.toString() ?: "",
                    fileKind = "note",
                )
            }
        }
        is TdApi.MessageAnimation -> c.animation?.let { a ->
            a.animation?.let { file ->
                VideoItem(
                    messageId = message.id,
                    chatTitle = chatTitle,
                    title = titleOf(c.caption?.text, a.fileName, "Animation"),
                    fileId = file.id,
                    fileSize = file.size,
                    durationSeconds = a.duration,
                    mimeType = a.mimeType,
                    fileName = fileNameOf(a.fileName, "gif.mp4"),
                    thumbnailFileId = a.thumbnail?.file?.id ?: 0,
                    chatId = message.chatId,
                    remoteFileId = file.remote?.id?.toString() ?: "",
                    fileKind = "animation",
                )
            }
        }
        is TdApi.MessageDocument -> {
            val d = c.document
            val f = d?.document
            if (d == null || f == null || !isVideoFile(d.mimeType, d.fileName)) {
                null
            } else {
                VideoItem(
                    messageId = message.id,
                    chatTitle = chatTitle,
                    title = titleOf(c.caption?.text, d.fileName, "Video"),
                    fileId = f.id,
                    fileSize = f.size,
                    durationSeconds = 0,
                    mimeType = d.mimeType,
                    fileName = fileNameOf(d.fileName, "video.mp4"),
                    thumbnailFileId = d.thumbnail?.file?.id ?: 0,
                    chatId = message.chatId,
                    remoteFileId = f.remote?.id?.toString() ?: "",
                    fileKind = "document",
                )
            }
        }
        else -> null
    }

    private fun isVideoFile(mimeType: String?, fileName: String?): Boolean {
        val mime = mimeType?.lowercase()?.trim().orEmpty()
        val name = fileName?.lowercase()?.trim().orEmpty()
        return mime.startsWith("video/") || VIDEO_EXTENSIONS.any { name.endsWith(it) }
    }

    private fun titleOf(caption: String?, fileName: String?, fallback: String): String {
        val n = fileName?.substringBeforeLast('.', fallback)?.take(48) ?: fallback
        return when {
            !caption.isNullOrBlank() -> caption.substringBefore('\n').take(80)
            !fileName.isNullOrBlank() -> n
            else -> fallback
        }
    }

    private fun fileNameOf(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() } ?: "video.mp4"
}
