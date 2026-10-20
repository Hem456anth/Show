package app.show.tv.vod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.show.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Trakt OAuth tokens. Times are seconds since the epoch, as Trakt sends them. */
data class TraktTokens(val access: String, val refresh: String, val createdAtSec: Long, val expiresInSec: Long) {
    val expiresAtMs: Long get() = (createdAtSec + expiresInSec) * 1000
    /** Refresh a day early so a token never expires mid-playback. */
    fun needsRefresh(nowMs: Long) = nowMs >= expiresAtMs - REFRESH_MARGIN_MS

    fun toJson(): String = JSONObject().put("a", access).put("r", refresh).put("c", createdAtSec).put("e", expiresInSec).toString()

    companion object {
        const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L
        fun fromJson(s: String?): TraktTokens? = runCatching {
            val o = JSONObject(s!!); TraktTokens(o.getString("a"), o.getString("r"), o.getLong("c"), o.getLong("e"))
        }.getOrNull()
        internal fun fromOAuth(o: JSONObject, nowMs: Long) = TraktTokens(
            o.getString("access_token"), o.getString("refresh_token"),
            o.optLong("created_at", nowMs / 1000).takeIf { it > 0 } ?: (nowMs / 1000), o.optLong("expires_in", 7_776_000),
        )
    }
}

/** Step one of the device flow: show [userCode] and [verificationUrl] to the viewer. */
data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSec: Int, val intervalSec: Int)

/** What one poll of `/oauth/device/token` said (Trakt documents each status code). */
sealed class TraktPoll {
    data object Pending : TraktPoll()      // 400
    data object SlowDown : TraktPoll()     // 429
    data object Invalid : TraktPoll()      // 404
    data object AlreadyUsed : TraktPoll()  // 409
    data object Expired : TraktPoll()      // 410
    data object Denied : TraktPoll()       // 418
    data class Success(val tokens: TraktTokens) : TraktPoll()
}

/** A movie or an episode to scrobble or mark watched, identified by its TMDB id (Trakt matches on it). */
data class TraktItem(val kind: VodKind, val tmdbId: Long, val title: String, val year: Int?, val season: Int? = null, val episode: Int? = null) {
    val isEpisode: Boolean get() = kind == VodKind.SHOW && season != null && episode != null

    internal fun json(): JSONObject {
        val ids = JSONObject().put("tmdb", tmdbId)
        val media = JSONObject().put("title", title).putOpt("year", year).put("ids", ids)
        return if (isEpisode) JSONObject().put("show", media).put("episode", JSONObject().put("season", season).put("number", episode))
        else JSONObject().put("movie", media)
    }

    companion object {
        /** Null when the title has no TMDB id (Internet Archive items): Trakt can't match it reliably. */
        fun from(t: VodTitle, ep: VodEpisode? = null): TraktItem? {
            val id = t.tmdbId?.toLongOrNull() ?: return null
            if (t.kind == VodKind.SHOW && ep == null) return null
            return TraktItem(t.kind, id, t.title, t.year, ep?.season, ep?.number)
        }
    }
}

/**
 * Trakt (api.trakt.tv) sync through the official OAuth **device-code** flow (1.8.0): sign-in with a code
 * on trakt.tv/activate, token refresh, sign-out (revoke), watchlist <-> My List, history and scrobbling.
 * Trakt only syncs what the viewer watched; it never supplies video.
 *
 * The client id/secret come from the build (TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET) or the viewer's own app
 * in Movies settings ([VodSettings.traktApp]); with neither, Trakt stays hidden. Tokens go to a
 * [SecretStore] (Android Keystore encrypted), never to logs.
 */
