package app.show.tv.data

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest

/**
 * Streaming Extended-M3U parser.
 *
 * Behaviour follows VLC's reference implementation (`modules/demux/playlist/m3u.c`):
 *  - `#EXTINF:<duration> <key="value" ...>,<title>`; attribute values may contain commas,
 *    so the title starts at the first comma *outside* quotes.
 *  - `tvg-*` and `group-title` attributes are read; the first occurrence wins (no overwrite).
 *  - `#EXTGRP:` sets the group when `group-title` is absent.
 *  - `#EXTVLCOPT:key=value` options apply to the next URI only.
 *  - Lines are processed one at a time, so memory stays O(channels), not O(file).
 *
 * iptv-org specifics on top of VLC: trailing `(1080p)` resolution and `[Geo-blocked]` /
 * `[Not 24/7]` flags are split out of the display name, and the `tvg-id` suffix
 * (`Name.cc@Feed`) yields a country.
 *
 * Duplicates are merged rather than repeated: entries whose URLs only differ by scheme case,
 * host case or a trailing slash are one stream ([urlKey]), and iptv-org's grouped indexes,
 * which list a stream once per group, collapse into one channel holding every group.
 * [grouping] says whether `group-title` is a category, a country or a language.
 */
object M3uParser {

    private const val MAX_LINE_LENGTH = 8 * 1024
    private val trailingTag = Regex("""\s*(\(([^()]*)\)|\[([^\[\]]*)])\s*$""")
    private val resolutionTag = Regex("""^\d{3,4}[pi]$""", RegexOption.IGNORE_CASE)

