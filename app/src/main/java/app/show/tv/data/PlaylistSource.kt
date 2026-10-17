package app.show.tv.data

/**
 * The official iptv-org playlists Show can load. Each one lists the same ~11k streams, grouped
 * differently: [grouping] says what `group-title` means in that file, so the parser files it as
 * a category, a country or a language. Whatever a source doesn't group by is filled in from the
 * other indexes by [CatalogEnricher], so every source gets category, country and language filters.
 */
enum class PlaylistSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val grouping: Grouping,
) {
    CATEGORIES("categories", "By category", "Every channel, grouped by genre", "https://iptv-org.github.io/iptv/index.category.m3u", Grouping.CATEGORY),
    INDIA("india", "India", "Indian channels only", "https://iptv-org.github.io/iptv/countries/in.m3u", Grouping.CATEGORY),
    GLOBAL("global", "Global", "Every channel in one list", "https://iptv-org.github.io/iptv/index.m3u", Grouping.CATEGORY),
    COUNTRIES("countries", "By country", "Every channel, grouped by country", "https://iptv-org.github.io/iptv/index.country.m3u", Grouping.COUNTRY),
    LANGUAGES("languages", "By language", "Every channel, grouped by language", "https://iptv-org.github.io/iptv/index.language.m3u", Grouping.LANGUAGE),
    ;

    enum class Grouping { CATEGORY, COUNTRY, LANGUAGE }

    /** The other indexes whose groups this source lacks, used to enrich it. */
    val enrichFrom: List<PlaylistSource>
        get() = when (grouping) {
            Grouping.CATEGORY -> listOf(COUNTRIES, LANGUAGES)
            Grouping.COUNTRY -> listOf(LANGUAGES, CATEGORIES)
            Grouping.LANGUAGE -> listOf(COUNTRIES, CATEGORIES)
        }

    companion object {
        val DEFAULT = CATEGORIES
        fun byId(id: String?): PlaylistSource? = entries.firstOrNull { it.id == id }
        fun byUrl(url: String?): PlaylistSource? = entries.firstOrNull { it.url == url }
        /** A custom playlist is enriched with countries and languages like a category index. */
        val CUSTOM_ENRICH: List<PlaylistSource> = listOf(COUNTRIES, LANGUAGES)
    }
}
