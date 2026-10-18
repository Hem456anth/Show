package app.show.tv.data

import androidx.compose.runtime.Immutable

/**
 * One playable entry of an M3U/IPTV playlist.
 *
 * Field mapping mirrors VLC's `modules/demux/playlist/m3u.c`:
 *  - `tvg-id`      -> [tvgId]      (VLC: XMLTV/tvg-id info)
 *  - `tvg-name`    -> preferred over the trailing title when present
 *  - `tvg-logo`    -> [logoUrl]    (VLC: album art)
 *  - `tvg-language`-> [language]
 *  - `group-title` / `#EXTGRP` -> [categories], [countries] or [languages] depending on the
 *    playlist's [PlaylistSource.Grouping] (iptv-org uses ';' for multi-value groups)
 *  - `#EXTVLCOPT:http-user-agent` / `http-referrer` and the inline `http-*` attributes
 *    -> [userAgent] / [referrer], replayed as request headers by the player.
 */
@Immutable
data class Channel(
    /** Stable id derived from the stream URL so favourites survive playlist refreshes. */
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val categories: List<String>,
    val tvgId: String?,
    val language: String?,
    val country: String?,
    val resolution: String?,
    val flags: Set<String>,
    val userAgent: String?,
    val referrer: String?,
    /** Every country the channel is listed under (English display names, e.g. "India"). */
    val countries: List<String> = listOfNotNull(country),
    /** Every language the channel broadcasts in (e.g. "Telugu", "Hindi"). */
    val languages: List<String> = listOfNotNull(language),
    /** Backup streams for the same feed, tried in order when [streamUrl] is dead. */
    val alternates: List<StreamLink> = emptyList(),
) {
    /** All playable links for this channel, primary first. */
    val links: List<StreamLink> get() = listOf(StreamLink(streamUrl, userAgent, referrer)) + alternates

    /** The same channel (same [id], so favourites and history stay attached) playing [link]. */
    fun withLink(link: StreamLink): Channel = copy(streamUrl = link.url, userAgent = link.userAgent, referrer = link.referrer)

    val primaryCategory: String get() = categories.firstOrNull() ?: UNCATEGORISED
    val isGeoBlocked: Boolean get() = flags.any { it.equals("Geo-blocked", ignoreCase = true) }
    val isNot247: Boolean get() = flags.any { it.equals("Not 24/7", ignoreCase = true) }
    val protocol: StreamProtocol get() = StreamProtocol.of(streamUrl)

    /**
     * Who the channel is, independent of its address. iptv-org rotates stream URLs often (Zee's
     * links changed several times in October 2026), and [id] is a hash of the URL, so a saved
     * channel is matched back to the live catalog by this key: the `tvg-id` feed when present,
     * otherwise the normalised display name.
     */
    val identity: String get() = identityOf(tvgId, name)

    /** Same channel but from a different source of links ([Channel.flags] carries it). */
    val isExtra: Boolean get() = EXTRA_FLAG in flags
    /** Plays in YouTube's or Dailymotion's own embedded player, not Media3 (1.5.4). */
    val isEmbed: Boolean get() = protocol == StreamProtocol.EMBED

    companion object {
        const val UNCATEGORISED = "Undefined"
        const val EXTRA_FLAG = "Free-TV"

        fun identityOf(tvgId: String?, name: String): String =
            tvgId?.trim()?.lowercase()?.takeIf { tvgIdShape.matches(it) }?.let { "id:$it" }
                ?: "name:${normalizeName(name)}"

        private val decorations = Regex("""[\u24B6-\u24E9\u2460-\u24FF]|\((?:\d{3,4}p|[^)]*)\)|\[[^\]]*\]""")
        private val spaces = Regex("""\s+""")
        /** iptv-org ids look like "ZeeTV.in@SD"; Free-TV sometimes leaves markdown such as "[NDTV](https://…)". */
        private val tvgIdShape = Regex("""[a-z0-9][a-z0-9._+-]*\.[a-z]{2,3}(@[a-z0-9._+-]+)?""")

        /** "Zee TV HD (720p) [Geo-blocked]" -> "zee tv hd": drops quality, flags and Free-TV's Ⓢ/Ⓨ marks. */
        fun normalizeName(name: String): String =
            name.replace(decorations, " ").lowercase().replace(spaces, " ").trim()
    }
}

/** One stream address plus the request headers it needs. */
@Immutable
data class StreamLink(val url: String, val userAgent: String? = null, val referrer: String? = null)

/** Parsed playlist plus header-level metadata (e.g. the XMLTV EPG url). */
data class Playlist(
    val channels: List<Channel>,
    val epgUrl: String?,
    val skippedEntries: Int,
)
