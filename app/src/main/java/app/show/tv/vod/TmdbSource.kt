package app.show.tv.vod

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Movie Database (api.themoviedb.org/3): metadata, artwork, cast & crew, trailers, collections,
 * companies and networks. **Metadata only: TMDB serves no full-length video.** Titles from here are
 * info pages with a trailer; they become playable only when the viewer's Jellyfin library holds the
 * same TMDB id (matched in [VodRepository]).
 *
 * Needs a free API key (v3 key, or a v4 read access token starting "eyJ"), entered in Movies settings
 * or baked in with the TMDB_API_KEY Gradle property. TMDB terms require the attribution Show shows
 * on the Movies screen ("This product uses the TMDB API but is not endorsed or certified by TMDB").
 */
class TmdbSource(private val http: VodHttp, private val key: String, private val base: String = "https://api.themoviedb.org/3") : VodSource {
    override val provider = Provider.TMDB
    private val bearer = key.startsWith("eyJ")
    private val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()

    private fun url(path: String, vararg q: Pair<String, String>): String {
        val params = (q.toList() + ("language" to "en-US") + (if (bearer) emptyList() else listOf("api_key" to key)))
        return "$base$path?" + params.joinToString("&") { "${it.first}=${it.second.enc()}" }
    }

    private suspend fun get(path: String, vararg q: Pair<String, String>) = http.json(url(path, *q), headers)

    private val shelves = listOf(
        Triple("tmdb_trending", "Trending This Week", "/trending/all/week"),
        Triple("tmdb_now", "Now Playing in Theatres", "/movie/now_playing"),
        Triple("tmdb_popular_movies", "Popular Movies", "/movie/popular"),
        Triple("tmdb_popular_tv", "Popular Series", "/tv/popular"),
        Triple("tmdb_top_movies", "Top Rated Movies", "/movie/top_rated"),
        Triple("tmdb_top_tv", "Top Rated Series", "/tv/top_rated"),
        Triple("tmdb_airing", "On the Air", "/tv/on_the_air"),
    )

