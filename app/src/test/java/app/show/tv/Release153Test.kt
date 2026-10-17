package app.show.tv

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.Channel
import app.show.tv.data.PlaylistRepository
import app.show.tv.player.PlaybackController
import app.show.tv.player.RetryPolicy
import app.show.tv.player.StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * 1.5.3 battery / background regressions (docs/AUDIT_1.5.3.md). Before 1.5.3 a stalled or failing
 * stream kept reconnecting after the player screen was left, and every reconnect called play(), so
 * audio could start again in the background with the network wake lock held.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class Release153Test {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine by lazy { StreamEngine(context, OkHttpClient(), segmentRetries = 0, retryBaseDelayMs = 10) }
    private val controllers = mutableListOf<PlaybackController>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { controllers.forEach { it.release() }; server.shutdown() }

    private fun ch(path: String) =
        Channel("id$path", path, server.url(path).toString(), null, listOf("News"), null, null, null, null, emptySet(), null, null)

    private fun controller(c: Channel, retry: RetryPolicy = RetryPolicy(0), stallMs: Long = 20_000) =
        PlaybackController(engine, engine.buildPlayer(), listOf(c), 0, retry = retry, stallTimeoutMs = stallMs).also { controllers += it }

    private fun pump(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun waitUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    @Test fun `leaving the player cancels pending retries and stops loading`() {
        repeat(40) { server.enqueue(MockResponse().setResponseCode(503)) }
        val c = controller(ch("/flaky.m3u8"), retry = RetryPolicy(maxAttempts = 10, baseDelayMs = 300))
        c.start()
        waitUntil { c.state.value.retrying }
        c.onBackground()
        val before = server.requestCount
        pump(4_000)
        assertEquals("no reconnects while in the background", before, server.requestCount)
        assertEquals(Player.STATE_IDLE, c.player.playbackState)
        assertFalse(c.player.playWhenReady)
        assertFalse(c.isForeground)
        // Coming back reconnects straight away.
        c.onForeground()
        waitUntil { server.requestCount > before }
        assertTrue(c.player.playWhenReady)
    }

    @Test fun `a stall while in the background never reconnects`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
        val c = controller(ch("/slow.m3u8"), retry = RetryPolicy(maxAttempts = 5, baseDelayMs = 100), stallMs = 500)
        c.start()
        waitUntil { server.requestCount >= 1 }
        c.onBackground()
        pump(3_000)
        assertNull("no stall issue raised in the background", c.state.value.issue)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a final error stays on screen after returning`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/gone.m3u8"))
        c.start()
        waitUntil { c.state.value.issue != null }
        c.onBackground(); c.onForeground()
        pump(1_000)
        assertEquals(1, server.requestCount)
        assertTrue(c.state.value.issue != null)
    }

    @Test fun `network callbacks are ignored in the background`() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(503)) }
        val c = controller(ch("/net.m3u8"), retry = RetryPolicy(maxAttempts = 5, baseDelayMs = 5_000))
        c.start()
        waitUntil { c.state.value.retrying }
        c.onBackground()
        val before = server.requestCount
        c.onNetworkAvailable()
        pump(1_000)
        assertEquals(before, server.requestCount)
    }

    @Test fun `background and foreground are idempotent and safe after release`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(ch("/x.m3u8"))
        c.start()
        c.onBackground(); c.onBackground()
        c.release()
        c.onForeground(); c.onNetworkAvailable() // must not throw on a released player
    }
}
