package app.show.tv

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.vod.BufferPreset
import app.show.tv.vod.RenditionHealth
import app.show.tv.vod.VodErrors
import app.show.tv.vod.VodRetry
import app.show.tv.vod.VodSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** 1.9.0: buffer presets, same-rendition retry policy, rendition health memory, error messages. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class Release190Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(n: String) = ctx.getSharedPreferences(n, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun `every buffer preset builds a valid load control and the setting persists`() {
        BufferPreset.entries.forEach { b -> b.loadControl(); assertTrue(b.minMs <= b.maxMs) }
        assertEquals(BufferPreset.BALANCED, BufferPreset.parse(null))
        assertEquals(BufferPreset.BALANCED, BufferPreset.parse("junk"))
        val p = prefs("vod_buf")
        val s = VodSettings(p)
        assertEquals(BufferPreset.BALANCED, s.player.value.buffer)
        s.setBufferPreset(BufferPreset.SMOOTH)
        assertEquals(BufferPreset.SMOOTH, s.player.value.buffer)
        assertEquals(BufferPreset.SMOOTH, VodSettings(p).player.value.buffer)
    }

    @Test fun `only transient failures retry, with 1-2-4 s backoff`() {
        assertTrue(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertTrue(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertTrue(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertTrue(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 429))
        assertFalse(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertFalse(VodRetry.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertFalse(VodRetry.isTransient(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null))
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (0..2).map { VodRetry.delayMs(it) })
        assertNull(VodRetry.delayMs(3))
    }

    @Test fun `renditions that fail twice are tried last for 14 days and one success clears them`() {
        var now = 1_000_000L
        val h = RenditionHealth(prefs("vod_h"), { now })
        val urls = listOf("https://a/hd.mp4", "https://a/sd.mp4")
        assertEquals(0, h.firstHealthy(urls, 0))
        h.markFailed(urls[0]); assertEquals("one failure is not enough", 0, h.firstHealthy(urls, 0))
        h.markFailed(urls[0]); assertEquals(1, h.firstHealthy(urls, 0))
        h.markFailed(urls[1]); h.markFailed(urls[1])
        assertEquals("all suspect -> keep the preferred one", 0, h.firstHealthy(urls, 0))
        h.markOk(urls[1]); assertEquals(1, h.firstHealthy(urls, 0))
        now += RenditionHealth.WINDOW_MS + 1
        assertEquals("expired after the window", 0, h.firstHealthy(urls, 0))
        assertEquals(0, h.firstHealthy(emptyList(), 3))
    }

    @Test fun `errors name the http status in plain words`() {
        assertTrue(VodErrors.message(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404).contains("gone"))
        assertTrue(VodErrors.message(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502).contains("HTTP 502"))
        assertTrue(VodErrors.message(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403).contains("403"))
        assertEquals("Connection lost. Check your network and try again.", VodErrors.message(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertTrue(VodErrors.message(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null).contains("decoder"))
    }
}
