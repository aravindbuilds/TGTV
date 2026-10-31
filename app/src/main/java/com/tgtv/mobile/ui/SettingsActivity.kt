package com.tgtv.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.mobile.R
import com.tgtv.mobile.model.ChatSelection
import com.tgtv.mobile.ota.UpdateManager
import com.tgtv.mobile.ota.Updater
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mobile settings: grouped premium sections - library (streaming folder,
 * TMDB metadata, subtitles), storage (clear cache), account (sign out).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnFolder).setOnClickListener { pickStreamFolder() }
        findViewById<View>(R.id.btnDownloadFolder).setOnClickListener { pickDownloadFolder() }
        findViewById<View>(R.id.btnChats).setOnClickListener {
            startActivity(ChatSelectActivity.intent(this, fromSettings = true))
        }
        findViewById<View>(R.id.btnTmdb).setOnClickListener { tmdbDialog() }
        findViewById<View>(R.id.btnSubs).setOnClickListener { subtitleDialog() }
        findViewById<View>(R.id.btnCacheRetention).setOnClickListener { retentionDialog() }
        findViewById<View>(R.id.btnClearCache).setOnClickListener { clearCache() }
        findViewById<View>(R.id.btnSignOut).setOnClickListener { signOut() }
        findViewById<View>(R.id.btnCheckUpdates).setOnClickListener { onUpdateRow() }
        findViewById<ImageButton>(R.id.btnBackIcon).setOnClickListener { finish() }
        setupBetaToggle()
        fillRows()
        // Live view of the process-wide update state; the check itself keeps
        // running in UpdateManager even if this screen is left.
        lifecycleScope.launch {
            UpdateManager.state.collect { renderUpdate(it) }
        }
        UpdateManager.check(this)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "Install unknown apps" page: if the user just
        // granted it, launch the installer right away.
        UpdateManager.resumeInstall(this)
    }

    /** Beta opt-in: when on, the updater also offers pre-release versions. */
    private fun setupBetaToggle() {
        val sw = findViewById<Switch>(R.id.switchBeta)
        val sub = findViewById<TextView>(R.id.txtBetaSub)
        sw.isChecked = Credentials.betaUpdates(this)
        sub.text = if (sw.isChecked) {
            "On — pre-releases offered as they come out"
        } else {
            "Off — stable releases only"
        }
        sw.setOnCheckedChangeListener { _, checked ->
            Credentials.setBetaUpdates(this, checked)
            sub.text = if (checked) {
                "On — pre-releases offered as they come out"
            } else {
                "Off — stable releases only"
            }
            // Re-check immediately so the row reflects the new preference.
            UpdateManager.check(this)
        }
    }

    /** Fills the per-row subtitle lines with live state. */
    private fun fillRows() {
        val dir = TdLib.streamRoot(this)
        findViewById<TextView>(R.id.txtFolderSub).text = abbreviatePath(dir.absolutePath)

        findViewById<TextView>(R.id.txtDownloadSub).text = abbreviatePath(
            Credentials.downloadDir(this) ?: downloadDefaultLabel(),
        )

        findViewById<TextView>(R.id.txtChatsSub).text = when {
            ChatSelection.isConfigured(this) -> {
                val n = ChatSelection.selected(this).size
                if (n == 1) "1 chat feeds the library" else "$n chats feed the library"
            }
            else -> "Saved Messages (default)"
        }

        val tmdbOn = !Credentials.tmdbDisabled(this)
        val customKey = Credentials.tmdbKey(this) != null
        findViewById<TextView>(R.id.txtTmdbSub).text = when {
            !tmdbOn -> "Off"
            customKey -> "On · custom API key"
            else -> "On · built-in key"
        }

        val subsOn = Credentials.subsEnabled(this)
        findViewById<TextView>(R.id.txtSubsSub).text = buildString {
            append(if (subsOn) "On" else "Off")
            append(" · ").append(Credentials.subsLanguages(this@SettingsActivity))
            append(if (Credentials.openSubtitlesKey(this@SettingsActivity) != null) " · key set" else "")
        }

        findViewById<TextView>(R.id.txtRetentionSub).text = retentionLabel(
            Credentials.cacheRetentionMinutes(this),
        )

        findViewById<TextView>(R.id.txtSessionSub).text = when {
            Credentials.botToken(this) != null -> "Bot session active"
            Credentials.phone(this) != null -> "Signed in · ${Credentials.phone(this)}"
            else -> "Not signed in"
        }

        findViewById<TextView>(R.id.txtVersion).text = runCatching {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            "TGTV v$v"
        }.getOrDefault("TGTV")

        // Cache size: streamed-video cache, computed off the UI thread.
        val cacheSub = findViewById<TextView>(R.id.txtCacheSub)
        lifecycleScope.launch(Dispatchers.IO) {
            val mb = dirSize(dir) / (1024L * 1024L)
            runOnUiThread {
                cacheSub.text = if (mb > 0) "~$mb MB streamed cache" else "Nothing cached"
            }
        }
    }

    /** Keeps long paths readable: root elided, last two segments kept. */
    private fun abbreviatePath(path: String): String {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size <= 3) path
        else "…/" + parts.takeLast(2).joinToString("/")
    }

    private fun dirSize(f: File): Long = when {
        f.isFile -> f.length()
        f.isDirectory -> f.listFiles()?.sumOf { dirSize(it) } ?: 0L
        else -> 0L
    }

    // ------------------------------------------------------- folder picker

    /**
     * Folders an Android app is actually allowed to write raw files to:
     * internal app cache (default), app-private dirs on every storage volume
     * (SD cards included). The choice persists and applies after relaunch.
     */
    private fun streamFolderCandidates(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val def = File(cacheDir, "tdlib_files")
        out.add("Default (internal app cache)\n${def.absolutePath}" to def.absolutePath)
        val primaryRoot = Environment.getExternalStorageDirectory()?.absolutePath
        val volumes = getExternalFilesDirs(null).filterNotNull()
        for ((i, f) in volumes.withIndex()) {
            val label = if (primaryRoot != null && f.absolutePath.startsWith(primaryRoot))
                "Internal storage (app folder)" else "SD card ${i + 1} (app folder)"
            out.add("$label\n${f.absolutePath}" to f.absolutePath)
        }
        return out
    }

    private fun pickStreamFolder() {
        val options = streamFolderCandidates()
        val labels = options.map { it.first }.toTypedArray()
        val current = Credentials.streamDir(this)
        AlertDialog.Builder(this)
            .setTitle("Streaming folder")
            .setSingleChoiceItems(
                labels,
                options.indexOfFirst { it.second == current }.coerceAtLeast(0),
            ) { dialog, which ->
                val chosen = options[which].second
                val changed = (current ?: options[0].second) != chosen
                Credentials.setStreamDir(this, if (which == 0) null else chosen)
                dialog.dismiss()
                fillRows()
                if (changed) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        TdLib.resetDb(applicationContext)
                        TdLib.start(applicationContext)
                        runOnUiThread {
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("Streaming folder changed")
                                .setMessage(
                                    "New folder: ${TdLib.streamRoot(applicationContext).absolutePath}. " +
                                        "Sign in again with your Telegram code (the session database was moved).",
                                )
                                .setPositiveButton("Open login") { _, _ ->
                                    startActivity(
                                        Intent(this@SettingsActivity, LoginActivity::class.java),
                                    )
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ TMDB

    private fun tmdbDialog() {
        val on = !Credentials.tmdbDisabled(this)
        val key = Credentials.tmdbKey(this)
        val line = when {
            !on -> "Off."
            key == null -> "On, using your saved key."
            else -> "On."
        }
        AlertDialog.Builder(this)
            .setTitle("TMDB metadata")
            .setMessage(line)
            .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                Credentials.setTmdbDisabled(this, on)
            }
            .setNegativeButton("Custom key") { _, _ -> tmdbKeyInput() }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun tmdbKeyInput() {
        val input = EditText(this).apply {
            hint = "32-character TMDB API key"
            setSingleLine()
            setText(Credentials.tmdbKey(this@SettingsActivity) ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("TMDB API key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Credentials.setTmdbKey(this, input.text.toString())
                Credentials.setTmdbDisabled(this, false)
            }
            .setNeutralButton("Remove") { _, _ -> Credentials.setTmdbKey(this, null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------- subtitles

    private fun subtitleDialog() {
        val on = Credentials.subsEnabled(this)
        val key = Credentials.openSubtitlesKey(this)
        val line = buildString {
            append(if (on) "Subtitles are on." else "Subtitles are off.")
            append("  Languages: ").append(Credentials.subsLanguages(this@SettingsActivity))
            append(".  OpenSubtitles: ").append(if (key == null) "no key" else "set")
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setMessage(line)
            .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                Credentials.setSubsEnabled(this, !on)
            }
            .setNegativeButton("Languages") { _, _ -> subsLanguagesInput() }
            .setNeutralButton("OpenSubtitles key") { _, _ -> opensubsKeyInput() }
            .show()
    }

    private fun subsLanguagesInput() {
        val input = EditText(this).apply {
            hint = "en, hi, ml"
            setSingleLine()
            setText(Credentials.subsLanguages(this@SettingsActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitle languages")
            .setMessage("Comma-separated ISO codes in priority order: en, hi, ml, ...")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Credentials.setSubsLanguages(this, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun opensubsKeyInput() {
        val input = EditText(this).apply {
            hint = "OpenSubtitles.com API key"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("OpenSubtitles key")
            .setMessage("Free account at opensubtitles.com → API keys, then paste it here.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Credentials.setOpenSubtitlesKey(this, input.text.toString())
            }
            .setNeutralButton("Remove") { _, _ -> Credentials.setOpenSubtitlesKey(this, null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------- download folder

    /** Label of the default download target (public Movies/TgTV). */
    private fun downloadDefaultLabel(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            "Movies/TgTV (gallery)"
        } else {
            abbreviatePath(
                File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TgTV").absolutePath,
            )
        }
    }

    /**
     * Where finished downloads land: the public gallery by default, or an
     * app-writable folder (internal storage / SD card) picked here. Applies
     * to the next download; existing files stay where they are.
     */
    private fun pickDownloadFolder() {
        val def = downloadDefaultLabel()
        val options = buildList {
            add("Default — Movies/TgTV (gallery)" to "")
            getExternalFilesDirs(null).filterNotNull().forEach { f ->
                val primaryRoot = Environment.getExternalStorageDirectory()?.absolutePath
                val label =
                    if (primaryRoot != null && f.absolutePath.startsWith(primaryRoot))
                        "Internal storage (app folder)\n${f.absolutePath}"
                    else "SD card (app folder)\n${f.absolutePath}"
                add(label to f.absolutePath)
            }
        }
        val current = Credentials.downloadDir(this) ?: ""
        AlertDialog.Builder(this)
            .setTitle("Download folder")
            .setSingleChoiceItems(
                options.map { it.first }.toTypedArray(),
                options.indexOfFirst { it.second == current }.coerceAtLeast(0),
            ) { dialog, which ->
                Credentials.setDownloadDir(this, options[which].second.ifBlank { null })
                dialog.dismiss()
                fillRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------- cache retention

    /** Retention choices in minutes; 0 = on exit, -1 = until the cache cap. */
    private val retentionOptions = listOf(
        "On exit (delete right after a video closes)" to 0,
        "15 minutes" to 15,
        "1 hour" to 60,
        "6 hours" to 360,
        "24 hours" to 1440,
        "Until cleared manually (no auto-delete)" to -1,
    )

    private fun retentionLabel(minutes: Int): String =
        retentionOptions.firstOrNull { it.second == minutes }?.first ?: "$minutes minutes"

    /**
     * How long streamed files stay on disk after a video is closed. Longer
     * retention means replays and re-seeks start instantly from local bytes;
     * "On exit" keeps storage lean.
     */
    private fun retentionDialog() {
        val current = Credentials.cacheRetentionMinutes(this)
        AlertDialog.Builder(this)
            .setTitle("Keep cached videos")
            .setSingleChoiceItems(
                retentionOptions.map { it.first }.toTypedArray(),
                retentionOptions.indexOfFirst { it.second == current }.coerceAtLeast(0),
            ) { dialog, which ->
                Credentials.setCacheRetentionMinutes(this, retentionOptions[which].second)
                dialog.dismiss()
                fillRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------- clear cache

    private fun clearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear cache")
            .setMessage(
                "Delete the streamed-video cache and all downloaded thumbnails?\n\n" +
                    "Files you explicitly downloaded via the download service are kept.",
            )
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val freedMb = TdLib.clearCache() / (1024L * 1024L)
                    HomeActivity.ThumbCache.clear()
                    HomeActivity.ImageCache.clear()
                    HomeActivity.InfoCache.clear()
                    DetailsActivity.BitmapCache.clear()
                    runOnUiThread {
                        fillRows()
                        android.widget.Toast.makeText(
                            this@SettingsActivity,
                            if (freedMb > 0) "Freed ~$freedMb MB" else "Nothing to clear",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- updates

    /** The update row mirrors the process-wide update state. */
    private fun renderUpdate(st: UpdateManager.UpdateState) {
        val sub = findViewById<TextView>(R.id.txtUpdateSub)
        sub.text = when (st.phase) {
            UpdateManager.Phase.IDLE -> "Check GitHub for a new version"
            UpdateManager.Phase.CHECKING -> "Checking…"
            UpdateManager.Phase.UP_TO_DATE ->
                "You're on the latest version (v${Updater.installedVersion(this)})"
            UpdateManager.Phase.AVAILABLE ->
                "v${st.tag}${if (st.prerelease) " (beta)" else ""} available — tap to download & install"
            UpdateManager.Phase.DOWNLOADING -> "Downloading… ${st.progress}%"
            UpdateManager.Phase.READY -> "v${st.tag}${if (st.prerelease) " (beta)" else ""} ready — tap to install"
            UpdateManager.Phase.FAILED -> "Download failed — tap to retry"
        }
    }

    /**
     * The row is stateful: each phase advances on the next tap — check,
     * download, install — and the download keeps running in the background
     * even if this screen is closed.
     */
    private fun onUpdateRow() {
        when (UpdateManager.state.value.phase) {
            UpdateManager.Phase.READY -> {
                val sub = findViewById<TextView>(R.id.txtUpdateSub)
                when (UpdateManager.install(this)) {
                    // APK stays on disk + state stays READY: a failed install
                    // is retried with a tap, never with a re-download.
                    Updater.InstallResult.NEEDS_PERMISSION ->
                        sub.text = "Allow \u201CInstall unknown apps\u201D for TGTV, then tap again"
                    Updater.InstallResult.FAILED ->
                        sub.text = "Couldn't open the installer — tap to try again"
                    else -> Unit
                }
            }
            UpdateManager.Phase.AVAILABLE -> UpdateManager.download(this)
            UpdateManager.Phase.CHECKING, UpdateManager.Phase.DOWNLOADING -> Unit
            else -> UpdateManager.check(this)
        }
    }

    // -------------------------------------------------------- sign out

    private fun signOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out")
            .setMessage("Delete this Telegram session and return to the login screen?")
            .setPositiveButton("Sign out") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    TdLib.resetDb(applicationContext)
                    Credentials.clear(applicationContext)
                    runOnUiThread {
                        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                        finishAffinity()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
