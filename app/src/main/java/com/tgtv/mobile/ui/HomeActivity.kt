package com.tgtv.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tgtv.mobile.R
import com.tgtv.mobile.model.ChatSelection
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.model.VideoMapper
import com.tgtv.mobile.model.WatchProgress
import com.tgtv.mobile.model.Watchlist
import com.tgtv.mobile.ota.UpdateManager
import com.tgtv.mobile.ota.Updater
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdError
import com.tgtv.mobile.td.TdLib
import com.tgtv.mobile.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mobile home, styled like the reference: a hero section for the featured
 * (newest) video with a big red play button, a poster grid below and bottom
 * tabs over one grid — Home library and the bookmarked My List.
 * Works for both user sessions (Saved Messages scan) and bot sessions
 * (live messages from chats the bot is in).
 */
class HomeActivity : AppCompatActivity() {

    private val loading = AtomicBoolean(false)
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private lateinit var bottomNav: BottomNavigationView
    private val items = mutableListOf<VideoItem>()

    /** Live hero views from the grid's first item; null when recycled. */
    private var heroViews: HeroViews? = null

    class HeroViews(
        val root: View,
        val img: ImageView,
        val title: TextView,
        val logo: ImageView,
        val overview: TextView,
    )

    private lateinit var heroAdapter: HeroAdapter
    private lateinit var labelAdapter: LabelAdapter
    private lateinit var continueAdapter: ContinueAdapter
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText

    /** Current library filter (empty = no filter). */
    private var query: String = ""

    /** Hero auto-rotation is paused until this time after the user touches it. */
    private var heroPausedUntil = 0L
    private lateinit var videoAdapter: VideoAdapter

    /** What the grid is currently showing; swapped by tab selection. */
    private var displayed: List<VideoItem> = items

    /** Video shown in the hero (Home tab only). */
    private var heroVideo: VideoItem? = null

    /** The video the hero is currently rendered for; identical rebinds are skipped. */
    private var heroBound: VideoItem? = null

    private enum class Tab { HOME, MYLIST }

    private var tab: Tab = Tab.HOME

    /** True when the live session is a bot (changes empty-state copy). */
    private var botSession = false

    private val videoUpdateListener: (TdApi.Object) -> Unit = { update ->
        if (update is TdApi.UpdateNewMessage && VideoMapper.isVideoMessage(update.message.content)) {
            lifecycleScope.launch(Dispatchers.IO) {
                delay(1200)
                if (!loading.get()) reload()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ThumbCache.init(java.io.File(filesDir, "thumbs"))
        ImageCache.init(java.io.File(filesDir, "posters"))
        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        bottomNav = findViewById(R.id.bottomNav)
        heroAdapter = HeroAdapter()
        labelAdapter = LabelAdapter()
        continueAdapter = ContinueAdapter()
        videoAdapter = VideoAdapter(::openDetails)
        // Shared view types: keeps getItemViewType returning our raw
        // TYPE_* constants, so the span lookup below stays reliable.
        val concat = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(false).build(),
            heroAdapter,
            continueAdapter,
            labelAdapter,
            videoAdapter,
        )
        grid.layoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                // Structure is fixed: [hero?] [continue?] [label] [rows...].
                // Position math - never ask the concat for view types, its
                // type mapping proved unreliable across layout passes.
                // Rows are video cards (span 1) or full-width items (span 3):
                // chat-group headers and the swipeable per-chat card rows
                // shown when the group toggle is on.
                override fun getSpanSize(position: Int): Int {
                    val before = heroAdapter.itemCount +
                        continueAdapter.itemCount + labelAdapter.itemCount
                    if (position < before) return 3
                    return if (videoAdapter.isFullSpan(position - before)) 3 else 1
                }
            }
        }
        grid.adapter = concat
        bottomNav.clipToOutline = true

