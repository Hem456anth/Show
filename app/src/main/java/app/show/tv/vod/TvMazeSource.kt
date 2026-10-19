package app.show.tv.vod

import org.json.JSONObject

/**
 * TVmaze (api.tvmaze.com): TV metadata, used when no TMDB key is set. **Metadata only, no video.**
 * Free, no key, CC BY-SA 4.0 (attribution shown on the Movies screen), 20 calls / 10 s rate limit.
 */
class TvMazeSource(private val http: VodHttp, private val base: String = "https://api.tvmaze.com") : VodSource {
    override val provider = Provider.TVMAZE

    override suspend fun rows(): List<VodRow> {
        // "Streaming today": web/streaming premieres airing today, de-duplicated to shows.
        val arr = org.json.JSONArray(http.get("$base/schedule/web"))
        val shows = arr.objs().mapNotNull { it.optJSONObject("_embedded")?.optJSONObject("show") }
            .mapNotNull(::parse).distinctBy { it.providerId }.filter { it.posterUrl != null }.take(30)
        return listOf(VodRow("tvmaze_today", "New Episodes Today", shows, provider)).filter { it.items.isNotEmpty() }
    }

    override suspend fun search(query: String): List<VodTitle> {
        if (query.isBlank()) return emptyList()
        return org.json.JSONArray(http.get("$base/search/shows?q=${query.trim().enc()}")).objs()
            .mapNotNull { it.optJSONObject("show")?.let(::parse) }
    }

    internal fun parse(o: JSONObject): VodTitle? {
        val id = o.opt("id")?.toString() ?: return null
        val img = o.optJSONObject("image")
        return VodTitle(
            Provider.TVMAZE, id, VodKind.SHOW, o.str("name") ?: return null,
            year = o.str("premiered")?.take(4)?.toIntOrNull(),
            overview = o.str("summary")?.let(ArchiveSource::stripHtml),
            posterUrl = img?.str("medium")?.replace("http://", "https://"),
            backdropUrl = img?.str("original")?.replace("http://", "https://"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            rating = o.optJSONObject("rating")?.optDouble("average")?.takeIf { !it.isNaN() },
            runtimeMin = o.optInt("averageRuntime").takeIf { it > 0 },
            tmdbId = null,
        )
    }

    override suspend fun detail(title: VodTitle): VodDetail {
        val o = http.json("$base/shows/${title.providerId}?embed[]=cast&embed[]=seasons&embed[]=crew")
        val emb = o.optJSONObject("_embedded")
        val cast = emb?.optJSONArray("cast").objs().take(20).map {
            val p = it.getJSONObject("person"); val c = it.optJSONObject("character")
            VodPerson(p.getString("name"), c?.str("name"), p.optJSONObject("image")?.str("medium")?.replace("http://", "https://"), p.opt("id")?.toString())
        }
        val crew = emb?.optJSONArray("crew").objs().take(12).map {
            val p = it.getJSONObject("person")
            VodPerson(p.getString("name"), it.str("type"), p.optJSONObject("image")?.str("medium")?.replace("http://", "https://"), p.opt("id")?.toString())
        }
        val seasons = emb?.optJSONArray("seasons").objs().filter { it.optInt("number") > 0 }
            .map { VodSeason(it.getInt("number"), it.str("name") ?: "Season ${it.getInt("number")}", id = it.opt("id")?.toString(), episodeCount = it.optInt("episodeOrder").takeIf { n -> n > 0 }) }
        val networks = listOfNotNull(o.optJSONObject("network")?.str("name"), o.optJSONObject("webChannel")?.str("name"))
        return VodDetail(parse(o) ?: title, cast = cast, crew = crew, networks = networks, seasons = seasons,
            sourcePage = o.str("url"))
    }

    override suspend fun season(title: VodTitle, season: VodSeason): VodSeason {
        val id = season.id ?: return season
        val eps = org.json.JSONArray(http.get("$base/seasons/$id/episodes")).objs().filter { it.optInt("number") > 0 }.map {
            VodEpisode(it.opt("id")?.toString() ?: "", season.number, it.optInt("number"), it.str("name") ?: "Episode ${it.optInt("number")}",
                it.str("summary")?.let(ArchiveSource::stripHtml), it.optJSONObject("image")?.str("medium")?.replace("http://", "https://"),
                it.optInt("runtime").takeIf { r -> r > 0 }?.times(60))
        }
        return season.copy(episodes = eps, episodeCount = eps.size)
    }

    companion object { const val ATTRIBUTION = "TV data from TVmaze (CC BY-SA)." }
}
