// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.lyrics

/** Artist and song title as a lyrics search needs them. */
data class CleanTitle(val artist: String?, val title: String)

/**
 * Turns a video or file title into artist and song title for a Lyrics search (ADR 6): music
 * video titles carry clutter such as "(Official Video)", "[4K]" or "| Official Audio", and often
 * put the artist in front ("Artist - Title").
 */
object TitleCleaner {
    /** Words that only describe the upload, in the languages common on music videos. */
    private const val CLUTTER_WORDS =
        "official|offizielle?s?|oficial|officiel(le)?|video|videoclip|musikvideo|audio|lyrics?|letra|paroles|songtext|" +
            "music|clip|visuali[sz]er|hd|hq|4k|8k|1080p|720p|remaster(ed)?|upgrade|mv|m/v|explicit|\\d{4}"
    private const val SEPARATORS = """[\s\-–—/|,&+]"""
    private val bracketed = Regex("""\s*[(\[【]([^)\]】]*)[)\]】]""")
    private val clutterGroup = Regex("""^$SEPARATORS*(($CLUTTER_WORDS)$SEPARATORS*)+$""", RegexOption.IGNORE_CASE)
    private val creditGroup = Regex("""^\s*(feat\.?|ft\.?|featuring|prod\.?|produced)\s""", RegexOption.IGNORE_CASE)
    private val featuringTail = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    /** Clutter after a dash or bar may be any clutter words ("Title - Official Video"). */
    private val dashedClutter = Regex("""\s*[-–—|/]\s*(($CLUTTER_WORDS)$SEPARATORS*)+$""", RegexOption.IGNORE_CASE)

    /**
     * Clutter after a plain space must be unmistakable: "Music" or "Video" alone may be part of
     * the song title ("Pop Music"), "Official Video" or "HD" may not.
     */
    private val spacedClutter = Regex(
        """\s+((official|offizielles?|oficial|officiel(le)?)(\s+(music|lyric|4k|hd))?\s+(video|audio|mv|clip|musikvideo|videoclip)|""" +
            """lyric video|lyrics?|hd|hq|4k|8k|1080p|720p|m/v|mv)$""",
        RegexOption.IGNORE_CASE,
    )
    private val artistTitle = Regex("""\s+[-–—]\s+""")
    private val topicChannel = Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE)
    private val vevo = Regex("""VEVO$""", RegexOption.IGNORE_CASE)
    private val quotes = Regex("""^["“”'‘’]+|["“”'‘’]+$""")

    /** [raw] is the video or file title; [artist] what the Source knows (e.g. the channel), if anything. */
    fun clean(raw: String, artist: String?): CleanTitle {
        var text = raw.replace('_', ' ').collapse()
        text = text.split(" | ").first()
        // Bracketed groups that are only clutter, a featured artist or a producer credit.
        text = bracketed.replace(text) { group ->
            val inside = group.groupValues[1]
            if (clutterGroup.matches(inside) || creditGroup.containsMatchIn(inside)) "" else group.value
        }.collapse()
        // An auto-generated "Artist - Topic" channel names its videos after the song alone.
        val fromTopicChannel = artist != null && topicChannel.containsMatchIn(artist)
        val knownArtist = artist?.let { vevo.replace(topicChannel.replace(it, ""), "") }?.collapse()?.ifEmpty { null }
        val parts = if (fromTopicChannel) listOf(text) else artistTitle.split(text, limit = 2)
        val (foundArtist, title) = if (parts.size == 2) parts[0] to parts[1] else null to text
        return CleanTitle(
            artist = (foundArtist ?: knownArtist)?.let { featuringTail.replace(it, "") }?.trim(),
            title = cleanTitle(title),
        )
    }

    private fun cleanTitle(title: String): String =
        quotes.replace(stripTrailingClutter(featuringTail.replace(title, "")).trim(), "").trim()

    private fun stripTrailingClutter(text: String): String {
        var current = text
        while (true) {
            val next = spacedClutter.replace(dashedClutter.replace(current, ""), "").trim()
            if (next == current || next.isEmpty()) return current // never strip the whole title
            current = next
        }
    }

    private fun String.collapse() = trim().replace(Regex("""\s+"""), " ")
}
