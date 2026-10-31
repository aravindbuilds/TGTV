package com.tgtv.mobile.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named favourites lists, persisted in prefs as JSON:
 * `[{"name": "My List", "items": [...]}, {"name": "Anime", "items": [...]}]`.
 *
 * A video can live in several lists at once. The grid/detail heart uses
 * quick semantics (tap = add to the default list / remove from every list),
 * and a long-press chooser manages per-list membership.
 *
 * The pre-names-lists storage ("watchlist_items", one flat set) migrates
 * into a single "My List" on first access.
 */
object Watchlist {

    /** The list the quick heart targets. Always exists after migration. */
    const val DEFAULT = "My List"

    private const val KEY = "watchlist_lists"
    private const val LEGACY_KEY = "watchlist_items"

    data class FavList(val name: String, val items: List<VideoItem>)

    fun lists(context: Context): List<FavList> =
        migrate(context).map { (name, items) -> FavList(name, items) }

    fun names(context: Context): List<String> = lists(context).map { it.name }

    /** Videos of one named list (empty when unknown). */
    fun items(context: Context, name: String): List<VideoItem> =
        lists(context).firstOrNull { it.name == name }?.items ?: emptyList()

    /** Union of every list, deduplicated - the "All" view of Favourites. */
    fun all(context: Context): List<VideoItem> {
        val seen = LinkedHashSet<Pair<Long, String>>()
        val out = mutableListOf<VideoItem>()
        for (v in lists(context).flatMap { it.items }) {
            if (seen.add(v.messageId to v.chatTitle)) out.add(v)
        }
        return out
    }

    /** True when the video is in at least one list (the heart's active state). */
    fun contains(context: Context, video: VideoItem): Boolean =
        lists(context).any { l -> l.items.any { same(it, video) } }

    /**
     * Quick heart: none of the lists have it -> add to the default list;
     * any list has it -> remove from every list. Returns true when the
     * video ends up bookmarked somewhere.
     */
    fun toggle(context: Context, video: VideoItem): Boolean {
        val ls = migrate(context)
        val inAny = ls.values.any { l -> l.any { same(it, video) } }
        if (inAny) {
            ls.values.forEach { l -> l.removeAll { same(it, video) } }
        } else {
            ls.getOrPut(DEFAULT) { mutableListOf() }.add(0, video)
        }
        save(context, ls)
        return !inAny
    }

    /** Names of every list currently holding [video]. */
    fun membership(context: Context, video: VideoItem): Set<String> =
        lists(context).filter { l -> l.items.any { same(it, video) } }.map { it.name }.toSet()

    /** Replaces [video]'s list membership wholesale (the chooser dialog). */
    fun setMembership(context: Context, video: VideoItem, listNames: Set<String>) {
        val ls = migrate(context)
        for ((name, items) in ls) {
            val had = items.any { same(it, video) }
            val wants = name in listNames
            when {
                had && !wants -> items.removeAll { same(it, video) }
                !had && wants -> items.add(0, video)
            }
        }
        save(context, ls)
    }

    /** Creates a list; returns false when the name exists (or is blank). */
    fun create(context: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || lists(context).any { it.name.equals(n, ignoreCase = true) }) return false
        val ls = migrate(context)
        ls[n] = mutableListOf()
        save(context, ls)
        return true
    }

    fun rename(context: Context, old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty() || ls(context)?.containsKey(n) == true) return false
        val ls = migrate(context)
        val items = ls.remove(old) ?: return false
        ls[n] = items
        save(context, ls)
        return true
    }

    fun delete(context: Context, name: String) {
        val ls = migrate(context)
        if (ls.size <= 1) return // never leave the user without any list
        ls.remove(name)
        save(context, ls)
    }

    // ------------------------------------------------------------ plumbing

    private fun same(a: VideoItem, b: VideoItem) =
        a.messageId == b.messageId && a.chatTitle == b.chatTitle

    /** Prefs storage as ordered name -> items. */
    private fun ls(context: Context): MutableMap<String, MutableList<VideoItem>>? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<String, MutableList<VideoItem>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = mutableListOf<VideoItem>()
                val ia = o.optJSONArray("items") ?: JSONArray()
                for (j in 0 until ia.length()) {
                    decode(ia.getJSONObject(j))?.let { items.add(it) }
                }
                out[o.optString("name", DEFAULT)] = items
            }
            out
        }.getOrNull()
    }

    /** First access: migrate the legacy flat list into "My List". */
    private fun migrate(context: Context): MutableMap<String, MutableList<VideoItem>> {
        ls(context)?.let { return it }
        val out = LinkedHashMap<String, MutableList<VideoItem>>()
        val legacy = prefs(context).getString(LEGACY_KEY, null)
        if (legacy != null) {
            runCatching {
                val arr = JSONArray(legacy)
                (0 until arr.length()).mapNotNull { decode(arr.getJSONObject(it)) }
                    .forEach { out.getOrPut(DEFAULT) { mutableListOf() }.add(it) }
            }
            // Keep the legacy copy until the first save succeeds below.
        }
        out.getOrPut(DEFAULT) { mutableListOf() }
        save(context, out)
        prefs(context).edit().remove(LEGACY_KEY).apply()
        return out
    }

    private fun save(context: Context, ls: Map<String, List<VideoItem>>) {
        val arr = JSONArray()
        for ((name, items) in ls) {
            val ia = JSONArray()
            items.forEach { ia.put(encode(it)) }
            arr.put(JSONObject().put("name", name).put("items", ia))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun encode(v: VideoItem) = JSONObject().apply {
        put("messageId", v.messageId)
        put("chatTitle", v.chatTitle)
        put("title", v.title)
        put("fileId", v.fileId)
        put("fileSize", v.fileSize)
        put("durationSeconds", v.durationSeconds)
        put("mimeType", v.mimeType)
        put("fileName", v.fileName)
        put("thumbnailFileId", v.thumbnailFileId)
        put("chatId", v.chatId)
        put("remoteFileId", v.remoteFileId)
        put("fileKind", v.fileKind)
    }

    private fun decode(o: JSONObject) = runCatching {
        VideoItem(
            o.getLong("messageId"),
            o.optString("chatTitle"),
            o.optString("title", o.optString("fileName")),
            o.getInt("fileId"),
            o.getLong("fileSize"),
            o.getInt("durationSeconds"),
            o.optString("mimeType"),
            o.optString("fileName", o.optString("title")),
            o.getInt("thumbnailFileId"),
            o.optLong("chatId"),
            o.optString("remoteFileId"),
            o.optString("fileKind", "video"),
        )
    }.getOrNull()

    private fun prefs(context: Context) =
        context.getSharedPreferences("tgvideotv", Context.MODE_PRIVATE)
}
