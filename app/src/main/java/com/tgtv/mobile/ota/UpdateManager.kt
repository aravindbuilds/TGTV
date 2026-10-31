package com.tgtv.mobile.ota

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Process-wide OTA update state. Deliberately decoupled from any Activity:
 * the check runs on an application-scoped coroutine and the download runs in
 * [UpdateService] (a foreground service), so leaving Settings - or the app -
 * never cancels an in-flight update. Both Settings and Home render from
 * [state]; whoever is on screen when the phase changes reacts to it.
 */
object UpdateManager {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

    data class UpdateState(
        val phase: Phase = Phase.IDLE,
        val tag: String = "",
        /** Download progress, 0..100 (only meaningful while DOWNLOADING). */
        val progress: Int = 0,
    )

    /** Single source of truth; UI screens collect this. */
    val state = MutableStateFlow(UpdateState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TAG = "UpdateManager"

    /** The release backing the current AVAILABLE/DOWNLOADING state. */
    private var release: Updater.Release? = null

    /**
     * Set when the last install attempt stopped at the "Install unknown
     * apps" page. Once the user grants it there, [resumeInstall] launches
     * the installer automatically - no second tap needed - and the granted
     * permission then persists for all future updates.
     */
    var awaitingInstallPermission = false
        private set

    /** Polls GitHub for the latest release. No-op while a check/download runs. */
    fun check(context: Context) {
        android.util.Log.d(TAG, "check: entry phase=${state.value.phase}")
        if (state.value.phase == Phase.CHECKING || state.value.phase == Phase.DOWNLOADING) return
        val app = context.applicationContext
        state.value = UpdateState(Phase.CHECKING)
        scope.launch {
            android.util.Log.d(TAG, "check: fetch started")
            // Bounded: DNS (esp. on emulators) can hang outside OkHttp's
            // connect timeout; without this the state would stick at
            // CHECKING forever and the UI retry loop would never advance.
            val rel = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                Updater.fetchLatest()
            }
            release = rel
            if (rel == null) {
                android.util.Log.w(TAG, "update check: GitHub fetch failed (network or no APK asset)")
            }
            state.value = when {
                rel == null -> UpdateState(Phase.FAILED)
                // Newer release AND the APK for it is already fully
                // downloaded (e.g. the install was interrupted or the
                // process died after the download): go straight to READY
                // instead of making the user download it again.
                Updater.isNewer(rel.tag, Updater.installedVersion(app)) ->
                    if (apkMatches(app, rel)) {
                        android.util.Log.d(TAG, "check: APK already downloaded for ${rel.tag} - skipping to READY")
                        UpdateState(Phase.READY, rel.tag, 100)
                    } else {
                        UpdateState(Phase.AVAILABLE, rel.tag)
                    }
                else -> {
                    // Stale APK from an old release would only waste space.
                    Updater.clearDownload(app)
                    UpdateState(Phase.UP_TO_DATE, rel.tag)
                }
            }
        }
    }

    /**
     * Starts the foreground-service download of the available release - or,
     * when the APK is already on disk and complete, skips straight to READY
     * without any network traffic.
     */
    fun download(context: Context) {
        if (state.value.phase != Phase.AVAILABLE) return
        val rel = release ?: return
        val app = context.applicationContext
        if (apkMatches(app, rel)) {
            android.util.Log.d(TAG, "download: APK already on disk for ${rel.tag} - skipping download")
            state.value = UpdateState(Phase.READY, rel.tag, 100)
            return
        }
        UpdateService.start(app, rel)
    }

    /**
     * Launches the system installer for the downloaded APK. On any failure
     * the state stays READY and the APK is kept - the user retries the
     * install only, never the download. Clears the update notification once
     * handed off.
     */
    fun install(context: Context): Updater.InstallResult {
        val file: File = Updater.apkFile(context)
        if (!file.exists()) return Updater.InstallResult.NO_APK
        return when (val res = Updater.install(context, file)) {
            Updater.InstallResult.LAUNCHED -> {
                awaitingInstallPermission = false
                state.value = UpdateState(Phase.IDLE)
                nm(context.applicationContext).cancel(UpdateService.NOTIF_ID)
                res
            }
            Updater.InstallResult.NEEDS_PERMISSION -> {
                awaitingInstallPermission = true
                res
            }
            // FAILED: keep state READY + APK on disk so only the install is
            // retried - never the download.
            else -> res
        }
    }

    /**
     * Called from screens' onResume: the moment the user grants "Install
     * unknown apps" (after the settings page we opened), the installer is
     * launched automatically. Only fires when the permission is actually
     * granted now, so coming back without granting never re-opens the page
     * or loops.
     */
    fun resumeInstall(context: Context) {
        if (!awaitingInstallPermission || state.value.phase != Phase.READY) return
        val app = context.applicationContext
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            app.packageManager.canRequestPackageInstalls()
        if (!granted) return
        android.util.Log.d(TAG, "install permission granted - resuming install")
        awaitingInstallPermission = false
        install(app)
    }

    /** Drops a finished or broken download so a later check starts clean. */
    fun reset(context: Context) {
        Updater.clearDownload(context)
        state.value = UpdateState(Phase.IDLE)
        nm(context.applicationContext).cancel(UpdateService.NOTIF_ID)
    }

    /** True when the APK on disk exists and matches [rel]'s size exactly. */
    private fun apkMatches(context: Context, rel: Updater.Release): Boolean {
        val f = Updater.apkFile(context)
        return rel.sizeBytes > 0 && f.exists() && f.length() == rel.sizeBytes
    }

    private fun nm(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
