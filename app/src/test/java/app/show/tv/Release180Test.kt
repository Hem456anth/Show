package app.show.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import app.show.tv.vod.*
import kotlinx.coroutines.cancel
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1.8.0: TMDB collections, studio/network discover, audio/subtitle track logic, picture modes, Trakt
 * device flow / refresh / scrobble / sync request building, Jellyfin subtitles + segments, new settings.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class Release180Test {
    private val server = MockWebServer()
    private val http = VodHttp(OkHttpClient())
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val vms = mutableListOf<VodViewModel>()
    /** View models outlive a test unless their scope is cancelled; cancel them so no collector leaks into later tests. */
    private fun <T : VodViewModel> T.tracked(): T = also { vms += it }
    @After fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        runCatching { server.shutdown() }
    }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun serve(routes: (RecordedRequest, String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes(request, request.body.clone().readUtf8())
            }
        }
        server.start()
    }
    private fun prefs(tag: String) = ctx.getSharedPreferences("r180_${tag}_${System.nanoTime()}", Context.MODE_PRIVATE)
    private fun ok(body: String) = MockResponse().setBody(body)
    private val KEY = "0123456789abcdef0123456789abcdef"

    // ---------------------------------------------------------------- 1. Collections

    @Test fun `tmdb detail exposes the collection and org ids, and collection parts come back in release order`() = runBlocking {
        serve { r, _ ->
            when {
                r.path!!.startsWith("/movie/120") -> ok("""{"id":120,"title":"The Fellowship of the Ring","release_date":"2001-12-18",
                    "belongs_to_collection":{"id":119,"name":"The Lord of the Rings Collection","poster_path":"/c.jpg","backdrop_path":"/cb.jpg"},
                    "production_companies":[{"id":12,"name":"New Line Cinema"},{"id":11,"name":"WingNut Films"}]}""")
                r.path!!.startsWith("/collection/119") -> ok("""{"id":119,"name":"The Lord of the Rings Collection","parts":[
                    {"id":122,"title":"The Return of the King","release_date":"2003-12-01"},
                    {"id":999,"title":"Untitled Sequel","release_date":""},
                    {"id":120,"title":"The Fellowship of the Ring","release_date":"2001-12-18"},
                    {"id":121,"title":"The Two Towers","release_date":"2002-12-18"},
                    {"id":5,"title":"Adult","adult":true,"release_date":"2000-01-01"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val src = TmdbSource(http, KEY, base())
        val d = src.detail(VodTitle(Provider.TMDB, "120", VodKind.MOVIE, "Fellowship", tmdbId = "120"))
        assertEquals(VodCollectionRef("119", "The Lord of the Rings Collection", "${TmdbSource.IMG}/w342/c.jpg", "${TmdbSource.IMG}/w780/cb.jpg"), d.collection)
        assertEquals(mapOf("New Line Cinema" to "12", "WingNut Films" to "11"), d.orgIds)
        val parts = src.collection("119")
        assertEquals(listOf("The Fellowship of the Ring", "The Two Towers", "The Return of the King", "Untitled Sequel"), parts.map { it.title })
        assertTrue(parts.all { it.kind == VodKind.MOVIE && !it.playable })
        assertEquals(parts, src.browsePage(VodShortcut(VodShortcut.Type.COLLECTION, "LOTR", Provider.TMDB, "119"), 1).items)
        assertNull(src.collectionRef(JSONObject("""{"id":null}""")))
        assertNull(src.collectionRef(null))
    }

    @Test fun `view model loads a movie's collection into its detail page`() = runBlocking {
        val movie = VodTitle(Provider.TMDB, "120", VodKind.MOVIE, "Fellowship", tmdbId = "120")
        val parts = listOf(movie, VodTitle(Provider.TMDB, "121", VodKind.MOVIE, "Two Towers", tmdbId = "121"))
        val fake = object : VodSource {
            override val provider = Provider.TMDB
            override suspend fun rows() = emptyList<VodRow>()
            override suspend fun search(query: String) = emptyList<VodTitle>()
            override suspend fun detail(title: VodTitle) = VodDetail(title, collection = VodCollectionRef("119", "LOTR"))
            override suspend fun browsePage(shortcut: VodShortcut, page: Int) = VodPage(parts)
        }
        // repo.collection() needs a real TmdbSource, so route the fake through browsePage for the shortcut and check the state.
        val s = VodSettings(prefs("vmc"))
        val vm = VodViewModel(VodRepository(http, { s.config.value }) { _, _ -> listOf(fake) }, VodLibrary(prefs("vmcl")), s).tracked()
        vm.open(movie)
        waitFor { vm.detail.value?.loading == false }
        assertEquals("LOTR", vm.detail.value!!.detail!!.collection!!.name)
        vm.openShortcut(VodShortcut(VodShortcut.Type.COLLECTION, "LOTR", Provider.TMDB, "119"))
        waitFor { vm.shortcut.value?.loading == false }
        assertEquals(listOf("Fellowship", "Two Towers"), vm.shortcut.value!!.items.map { it.title })
    }

    // ---------------------------------------------------------------- 2. Studios & networks discover

    @Test fun `company and network discover pages are paged and send the right filters`() = runBlocking {
        serve { r, _ ->
            val page = Regex("page=(\\d+)").find(r.path!!)!!.groupValues[1].toInt()
            when {
                r.path!!.startsWith("/discover/movie") -> ok("""{"page":$page,"total_pages":3,"results":[{"id":${page * 10},"title":"Movie $page"},{"id":${page * 10 + 1},"title":"Adult","adult":true}]}""")
                r.path!!.startsWith("/discover/tv") -> ok("""{"page":$page,"total_pages":900,"results":[{"id":${page * 10},"name":"Show $page"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val src = TmdbSource(http, KEY, base())
        val marvel = VodStudios.shortcut(VodStudios.studios.first { it.name == "Marvel Studios" }, network = false)
        val p2 = src.browsePage(marvel, 2)
        assertEquals(listOf("Movie 2"), p2.items.map { it.title })
        assertEquals(2 to 3, p2.page to p2.totalPages)
        assertTrue(p2.hasMore)
        val req = requests.last().path!!
        assertTrue(req, req.contains("with_companies=420") && req.contains("page=2") && req.contains("include_adult=false") && req.contains("sort_by=popularity.desc"))
        val netflix = VodStudios.shortcut(VodStudios.networks.first { it.name == "Netflix" }, network = true)
        val n = src.browsePage(netflix, 1)
        assertEquals(VodKind.SHOW, n.items.single().kind)
        assertEquals("TMDB caps discover at 500 pages", TmdbSource.MAX_PAGE, n.totalPages)
        assertTrue(requests.last().path!!.contains("with_networks=213"))
    }

    @Test fun `curated studios and networks are unique, https and need a TMDB key to open`() = runBlocking {
        val all = VodStudios.studios + VodStudios.networks
        assertTrue(VodStudios.studios.size >= 15 && VodStudios.networks.size >= 15)
        assertEquals(VodStudios.studios.size, VodStudios.studios.map { it.id }.toSet().size)
        assertEquals(VodStudios.networks.size, VodStudios.networks.map { it.id }.toSet().size)
        assertTrue(all.all { it.logoUrl.startsWith("https://image.tmdb.org/t/p/") && it.logoPath.matches(Regex("/[A-Za-z0-9]+\\.(png|svg)")) })
        // No key: TVmaze replaces TMDB, so the studio page explains instead of showing an empty grid.
        val s = VodSettings(prefs("nokey"))
        val repo = VodRepository(http, { s.config.value })
        val e = runCatching { repo.browsePage(VodStudios.shortcut(VodStudios.studios.first(), false), 1) }.exceptionOrNull()
        assertEquals(VodRepository.NEEDS_TMDB, e!!.message)
        assertFalse(repo.hasTmdb)
        val vm = VodViewModel(repo, VodLibrary(prefs("nokeylib")), s).tracked()
        vm.openShortcut(VodStudios.shortcut(VodStudios.studios.first(), false))
        waitFor { vm.shortcut.value?.loading == false }
        assertEquals(VodRepository.NEEDS_TMDB, vm.shortcut.value!!.error)
    }

    @Test fun `view model pages a studio grid, dedupes and stops at the last page`() = runBlocking {
        var calls = 0
        val fake = object : VodSource {
            override val provider = Provider.TMDB
            override suspend fun rows() = emptyList<VodRow>()
            override suspend fun search(query: String) = emptyList<VodTitle>()
            override suspend fun detail(title: VodTitle) = VodDetail(title)
            override suspend fun browsePage(shortcut: VodShortcut, page: Int): VodPage {
                calls++
                val items = (0 until 3).map { VodTitle(Provider.TMDB, "${page}_$it", VodKind.MOVIE, "T$page$it") } + VodTitle(Provider.TMDB, "dup", VodKind.MOVIE, "Dup")
                return VodPage(items, page, 2)
            }
        }
        val s = VodSettings(prefs("page"))
        val vm = VodViewModel(VodRepository(http, { s.config.value }) { _, _ -> listOf(fake) }, VodLibrary(prefs("pagel")), s).tracked()
        vm.openShortcut(VodShortcut(VodShortcut.Type.COMPANY, "A24", Provider.TMDB, "41077"))
        waitFor { vm.shortcut.value?.loading == false }
        assertTrue(vm.shortcut.value!!.hasMore)
        vm.loadMoreShortcut()
        waitFor { vm.shortcut.value?.page == 2 && vm.shortcut.value?.loadingMore == false }
        assertEquals(7, vm.shortcut.value!!.items.size)
        assertFalse(vm.shortcut.value!!.hasMore)
        vm.loadMoreShortcut(); Thread.sleep(100)
        assertEquals("no request past the last page", 2, calls)
    }

    // ---------------------------------------------------------------- 3. Track selection

    private fun audio(lang: String?, ch: Int, label: String? = null, role: Int = 0) =
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(lang).setChannelCount(ch).setLabel(label).setRoleFlags(role).build()
    private fun text(lang: String?, forced: Boolean = false) =
        Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(lang).setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0).build()
    private fun group(f: Format, selected: Boolean, supported: Boolean = true) =
        Tracks.Group(TrackGroup(f), false, intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(selected))

    private val tracks = Tracks(ImmutableList.of(
        group(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(), true),
        group(audio("eng", 2), true),
        group(audio("hin", 6), false),
        group(audio("eng", 2, role = C.ROLE_FLAG_COMMENTARY), false),
        group(audio("tel", 2), false, supported = false),
        group(text("en"), false),
        group(text("en", forced = true), false),
    ))

    @Test fun `audio and subtitle menus list supported tracks with readable labels and an Off entry`() {
        val a = VodTracks.audio(tracks)
        assertEquals(listOf("English · Stereo", "Hindi · 5.1", "English · Stereo · Commentary"), a.map { it.label })
        assertEquals(listOf(true, false, false), a.map { it.selected })
        assertEquals(listOf("en", "hi", "en"), a.map { it.language })
        val subs = VodTracks.subtitles(tracks)
        assertEquals("Off", subs.first().label)
        assertTrue("nothing selected -> Off is checked", subs.first().selected && subs.first().isOff)
        assertEquals(listOf("English", "English · Forced"), subs.drop(1).map { it.label })
        assertEquals(1, VodTracks.subtitles(Tracks.EMPTY).size)
        // Two identical labels get numbered.
        val dup = Tracks(ImmutableList.of(group(audio("eng", 2), true), group(audio("eng", 2), false)))
        assertEquals(listOf("English · Stereo 1", "English · Stereo 2"), VodTracks.audio(dup).map { it.label })
    }

    @Test fun `picking a track overrides it, Off disables text, and preferences seed the next video`() {
        val base = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val hindi = VodTracks.audio(tracks)[1]
        val p = VodTracks.select(base, tracks, C.TRACK_TYPE_AUDIO, hindi)
        val override = p.overrides.values.single()
        assertEquals("Media3 normalises hin -> hi", "hi", override.mediaTrackGroup.getFormat(0).language)
        assertEquals("hi", VodTracks.rememberedLanguage(hindi))
        val off = VodTracks.subtitles(tracks).first()
        val p2 = VodTracks.select(p, tracks, C.TRACK_TYPE_TEXT, off)
        assertTrue(C.TRACK_TYPE_TEXT in p2.disabledTrackTypes)
        assertEquals(VodTracks.OFF, VodTracks.rememberedLanguage(off))
        val english = VodTracks.subtitles(tracks)[1]
        assertFalse(C.TRACK_TYPE_TEXT in VodTracks.select(p2, tracks, C.TRACK_TYPE_TEXT, english).disabledTrackTypes)

        val seeded = VodTracks.preferred(base, "hi", "en")
        assertEquals(listOf("hi"), seeded.preferredAudioLanguages)
        assertEquals(listOf("en"), seeded.preferredTextLanguages)
        assertTrue(C.TRACK_TYPE_TEXT in VodTracks.preferred(base, null, VodTracks.OFF).disabledTrackTypes)
        assertEquals(base, VodTracks.preferred(base, null, null))
    }

    @Test fun `language codes normalise from ISO 639-2 and stay null when undetermined`() {
        assertEquals("en", VodTracks.normLang("eng"))
        assertEquals("fr", VodTracks.normLang("fre"))
        assertEquals("de", VodTracks.normLang("ger"))
        assertEquals("te", VodTracks.normLang("tel"))
        assertEquals("pt", VodTracks.normLang("pt-BR"))
        assertNull(VodTracks.normLang("und"))
        assertNull(VodTracks.normLang(""))
        assertEquals("Telugu", VodTracks.languageName("tel"))
        assertNull(VodTracks.languageName(null))
    }

    // ---------------------------------------------------------------- 4. Resize, speed, segments, settings

    @Test fun `resize mode cycles Fit Fill Zoom and persists, separately from the provider config`() = runBlocking {
        assertEquals(listOf(ResizeMode.FILL, ResizeMode.ZOOM, ResizeMode.FIT), listOf(ResizeMode.FIT.next(), ResizeMode.FILL.next(), ResizeMode.ZOOM.next()))
        assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, ResizeMode.ZOOM.media3)
        assertEquals(ResizeMode.FIT, ResizeMode.parse("bogus"))
        val p = prefs("resize")
        val s = VodSettings(p)
        assertEquals(ResizeMode.FIT, s.player.value.resizeMode)
        val configBefore = s.config.value
        s.setResizeMode(ResizeMode.ZOOM); s.setAudioLang("hi"); s.setSubtitleLang(VodTracks.OFF)
        assertSame("player prefs never rebuild providers or reload the catalog", configBefore, s.config.value)
        val again = VodSettings(p)
        assertEquals(VodPlayerPrefs("hi", VodTracks.OFF, ResizeMode.ZOOM), again.player.value)
        again.setSubtitleLang(null)
        assertNull(VodSettings(p).player.value.subtitleLang)
    }

    @Test fun `speeds, skip segments and their labels`() {
        assertEquals(listOf("0.5×", "0.75×", "Normal", "1.25×", "1.5×", "2×"), VodSpeeds.all.map(VodSpeeds::label))
        val segs = JellyfinSource.parseSegments(JSONObject("""{"Items":[
            {"Type":"Outro","StartTicks":25000000000,"EndTicks":26000000000},
            {"Type":"Intro","StartTicks":300000000,"EndTicks":900000000},
            {"Type":"Commercial","StartTicks":0,"EndTicks":100000000},
            {"Type":"Recap","StartTicks":0,"EndTicks":10000000}]}"""))
        assertEquals(listOf(VodSegment.Type.INTRO, VodSegment.Type.OUTRO), segs.map { it.type })
        val intro = segs.first()
        assertEquals(30_000L to 90_000L, intro.startMs to intro.endMs)
        assertTrue(intro.contains(45_000)); assertFalse(intro.contains(89_500)); assertFalse(intro.contains(10_000))
        assertEquals("Skip intro", intro.type.action)
        assertTrue(JellyfinSource.parseSegments(JSONObject("{}")).isEmpty())
    }

    @Test fun `trakt app settings validate, fall back to the build and hide Trakt without a client id`() {
        val id = "a".repeat(64); val secret = "b".repeat(64)
        val s = VodSettings(prefs("trakt"))
        assertNull(s.traktApp.value)
        assertNotNull(s.setTraktApp("short", secret))
        assertNull(s.setTraktApp(id, secret))
        assertEquals(TraktApp(id, secret), s.traktApp.value)
        assertTrue(s.hasOwnTraktApp)
        assertNull(s.setTraktApp("", ""))
        assertNull(s.traktApp.value)
        val built = VodSettings(prefs("trakt2"), null, TraktApp(id, secret))
        assertEquals(id, built.traktApp.value!!.clientId)
        assertNull("blank build values = hidden", VodSettings(prefs("trakt3"), null, TraktApp("", "")).traktApp.value)
        val client = TraktClient(http, { null }, MemorySecretStore())
        assertFalse(client.configured)
    }

    // ---------------------------------------------------------------- 5. Trakt

    private val app = TraktApp("c".repeat(64), "d".repeat(64))

    @Test fun `trakt device flow sends the documented requests and maps every poll status`() = runBlocking {
        val statuses = ArrayDeque(listOf(400, 429, 200))
        serve { r, body ->
            when (r.path) {
                "/oauth/device/code" -> ok("""{"device_code":"dev123","user_code":"5055CC52","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""")
                "/oauth/device/token" -> when (val c = statuses.removeFirst()) {
                    200 -> ok("""{"access_token":"acc1","refresh_token":"ref1","created_at":1700000000,"expires_in":7776000,"token_type":"bearer"}""")
                    else -> MockResponse().setResponseCode(c)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val store = MemorySecretStore()
        val t = TraktClient(http, { app }, store, { 1_700_000_100_000L }, base())
        val code = t.deviceCode()
        assertEquals("5055CC52" to 5, code.userCode to code.intervalSec)
        val first = requests.first()
        assertEquals("2", first.getHeader("trakt-api-version"))
        assertEquals(app.clientId, first.getHeader("trakt-api-key"))
        assertNull(first.getHeader("Authorization"))
        assertEquals(app.clientId, JSONObject(first.body.readUtf8()).getString("client_id"))
        assertEquals(TraktPoll.Pending, t.poll(code))
        assertEquals(TraktPoll.SlowDown, t.poll(code))
        val ok = t.poll(code) as TraktPoll.Success
        assertEquals("acc1", ok.tokens.access)
        val tokenBody = JSONObject(requests.last().body.readUtf8())
        assertEquals(listOf("dev123", app.clientId, app.clientSecret), listOf(tokenBody.getString("code"), tokenBody.getString("client_id"), tokenBody.getString("client_secret")))
        assertTrue(t.signedIn.value)
        assertEquals("acc1", TraktClient(http, { app }, store).tokens()!!.access)
        assertFalse("secret never stored in plain token json", store.get("trakt_tokens")!!.contains(app.clientSecret))
    }

    @Test fun `trakt poll maps expired, denied, invalid and used codes`() = runBlocking {
        val statuses = ArrayDeque(listOf(410, 418, 404, 409))
        serve { _, _ -> MockResponse().setResponseCode(statuses.removeFirst()) }
        val t = TraktClient(http, { app }, MemorySecretStore(), base = base())
        val code = TraktDeviceCode("d", "u", "https://trakt.tv/activate", 600, 5)
        assertEquals(listOf(TraktPoll.Expired, TraktPoll.Denied, TraktPoll.Invalid, TraktPoll.AlreadyUsed), (1..4).map { t.poll(code) })
        assertFalse(t.signedIn.value)
    }

    @Test fun `trakt refreshes an expiring token first, retries once on 401, and signs out when refresh is refused`() = runBlocking {
        var tokenCalls = 0
        var refuseRefresh = false
        serve { r, body ->
            when (r.path) {
                "/oauth/token" -> {
                    tokenCalls++
                    if (refuseRefresh) MockResponse().setResponseCode(401)
                    else {
                        val o = JSONObject(body)
                        assertEquals("refresh_token", o.getString("grant_type")); assertEquals(TraktClient.REDIRECT_OOB, o.getString("redirect_uri"))
                        ok("""{"access_token":"acc$tokenCalls","refresh_token":"ref$tokenCalls","created_at":2000000000,"expires_in":7776000}""")
                    }
                }
                "/sync/watchlist/movies" -> if (r.getHeader("Authorization") == "Bearer stale") MockResponse().setResponseCode(401)
                    else ok("""[{"movie":{"title":"Dune","year":2021,"ids":{"trakt":1,"tmdb":438631}}},{"movie":{"title":"No TMDB","ids":{"trakt":2}}}]""")
                "/sync/watchlist/shows" -> ok("""[{"show":{"title":"Severance","year":2022,"ids":{"tmdb":95396}}}]""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val store = MemorySecretStore()
        var now = 1_000_000_000_000L
        val t = TraktClient(http, { app }, store, { now }, base())
        // 1) Fresh token rejected (401) -> refresh -> retry succeeds.
        store.put("trakt_tokens", TraktTokens("stale", "r0", now / 1000, 7_776_000).toJson())
        val list = t.watchlist()
        assertEquals(listOf("tmdb:movie:438631", "tmdb:show:95396"), list.map { it.key })
        assertEquals(1, tokenCalls)
        assertEquals("r0", JSONObject(requests.first { it.path == "/oauth/token" }.body.readUtf8()).getString("refresh_token"))
        // 2) Token inside the refresh margin is refreshed before the call.
        now = (2_000_000_000L + 7_776_000L) * 1000 - 1000
        t.watchlist()
        assertEquals(2, tokenCalls)
        // 3) Refresh refused -> signed out locally.
        refuseRefresh = true
        now += TraktTokens.REFRESH_MARGIN_MS * 200
        assertFalse(t.refresh())
        assertFalse(t.signedIn.value)
        assertNull(t.tokens())
    }

    @Test fun `trakt scrobble, history and watchlist bodies match the API`() = runBlocking {
        serve { _, _ -> MockResponse().setResponseCode(201).setBody("{}") }
        val store = MemorySecretStore()
        store.put("trakt_tokens", TraktTokens("acc", "ref", System.currentTimeMillis() / 1000, 7_776_000).toJson())
        val t = TraktClient(http, { app }, store, base = base())
        val movie = VodTitle(Provider.JELLYFIN, "m1", VodKind.MOVIE, "Dracula", 1931, tmdbId = "138")
        val item = TraktItem.from(movie)!!
        t.scrobble(TraktClient.Scrobble.START, item, 12.3456f)
        val r = requests.last()
        assertEquals("/scrobble/start", r.path)
        assertEquals("Bearer acc", r.getHeader("Authorization"))
        val o = JSONObject(r.body.readUtf8())
        assertEquals(138L, o.getJSONObject("movie").getJSONObject("ids").getLong("tmdb"))
        assertEquals(12.35, o.getDouble("progress"), 0.001)
        assertEquals(BuildConfig.VERSION_NAME, o.getString("app_version"))

        val show = VodTitle(Provider.JELLYFIN, "s1", VodKind.SHOW, "Pioneer One", 2010, tmdbId = "30982")
        val ep = TraktItem.from(show, VodEpisode("e", 1, 3, "Three"))!!
        val body = JSONObject(t.scrobbleBody(ep, 150f))
        assertEquals(30982L, body.getJSONObject("show").getJSONObject("ids").getLong("tmdb"))
        assertEquals(1 to 3, body.getJSONObject("episode").getInt("season") to body.getJSONObject("episode").getInt("number"))
        assertEquals("progress is clamped", 100.0, body.getDouble("progress"), 0.0)
        assertNull("no TMDB id -> no scrobble", TraktItem.from(VodTitle(Provider.ARCHIVE, "x", VodKind.MOVIE, "X")))
        assertNull("a series needs an episode", TraktItem.from(show))

        t.scrobble(TraktClient.Scrobble.STOP, item, 95f)
        assertEquals("/scrobble/stop", requests.last().path)
        t.addToHistory(ep)
        val h = JSONObject(requests.last().body.readUtf8())
        assertEquals(3, h.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getInt("number"))
        assertEquals(2, t.addToWatchlist(listOf(movie, show, VodTitle(Provider.ARCHIVE, "x", VodKind.MOVIE, "X"))))
        val w = JSONObject(requests.last().body.readUtf8())
        assertEquals("/sync/watchlist", requests.last().path)
        assertEquals(1 to 1, w.getJSONArray("movies").length() to w.getJSONArray("shows").length())
        assertEquals("nothing sendable -> no request", 0, t.addToWatchlist(listOf(VodTitle(Provider.ARCHIVE, "x", VodKind.MOVIE, "X"))))
        t.signOut()
        assertEquals("/oauth/revoke", requests.last().path)
        assertEquals("acc", JSONObject(requests.last().body.readUtf8()).getString("token"))
        assertFalse(t.signedIn.value)
    }

    @Test fun `view model trakt sync imports the watchlist into my list and pushes my list back`() = runBlocking {
        serve { r, _ ->
            when (r.path) {
                "/sync/watchlist/movies" -> ok("""[{"movie":{"title":"Dune","year":2021,"ids":{"tmdb":438631}}}]""")
                "/sync/watchlist/shows" -> ok("[]")
                "/sync/watchlist" -> MockResponse().setResponseCode(201).setBody("{}")
                else -> if (r.path!!.startsWith("/sync/history")) ok("""[{"type":"movie","movie":{"title":"Heat","year":1995,"ids":{"tmdb":949}}},{"type":"episode","show":{"title":"Severance","ids":{"tmdb":95396}},"episode":{"season":1,"number":1}}]""")
                    else MockResponse().setResponseCode(404)
            }
        }
        val store = MemorySecretStore()
        store.put("trakt_tokens", TraktTokens("acc", "ref", System.currentTimeMillis() / 1000, 7_776_000).toJson())
        val trakt = TraktClient(http, { app }, store, base = base())
        val s = VodSettings(prefs("sync"))
        val lib = VodLibrary(prefs("syncl"))
        lib.toggle(VodTitle(Provider.TMDB, "138", VodKind.MOVIE, "Dracula", tmdbId = "138"))
        val vm = VodViewModel(VodRepository(http, { s.config.value }) { _, _ -> emptyList() }, lib, s, trakt).tracked()
        vm.syncTrakt()
        waitFor { vm.traktUi.value is TraktUi.Message }
        assertEquals("Synced: 1 new in My List, 2 on your Trakt watchlist.", (vm.traktUi.value as TraktUi.Message).text)
        assertEquals(listOf("Dracula", "Dune"), lib.myList.value.map { it.title })
        assertEquals(listOf("Heat", "Severance"), vm.traktHistory.value.map { it.title })
    }

    // ---------------------------------------------------------------- 6. Jellyfin subtitles, segments, played

    @Test fun `jellyfin external subtitles are side-loaded as WebVTT and items can be marked played`() = runBlocking {
        serve { r, _ ->
            when {
                r.path!!.startsWith("/Users/u1/Items/m1") -> ok("""{"Type":"Movie","Id":"m1","Name":"Dracula","MediaSources":[{"Id":"ms1","MediaStreams":[
                    {"Type":"Video","Index":0},
                    {"Type":"Subtitle","Index":2,"Language":"eng","DisplayTitle":"English - SRT - External","IsExternal":true,"IsTextSubtitleStream":true},
                    {"Type":"Subtitle","Index":3,"Language":"fre","IsExternal":false,"IsTextSubtitleStream":true},
                    {"Type":"Subtitle","Index":4,"Language":"ger","IsExternal":true,"IsTextSubtitleStream":false}]}]}""")
                r.path!!.startsWith("/MediaSegments/m1") -> ok("""{"Items":[{"Type":"Intro","StartTicks":0,"EndTicks":600000000}]}""")
                r.path!!.startsWith("/UserPlayedItems/m1") -> MockResponse().setResponseCode(404)
                r.path!!.startsWith("/Users/u1/PlayedItems/m1") -> ok("{}")
                r.path!!.startsWith("/Users/u1/Items") -> ok("""{"Items":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val jf = JellyfinSource(http, JellyfinAccount(base(), "u1", "tok", "Hem", "dev"))
        val d = jf.detail(VodTitle(Provider.JELLYFIN, "m1", VodKind.MOVIE, "Dracula"))
        val subs = d.sources.first().subtitles
        assertEquals(1, subs.size)
        assertEquals("${base()}/Videos/m1/ms1/Subtitles/2/0/Stream.vtt", subs.single().url)
        assertEquals("eng" to "English - SRT - External", subs.single().language to subs.single().label)
        assertTrue("every rendition carries them", d.sources.all { it.subtitles == subs })
        assertFalse(subs.single().url.contains("tok"))
        assertTrue(requests.first().path!!.contains("MediaSources"))
        assertEquals(VodSegment.Type.INTRO, jf.segments("m1").single().type)
        assertTrue(jf.markPlayed("m1"))
        assertTrue(requests.any { it.path!!.startsWith("/Users/u1/PlayedItems/m1") && it.method == "POST" })
        jf.tmdbIndex()
        assertTrue(requests.last().path!!.contains("EnableImages=false"))
        assertEquals(BuildConfig.VERSION_NAME, JellyfinSource.CLIENT_VERSION)
    }

    // ---------------------------------------------------------------- 7. Library, genres, secure store

    @Test fun `mark as watched leaves continue watching and imports add only new titles`() {
        val lib = VodLibrary(prefs("watched"))
        val t = VodTitle(Provider.ARCHIVE, "a", VodKind.MOVIE, "A", runtimeMin = 90)
        lib.record(t, 30 * 60_000L, 90 * 60_000L)
        assertEquals(1, lib.continueWatching().size)
        lib.markWatched(t)
        assertTrue(lib.isWatched(t))
        assertTrue(lib.continueWatching().isEmpty())
        val show = VodTitle(Provider.ARCHIVE, "s", VodKind.SHOW, "S")
        lib.markWatched(show, "e1", "S1:E1 · One")
        assertTrue(lib.isWatched(show, "e1"))
        assertTrue(lib.continueWatching().isEmpty())
        lib.toggle(t)
        assertEquals(1, lib.importTitles(listOf(t, show, show)))
        assertEquals(listOf("archive:a", "archive:s"), lib.myList.value.map { it.key })
        assertEquals(0, lib.importTitles(listOf(t)))
    }

    @Test fun `genre chips come from loaded titles shared by at least three`() {
        fun t(id: String, vararg g: String) = VodTitle(Provider.ARCHIVE, id, VodKind.MOVIE, id, genres = g.toList())
        val rows = listOf(
            VodRow("a", "A", listOf(t("1", "Drama", "Noir"), t("2", "Drama"), t("3", "Drama", "Noir"), t("4", "Comedy")), Provider.ARCHIVE),
            VodRow("b", "B", listOf(t("1", "Drama", "Noir"), t("5", "Noir"), t("6", "Comedy")), Provider.ARCHIVE),
        )
        assertEquals(listOf("Drama", "Noir"), genreChips(rows))
    }

    @Test fun `secret store round-trips and falls back to private prefs when the keystore is unavailable`() {
        val p = prefs("secret")
        val s = KeystoreSecretStore(p)
        s.put("k", "v1")
        assertEquals("v1", s.get("k"))
        assertEquals("v1", KeystoreSecretStore(p).get("k"))
        if (!s.encrypted) assertEquals("p:v1", p.getString("k", null))
        else assertFalse(p.getString("k", null)!!.contains("v1"))
        s.put("k", null)
        assertNull(s.get("k"))
        p.edit().putString("junk", "e1:not-base64!").commit()
        assertNull("undecryptable values read as absent", s.get("junk"))
    }

    @Test fun `jellyfin token moves out of plain prefs into the secret store`() {
        val p = prefs("jfsecret")
        p.edit().putString("jf_server", "https://jf.example").putString("jf_user", "u").putString("jf_token", "old").putString("jf_name", "Hem").commit()
        val secrets = MemorySecretStore()
        val s = VodSettings(p, secrets = secrets)
        assertEquals("old", s.config.value.jellyfin!!.token)
        assertNull("migrated out of plain prefs", p.getString("jf_token", null))
        s.setJellyfin(JellyfinAccount("https://jf.example", "u", "new", "Hem", s.deviceId))
        assertEquals("new", secrets.get("jf_token"))
        assertNull(p.getString("jf_token", null))
        s.setJellyfin(null)
        assertNull(secrets.get("jf_token")); assertNull(s.config.value.jellyfin)
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("condition not reached", cond())
    }
}
