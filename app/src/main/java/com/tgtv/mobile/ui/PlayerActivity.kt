package com.tgtv.mobile.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tgtv.mobile.R
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.model.VideoMapper
import com.tgtv.mobile.model.WatchProgress
import com.tgtv.mobile.sub.Languages
import com.tgtv.mobile.sub.SubtitlesClient
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib
import com.tgtv.mobile.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/** Full-screen player that streams the Telegram file through [TdVideoStreamDataSource]. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context, video: VideoItem, startMs: Long = 0L): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_ID, video.fileId)
                putExtra(EXTRA_SIZE, video.fileSize)
                putExtra(EXTRA_MIME, video.mimeType)
                putExtra(EXTRA_NAME, video.fileName.ifBlank { video.title })
                putExtra(EXTRA_REMOTE, video.remoteFileId)
                putExtra(EXTRA_KIND, video.fileKind)
                putExtra(EXTRA_CHAT_ID, video.chatId)
                putExtra(EXTRA_MSG_ID, video.messageId)
                putExtra(EXTRA_DURATION, video.durationSeconds)
                putExtra(EXTRA_START_MS, startMs)
            }

        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_REMOTE = "remote"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CHAT_ID = "chatId"
        private const val EXTRA_MSG_ID = "msgId"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_START_MS = "startMs"
    }

    private var player: ExoPlayer? = null
    private var currentFileId = 0
    private var currentSize = 0L
    private var currentDuration = 0
    /** Longest duration ExoPlayer reported this session (see onEvents). */
    private var bestDurationMs = 0L
    private var fileName = ""
    private var externalSubsTried = false

    // Kept for re-resolving on retry: a stale file reference never recovers
    // by re-preparing - it must be re-resolved from the live message first.
    private var rawFileId = 0
    private var remoteRef = ""
    private var kind = "video"
    private var chatId = 0L
    private var msgId = 0L

    // Watch progress ("Resume"): start position + save-once-per-play guard.
    private var startMs = 0L
    private var ended = false
    private var progressSaved = false

    // Gesture state
    private val hudHandler = Handler(Looper.getMainLooper())
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    private var speedIndex = 0
    private var longPressBoost = false
    private var scrollMode = 0 // 0 none, 1 brightness, 2 volume

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        fileName = intent.getStringExtra(EXTRA_NAME) ?: ""
        currentDuration = intent.getIntExtra(EXTRA_DURATION, 0)
        rawFileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
        if (rawFileId <= 0) {
            Toast.makeText(this, "Invalid video reference", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        msgId = intent.getLongExtra(EXTRA_MSG_ID, 0L)
        remoteRef = intent.getStringExtra(EXTRA_REMOTE) ?: ""
        kind = intent.getStringExtra(EXTRA_KIND) ?: "video"
        startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
        // "Play Again" (startMs == 0 with an existing entry) restarts from 0,
        // so the stored progress must not come back afterwards.
        if (startMs == 0L) {
            WatchProgress.clear(this, chatId, msgId, fileName)
        }

        lifecycleScope.launch {
            val (fileId, size) = withContext(Dispatchers.IO) { resolveLiveFile(currentSizeFallback()) }
            currentFileId = fileId
            currentSize = size
            setupPlayer(fileId, size, startMs)
        }
    }

    private fun currentSizeFallback(): Long = intent.getLongExtra(EXTRA_SIZE, 0L)

    /**
     * Re-resolves a live file reference for this session.
     *
     * GetMessage first, always: only message-derived files get the TDLib
     * auto-repair treatment, so their ids stay fresh across sessions. Files
     * resolved through GetRemoteFile carry remote references that expire and
     * then fail with "File not found" - that path is the fallback only, used
     * for rows without chat coordinates.
     */
    private suspend fun resolveLiveFile(sizeFallback: Long): Pair<Int, Long> {
        var fileId = rawFileId
        var size = sizeFallback
        if (chatId != 0L && msgId != 0L) {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    TdLib.send(TdApi.GetMessage(chatId, msgId)) as? TdApi.Message
                }.getOrNull()
            }
            if (msg != null) {
                VideoMapper.mapMessage(msg, "")?.let { v ->
                    if (v.fileId > 0) {
                        if (v.fileSize > 0) size = v.fileSize
                        return v.fileId to size
                    }
                }
            }
            TdLib.log("player: GetMessage resolve failed, falling back to remote id")
        }
        if (remoteRef.isNotBlank()) {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    TdLib.send(TdApi.GetRemoteFile(remoteRef, TdLib.fileTypeFor(kind))) as? TdApi.File
                }.getOrNull()
            }
            if (fresh != null && fresh.id > 0) {
                fileId = fresh.id
                if (fresh.size > 0) size = fresh.size
            } else {
                TdLib.log("player: remote re-resolve failed, using stored fileId=$fileId")
            }
        }
        return fileId to size
    }

    private fun setupPlayer(fileId: Int, size: Long, startPosMs: Long = 0L) {
        TdLib.activeStreamFileId = fileId
        currentSize = size
        // Fresh DataSource per load: ExoPlayer opens several DataSources
        // concurrently (e.g. MP4 reads the moov trailer at EOF while the main
        // loader reads the start) and they must not share mutable state.
        val dataSourceFactory: DataSource.Factory =
            DataSource.Factory { TdVideoStreamDataSource(fileId, size) }

        val errorView = findViewById<TextView>(R.id.playerError)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // YouTube-style time-based buffering: start play as soon as ~2s is
        // buffered, then keep looking ahead up to 5 minutes of video.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 300_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setBackBuffer(60_000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Hold a wake lock while rendering so phones / the emulator
                // don't doze mid-playback.
                setWakeMode(C.WAKE_MODE_NETWORK)
                // Mime type left unset: media3 sniffs the container (mp4/mkv/webm...)
                val item = MediaItem.fromUri(Uri.parse("tgvfile://$fileId/$size"))
                // Resume where the user last left off (0 = play from the start)
                setMediaItem(item, startPosMs)
                // Subtitles setting drives both embedded selection and web fetch
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setSelectUndeterminedTextLanguage(Credentials.subsEnabled(this@PlayerActivity))
                    .build()
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errorView.visibility = View.VISIBLE
                        errorView.text = playbackErrorText(error)
                    }

                    /** Track the largest duration ever seen: a TDLib stream
                     * reports only the downloaded prefix, so the value at exit
                     * can be far short of the real runtime. */
                    override fun onEvents(player: Player, events: Player.Events) {
                        val d = player.duration
                        if (d != C.TIME_UNSET && d > bestDurationMs) bestDurationMs = d
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // Watched through: drop the resume row so the details
                        // screen goes back to a plain "Play".
                        if (state == Player.STATE_ENDED) {
                            ended = true
                            WatchProgress.clear(this@PlayerActivity, chatId, msgId, fileName)
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        maybeFetchExternalSubtitles(tracks)
                    }
                })
            }
        player = exo
        val playerView = findViewById<PlayerView>(R.id.playerView)
        playerView.player = exo
        playerView.subtitleView?.setBottomPaddingFraction(0.02f)
        // The ratio / subtitle buttons must never burn into the picture: they
        // show and hide exactly together with the playback controls.
        val overlay = findViewById<View>(R.id.playerOverlay)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> overlay.visibility = v },
        )
        // Sync to the controller's state right now - the auto-hide may have
        // fired before the listener was attached.
        overlay.visibility =
            if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.btnRatio).setOnClickListener { cycleResizeMode(playerView) }
        findViewById<ImageButton>(R.id.btnSubs).setOnClickListener { showSubtitlesDialog() }
        val btnSpeed = findViewById<TextView>(R.id.btnSpeed)
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            applySpeed(speeds[speedIndex])
            btnSpeed.text = speedLabel(speeds[speedIndex])
            showHud("Speed ${speedLabel(speeds[speedIndex])}")
        }
        installGestures(playerView)
        // Tap the error banner to retry playback: re-resolve the file
        // reference first (a stale one never recovers by re-preparing),
        // then rebuild the player at the same position.
        errorView.setOnClickListener {
            it.visibility = View.GONE
            retryPlayback()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive playback: hide the status bar and the navigation / task
        // bar for the whole video, swipe-in only when the user wants them.
        hideSystemBars()
    }

    private fun speedLabel(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

    private fun applySpeed(v: Float) {
        player?.playbackParameters = androidx.media3.common.PlaybackParameters(v)
    }

    /** Centered, auto-hiding feedback bubble for gestures. */
    private fun showHud(text: String) {
        val hud = findViewById<TextView>(R.id.gestureHud)
        hud.text = text
        hud.visibility = View.VISIBLE
        hudHandler.removeCallbacksAndMessages(null)
        hudHandler.postDelayed({ hud.visibility = View.GONE }, 700)
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, dur))
        showHud(if (deltaMs > 0) "▶▶  +${deltaMs / 1000}s" else "◀◀  −${-deltaMs / 1000}s")
    }

    /**
     * Touch gestures on the video surface (controls keep working - they are
     * child views and get their touches first):
     *  - single tap: show / hide controls
     *  - double tap left / right third: seek -10s / +10s
     *  - vertical swipe on the left half: brightness; right half: volume
     *  - long-press: 2x speed while held
     */
    private fun installGestures(playerView: PlayerView) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var startVol = 0
        var startBright = 0.5f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scrollMode = 0
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                startBright = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = playerView.width / 3f
                when {
                    e.x < third -> seekBy(-10_000L)
                    e.x > third * 2 -> seekBy(10_000L)
                    else -> player?.let { it.playWhenReady = !it.playWhenReady }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressBoost = true
                applySpeed(2f)
                showHud("2x speed")
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                if (e1 == null) return false
                val h = playerView.height.toFloat().coerceAtLeast(1f)
                val dyTotal = e1.y - e2.y
                val dxTotal = e2.x - e1.x
                if (scrollMode == 0) {
                    // Only commit to a vertical gesture once it is clearly vertical.
                    if (Math.abs(dyTotal) < 24f || Math.abs(dyTotal) < Math.abs(dxTotal)) return false
                    scrollMode = if (e1.x < playerView.width / 2f) 1 else 2
                }
                val frac = (dyTotal / (h * 0.8f))
                if (scrollMode == 1) {
                    val b = (startBright + frac).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = b }
                    showHud("☀  ${(b * 100).toInt()}%")
                } else {
                    val v = (startVol + frac * maxVol).toInt().coerceIn(0, maxVol)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    showHud("🔊  ${(v * 100) / maxVol}%")
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (longPressBoost) {
                    longPressBoost = false
                    applySpeed(speeds[speedIndex])
                }
                scrollMode = 0
            }
            true
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // Some launchers restore the bars on window focus gain: re-hide once.
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
            if (focused) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * When the file ships no subtitle tracks at all and web subtitles are
     * enabled, resolves the title through [TmdbClient] and side-loads an
     * OpenSubtitles file (bring-your-own API key). Feedback is always shown
     * so the fetch is never silent.
     */
    private fun maybeFetchExternalSubtitles(tracks: Tracks) {
        if (externalSubsTried) return
        if (!Credentials.subsEnabled(this)) return
        if (fileName.isBlank()) return
        val hasText = tracks.groups.any {
            it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0)
        }
        if (hasText) return
        externalSubsTried = true
        fetchExternalSubtitles(force = false)
    }

    /**
     * Manual "Search subtitles" (btnSubs): bypasses the once-per-play guard
     * and the Settings switch so users can retry at any time.
     */
    private fun fetchExternalSubtitles(force: Boolean) {
        val exo = player ?: return
        val context = applicationContext
        if (!force && !Credentials.subsEnabled(this)) return
        if (fileName.isBlank()) return
        if (!SubtitlesClient.hasKey(context)) {
            TdLib.log("subs: no OpenSubtitles API key configured")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val fetched = SubtitlesClient.fetchFor(context, subVideoItem()) ?: run {
                TdLib.log("subs: no external subtitles found for '$fileName'")
                return@launch
            }
            withContext(Dispatchers.Main) {
                attachSubtitle(exo, fetched.file, fetched.language, fetched.label)
                TdLib.log("subs: attached ${Languages.nameOf(fetched.language)}")
            }
        }
    }

    /** Video descriptor for the subtitle client - built from the current stream. */
    private fun subVideoItem() = VideoItem(
        messageId = 0L,
        chatTitle = "",
        title = fileName,
        fileId = currentFileId,
        fileSize = currentSize,
        durationSeconds = currentDuration,
        mimeType = "",
        fileName = fileName,
        thumbnailFileId = 0,
    )

    /**
     * MX-Player-style search box: pre-filled with the file name, editable.
     * The actual search always covers the TMDB title AND the full file name;
     * the typed text just adds one more strategy on top.
     */
    private fun showSubtitleSearchDialog() {
        val context = applicationContext
        if (!SubtitlesClient.hasKey(context)) {
            TdLib.log("subs: no OpenSubtitles API key configured")
            return
        }
        val box = EditText(this).apply {
            setSingleLine(true)
            hint = "Movie, show or file name"
            setText(TmdbClient.parseTitle(fileName).ifBlank { fileName }.substringBeforeLast('.'))
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle("Search subtitles")
            .setMessage(
                "Searched with the TMDB title and the full file name. " +
                    "Type your own text to search that too.",
            )
            .setView(wrap)
            .setPositiveButton("Search") { _, _ -> runSubtitleSearch(box.text?.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Runs the multi-strategy search behind a progress dialog, then lists the hits. */
    private fun runSubtitleSearch(userQuery: String?) {
        val exo = player ?: return
        val context = applicationContext
        if (fileName.isBlank()) return
        val progress = AlertDialog.Builder(this)
            .setTitle("Searching subtitles…")
            .setView(ProgressBar(this))
            .setCancelable(true)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            val options = SubtitlesClient.searchOptions(context, subVideoItem(), userQuery)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                runCatching { progress.dismiss() }
                if (options.isEmpty()) {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("Subtitles")
                        .setMessage("No subtitles found for \"$fileName\".")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showSubtitleOptionsDialog(exo, options)
                }
            }
        }
    }

    /** Lists every candidate ("Language · flags" + release name) for the user to pick. */
    private fun showSubtitleOptionsDialog(exo: ExoPlayer, options: List<SubtitlesClient.Option>) {
        val names = options.map { o ->
            val lang = Languages.nameOf(o.language)
            val head = if (o.flags.isBlank()) lang else "$lang · ${o.flags}"
            head + "\n" + o.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitles (${options.size})")
            .setItems(names) { _, which -> downloadAndAttach(exo, options[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Downloads the picked candidate (silent) and side-loads it into the player. */
    private fun downloadAndAttach(exo: ExoPlayer, option: SubtitlesClient.Option) {
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val fetched = SubtitlesClient.download(context, subVideoItem(), option)
                ?: run {
                    TdLib.log("subs: download failed for file_id=${option.fileId}")
                    return@launch
                }
            withContext(Dispatchers.Main) {
                if (player === exo) attachSubtitle(exo, fetched.file, fetched.language, fetched.label)
                TdLib.log("subs: attached ${Languages.nameOf(fetched.language)}")
            }
        }
    }

    /** Cycles Fit -> Zoom (crop) -> Stretch picture geometry. */
    private fun cycleResizeMode(playerView: PlayerView) {
        val next = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = next
        val label = when (next) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (fills screen, crops edges)"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            else -> "Fit (original ratio)"
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    /**
     * Subtitle picker: embedded tracks (selectable), Off, and a manual
     * OpenSubtitles fetch for files without embedded subs.
     */
    private fun showSubtitlesDialog() {
        val exo = player ?: return
        val names = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        names.add("Off")
        actions.add {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

        // Search sits right under "Off" so it's always reachable - the
        // embedded track list below can be dozens of rows long.
        names.add("Search subtitles…")
        actions.add {
            showSubtitleSearchDialog()
        }

        val textGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isNotEmpty()) {
            names.add("Auto (embedded)")
            actions.add {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
            }
            for (g in textGroups) {
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val label = f.label
                        ?: f.language?.let { Languages.nameOf(it) }
                        ?: "Track ${names.size}"
                    names.add(label)
                    actions.add {
                        exo.trackSelectionParameters = exo.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(names.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    /** Re-opens the media item with the fetched subtitle attached side-loaded. */
    private fun attachSubtitle(exo: ExoPlayer, file: File, language: String, label: String) {
        val position = exo.currentPosition
        val playWhenReady = exo.playWhenReady
        val config = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setLabel(label.ifBlank { Languages.nameOf(language) })
            .setId("tgsubs-$language")
            .build()
        val base = exo.currentMediaItem ?: return
        val item = base.buildUpon()
            .setSubtitleConfigurations(listOf(config))
            .build()
        exo.setMediaItem(item, position)
        exo.prepare()
        exo.playWhenReady = playWhenReady
    }

    /**
     * Human-readable playback error. Streaming failures carry the TDLib
     * reason in the cause chain - most importantly Telegram's rule that
     * files over 2 GB can only be downloaded by Premium accounts.
     */
    private fun playbackErrorText(error: PlaybackException): String {
        val causeMsg = generateSequence(error.cause) { t -> t.cause }
            .filterNotNull()
            .map { t -> t.message.orEmpty() }
            .firstOrNull { m -> m.isNotBlank() } ?: ""
        val hint = when {
            causeMsg.contains("premium", ignoreCase = true) ||
                causeMsg.contains("too big", ignoreCase = true) ||
                causeMsg.contains("FILE_TOO_BIG", ignoreCase = true) ->
                "Telegram only delivers the file parts needed for streaming beyond 2 GB to Premium accounts."
            else -> "Tap here to retry, or long-press the item in the library to download."
        }
        return buildString {
            append("Playback error: ")
            append(error.errorCodeName)
            if (causeMsg.isNotBlank()) {
                append('\n')
                append(causeMsg)
            }
            append('\n')
            append(hint)
        }
    }

    /**
     * Full retry after a playback error: re-resolve the file reference (the
     * old code just re-prepared with the same dead id, so "File not found"
     * never recovered) and rebuild the player at the last position.
     */
    private fun retryPlayback() {
        val exo = player ?: return
        val position = if (exo.playbackState == Player.STATE_IDLE) 0L else exo.currentPosition
        lifecycleScope.launch {
            val (fileId, size) = withContext(Dispatchers.IO) { resolveLiveFile(currentSize) }
            player?.release()
            player = null
            if (fileId != currentFileId) {
                TdLib.log("player: retry resolved fresh fileId=$fileId (was $currentFileId)")
                currentFileId = fileId
            }
            if (size > 0) currentSize = size
            setupPlayer(fileId, currentSize, position)
        }
    }

    /**
     * Persists where playback stopped so the details screen can offer
     * "Resume". Cleared when the video ended or was watched past 97% -
     * ported from the TV player's onStop semantics.
     */
    private fun saveProgress() {
        if (progressSaved) return
        progressSaved = true
        val exo = player ?: return
        val pos = exo.currentPosition
        val dur = maxOf(
            if (currentDuration > 0) currentDuration * 1000L else 0L,
            bestDurationMs,
            exo.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
        )
        if (ended || !WatchProgress.resumable(pos, dur)) {
            WatchProgress.clear(this, chatId, msgId, fileName)
        } else {
            WatchProgress.save(this, chatId, msgId, fileName, pos, dur)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-arm saving: a play -> background -> resume -> exit cycle should
        // persist the final position, not just the first stop
        progressSaved = false
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        hudHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (currentFileId > 0) {
            if (TdLib.activeStreamFileId == currentFileId) {
                TdLib.activeStreamFileId = null
            }
            TdLib.scheduleStreamCachePurge(currentFileId)
        }
    }
}
