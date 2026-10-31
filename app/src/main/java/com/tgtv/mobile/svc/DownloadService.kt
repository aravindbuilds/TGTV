package com.tgtv.mobile.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tgtv.mobile.R
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that fully downloads a Telegram file (via TdApi.DownloadFile)
 * and saves it to Movies/TgTV (or the app's Movies dir below API 29).
 */
class DownloadService : Service() {

    companion object {
        // v3: silent (LOW importance = no sound) but still plainly visible in
        // the shade. v2 was DEFAULT and played an alert on every download.
        private const val CHANNEL_ID = "downloads_v3"
        private const val NOTIF_ID = 42
        private const val DONE_NOTIF_ID = 44
        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_MESSAGE_ID = "messageId"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_FILE_NAME = "name"
        private const val EXTRA_MIME = "mime"

        fun start(context: Context, item: VideoItem) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_FILE_ID, item.fileId)
                .putExtra(EXTRA_MESSAGE_ID, item.messageId)
                .putExtra(EXTRA_SIZE, item.fileSize)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_CHAT, item.chatTitle)
                .putExtra(EXTRA_FILE_NAME, item.fileName)
                .putExtra(EXTRA_MIME, item.mimeType)
            // startForegroundService exists only from API 26 (MiBox4 is API 23)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun videoFrom(intent: Intent): VideoItem = VideoItem(
            messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L),
            chatTitle = intent.getStringExtra(EXTRA_CHAT) ?: "",
            title = intent.getStringExtra(EXTRA_TITLE) ?: "Video",
            fileId = intent.getIntExtra(EXTRA_FILE_ID, 0),
            fileSize = intent.getLongExtra(EXTRA_SIZE, 0L),
            durationSeconds = 0,
            mimeType = intent.getStringExtra(EXTRA_MIME) ?: "",
            fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "",
            thumbnailFileId = 0,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nm: NotificationManager
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // Notification channels only exist on API 26+. LOW importance keeps
        // the progress card visible in the shade but plays no sound.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIF_ID, notification("Preparing...", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val item = intent?.let { videoFrom(it) }
        if (item == null || item.fileId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            // Already downloading something else; ignore extra request for now
            stopSelf()
            return START_STICKY
        }
        job = scope.launch { downloadAndSave(item) }
        return START_STICKY
    }

    private fun notification(text: CharSequence, progress: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, progress, total == 0)
            .setContentIntent(openApp())
            .build()

    /** Sticks around after the service stops, so "complete" is actually seen. */
    private fun doneNotification(text: CharSequence): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.download_complete))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    /** Tapping the notification opens TGTV. */
    private fun openApp(): PendingIntent? {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun update(text: CharSequence, progress: Int, total: Int) {
        nm.notify(NOTIF_ID, notification(text, progress, total))
    }

    private suspend fun downloadAndSave(item: VideoItem) {
        val listener: (TdApi.Object) -> Unit = { obj ->
            val f = (obj as? TdApi.UpdateFile)?.file
            if (f != null && f.id == item.fileId) {
                val locals = f.local
                val total = f.size.takeIf { it > 0 } ?: f.expectedSize
                if (locals != null && total > 0 && !locals.isDownloadingCompleted) {
                    val done = maxOf(locals.downloadedPrefixSize, locals.downloadedSize)
                    if (done > 0) update(item.title, (done * 100 / total).toInt(), 100)
                }
            }
        }
        TdLib.addListener(listener)
        try {
            val file = TdLib.downloadFully(item.fileId, priority = 24)
            val src = file.local!!.path
            val dest = copyToMovies(src, item)
            nm.notify(DONE_NOTIF_ID, doneNotification(dest))

            // Drop the managed download record; the cached copy stays until cleared
            TdLib.send(TdApi.RemoveFileFromDownloads(item.fileId, false))
        } catch (e: Exception) {
            update("Download failed: ${e.message}", 0, 100)
        } finally {
            TdLib.removeListener(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun copyToMovies(srcPath: String, item: VideoItem): String {
        val displayName = item.fileName.ifBlank { "tgvideo_${item.messageId}.mp4" }
        // User-chosen download folder (Settings): plain file copy. These are
        // app-writable dirs on internal storage / SD cards.
        Credentials.downloadDir(this)?.let { path ->
            val dir = File(path).apply { mkdirs() }
            val dest = File(dir, displayName)
            FileOutputStream(dest).use { out ->
                File(srcPath).inputStream().use { it.copyTo(out) }
            }
            return dest.absolutePath
        }
        val resolver = contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(
                    android.provider.MediaStore.Video.Media.MIME_TYPE,
                    item.mimeType.ifBlank { "video/mp4" },
                )
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/TgTV")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val outUri = resolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Could not create MediaStore entry")
            try {
                resolver.openOutputStream(outUri)?.use { out ->
                    File(srcPath).inputStream().use { it.copyTo(out) }
                } ?: error("Could not open output stream")
                values.clear()
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
                "Movies/TgTV/$displayName"
            } catch (e: Exception) {
                resolver.delete(outUri, null, null)
                throw e
            }
        } else {
            val dir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir,
                "TgTV",
            ).apply { mkdirs() }
            val dest = File(dir, displayName)
            FileOutputStream(dest).use { out ->
                File(srcPath).inputStream().use { it.copyTo(out) }
            }
            "App movies dir: ${dest.absolutePath}"
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