        searchRow = findViewById(R.id.searchRow)
        searchInput = findViewById(R.id.searchInput)
        findViewById<View>(R.id.btnSearch).setOnClickListener { toggleSearch() }
        // Downloads progress in the status bar; Android 13+ denies the
        // permission by default, so ask once up front.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        findViewById<View>(R.id.btnSearchClear).setOnClickListener {
            if (searchInput.text.isNullOrEmpty()) toggleSearch() else searchInput.setText("")
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                if (tab == Tab.MYLIST) showMyList() else showHome()
            }
        })
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnSort).setOnClickListener { sortDialog() }
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    if (tab != Tab.HOME) {
                        tab = Tab.HOME
                        showHome()
                    }
                    true
                }
                R.id.nav_mylist -> {
                    tab = Tab.MYLIST
                    showMyList()
                    true
                }
                R.id.nav_profile -> {
                    // Settings stays a separate screen; keep the current tab selected.
                    startActivity(Intent(this, SettingsActivity::class.java))
                    false
                }
                else -> false
            }
        }
        TdLib.addListener(videoUpdateListener)
        showHome()
        refresh()
        autoCheckUpdates()
    }

    override fun onDestroy() {
        TdLib.removeListener(videoUpdateListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------ updates

    /** True once this process has done its silent launch-time check. */
    private var updateOfferShown = false
    private var updateDlg: AlertDialog? = null

    /**
     * Silent update check on Home launch - once per process, so it never
     * nags. Mirrors the process-wide UpdateManager state: an available
     * release gets an offer dialog; a download started here shows progress
     * and hands off to the installer when done. Leaving this screen never
     * interrupts the download (it lives in UpdateManager, not here).
     */
    private fun autoCheckUpdates() {
        android.util.Log.d("UpdateManager", "autoCheckUpdates: entry (checked=${HomeActivity.autoUpdateChecked})")
        // Every instance observes the shared state, so the offer always lands
        // on the Home that is actually on screen; only the silent check is
        // done once per process.
        lifecycleScope.launch {
            UpdateManager.state.collect { st ->
                when (st.phase) {
                    UpdateManager.Phase.AVAILABLE -> if (!updateOfferShown) {
                        updateOfferShown = true
                        showUpdateOffer(st.tag)
                    }
                    UpdateManager.Phase.DOWNLOADING -> updateDlg?.let { dlg ->
                        dlg.setTitle("Downloading update")
                        dlg.setMessage("TGTV v${st.tag} — ${st.progress}%")
                    }
                    UpdateManager.Phase.READY -> {
                        updateDlg?.dismiss()
                        updateDlg = null
                        when (UpdateManager.install(this@HomeActivity)) {
                            // NEEDS_PERMISSION / FAILED keep the APK and stay
                            // READY - the user retries the install only, the
                            // download is never repeated.
                            Updater.InstallResult.NEEDS_PERMISSION -> Toast.makeText(
                                this@HomeActivity,
                                "Allow \u201CInstall unknown apps\u201D for TGTV, then install again from Settings.",
                                Toast.LENGTH_LONG,
                            ).show()
                            Updater.InstallResult.FAILED -> Toast.makeText(
                                this@HomeActivity,
                                "Couldn't open the installer - try again from Settings.",
                                Toast.LENGTH_LONG,
                            ).show()
                            else -> Unit
                        }
                    }
                    UpdateManager.Phase.FAILED -> {
                        updateDlg?.setCancelable(true)
                        updateDlg?.setMessage("Download failed — check your connection and try again from Settings.")
                        updateDlg = null
                    }
                    else -> Unit
                }
            }
        }
        if (HomeActivity.autoUpdateChecked) return
        HomeActivity.autoUpdateChecked = true
        // The network stack (especially DNS) can still be coming up seconds
        // after launch; retry a failed check a couple of times before giving
        // up quietly for this process.
        lifecycleScope.launch {
            repeat(3) {
                UpdateManager.check(this@HomeActivity)
                UpdateManager.state.first { it.phase != UpdateManager.Phase.CHECKING }
                if (UpdateManager.state.value.phase != UpdateManager.Phase.FAILED) return@launch
                delay(5_000)
            }
        }
    }

    /** Offer dialog for a newly found release; Download starts the transfer. */
    private fun showUpdateOffer(tag: String) {
        if (isFinishing || isDestroyed) return
        updateDlg = AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("TGTV v$tag is ready to install.")
            .setPositiveButton("Download") { _, _ -> UpdateManager.download(this) }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the "Install unknown apps" page: if the user just
        // granted it, launch the installer right away.
        UpdateManager.resumeInstall(this)
        // Settings may have changed the streaming dir or the picked chats;
        // My List may have been edited in Details. Refresh accordingly.
        if (!loading.get()) {
            if (chatsChanged) {
                chatsChanged = false
                refresh()
                return
            }
            when (tab) {
                Tab.MYLIST -> showMyList()
                else -> if (items.isEmpty()) refresh() else showHome()
            }
        }
    }

    // ------------------------------------------------------------- tabs

    /** Filters a list by the current search text (matches title and file name). */
    private fun filtered(list: List<VideoItem>): List<VideoItem> {
        if (query.isBlank()) return list
        val q = query.lowercase()
        return list.filter {
            it.title.lowercase().contains(q) || it.fileName.lowercase().contains(q) ||
                TmdbClient.parseTitle(it.title).lowercase().contains(q) ||
                InfoCache.get(it.title)?.title?.lowercase()?.contains(q) == true
        }
    }

    private fun toggleSearch() {
        val show = searchRow.visibility != View.VISIBLE
        searchRow.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            searchInput.requestFocus()
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            searchInput.setText("")
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        }
    }

    private fun showHome() {
        val searching = query.isNotBlank()
        displayed = sortedForDisplay(if (searching) filtered(items) else items)
        labelAdapter.text = if (searching) "Results (${displayed.size})" else "My Library"
        labelAdapter.refresh()
        videoAdapter.rebuild()
        bindHero(if (searching) null else items.firstOrNull())
        refreshContinue()
        startHeroRotation()
        prefetchVisibleThumbs()
        prefetchHeroBackdrops()
        renderStatusFor(displayed)
    }

    private fun showMyList() {
        displayed = sortedForDisplay(filtered(Watchlist.all(this)))
        labelAdapter.text = if (query.isNotBlank()) "Results (${displayed.size})" else "My List"
        labelAdapter.refresh()
        videoAdapter.rebuild()
        bindHero(null)
        continueAdapter.setData(emptyList())
        prefetchVisibleThumbs()
        renderStatusFor(displayed)
    }

    // -------------------------------------------------------------- sort

    /**
     * Orders the library for display. The sort pref picks the order; when
     * the group toggle is on, the list is additionally made chat-consecutive
     * (the adapter then renders one header + swipeable row per chat).
     */
    private fun sortedForDisplay(list: List<VideoItem>): List<VideoItem> {
        val ordered = when (Credentials.homeSort(this)) {
            SORT_OLDEST -> list.asReversed()
            SORT_TITLE -> list.sortedBy {
                TmdbClient.parseTitle(it.title).ifBlank { it.title }.lowercase()
            }
            SORT_SIZE -> list.sortedByDescending { it.fileSize }
            else -> list
        }
        return if (Credentials.homeGroup(this)) groupedByChats(ordered) else ordered
    }

    private fun groupedByChats(list: List<VideoItem>): List<VideoItem> {
        val groups = LinkedHashMap<Any, MutableList<VideoItem>>()
        for (v in list) groups.getOrPut(v.chatId) { mutableListOf() }.add(v)
        return groups.values.flatten()
    }

    private fun sortDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sort, null)
        val choices = mapOf(
            SORT_NEWEST to view.findViewById<RadioButton>(R.id.rbNewest),
            SORT_OLDEST to view.findViewById<RadioButton>(R.id.rbOldest),
            SORT_TITLE to view.findViewById<RadioButton>(R.id.rbTitle),
            SORT_SIZE to view.findViewById<RadioButton>(R.id.rbSize),
        )
        choices[Credentials.homeSort(this)]?.isChecked = true
        val groupSwitch = view.findViewById<Switch>(R.id.switchGroup)
        groupSwitch.isChecked = Credentials.homeGroup(this)
        AlertDialog.Builder(this)
            .setTitle("Sort library")
            .setView(view)
            .setPositiveButton("Done") { _, _ ->
                val mode = choices.entries.firstOrNull { it.value.isChecked }?.key
                    ?: SORT_NEWEST
                Credentials.setHomeSort(this, mode)
                Credentials.setHomeGroup(this, groupSwitch.isChecked)
                if (tab == Tab.MYLIST) showMyList() else showHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Cycles the featured hero through every film in the library so the
     * spotlight changes on its own, not just on launch.
     */
    private fun startHeroRotation() {
        if (heroRotationJob?.isActive == true) return
        heroRotationJob = lifecycleScope.launch {
            while (isActive) {
                // Warm the next film's art BEFORE the switch, so the hero
                // that comes into view is already complete - no pop-in.
                if (tab == Tab.HOME && displayed.size >= 2 && heroAdapter.visible &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    prefetchNextHeroArt()
                }
                delay(8_000)
                if (tab != Tab.HOME || displayed.size < 2) continue
                if (!heroAdapter.visible) continue
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                if (System.currentTimeMillis() < heroPausedUntil) continue
                val idx = displayed.indexOfFirst { it == heroVideo }
                bindHero(displayed[(idx + 1).mod(displayed.size)])
            }
        }
    }

    /**
     * Loads the upcoming hero's backdrop + title logo into the image cache
     * (and resolves its TMDB info if still missing) so the switch renders
     * instantly instead of updating as soon as it comes into view.
     */
    private suspend fun prefetchNextHeroArt() {
        val list = displayed.toList()
        if (list.size < 2) return
        val idx = list.indexOfFirst { it == heroVideo }
        val next = list[(idx + 1).mod(list.size)]
        withContext(Dispatchers.IO) {
            val info = InfoCache.get(next.title) ?: runCatching {
                TmdbClient.lookupQuick(applicationContext, next.title)
            }.getOrNull()?.also { InfoCache.put(next.title, it) } ?: return@withContext
            info.backdropPath?.let { runCatching { ImageCache.load(IMAGE_W780 + it) } }
            info.logoPath?.let { runCatching { ImageCache.load(IMAGE_W500 + it) } }
        }
    }

    private fun stopHeroRotation() {
        heroRotationJob?.cancel()
        heroRotationJob = null
    }

    private fun renderStatusFor(list: List<VideoItem>) {
        if (loading.get()) return
        when {
            list.isNotEmpty() -> status.visibility = View.GONE
            query.isNotBlank() -> {
                status.visibility = View.VISIBLE
                status.text = "No matches for \"$query\"."
            }
            tab == Tab.MYLIST -> {
                status.visibility = View.VISIBLE
                status.text = "Your list is empty.\nTap the heart on any poster to bookmark it here."
            }
            botSession -> {
                status.visibility = View.VISIBLE
                status.text = "No videos yet.\nOpen the bot in Telegram and send or forward any " +
                    "movie or series file to it - it appears here instantly. Add the bot to your " +
                    "groups to stream from those chats too."
            }
            else -> {
                status.visibility = View.VISIBLE
                status.text = "No videos yet.\nForward a movie or series file to yourself in " +
                    "Saved Messages, then refresh."
            }
        }
    }

    // -------------------------------------------------------------- hero

    /** View type of the full-width hero item inside the grid. */
    companion object {
        const val TYPE_VIDEO = 0
        const val TYPE_HERO = 1
        const val TYPE_LABEL = 2
        const val TYPE_CONTINUE = 3
        const val TYPE_GROUP_HEADER = 4
        const val TYPE_GROUP_ROW = 5

        /** Home sort modes (Credentials.homeSort). */
        const val SORT_NEWEST = 0
        const val SORT_OLDEST = 1
        const val SORT_TITLE = 2
        const val SORT_SIZE = 3

        /** Telegram's free-account download ceiling; bigger files need Premium. */
        const val BIG_FILE_BYTES = 2_000_000_000L

        /** Set by ChatSelectActivity; the next onResume reloads the library. */
        @Volatile
        var chatsChanged = false

        /** True once this process has done its silent launch-time update check. */
        var autoUpdateChecked = false
    }

    /** Crossfades the featured video around the screen. */
    private var heroRotationJob: Job? = null

    private fun bindHero(video: VideoItem?) {
        heroVideo = video
        if (video == null) {
            heroBound = null
            stopHeroRotation()
            if (heroAdapter.visible) {
                heroAdapter.visible = false
                heroAdapter.notifyItemRemoved(0)
            }
            return
        }
        if (!heroAdapter.visible) {
            heroAdapter.visible = true
            heroAdapter.notifyItemInserted(0)
        }
        if (heroBound == video) return // already rendered - keep title/backdrop untouched
        val views = heroViews
        if (views == null) {
            // Holder not materialised yet; onCreateViewHolder binds it.
            heroBound = video
            return
        }
        if (views.root.alpha < 1f) {
            heroBound = video
            doBindHero(video)
            views.root.animate().alpha(1f).setDuration(200).start()
            return
        }
        views.root.animate().alpha(0f).setDuration(170).withEndAction {
            heroBound = video
            doBindHero(video)
            views.root.animate().alpha(1f).setDuration(220).start()
        }.start()
    }

    private fun doBindHero(video: VideoItem) {
        heroBound = video
        val views = heroViews ?: return
        heroAdapter.visible = true

        // Season/episode badge and the >2 GB premium note now live on the
        // details screen; the hero keeps only the summary.

        // Resolved info from a previous bind: apply synchronously - no network
        // hop, no flash. Only a genuine first show of this video goes online.
        val cached = InfoCache.get(video.title)
        if (cached != null) {
            val logoPath = cached.logoPath
            val logo = logoPath?.let { ImageCache.get(IMAGE_W500 + it) }
            applyHeroIdentity(views, cached.title, logo)
            applyHeroOverview(views, cached.overview)
            // Logo known but not in memory (fresh process)? Pull it from
            // disk and swap the text name out for the wordmark.
            if (logoPath != null && logo == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = runCatching { ImageCache.load(IMAGE_W500 + logoPath) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (heroVideo != video || heroBound != video) return@withContext
                        heroViews?.let { v -> if (bmp != null) applyHeroIdentity(v, cached.title, bmp) }
                    }
                }
            }
            val backdrop = cached.backdropPath?.let { p -> ImageCache.get(IMAGE_W780 + p) }
            val thumb = backdrop ?: ThumbCache.cached(ThumbCache.key(video))
            // Nothing instant? Keep the previous backdrop on screen as the
            // placeholder so rotation never flashes an empty hero.
            if (thumb != null) views.img.setImageBitmap(thumb)
            if (thumb == null) {
                loadThumbInto(video) { bmp ->
                    if (heroVideo == video) heroViews?.img?.setImageBitmap(bmp)
                }
            }
            // Card prefetches store logo-less infos; grab the poster logo
            // now (once per movie, then disk-cached) and re-apply it.
            if (logoPath == null && TmdbClient.enabled(this)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val withL = TmdbClient.withLogo(applicationContext, cached)
                    if (withL.logoPath == null) return@launch
                    InfoCache.put(video.title, withL)
                    val logo = withL.logoPath?.let { p ->
                        runCatching { ImageCache.load(IMAGE_W500 + p) }.getOrNull()
                    }
                    withContext(Dispatchers.Main) {
                        if (heroVideo != video || heroBound != video) return@withContext
                        heroViews?.let { v -> applyHeroIdentity(v, withL.title, logo) }
                    }
                }
            }
            return
        }

        // Cleaned filename as the instant fallback; TMDB's canonical title
        // (or poster logo) replaces it once its (disk-cached) lookup lands.
        views.title.text = TmdbClient.parseTitle(video.title).ifBlank { video.title }
        views.title.visibility = View.VISIBLE
        views.logo.visibility = View.GONE
        applyHeroOverview(views, "")
        val thumb = ThumbCache.cached(ThumbCache.key(video))
        if (thumb != null) views.img.setImageBitmap(thumb)
        if (thumb == null) {
            loadThumbInto(video) { bmp ->
                if (heroVideo == video) heroViews?.img?.setImageBitmap(bmp)
            }
        }
        // TMDB: canonical title / poster logo + big backdrop. The name
        // applies before the image downloads so the hero is never blocked
        // by the network.
        if (TmdbClient.enabled(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                var info = runCatching { TmdbClient.lookupQuick(applicationContext, video.title) }
                    .getOrNull() ?: return@launch
                InfoCache.put(video.title, info)
                // Prefer the poster logo; one small cached fetch per movie.
                if (info.logoPath == null) {
                    info = TmdbClient.withLogo(applicationContext, info)
                    InfoCache.put(video.title, info)
                }
                val backdrop = info.backdropPath?.let { p ->
                    runCatching { ImageCache.load(IMAGE_W780 + p) }.getOrNull()
                }
                val logo = info.logoPath?.let { p ->
                    runCatching { ImageCache.load(IMAGE_W500 + p) }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    if (heroVideo != video || heroBound != video) return@withContext
                    heroViews?.let { v ->
                        applyHeroIdentity(v, info.title, logo)
                        applyHeroOverview(v, info.overview)
                        if (backdrop != null) v.img.setImageBitmap(backdrop)
                    }
                }
            }
        }
    }

    /**
     * Summary shown straight after the season/episode badge; hidden until a
     * TMDB overview exists (never fall back to the raw file name here).
     */
    private fun applyHeroOverview(views: HeroViews, overview: String) {
        if (overview.isBlank()) {
            views.overview.visibility = View.GONE
        } else {
            views.overview.text = overview.trim()
            views.overview.visibility = View.VISIBLE
        }
    }

    /**
     * Poster name first: shows the official title artwork when present and
     * hides the text; otherwise falls back to the text name in the poster
     * font.
     */
    private fun applyHeroIdentity(views: HeroViews, name: String, logo: android.graphics.Bitmap?) {
        if (logo != null) {
            views.logo.setImageBitmap(logo)
            views.logo.visibility = View.VISIBLE
            views.title.visibility = View.GONE
        } else {
            views.title.text = name
            views.title.visibility = View.VISIBLE
            views.logo.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------ loading

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!TdLib.isStarted) TdLib.start(applicationContext)
            val ready = withTimeoutOrNull(30_000L) {
                while (TdLib.authState.value !is TdApi.AuthorizationStateReady) delay(250)
                true
            }
            if (ready != true) {
                runOnUiThread {
                    startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                    finish()
                }
                return@launch
            }
            reload()
        }
    }

    private suspend fun reload() {
        if (!loading.compareAndSet(false, true)) return
        runOnUiThread {
            status.visibility = View.VISIBLE
            status.text = "Loading your videos…"
        }
        try {
            // Bots have no Saved Messages and cannot read history; user
            // sessions scan Saved Messages. Branch on the live session type.
            val isUser = TdLib.ensureMe()
            botSession = !isUser
            if (isUser) reloadUser() else reloadBot()
        } catch (e: TdError) {
            runOnUiThread { status.visibility = View.VISIBLE; status.text = "Couldn't reach Telegram. Check your connection and tap refresh." }
        } catch (e: Exception) {
            runOnUiThread { status.visibility = View.VISIBLE; status.text = "Something went wrong loading your library. Tap refresh to try again." }
        } finally {
            loading.set(false)
        }
    }

    /**
     * User session: newest videos from every picked chat (Saved Messages by
     * default). Each chat contributes its recent history plus targeted
     * searches for older uploads.
     */
    private suspend fun reloadUser() {
        TdLib.ensureMe()
        val me = TdLib.send(TdApi.GetMe()) as TdApi.User
        val chatIds = if (ChatSelection.isConfigured(this)) {
            ChatSelection.selected(this)
        } else {
            setOf(me.id)
        }
        if (chatIds.isEmpty()) {
            runOnUiThread { applyVideos(emptyList()) }
            return
        }

        // Resolve chats: prefer the session's live cache, fall back to GetChat.
        val chats = TdLib.chatCache.values.filter { it.id in chatIds }.ifEmpty {
            chatIds.mapNotNull { id ->
                runCatching { TdLib.send(TdApi.GetChat(id)) as? TdApi.Chat }.getOrNull()
            }
        }

        val pairs = mutableListOf<Pair<TdApi.Message, VideoItem>>()
        for (chat in chats) {
            runCatching { TdLib.send(TdApi.OpenChat(chat.id)) }
            val before = pairs.size
            runCatching {
                TdLib.send(TdApi.GetChatHistory(chat.id, 0, 0, 200, false))
            }.onSuccess { hist ->
                hist.messages?.forEach { msg ->
                    VideoMapper.mapMessage(msg, chat.title)?.let { v -> pairs.add(msg to v) }
                }
            }
            // Older videos beyond the history window: targeted searches.
            if (pairs.size - before < 100) {
                for (filter in VideoMapper.filters) {
                    runCatching {
                        TdLib.send(
                            TdApi.SearchChatMessages(chat.id, null, "", null, 0L, 0, 100, filter),
                        )
                    }.onSuccess { found ->
                        found.messages?.forEach { msg ->
                            VideoMapper.mapMessage(msg, chat.title)?.let { v ->
                                pairs.add(msg to v)
                            }
                        }
                    }
                }
            }
        }
        // One card per actual video file (history + search results overlap heavily).
        val videos = pairs
            .distinctBy { it.second.fileId }
            .sortedByDescending { it.first.date }
            .map { it.second }
            .take(120)
        runOnUiThread { applyVideos(videos) }
    }

    /**
     * Bot session: TDLib bots cannot call GetChatHistory / SearchChatMessages,
     * so the library is the persisted bot library - videos received live are
     * added to it by TdLib, and past videos are re-resolved with GetMessage
     * after every restart, so the collection survives process deaths.
     * Only videos from the chats picked in the chat selector are shown
     * (everything stays stored, so re-checking a chat brings its videos back).
     */
    private fun reloadBot() {
        val allowed = if (ChatSelection.isConfigured(this)) ChatSelection.selected(this) else null
        val videos = TdLib.botVideos()
            .filter { allowed == null || it.chatId in allowed }
            .distinctBy { it.fileId }
            .take(120)
        runOnUiThread { applyVideos(videos) }
    }

    private fun applyVideos(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        if (tab == Tab.MYLIST) {
            // Keep whatever My List is showing; the reload only affects Home.
            renderStatusFor(displayed)
        } else {
            displayed = sortedForDisplay(if (query.isNotBlank()) filtered(items) else items)
            // Notify the CHILD adapter: ConcatAdapter's own
            // notifyDataSetChanged does not refresh nested item counts.
            videoAdapter.rebuild()
            bindHero(if (query.isNotBlank()) null else items.firstOrNull())
            refreshContinue()
            prefetchVisibleThumbs()
            prefetchHeroBackdrops()
            renderStatusFor(displayed)
        }
    }

    private fun openDetails(item: VideoItem, rowItems: List<VideoItem>) {
        val intent = DetailsActivity.intent(this, item, rowItems)
        startActivity(intent)
    }

    // ------------------------------------------------------ continue watching

    /** Stored (position, duration) when the video is resumable, else null. */
    private fun progressOf(v: VideoItem): Pair<Long, Long>? {
        val p = WatchProgress.get(this, v.chatId, v.messageId, v.fileName) ?: return null
        return if (WatchProgress.resumable(p.first, p.second)) p else null
    }

    /** Rebuilds the "Continue Watching" row from the library (Home tab, no search). */
    private fun refreshContinue() {
        if (tab != Tab.HOME || query.isNotBlank()) {
            continueAdapter.setData(emptyList())
            return
        }
        continueAdapter.setData(
            items.mapNotNull { v -> progressOf(v)?.let { Triple(v, it.first, it.second) } }
                .take(10),
        )
    }

    /** Full-width block holding the horizontal "Continue Watching" list. */
    inner class ContinueAdapter : RecyclerView.Adapter<ContinueHolder>() {

        private var data: List<Triple<VideoItem, Long, Long>> = emptyList()
        private val inner = ContinueCards()

        fun setData(list: List<Triple<VideoItem, Long, Long>>) {
            val hadRow = data.isNotEmpty()
            data = list
            inner.submit(list)
            val hasRow = list.isNotEmpty()
            when {
                !hadRow && hasRow -> notifyItemInserted(0)
                hadRow && !hasRow -> notifyItemRemoved(0)
            }
        }

        override fun getItemViewType(position: Int): Int = TYPE_CONTINUE

        override fun getItemCount(): Int = if (data.isEmpty()) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContinueHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_continue_row, parent, false)
            v.findViewById<RecyclerView>(R.id.continueList).apply {
                layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)
                adapter = inner
            }
            return ContinueHolder(v)
        }

        override fun onBindViewHolder(holder: ContinueHolder, position: Int) = Unit
    }

    class ContinueHolder(v: View) : RecyclerView.ViewHolder(v)

    class ContinueCell(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.cThumb)
        val title: TextView = v.findViewById(R.id.cTitle)
        val meta: TextView = v.findViewById(R.id.cMeta)
        val progress: ProgressBar = v.findViewById(R.id.cProgress)
    }

    inner class ContinueCards : RecyclerView.Adapter<ContinueCell>() {

        private var data: List<Triple<VideoItem, Long, Long>> = emptyList()

        fun submit(list: List<Triple<VideoItem, Long, Long>>) {
            data = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContinueCell =
            ContinueCell(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_continue_card, parent, false),
            )

        override fun onBindViewHolder(holder: ContinueCell, position: Int) {
            val (video, pos, dur) = data.getOrNull(position) ?: return
            holder.itemView.tag = video
            val info = InfoCache.get(video.title)
            holder.title.text = info?.title ?: TmdbClient.parseTitle(video.title).ifBlank { video.title }
            // "Minutes left" needs a trustworthy total: a TDLib stream only
            // knows the downloaded prefix, so a tiny `dur` that barely exceeds
            // `pos` is not the real runtime. Fall back to the TMDB runtime.
            // "Almost done" is decided by the watched fraction (pos/total),
            // never by raw minutes, so a 20%-watched title never flips to
            // "Almost done" just because little time remains in a bogus total.
            val durKnown = dur > pos + 60_000L
            val totalMin = if (durKnown) dur / 60_000L else (info?.runtimeMinutes?.toLong() ?: 0L)
            val watchedMin = pos / 60_000L
            val fraction = if (totalMin > 0) watchedMin.toDouble() / totalMin else 0.0
            holder.meta.text = when {
                totalMin <= 0L -> "In progress"
                fraction >= 0.9 -> "Almost done"
                else -> "${(totalMin - watchedMin).coerceAtLeast(1)} min left"
            }
            holder.progress.progress = when {
                durKnown -> ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                totalMin > 0 -> (fraction * 1000).toInt().coerceIn(0, 1000)
                else -> 0
            }
            holder.img.setImageBitmap(null)
            // Wide image first (TMDB backdrop), else the Telegram thumbnail.
            val backdrop = info?.backdropPath?.let { ImageCache.get(IMAGE_W780 + it) }
            val thumb = backdrop ?: ThumbCache.cached(ThumbCache.key(video))
            if (thumb != null) {
                holder.img.setImageBitmap(thumb)
            } else {
                loadThumbInto(video) { bmp ->
                    if (holder.itemView.tag == video) holder.img.setImageBitmap(bmp)
                }
            }
            // Tapping resumes straight away - no detour through Details.
            holder.itemView.setOnClickListener {
                startActivity(PlayerActivity.intent(this@HomeActivity, video, pos))
            }
            holder.itemView.setOnLongClickListener {
                openDetails(video, items.toList())
                true
            }
        }
    }

    // --------------------------------------------------------------- grid

    /** Full-width hero item, first in the grid, scrolling with the list. */
    inner class HeroAdapter : RecyclerView.Adapter<HeroHolder>() {

        var visible = false

        override fun getItemViewType(position: Int): Int = TYPE_HERO

        override fun getItemCount(): Int = if (visible) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroHolder =
            HeroHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false),
            )

        override fun onBindViewHolder(holder: HeroHolder, position: Int) {
            heroViews = holder.views
            heroBound?.let { doBindHero(it) }
        }

        override fun onViewRecycled(holder: HeroHolder) {
            if (heroViews?.root === holder.itemView) heroViews = null
        }
    }

    /** Full-width section label ("My Library" / "My List") inside the grid. */
    inner class LabelAdapter : RecyclerView.Adapter<LabelHolder>() {

        var text: String = ""

        fun refresh() = notifyDataSetChanged()

        override fun getItemViewType(position: Int): Int = TYPE_LABEL

        override fun getItemCount(): Int = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LabelHolder =
            LabelHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_section_label, parent, false),
            )

        override fun onBindViewHolder(holder: LabelHolder, position: Int) {
            holder.tv.text = text
        }
    }

    class LabelHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v as TextView
    }

    class HeroHolder(v: View) : RecyclerView.ViewHolder(v) {
        val views = HeroViews(
            v,
            v.findViewById(R.id.heroImage),
            v.findViewById(R.id.heroTitle),
            v.findViewById(R.id.heroLogo),
            v.findViewById(R.id.heroOverview),
        )

        init {
            v.findViewById<View>(R.id.btnPlayHero).setOnClickListener { view ->
                (view.context as? HomeActivity)?.let { act ->
                    act.heroVideo?.let { act.openDetails(it, act.displayed.toList()) }
                }
            }
            // Touching the hero pauses the auto-rotation for a while, so the
            // featured film doesn't change under the user's finger.
            v.setOnTouchListener { view, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    (view.context as? HomeActivity)?.heroPausedUntil =
                        System.currentTimeMillis() + 20_000
                }
                false
            }
        }
    }

    /**
     * One grid row: a full-width chat-group header, a full-width swipeable
     * card row (group mode), or a video card (flat grid).
     */
    private class AdapterRow(
        val header: String? = null,
        val video: VideoItem? = null,
        val groupVideos: List<VideoItem>? = null,
    )

    class Cell(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val badge: TextView = v.findViewById(R.id.ratingBadge)
        val btnList: ImageButton = v.findViewById(R.id.btnList)
        val progress: ProgressBar = v.findViewById(R.id.cardProgress)
    }

    inner class VideoAdapter(
        private val onClick: (VideoItem, List<VideoItem>) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<AdapterRow> = emptyList()

        /**
         * Rebuilds the rows from [displayed]. With the group toggle on,
         * [displayed] is chat-consecutive and each chat becomes a small
         * header + one full-width row of swipeable cards; otherwise the
         * list is the flat 3-column grid.
         */
        fun rebuild() {
            val out = mutableListOf<AdapterRow>()
            if (Credentials.homeGroup(this@HomeActivity)) {
                val groups = LinkedHashMap<Any, MutableList<VideoItem>>()
                for (v in displayed) groups.getOrPut(v.chatId) { mutableListOf() }.add(v)
                for (vids in groups.values) {
                    out.add(AdapterRow(header = vids.first().chatTitle.ifBlank { "Saved Messages" }))
                    out.add(AdapterRow(groupVideos = vids.toList()))
                }
            } else {
                for (v in displayed) out.add(AdapterRow(video = v))
            }
            rows = out
            notifyDataSetChanged()
        }

        /** True when the video-region row at [offset] takes the full width. */
        fun isFullSpan(offset: Int): Boolean =
            offset >= 0 && rows.getOrNull(offset)?.video == null

        override fun getItemViewType(position: Int): Int {
            val row = rows.getOrNull(position) ?: return TYPE_VIDEO
            return when {
                row.header != null -> TYPE_GROUP_HEADER
                row.groupVideos != null -> TYPE_GROUP_ROW
                else -> TYPE_VIDEO
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_GROUP_HEADER -> LabelHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_group_header, parent, false),
                )
                TYPE_GROUP_ROW -> GroupRowHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_chat_row, parent, false),
                )
                else -> Cell(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_video_card, parent, false),
                )
            }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is LabelHolder -> holder.tv.text = rows[position].header.orEmpty()
                is GroupRowHolder -> holder.adapter.setItems(
                    rows[position].groupVideos.orEmpty(),
                )
                is Cell -> bindVideo(holder, rows.getOrNull(position)?.video ?: return)
            }
        }

    }

    /**
     * Full-width horizontal strip of cards for one chat in group mode: the
     * user swipes left/right through that chat's videos instead of the
     * 3-column grid.
     */
    inner class HorizontalCardAdapter : RecyclerView.Adapter<Cell>() {

        private var videos: List<VideoItem> = emptyList()

        fun setItems(list: List<VideoItem>) {
            videos = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_video_card, parent, false)
            // Fixed width ≈ one grid column, so about three cards fit on
            // screen and the rest is reached by swiping.
            v.layoutParams.width = (parent.resources.displayMetrics.widthPixels * 0.30f).toInt()
            return Cell(v)
        }

        override fun getItemCount(): Int = videos.size

        override fun onBindViewHolder(holder: Cell, position: Int) {
            bindVideo(holder, videos[position])
        }
    }

    /** Holder of a swipeable per-chat card row (group mode). */
    inner class GroupRowHolder(v: View) : RecyclerView.ViewHolder(v) {
        val adapter = HorizontalCardAdapter()

        init {
            val rv = v.findViewById<RecyclerView>(R.id.chatRow)
            rv.layoutManager = LinearLayoutManager(
                v.context,
                LinearLayoutManager.HORIZONTAL,
                false,
            )
            rv.adapter = adapter
        }
    }

    /**
     * Binds a video card, in the flat grid or inside a swipeable chat row.
     * Shared by [VideoAdapter] and [HorizontalCardAdapter].
     */
    private fun bindVideo(holder: Cell, video: VideoItem) {
        val context = holder.itemView.context
        holder.itemView.tag = video
        // Fallback display name: clean the caption/filename of release
        // junk ("Movie.Name.2026.720p..." -> "Movie Name"); TMDB replaces
        // it with the canonical title when its lookup lands.
        holder.title.text = TmdbClient.parseTitle(video.title).ifBlank { video.title }
        holder.badge.visibility = View.GONE
        holder.meta.text = listOfNotNull(
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
        ).joinToString(" • ").ifBlank { video.chatTitle }
        holder.img.setImageBitmap(null)

        // Watch progress strip.
        val prog = progressOf(video)
        if (prog != null && prog.second > 0) {
            holder.progress.progress = ((prog.first * 1000L) / prog.second).toInt().coerceIn(0, 1000)
            holder.progress.visibility = View.VISIBLE
        } else {
            holder.progress.visibility = View.GONE
        }

        // Heart button: bookmark straight from the grid.
        renderListState(holder, Watchlist.contains(context, video))
        holder.btnList.setOnClickListener {
            val added = Watchlist.toggle(context, video)
            renderListState(holder, added)
            if (tab == Tab.MYLIST && !added) showMyList()
        }

        // Telegram thumbnail: memory hit paints instantly; otherwise one
        // cached fetch (disk or download) keyed by the stable remote id.
        if (video.thumbnailFileId != 0) {
            ThumbCache.cached(ThumbCache.key(video))?.let { holder.img.setImageBitmap(it) }
                ?: holder.itemView.post {
                    if (holder.itemView.tag == video) loadThumb(holder, video)
                }
        }

        // TMDB: real title/rating/year + poster. A resolved Info from a
        // previous bind applies synchronously with zero network; only the
        // first time a card comes into view launches a lookup.
        if (TmdbClient.enabled(context)) {
            val cached = InfoCache.get(video.title)
            if (cached != null) {
                applyTmdb(holder, video, cached)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    val info = runCatching { TmdbClient.lookupQuick(context, video.title) }
                        .getOrNull() ?: return@launch
                    InfoCache.put(video.title, info)
                    withContext(Dispatchers.Main) {
                        if (holder.itemView.tag == video) applyTmdb(holder, video, info)
                    }
                }
            }
        }

        holder.itemView.setOnClickListener {
            val i = holder.bindingAdapterPosition
            if (i >= 0) {
                openDetails(video, displayed.toList())
            }
        }
        // Long-press only removes from My List; downloads are started from
        // the details screen, not by hidden presses in the grid.
        holder.itemView.setOnLongClickListener {
            if (tab == Tab.MYLIST) {
                Watchlist.toggle(this@HomeActivity, video)
                showMyList()
                Toast.makeText(this@HomeActivity, "Removed from My List", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun renderListState(holder: Cell, added: Boolean) {
        holder.btnList.setBackgroundResource(
            if (added) R.drawable.bg_fab_red else R.drawable.bg_fab_dark,
        )
        holder.btnList.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this@HomeActivity,
                if (added) android.R.color.white else R.color.text_secondary,
            ),
        )
    }

    /**
     * Applies resolved TMDB data to a card. The poster comes from the
     * image cache when present; otherwise an image-only fetch is kicked
     * off (the text/meta is never delayed by the network).
     */
    private fun applyTmdb(holder: Cell, video: VideoItem, info: TmdbClient.Info) {
        holder.title.text = info.title
        if (info.rating != null && info.rating > 0.0) {
            holder.badge.text = "★ %.1f".format(info.rating)
            holder.badge.visibility = View.VISIBLE
        }
        holder.meta.text = listOfNotNull(
            info.year?.toString(),
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
        ).joinToString(" • ").ifBlank { video.chatTitle }

        val path = info.posterPath ?: return
        val poster = ImageCache.get(IMAGE_W300 + path)
        if (poster != null) {
            holder.img.setImageBitmap(poster)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching { ImageCache.load(IMAGE_W300 + path) }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) {
                if (holder.itemView.tag == video) holder.img.setImageBitmap(bmp)
            }
        }
    }

    private fun loadThumb(holder: Cell, video: VideoItem) {
        loadThumbInto(video) { bmp ->
            if (holder.itemView.tag == video) holder.img.setImageBitmap(bmp)
        }
    }

    /**
     * Shared thumbnail fetch: memory → disk cache → TDLib download. Whatever
     * it lands in is cached under the video's stable key, so this runs at
     * most once per video per install.
     */
    private fun loadThumbInto(video: VideoItem, onLoaded: (android.graphics.Bitmap) -> Unit) {
        if (video.thumbnailFileId == 0) return
        val key = ThumbCache.key(video)
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                ThumbCache.bitmap(key) ?: run {
                    val f = TdLib.downloadFully(video.thumbnailFileId, priority = 1)
                    decodeDownscaled(f.local?.path ?: return@launch)
                        ?.also { ThumbCache.put(key, it) }
                }
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { onLoaded(bmp) }
        }
    }

    /**
     * Warms the cache for everything currently in view so posters appear
     * once, up front, instead of one re-download per scroll.
     */
    private fun prefetchVisibleThumbs() {
        displayed.take(9).forEach { v ->
            if (v.thumbnailFileId != 0 && ThumbCache.cached(ThumbCache.key(v)) == null) {
                loadThumbInto(v) { }
            }
        }
    }

    /**
     * Resolves TMDB info + the big w780 backdrop for EVERY library entry up
     * front, so the auto-rotating hero always has a real image ready the
     * moment it spins to the next film (no empty frames).
     */
    private fun prefetchHeroBackdrops() {
        if (!TmdbClient.enabled(this)) return
        // Snapshot on the main thread: `displayed` aliases `items`, which
        // applyVideos mutates - iterating it on IO threw CME and crashed.
        val snapshot = displayed.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            snapshot.forEach { v ->
                val info = InfoCache.get(v.title) ?: runCatching {
                    TmdbClient.lookupQuick(applicationContext, v.title)
                }.getOrNull()?.also { InfoCache.put(v.title, it) } ?: return@forEach
                val path = info.backdropPath ?: return@forEach
                if (ImageCache.get(IMAGE_W780 + path) == null) {
                    runCatching { ImageCache.load(IMAGE_W780 + path) }
                }
            }
        }
    }

    private fun decodeDownscaled(path: String): android.graphics.Bitmap? {
        val f = java.io.File(path)
        if (!f.exists() || f.length() == 0L) return null
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 450) sample *= 2
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    // -------------------------------------------------------- caches

    /** Poster/backdrop/logo image bases used across hero and grid. */
    private val IMAGE_W780 = "https://image.tmdb.org/t/p/w780"
    private val IMAGE_W300 = "https://image.tmdb.org/t/p/w300"
    private val IMAGE_W500 = "https://image.tmdb.org/t/p/w500"

    /** Resolved TMDB info per video title, so rebinds (resume, tab switches,
     * new-message reloads) apply synchronously instead of re-querying. */
    object InfoCache {
        private val map = java.util.concurrent.ConcurrentHashMap<String, TmdbClient.Info>()

        fun get(title: String): TmdbClient.Info? = map[title.lowercase()]

        fun put(title: String, info: TmdbClient.Info) {
            if (map.size > 300) map.clear()
            map[title.lowercase()] = info
        }

        fun clear() = map.clear()
    }

    /**
     * Telegram thumbnail cache, keyed by the video's STABLE remoteFileId -
     * TDLib thumbnail file ids change every session, so keying by them made
     * every launch re-download every poster. Memory holds live bitmaps; a
     * small files-dir cache survives process restarts, so a thumbnail is
     * downloaded exactly once, ever.
     */
    object ThumbCache {
        private val map = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
        private var dir: java.io.File? = null

        /** Stable cache key for a video. */
        fun key(v: com.tgtv.mobile.model.VideoItem): String =
            v.remoteFileId.ifBlank { v.title.ifBlank { v.fileId.toString() } }

        /** Context-free init; call once per process from onCreate. */
        fun init(d: java.io.File) {
            dir = d.apply { mkdirs() }
        }

        /** Memory-only check - safe on the main thread. */
        fun cached(key: String): android.graphics.Bitmap? = map[key]

        /** Memory hit, else a (blocking) disk decode. IO thread only. */
        fun bitmap(key: String): android.graphics.Bitmap? {
            map[key]?.let { return it }
            val f = java.io.File(dir ?: return null, "$key.jpg")
            if (!f.exists() || f.length() == 0L) return null
            return android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.also { map[key] = it }
        }

        fun put(key: String, bmp: android.graphics.Bitmap) {
            map[key] = bmp
            val f = java.io.File(dir ?: return, "$key.jpg")
            try {
                f.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out) }
            } catch (_: Exception) {
            }
        }

        fun clear() {
            map.clear()
            dir?.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * TMDB poster/backdrop cache: memory + disk. Keyed by URL; a w780
     * backdrop downloads exactly once per install and every later hero
     * rotation / card bind reads it instantly from disk.
     */
    object ImageCache {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
        private var dir: java.io.File? = null

        /** Context-free init; call once per process from onCreate. */
        fun init(d: java.io.File) {
            dir = d.apply { mkdirs() }
        }

        private fun fileFor(url: String): java.io.File? {
            val d = dir ?: return null
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            // "v2" busts the old JPEG-only cache that flattened transparent
            // title logos onto black squares.
            return java.io.File(d, "v2_$digest.img")
        }

        /** Non-blocking lookup; main-thread safe. */
        fun get(url: String): android.graphics.Bitmap? = cache[url]

        /** Memory → disk → network. IO thread only. */
        fun load(url: String): android.graphics.Bitmap? {
            cache[url]?.let { return it }
            val f = fileFor(url)
            if (f != null && f.exists() && f.length() > 0L) {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                    ?.let { cache[url] = it; return it }
            }
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Connection", "close")
                if (conn.responseCode != 200) return null
                val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream) ?: return null
                if (f != null) {
                    try {
                        f.outputStream().use { out ->
                            // Title wordmarks are transparent PNGs: keep their
                            // alpha (PNG) so they blend over the backdrop;
                            // photos stay JPEG. decodeFile sniffs the bytes,
                            // so the extension never matters.
                            val fmt =
                                if (bmp.hasAlpha()) android.graphics.Bitmap.CompressFormat.PNG
                                else android.graphics.Bitmap.CompressFormat.JPEG
                            bmp.compress(fmt, 88, out)
                        }
                    } catch (_: Exception) {
                    }
                }
                cache[url] = bmp
                return bmp
            } finally {
                conn.disconnect()
            }
        }

        fun clear() {
            cache.clear()
            dir?.listFiles()?.forEach { it.delete() }
        }
    }
}
