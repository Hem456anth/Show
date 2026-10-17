package app.show.tv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.LibraryStore
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.SettingsStore
import app.show.tv.player.PlaybackQueue
import app.show.tv.player.PlayerActivity
import app.show.tv.player.StreamEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * Regression suite for "tapping Play navigates straight back".
 *
 * Root cause: StreamEngine enables ExoPlayer's WAKE_MODE_NETWORK, whose WakeLockManager calls
 * PowerManager.WakeLock.acquire() without catching SecurityException. With no WAKE_LOCK
 * permission in the manifest that throws the moment playback starts, the process dies and
 * Android restores the previous activity, which looks like the app navigating back.
 * Robolectric doesn't enforce permissions, so the manifest is asserted directly.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34])
class PlayerLaunchRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<ShowApp>()

    @Before fun graph() { app.graph = AppGraph(app, playlistUrl = "https://127.0.0.1:9/offline.m3u") }

    @Test fun `manifest declares every permission the player's wake mode needs`() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions.orEmpty().toSet()
        assertTrue("WAKE_LOCK missing: ExoPlayer wake mode would crash playback", "android.permission.WAKE_LOCK" in perms)
        assertTrue("android.permission.INTERNET" in perms)
        assertTrue("android.permission.ACCESS_NETWORK_STATE" in perms)
    }

    @Test fun `opening the player keeps it on screen and starts playback`() {
        val ch = channel("Regression One", url = "https://127.0.0.1:9/live.m3u8")
        val intent = PlaybackQueue.intent(app, ch, listOf(ch, channel("Two", url = "https://127.0.0.1:9/two.m3u8")))
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertFalse("player must not finish on its own", activity.isFinishing)
        assertEquals(ch.id, activity.currentController()!!.current.id)
        assertTrue(activity.currentController()!!.player.playWhenReady)
        assertEquals(ch.id, app.graph.libraryStore.history.value.first().channelId)
        controller.pause().stop().destroy()
    }

    @Test fun `a new channel picked while the player is open re-targets it`() {
        val a = channel("Alpha", url = "https://127.0.0.1:9/a.m3u8")
        val b = channel("Beta", url = "https://127.0.0.1:9/b.m3u8")
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, PlaybackQueue.intent(app, a, listOf(a))).setup()
        controller.newIntent(PlaybackQueue.intent(app, b, listOf(b)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Beta", controller.get().currentController()!!.current.name)
        controller.pause().stop().destroy()
    }

    @Test fun `tv remote channel keys zap and Last recalls the previous channel`() {
        val a = channel("Alpha", url = "https://127.0.0.1:9/a.m3u8")
        val b = channel("Beta", url = "https://127.0.0.1:9/b.m3u8")
        val c = channel("Gamma", url = "https://127.0.0.1:9/c.m3u8")
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, PlaybackQueue.intent(app, a, listOf(a, b, c))).setup()
        val act = controller.get()
        fun press(code: Int) { act.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)); shadowOf(Looper.getMainLooper()).idle() }
        press(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals("Beta", act.currentController()!!.current.name)
        press(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals("Gamma", act.currentController()!!.current.name)
        press(android.view.KeyEvent.KEYCODE_LAST_CHANNEL)
        assertEquals("Beta", act.currentController()!!.current.name)
        press(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)
        assertEquals("Alpha", act.currentController()!!.current.name)
        controller.pause().stop().destroy()
    }

    @Test fun `manifest is installable on Android TV`() {
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, PackageManager.GET_CONFIGURATIONS)
        val features = info.reqFeatures.orEmpty().associate { it.name to (it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0) }
        assertEquals(false, features["android.software.leanback"])
        assertEquals(false, features["android.hardware.touchscreen"])
        val tv = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(app.packageName)
        assertTrue(pm.queryIntentActivities(tv, 0).isNotEmpty())
    }

    @Test fun `an unusable intent closes the player instead of crashing`() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, android.content.Intent(app, PlayerActivity::class.java)).setup()
        assertTrue(controller.get().isFinishing)
    }
}

@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class SettingsAndHealthTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun `playlist url validation`() {
        val s = SettingsStore(prefs("s1"))
        assertTrue(s.setPlaylistUrl("http://insecure.example/list.m3u") is SettingsStore.UrlResult.Invalid)
        assertTrue(s.setPlaylistUrl("not a url") is SettingsStore.UrlResult.Invalid)
        assertTrue(s.setPlaylistUrl("https://user:pw@x.example/l.m3u") is SettingsStore.UrlResult.Invalid)
        assertEquals(PlaylistRepository.DEFAULT_PLAYLIST_URL, s.playlistUrl)
        assertEquals(SettingsStore.UrlResult.Ok, s.setPlaylistUrl("  https://my.example/tv.m3u  "))
        assertEquals("https://my.example/tv.m3u", SettingsStore(ctx.getSharedPreferences("s1", Context.MODE_PRIVATE)).playlistUrl)
        assertEquals(SettingsStore.UrlResult.Ok, s.setPlaylistUrl(""))
        assertFalse(s.settings.value.isCustomPlaylist)
    }

    @Test fun `toggles persist`() {
        val s = SettingsStore(prefs("s2"))
        s.setHideGeoBlocked(true); s.setHideUnavailable(true)
        val r = SettingsStore(ctx.getSharedPreferences("s2", Context.MODE_PRIVATE)).settings.value
        assertTrue(r.hideGeoBlocked && r.hideUnavailable)
    }

    @Test fun `dead links are remembered, expire after the window and clear on success`() {
        var now = 1_000_000L
        val lib = LibraryStore(prefs("h1"), clock = { now })
        lib.markUnavailable("a"); lib.markUnavailable("b")
        assertEquals(setOf("a", "b"), lib.recentlyUnavailable())
        lib.markAvailable("a")
        assertEquals(setOf("b"), LibraryStore(ctx.getSharedPreferences("h1", Context.MODE_PRIVATE), clock = { now }).recentlyUnavailable())
        now += LibraryStore.UNAVAILABLE_WINDOW_MS + 1
        assertTrue(lib.recentlyUnavailable().isEmpty())
    }

    @Test fun `header values are sanitised against injection`() {
        assertEquals("abcX-Evil: 1", StreamEngine.sanitizeHeader("abc\r\nX-Evil: 1"))
        val ch = channel("H").copy(referrer = "https://r.example/\r\nSet-Cookie: x")
        assertEquals("https://r.example/Set-Cookie: x", StreamEngine.requestHeaders(ch)["Referer"])
        assertNull(StreamEngine.requestHeaders(channel("NoRef"))["Origin"])
    }
}
