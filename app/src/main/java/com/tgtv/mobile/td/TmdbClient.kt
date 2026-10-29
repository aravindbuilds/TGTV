package com.tgtv.mobile.td

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal TMDB metadata client. Parses a release-style file name, queries
 * TMDB's search/multi endpoint and caches the top hit per query. Uses a
 * bring-your-own API key stored via [Credentials.setTmdbKey].
 */
object TmdbClient {

    /** Optional fallback TMDB key. Left empty on purpose: paste your own
     *  free key from themoviedb.org here, or enter it in-app via
     *  Settings > TMDB (the user key always wins when present). */
    private const val BUILT_IN_KEY = ""

    data class CastMember(val name: String, val character: String, val profilePath: String?)

    data class Info(
        val title: String,
        val year: Int?,
        val rating: Double?,
        val overview: String,
        val tmdbId: Int = 0,
        val mediaType: String? = null,
        val backdropPath: String? = null,
        val posterPath: String? = null,
        val genres: List<String> = emptyList(),
        val runtimeMinutes: Int? = null,
        val tagline: String? = null,
        val status: String? = null,
        val cast: List<CastMember> = emptyList(),
        /** Official title-treatment (poster logo) path, for the hero. */
        val logoPath: String? = null,
        /** Disk-cache key this Info was resolved under (internal). */
        val cacheKey: String? = null,
    )

    /** Sentinel for "TMDB has no such title" (ConcurrentHashMap rejects nulls). */
    private val MISS = Info("", null, null, "")

    private const val WEB_UA = "TGTV/0.3 (github.com/aravindbuilds/TelegramVideoTV)"

    /** Normalized query -> top result (misses cached through [MISS] to avoid refetching). */
    private val cache = ConcurrentHashMap<String, Info>()

    // ------------------------------------------------------------ disk cache
    // Every successful lookup is persisted as a tiny JSON file, so a restart
    // (and its burst of card lookups) reads local files instead of hammering
    // TMDB again - no repeated 429s, instant grids.

    private var diskDir: java.io.File? = null

    private fun ensureDisk(context: Context) {
        if (diskDir == null) {
            diskDir = java.io.File(context.filesDir, "tmdb").apply { mkdirs() }
        }
    }

    private fun diskFileFor(key: String): java.io.File? {
        val dir = diskDir ?: return null
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$digest.json")
    }

    private fun saveDisk(key: String, info: Info) {
        val f = diskFileFor(key) ?: return
        runCatching {
            val cast = org.json.JSONArray()
            info.cast.forEach { c ->
                cast.put(
                    org.json.JSONObject()
                        .put("n", c.name)
                        .put("c", c.character)
                        .put("p", c.profilePath ?: ""),
                )
            }
            val o = org.json.JSONObject()
                .put("title", info.title)
                .put("overview", info.overview)
                .put("tmdbId", info.tmdbId)
            info.year?.let { o.put("year", it) }
            info.rating?.let { o.put("rating", it) }
            info.mediaType?.let { o.put("mediaType", it) }
            info.backdropPath?.let { o.put("backdropPath", it) }
            info.posterPath?.let { o.put("posterPath", it) }
            info.runtimeMinutes?.let { o.put("runtime", it) }
            info.tagline?.let { o.put("tagline", it) }
            info.status?.let { o.put("status", it) }
            info.logoPath?.let { o.put("logoPath", it) }
            info.cacheKey?.let { o.put("cacheKey", it) }
            if (info.genres.isNotEmpty()) o.put("genres", org.json.JSONArray(info.genres))
            if (info.cast.isNotEmpty()) o.put("cast", cast)
            f.writeText(o.toString())
        }
    }

