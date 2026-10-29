package com.tgtv.mobile.sub

import android.content.Context
import com.tgtv.mobile.model.VideoItem
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib
import com.tgtv.mobile.td.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles.com fallback: the player offers an MX-Player-style subtitle
 * search. Every search runs the full strategy list and returns ALL matching
 * candidates - never silently attaches the first hit:
 *
 *  1. `tmdb_id=<id>` pinned to the exact season/episode, then the whole show
 *  2. the full TMDB-resolved title as a free-text query
 *  3. the user's own typed query (MX-Player style search box)
 *  4. the full original file name, verbatim
 *
 * Results are merged and de-duplicated by file id. The auto path
 * ([fetchFor]) still exists for the once-per-play silent fetch - it simply
 * downloads the best first candidate.
 *
 * Bring-your-own API key from opensubtitles.com (free account -> API keys);
 * without a key web subtitles stay disabled.
 */
object SubtitlesClient {

    private const val WEB_UA = "TGTV/0.3 (github.com/aravindbuilds/TelegramVideoTV)"

    private const val MAX_OPTIONS = 40

    /**
     * HTTP client that dials IPv4 only. api.opensubtitles.com sits behind
     * Cloudflare and publishes its IPv6 address first; on networks where IPv6
     * is configured but not routed (most emulators), the OS-resolver ordering
     * hangs every connection until timeout while IPv4 works fine. Filtering
     * the resolved addresses to A records sidesteps the whole problem.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val all = InetAddress.getAllByName(hostname).toList()
                    return all.filterIsInstance<Inet4Address>().ifEmpty { all }
                }
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun hasKey(context: Context): Boolean = !Credentials.openSubtitlesKey(context).isNullOrBlank()

    data class Fetched(val file: File, val language: String, val label: String)

    /** One downloadable subtitle candidate shown in the picker. */
    data class Option(val fileId: Long, val language: String, val label: String, val flags: String)

    /**
     * Resolves [video] through TMDB, then merges every search strategy into
     * a de-duplicated candidate list. [userQuery] (optional) is what the user
     * typed into the search box; it is searched in addition to the automatic
     * strategies, never instead of them.
     */
    suspend fun searchOptions(
        context: Context,
        video: VideoItem,
        userQuery: String? = null,
    ): List<Option> = withContext(Dispatchers.IO) {
        runCatching { runSearch(context, video, userQuery) }
            .onFailure {
                TdLib.log("subs: search crashed ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrDefault(emptyList())
    }

    /** Downloads the picked candidate into the app cache. Silent on failure. */
    suspend fun download(context: Context, video: VideoItem, option: Option): Fetched? =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = Credentials.openSubtitlesKey(context) ?: return@runCatching null
                val srt = downloadFile(apiKey, option.fileId, context, video.fileId)
                    ?: return@runCatching null
                TdLib.log("subs: downloaded ${Languages.nameOf(option.language)} from ${option.label}")
                Fetched(srt, option.language, option.label)
            }.getOrNull()
        }

    /**
     * Auto path (once-per-play silent fetch): pick the best first candidate
     * and download it.
     */
    suspend fun fetchFor(context: Context, video: VideoItem): Fetched? {
        val option = searchOptions(context, video).firstOrNull() ?: return null
        return download(context, video, option)
    }

