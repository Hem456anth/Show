package app.show.tv

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.ui.screens.Metrics
import app.show.tv.ui.theme.ShowTheme
import app.show.tv.vod.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.zip.GZIPInputStream

/** 1.6.0 Movies & Series: providers, rights filter, library/progress, repository, settings and the tab UI. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class Release160Test {
    private val server = MockWebServer()
    private val http = VodHttp(OkHttpClient())
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    @After fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun res(name: String) = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(name)).bufferedReader().readText()
    private fun base() = server.url("/").toString().trimEnd('/')

    private fun serve(routes: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = routes(request) }
        server.start()
    }

    // ---------------------------------------------------------------- Internet Archive

    @Test fun `archive catalog keeps only public-domain or CC items and builds artwork`() = runBlocking {
        val src = ArchiveSource(http)
        val docs = JSONObject(res("vod_archive_search.json.gz")).getJSONObject("response").getJSONArray("docs")
        val titles = (0 until docs.length()).mapNotNull { src.parseDoc(docs.getJSONObject(it), null) }
        assertEquals(3, titles.size)
        assertTrue(titles.all { it.provider == Provider.ARCHIVE && it.playable && it.posterUrl!!.startsWith("https://archive.org/services/img/") })
        val copyrighted = JSONObject().put("identifier", "get-smart").put("title", "Get Smart").put("collection", JSONArray().put("classic_tv"))
        assertNull("no licence = not listed", src.parseDoc(copyrighted, null))
        assertNull(src.parseDoc(JSONObject(copyrighted.toString()).put("licenseurl", "https://example.com/all-rights-reserved"), null))
        val pd = JSONObject(copyrighted.toString()).put("licenseurl", "http://creativecommons.org/publicdomain/mark/1.0/")
        assertEquals(VodKind.SHOW, src.parseDoc(pd, null)!!.kind)
        val url = src.searchUrl("collection:feature_films", 30, "downloads desc")
        assertTrue(url.contains("licenseurl%3A%28*publicdomain*%20OR%20*creativecommons*%29"))
    }

    @Test fun `archive movie detail returns renditions best first and a series returns ordered episodes`() = runBlocking {
        serve { r ->
            when {
                r.path!!.contains("his_girl_friday") -> MockResponse().setBody(res("vod_meta_his_girl_friday.json.gz"))
                r.path!!.contains("theloneranger") -> MockResponse().setBody(res("vod_meta_theloneranger_201705.json.gz"))
                r.path!!.contains("dark") -> MockResponse().setBody("""{"is_dark":true,"metadata":{"licenseurl":"http://creativecommons.org/licenses/publicdomain/"}}""")
                else -> MockResponse().setBody("""{"metadata":{"title":"X","licenseurl":"https://example.com/rr"},"files":[{"name":"x.mp4","format":"h.264","height":"480"}]}""")
            }
        }
        val src = ArchiveSource(http, base())
        val movie = src.detail(VodTitle(Provider.ARCHIVE, "his_girl_friday", VodKind.MOVIE, "His Girl Friday"))
        assertEquals(listOf("480p", "240p"), movie.sources.map { it.label })
        assertTrue(movie.sources.first().url.endsWith("/download/his_girl_friday/his_girl_friday.mp4"))
        assertEquals(91, movie.title.runtimeMin)
        assertTrue(movie.license!!.contains("publicdomain"))

        val show = src.detail(VodTitle(Provider.ARCHIVE, "theloneranger_201705", VodKind.SHOW, "The Lone Ranger"))
        val eps = show.seasons.single().episodes!!
        assertEquals(16, eps.size)
        assertEquals((1..16).toList(), eps.map { it.number })
        assertTrue(eps.first().title.startsWith("Enter"))
        assertEquals("The Legionof Old Timers".take(10), eps[3].title.take(10))
        assertTrue(eps.all { it.sources.isNotEmpty() })

        val dark = runCatching { src.detail(VodTitle(Provider.ARCHIVE, "dark", VodKind.MOVIE, "Gone")) }
        assertTrue(dark.exceptionOrNull()!!.message!!.contains("taken down"))
        val rr = src.detail(VodTitle(Provider.ARCHIVE, "other", VodKind.MOVIE, "Other"))
        assertTrue("not rights-clear on its own metadata = nothing to play", rr.sources.isEmpty())
    }

    @Test fun `archive helpers`() {
        assertEquals(2 to 5, ArchiveSource.episodeNumbers("Show_S02E05_title.mp4"))
        assertEquals(1 to 12, ArchiveSource.episodeNumbers("show 1x12.mp4"))
        assertNull(ArchiveSource.episodeNumbers("movie.mp4"))
        assertEquals(91, ArchiveSource.parseRuntime("1:31:44"))
        assertEquals(75, ArchiveSource.parseRuntime("75 min"))
        assertEquals("The Lone Ranger Fights On", ArchiveSource.cleanTitle("s01e02_TheLoneRangerFightsOn"))
        assertFalse(ArchiveSource.isRightsClear(null))
        assertTrue(ArchiveSource.isRightsClear("http://creativecommons.org/licenses/by-nc/3.0/"))
    }

    // ---------------------------------------------------------------- TMDB

    private val tmdbMovie = """{"id":138,"title":"Dracula","release_date":"1931-02-12","overview":"The count.","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
        "genres":[{"id":27,"name":"Horror"}],"vote_average":7.1,"runtime":75,
        "production_companies":[{"id":33,"name":"Universal Pictures"}],
        "credits":{"cast":[{"id":1,"name":"Bela Lugosi","character":"Dracula","profile_path":"/l.jpg"}],"crew":[{"id":2,"name":"Tod Browning","job":"Director"},{"id":3,"name":"Grip Guy","job":"Grip"}]},
        "videos":{"results":[{"site":"YouTube","type":"Teaser","key":"AAAAAAAAAAA","official":false},{"site":"YouTube","type":"Trailer","key":"sSPNLK1wWaU","official":true},{"site":"Vimeo","type":"Trailer","key":"1"}]},
        "recommendations":{"results":[{"id":3035,"media_type":"movie","title":"Frankenstein","release_date":"1931-11-21","genre_ids":[27]},{"id":9,"media_type":"movie","title":"Adult","adult":true}]}}"""

    @Test fun `tmdb is metadata only, picks the official trailer and filters adult titles`() = runBlocking {
        serve { r ->
            when {
                r.path!!.startsWith("/trending") -> MockResponse().setBody("""{"results":[{"id":1,"media_type":"movie","title":"A","release_date":"2026-01-01","genre_ids":[28]},{"id":2,"media_type":"tv","name":"B","first_air_date":"2025-05-01"},{"id":3,"media_type":"person","name":"P"}]}""")
                r.path!!.startsWith("/movie/138") -> MockResponse().setBody(tmdbMovie)
                r.path!!.startsWith("/tv/2/season/1") -> MockResponse().setBody("""{"episodes":[{"id":11,"episode_number":1,"name":"Pilot","runtime":42,"still_path":"/s.jpg"}]}""")
                else -> MockResponse().setBody("""{"results":[]}""")
            }
        }
        val src = TmdbSource(http, "0123456789abcdef0123456789abcdef", base())
        val trending = src.rows().first { it.id == "tmdb_trending" }.items
        assertEquals(listOf("A", "B"), trending.map { it.title })
        assertTrue(trending.none { it.playable })
        assertEquals("Action", trending.first().genres.single())
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("api_key=0123456789abcdef0123456789abcdef"))

        val d = src.detail(VodTitle(Provider.TMDB, "138", VodKind.MOVIE, "Dracula", tmdbId = "138"))
        assertEquals("sSPNLK1wWaU", d.trailerYoutubeId)
        assertEquals("Bela Lugosi", d.cast.single().name)
        assertEquals(listOf("Tod Browning"), d.crew.map { it.name })
        assertEquals(listOf("Universal Pictures"), d.companies)
        assertEquals(listOf("Frankenstein"), d.recommendations.map { it.title })
        assertTrue(d.sources.isEmpty())
        assertEquals("${TmdbSource.IMG}/w342/p.jpg", d.title.posterUrl)

        val season = src.season(VodTitle(Provider.TMDB, "2", VodKind.SHOW, "B"), VodSeason(1, "Season 1"))
        assertEquals("Pilot", season.episodes!!.single().title)
        assertEquals(42 * 60, season.episodes!!.single().runtimeSec)
        assertTrue(season.episodes!!.single().sources.isEmpty())
    }

    @Test fun `tmdb read token goes in the Authorization header, never the URL`() = runBlocking {
        serve { MockResponse().setBody("""{"results":[]}""") }
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2ln"
        TmdbSource(http, token, base()).search("dracula")
        val r = server.takeRequest()
        assertEquals("Bearer $token", r.getHeader("Authorization"))
        assertFalse(r.path!!.contains("api_key"))
    }

    // ---------------------------------------------------------------- Jellyfin

    @Test fun `jellyfin sign-in, catalog, renditions with auth header and progress sync`() = runBlocking {
        serve { r ->
            when {
                r.path == "/Users/AuthenticateByName" -> {
                    val body = JSONObject(r.body.readUtf8())
                    if (body.getString("Pw") == "right") MockResponse().setBody("""{"AccessToken":"tok123","User":{"Id":"u1","Name":"Hem"}}""")
                    else MockResponse().setResponseCode(401)
                }
                r.path!!.startsWith("/Users/u1/Items/Resume") -> MockResponse().setBody("""{"Items":[{"Type":"Episode","Id":"e1","SeriesId":"s1","SeriesName":"Pioneer One","Name":"Earthfall"}]}""")
                r.path!!.startsWith("/Users/u1/Items/m1") -> MockResponse().setBody("""{"Type":"Movie","Id":"m1","Name":"Dracula","ProductionYear":1931,"RunTimeTicks":44662997880,
                    "People":[{"Name":"Bela Lugosi","Type":"Actor","Role":"Dracula","Id":"p1","PrimaryImageTag":"t"},{"Name":"Tod Browning","Type":"Director","Id":"p2"}],
                    "Studios":[{"Name":"Universal Pictures"}],"RemoteTrailers":[{"Url":"https://www.youtube.com/watch?v=sSPNLK1wWaU"}],
                    "ProviderIds":{"Tmdb":"138"},"UserData":{"PlaybackPositionTicks":12617283333}}""")
                r.path!!.startsWith("/Users/u1/Items") -> MockResponse().setBody("""{"Items":[{"Type":"Movie","Id":"m1","Name":"Dracula","ProductionYear":1931,"ProviderIds":{"Tmdb":"138"},"BackdropImageTags":["x"]},{"Type":"Series","Id":"s1","Name":"Pioneer One"},{"Type":"Folder","Id":"f"}]}""")
                r.path!!.startsWith("/Sessions/Playing") -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val wrong = runCatching { JellyfinSource.signIn(http, base(), "hem", "nope", "dev1") }
        assertEquals("Wrong username or password.", wrong.exceptionOrNull()!!.message)
        server.takeRequest()
        val acct = JellyfinSource.signIn(http, base(), "hem", "right", "dev1")
        val authReq = server.takeRequest()
        assertTrue(authReq.getHeader("Authorization")!!.contains("DeviceId=\"dev1\""))
        assertEquals("tok123", acct.token)

        val jf = JellyfinSource(http, acct)
        val rows = jf.rows()
        val resume = rows.first { it.id == "jf_resume" }.items.single()
        assertEquals("s1" to "Pioneer One", resume.providerId to resume.title)
        assertEquals(listOf("Dracula", "Pioneer One"), rows.first { it.id == "jf_movies" }.items.map { it.title })

        val d = jf.detail(VodTitle(Provider.JELLYFIN, "m1", VodKind.MOVIE, "Dracula"))
        assertEquals(1261728L, d.remotePositionMs)
        assertEquals("sSPNLK1wWaU", d.trailerYoutubeId)
        assertEquals(listOf("Universal Pictures"), d.companies)
        assertEquals(74, d.title.runtimeMin)
        assertEquals("Original", d.sources.first().label)
        assertTrue(d.sources.first().url.contains("/Videos/m1/stream?static=true"))
        assertFalse("token never in the URL", d.sources.any { it.url.contains("tok123") })
        assertTrue(d.sources.all { it.headers["Authorization"]!!.contains("Token=\"tok123\"") })
        assertEquals(mapOf("MOVIE:138" to "m1"), jf.tmdbIndex().mapValues { it.value.providerId })

        jf.report("m1", 60_000, "progress")
        var rep: RecordedRequest
        do { rep = server.takeRequest() } while (!rep.path!!.startsWith("/Sessions"))
        assertEquals("/Sessions/Playing/Progress", rep.path)
        assertEquals(600_000_000L, JSONObject(rep.body.readUtf8()).getLong("PositionTicks"))
    }

    @Test fun `jellyfin server address is normalised and unsafe forms are refused`() {
        assertEquals("https://media.example.com", JellyfinSource.normaliseServer("media.example.com/"))
        assertEquals("http://192.168.1.5:8096", JellyfinSource.normaliseServer("http://192.168.1.5:8096"))
        assertNull(JellyfinSource.normaliseServer("ftp://x"))
        assertNull(JellyfinSource.normaliseServer("https://user:pw@x.com"))
        assertNull(JellyfinSource.normaliseServer("javascript:alert(1)"))
        assertNull(JellyfinSource.normaliseServer(""))
    }

    // ---------------------------------------------------------------- Library, settings, repository

    private fun title(id: String, kind: VodKind = VodKind.MOVIE, p: Provider = Provider.ARCHIVE, year: Int? = 1940) =
        VodTitle(p, id, kind, id.replaceFirstChar { it.uppercase() }, year)

    @Test fun `progress, resume and continue watching survive a restart`() {
        val prefs = ctx.getSharedPreferences("vod_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        var now = 1_000L
        val lib = VodLibrary(prefs) { now }
        lib.record(title("a"), 30_000, 6_000_000)          // just started: not resumable
        now += 1; lib.record(title("b"), 3_000_000, 6_000_000)
        now += 1; lib.record(title("c"), 5_900_000, 6_000_000)   // finished
        now += 1; lib.record(title("show", VodKind.SHOW), 120_000, 1_500_000, "e2", "S1:E2 · Two")
        val again = VodLibrary(prefs) { now }
        assertEquals(listOf("show", "b"), again.continueWatching().map { it.title.providerId })
        assertEquals("e2", again.positionFor(title("show", VodKind.SHOW))!!.episodeId)
        assertEquals(120_000L, again.positionFor(title("show", VodKind.SHOW), "e2")!!.positionMs)
        assertFalse(again.positionFor(title("c"))!!.resumable)
        again.removeProgress(title("show", VodKind.SHOW).key)
        assertNull(again.positionFor(title("show", VodKind.SHOW), "e2"))
        lib.record(title("z"), 10, 0) // unknown duration is ignored
        assertNull(lib.positionFor(title("z")))
    }

    @Test fun `my list holds titles and company, network and cast shortcuts`() {
        val prefs = ctx.getSharedPreferences("vod_list_${System.nanoTime()}", Context.MODE_PRIVATE)
        val lib = VodLibrary(prefs)
        lib.toggle(title("a")); lib.toggle(title("b", p = Provider.TMDB))
        lib.toggle(VodShortcut(VodShortcut.Type.COMPANY, "Universal Pictures", Provider.TMDB, "33"))
        lib.toggle(VodShortcut(VodShortcut.Type.PERSON, "Bela Lugosi", Provider.TMDB, "1"))
        lib.toggle(title("a"))
        val again = VodLibrary(prefs)
        assertEquals(listOf("tmdb:movie:b"), again.myList.value.map { it.key })
        assertEquals(listOf("Bela Lugosi", "Universal Pictures"), again.shortcuts.value.map { it.name })
    }

    @Test fun `tmdb key validation and jellyfin token storage`() {
        val s = VodSettings(ctx.getSharedPreferences("vod_set_${System.nanoTime()}", Context.MODE_PRIVATE))
        assertNotNull(s.setTmdbKey("not a key"))
        assertNull(s.setTmdbKey("0123456789abcdef0123456789abcdef"))
        assertEquals("0123456789abcdef0123456789abcdef", s.config.value.tmdbKey)
        assertNull(s.setTmdbKey(""))
        assertNull(s.config.value.tmdbKey)
        s.setJellyfin(JellyfinAccount("https://jf.example", "u", "tok", "Hem", s.deviceId))
        assertEquals("tok", s.config.value.jellyfin!!.token)
        s.setJellyfin(null)
        assertNull(s.config.value.jellyfin)
        val builtIn = VodSettings(ctx.getSharedPreferences("vod_set2_${System.nanoTime()}", Context.MODE_PRIVATE), "fedcba9876543210fedcba9876543210")
        assertEquals("fedcba9876543210fedcba9876543210", builtIn.config.value.tmdbKey)
    }

    private class FakeSource(override val provider: Provider, val rows: List<VodRow>, val fail: Boolean = false, val found: List<VodTitle> = emptyList()) : VodSource {
        var calls = 0
        override suspend fun rows(): List<VodRow> { calls++; if (fail) throw java.io.IOException("down"); return rows }
        override suspend fun search(query: String) = found
        override suspend fun detail(title: VodTitle) = VodDetail(title, sources = if (title.playable) listOf(VodVideo("480p", "https://x/v.mp4", 480)) else emptyList(),
            trailerYoutubeId = if (title.playable) null else "sSPNLK1wWaU")
    }

    @Test fun `repository keeps working providers when one fails, caches, and dedupes search towards playable copies`() = runBlocking {
        val archive = FakeSource(Provider.ARCHIVE, listOf(VodRow("ia", "Classic Movies", listOf(title("dracula", year = 1931)), Provider.ARCHIVE)),
            found = listOf(title("dracula", year = 1931)))
        val tmdb = FakeSource(Provider.TMDB, emptyList(), fail = true,
            found = listOf(title("dracula", p = Provider.TMDB, year = 1931), title("nosferatu", p = Provider.TMDB, year = 1922)))
        var now = 0L
        val repo = VodRepository(http, { VodConfig() }, { now }) { _, _ -> listOf(tmdb, archive) }
        val c = repo.catalog()
        assertEquals(listOf("ia"), c.rows.map { it.id })
        assertEquals(listOf(Provider.TMDB), c.failed)
        repo.catalog(); assertEquals("cached", 1, archive.calls)
        now += VodRepository.TTL_MS + 1; repo.catalog(); assertEquals(2, archive.calls)
        val found = repo.search("dracula")
        assertEquals(listOf("archive:dracula", "tmdb:movie:nosferatu"), found.map { it.key })
        assertTrue(repo.search("d").isEmpty())
    }

    @Test fun `youtube ids are extracted only from real YouTube URLs`() {
        assertEquals("sSPNLK1wWaU", youtubeId("https://www.youtube.com/watch?v=sSPNLK1wWaU"))
        assertEquals("sSPNLK1wWaU", youtubeId("https://youtu.be/sSPNLK1wWaU"))
        assertNull(youtubeId("https://evil.example/watch?v=sSPNLK1wWaU&x"))
        assertNotNull(app.show.tv.data.Embeds.parse("https://www.youtube.com/embed/sSPNLK1wWaU"))
    }

    private class FakeSourceHolder
}