    private fun readDisk(key: String): Info? {
        val f = diskFileFor(key) ?: return null
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            val o = org.json.JSONObject(f.readText())
            val cast = mutableListOf<CastMember>()
            o.optJSONArray("cast")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    cast.add(
                        CastMember(
                            c.optString("n"),
                            c.optString("c"),
                            c.optString("p").ifBlank { null },
                        ),
                    )
                }
            }
            val genres = mutableListOf<String>()
            o.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) genres.add(arr.optString(i))
            }
            Info(
                title = o.getString("title"),
                year = o.optInt("year", -1).takeIf { it >= 0 },
                rating = o.optDouble("rating", Double.NaN).takeIf { !it.isNaN() },
                overview = o.optString("overview"),
                tmdbId = o.optInt("tmdbId"),
                mediaType = o.optString("mediaType").ifBlank { null },
                backdropPath = o.optString("backdropPath").ifBlank { null },
                posterPath = o.optString("posterPath").ifBlank { null },
                genres = genres,
                runtimeMinutes = o.optInt("runtime", -1).takeIf { it >= 0 },
                tagline = o.optString("tagline").ifBlank { null },
                status = o.optString("status").ifBlank { null },
                cast = cast,
                logoPath = o.optString("logoPath").ifBlank { null },
                cacheKey = o.optString("cacheKey").ifBlank { null },
            )
        }.getOrNull()
    }

    /**
     * Caps concurrent TMDB searches: a grid of cards firing lookups at once
     * would burst past the API's rate limit (429s) and stall the emulator's
     * NAT; three at a time stays comfortably inside every limit.
     */
    private val lookupGate = kotlinx.coroutines.sync.Semaphore(3)

    /** On by default; supported by the built-in key or a user override in prefs. */
    fun enabled(context: Context): Boolean = !Credentials.tmdbDisabled(context)

    /** User-entered key overrides the built-in one when present. */
    private fun apiKey(context: Context): String = Credentials.tmdbKey(context) ?: BUILT_IN_KEY

    /**
     * "Movie.Name.2023.1080p.WEB-DL.x264-GROUP.mkv" -> "Movie Name".
     * Strips the extension, bracketed tags, release-scene tokens and separators.
     */
    /** TV season/episode markers that break series-name matching:
     *  "S01E05", "1x05", "Season 2 Episode 3", "EP05", "e12". */
    private val EPISODE_MARKERS = Regex(
        """(?i)\b(?:s\d{1,2}[\s._-]?e\d{1,4}|s\d{1,2}\b|\d{1,2}x\d{1,3}|season[\s._-]?\d{1,2}(?:[\s._-]*(?:episode|ep)[\s._-]?\d{1,4})?|episode[\s._-]?\d{1,4}|ep[\s._-]?\d{1,4}|e\d{2,4})\b""",
    )

    fun parseTitle(fileName: String): String {
        var t = fileName.substringBeforeLast('.')
        t = t.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        // Unbalanced trailing paren group, e.g. "Movie.Name.(HDRip" with no ')'
        t = t.replace(Regex("""\(\s*\S.*$"""), " ")
        // Series files: drop the season/episode marker so the SERIES name
        // is what reaches TMDB ("Money.Heist.S05E03..." -> "Money Heist").
        // Every episode then resolves to the same show and shares the
        // disk-cached lookup.
        t = t.replace(EPISODE_MARKERS, " ")
        t = t.replace(RELEASE_TOKENS, " ")
        t = t.replace(Regex("""[._\-]+\s*"""), " ")
        t = t.replace(Regex("""[()\[\]:;]"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim(' ', '-')
        return t
    }

    /** Last plausible year token in the name ("Blade.Runner.2049" -> 2049). */
    fun parseYear(fileName: String): Int? =
        Regex("""(?<![\d.])(19\d{2}|20\d{2})(?![\d.pP])""")
            .findAll(fileName)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()

    /** Season/episode coordinates of a series file; null for movies. */
    data class EpisodeInfo(val season: Int, val episode: Int)

    /**
     * Extracts season + episode from a series file name: "S05E03", "5x03",
     * "Season 5 Episode 3". Movies return null. Mirrors the markers
     * [EPISODE_MARKERS] strips during title matching.
     */
    fun parseEpisodeInfo(fileName: String): EpisodeInfo? {
        for (p in EPISODE_PATTERNS) {
            val m = p.find(fileName) ?: continue
            val s = m.groupValues[1].toIntOrNull() ?: continue
            val e = m.groupValues[2].toIntOrNull() ?: continue
            return EpisodeInfo(s, e)
        }
        return null
    }

    private val EPISODE_PATTERNS = listOf(
        Regex("""(?i)\bs(\d{1,2})[\s._-]?e(\d{1,4})\b"""),
        Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b"""),
        Regex("""(?i)\bseason[\s._-]?(\d{1,2})[\s._-]*(?:episode|ep)[\s._-]?(\d{1,4})\b"""),
    )

    /** "S05 E03" badge label for the hero; null for movies. */
    fun parseEpisode(fileName: String): String? =
        parseEpisodeInfo(fileName)?.let { "S%02d E%02d".format(it.season, it.episode) }

    suspend fun lookup(context: Context, fileName: String): Info? {
        ensureDisk(context)
        val apiKey = apiKey(context)
        val query = parseTitle(fileName)
        if (query.isBlank()) return null
        if (JUNK_QUERY.matches(query.trim())) {
            TdLib.log("tmdb: skip junk '$query'")
            return null
        }
        val cacheKey = query.lowercase()
        cache[cacheKey]?.let { return it.takeUnless { hit -> hit === MISS } }
        val outcome = withContext(Dispatchers.IO) {
            readDisk(cacheKey)?.let { disk ->
                cache[cacheKey] = disk
                TdLib.log("tmdb: '$query' (disk)")
                Result.success<Info?>(disk)
            } ?: runCatching { fetch(apiKey, query) }
        }
        return outcome.fold(
            onSuccess = { info ->
                TdLib.log("tmdb: '$query' -> ${info?.title ?: "no match"}")
                val stamped = info?.copy(cacheKey = cacheKey)
                cache[cacheKey] = stamped ?: MISS
                if (stamped != null) saveDisk(cacheKey, stamped)
                stamped
            },
            onFailure = { e ->
                // Transport error: transient -> do NOT cache a MISS over it
                TdLib.log("tmdb: '$query' failed: ${e.message}")
                null
            },
        )
    }

    /**
     * Fast path for list screens: direct TMDB search with the retry ladder
     * only. No Wikipedia/DuckDuckGo web resolution, so unmatched titles fail
     * in seconds instead of minutes - details screens use [lookup] for the
     * full pipeline.
     */
    suspend fun lookupQuick(context: Context, fileName: String): Info? {
        ensureDisk(context)
        val apiKey = apiKey(context)
        val query = parseTitle(fileName)
        if (query.isBlank() || JUNK_QUERY.matches(query.trim())) return null
        val cacheKey = query.lowercase()
        cache[cacheKey]?.let { return it.takeUnless { hit -> hit === MISS } }
        val outcome = lookupGate.withPermit {
            withContext(Dispatchers.IO) {
                readDisk(cacheKey)?.let { disk ->
                    cache[cacheKey] = disk
                    TdLib.log("tmdb-quick: '$query' (disk)")
                    Result.success<Info?>(disk)
                } ?: runCatching { searchWithRetries(apiKey, query) }
            }
        }
        return outcome.fold(
            onSuccess = { info ->
                TdLib.log("tmdb-quick: '$query' -> ${info?.title ?: "no match"}")
                val stamped = info?.copy(cacheKey = cacheKey)
                cache[cacheKey] = stamped ?: MISS
                if (stamped != null) saveDisk(cacheKey, stamped)
                stamped
            },
            onFailure = { e ->
                TdLib.log("tmdb-quick: '$query' failed: ${e.message}")
                null
            },
        )
    }

    /**
     * Progressive retries: exact query -> doubled letters collapsed (TMDB has
     * "Chadum" for files spelled "Chaadum" - transliterated titles vary) ->
     * year-tokens removed -> both -> -> then a web-assisted pass: Wikipedia /
     * DuckDuckGo give the canonical title, which is fed back into TMDB.
     * First hit wins.
     */
    private fun fetch(apiKey: String, query: String): Info? {
        searchWithRetries(apiKey, query)?.let {
            TdLib.log("tmdb: matched '$query'")
            return it
        }
        val candidates = webCandidates(query).take(4)
        var sawMiss = false
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            try {
                val info = searchWithRetries(apiKey, candidate)
                if (info != null) {
                    TdLib.log("tmdb: web-resolved '$query' -> '$candidate'")
                    return info
                }
                sawMiss = true
            } catch (_: Exception) {
                // transport error on this candidate - keep trying others
            }
        }
        if (!sawMiss && candidates.isNotEmpty()) {
            // every path failed on transport, not "no such title"
            throw IllegalStateException("all web resolution attempts failed")
        }
        return null
    }

    /** Attempt pipeline for one base title: fuzzy + year-stripped variants.
     *  Throws when every attempt died on a transport error (never the API's fault). */
    private fun searchWithRetries(apiKey: String, base: String): Info? {
        val collapsed = collapseDoubles(base)
        val yearless = removeYears(base)
        val attempts = linkedSetOf(base, collapsed, yearless, collapseDoubles(yearless))
        // Truncation tolerance: forwarded/renamed files often end mid-token
        // ("Lokah.Chapter.1.Chandra.HDRi") - retry with the trailing word(s)
        // dropped, since the tail is almost always release junk
        val words = base.trim().split(' ').filter { it.isNotBlank() }
        if (words.size > 1) {
            val cut1 = words.dropLast(1).joinToString(" ")
            val cut2 = words.dropLast(2).ifEmpty { words.dropLast(1) }.joinToString(" ")
            attempts.add(removeYears(cut1))
            attempts.add(collapseDoubles(removeYears(cut2)))
        }
        var attempted = 0
        var transportFails = 0
        var lastError: Exception? = null
        for (attempt in attempts) {
            if (attempt.isBlank()) continue
            attempted++
            try {
                fetchOnce(apiKey, attempt)?.let { return it }
            } catch (e: Exception) {
                transportFails++
                lastError = e
            }
        }
        if (attempted in 1..transportFails && lastError != null) throw lastError
        return null
    }

    /** Collapses runs of repeated letters: "Chaadum" -> "Chadum". */
    private fun collapseDoubles(query: String): String =
        Regex("""([a-z])\1+""")
            .replace(query.lowercase()) { m -> m.groupValues[1] }
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun removeYears(query: String): String =
        Regex("""\b(?:19|20)\d{2}\b""").replace(query, " ").replace(Regex("""\s+"""), " ").trim()

    // ---------------------------------------------------- Web title resolve

    /**
     * Films (especially transliterated Indian ones) often only match TMDB
     * under their canonical wiki spelling. Ask Wikipedia + DuckDuckGo for
     * candidate titles and let the caller feed each into TMDB.
     */
    private fun webCandidates(query: String): List<String> {
        val out = mutableListOf<String>()
        out.addAll(wikipediaTitles(query))
        out.addAll(duckDuckGoTitles(query))
        return out
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
    }

    private fun wikipediaTitles(query: String): List<String> {
        val url = URL(
            "https://en.wikipedia.org/w/api.php?action=opensearch&search=" +
                URLEncoder.encode(query, "UTF-8") + "&limit=5&namespace=0&format=json",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", WEB_UA)
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // opensearch returns [ "query", [titles], [descriptions], [urls] ]
            val titles = JSONArray(body).optJSONArray(1) ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until titles.length()) {
                cleanWebTitle(titles.optString(i))?.let { out.add(it) }
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun duckDuckGoTitles(query: String): List<String> {
        // Filename + "imdb" forgives misspellings: the top hit is the film's
        // canonical page, whose visible title carries the real name
        val ddgQuery = query + " imdb"
        val conn = URL("https://lite.duckduckgo.com/lite/").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", WEB_UA)
            conn.outputStream.use {
                it.write(("q=" + URLEncoder.encode(ddgQuery, "UTF-8")).toByteArray())
            }
            if (conn.responseCode != 200) {
                emptyList()
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                RESULT_LINK.findAll(body)
                    .map { m -> cleanWebTitle(m.groupValues[1]) }
                    .filterNotNull()
                    .take(5)
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private val RESULT_LINK = Regex(
        """class="result-link"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Drops site suffixes and parenthetical qualifiers from a result link text. */
    private fun cleanWebTitle(raw: String): String? {
        var s = raw
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            .trim()
        // Strip trailing site names: "Title - Wikipedia", "Title | IMDb", "Title" alone
        s = s.split(Regex("""\s[-|]\s""")).first().trim()
        // Strip trailing "(2025 film)" style qualifiers
        s = s.replace(Regex("""\s*\((?:19|20)?\d{2}(?:\s+(?:film|movie|channel|tv series))?\)$"""), "")
        if (s.lowercase() == "wikipedia" || s.lowercase() == "imdb") return null
        return s.ifBlank { null }
    }

    /**
     * One TMDB search request. Stale keep-alive sockets on the TV's Wi-Fi
     * intermittently reset mid-handshake ("connection reset by peer"), so each
     * request uses a fresh socket (Connection: close) and gets one retry.
     */
    private fun fetchOnce(apiKey: String, query: String): Info? {
        val url = URL(
            "https://api.themoviedb.org/3/search/multi?api_key=" + apiKey +
                "&query=" + URLEncoder.encode(query, "UTF-8") +
                "&include_adult=false&language=en-US&page=1",
        )
        return try {
            tmdbSearch(url)
        } catch (e: java.io.IOException) {
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            tmdbSearch(url)
        }
    }

    private fun tmdbSearch(url: URL): Info? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            // Rate limited: back off briefly and retry once on a fresh socket
            if (code == 429) {
                try { Thread.sleep(1_500) } catch (_: InterruptedException) {}
                return tmdbSearchFresh(url)
            }
            if (code != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val type = r.optString("media_type")
                if (type != "movie" && type != "tv") continue
                val title = r.optString(if (type == "movie") "title" else "name")
                if (title.isBlank()) continue
                val date = r.optString(if (type == "movie") "release_date" else "first_air_date")
                val year = if (date.length >= 4) date.substring(0, 4).toIntOrNull() else null
                val rating = if (r.has("vote_average") && !r.isNull("vote_average")) {
                    r.optDouble("vote_average").takeIf { it > 0.0 }
                } else {
                    null
                }
                return Info(
                    title = title,
                    year = year,
                    rating = rating,
                    overview = r.optString("overview"),
                    tmdbId = r.optInt("id", 0),
                    mediaType = type,
                    backdropPath = r.optString("backdrop_path").takeIf { it.isNotBlank() },
                    posterPath = r.optString("poster_path").takeIf { it.isNotBlank() },
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** One fresh-socket retry pass, used after a rate-limit backoff. */
    private fun tmdbSearchFresh(url: URL): Info? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val type = r.optString("media_type")
                if (type != "movie" && type != "tv") continue
                val title = r.optString(if (type == "movie") "title" else "name")
                if (title.isBlank()) continue
                val date = r.optString(if (type == "movie") "release_date" else "first_air_date")
                val year = if (date.length >= 4) date.substring(0, 4).toIntOrNull() else null
                val rating = if (r.has("vote_average") && !r.isNull("vote_average")) {
                    r.optDouble("vote_average").takeIf { it > 0.0 }
                } else {
                    null
                }
                return Info(
                    title = title,
                    year = year,
                    rating = rating,
                    overview = r.optString("overview"),
                    tmdbId = r.optInt("id", 0),
                    mediaType = type,
                    backdropPath = r.optString("backdrop_path").takeIf { it.isNotBlank() },
                    posterPath = r.optString("poster_path").takeIf { it.isNotBlank() },
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** True when [info] is a TMDB hit that has not had details (genres/runtime) fetched yet. */
    fun needsDetails(info: Info?): Boolean =
        info != null && info.tmdbId != 0 && info.genres.isEmpty() && info.mediaType != null

    /**
     * Fetches the TMDB details page for a hit (genres, runtime, full backdrop)
     * and returns an enriched copy. Failures return the base [info] unchanged.
     */
    suspend fun enrich(context: Context, info: Info): Info {
        if (!needsDetails(info)) return info
        val enriched = withContext(Dispatchers.IO) {
            runCatching { fetchDetails(apiKey(context), info) }.getOrNull()
        }
        if (enriched != null) {
            // Persist the ENRICHED copy too: next launch's lookup reads the
            // full record (genres + runtime + cast) from disk and the
            // details screen renders its hero with zero network.
            cache[info.title.lowercase()] = enriched
            saveDisk(info.cacheKey ?: info.title.lowercase(), enriched)
        }
        return enriched ?: info
    }

    /**
     * Fetches the movie's official title-treatment (poster logo, transparent
     * wordmark) once, persists it, and returns the updated copy. Failures
     * return [info] unchanged so callers fall back to the text title.
     */
    suspend fun withLogo(context: Context, info: Info): Info {
        if (info.logoPath != null || info.tmdbId == 0 || info.mediaType == null) return info
        return lookupGate.withPermit {
            withContext(Dispatchers.IO) {
                val path = runCatching { fetchLogoPath(apiKey(context), info) }.getOrNull()
                if (path.isNullOrBlank()) info else {
                    val out = info.copy(logoPath = path)
                    cache[(info.cacheKey ?: info.title).lowercase()] = out
                    saveDisk(info.cacheKey ?: info.title.lowercase(), out)
                    out
                }
            }
        }
    }

    private fun fetchLogoPath(apiKey: String, info: Info): String? {
        val url = URL(
            "https://api.themoviedb.org/3/" + info.mediaType + "/" + info.tmdbId +
                "/images?api_key=" + apiKey,
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val logos = JSONObject(body).optJSONArray("logos") ?: return null
            var fallback: String? = null
            var noLang: String? = null
            for (i in 0 until logos.length()) {
                val l = logos.getJSONObject(i)
                val path = l.optString("file_path").takeIf { it.isNotBlank() } ?: continue
                when (l.optString("iso_639_1")) {
                    "en" -> return path
                    "" -> if (noLang == null) noLang = path
                    else -> if (fallback == null) fallback = path
                }
            }
            return noLang ?: fallback
        } finally {
            conn.disconnect()
        }
    }
    private fun fetchDetails(apiKey: String, info: Info): Info {
        val url = URL(
            "https://api.themoviedb.org/3/" + info.mediaType + "/" + info.tmdbId +
                "?api_key=" + apiKey + "&language=en-US" +
                "&append_to_response=credits",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Connection", "close")
            if (conn.responseCode != 200) return info
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val d = JSONObject(body)
            val genres = mutableListOf<String>()
            d.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.getJSONObject(i).optString("name")?.takeIf { it.isNotBlank() }
                        ?.let { genres.add(it) }
                }
            }
            val runtime = when (info.mediaType) {
                "movie" -> d.optInt("runtime", 0).takeIf { it > 0 }
                else -> d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
            }
            val cast = mutableListOf<CastMember>()
            d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 12)) {
                    val c = arr.getJSONObject(i)
                    val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                    cast.add(
                        CastMember(
                            name = name,
                            character = c.optString("character"),
                            profilePath = c.optString("profile_path").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            return info.copy(
                genres = genres,
                runtimeMinutes = runtime,
                rating = info.rating ?: run {
                    if (d.has("vote_average") && !d.isNull("vote_average")) {
                        d.optDouble("vote_average").takeIf { it > 0.0 }
                    } else null
                },
                overview = info.overview.ifBlank { d.optString("overview") },
                backdropPath = info.backdropPath ?: d.optString("backdrop_path"),
                tagline = d.optString("tagline").takeIf { it.isNotBlank() },
                status = d.optString("status").takeIf { it.isNotBlank() },
                cast = cast,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Release-scene junk that must not end up in the search query. */    private val RELEASE_TOKENS = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|360p|4k|uhd|hdr(?:ip|10)?\+?|dv|hq|hd|dl|dolby|vision|x264|x265|h\.?264|h\.?265|hevc|avc|web-?dl|webrip|web|bluray|blu-ray|bdrip|brrip|dvdrip|dvdscr|hdtv|hdrip|hdts|cam|aac\d*|ac3|eac3|ddp?\d*(?:\.\d)?|dts(?:-hd)?|truehd|atmos|mp3|flac|10bit|8bit|esubs?|hc|proper|repack|extended|unrated|remastered|multi|dual(?:\s?audio)?|internal|amzn|nf|dsnp|hulu|max|itunes|netflix|yify|rargb|evo|tgx|megapek|adjutant|skillhunt|malaya|mkv|mp4|avi|m(?:kv|ov)|webm|m4v|mpg|mpeg|wmv|flv|m2ts|mts|hindi|english|tamil|telugu|malayalam|kannada|marathi|punjabi|bhojpuri|bengali|gujarati|urdu|japanese|korean|chinese|mandarin|cantonese|spanish|french|german|italian|russian|turkish|arabic|persian|portuguese)\b""",
    )

    /** Camera/auto-generated file names that must never be searched on TMDB. */
    private val JUNK_QUERY = Regex(
        """(?i)^(?:img|dsc|mvi|vid|video|movie|vig|vin|tmp|record|screen|screenshot|whatsapp)[ ._-]*(?:\d{1,8})?$""",
    )
}
