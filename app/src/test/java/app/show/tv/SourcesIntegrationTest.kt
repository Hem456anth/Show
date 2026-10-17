package app.show.tv

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.CatalogEnricher
import app.show.tv.data.Channel
import app.show.tv.data.ChannelCatalog
import app.show.tv.data.LibraryStore
import app.show.tv.data.M3uParser
import app.show.tv.data.Playlist
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.PlaylistSource
import app.show.tv.data.PlaylistSource.Grouping
import app.show.tv.data.SettingsStore
import app.show.tv.data.StreamLink
import app.show.tv.player.PlaybackController
import app.show.tv.player.PlaybackQueue
import app.show.tv.player.RetryPolicy
import app.show.tv.player.StreamEngine
import app.show.tv.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Snapshots of the four official playlists, captured from iptv-org on 2026-10-16. */
internal object Snapshots {
    private val cache = HashMap<PlaylistSource, Playlist>()

    fun text(source: PlaylistSource): String {
        val name = when (source) {
            PlaylistSource.INDIA -> "iptv_org_countries_in_snapshot.m3u.gz"
            PlaylistSource.GLOBAL -> "iptv_org_index_snapshot.m3u.gz"
            PlaylistSource.COUNTRIES -> "iptv_org_index_country_snapshot.m3u.gz"
            PlaylistSource.LANGUAGES -> "iptv_org_index_language_snapshot.m3u.gz"
            PlaylistSource.CATEGORIES -> return javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u")!!.bufferedReader().readText()
        }
        return GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(name)!!).bufferedReader().readText()
    }

    @Synchronized fun parsed(source: PlaylistSource): Playlist = cache.getOrPut(source) { M3uParser.parse(text(source), source.grouping) }

    fun enriched(source: PlaylistSource): List<Channel> =
        CatalogEnricher.enrich(CatalogEnricher.dedupe(parsed(source).channels), source.enrichFrom.map { it.grouping to parsed(it) })

    val NEW_SOURCES = listOf(PlaylistSource.INDIA, PlaylistSource.GLOBAL, PlaylistSource.COUNTRIES, PlaylistSource.LANGUAGES)
}

/** Pure-JVM checks of parsing, grouping, de-duplication and enrichment on the real playlists. */
class OfficialSourcesTest {

    @Test fun `every official source parses fully with no duplicate channels`() {
        val minimum = mapOf(PlaylistSource.INDIA to 700, PlaylistSource.GLOBAL to 10_000, PlaylistSource.COUNTRIES to 10_000, PlaylistSource.LANGUAGES to 10_000)
        for (src in Snapshots.NEW_SOURCES) {
            val text = Snapshots.text(src)
            val p = Snapshots.parsed(src)
            val uniqueUrls = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(M3uParser::urlKey).toSet()
            assertTrue("${src.id}: ${p.channels.size} channels", p.channels.size >= minimum.getValue(src))
            assertEquals("${src.id}: one channel per stream", uniqueUrls.size, p.channels.size)
            assertEquals("${src.id}: ids unique", p.channels.size, p.channels.map { it.id }.toSet().size)
            assertEquals("${src.id}: nothing skipped", 0, p.skippedEntries)
            assertTrue(p.channels.all { M3uParser.isAcceptableUrl(it.streamUrl) && it.name.isNotBlank() })
        }
    }

    @Test fun `grouped indexes collapse a stream listed under several groups`() {
        val text = Snapshots.text(PlaylistSource.COUNTRIES)
        val entries = text.lineSequence().count { it.startsWith("#EXTINF") }
        val p = Snapshots.parsed(PlaylistSource.COUNTRIES)
        assertTrue("index.country lists $entries entries for ${p.channels.size} streams", entries > p.channels.size)
        assertTrue(p.channels.any { it.countries.size > 1 })
        val l = Snapshots.parsed(PlaylistSource.LANGUAGES)
        assertTrue(l.channels.any { it.languages.size > 1 })
        assertTrue("language groups never leak into categories", l.channels.all { it.categories == listOf(Channel.UNCATEGORISED) })
    }

