package app.show.tv

import app.show.tv.data.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class PlaylistRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\nhttps://a.example/1.m3u8\n#EXTINF:-1,Two\nhttps://a.example/2.m3u8\n"

    @Before fun setUp() {
        val localhost = InetAddress.getByName("localhost").canonicalHostName
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(localhost).build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false); start() }
        client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
    }

    @After fun tearDown() = server.shutdown()

    private fun repo(url: String = server.url("/index.m3u").toString(), max: Long = 1024 * 1024) =
        PlaylistRepository(client, tmp.root, url, max, Dispatchers.Unconfined)

    @Test fun `downloads, caches and uses etag for conditional refresh`() = runBlocking {
        server.enqueue(MockResponse().setBody(m3u).setHeader("ETag", "\"v1\""))
        val first = repo().refresh()
        assertEquals(PlaylistRepository.Source.Network, first.source)
        assertEquals(2, first.playlist.channels.size)

        server.enqueue(MockResponse().setResponseCode(304))
        val second = repo().refresh()
        assertEquals(PlaylistRepository.Source.NotModified, second.source)
        assertEquals(2, second.playlist.channels.size)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun `falls back to cache when the network fails`() = runBlocking {
        server.enqueue(MockResponse().setBody(m3u))
        repo().refresh()
        server.enqueue(MockResponse().setResponseCode(500))
        val r = repo().refresh()
        assertTrue(r.source is PlaylistRepository.Source.Cache)
        assertEquals(2, r.playlist.channels.size)
        assertEquals(2, repo().loadCached()!!.playlist.channels.size)
    }

    @Test fun `throws when nothing cached and server errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try { repo().refresh(); fail("expected failure") } catch (e: PlaylistRepository.PlaylistException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test fun `refuses cleartext playlist urls`() = runBlocking {
        try { repo(url = "http://iptv-org.github.io/iptv/index.category.m3u").refresh(); fail() } catch (e: PlaylistRepository.PlaylistException) {
            assertTrue(e.message!!.contains("cleartext"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `enforces size cap and keeps previous good copy`() = runBlocking {
        server.enqueue(MockResponse().setBody(m3u))
        repo().refresh()
        val huge = buildString { append("#EXTM3U\n"); repeat(40_000) { append("#EXTINF:-1,C$it\nhttps://h.example/$it.m3u8\n") } }
        server.enqueue(MockResponse().setBody(huge))
        val r = repo(max = 64 * 1024).refresh()
        assertTrue(r.source is PlaylistRepository.Source.Cache)
        assertEquals(2, r.playlist.channels.size)
    }

    @Test fun `each playlist source has its own cache`() = runBlocking {
        server.enqueue(MockResponse().setBody(m3u))
        repo(url = server.url("/a.m3u").toString()).refresh()
        val other = repo(url = server.url("/b.m3u").toString())
        assertEquals(null, other.loadCached())
        server.enqueue(MockResponse().setResponseCode(500))
        try { other.refresh(); fail("b has no cache to fall back on") } catch (_: PlaylistRepository.PlaylistException) {}
    }

    @Test fun `refuses an https playlist that redirects to cleartext`() = runBlocking {
        // A client that (unlike the app's) follows cross-scheme redirects proves the repository's own guard.
        val plain = MockWebServer().apply { enqueue(MockResponse().setBody(m3u)); start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", plain.url("/evil.m3u")))
            val lax = client.newBuilder().followSslRedirects(true).build()
            val r = PlaylistRepository(lax, tmp.root, server.url("/index.m3u").toString(), 1024 * 1024, Dispatchers.Unconfined)
            try { r.refresh(); fail() } catch (e: PlaylistRepository.PlaylistException) { assertTrue(e.message!!.contains("insecure")) }
        } finally { plain.shutdown() }
    }

    @Test fun `rejects a playlist with no channels`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        try { repo().refresh(); fail() } catch (e: PlaylistRepository.PlaylistException) {
            assertTrue(e.message!!.contains("no playable"))
        }
    }

    // 1.5.3
    @Test fun `secondary indexes are not re-requested while their copy is fresh`() = runBlocking {
        val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\nhttps://a.example/1.m3u8\n"
        server.enqueue(MockResponse().setBody(m3u))
        val url = server.url("/index.country.m3u").toString()
        val repo = PlaylistRepository(client, tmp.root, url, 1024 * 1024, Dispatchers.Unconfined)
        assertEquals(1, repo.refreshIfOlderThan(60_000).playlist.channels.size)
        assertEquals(1, server.requestCount)
        val again = repo.refreshIfOlderThan(60_000)
        assertEquals(1, again.playlist.channels.size)
        assertEquals("fresh copy used without a request", 1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(304))
        repo.refreshIfOlderThan(0)
        assertEquals("stale copy is re-checked", 2, server.requestCount)
    }
}
