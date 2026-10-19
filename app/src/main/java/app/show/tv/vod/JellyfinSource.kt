package app.show.tv.vod

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** A signed-in Jellyfin session. The password is never stored, only the access token the server issued. */
data class JellyfinAccount(val server: String, val userId: String, val token: String, val userName: String, val deviceId: String)

/**
 * The viewer's own Jellyfin server (open source, GPL-2.0; https://jellyfin.org): their library,
 * played in full. Auth is `POST /Users/AuthenticateByName`; every call then carries the
 * `Authorization: MediaBrowser …, Token="…"` header. Watch progress is synced back to the server
 * (`/Sessions/Playing`, `/Progress`, `/Stopped`), so Continue Watching matches other Jellyfin clients.
 *
 * Verified against the public demo server (demo.jellyfin.org/stable, Jellyfin 12.2.0).
 */
class JellyfinSource(private val http: VodHttp, val account: JellyfinAccount) : VodSource {
    override val provider = Provider.JELLYFIN
    private val s = account.server.trimEnd('/')
    private val auth = mapOf("Authorization" to authHeader(account.deviceId, account.token))
    private val fields = "Overview,Genres,ProviderIds,ProductionYear,CommunityRating,RunTimeTicks"

    override suspend fun rows(): List<VodRow> = coroutineScope {
        val u = account.userId
        listOf(
            Triple("jf_resume", "Continue on Jellyfin", "/Users/$u/Items/Resume?MediaTypes=Video&Limit=20&Fields=$fields"),
            Triple("jf_movies", "Your Movies", "/Users/$u/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=40&Fields=$fields"),
            Triple("jf_shows", "Your Series", "/Users/$u/Items?IncludeItemTypes=Series&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=40&Fields=$fields"),
            Triple("jf_fav", "Jellyfin Favourites", "/Users/$u/Items?Filters=IsFavorite&IncludeItemTypes=Movie,Series&Recursive=true&Limit=40&Fields=$fields"),
        ).map { (id, title, path) ->
            async { runCatching { VodRow(id, title, items(http.json(s + path, auth)), provider) }.getOrNull() }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    override suspend fun search(query: String): List<VodTitle> {
        if (query.isBlank()) return emptyList()
        return items(http.json("$s/Users/${account.userId}/Items?SearchTerm=${query.trim().enc()}&IncludeItemTypes=Movie,Series&Recursive=true&Limit=40&Fields=$fields", auth))
    }

    /** Every TMDB id in the library -> its Jellyfin title, so TMDB info pages can offer "Play on Jellyfin". */
    suspend fun tmdbIndex(): Map<String, VodTitle> =
        items(http.json("$s/Users/${account.userId}/Items?IncludeItemTypes=Movie,Series&Recursive=true&Fields=$fields", auth))
            .filter { it.tmdbId != null }.associateBy { "${it.kind}:${it.tmdbId}" }

    private fun items(o: JSONObject): List<VodTitle> = o.optJSONArray("Items").objs().mapNotNull(::parse)

    internal fun parse(i: JSONObject): VodTitle? {
        val type = i.optString("Type")
        // Resume rows return episodes: show them as their series, carrying the episode in the id.
        val kind = when (type) { "Movie" -> VodKind.MOVIE; "Series", "Episode" -> VodKind.SHOW; else -> return null }
        val id = if (type == "Episode") i.str("SeriesId") ?: return null else i.str("Id") ?: return null
        val name = if (type == "Episode") i.str("SeriesName") ?: return null else i.str("Name") ?: return null
        val hasBackdrop = (i.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0
        return VodTitle(
            Provider.JELLYFIN, id, kind, name,
            year = i.optInt("ProductionYear").takeIf { it > 0 },
            overview = if (type == "Episode") null else i.str("Overview"),
            posterUrl = "$s/Items/$id/Images/Primary?maxHeight=480&quality=85",
            backdropUrl = if (hasBackdrop || type == "Episode") "$s/Items/$id/Images/Backdrop?maxWidth=1280&quality=80" else "$s/Items/$id/Images/Primary?maxHeight=720",
            genres = i.optJSONArray("Genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            rating = i.optDouble("CommunityRating").takeIf { !it.isNaN() },
            runtimeMin = (i.optLong("RunTimeTicks") / TICKS_PER_MS / 60_000).toInt().takeIf { it > 0 && type != "Episode" },
            tmdbId = i.optJSONObject("ProviderIds")?.str("Tmdb"),
        )
    }

    override suspend fun detail(title: VodTitle): VodDetail {
        val i = http.json("$s/Users/${account.userId}/Items/${title.providerId}?Fields=$fields,People,Studios,RemoteTrailers,Taglines", auth)
        val t = parse(i) ?: title
        val people = i.optJSONArray("People").objs()
        fun photo(p: JSONObject) = p.str("PrimaryImageTag")?.let { "$s/Items/${p.getString("Id")}/Images/Primary?maxHeight=300" }
        val cast = people.filter { it.optString("Type") == "Actor" }.take(20).map { VodPerson(it.getString("Name"), it.str("Role"), photo(it), it.str("Id")) }
        val crew = people.filter { it.optString("Type") in setOf("Director", "Writer", "Producer", "Composer") }.take(12)
            .map { VodPerson(it.getString("Name"), it.str("Type"), photo(it), it.str("Id")) }
        val trailer = i.optJSONArray("RemoteTrailers").objs().firstNotNullOfOrNull { youtubeId(it.str("Url")) }
        val studios = i.optJSONArray("Studios").objs().map { it.getString("Name") }
        val position = i.optJSONObject("UserData")?.optLong("PlaybackPositionTicks")?.div(TICKS_PER_MS)?.takeIf { it > 0 }
        val seasons = if (t.kind == VodKind.SHOW)
            http.json("$s/Shows/${title.providerId}/Seasons?userId=${account.userId}", auth).optJSONArray("Items").objs()
                .map { VodSeason(it.optInt("IndexNumber"), it.str("Name") ?: "Season ${it.optInt("IndexNumber")}", id = it.str("Id")) }
        else emptyList()
        return VodDetail(
            t, cast = cast, crew = crew,
            companies = if (t.kind == VodKind.MOVIE) studios else emptyList(),
            networks = if (t.kind == VodKind.SHOW) studios else emptyList(),
            trailerYoutubeId = trailer, seasons = seasons,
            sources = if (t.kind == VodKind.MOVIE) videos(title.providerId) else emptyList(),
            sourcePage = "$s/web/#/details?id=${title.providerId}",
            remotePositionMs = position,
        )
    }

    override suspend fun season(title: VodTitle, season: VodSeason): VodSeason {
        val sid = season.id ?: return season
        val eps = http.json("$s/Shows/${title.providerId}/Episodes?seasonId=$sid&userId=${account.userId}&Fields=Overview", auth)
            .optJSONArray("Items").objs().map {
                val id = it.getString("Id")
                VodEpisode(id, it.optInt("ParentIndexNumber", season.number), it.optInt("IndexNumber"), it.str("Name") ?: "Episode",
                    it.str("Overview"), "$s/Items/$id/Images/Primary?maxWidth=480", (it.optLong("RunTimeTicks") / TICKS_PER_MS / 1000).toInt().takeIf { r -> r > 0 },
                    videos(id))
            }
        return season.copy(episodes = eps, episodeCount = eps.size)
    }

    /**
     * Renditions: the original file (direct play, no server load) first, then server transcodes at
     * fixed bitrates for slow networks. The player falls back to the original if a transcode fails
     * (some servers, like the demo, disable transcoding).
     */
    fun videos(itemId: String): List<VodVideo> {
        val h = mapOf("Authorization" to authHeader(account.deviceId, account.token))
        val original = VodVideo("Original", "$s/Videos/$itemId/stream?static=true&MediaSourceId=$itemId", headers = h)
        val transcodes = listOf(1080 to 8_000_000, 720 to 4_000_000, 480 to 1_500_000).map { (height, bitrate) ->
            VodVideo("${height}p", "$s/Videos/$itemId/master.m3u8?MediaSourceId=$itemId&VideoCodec=h264&AudioCodec=aac" +
                "&MaxStreamingBitrate=$bitrate&MaxHeight=$height&DeviceId=${account.deviceId.enc()}&PlaySessionId=${account.deviceId.enc()}", height = height, headers = h)
        }
        return listOf(original) + transcodes
    }

    /** Progress sync (fire and forget; failures only cost the server-side resume point). */
    suspend fun report(itemId: String, positionMs: Long, event: String, paused: Boolean = false) {
        val path = when (event) { "start" -> "/Sessions/Playing"; "stop" -> "/Sessions/Playing/Stopped"; else -> "/Sessions/Playing/Progress" }
        val body = JSONObject().put("ItemId", itemId).put("MediaSourceId", itemId).put("PositionTicks", positionMs * TICKS_PER_MS)
            .put("IsPaused", paused).put("CanSeek", true).put("PlayMethod", "DirectPlay").toString()
        runCatching { http.post(s + path, body, auth) }
    }

    companion object {
        const val TICKS_PER_MS = 10_000L
        const val CLIENT_VERSION = "1.6.0"

        fun authHeader(deviceId: String, token: String? = null) =
            "MediaBrowser Client=\"Show\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"$CLIENT_VERSION\"" + (token?.let { ", Token=\"$it\"" } ?: "")

        /** Normalises what the viewer typed: https assumed when no scheme; only http(s); no credentials or fragments. */
        fun normaliseServer(raw: String): String? {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "https://$s"
            val u = runCatching { java.net.URI(s) }.getOrNull() ?: return null
            if (u.scheme !in setOf("http", "https") || u.host.isNullOrBlank() || u.userInfo != null || u.fragment != null || u.query != null) return null
            return s
        }

        suspend fun signIn(http: VodHttp, server: String, user: String, password: String, deviceId: String): JellyfinAccount {
            val s = normaliseServer(server) ?: throw IllegalArgumentException("Enter a server address like https://media.example.com")
            val body = JSONObject().put("Username", user).put("Pw", password).toString()
            val o = try {
                JSONObject(http.post("$s/Users/AuthenticateByName", body, mapOf("Authorization" to authHeader(deviceId))))
            } catch (e: VodHttp.HttpError) {
                throw IllegalStateException(if (e.code == 401) "Wrong username or password." else "The server answered ${e.code}.")
            }
            val token = o.str("AccessToken") ?: throw IllegalStateException("The server didn't return a session.")
            val u = o.getJSONObject("User")
            return JellyfinAccount(s, u.getString("Id"), token, u.optString("Name", user), deviceId)
        }
    }
}