    @Test fun `country and language indexes give real filter values`() {
        val countries = ChannelCatalog(Snapshots.parsed(PlaylistSource.COUNTRIES).channels).countries
        assertTrue("${countries.size} countries", countries.size >= 150)
        assertTrue("India" in countries && "United States" in countries && "Undefined" !in countries)
        val languages = ChannelCatalog(Snapshots.parsed(PlaylistSource.LANGUAGES).channels).languages
        assertTrue("${languages.size} languages", languages.size >= 150)
        assertTrue(listOf("Telugu", "Hindi", "Tamil", "English", "Oriya").all { it in languages })
        assertTrue(languages.none { it.endsWith("(macrolanguage)") })
    }

    @Test fun `enrichment gives every source categories, countries and languages`() {
        for (src in Snapshots.NEW_SOURCES) {
            val chans = Snapshots.enriched(src)
            val n = chans.size.toDouble()
            val withCountry = chans.count { it.countries.isNotEmpty() } / n
            val withLanguage = chans.count { it.languages.isNotEmpty() } / n
            val withCategory = chans.count { it.categories != listOf(Channel.UNCATEGORISED) } / n
            assertTrue("${src.id} country coverage $withCountry", withCountry > 0.95)
            assertTrue("${src.id} language coverage $withLanguage", withLanguage > 0.95)
            assertTrue("${src.id} category coverage $withCategory", withCategory > 0.80)
        }
        val india = ChannelCatalog(Snapshots.enriched(PlaylistSource.INDIA))
        assertTrue(india.channels.count { "India" in it.countries } > india.channels.size * 0.95)
        assertTrue("Telugu channels in the India source", india.inLanguage("Telugu").size >= 50)
        assertTrue(india.browse(category = "News", country = "India", language = "Telugu").isNotEmpty())
        assertTrue(india.search("telugu").isNotEmpty())
    }

    @Test fun `country from tvg-id is spelled the way the country index spells it`() {
        // tvg-id gives the channel's origin and the index the countries it targets, which can
        // differ (a US channel aimed at Brazil), so compare spellings, not per-channel values.
        val indexNames = ChannelCatalog(Snapshots.parsed(PlaylistSource.COUNTRIES).channels).countries.toSet()
        val guessed = Snapshots.parsed(PlaylistSource.GLOBAL).channels.mapNotNull { it.country }.toSet()
        val unknown = guessed - indexNames
        assertTrue("names the index never uses: $unknown", unknown.size <= 3)
    }

    @Test fun `home rows follow the source's grouping`() {
        val countries = ChannelCatalog(Snapshots.enriched(PlaylistSource.COUNTRIES)).rows(Grouping.COUNTRY)
        assertEquals("United States", countries.first().first)
        val languages = ChannelCatalog(Snapshots.enriched(PlaylistSource.LANGUAGES)).rows(Grouping.LANGUAGE)
        assertEquals("English", languages.first().first)
        val india = ChannelCatalog(Snapshots.enriched(PlaylistSource.INDIA)).rows(Grouping.CATEGORY)
        assertEquals("News", india.first().first)
        assertTrue((countries + languages + india).all { it.second.isNotEmpty() })
    }

