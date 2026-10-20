package app.show.tv.vod

import org.json.JSONArray
import org.json.JSONObject

/**
 * Movies & Series (1.6.0). Metadata providers and playback providers are kept apart on purpose:
 *
 *  - [Provider.ARCHIVE]  Internet Archive public-domain / Creative Commons films and TV: metadata AND full playback.
 *  - [Provider.JELLYFIN] the viewer's own Jellyfin server: metadata AND full playback (their library).
 *  - [Provider.TMDB]     The Movie Database: metadata, artwork, cast, trailers only. Never full-length playback.
 *  - [Provider.TVMAZE]   TVmaze: TV metadata only (used when no TMDB key is set). Never playback.
 *
 * [VodTitle.playable] is true only for titles whose provider actually serves the video.
 */
enum class Provider(val label: String, val streams: Boolean) {
    ARCHIVE("Internet Archive", true),
    JELLYFIN("Jellyfin", true),
    TMDB("TMDB", false),
    TVMAZE("TVmaze", false),
}

enum class VodKind { MOVIE, SHOW }

data class VodTitle(
    val provider: Provider,
    val providerId: String,
    val kind: VodKind,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtimeMin: Int? = null,
    val tmdbId: String? = null,
) {
    /** Stable across launches: "archive:his_girl_friday", "tmdb:movie:138", "jellyfin:c002…". */
    val key: String get() = when (provider) {
        Provider.TMDB, Provider.TVMAZE -> "${provider.name.lowercase()}:${kind.name.lowercase()}:$providerId"
        else -> "${provider.name.lowercase()}:$providerId"
    }
    val playable: Boolean get() = provider.streams
    val subtitle: String get() = listOfNotNull(year?.toString(), if (kind == VodKind.SHOW) "Series" else "Movie", genres.firstOrNull()).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("p", provider.name).put("id", providerId).put("k", kind.name).put("t", title)
        .putOpt("y", year).putOpt("o", overview?.take(600)).putOpt("poster", posterUrl).putOpt("bd", backdropUrl)
        .put("g", JSONArray(genres)).putOpt("r", rating).putOpt("rt", runtimeMin).putOpt("tmdb", tmdbId)

    companion object {
        fun fromJson(o: JSONObject): VodTitle? = runCatching {
            VodTitle(
                Provider.valueOf(o.getString("p")), o.getString("id"), VodKind.valueOf(o.getString("k")), o.getString("t"),
                o.optInt("y").takeIf { it > 0 }, o.optString("o").ifEmpty { null }, o.optString("poster").ifEmpty { null },
                o.optString("bd").ifEmpty { null },
                o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optDouble("r").takeIf { !it.isNaN() }, o.optInt("rt").takeIf { it > 0 }, o.optString("tmdb").ifEmpty { null },
            )
        }.getOrNull()
    }
}

data class VodPerson(val name: String, val role: String?, val photoUrl: String?, val id: String? = null)

/** One playable rendition. [height] null = original/unknown. [subtitles] are side-loaded text tracks (Jellyfin external subtitles). */
data class VodVideo(
    val label: String,
    val url: String,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<VodSubtitle> = emptyList(),
)

/** A side-loaded subtitle file (1.8.0). [mimeType] is a Media3 MimeTypes text type, e.g. text/vtt. */
data class VodSubtitle(val url: String, val language: String?, val label: String, val mimeType: String = "text/vtt")

/** A skippable span (1.8.0), from Jellyfin media segments: intro, recap, outro, preview. */
data class VodSegment(val type: Type, val startMs: Long, val endMs: Long) {
    enum class Type(val action: String) { INTRO("Skip intro"), RECAP("Skip recap"), OUTRO("Skip credits"), PREVIEW("Skip preview") }
    fun contains(positionMs: Long) = positionMs in startMs until (endMs - 1_000).coerceAtLeast(startMs + 1)
}

/** A TMDB collection a movie belongs to (1.8.0), e.g. "The Lord of the Rings Collection". */
data class VodCollectionRef(val id: String, val name: String, val posterUrl: String? = null, val backdropUrl: String? = null)

/** One page of a paged listing (TMDB discover). */
data class VodPage(val items: List<VodTitle>, val page: Int = 1, val totalPages: Int = 1) {
    val hasMore: Boolean get() = page < totalPages
}

data class VodEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val runtimeSec: Int? = null,
    val sources: List<VodVideo> = emptyList(),
) {
    val code: String get() = "S$season:E$number"
}

data class VodSeason(val number: Int, val name: String, val id: String? = null, val episodes: List<VodEpisode>? = null, val episodeCount: Int? = null)

data class VodDetail(
    val title: VodTitle,
    val cast: List<VodPerson> = emptyList(),
    val crew: List<VodPerson> = emptyList(),
    val companies: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val trailerYoutubeId: String? = null,
    val recommendations: List<VodTitle> = emptyList(),
    val seasons: List<VodSeason> = emptyList(),
    /** Movie renditions, best first. Empty for shows (episodes carry their own) and info-only titles. */
    val sources: List<VodVideo> = emptyList(),
    val license: String? = null,
    val sourcePage: String? = null,
    /** Server-side resume position (Jellyfin), ms. */
    val remotePositionMs: Long? = null,
    /** TMDB collection (franchise) this movie belongs to, if any. */
    val collection: VodCollectionRef? = null,
    /** TMDB ids of [companies] and [networks] by name, so they open as browse pages without another request. */
    val orgIds: Map<String, String> = emptyMap(),
)

data class VodRow(val id: String, val title: String, val items: List<VodTitle>, val provider: Provider)

/** A My List shortcut that is not a title: a company, a network or a person. */
data class VodShortcut(val type: Type, val name: String, val provider: Provider, val id: String?) {
    enum class Type { COMPANY, NETWORK, PERSON, COLLECTION }
    val key get() = "${type.name}:${provider.name}:${id ?: name}"
    fun toJson() = JSONObject().put("type", type.name).put("name", name).put("p", provider.name).putOpt("id", id)
    companion object {
        fun fromJson(o: JSONObject) = runCatching {
            VodShortcut(Type.valueOf(o.getString("type")), o.getString("name"), Provider.valueOf(o.getString("p")), o.optString("id").ifEmpty { null })
        }.getOrNull()
    }
}

/** YouTube video id from a watch/embed/short URL, or null. */
fun youtubeId(url: String?): String? {
    val u = url ?: return null
    val m = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""").find(u) ?: return null
    return m.groupValues[1]
}

interface VodSource {
    val provider: Provider
    suspend fun rows(): List<VodRow>
    suspend fun search(query: String): List<VodTitle>
    suspend fun detail(title: VodTitle): VodDetail
    /** Loads a season's episodes when [VodDetail.seasons] came without them. */
    suspend fun season(title: VodTitle, season: VodSeason): VodSeason = season
    /** Titles for a My List shortcut (a company, network, person or collection). */
    suspend fun browse(shortcut: VodShortcut): List<VodTitle> = browsePage(shortcut, 1).items
    /** A page of [browse]; providers with paging (TMDB discover) override this. */
    suspend fun browsePage(shortcut: VodShortcut, page: Int): VodPage = VodPage(emptyList())
}
