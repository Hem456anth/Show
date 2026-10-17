package app.show.tv

import android.content.Context
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.Channel
import app.show.tv.data.LibraryStore
import app.show.tv.player.PlaybackController
import app.show.tv.player.PlaybackIssue
import app.show.tv.player.RetryPolicy
import app.show.tv.player.StreamEngine
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Drives the real Media3 pipeline against a local server to verify headers and recovery. */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackControllerTest {
    private lateinit var server: MockWebServer
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine by lazy { StreamEngine(context, OkHttpClient(), segmentRetries = 1, retryBaseDelayMs = 10) }
    private val controllers = mutableListOf<PlaybackController>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { controllers.forEach { it.release() }; server.shutdown() }

    private fun ch(path: String, ua: String? = null, ref: String? = null, name: String = path) =
        Channel("id$path", name, server.url(path).toString(), null, listOf("News"), null, null, null, null, emptySet(), ua, ref)

    private fun controller(vararg queue: Channel, retry: RetryPolicy = RetryPolicy(0)): PlaybackController =
        PlaybackController(engine, engine.buildPlayer(), queue.toList(), 0, retry = retry).also { controllers += it }

    /** Pump the main looper (and real network threads) until [cond] or timeout. */
    private fun waitUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    @Test fun `sends channel user agent and referrer like VLC http options`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/live/a.m3u8", ua = "TestUA/1.0", ref = "https://broadcaster.example/watch"))
        c.start()
        val req = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("TestUA/1.0", req.getHeader("User-Agent"))
        assertEquals("https://broadcaster.example/watch", req.getHeader("Referer"))
        assertEquals(null, req.getHeader("Origin")) // VLC sends Referer only; Origin triggers CORS 403s on some CDNs
    }

    @Test fun `default user agent identifies the app`() {
        server.enqueue(MockResponse().setResponseCode(404))
        controller(ch("/b.m3u8")).start()
        assertTrue(server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("User-Agent")!!.startsWith("Show/"))
    }

    @Test fun `404 surfaces offline channel without auto retry`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/gone.m3u8"))
        c.start()
        waitUntil { c.state.value.issue != null }
        assertEquals(PlaybackIssue.Kind.NOT_FOUND, c.state.value.issue!!.kind)
        assertFalse(c.state.value.retrying)
    }

    @Test fun `403 is reported as region restricted`() {
        server.enqueue(MockResponse().setResponseCode(403))
        val c = controller(ch("/geo.m3u8"))
        c.start()
        waitUntil { c.state.value.issue != null }
        assertEquals(PlaybackIssue.Kind.FORBIDDEN, c.state.value.issue!!.kind)
    }

    @Test fun `server errors schedule backoff retries then recover on manual retry`() {
        repeat(30) { server.enqueue(MockResponse().setResponseCode(503)) }
        val c = controller(ch("/flaky.m3u8"), retry = RetryPolicy(maxAttempts = 2, baseDelayMs = 1_000))
        c.start()
        waitUntil { c.state.value.issue != null }
        assertEquals(PlaybackIssue.Kind.SERVER, c.state.value.issue!!.kind)
        assertTrue(c.state.value.retrying)
        waitUntil(30_000) { c.state.value.issue != null && !c.state.value.retrying }
        assertTrue("retried at least twice", server.requestCount >= 3)
        val before = server.requestCount
        c.retryNow()
        waitUntil { server.requestCount > before }
    }

    @Test fun `unsupported protocols are not handed to the engine`() {
        val mms = Channel("mms", "MMS radio", "mmsh://radio.example/live", null, listOf("Music"), null, null, null, null, emptySet(), null, null)
        val c = controller(mms)
        c.start()
        assertEquals(PlaybackIssue.Kind.UNSUPPORTED_PROTOCOL, c.state.value.issue!!.kind)
    }

    @Test fun `zapping moves through the queue and wraps`() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(404)) }
        val c = controller(ch("/1.m3u8", name = "One"), ch("/2.m3u8", name = "Two"), ch("/3.m3u8", name = "Three"))
        c.start()
        c.next(); assertEquals("Two", c.state.value.channel.name)
        c.next(); c.next(); assertEquals("One", c.state.value.channel.name)
        c.previous(); assertEquals("Three", c.state.value.channel.name)
        assertNotNull(c.state.value)
    }

    @Test fun `behind live window re-prepares at live edge instead of erroring`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/live.m3u8"))
        c.start()
        c.onError(PlaybackException("blw", null, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(null, c.state.value.issue)
    }

    @Test fun `dead links are reported so browsing can flag them`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val failed = mutableListOf<String>()
        val c = PlaybackController(engine, engine.buildPlayer(), listOf(ch("/dead.m3u8")), 0, retry = RetryPolicy(0),
            onChannelFailed = { chn, _ -> failed += chn.id })
        controllers += c
        c.start()
        waitUntil { failed.isNotEmpty() }
        assertEquals(listOf("id/dead.m3u8"), failed)
    }

    @Test fun `sleep timer pauses playback and can be cancelled`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/sleep.m3u8"))
        c.start()
        c.setSleepTimer(1)
        assertTrue(c.state.value.sleepAt != null)
        shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
        assertTrue(c.state.value.sleptByTimer)
        assertFalse(c.player.playWhenReady)
        c.resumeAfterSleep()
        assertFalse(c.state.value.sleptByTimer)
        c.setSleepTimer(5); c.setSleepTimer(null)
        assertEquals(null, c.state.value.sleepAt)
    }

    @Test fun `quality list is derived from supported video tracks only`() {
        assertTrue(PlaybackController.videoHeights(androidx.media3.common.Tracks.EMPTY).isEmpty())
    }

    @Test fun `history records started channels`() {
        val store = LibraryStore(context.getSharedPreferences("t", Context.MODE_PRIVATE))
        server.enqueue(MockResponse().setResponseCode(404))
        val c = PlaybackController(engine, engine.buildPlayer(), listOf(ch("/h.m3u8")), 0, onChannelStarted = { store.recordWatched(it.id) })
        controllers += c
        c.start()
        assertEquals("id/h.m3u8", store.history.value.single().channelId)
    }
}
