package app.show.tv

import app.show.tv.data.Channel
import app.show.tv.data.ChannelCatalog
import app.show.tv.data.M3uParser
import app.show.tv.data.StreamProtocol
import app.show.tv.player.PlaybackIssue
import app.show.tv.player.PlaybackQueue
import app.show.tv.player.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun channel(name: String, url: String = "https://s.example/${name.hashCode()}.m3u8", cats: List<String> = listOf("News"), logo: String? = "https://l.example/$name.png", country: String? = null, res: String? = null, flags: Set<String> = emptySet()) =
    Channel(M3uParser.stableId(url), name, url, logo, cats, null, null, country, res, flags, null, null)

class StreamProtocolTest {
    @Test fun classifies() {
        assertEquals(StreamProtocol.HLS, StreamProtocol.of("https://a/b/playlist.m3u8?token=1"))
        assertEquals(StreamProtocol.HLS, StreamProtocol.of("HTTP://A/LIVE/INDEX.M3U8"))
        assertEquals(StreamProtocol.DASH, StreamProtocol.of("https://a/manifest.mpd"))
        assertEquals(StreamProtocol.SMOOTH, StreamProtocol.of("https://a/ch.ism/Manifest"))
        assertEquals(StreamProtocol.RTSP, StreamProtocol.of("rtsp://cam/live"))
        assertEquals(StreamProtocol.RTMP, StreamProtocol.of("rtmp://a/live/x"))
        assertEquals(StreamProtocol.MMS, StreamProtocol.of("mmsh://a/x"))
        assertEquals(StreamProtocol.SRT, StreamProtocol.of("srt://a:9000"))
        assertEquals(StreamProtocol.UDP_RTP, StreamProtocol.of("udp://@239.0.0.1:1234"))
        assertEquals(StreamProtocol.PROGRESSIVE, StreamProtocol.of("https://a/stream"))
        assertEquals(StreamProtocol.UNKNOWN, StreamProtocol.of("garbage"))
        assertFalse(StreamProtocol.MMS.playableInApp)
        assertTrue(StreamProtocol.RTMP.playableInApp)
    }
}

class ChannelCatalogTest {
    private val cat = ChannelCatalog(
        listOf(
            channel("BBC News", cats = listOf("News"), country = "UK", res = "1080p"),
            channel("CNN International", cats = listOf("News")),
            channel("Café Música", cats = listOf("Music"), country = "ES"),
            channel("News Kids", cats = listOf("Kids", "News")),
            channel("Mystery", cats = listOf(Channel.UNCATEGORISED), logo = null),
        ),
    )

    @Test fun `categories ordered by size, Undefined last`() {
        assertEquals(listOf("News", "Music", "Kids", Channel.UNCATEGORISED), cat.categories)
        assertEquals(3, cat.inCategory("News").size)
        assertEquals(5, cat.inCategory(null).size)
    }

    @Test fun `search ranks exact and prefix matches first`() {
        assertEquals("News Kids", cat.search("news kids").first().name)
        assertEquals(listOf("News Kids", "BBC News"), cat.search("news").take(2).map { it.name })
    }

    @Test fun `search is accent and case insensitive and matches country`() {
        assertEquals("Café Música", cat.search("CAFE musica").single().name)
        assertEquals("Café Música", cat.search("es").first { it.country == "ES" }.name)
        assertEquals("BBC News", cat.search("uk").single().name)
    }

    @Test fun `search respects category filter and blank query`() {
        assertEquals(listOf("News Kids"), cat.search("news", category = "Kids").map { it.name })
        assertTrue(cat.search("   ").isEmpty())
    }

    @Test fun `featured prefers artwork and is deterministic per seed`() {
        val a = cat.featured(42)
        assertEquals(a, cat.featured(42))
        assertTrue(a.none { it.logoUrl == null })
    }

    @Test fun `browse combines category, country and exclusions`() {
        assertEquals(listOf("UK", "ES"), cat.countries)
        assertEquals(listOf("BBC News"), cat.browse("News", "UK").map { it.name })
        val bbc = cat.search("bbc").single()
        assertEquals(2, cat.browse("News", null, exclude = setOf(bbc.id)).size)
        assertEquals(5, cat.browse(null, null).size)
    }

    @Test fun `rows put logo channels first`() {
        val row = ChannelCatalog(listOf(channel("NoLogo", logo = null), channel("Logo"))).rowFor("News")
        assertEquals("Logo", row.first().name)
    }

    @Test fun `search over the real catalog is fast`() {
        val p = javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u").bufferedReader().use(M3uParser::parse)
        val big = ChannelCatalog(p.channels)
        val t = System.nanoTime()
        repeat(20) { big.search("news") }
        val avg = (System.nanoTime() - t) / 20 / 1_000_000
        assertTrue(big.search("news").isNotEmpty())
        assertTrue("avg search $avg ms", avg < 150)
    }
}

class PlaybackIssueTest {
    @Test fun `http codes map to viewer messages and retry policy`() {
        assertEquals(PlaybackIssue.Kind.FORBIDDEN, PlaybackIssue.fromHttpCode(403).kind)
        assertFalse(PlaybackIssue.fromHttpCode(403).autoRetry)
        assertEquals(PlaybackIssue.Kind.NOT_FOUND, PlaybackIssue.fromHttpCode(404).kind)
        assertTrue(PlaybackIssue.fromHttpCode(503).autoRetry)
        assertTrue(PlaybackIssue.fromHttpCode(429).autoRetry)
        assertEquals(PlaybackIssue.Kind.UNSUPPORTED_PROTOCOL, PlaybackIssue.unsupportedProtocol("MMS").kind)
    }

    @Test fun `retry policy backs off exponentially then gives up`() {
        val r = RetryPolicy(maxAttempts = 3, baseDelayMs = 1000)
        assertEquals(1000L, r.nextDelayMs())
        assertEquals(2000L, r.nextDelayMs())
        assertEquals(4000L, r.nextDelayMs())
        assertNull(r.nextDelayMs())
        r.reset()
        assertEquals(1000L, r.nextDelayMs())
    }
}

class PlaybackQueueTest {
    @Test fun `window keeps chosen channel and caps size`() {
        val many = (0 until 1000).map { channel("C$it", url = "https://q.example/$it.m3u8") }
        val w = PlaybackQueue.buildWindow(many[900], many)
        assertEquals(200, w.size)
        assertTrue(many[900] in w)
        val single = PlaybackQueue.buildWindow(channel("Solo", url = "https://q.example/solo"), many.take(3))
        assertEquals("Solo", single.first().name)
    }
}