    @Test fun `url key folds cosmetic differences but keeps path and tokens`() {
        assertEquals(M3uParser.urlKey("HTTPS://CDN.Example.com/live/"), M3uParser.urlKey("https://cdn.example.com/live"))
        assertEquals("https://cdn.example.com", M3uParser.urlKey("https://cdn.example.com/"))
        assertFalse(M3uParser.urlKey("https://a.com/Live?t=AbC") == M3uParser.urlKey("https://a.com/live?t=abc"))
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1 group-title=\"News\",A\nhttps://X.com/a/\n#EXTINF:-1 group-title=\"Music\",A again\nhttps://x.com/a\n")
        assertEquals(1, p.channels.size)
        assertEquals(listOf("News", "Music"), p.channels.single().categories)
    }

    @Test fun `language and country groupings fill the right fields`() {
        val lang = M3uParser.parse("#EXTINF:-1 tvg-id=\"Foo.in@SD\" group-title=\"Oriya (macrolanguage)\",Foo\nhttps://a/1\n#EXTINF:-1 tvg-id=\"Foo.in@SD\" group-title=\"Hindi\",Foo\nhttps://a/1\n", Grouping.LANGUAGE)
        val ch = lang.channels.single()
        assertEquals(listOf("Oriya", "Hindi"), ch.languages)
        assertEquals(listOf("India"), ch.countries) // from tvg-id
        assertEquals(listOf(Channel.UNCATEGORISED), ch.categories)
        val cty = M3uParser.parse("#EXTINF:-1 group-title=\"Undefined\",Bar\nhttps://a/2\n#EXTINF:-1 group-title=\"Kenya\",Bar\nhttps://a/2\n", Grouping.COUNTRY)
        assertEquals(listOf("Kenya"), cty.channels.single().countries)
    }

    @Test fun `same feed listed twice becomes one channel with a backup link`() {
        val p = M3uParser.parse(
            "#EXTINF:-1 tvg-id=\"News.in@HD\" http-user-agent=\"UA2\" group-title=\"News\",News One (720p)\nhttps://a/main.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"News.in@HD\" http-user-agent=\"UA2\" group-title=\"General\",News One (720p)\nhttps://b/backup.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"News.in@SD\" group-title=\"News\",News One (480p)\nhttps://c/sd.m3u8\n",
        )
        val out = CatalogEnricher.dedupe(p.channels)
        assertEquals(2, out.size) // the SD feed is a different feed, kept
        val hd = out.first()
        assertEquals(listOf(StreamLink("https://b/backup.m3u8", "UA2", null)), hd.alternates)
        assertEquals(listOf("News", "General"), hd.categories)
        assertEquals(hd.id, hd.withLink(hd.alternates.first()).id)
    }

    @Test fun `every source maps onto a known playlist and enrich plan`() {
        assertEquals(PlaylistSource.INDIA, PlaylistSource.byUrl("https://iptv-org.github.io/iptv/countries/in.m3u"))
        assertEquals(PlaylistSource.GLOBAL, PlaylistSource.byUrl("https://iptv-org.github.io/iptv/index.m3u"))
        assertEquals(PlaylistSource.COUNTRIES, PlaylistSource.byUrl("https://iptv-org.github.io/iptv/index.country.m3u"))
        assertEquals(PlaylistSource.LANGUAGES, PlaylistSource.byUrl("https://iptv-org.github.io/iptv/index.language.m3u"))
        for (src in PlaylistSource.entries) {
            assertTrue(src.url.startsWith("https://iptv-org.github.io/iptv/"))
            assertFalse(src.grouping in src.enrichFrom.map { it.grouping })
            assertEquals(2, src.enrichFrom.size)
        }
    }

    @Test fun `playlists cannot point the player at local files or other apps`() {
        listOf("file:///data/data/app.show.tv/shared_prefs/library.xml", "content://media/x", "intent://scan#Intent;end",
            "javascript://alert(1)", "data://text", "ftp://e/x").forEach { assertFalse(it, M3uParser.isAcceptableUrl(it)) }
        listOf("https://a/b.m3u8", "http://a/b", "rtmp://a/live", "rtsp://a/s", "udp://@239.0.0.1:1234").forEach { assertTrue(it, M3uParser.isAcceptableUrl(it)) }
        val p = M3uParser.parse("#EXTINF:-1,Evil\nfile:///etc/hosts\n#EXTINF:-1,Ok\nhttps://ok/a.m3u8\n")
        assertEquals(listOf("Ok"), p.channels.map { it.name })
        assertEquals(1, p.skippedEntries)
    }

    @Test fun `related channels never fall back to the Undefined bucket`() {
        val cat = ChannelCatalog(Snapshots.parsed(PlaylistSource.LANGUAGES).channels) // language index: no categories at all
        val ch = cat.inLanguage("Telugu").first { it.languages == listOf("Telugu") }
        val related = cat.related(ch)
        assertTrue("non-empty, excludes self", related.isNotEmpty() && related.none { it.id == ch.id })
        assertTrue("all Telugu: ${related.map { it.languages }}", related.all { "Telugu" in it.languages })
        assertEquals("More in Telugu", cat.relatedTitle(ch.copy(categories = emptyList())))
        val india = ChannelCatalog(Snapshots.enriched(PlaylistSource.INDIA))
        val news = india.inCategory("News").first { it.languages.firstOrNull() == "Telugu" }
        assertTrue("same-language neighbours first", "Telugu" in india.related(news).first().languages)
    }

    @Test fun `saved channel snapshots round-trip`() {
        val ch = Snapshots.enriched(PlaylistSource.INDIA).first { it.languages.isNotEmpty() && it.logoUrl != null }
        val back = LibraryStore.decode(LibraryStore.encode(ch))!!
        assertEquals(ch.copy(alternates = emptyList()), back)
        assertNull(LibraryStore.decode("garbage"))
    }
}

