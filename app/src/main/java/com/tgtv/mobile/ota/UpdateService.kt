package com.tgtv.mobile.ota

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tgtv.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that downloads the OTA update APK from GitHub Releases.
 *
 * Runs as a foreground (dataSync) service so the system keeps the process
 * alive for the whole transfer - leaving the app, or the screen that started
 * it, never cancels the download. Progress is published both as a shade
 * notification and through [UpdateManager.state] for in-app UI.
 */
class UpdateService : Service() {

    companion object {
        // v2: silent (LOW importance = no sound); the progress card still
        // shows in the shade. v1 was DEFAULT and alerted on every download.
        private const val CHANNEL_ID = "app_updates_v2"
        const val NOTIF_ID = 45
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"
        private const val TAG = "UpdateManager"

        /** Kicks off the download of [rel]; safe to call from any screen. */
        fun start(context: Context, rel: Updater.Release) {
            val i = Intent(context, UpdateService::class.java)
                .putExtra(EXTRA_TAG, rel.tag)
                .putExtra(EXTRA_URL, rel.apkUrl)
                .putExtra(EXTRA_SIZE, rel.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
        startForeground(NOTIF_ID, progressNotification("Starting…", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tag = intent?.getStringExtra(EXTRA_TAG).orEmpty()
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val size = intent?.getLongExtra(EXTRA_SIZE, 0L) ?: 0L
        if (tag.isBlank() || url.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        UpdateManager.state.value = UpdateManager.UpdateState(
            UpdateManager.Phase.DOWNLOADING, tag, 0,
        )
        scope.launch {
            val file = Updater.download(
                applicationContext,
                Updater.Release(tag, "", "", url, size),
            ) { pct ->
                UpdateManager.state.value = UpdateManager.state.value.copy(progress = pct)
                nm.notify(NOTIF_ID, progressNotification("$tag — $pct%", pct))
            }
            // Leave the foreground (and its stuck progress card), then post a
            // regular notification the user can keep or dismiss.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            if (file != null) {
                // Convenience fallback: a copy in public Downloads, so the
                // update can also be installed manually from any file
                // manager if the in-app installer is ever refused.
                val publicPath = runCatching { copyToDownloads(tag, file) }
                    .onFailure { android.util.Log.w(TAG, "Downloads copy failed", it) }
                    .getOrNull()
                UpdateManager.state.value = UpdateManager.UpdateState(
                    UpdateManager.Phase.READY, tag, 100,
                )
                nm.notify(NOTIF_ID, readyNotification(tag, publicPath))
            } else {
                UpdateManager.state.value = UpdateManager.UpdateState(
                    UpdateManager.Phase.FAILED, tag,
                )
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun progressNotification(text: String, pct: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading TGTV update")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setContentIntent(openApp())
            .build()

    private fun readyNotification(tag: String, publicPath: String?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("TGTV v$tag ready to install")
            .setContentText(
                publicPath?.let { "TGTV opens the installer - or install $it from a file manager." }
                    ?: "TGTV opens the installer. Tap to continue.",
            )
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    /**
     * Copies the downloaded APK to Downloads/TgTV/TGTV-v<tag>.apk via
     * MediaStore (no storage permission needed on API 29+). Returns the
     * user-visible path, or null on failure - it is only a fallback.
     */
    private fun copyToDownloads(tag: String, src: File): String? {
        val name = "TGTV-v$tag.apk"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(
                    android.provider.MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.android.package-archive",
                )
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/TgTV")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            // Replace a leftover copy from a previous attempt.
            resolver.delete(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=? AND " +
                    "${android.provider.MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, "Download/TgTV"),
            )
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/TgTV/$name"
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TgTV",
            ).apply { mkdirs() }
            val dest = File(dir, name)
            if (dest.exists()) dest.delete()
            dest.outputStream().use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            "Download/TgTV/$name"
        }
    }

    private fun openApp(): PendingIntent? {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
