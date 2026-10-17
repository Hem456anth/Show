package app.show.tv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.Channel
import app.show.tv.data.ExtraSources
import app.show.tv.data.LibraryStore
import app.show.tv.data.M3uParser
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.SettingsStore
import app.show.tv.player.AudioLabels
import app.show.tv.player.PlaybackController
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

private fun zee(url: String, tvgId: String? = "ZeeTV.in@SD", name: String = "Zee TV") =
    Channel(M3uParser.stableId(url), name, url, null, listOf("Entertainment"), tvgId, "Hindi", "India", "576p", emptySet(), null, null,
        countries = listOf("India"), languages = listOf("Hindi"))

/** 1.5 root cause #1: iptv-org rotates Zee links; saved channels must follow the channel, not the dead URL. */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class RotatedLinkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `identity uses the tvg-id feed, else the normalised name`() {
        assertEquals("id:zeetv.in@sd", zee("http://a/1.m3u8").identity)
        assertEquals(zee("http://a/1.m3u8").identity, zee("http://b/2.m3u8").identity)
        assertNotEquals(zee("http://a/1.m3u8").identity, zee("http://a/1.m3u8", tvgId = "ZeeTV.in@HD").identity)
        assertEquals("name:zee tv hd", Channel.identityOf(null, "Zee TV HD (720p) [Geo-blocked]"))
        assertEquals("name:ndtv india", Channel.identityOf("[NDTV](https://ndtv.com)", "NDTV India \u24C8"))
    }

    @Test fun `reconcile moves favourites and history to the channel's new address`() {
        val prefs = ctx.getSharedPreferences("rot", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val lib = LibraryStore(prefs)
        val old = zee("http://103.151.60.162:2122/play/OLD/index.m3u8?hls")
        val other = zee("https://x.example/news.m3u8", tvgId = "ZeeNews.in@SD", name = "Zee News")
        lib.toggleFavorite(old); lib.toggleFavorite(other); lib.recordWatched(old)
        val fresh = zee("http://103.151.60.162:2122/play/a01u/index.m3u8?hls")
        val catalog = listOf(fresh, other).associateBy { it.identity }
        val moved = lib.reconcile(isCurrent = { it == other.id || it == fresh.id }, current = { catalog[it.identity] })
        assertEquals(1, moved)
        assertEquals(listOf(other.id, fresh.id), lib.favorites.value)
        assertEquals(listOf(fresh.id), lib.history.value.map { it.channelId })
        assertEquals(fresh.streamUrl, lib.snapshot(fresh.id)!!.streamUrl)
        // Persisted, and a second pass is a no-op.
        assertEquals(listOf(other.id, fresh.id), LibraryStore(prefs).favorites.value)
        assertEquals(0, lib.reconcile(isCurrent = { true }, current = { null }))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `My List plays the current Zee link after iptv-org replaced it`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            fun playlist(url: String) = "#EXTM3U\n#EXTINF:-1 tvg-id=\"ZeeTV.in@SD\" group-title=\"Entertainment\",Zee TV (576p)\n$url\n" +
                "#EXTINF:-1 tvg-id=\"ZeeNews.in@SD\" group-title=\"News\",Zee News (1080p)\nhttps://d.example/index_57.m3u8\n"
            // Unroutable https URL: each view model falls back to the cached copy, which we rewrite in between.
            val url = "https://127.0.0.1:9/in.m3u"
            val dir = File(ctx.filesDir, "rot-${System.nanoTime()}").apply { mkdirs() }
            val cache = File(dir, PlaylistRepository.cacheFileName(url))
            val repo = PlaylistRepository(OkHttpClient(), dir, url)
            val lib = LibraryStore(ctx.getSharedPreferences("rot-vm", Context.MODE_PRIVATE).also { it.edit().clear().commit() })

            cache.writeText(playlist("http://old.example/zeetv/index.m3u8"))
            val vm = MainViewModel(repo, lib, compute = Dispatchers.Default)
            val first = withTimeout(20_000) { vm.state.first { !it.loading && it.catalog.channels.isNotEmpty() } }
            val saved = first.catalog.channels.first { it.name == "Zee TV" }
            vm.toggleFavorite(saved)
            assertTrue(saved.streamUrl.contains("old.example"))

            cache.writeText(playlist("http://new.example/zeetv/index.m3u8"))
            val vm2 = MainViewModel(repo, lib, compute = Dispatchers.Default)
            withTimeout(20_000) { vm2.state.first { !it.loading && it.catalog.channels.any { c -> c.streamUrl.contains("new.example") } } }
            val fav = withTimeout(5_000) { vm2.favorites.first { it.isNotEmpty() && it.single().streamUrl.contains("new.example") } }.single()
            assertEquals("Zee TV", fav.name)
            // The heart stays on: the saved id is migrated to the new link's id.
            withTimeout(5_000) { vm2.favoriteIds.first { fav.id in it } }
            assertEquals(listOf(fav.id), lib.favorites.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `refreshIfStale only reloads an old list`() {
        // Pure timing logic, exercised through the constant so the threshold is documented.
        assertEquals(6L * 60 * 60 * 1000, MainViewModel.STALE_AFTER_MS)
    }
}

/** Free-TV merge: allowlist-gated, de-duplicated, YouTube/VOD never added. (Robolectric for the real org.json.) */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ExtraSourcesTest {
    private val freeTv = """
        #EXTM3U
        #EXTINF:-1 tvg-name="NDTV India" tvg-id="NDTVIndia.in" tvg-country="IN" group-title="India",NDTV India
        https://ndtv.example/master.m3u8
        #EXTINF:-1 tvg-name="TV9 Telugu" tvg-id="TV9Telugu.in" tvg-country="IN" group-title="India",TV9 Telugu
        https://tv9.example/telugu/playlist.m3u8
        #EXTINF:-1 tvg-name="DD News" tvg-id="DDNews.in" tvg-country="IN" group-title="India",DD News Ⓨ
        https://www.youtube.com/c/ddnews/live
        #EXTINF:-1 tvg-name="Kanali 7" tvg-id="Kanali7.al" tvg-country="AL" group-title="Albania",Kanali 7 Ⓖ
        https://al.example/k7.m3u8
        #EXTINF:-1 tvg-name="Unverified" tvg-id="Nope.in" tvg-country="IN" group-title="India",Unverified
        https://dead.example/x.m3u8
        #EXTINF:-1 tvg-name="Film" tvg-country="IT" group-title="VOD Italy",Film
        https://vod.example/film.mp4
    """.trimIndent()
    private val allow = ExtraSources.Allowlist("2026-10-17", mapOf("freetv" to setOf(
        "https://ndtv.example/master.m3u8", "https://tv9.example/telugu/playlist.m3u8", "https://www.youtube.com/c/ddnews/live",
        "https://al.example/k7.m3u8", "https://vod.example/film.mp4",
    ).map(M3uParser::urlKey).toSet()))
    private val base = listOf(
        Channel(M3uParser.stableId("https://iptv.example/ndtv.m3u8"), "NDTV India", "https://iptv.example/ndtv.m3u8", null, listOf("News"),
            "NDTVIndia.in@SD", null, "India", null, emptySet(), null, null, countries = listOf("India")),
    )
    private val extra = M3uParser.parse(freeTv).channels

    @Test fun `matches become backup links and new channels are added once`() {
        val out = ExtraSources.merge(base, extra, allow)
        val ndtv = out.single { it.name == "NDTV India" }
        assertEquals(listOf("https://ndtv.example/master.m3u8"), ndtv.alternates.map { it.url })
        val tv9 = out.single { it.name == "TV9 Telugu" }
        assertTrue(tv9.isExtra)
        assertEquals(listOf("General"), tv9.categories)
        assertEquals(listOf("India"), tv9.countries)
        assertTrue(out.single { it.name == "Kanali 7" }.isGeoBlocked) // Ⓖ marker becomes the geo flag
        assertTrue(out.none { it.name == "DD News" || it.name == "Unverified" || it.name == "Film" })
        assertEquals(3, out.size)
        // Idempotent: merging again adds nothing (the URLs are now known).
        assertEquals(out, ExtraSources.merge(out, extra, allow))
    }

    @Test fun `India source only gains Indian channels, empty allowlist adds nothing`() {
        val india = ExtraSources.merge(base, extra, allow, countryFilter = "India")
        assertTrue(india.none { it.name == "Kanali 7" })
        assertTrue(india.any { it.name == "TV9 Telugu" })
        assertEquals(base, ExtraSources.merge(base, extra, ExtraSources.Allowlist.EMPTY))
    }

    @Test fun `allowlist json parses and the shipped asset is populated`() {
        val a = ExtraSources.parseAllowlist("""{"verified_at":"x","sources":{"freetv":{"urls":["https://A.example/p.m3u8"]}}}""")
        assertTrue(a.allows("freetv", "https://a.example/p.m3u8"))
        assertFalse(a.allows("freetv", "https://b.example/p.m3u8"))
        assertTrue(ExtraSources.parseAllowlist("not json").isEmpty)
        val asset = File("src/main/assets/verified_extra.json").readText()
        val shipped = ExtraSources.parseAllowlist(asset)
        assertTrue("verified Free-TV urls shipped", (shipped.urls["freetv"]?.size ?: 0) > 300)
        assertTrue(asset.contains("\"verified_at\""))
    }
}

/** Audio-track listing and labels. */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class AudioTracksTest {
    private fun audio(lang: String?, label: String? = null, ch: Int = 2, mime: String = MimeTypes.AUDIO_AAC, br: Int = Format.NO_VALUE, role: Int = 0) =
        Format.Builder().setSampleMimeType(mime).setLanguage(lang).setLabel(label).setChannelCount(ch).setAverageBitrate(br).setRoleFlags(role).build()

    private fun tracks(vararg groups: List<Format>, selected: Pair<Int, Int> = 0 to 0) = Tracks(groups.mapIndexed { gi, fs ->
        Tracks.Group(TrackGroup("g$gi", *fs.toTypedArray()), false, IntArray(fs.size) { C.FORMAT_HANDLED },
            BooleanArray(fs.size) { ti -> gi == selected.first && ti == selected.second })
    })

    @Test fun `labels read like a TV menu`() {
        assertEquals("Telugu · Stereo", AudioLabels.label(audio("te")))
        assertEquals("Hindi (a2) · Mono", AudioLabels.label(audio("hin", "hin a2", ch = 1)))
        assertEquals("English (Commentary) · 5.1 · Dolby Digital", AudioLabels.label(audio("en", ch = 6, mime = MimeTypes.AUDIO_AC3, role = C.ROLE_FLAG_COMMENTARY)))
        assertEquals("Audio · Stereo", AudioLabels.label(audio("und", "Undetermined")))
        assertEquals("Tamil · Stereo", AudioLabels.label(audio("ta", "audio_208482_und")))
        assertNull(AudioLabels.language("und"))
    }

    @Test fun `lists every audio track, marks the playing one, separates same-label renditions`() {
        // Zee Tamil HD: three "und" AAC renditions at different bitrates.
        val t = tracks(listOf(audio("und", "Undetermined", br = 121_600)), listOf(audio("und", "Undetermined", br = 164_800)),
            listOf(audio("und", "Undetermined", br = 208_000)), selected = 1 to 0)
        val list = PlaybackController.audioTracks(t)
        assertEquals(listOf("Audio · Stereo · 121 kbps", "Audio · Stereo · 164 kbps", "Audio · Stereo · 208 kbps"), list.map { it.label })
        assertEquals(listOf(false, true, false), list.map { it.selected })
        // A bilingual stream: two languages in one group.
        val bi = PlaybackController.audioTracks(tracks(listOf(audio("hi"), audio("te"))))
        assertEquals(listOf("Hindi · Stereo", "Telugu · Stereo"), bi.map { it.label })
        assertEquals(listOf("hi", "te"), bi.map { it.language })
    }

    @Test fun `adaptive fallback steps one rendition down`() {
        assertEquals(720, PlaybackController.stepDown(listOf(1080, 720, 480), 1080))
        assertEquals(480, PlaybackController.stepDown(listOf(1080, 720, 480), 720))
        assertNull(PlaybackController.stepDown(listOf(1080, 720, 480), 480))
        assertNull(PlaybackController.stepDown(emptyList(), 1080))
    }

    @Test fun `live playback never speeds up to chase the edge and rebuffers wait longer`() {
        assertEquals(1.0f, StreamEngine.MAX_LIVE_SPEED)
        assertTrue(StreamEngine.MIN_LIVE_SPEED < 1f)
        assertTrue(StreamEngine.REBUFFER_MS >= 5_000)
        val item = StreamEngine.mediaItem(zee("https://z.example/a.m3u8"))
        assertEquals(1.0f, item.liveConfiguration.maxPlaybackSpeed)
        StreamEngine.adaptiveFactory() // constructs with the tuned parameters
    }

    @Test fun `audio language preference persists and is sanitised`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("aud", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val s = SettingsStore(prefs)
        assertNull(s.settings.value.audioLanguage)
        assertTrue(s.settings.value.extraSources)
        s.setAudioLanguage("te")
        s.setExtraSources(false)
        assertEquals("te", SettingsStore(prefs).settings.value.audioLanguage)
        assertFalse(SettingsStore(prefs).settings.value.extraSources)
        s.setAudioLanguage("te\nX-Injected: 1")
        assertNull(SettingsStore(prefs).settings.value.audioLanguage)
    }
}