    override suspend fun rows(): List<VodRow> = coroutineScope {
        shelves.map { (id, title, path) ->
            async { runCatching { VodRow(id, title, list(get(path).optJSONArray("results"), kindFor(path)), provider) }.getOrNull() }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    private fun kindFor(path: String): VodKind? = when {
        path.startsWith("/movie") -> VodKind.MOVIE
        path.startsWith("/tv") -> VodKind.SHOW
        else -> null
    }

    override suspend fun search(query: String): List<VodTitle> =
        if (query.isBlank()) emptyList() else list(get("/search/multi", "query" to query.trim(), "include_adult" to "false").optJSONArray("results"), null)

    internal fun list(arr: JSONArray?, kind: VodKind?): List<VodTitle> =
        (0 until (arr?.length() ?: 0)).mapNotNull { parse(arr!!.getJSONObject(it), kind) }

    internal fun parse(o: JSONObject, kind: VodKind?): VodTitle? {
        if (o.optBoolean("adult")) return null
        val k = kind ?: when (o.optString("media_type")) { "movie" -> VodKind.MOVIE; "tv" -> VodKind.SHOW; else -> return null }
        val id = o.opt("id")?.toString() ?: return null
        val name = o.str("title") ?: o.str("name") ?: return null
        return VodTitle(
            Provider.TMDB, id, k, name,
            year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)?.toIntOrNull(),
            overview = o.str("overview"),
            posterUrl = o.str("poster_path")?.let { "$IMG/w342$it" },
            backdropUrl = o.str("backdrop_path")?.let { "$IMG/w780$it" },
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
                ?: o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { GENRES[a.getInt(it)] } } ?: emptyList(),
            rating = o.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            runtimeMin = o.optInt("runtime").takeIf { it > 0 } ?: o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 },
            tmdbId = id,
        )
    }

    override suspend fun detail(title: VodTitle): VodDetail {
        val path = if (title.kind == VodKind.MOVIE) "/movie/${title.providerId}" else "/tv/${title.providerId}"
        val o = get(path, "append_to_response" to "credits,videos,recommendations")
        val t = parse(o, title.kind) ?: title
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast").objs().take(20).map {
            VodPerson(it.getString("name"), it.str("character"), it.str("profile_path")?.let { p -> "$IMG/w185$p" }, it.opt("id")?.toString())
        }
        val crew = credits?.optJSONArray("crew").objs()
            .filter { it.optString("job") in setOf("Director", "Screenplay", "Writer", "Producer", "Original Music Composer", "Director of Photography") }
            .distinctBy { it.optString("name") + it.optString("job") }.take(12)
            .map { VodPerson(it.getString("name"), it.str("job"), it.str("profile_path")?.let { p -> "$IMG/w185$p" }, it.opt("id")?.toString()) }
        val creators = o.optJSONArray("created_by").objs().map { VodPerson(it.getString("name"), "Creator", it.str("profile_path")?.let { p -> "$IMG/w185$p" }, it.opt("id")?.toString()) }
        val trailer = o.optJSONObject("videos")?.optJSONArray("results").objs()
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(compareByDescending<JSONObject> { it.optBoolean("official") }.thenByDescending { it.optString("type") == "Trailer" })
            .firstOrNull()?.str("key")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val seasons = o.optJSONArray("seasons").objs().filter { it.optInt("season_number") > 0 }
            .map { VodSeason(it.getInt("season_number"), it.str("name") ?: "Season ${it.getInt("season_number")}", episodeCount = it.optInt("episode_count")) }
        return VodDetail(
            t, cast = cast, crew = creators + crew,
            companies = o.optJSONArray("production_companies").objs().map { it.getString("name") },
            networks = o.optJSONArray("networks").objs().map { it.getString("name") },
            trailerYoutubeId = trailer,
            recommendations = list(o.optJSONObject("recommendations")?.optJSONArray("results"), null).take(20),
            seasons = seasons,
            sourcePage = "https://www.themoviedb.org/${if (title.kind == VodKind.MOVIE) "movie" else "tv"}/${title.providerId}",
        )
    }

    override suspend fun season(title: VodTitle, season: VodSeason): VodSeason {
        val o = get("/tv/${title.providerId}/season/${season.number}")
        val eps = o.optJSONArray("episodes").objs().map {
            VodEpisode(it.opt("id")?.toString() ?: "", season.number, it.optInt("episode_number"), it.str("name") ?: "Episode ${it.optInt("episode_number")}",
                it.str("overview"), it.str("still_path")?.let { p -> "$IMG/w300$p" }, it.optInt("runtime").takeIf { r -> r > 0 }?.times(60))
        }
        return season.copy(episodes = eps, episodeCount = eps.size)
    }

    /** Company / network / person shortcuts from My List. */
    override suspend fun browse(shortcut: VodShortcut): List<VodTitle> {
        val id = shortcut.id ?: return emptyList()
        return when (shortcut.type) {
            VodShortcut.Type.COMPANY -> list(get("/discover/movie", "with_companies" to id, "sort_by" to "popularity.desc").optJSONArray("results"), VodKind.MOVIE)
            VodShortcut.Type.NETWORK -> list(get("/discover/tv", "with_networks" to id, "sort_by" to "popularity.desc").optJSONArray("results"), VodKind.SHOW)
            VodShortcut.Type.PERSON -> list(get("/person/$id/combined_credits").optJSONArray("cast"), null)
                .distinctBy { it.key }.sortedByDescending { it.rating ?: 0.0 }.take(40)
        }
    }

    /** Ids for company/network names on a detail page, so they can be saved as shortcuts. */
    suspend fun companyIds(title: VodTitle): Map<String, String> {
        val path = if (title.kind == VodKind.MOVIE) "/movie/${title.providerId}" else "/tv/${title.providerId}"
        val o = get(path)
        return (o.optJSONArray("production_companies").objs() + o.optJSONArray("networks").objs())
            .associate { it.getString("name") to (it.opt("id")?.toString() ?: "") }
    }

    companion object {
        const val IMG = "https://image.tmdb.org/t/p"
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
        val GENRES = mapOf(
            28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
            10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
            878 to "Science Fiction", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western", 10759 to "Action & Adventure",
            10762 to "Kids", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
        )
    }
}

internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