/** Android-side checks: settings, the view model's two-pass load, and handing channels to the player. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SourcesAndroidTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun `choosing a source persists and switches grouping`() {
        val p = prefs("src")
        val s = SettingsStore(p)
        assertEquals(PlaylistSource.DEFAULT, s.settings.value.source)
        s.setSource(PlaylistSource.LANGUAGES)
        assertEquals(Grouping.LANGUAGE, s.settings.value.grouping)
        assertEquals(PlaylistSource.LANGUAGES, SettingsStore(p).settings.value.source)
        s.setPlaylistUrl("https://iptv-org.github.io/iptv/countries/in.m3u")
        assertEquals(PlaylistSource.INDIA, s.settings.value.source)
        s.setPlaylistUrl("https://example.com/mine.m3u")
        assertTrue(s.settings.value.isCustomPlaylist)
        assertEquals(Grouping.CATEGORY, s.settings.value.grouping)
    }

    /** Offline: every playlist comes from its cache, exactly as after a first successful run. */
    private fun seeded(dir: File, src: PlaylistSource) {
        File(dir, PlaylistRepository.cacheFileName("https://127.0.0.1:9/${src.id}.m3u")).writeText(Snapshots.text(src))
    }

    @Test fun `each source loads, gets enriched and keeps favourites from other sources`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val dir = File(ctx.filesDir, "srcvm-${System.nanoTime()}").apply { mkdirs() }
            PlaylistSource.entries.forEach { seeded(dir, it) }
            val lib = LibraryStore(prefs("srclib"))
            fun repo(src: PlaylistSource) = PlaylistRepository(OkHttpClient(), dir, "https://127.0.0.1:9/${src.id}.m3u", grouping = src.grouping)

            var favourite: Channel? = null
            for (src in Snapshots.NEW_SOURCES) {
                val st = SettingsStore(prefs("st-${src.id}")).also { it.setSource(src) }
                val vm = MainViewModel(repo(src), lib, compute = Dispatchers.Default, settingsStore = st, indexRepository = ::repo)
                val state = withTimeout(60_000) {
                    vm.state.first { s -> !s.loading && s.catalog.languages.isNotEmpty() && s.catalog.countries.isNotEmpty() }
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("${src.id} rows", state.rows.isNotEmpty())
                assertTrue("${src.id} languages", state.catalog.languages.size > 5)
                assertTrue("${src.id} countries", state.catalog.countries.isNotEmpty())
                if (favourite == null) {
                    favourite = state.catalog.channels.first { it.countries.contains("India") }
                    vm.toggleFavorite(favourite)
                } else {
                    // Saved from the India source: still in My List here, even if this list lacks it.
                    assertEquals(favourite.id, withTimeout(5_000) { vm.favorites.first { it.isNotEmpty() } }.first().id)
                }
            }
            // A custom playlist without that channel still shows it in My List, from its snapshot.
            File(dir, PlaylistRepository.cacheFileName("https://127.0.0.1:9/mine.m3u")).writeText("#EXTM3U\n#EXTINF:-1,Mine\nhttps://example.com/mine.m3u8\n")
            val vm = MainViewModel(PlaylistRepository(OkHttpClient(), dir, "https://127.0.0.1:9/mine.m3u"), lib, compute = Dispatchers.Default)
            withTimeout(30_000) { vm.state.first { !it.loading && it.catalog.channels.size == 1 } }
            val saved = withTimeout(5_000) { vm.favorites.first { it.isNotEmpty() } }.single()
            assertEquals(favourite!!.id, saved.id)
            assertEquals(favourite.streamUrl, saved.streamUrl)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `channels from every source hand off to the player`() {
        val engine = StreamEngine(ctx, OkHttpClient())
        for (src in Snapshots.NEW_SOURCES) {
            val chans = Snapshots.enriched(src)
            val sample = chans.filter { it.protocol.playableInApp }.let { list -> list.filterIndexed { i, _ -> i % (list.size / 150 + 1) == 0 } } +
                chans.filter { it.userAgent != null || it.referrer != null }.take(20)
            for (ch in sample) {
                engine.mediaSource(ch) // must build for every playable entry
                val intent = PlaybackQueue.intent(ctx, ch, sample)
                val (queue, i) = PlaybackQueue.take(intent)!!
                assertEquals(ch.id, queue[i].id)
                assertEquals(ch.streamUrl, queue[i].streamUrl)
            }
        }
    }

    @Test fun `dead main stream falls back to the backup, and zapping skips dead channels`() {
        val server = MockWebServer().apply { start() }
        val engine = StreamEngine(ctx, OkHttpClient(), segmentRetries = 1, retryBaseDelayMs = 10)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(404)
        }
        val main = Channel("idA", "A", server.url("/main.m3u8").toString(), null, listOf("News"), null, null, null, null, emptySet(), null, null,
            alternates = listOf(StreamLink(server.url("/backup.m3u8").toString(), "BackupUA", null)))
        val dead = main.copy(id = "idB", name = "B", streamUrl = server.url("/b.m3u8").toString(), alternates = emptyList())
        val live = main.copy(id = "idC", name = "C", streamUrl = server.url("/c.m3u8").toString(), alternates = emptyList())
        val failed = mutableListOf<String>()
        val c = PlaybackController(engine, engine.buildPlayer(), listOf(main, dead, live), 0, retry = RetryPolicy(0),
            onChannelFailed = { ch, _ -> failed += ch.id }, isUnavailable = { it.id == "idB" })
        try {
            c.start()
            val paths = mutableListOf<String>()
            val end = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < end && (c.state.value.issue == null || "/backup.m3u8" !in paths)) {
                shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
                server.takeRequest(10, TimeUnit.MILLISECONDS)?.let { r -> paths += r.path!!; if (r.path == "/backup.m3u8") assertEquals("BackupUA", r.getHeader("User-Agent")) }
            }
            assertEquals("/main.m3u8", paths.first())
            assertTrue("backup tried: $paths", "/backup.m3u8" in paths)
            assertEquals(1, c.state.value.backup)
            assertTrue("channel reported dead only after its backup failed", failed == listOf("idA"))
            assertEquals("idA", c.state.value.channel.id)

            c.next()
            assertEquals("skips the recently dead channel", "idC", c.current.id)
            c.previous()
            assertEquals("idA", c.current.id)
        } finally { c.release(); server.shutdown() }
    }
}
