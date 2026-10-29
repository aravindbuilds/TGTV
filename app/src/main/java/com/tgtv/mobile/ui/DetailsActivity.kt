package com.tgtv.mobile.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tgtv.mobile.R
import com.tgtv.mobile.model.Release
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.model.Watchlist
import com.tgtv.mobile.model.WatchProgress
import com.tgtv.mobile.svc.DownloadService
import com.tgtv.mobile.td.TdLib
import com.tgtv.mobile.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Mobile details, styled like the reference: full-bleed TMDB backdrop with a
 * centered play button, star rating + meta chips, expandable story line and
 * a horizontal cast row (actor + character). The Story Line header has a
 * My List toggle.
 */
class DetailsActivity : AppCompatActivity() {

    private lateinit var video: VideoItem
    private var items: List<VideoItem> = emptyList()

    private val backdrop: ImageView by lazy { findViewById(R.id.backdrop) }
    private val txtTitle: TextView by lazy { findViewById(R.id.txtTitle) }
    private val txtRating: TextView by lazy { findViewById(R.id.txtRating) }
    private val chipYear: TextView by lazy { findViewById(R.id.chipYear) }
    private val chipRuntime: TextView by lazy { findViewById(R.id.chipRuntime) }
    private val chipGenres: TextView by lazy { findViewById(R.id.chipGenres) }
    private val txtOverview: TextView by lazy { findViewById(R.id.txtOverview) }
    private val txtReadMore: TextView by lazy { findViewById(R.id.txtReadMore) }
    private val castRow: RecyclerView by lazy { findViewById(R.id.castRow) }
    private val txtFile: TextView by lazy { findViewById(R.id.txtFile) }

    private val txtEpisode: TextView by lazy { findViewById(R.id.txtEpisode) }

    private val txtBigFile: TextView by lazy { findViewById(R.id.txtBigFile) }
    private val versionsList: LinearLayout by lazy { findViewById(R.id.versionsList) }

    private var backdropUrl: String? = null
    private var expanded = false

    // Watch progress: shown as "Resume" on the play button; 0 = play fresh.
    private var resumeMs = 0L

