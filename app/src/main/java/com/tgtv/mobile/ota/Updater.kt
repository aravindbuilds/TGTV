package com.tgtv.mobile.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub-Releases OTA updater for TGTV.
 *
 * How a release becomes an update:
 *   1. Bump versionName in app/build.gradle.kts (e.g. "1.1").
 *   2. Push a git tag `v1.1` and attach the built APK as a release asset.
 *   3. The app compares the release tag against its own versionName; when the
 *      tag is newer it downloads the APK asset and hands it to the system
 *      package installer.
 *
 * Tags are compared as digit-group tuples ("v1.10" > "v1.9"), so a plain
 * vX.Y scheme is enough - no ordering metadata is fetched from GitHub.
 */
object Updater {

    /** GitHub repo polled for the latest release. */
    const val REPO = "aravindbuilds/TGTV"

    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** Where the downloaded APK lands; shared with the installer via FileProvider. */
    fun apkFile(context: Context): File = File(File(context.cacheDir, "ota"), "update.apk")

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    /** This build's versionName (e.g. "1.0"). */
    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * Fetches the latest published release, or null when GitHub is
     * unreachable / the release has no APK asset.
     */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val json = http.newCall(
                Request.Builder()
                    .url(LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("UpdateManager", "GitHub responded ${resp.code}")
                    return@withContext null
                }
                resp.body?.string() ?: return@withContext null
            }
            val obj = JSONObject(json)
            val assets = obj.optJSONArray("assets") ?: return@withContext null
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0L)
                    break
                }
            }
            if (url.isBlank()) return@withContext null
            Release(
                tag = obj.optString("tag_name", "").trimStart('v', 'V'),
                title = obj.optString("name", ""),
                notes = obj.optString("body", ""),
                apkUrl = url,
                sizeBytes = size,
            )
        }.onFailure {
            android.util.Log.w("UpdateManager", "fetchLatest failed", it)
        }.getOrNull()
    }

    /**
     * True when [latest] is strictly newer than [installed]. Digit groups are
     * compared pairwise ("1.10" beats "1.9"); missing groups count as 0.
     */
    fun isNewer(latest: String, installed: String): Boolean {
        val a = latest.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        val b = installed.split('.').map { it.filter(Char::isDigit).toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Downloads the release APK to [apkFile], reporting progress as a 0..100
     * percentage. Returns the file, or null on network failure.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dest = apkFile(context)
            dest.parentFile?.mkdirs()
            dest.delete()
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                var lastPct = -1
                body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            dest
        }.onFailure {
            android.util.Log.w("UpdateManager", "apk download failed", it)
        }.getOrNull()
    }

    /** Outcome of trying to hand the downloaded APK to the system installer. */
    enum class InstallResult {
        /** Installer is running; the update flow is done. */
        LAUNCHED,
        /** The user must first grant "Install unknown apps"; its settings
         *  page was opened. The APK is kept - retry the install afterwards. */
        NEEDS_PERMISSION,
        /** The installer could not be started. The APK is kept so the
         *  install can be retried (no automatic re-download). */
        FAILED,
        /** No downloaded APK on disk (should not happen while READY). */
        NO_APK,
    }

    /**
     * Hands the APK to the system installer. On Android 8+ the user must once
     * allow installs from this app; when not granted, the unknown-sources
     * settings page opens and the caller should retry afterwards. Never
     * deletes [apk] - a failed install must not cost a re-download.
     */
    fun install(context: Context, apk: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return InstallResult.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            InstallResult.LAUNCHED
        } else {
            InstallResult.FAILED
        }
    }

    /** Drops a finished/corrupt download so the next check starts clean. */
    fun clearDownload(context: Context) {
        apkFile(context).delete()
    }
}
