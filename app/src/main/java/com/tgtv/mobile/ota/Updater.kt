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
import org.json.JSONArray
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

    private const val REPO_API = "https://api.github.com/repos/$REPO"

    /**
     * Bounded DNS: InetAddress lookups have no timeout of their own and can
     * hang for minutes on some networks/emulators, which defeats OkHttp's
     * connect timeout and coroutine cancellation alike. Each lookup gets a
     * hard 5s deadline; past that we fail the request fast so the update
     * check can report failure and retry.
     */
    private val dnsExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "tgtx-dns").apply { isDaemon = true }
    }

    /** DNS-over-HTTPS answers, cached 5 minutes per hostname. */
    private val dnsCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<java.net.InetAddress>>>()

    /**
     * Pinned to Cloudflare 1.1.1.1 BY IP - the TLS cert covers the bare
     * address and no OS resolver is involved, so this works even when the
     * system DNS is wedged (a classic emulator stall). Fully covered by
     * timeouts, unlike getaddrinfo which cannot be interrupted.
     */
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun resolveDoh(hostname: String): List<java.net.InetAddress> {
        val json = dohClient.newCall(
            Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("accept", "application/dns-json")
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("DoH responded ${resp.code}")
            resp.body?.string() ?: error("empty DoH response")
        }
        val answers = JSONObject(json).optJSONArray("Answer") ?: error("no DoH answer")
        val out = mutableListOf<java.net.InetAddress>()
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            if (a.optInt("type") == 1) {
                // Parsing an IP literal never touches the OS resolver.
                runCatching { java.net.InetAddress.getByName(a.optString("data")) }
                    .getOrNull()?.let { out.add(it) }
            }
        }
        if (out.isEmpty()) error("no A record for $hostname")
        return out
    }

    /** DoH first (bulletproof), then the bounded system resolver. */
    private fun resolve(hostname: String): List<java.net.InetAddress> {
        dnsCache[hostname]?.let { (at, v) ->
            if (System.currentTimeMillis() - at < 5 * 60_000L) return v
        }
        val v = runCatching { resolveDoh(hostname) }.getOrNull()
            ?: dnsExecutor
                .submit(java.util.concurrent.Callable {
                    java.net.InetAddress.getAllByName(hostname).toList()
                })
                .get(5, TimeUnit.SECONDS)
        dnsCache[hostname] = System.currentTimeMillis() to v
        return v
    }

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = try {
                resolve(hostname)
            } catch (e: Exception) {
                android.util.Log.w("UpdateManager", "DNS lookup failed/timed out: $hostname")
                throw java.net.UnknownHostException("DNS timeout: $hostname")
            }
        })
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Where the downloaded APK lands; shared with the installer via FileProvider. */
    fun apkFile(context: Context): File = File(File(context.cacheDir, "ota"), "update.apk")

    data class Release(
        val tag: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val prerelease: Boolean = false,
    )

    /** This build's versionName (e.g. "1.0"). */
    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * Fetches the release the app should offer: the latest stable by default,
     * or - when [betas] is on - the newest entry (stable or pre-release) with
     * an APK asset. Null when GitHub is unreachable / nothing has an APK.
     */
    suspend fun fetchLatest(betas: Boolean = false): Release? = withContext(Dispatchers.IO) {
        runCatching {
            if (betas) return@runCatching fetchBetaFromList()
            val obj = http().newCall(
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
            }.let { JSONObject(it) }
            apkFrom(obj, prerelease = false)
        }.onFailure {
            android.util.Log.w("UpdateManager", "fetchLatest failed", it)
        }.getOrNull()
    }

    /**
     * Walks the newest releases top-down and returns the first one that is
     * allowed (pre-releases are permitted here) and carries an APK. The list
     * is newest-first, so the first match is the newest candidate.
     */
    private fun fetchBetaFromList(): Release? {
        val json = http().newCall(
            Request.Builder()
                .url("$REPO_API/releases?per_page=10")
                .header("Accept", "application/vnd.github+json")
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub responded ${resp.code}")
            resp.body?.string() ?: error("empty releases body")
        }
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val rel = apkFrom(arr.getJSONObject(i), prerelease = arr.getJSONObject(i).optBoolean("prerelease"))
            if (rel != null) return rel
        }
        return null
    }

    /** Builds a [Release] from one release object, or null without an APK asset. */
    private fun apkFrom(obj: JSONObject, prerelease: Boolean): Release? {
        val assets = obj.optJSONArray("assets") ?: return null
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
        if (url.isBlank()) return null
        return Release(
            tag = obj.optString("tag_name", "").trimStart('v', 'V'),
            title = obj.optString("name", ""),
            notes = obj.optString("body", ""),
            apkUrl = url,
            sizeBytes = size,
            prerelease = prerelease,
        )
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
            http().newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
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