    /** Re-queries shortly after returning from the player: its onStop save
     *  lands AFTER our onResume (lifecycle order), so poll once more. */
    private val resumeQuery = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        BitmapCache.init(java.io.File(filesDir, "details-img"))
        video = intent.getSerializableExtra(EXTRA_VIDEO) as VideoItem
        @Suppress("UNCHECKED_CAST")
        items = (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<VideoItem>) ?: listOf(video)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPlayHero).setOnClickListener { play(video, resumeMs) }
        findViewById<View>(R.id.btnPlayAgain).setOnClickListener { play(video, 0L) }
        findViewById<ImageButton>(R.id.btnDownload).setOnClickListener {
            DownloadService.start(this, video)
            android.widget.Toast.makeText(this, "Downloading \"${video.title}\"...", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btnAddList).setOnClickListener { addToList() }
        renderWatchlistState(Watchlist.contains(this, video))

        txtTitle.text = video.title
        txtFile.text = listOfNotNull(
            TmdbClient.parseEpisode(video.fileName)?.takeIf { it.isNotBlank() },
            video.prettySize().ifBlank { null },
            video.prettyDuration().ifBlank { null },
            Release.describe(video.fileName).takeIf { it.isNotBlank() },
            "from ${video.chatTitle}",
        ).joinToString("   ·   ")

        // Season/episode chip ("S05 E03") under the title chips; series only.
        val episode = TmdbClient.parseEpisode(video.fileName)?.takeIf { it.isNotBlank() }
        if (episode == null) {
            txtEpisode.visibility = View.GONE
        } else {
            txtEpisode.text = episode
            txtEpisode.visibility = View.VISIBLE
        }

        // Big-file note: Telegram only streams file parts past 2 GB for
        // Premium accounts, so warn before the user hits the wall.
        if (video.fileSize > HomeActivity.BIG_FILE_BYTES) {
            txtBigFile.text =
                "Streaming works best with files under 2 GB - larger files need a Telegram Premium account."
            txtBigFile.visibility = View.VISIBLE
        } else {
            txtBigFile.visibility = View.GONE
        }
        txtOverview.post { syncReadMore() }
        txtReadMore.setOnClickListener {
            expanded = !expanded
            txtOverview.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
            txtReadMore.text = if (expanded) "Read Less" else "Read More"
        }
        renderVersions()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!TmdbClient.enabled(applicationContext)) return@launch
                val info = TmdbClient.lookup(applicationContext, video.title)
                    ?.takeIf { it.tmdbId > 0 }
                    ?.let { base -> TmdbClient.enrich(applicationContext, base) }
                    ?: return@launch
                renderTmdb(info)
            } catch (_: Throwable) {
            }
            renderTgBackdrop(video.thumbnailFileId)
        }
    }

    private fun play(v: VideoItem, startMs: Long = 0L) {
        PlayerActivity.intent(this, v, startMs).let { startActivity(it) }
    }

    /** Fresh play always plays from 0; the resume position rides on the intent. */
    override fun onResume() {
        super.onResume()
        renderResumeState()
        resumeQuery.postDelayed({ renderResumeState() }, 800)
    }

    override fun onPause() {
        resumeQuery.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /**
     * Mobile-flavoured resume state: the red play button keeps its single
     * centered look - with stored progress it resumes (labelled under the
     * button) and a small "Play Again" pill appears beneath it.
     */
    private fun renderResumeState() {
        val (pos, dur) = WatchProgress.get(this, video.chatId, video.messageId, video.fileName)
            ?: (0L to 0L)
        resumeMs = pos
        val canResume = WatchProgress.resumable(pos, dur)
        val txtResume = findViewById<TextView>(R.id.txtResume)
        val btnPlayAgain = findViewById<View>(R.id.btnPlayAgain)
        if (canResume) {
            txtResume.text = "Resume from ${WatchProgress.prettyMs(pos)}"
            txtResume.visibility = View.VISIBLE
            btnPlayAgain.visibility = View.VISIBLE
        } else {
            txtResume.visibility = View.GONE
            btnPlayAgain.visibility = View.GONE
        }
    }

    /** Toggles My List from the Story Line header (the only My List control here). */
    private fun addToList() {
        val added = Watchlist.toggle(this, video)
        renderWatchlistState(added)
        Toast.makeText(
            this,
            if (added) "Added to My List" else "Removed from My List",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun renderTmdb(info: TmdbClient.Info) = runOnUiThread {
        try {
            txtTitle.text = info.title
            if (info.rating != null && info.rating > 0.0) {
                txtRating.text = "★ %.1f".format(info.rating)
                txtRating.visibility = View.VISIBLE
            }
            info.year?.let {
                chipYear.text = it.toString()
                chipYear.visibility = View.VISIBLE
            }
            (info.runtimeMinutes?.let { m -> "${m / 60}h ${m % 60}m" }
                ?: video.prettyRuntime().takeIf { it.isNotBlank() })?.let {
                chipRuntime.text = it
                chipRuntime.visibility = View.VISIBLE
            }
            if (info.genres.isNotEmpty()) {
                chipGenres.text = info.genres.joinToString(", ")
                chipGenres.visibility = View.VISIBLE
            }
            if (info.overview.isNotBlank()) {
                txtOverview.text = info.overview
                expanded = false
                txtOverview.maxLines = COLLAPSED_LINES
                txtOverview.post { syncReadMore() }
            }
            info.backdropPath?.let { path ->
                backdropUrl = IMAGE_BASE_W1280 + path
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = BitmapCache.load(backdropUrl ?: return@launch) ?: return@launch
                    withContext(Dispatchers.Main) { backdrop.setImageBitmap(bmp) }
                }
            }
            if (info.cast.isNotEmpty()) {
                castRow.visibility = View.VISIBLE
                findViewById<TextView>(R.id.txtCastLabel).visibility = View.VISIBLE
                castRow.layoutManager =
                    LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
                castRow.adapter = CastAdapter(info.cast)
            }
        } catch (_: Throwable) {
        }
    }

    /** Shows "Read More" only when the overview is long enough to be clipped. */
    private fun syncReadMore() {
        // A text clipped at exactly COLLAPSED_LINES has lineCount == COLLAPSED_LINES.
        val lines = txtOverview.layout?.lineCount ?: 0
        txtReadMore.visibility = if (lines >= COLLAPSED_LINES) View.VISIBLE else View.GONE
    }

    /** Tints the Story Line heart: red when listed, muted when not. */
    private fun renderWatchlistState(added: Boolean) {
        findViewById<ImageButton>(R.id.btnAddList).imageTintList =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (added) R.color.accent else R.color.text_secondary,
                ),
            )
    }

    private fun renderVersions() {
        val same = items.filter {
            TmdbClient.parseTitle(it.fileName).equals(TmdbClient.parseTitle(video.fileName), true)
        }
        if (same.size < 2) return
        runOnUiThread {
            findViewById<TextView>(R.id.txtVersionsLabel).visibility = View.VISIBLE
            for (v in same) {
                val row = TextView(this).apply {
                    text = listOfNotNull(
                        Release.quality(v.fileName),
                        v.prettySize().ifBlank { null },
                        v.prettyDuration().ifBlank { null },
                    ).joinToString("  ·  ")
                    textSize = 14f
                    setTextColor(0xFFDDDDDD.toInt())
                    setPadding(0, 12, 0, 12)
                    setBackgroundResource(R.drawable.bg_versions_item)
                    setOnClickListener {
                        if (v.fileId == video.fileId) return@setOnClickListener
                        play(v)
                    }
                }
                versionsList.addView(row)
            }
        }
    }

    /** Telegram thumbnail as hero fallback until the TMDB backdrop arrives. */
    private suspend fun renderTgBackdrop(thumbFileId: Int) {
        if (thumbFileId == 0 || backdropUrl != null) return
        runCatching {
            val f = TdLib.downloadFully(thumbFileId, priority = 1)
            val path = f.local?.path ?: return
            withContext(Dispatchers.Main) {
                backdrop.setImageBitmap(BitmapFactory.decodeFile(path))
            }
        }
    }

    // ------------------------------------------------------------ adapters

    class CastHolder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val role: TextView = v.findViewById(R.id.role)
    }

    inner class CastAdapter(private val cast: List<TmdbClient.CastMember>) :
        RecyclerView.Adapter<CastHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastHolder =
            CastHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_cast, parent, false))

        override fun getItemCount(): Int = cast.size

        override fun onBindViewHolder(holder: CastHolder, position: Int) {
            val member = cast[position]
            holder.itemView.tag = member
            holder.name.text = member.name
            holder.role.text = member.character
            holder.role.visibility = if (member.character.isBlank()) View.GONE else View.VISIBLE
            holder.img.setImageDrawable(null)
            member.profilePath?.let { path ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = BitmapCache.load("$IMAGE_BASE_W185$path") ?: return@launch
                    withContext(Dispatchers.Main) {
                        if (holder.itemView.tag == member) holder.img.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- images

    /** Small cross-open cache so re-entering details doesn't refetch portraits. */
    /**
     * Backdrop/cast-image cache with a disk layer: a poster downloads once
     * per install, so the details hero renders instantly on every visit -
     * no network, no loading gap.
     */
    object BitmapCache {
        private val map = ConcurrentHashMap<String, android.graphics.Bitmap>()
        private var dir: java.io.File? = null

        fun init(d: java.io.File) {
            dir = d.apply { mkdirs() }
        }

        private fun fileFor(url: String): java.io.File? {
            val d = dir ?: return null
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return java.io.File(d, "$digest.jpg")
        }

        fun load(url: String): android.graphics.Bitmap? {
            map[url]?.let { return it }
            val f = fileFor(url)
            if (f != null && f.exists() && f.length() > 0L) {
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    map[url] = it
                    return it
                }
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Connection", "close")
                if (conn.responseCode != 200) return null
                val bmp = BitmapFactory.decodeStream(conn.inputStream) ?: return null
                if (f != null) {
                    try {
                        f.outputStream().use { out ->
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                        }
                    } catch (_: Throwable) {
                    }
                }
                map[url] = bmp
                return bmp
            } catch (_: Throwable) {
                return null
            } finally {
                conn.disconnect()
            }
        }

        fun clear() {
            map.clear()
            dir?.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_ITEMS = "items"
        private const val COLLAPSED_LINES = 4
        private const val IMAGE_BASE_W1280 = "https://image.tmdb.org/t/p/w1280"
        private const val IMAGE_BASE_W185 = "https://image.tmdb.org/t/p/w185"

        fun intent(context: Activity, video: VideoItem, items: List<VideoItem>): Intent {
            val i = Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_VIDEO, video as Serializable)
            i.putExtra(EXTRA_ITEMS, ArrayList<Serializable>(items as? List<Serializable> ?: emptyList()))
            return i
        }
    }
}