    fun parse(reader: Reader, grouping: PlaylistSource.Grouping = PlaylistSource.Grouping.CATEGORY): Playlist {
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        val channels = ArrayList<Channel>(4096)
        val indexByUrl = HashMap<String, Int>(8192)
        var epgUrl: String? = null
        var skipped = 0

        var pending: PendingEntry? = null
        var vlcOptions = HashMap<String, String>(2)
        var extGroup: String? = null
        var first = true

        buffered.lineSequence().forEach { raw ->
            var line = raw
            if (first) { line = line.removePrefix("\uFEFF"); first = false }
            line = line.trim()
            if (line.isEmpty()) return@forEach
            if (line.length > MAX_LINE_LENGTH) { skipped++; pending = null; return@forEach }

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substring(7))
                    epgUrl = attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: epgUrl
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (pending != null) skipped++ // previous EXTINF never got a URL
                    pending = parseExtInf(line.substring(8))
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substring(8).trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val eq = opt.indexOf('=')
                    if (eq > 0) vlcOptions[opt.substring(0, eq).trim().lowercase()] = opt.substring(eq + 1).trim()
                }
                line.startsWith("#") -> Unit // other directives / comments
                else -> {
                    val entry = pending
                    val url = line
                    if (entry != null && isAcceptableUrl(url)) {
                        val channel = entry.toChannel(url, extGroup, vlcOptions, grouping)
                        val key = urlKey(url)
                        val existing = indexByUrl[key]
                        if (existing == null) {
                            indexByUrl[key] = channels.size
                            channels += channel
                        } else {
                            // Grouped indexes list a channel once per group: merge them.
                            channels[existing] = mergeGroups(channels[existing], channel)
                        }
                    } else {
                        skipped++
                    }
                    pending = null
                    extGroup = null
                    if (vlcOptions.isNotEmpty()) vlcOptions = HashMap(2)
                }
            }
        }
        if (pending != null) skipped++
        channels.trimToSize()
        return Playlist(channels, epgUrl, skipped)
    }

    fun parse(text: String, grouping: PlaylistSource.Grouping = PlaylistSource.Grouping.CATEGORY): Playlist =
        parse(text.reader(), grouping)

    /** Union of two listings of the same stream: groups merged, first listing's metadata kept. */
    internal fun mergeGroups(a: Channel, b: Channel): Channel = a.copy(
        categories = mergeList(a.categories, b.categories, Channel.UNCATEGORISED),
        countries = mergeList(a.countries, b.countries, null),
        languages = mergeList(a.languages, b.languages, null),
        flags = a.flags + b.flags,
        logoUrl = a.logoUrl ?: b.logoUrl,
        tvgId = a.tvgId ?: b.tvgId,
    ).let { it.copy(country = it.countries.firstOrNull(), language = it.languages.firstOrNull()) }

    internal fun mergeList(a: List<String>, b: List<String>, placeholder: String?): List<String> {
        if (b.isEmpty() || a == b) return a
        val all = (a + b).distinct()
        return if (placeholder != null && all.size > 1) all - placeholder else all
    }

    /**
     * Identity of a stream address for de-duplication: scheme and host are case-insensitive and
     * a trailing slash is noise. Path and query are kept exactly (tokens are case-sensitive).
     */
    fun urlKey(url: String): String {
        val trimmed = url.trim()
        val sep = trimmed.indexOf("://")
        if (sep < 0) return trimmed
        val scheme = trimmed.substring(0, sep).lowercase()
        val rest = trimmed.substring(sep + 3)
        val slash = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val host = rest.substring(0, slash).lowercase()
        val tail = rest.substring(slash).let { if (it.length > 1 && it.endsWith('/')) it.dropLast(1) else if (it == "/") "" else it }
        return "$scheme://$host$tail"
    }

    /**
     * Network streaming schemes only. A custom playlist could otherwise point the player (or the
     * "open externally" hand-off) at file://, content://, intent:// or javascript: URIs, which
     * would let a hostile M3U read the app's own files or launch other apps (security fix, 1.4.0).
     */
    private val allowedSchemes = setOf(
        "http", "https", "rtmp", "rtmps", "rtmpe", "rtmpt", "rtsp", "rtsps", "rtp", "udp", "srt", "mms", "mmsh", "mmst", "mmsu",
    )

    internal fun isAcceptableUrl(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return scheme in allowedSchemes && url.length > scheme.length + 3 && url.none { it.isWhitespace() || it.code < 0x20 }
    }

    private class PendingEntry(val title: String, val attrs: Map<String, String>)

    private fun parseExtInf(body: String): PendingEntry {
        val split = indexOfUnquotedComma(body)
        val head = if (split >= 0) body.substring(0, split) else body
        val title = if (split >= 0) body.substring(split + 1).trim() else ""
        // head = "<duration> key="v" key2="v2"" -> drop the duration token.
        val attrs = parseAttributes(head.trimStart().dropWhile { !it.isWhitespace() })
        return PendingEntry(title, attrs)
    }

    internal fun indexOfUnquotedComma(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    /** Parses `key="value" key2=value2` pairs. Keys are lower-cased; first occurrence wins. */
    internal fun parseAttributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>(8)
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') { while (i < n && !s[i].isWhitespace()) i++; continue }
            val key = s.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < n && s[i] == '"') {
                val end = s.indexOf('"', i + 1).let { if (it < 0) n else it }
                value = s.substring(i + 1, end)
                i = (end + 1).coerceAtMost(n)
            } else {
                val start = i
                while (i < n && !s[i].isWhitespace()) i++
                value = s.substring(start, i)
            }
            if (key.isNotEmpty() && key !in out) out[key] = value.trim()
        }
        return out
    }

    private fun PendingEntry.toChannel(url: String, extGroup: String?, opts: Map<String, String>, grouping: PlaylistSource.Grouping): Channel {
        val rawName = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: title.ifBlank { url }
        val (name, resolution, flags) = splitTags(rawName)
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup
        val groups = group?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        val categories = (if (grouping == PlaylistSource.Grouping.CATEGORY) groups else emptyList()).ifEmpty { listOf(Channel.UNCATEGORISED) }
        val countries = when (grouping) {
            PlaylistSource.Grouping.COUNTRY -> groups.filterNot { it.equals(Channel.UNCATEGORISED, ignoreCase = true) }
            else -> listOfNotNull(tvgId?.let(::countryFromTvgId)?.let(Countries::name))
        }
        val languages = when (grouping) {
            PlaylistSource.Grouping.LANGUAGE -> groups.filterNot { it.equals(Channel.UNCATEGORISED, ignoreCase = true) }.map(::languageName)
            else -> attrs["tvg-language"]?.split(';', ',')?.map { languageName(it.trim()) }?.filter { it.isNotEmpty() }.orEmpty()
        }.distinct()
        return Channel(
            id = stableId(url),
            name = name,
            streamUrl = url,
            logoUrl = attrs["tvg-logo"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            categories = categories,
            tvgId = tvgId,
            language = languages.firstOrNull(),
            country = countries.firstOrNull(),
            resolution = resolution,
            flags = flags,
            userAgent = opts["http-user-agent"] ?: attrs["http-user-agent"],
            referrer = opts["http-referrer"] ?: opts["http-referer"] ?: attrs["http-referrer"] ?: attrs["http-referer"],
            countries = countries,
            languages = languages,
        )
    }

    /** "Oriya (macrolanguage)" -> "Oriya". */
    internal fun languageName(raw: String): String = raw.removeSuffix(" (macrolanguage)").trim()

    /** "Foo (720p) [Geo-blocked]" -> ("Foo", "720p", {"Geo-blocked"}). */
    internal fun splitTags(raw: String): Triple<String, String?, Set<String>> {
        var name = raw.trim()
        var resolution: String? = null
        val flags = LinkedHashSet<String>()
        while (true) {
            val m = trailingTag.find(name) ?: break
            val paren = m.groups[2]?.value
            val bracket = m.groups[3]?.value
            when {
                paren != null && resolutionTag.matches(paren.trim()) -> resolution = resolution ?: paren.trim()
                bracket != null && bracket.isNotBlank() -> flags += bracket.trim()
                else -> break // a genuine part of the name, e.g. "Channel (Spain)"
            }
            name = name.substring(0, m.range.first).trimEnd()
        }
        return Triple(name.ifEmpty { raw.trim() }, resolution, flags)
    }

    /** "BBCNews.uk@SD" -> "UK". */
    internal fun countryFromTvgId(tvgId: String): String? {
        val base = tvgId.substringBefore('@')
        val dot = base.lastIndexOf('.')
        if (dot < 0 || dot == base.length - 1) return null
        val cc = base.substring(dot + 1)
        return cc.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.uppercase()
    }

    internal fun stableId(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return buildString(16) { for (i in 0 until 8) append("%02x".format(digest[i])) }
    }
}
