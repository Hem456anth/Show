package app.show.tv.data

import org.json.JSONObject

/**
 * Independently curated playlists merged into the iptv-org catalog, gated by a verification
 * allowlist. Only one source qualifies today:
 *
 *  - **Free-TV/IPTV** (github.com/Free-TV/IPTV, ~21k stars, updated daily): only channels that are
 *    officially free to everyone in their country (DVB-T/S free-to-air or the broadcaster's own
 *    free stream), mainstream, no adult or paid channels, one URL per channel.
 *
 * "Never add unverified": a Free-TV entry is used only when its exact URL is in
 * `assets/verified_extra.json`, written by `tools/verify_extra_sources.py`, which fetched the
 * playlist, followed the top variant and downloaded media bytes for every candidate. YouTube
 * pages (Ⓨ) and VOD sections are never candidates.
 *
 * Merging removes duplicates: an entry for a channel the catalog already has (same `tvg-id`
 * base or same normalised name in the same country) becomes a backup link of that channel;
 * only channels the catalog lacks are added, tagged [Channel.EXTRA_FLAG].
 */
object ExtraSources {
    const val FREETV_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    const val FREETV_KEY = "freetv"

    /** URL keys ([M3uParser.urlKey]) that passed verification, per source key. */
    data class Allowlist(val verifiedAt: String?, val urls: Map<String, Set<String>>) {
        fun allows(source: String, url: String) = urls[source]?.contains(M3uParser.urlKey(url)) == true
        val isEmpty get() = urls.values.all { it.isEmpty() }
        companion object { val EMPTY = Allowlist(null, emptyMap()) }
    }

    fun parseAllowlist(json: String): Allowlist = runCatching {
        val root = JSONObject(json)
        val sources = root.optJSONObject("sources") ?: return Allowlist.EMPTY
        val map = HashMap<String, Set<String>>()
        for (key in sources.keys()) {
            val arr = sources.getJSONObject(key).optJSONArray("urls") ?: continue
            map[key] = (0 until arr.length()).mapTo(HashSet(arr.length() * 2)) { M3uParser.urlKey(arr.getString(it)) }
        }
        Allowlist(root.optString("verified_at").ifEmpty { null }, map)
    }.getOrDefault(Allowlist.EMPTY)

    private val nonPlayableHosts = listOf("youtube.com", "youtu.be", "dailymotion.com", "twitch.tv", "facebook.com")

    /** Free-TV's group-title is a country name or a theme ("News"); returns the category to file it under. */
    internal fun categoryFor(group: String, countries: List<String>): String = when {
        group.equals("News", true) -> "News"
        group.equals("Sports", true) || group.equals("Sport", true) -> "Sports"
        group.equals("Music", true) -> "Music"
        group.equals("Kids", true) -> "Kids"
        else -> "General" // a country section: iptv-org files mainstream national channels as General
    }

    /**
     * Merges [extra] (parsed with CATEGORY grouping) into [base]. [countryFilter] limits *new*
     * channels to one country (the India source); backup links are added regardless.
     */
    fun merge(base: List<Channel>, extra: List<Channel>, allow: Allowlist, source: String = FREETV_KEY, countryFilter: String? = null): List<Channel> {
        val usable = extra.filter { ch ->
            allow.allows(source, ch.streamUrl) && ch.protocol.playableInApp &&
                nonPlayableHosts.none { ch.streamUrl.contains(it, ignoreCase = true) } &&
                ch.categories.none { it.startsWith("VOD", ignoreCase = true) }
        }
        if (usable.isEmpty()) return base
        val out = base.toMutableList()
        val byId = HashMap<String, Int>()
        val byName = HashMap<String, Int>()
        val knownUrls = HashSet<String>()
        out.forEachIndexed { i, ch ->
            ch.tvgId?.let { tvgBase(it) }?.let { byId.putIfAbsent(it, i) }
            byName.putIfAbsent(nameKey(ch), i)
            ch.links.forEach { knownUrls += M3uParser.urlKey(it.url) }
        }
        for (raw in usable) {
            val key = M3uParser.urlKey(raw.streamUrl)
            if (key in knownUrls) continue // the exact stream is already in the catalog
            knownUrls += key
            val at = raw.tvgId?.let { tvgBase(it) }?.let(byId::get) ?: byName[nameKey(raw)]
            if (at != null) {
                val keep = out[at]
                out[at] = keep.copy(alternates = keep.alternates + StreamLink(raw.streamUrl, raw.userAgent, raw.referrer))
                continue
            }
            if (countryFilter != null && raw.countries.none { it.equals(countryFilter, true) }) continue
            val group = raw.categories.firstOrNull().orEmpty()
            val added = raw.copy(categories = listOf(categoryFor(group, raw.countries)), flags = raw.flags + Channel.EXTRA_FLAG)
            out += added
            added.tvgId?.let { tvgBase(it) }?.let { byId.putIfAbsent(it, out.lastIndex) }
            byName.putIfAbsent(nameKey(added), out.lastIndex)
        }
        return out
    }

    /** "NDTVIndia.in@SD" -> "ndtvindia.in"; ids with spaces (Free-TV markdown leftovers) are ignored. */
    internal fun tvgBase(id: String): String? = id.substringBefore('@').trim().lowercase().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) && it.contains('.') }

    /** Name plus country, so "News 24" in India and in Albania stay different channels. */
    internal fun nameKey(ch: Channel): String =
        Channel.normalizeName(ch.name).removeSuffix(" hd").trim() + "|" + (ch.countries.firstOrNull()?.lowercase() ?: "")
}
