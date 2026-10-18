package app.show.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.Channel
import app.show.tv.data.Embeds
import app.show.tv.data.ExtraSources
import app.show.tv.data.M3uParser
import app.show.tv.data.StreamProtocol
import app.show.tv.player.EmbedPlayerActivity
import app.show.tv.player.PlayerActivity
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** 1.5.4: Free-TV re-verification, remote allowlist, YouTube/Dailymotion official embeds. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class Release154Test {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient()
    @After fun tearDown() = server.shutdown()

    // 1.5.4: remote allowlist refresh
    private fun allow(epoch: Long, vararg urls: String) =
        """{"verified_epoch": $epoch, "sources": {"freetv": {"urls": [${urls.joinToString(",") { "\"$it\"" }}]}}}"""

    @Test fun `allowlist store prefers a newer remote copy and caches it for a day`() {
        server.enqueue(MockResponse().setBody(allow(200, "https://a.example/new.m3u8")))
        var now = 1_000_000L
        val store = app.show.tv.data.AllowlistStore(client, tmp.newFolder("al"), { allow(100, "https://a.example/old.m3u8") },
            remoteUrl = server.url("/verified_extra.json").toString(), clock = { now }, requireHttps = false)
        val first = store.current()
        assertTrue(first.allows("freetv", "https://a.example/new.m3u8"))
        assertTrue(!first.allows("freetv", "https://a.example/old.m3u8"))
        now += 60_000
        store.current()
        assertEquals("cached within the day", 1, server.requestCount)
        // A new store (app restart) reads the cache instead of fetching again.
        val restarted = app.show.tv.data.AllowlistStore(client, java.io.File(tmp.root, "al"), { allow(100, "https://a.example/old.m3u8") },
            remoteUrl = server.url("/verified_extra.json").toString(), clock = { now }, requireHttps = false).current()
        assertTrue(restarted.allows("freetv", "https://a.example/new.m3u8"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `allowlist store keeps the bundled copy when the remote is older, broken or oversized`() {
        val dir = tmp.newFolder("al2")
        val bundled = { allow(300, "https://a.example/bundled.m3u8") }
        server.enqueue(MockResponse().setBody(allow(200, "https://a.example/older.m3u8")))
        val older = app.show.tv.data.AllowlistStore(client, dir, bundled, remoteUrl = server.url("/a").toString(), requireHttps = false).current()
        assertTrue(older.allows("freetv", "https://a.example/bundled.m3u8"))

        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        val broken = app.show.tv.data.AllowlistStore(client, tmp.newFolder("al3"), bundled, remoteUrl = server.url("/b").toString(), requireHttps = false).current()
        assertTrue(broken.allows("freetv", "https://a.example/bundled.m3u8"))

        server.enqueue(MockResponse().setBody("x".repeat(app.show.tv.data.AllowlistStore.MAX_BYTES + 10)))
        val huge = app.show.tv.data.AllowlistStore(client, tmp.newFolder("al4"), bundled, remoteUrl = server.url("/c").toString(), requireHttps = false).current()
        assertTrue(huge.allows("freetv", "https://a.example/bundled.m3u8"))

        server.enqueue(MockResponse().setResponseCode(500))
        val down = app.show.tv.data.AllowlistStore(client, tmp.newFolder("al5"), bundled, remoteUrl = server.url("/d").toString(), requireHttps = false).current()
        assertTrue(down.allows("freetv", "https://a.example/bundled.m3u8"))
    }

    @Test fun `allowlist store refuses a non-https remote`() {
        val plain = app.show.tv.data.AllowlistStore(client, tmp.newFolder("al6"), { allow(1, "https://a.example/b.m3u8") },
            remoteUrl = "http://example.com/verified_extra.json").current()
        assertTrue(plain.allows("freetv", "https://a.example/b.m3u8"))
        assertEquals(0, server.requestCount)
    }

    private val ytCh = "https://www.youtube.com/embed/live_stream?channel=UChR-A__NS_C5kHDWj3PeAhw"
    private val dm = "https://geo.dailymotion.com/player.html?video=x3b68jn"

    @Test fun `only the three official embed shapes are accepted`() {
        assertEquals(Embeds.Kind.YOUTUBE, Embeds.parse(ytCh)?.kind)
        assertTrue(Embeds.parse(ytCh)!!.isChannel)
        assertEquals("dQw4w9WgXcQ", Embeds.parse("https://www.youtube.com/embed/dQw4w9WgXcQ")?.id)
        assertEquals(Embeds.Kind.DAILYMOTION, Embeds.parse(dm)?.kind)
        listOf(
            "http://www.youtube.com/embed/dQw4w9WgXcQ",                         // cleartext
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",                        // a page, not an embed
            "https://www.youtube.com/embed/dQw4w9WgXcQ\"><script>",                 // injection
            "https://evil.example/embed/live_stream?channel=UChR-A__NS_C5kHDWj3PeAhw",
            "https://geo.dailymotion.com/player.html?video=x3b68jn&x=\"",
            "https://www.youtube.com/embed/live_stream?channel=UC123",           // malformed id
        ).forEach { assertNull(it, Embeds.parse(it)) }
    }

    @Test fun `embed channels get their own protocol and never reach Media3`() {
        assertEquals(StreamProtocol.EMBED, StreamProtocol.of(dm))
        assertEquals(StreamProtocol.HLS, StreamProtocol.of("https://a.example/live.m3u8"))
        val html = Embeds.html(Embeds.parse(ytCh)!!)
        assertTrue(html.contains("live_stream?channel=UChR-A__NS_C5kHDWj3PeAhw"))
        assertTrue(html.contains("origin=${Embeds.ORIGIN}"))
        assertTrue(html.contains("iframe_api"))
        assertTrue(Embeds.html(Embeds.parse(dm)!!).contains("geo.dailymotion.com/player.html?video=x3b68jn"))
        assertEquals("This channel isn't live right now.", Embeds.errorText(100))
    }

    private fun ch(name: String, url: String, country: String = "France", tvg: String? = null) = Channel(
        id = M3uParser.stableId(url), name = name, streamUrl = url, logoUrl = null, categories = listOf("France"),
        tvgId = tvg, language = null, country = country, resolution = null, flags = emptySet(), userAgent = null, referrer = null,
        countries = listOf(country), languages = emptyList(),
    )

    private val allow = ExtraSources.parseAllowlist("""{"sources":{"freetv":{"urls":[],"embeds":[
        {"source_url":"https://www.dailymotion.com/video/x3b68jn","embed_url":"$dm","kind":"dailymotion"},
        {"source_url":"https://www.youtube.com/@france24/live","embed_url":"https://www.youtube.com/embed/live_stream?channel=UCQfwfsi5VrQ8yKZ-UWmAEFg","kind":"youtube"},
        {"source_url":"https://www.youtube.com/@bad/live","embed_url":"https://evil.example/x","kind":"youtube"}
    ]}}}""")

    @Test fun `verified embeds are added once, natives win, unverified and malformed are dropped`() {
        val base = listOf(ch("France 24", "https://f24.example/live.m3u8"))
        val extra = listOf(
            ch("CNews", "https://www.dailymotion.com/video/x3b68jn"),
            ch("France 24", "https://www.youtube.com/@france24/live"),    // duplicate of a native channel
            ch("Bad", "https://www.youtube.com/@bad/live"),               // embed_url not an official shape
            ch("Unverified", "https://www.youtube.com/@nobody/live"),     // not in the allowlist
        )
        val merged = ExtraSources.merge(base, extra, allow)
        assertEquals(listOf("France 24", "CNews"), merged.map { it.name })
        val cnews = merged.last()
        assertEquals(dm, cnews.streamUrl)
        assertTrue(cnews.isEmbed)
        assertTrue("Dailymotion" in cnews.flags && Channel.EXTRA_FLAG in cnews.flags)
        assertTrue(merged.first().alternates.isEmpty()) // never a Media3 backup link
        assertEquals(1, ExtraSources.merge(base, extra, allow, includeEmbeds = false).size)
        assertEquals(1, ExtraSources.merge(base, extra, allow, countryFilter = "India").size)
    }

    @Test fun `playing an embed opens the embedded player and keeps it out of the zapping queue`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val native = ch("Rai 1", "https://rai.example/1.m3u8", "Italy")
        val embed = ch("CNews", dm)
        PlayerActivity.start(ctx, embed, listOf(native, embed))
        val first = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(EmbedPlayerActivity::class.java.name, first.component?.className)
        PlayerActivity.start(ctx, native, listOf(native, embed))
        val second = Shadows.shadowOf(ctx).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, second.component?.className)
        val queue = app.show.tv.player.PlaybackQueue.take(second)
        assertNotNull(queue)
        assertFalse(queue!!.first.any { it.isEmbed })
    }

    @Test fun `bundled allowlist is the v2 re-verification with no web pages or DRM`() {
        val text = java.io.File("src/main/assets/verified_extra.json").readText()
        val json = org.json.JSONObject(text)
        assertTrue(json.optInt("verifier") >= 2 && json.optLong("verified_epoch") > 0)
        val urls = json.getJSONObject("sources").getJSONObject("freetv").getJSONArray("urls")
        val all = (0 until urls.length()).map { urls.getString(it) }
        assertTrue(all.size > 500)
        assertFalse(all.any { it.contains("lcp.fr/le-live") || it == "https://citizentv.co.ke/live" })
    }
}