    private suspend fun runSearch(
        context: Context,
        video: VideoItem,
        userQuery: String?,
    ): List<Option> {
        val apiKey = Credentials.openSubtitlesKey(context) ?: return emptyList()
        val title = video.title.ifBlank { video.fileName }
        val info = when {
            TmdbClient.enabled(context) ->
                TmdbClient.lookup(context, title)?.let { TmdbClient.enrich(context, it) }
            else -> null
        }
        val languages = Credentials.subsLanguages(context)
        // Series files: pin the search to the exact season/episode so the
        // subs offered match what's playing (not episode 1 of the show).
        val episodeSuffix = TmdbClient.parseEpisodeInfo(video.fileName)
            ?.let { "&season_number=${it.season}&episode_number=${it.episode}" } ?: ""
        TdLib.log("subs: search start langs='$languages' epSuffix='$episodeSuffix' title='${info?.title ?: title}' tmdbId=${info?.tmdbId ?: 0}")

        val results = LinkedHashMap<Long, Option>()
        fun addAll(tag: String, entries: List<Entry>) {
            TdLib.log("subs: $tag -> ${entries.size} results (total ${results.size})")
            for (e in entries) {
                if (results.size >= MAX_OPTIONS) return
                if (!results.containsKey(e.fileId)) {
                    results[e.fileId] = Option(e.fileId, e.language, e.label, e.flags)
                }
            }
        }

        // One failing strategy must never kill the rest of the search.
        var lastRequestAt = 0L
        fun tryAdd(tag: String, suffix: String) {
            // Free API keys allow roughly one request per second - pace the
            // strategy calls so they don't get throttled.
            val since = System.currentTimeMillis() - lastRequestAt
            if (since < 1_100) Thread.sleep(1_100 - since)
            lastRequestAt = System.currentTimeMillis()
            val entries = try {
                searchEntries(apiKey, suffix, languages)
            } catch (e: Exception) {
                TdLib.log("subs: $tag ERROR ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            addAll(tag, entries)
        }

        // 1 + 2: everything TMDB knows about this video
        if (info != null && info.tmdbId != 0) {
            tryAdd("tmdb_id=${info.tmdbId}$episodeSuffix", "tmdb_id=" + info.tmdbId + episodeSuffix)
            // A pinned episode search can miss (episode not indexed); also
            // search the show without episode filtering.
            if (episodeSuffix.isNotEmpty()) {
                tryAdd("tmdb_id=${info.tmdbId}", "tmdb_id=" + info.tmdbId)
            }
            // The full TMDB-resolved title ("Avatar: The Last Airbender")
            val tmdbTitle = info.title.ifBlank { title }
            if (tmdbTitle.isNotBlank()) {
                tryAdd(
                    "query(tmdb '$tmdbTitle')",
                    "query=" + URLEncoder.encode(tmdbTitle, "UTF-8"),
                )
            }
        } else {
            TdLib.log("subs: tmdb unresolved (info=$info), skipping id searches")
        }

        // 3: whatever the user typed into the search box
        if (!userQuery.isNullOrBlank()) {
            tryAdd(
                "query(user '$userQuery')",
                "query=" + URLEncoder.encode(userQuery.trim(), "UTF-8"),
            )
        }

        // 4: the full original file name, verbatim
        val rawName = video.fileName.ifBlank { title }
        if (rawName.isNotBlank()) {
            tryAdd(
                "query(file '$rawName')",
                "query=" + URLEncoder.encode(rawName, "UTF-8"),
            )
        }
        // English first, then everything else in relevance order (stable sort)
        return results.values.toList().sortedBy { if (it.language.startsWith("en")) 0 else 1 }
    }

    private data class Entry(val fileId: Long, val language: String, val label: String, val flags: String)

    /**
     * GET /api/v1/subtitles with a raw suffix query ("tmdb_id=123", "query=...").
     * Returns ALL downloadable results, not just the first match. Retries on
     * 429 (free keys allow ~1 request/second and the search fires several).
     */
    private fun searchEntries(apiKey: String, suffix: String, languages: String): List<Entry> {
        var attempt = 0
        while (true) {
            attempt++
            val req = Request.Builder()
                .url(
                    "https://api.opensubtitles.com/api/v1/subtitles?" + suffix +
                        "&languages=" + languages + "&format=srt",
                )
                .header("Api-Key", apiKey)
                .header("User-Agent", WEB_UA)
                .build()
            val resp = http.newCall(req).execute()
            val code = resp.code
            val body = try { resp.body?.string() } catch (_: Exception) { null }
            if (code == 429 && attempt < 3) {
                val wait = resp.header("Retry-After")?.toLongOrNull() ?: 2L
                resp.close()
                TdLib.log("subs: rate limited, waiting ${wait}s")
                Thread.sleep(wait * 1000)
                continue
            }
            resp.close()
            if (code != 200) {
                TdLib.log("subs: search $suffix failed HTTP $code ${body?.take(200) ?: ""}")
                return emptyList()
            }
            val data = try {
                JSONObject(body ?: "").optJSONArray("data")
            } catch (e: Exception) {
                TdLib.log("subs: search $suffix bad JSON: ${(body ?: "").take(160)}")
                return emptyList()
            } ?: run {
                TdLib.log("subs: search $suffix no 'data': ${(body ?: "").take(160)}")
                return emptyList()
            }
            return parseEntries(data)
        }
    }

    private fun parseEntries(data: org.json.JSONArray): List<Entry> {
        val out = mutableListOf<Entry>()
        for (i in 0 until data.length()) {
            val attrs = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val language = attrs.optString("language").takeIf { it.isNotBlank() } ?: "xx"
            val label = attrs.optString("release").takeIf { it.isNotBlank() }
                ?: attrs.optString("movie_title_plain", "")
            // Flag candidates the viewer may want to avoid / prefer
            val flags = buildList {
                if (attrs.optBoolean("hearing_impaired", false)) add("CC")
                if (attrs.optBoolean("ai_translated", false) ||
                    attrs.optBoolean("machine_translated", false)
                ) add("AI")
            }.joinToString("/")
            val files = attrs.optJSONArray("files") ?: continue
            // One srt file per result row keeps the list readable
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.optString("file_name")
                val ext = name.substringAfterLast('.', "srt").lowercase()
                if (ext in setOf("srt", "ass", "vtt", "ssa")) {
                    val id = f.optLong("file_id", 0L)
                    if (id > 0) {
                        out.add(Entry(id, language, label, flags))
                        break
                    }
                }
            }
        }
        return out
    }

    /**
     * POST /api/v1/download returns a temporary CDN link; the actual bytes may
     * still be delivered behind a follow. Returns the cached .srt file.
     */
    private fun downloadFile(apiKey: String, fileId: Long, context: Context, videoFileId: Int): File? {
        val payload = """{"file_id":$fileId}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.opensubtitles.com/api/v1/download")
            .header("Api-Key", apiKey)
            .header("User-Agent", WEB_UA)
            .post(payload)
            .build()
        // The download endpoint occasionally answers 5xx with its HTML error
        // page (seen live); one quick retry rides out those blips.
        repeat(2) { attempt ->
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val link = JSONObject(body).optString("link")
                    if (link.isNotBlank()) return File(downloadedBytes(context, link, videoFileId))
                    TdLib.log("subs: download file_id=$fileId no link: ${body.take(200)}")
                    return null
                }
                if (resp.code in 500..599 && attempt == 0) {
                    TdLib.log("subs: download file_id=$fileId HTTP ${resp.code}, retrying")
                    Thread.sleep(3_000)
                } else {
                    TdLib.log("subs: download file_id=$fileId failed HTTP ${resp.code} ${body.take(200)}")
                    return null
                }
            }
        }
        return null
    }

    private fun downloadedBytes(context: Context, link: String, videoFileId: Int): String {
        val f = File(context.cacheDir, "subs_$videoFileId.srt")
        val req = Request.Builder().url(link).header("User-Agent", WEB_UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("subtitle download failed (${resp.code})")
            }
            resp.body?.byteStream()?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
        }
        if (f.length() == 0L) throw IllegalStateException("empty subtitle response")
        return f.absolutePath
    }

    /** Drops subtitle files older than a day so the cache dir cannot refill forever. */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        context.cacheDir.listFiles { f ->
            f.name.startsWith("subs_") && f.lastModified() < cutoff
        }?.forEach { it.delete() }
    }
}

/** Tiny helper for nicer subtitle names in the player track selector. */
object Languages {
    fun nameOf(iso: String): String {
        val base = iso.substringBefore('-')
        return java.util.Locale.forLanguageTag(base).displayLanguage.ifBlank { iso }
            .replaceFirstChar { it.uppercase() }
    }
}
