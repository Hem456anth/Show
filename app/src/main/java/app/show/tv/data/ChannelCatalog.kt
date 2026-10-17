package app.show.tv.data

import java.text.Normalizer
import java.util.Locale

/**
 * Immutable, pre-indexed view of a playlist used by every screen. Indexes are built once
 * per refresh on a background thread so browsing and search never scan raw strings twice.
 */
class ChannelCatalog(val channels: List<Channel>) {

    val byId: Map<String, Channel> = channels.associateBy { it.id }

    /** Categories ordered by size, "Undefined" last. */
    val categories: List<String> = channels.asSequence()
        .flatMap { it.categories.asSequence() }
        .groupingBy { it }.eachCount()
        .entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.key == Channel.UNCATEGORISED }.thenByDescending { it.value })
        .map { it.key }

    /** Countries ordered by channel count ("International" after real countries). */
    val countries: List<String> = ranked(channels.asSequence().flatMap { it.countries.asSequence() }.filter { it != Channel.UNCATEGORISED })
        .sortedBy { it in Countries.NON_COUNTRIES }

    /** Languages ordered by channel count. */
    val languages: List<String> = ranked(channels.asSequence().flatMap { it.languages.asSequence() }.filter { it != Channel.UNCATEGORISED })

    private val byCategory: Map<String, List<Channel>> = index { it.categories }
    private val byCountry: Map<String, List<Channel>> = index { it.countries }
    private val byLanguage: Map<String, List<Channel>> = index { it.languages }

    private inline fun index(crossinline keys: (Channel) -> List<String>): Map<String, List<Channel>> =
        buildMap<String, MutableList<Channel>> { channels.forEach { ch -> keys(ch).forEach { getOrPut(it) { mutableListOf() } += ch } } }

    private val searchKeys: Array<String> = Array(channels.size) { i ->
        val c = channels[i]
        normalize((listOf(c.name) + c.countries + c.languages + c.categories).joinToString(" "))
    }

    private val nameKeys: Array<String> = Array(channels.size) { normalize(channels[it].name) }

    fun inCategory(category: String?): List<Channel> =
        if (category == null) channels else byCategory[category].orEmpty()

    fun inCountry(country: String): List<Channel> = byCountry[country].orEmpty()
    fun inLanguage(language: String): List<Channel> = byLanguage[language].orEmpty()

    /** Live TV browse filter. Every argument is optional; null means "any". */
    fun browse(category: String?, country: String?, exclude: Set<String> = emptySet(), language: String? = null): List<Channel> {
        // Start from the smallest index that applies, then filter the rest.
        val base = when {
            country != null -> inCountry(country)
            language != null -> inLanguage(language)
            else -> inCategory(category)
        }
        if (category == null && country == null && language == null && exclude.isEmpty()) return base
        return base.filter {
            (category == null || category in it.categories) && (country == null || country in it.countries) &&
                (language == null || language in it.languages) && it.id !in exclude
        }
    }

    /**
     * Ranks: exact name > name prefix > word prefix > substring anywhere (name, country,
     * language or category). Every whitespace-separated term must match.
     */
    fun search(query: String, category: String? = null, limit: Int = 200): List<Channel> {
        val terms = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val q = terms.joinToString(" ")
        val scored = ArrayList<Pair<Int, Channel>>()
        for (i in channels.indices) {
            val ch = channels[i]
            if (category != null && category !in ch.categories) continue
            val key = searchKeys[i]
            if (terms.any { it !in key }) continue
            val name = nameKeys[i]
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                name.startsWith(terms.first()) || name.contains(" " + terms.first()) -> 2
                else -> 3
            }
            scored += score to ch
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.name.length })).take(limit).map { it.second }
    }

    /**
     * Picks a hero: prefers channels with artwork, HD and no geo/24-7 caveats, rotating
     * deterministically by [seed] (e.g. the day number) so the banner changes daily.
     */
    fun featured(seed: Long, count: Int = 5): List<Channel> {
        val pool = channels.filter { it.logoUrl != null && !it.isGeoBlocked && !it.isNot247 && it.protocol.playableInApp }
            .sortedByDescending { resolutionRank(it.resolution) }
            .take(400)
        if (pool.isEmpty()) return channels.take(count)
        val start = Math.floorMod(seed * 7919, pool.size.toLong()).toInt()
        return List(minOf(count, pool.size)) { pool[(start + it * 37) % pool.size] }.distinct()
    }

    /** Category rows for Home: the best-artwork channels first in every row. */
    fun rowFor(category: String, limit: Int = 24): List<Channel> = best(inCategory(category), limit)

    /**
     * "More like this" for a detail page: same category when the channel has a real one,
     * otherwise same language, then same country, never the channel itself.
     */
    fun related(ch: Channel, limit: Int = 16): List<Channel> {
        val pool = ch.categories.firstOrNull { it != Channel.UNCATEGORISED }?.let(::inCategory)
            ?: ch.languages.firstOrNull()?.let(::inLanguage)
            ?: ch.countries.firstOrNull()?.let(::inCountry)
            ?: emptyList()
        // Prefer neighbours that also share the language (e.g. Telugu news for a Telugu news channel).
        val lang = ch.languages.firstOrNull()
        val (same, other) = pool.filter { it.id != ch.id }.partition { lang != null && lang in it.languages }
        return (best(same, limit) + best(other, limit)).take(limit)
    }

    /** Title for [related]. */
    fun relatedTitle(ch: Channel): String =
        ch.categories.firstOrNull { it != Channel.UNCATEGORISED }?.let { "More in $it" }
            ?: ch.languages.firstOrNull()?.let { "More in $it" }
            ?: ch.countries.firstOrNull()?.let { "More from $it" } ?: "More channels"

    /** Home rows for a source grouped by [grouping]: its biggest groups, best artwork first. */
    fun rows(grouping: PlaylistSource.Grouping, count: Int = 14): List<Pair<String, List<Channel>>> {
        val (titles, members) = when (grouping) {
            PlaylistSource.Grouping.CATEGORY -> categories.filter { it != Channel.UNCATEGORISED } to ::inCategory
            PlaylistSource.Grouping.COUNTRY -> countries to ::inCountry
            PlaylistSource.Grouping.LANGUAGE -> languages to ::inLanguage
        }
        return titles.take(count).map { it to best(members(it), 24) }.filter { it.second.isNotEmpty() }
    }

    private fun best(list: List<Channel>, limit: Int): List<Channel> =
        list.sortedWith(
            compareBy<Channel> { it.logoUrl == null }.thenBy { it.isGeoBlocked }.thenByDescending { resolutionRank(it.resolution) },
        ).take(limit)

    private fun ranked(values: Sequence<String>): List<String> =
        values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    companion object {
        val EMPTY = ChannelCatalog(emptyList())

        fun resolutionRank(res: String?): Int = res?.filter(Char::isDigit)?.toIntOrNull() ?: 0

        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
