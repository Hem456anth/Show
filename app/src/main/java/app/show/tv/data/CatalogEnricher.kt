package app.show.tv.data

/**
 * Joins one playlist with iptv-org's other indexes so every source can be filtered by category,
 * country and language, and removes duplicates.
 *
 *  - **Enrich:** each index lists the same streams grouped by one dimension. A stream is matched
 *    across files by [M3uParser.urlKey], and the groups it lacks are copied in. For example,
 *    countries/in.m3u says "News" and the language index adds "Telugu".
 *  - **De-duplicate:** two entries with the same `tvg-id` (channel + feed) and the same display
 *    name are one channel. The second address becomes a backup [Channel.alternates] link the
 *    player falls back to, instead of a second tile.
 */
object CatalogEnricher {

    private class Groups(val categories: List<String>, val countries: List<String>, val languages: List<String>)

    fun enrich(primary: List<Channel>, indexes: List<Pair<PlaylistSource.Grouping, Playlist>>): List<Channel> {
        if (indexes.isEmpty()) return primary
        val lookup = HashMap<String, Groups>(primary.size * 2)
        val wanted = primary.mapTo(HashSet(primary.size * 2)) { M3uParser.urlKey(it.streamUrl) }
        for ((grouping, playlist) in indexes) {
            for (ch in playlist.channels) {
                val key = M3uParser.urlKey(ch.streamUrl)
                if (key !in wanted) continue
                val prev = lookup[key]
                lookup[key] = when (grouping) {
                    PlaylistSource.Grouping.CATEGORY -> Groups(ch.categories.filter { it != Channel.UNCATEGORISED }, prev?.countries.orEmpty(), prev?.languages.orEmpty())
                    PlaylistSource.Grouping.COUNTRY -> Groups(prev?.categories.orEmpty(), ch.countries, prev?.languages.orEmpty())
                    PlaylistSource.Grouping.LANGUAGE -> Groups(prev?.categories.orEmpty(), prev?.countries.orEmpty(), ch.languages)
                }
            }
        }
        return primary.map { ch ->
            val g = lookup[M3uParser.urlKey(ch.streamUrl)] ?: return@map ch
            val categories = M3uParser.mergeList(ch.categories, g.categories, Channel.UNCATEGORISED)
            // An index's country list is authoritative; the tvg-id guess only fills gaps.
            val countries = if (g.countries.isNotEmpty()) (g.countries + ch.countries).distinct() else ch.countries
            val languages = (ch.languages + g.languages).distinct()
            if (categories == ch.categories && countries == ch.countries && languages == ch.languages) ch
            else ch.copy(categories = categories, countries = countries, languages = languages,
                country = countries.firstOrNull(), language = languages.firstOrNull())
        }
    }

    /** Collapses same-feed duplicates into one channel with backup links, keeping list order. */
    fun dedupe(channels: List<Channel>): List<Channel> {
        val firstByFeed = HashMap<String, Int>()
        val out = ArrayList<Channel>(channels.size)
        for (ch in channels) {
            val feed = ch.tvgId?.let { "${it.lowercase()}|${ch.name.lowercase()}" }
            val at = feed?.let(firstByFeed::get)
            if (at == null) {
                if (feed != null) firstByFeed[feed] = out.size
                out += ch
            } else {
                val keep = out[at]
                val link = StreamLink(ch.streamUrl, ch.userAgent, ch.referrer)
                if (keep.links.none { M3uParser.urlKey(it.url) == M3uParser.urlKey(link.url) }) {
                    out[at] = M3uParser.mergeGroups(keep, ch).copy(alternates = keep.alternates + link + ch.alternates)
                }
            }
        }
        return out
    }
}