class TraktClient(
    private val http: VodHttp,
    private val app: () -> TraktApp?,
    private val store: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val base: String = "https://api.trakt.tv",
) {
    private val refreshLock = Mutex()
    private val _signedIn = MutableStateFlow(tokens() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val _user = MutableStateFlow(store.get(K_USER))
    val user: StateFlow<String?> = _user.asStateFlow()

    val configured: Boolean get() = app() != null

    fun tokens(): TraktTokens? = TraktTokens.fromJson(store.get(K_TOKENS))

    private fun save(t: TraktTokens?) { store.put(K_TOKENS, t?.toJson()); _signedIn.value = t != null }

    private fun requireApp() = app() ?: throw IllegalStateException("Trakt isn't set up in this build. Add a Trakt app in Movies settings.")

    internal fun headers(access: String? = null): Map<String, String> = buildMap {
        put("trakt-api-version", "2")
        put("trakt-api-key", requireApp().clientId)
        access?.let { put("Authorization", "Bearer $it") }
    }

    // ------------------------------------------------------------- OAuth device flow

    suspend fun deviceCode(): TraktDeviceCode {
        val a = requireApp()
        val o = JSONObject(http.post("$base/oauth/device/code", JSONObject().put("client_id", a.clientId).toString(), headers()))
        return TraktDeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"),
            o.optInt("expires_in", 600), o.optInt("interval", 5).coerceAtLeast(1))
    }

    internal fun deviceTokenBody(code: String): String {
        val a = requireApp()
        return JSONObject().put("code", code).put("client_id", a.clientId).put("client_secret", a.clientSecret).toString()
    }

    suspend fun poll(code: TraktDeviceCode): TraktPoll = try {
        val o = JSONObject(http.post("$base/oauth/device/token", deviceTokenBody(code.deviceCode), headers()))
        TraktTokens.fromOAuth(o, clock()).also(::save).let { TraktPoll.Success(it) }
    } catch (e: VodHttp.HttpError) {
        when (e.code) {
            400 -> TraktPoll.Pending; 429 -> TraktPoll.SlowDown; 404 -> TraktPoll.Invalid
            409 -> TraktPoll.AlreadyUsed; 410 -> TraktPoll.Expired; 418 -> TraktPoll.Denied
            else -> throw e
        }
    }

    internal fun refreshBody(refresh: String): String {
        val a = requireApp()
        return JSONObject().put("refresh_token", refresh).put("client_id", a.clientId).put("client_secret", a.clientSecret)
            .put("redirect_uri", REDIRECT_OOB).put("grant_type", "refresh_token").toString()
    }

    /** Exchanges the refresh token. A refused refresh (401/400) signs out locally; a network error keeps the old tokens. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        val t = tokens() ?: return false
        try {
            val o = JSONObject(http.post("$base/oauth/token", refreshBody(t.refresh), headers()))
            save(TraktTokens.fromOAuth(o, clock())); true
        } catch (e: VodHttp.HttpError) {
            if (e.code == 400 || e.code == 401) signOutLocally()
            false
        }
    }

    /** Revokes the token on Trakt (best effort) and forgets it here. */
    suspend fun signOut() {
        val t = tokens()
        val a = app()
        if (t != null && a != null) runCatching {
            http.post("$base/oauth/revoke", JSONObject().put("token", t.access).put("client_id", a.clientId).put("client_secret", a.clientSecret).toString(), headers())
        }
        signOutLocally()
    }

    private fun signOutLocally() { save(null); store.put(K_USER, null); _user.value = null }

    /** Runs [block] with a fresh token; on a 401 refreshes once and retries. */
    private suspend fun <T> authed(block: suspend (Map<String, String>) -> T): T {
        var t = tokens() ?: throw IllegalStateException("Connect Trakt in Movies settings first.")
        if (t.needsRefresh(clock()) && refresh()) t = tokens() ?: t
        return try { block(headers(t.access)) } catch (e: VodHttp.HttpError) {
            if (e.code != 401 || !refresh()) throw e
            block(headers(tokens()!!.access))
        }
    }

    suspend fun loadUser(): String? = runCatching {
        authed { h -> JSONObject(http.get("$base/users/settings", h)).optJSONObject("user")?.str("username") }
    }.getOrNull()?.also { store.put(K_USER, it); _user.value = it }

    // ------------------------------------------------------------- Sync

    /** The viewer's Trakt watchlist as TMDB titles (items without a TMDB id are skipped). */
    suspend fun watchlist(): List<VodTitle> = authed { h ->
        parseList(JSONArray(http.get("$base/sync/watchlist/movies", h)), "movie", VodKind.MOVIE) +
            parseList(JSONArray(http.get("$base/sync/watchlist/shows", h)), "show", VodKind.SHOW)
    }

    /** Recently watched on Trakt, newest first, one entry per title. */
    suspend fun history(limit: Int = 40): List<VodTitle> = authed { h ->
        JSONArray(http.get("$base/sync/history?limit=$limit", h)).objs().mapNotNull { o ->
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.let { media(it, VodKind.MOVIE) }
                "episode" -> o.optJSONObject("show")?.let { media(it, VodKind.SHOW) }
                else -> null
            }
        }.distinctBy { it.key }
    }

    /** Adds titles that have TMDB ids to the Trakt watchlist. Returns how many were sent. */
    suspend fun addToWatchlist(titles: List<VodTitle>): Int {
        val body = idsBody(titles)
        val n = body.optJSONArray("movies").objs().size + body.optJSONArray("shows").objs().size
        if (n == 0) return 0
        authed { h -> http.post("$base/sync/watchlist", body.toString(), h) }
        return n
    }

    suspend fun removeFromWatchlist(titles: List<VodTitle>) {
        val body = idsBody(titles)
        if (body.length() == 0) return
        authed { h -> http.post("$base/sync/watchlist/remove", body.toString(), h) }
    }

    suspend fun addToHistory(item: TraktItem) = authed { h -> http.post("$base/sync/history", historyBody(item, clock()), h) }

    // ------------------------------------------------------------- Scrobble

    enum class Scrobble(val path: String) { START("start"), PAUSE("pause"), STOP("stop") }

    /** Trakt scrobble: start/pause/stop with progress 0..100. Stop at 80 % or more marks it watched on Trakt. */
    suspend fun scrobble(action: Scrobble, item: TraktItem, progressPercent: Float) {
        authed { h -> http.post("$base/scrobble/${action.path}", scrobbleBody(item, progressPercent), h) }
    }

    internal fun scrobbleBody(item: TraktItem, progressPercent: Float): String =
        item.json().put("progress", "%.2f".format(java.util.Locale.US, progressPercent.coerceIn(0f, 100f)).toDouble())
            .put("app_version", BuildConfig.VERSION_NAME).toString()

    companion object {
        const val REDIRECT_OOB = "urn:ietf:wg:oauth:2.0:oob"
        const val ATTRIBUTION = "Watch history sync by Trakt (trakt.tv). Not endorsed by Trakt."
        private const val K_TOKENS = "trakt_tokens"
        private const val K_USER = "trakt_user"

        internal fun media(o: JSONObject, kind: VodKind): VodTitle? {
            val tmdb = o.optJSONObject("ids")?.opt("tmdb")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            return VodTitle(Provider.TMDB, tmdb, kind, o.str("title") ?: return null, o.optInt("year").takeIf { it > 0 }, tmdbId = tmdb)
        }

        internal fun parseList(arr: JSONArray, field: String, kind: VodKind) = arr.objs().mapNotNull { it.optJSONObject(field)?.let { m -> media(m, kind) } }

        internal fun idsBody(titles: List<VodTitle>): JSONObject {
            fun arr(kind: VodKind) = JSONArray(titles.filter { it.kind == kind }.mapNotNull { it.tmdbId?.toLongOrNull() }.distinct()
                .map { JSONObject().put("ids", JSONObject().put("tmdb", it)) })
            return JSONObject().apply {
                arr(VodKind.MOVIE).takeIf { it.length() > 0 }?.let { put("movies", it) }
                arr(VodKind.SHOW).takeIf { it.length() > 0 }?.let { put("shows", it) }
            }
        }

        internal fun historyBody(item: TraktItem, nowMs: Long): String {
            val at = java.time.Instant.ofEpochMilli(nowMs).toString()
            val ids = JSONObject().put("tmdb", item.tmdbId)
            return if (item.isEpisode) JSONObject().put("shows", JSONArray().put(JSONObject().put("ids", ids).put("seasons", JSONArray().put(
                JSONObject().put("number", item.season).put("episodes", JSONArray().put(JSONObject().put("number", item.episode).put("watched_at", at))))))).toString()
            else JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids).put("watched_at", at))).toString()
        }
    }
}
