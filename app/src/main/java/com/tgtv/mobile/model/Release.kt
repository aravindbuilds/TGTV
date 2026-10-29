package com.tgtv.mobile.model

/** Extracts release-scene labels (quality, source, audio) from a video file name. */
object Release {

    private val QUALITY = Regex(
        """(?i)\b(2160p|1440p|1080p|1080i|720p|576p|480p|360p)\b""",
    )

    private val SOURCE = Regex(
        """(?i)\b(web-?dl|webrip|web|bluray|blu-ray|bdrip|brrip|dvdrip|hdtv|hdrip|hdts|cam)\b""",
    )

    private val AUDIO = Regex(
        """(?i)\b(hindi|tamil|telugu|malayalam|kannada|marathi|punjabi|bhojpuri|bengali|english|japanese|korean|chinese|mandarin|spanish|french|german|dual[ ._-]?audio|multi[ ._-]?audio|dubbed|tamil[ ._-]?dub|ta[ ._-]?dub)\b""",
    )

    /** "1080p", "4K" for 2160, or null when not stated. */
    fun quality(fileName: String): String? {
        val m = QUALITY.find(fileName)?.value?.lowercase() ?: return null
        return if (m == "2160p" || m == "1440p") "4K" else m
    }

    /** Audio language hint ("Hindi", "Dual Audio", ...) or null. */
    fun audio(fileName: String): String? =
        AUDIO.find(fileName)?.value?.replace(Regex("[._-]"), " ")?.trim()
            ?.replaceFirstChar { it.uppercase() }

    /** Distribution source ("WEB-DL", "BluRay", ...) or null. */
    fun source(fileName: String): String? =
        SOURCE.find(fileName)?.value?.replace(Regex("[._-]"), "")
            ?.replaceFirstChar { it.uppercase() }
            ?.let { if (it.equals("Web", true)) "WEB" else it }

    /** Human version label: "1080p • WEB-DL • Hindi". */
    fun describe(fileName: String): String =
        listOfNotNull(quality(fileName), source(fileName), audio(fileName))
            .joinToString(" • ")

    /** Quality ranking for sorting version lists (better first). */
    fun qualityRank(fileName: String): Int = when (quality(fileName)) {
        "4K" -> 4
        "1080p" -> 3
        "1080i" -> 2
        "720p" -> 2
        "576p" -> 1
        "480p" -> 1
        "360p" -> 0
        else -> -1
    }
}
