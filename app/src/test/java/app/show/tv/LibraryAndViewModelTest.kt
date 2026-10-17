package app.show.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.LibraryStore
import app.show.tv.data.PlaylistRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class LibraryStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lib", Context.MODE_PRIVATE)
    private var now = 1_000L

    @Before fun clear() = prefs.edit().clear().commit().let { }

    @Test fun `favorites toggle, newest first and persist`() {
        val s = LibraryStore(prefs)
        assertTrue(s.toggleFavorite("a"))
        assertTrue(s.toggleFavorite("b"))
        assertEquals(listOf("b", "a"), s.favorites.value)
        assertFalse(s.toggleFavorite("a"))
        assertEquals(listOf("b"), LibraryStore(prefs).favorites.value)
    }

    @Test fun `history dedupes, orders by recency and is capped`() {
        val s = LibraryStore(prefs, clock = { now++ }, maxHistory = 3)
        listOf("a", "b", "c", "a", "d").forEach(s::recordWatched)
        assertEquals(listOf("d", "a", "c"), s.history.value.map { it.channelId })
        assertEquals(listOf("d", "a", "c"), LibraryStore(prefs, maxHistory = 3).history.value.map { it.channelId })
        s.clearHistory()
        assertTrue(LibraryStore(prefs).history.value.isEmpty())
    }

    @Test fun `corrupt stored data is ignored`() {
        prefs.edit().putString("history", "x:notanumber,,y:5,:").putString("favorites", ",,z,z").commit()
        val s = LibraryStore(prefs)
        assertEquals(listOf("y"), s.history.value.map { it.channelId })
        assertEquals(listOf("z"), s.favorites.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class MainViewModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test fun `loads cached snapshot offline, builds rows and resolves favorites`() = runBlocking {
        val dir = File(ctx.filesDir, "vm-${System.nanoTime()}").apply { mkdirs() }
        javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u")!!.use { it.copyTo(File(dir, PlaylistRepository.cacheFileName("https://127.0.0.1:9/none.m3u")).outputStream()) }
        // Unroutable https URL: refresh fails fast and the VM must fall back to the cache.
        val repo = PlaylistRepository(OkHttpClient(), dir, "https://127.0.0.1:9/none.m3u")
        val lib = LibraryStore(ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val vm = MainViewModel(repo, lib, compute = Dispatchers.Default)

        val state = withTimeout(30_000) { vm.state.first { !it.loading && it.catalog.channels.isNotEmpty() } }
        assertTrue(state.catalog.channels.size > 10_000)
        assertTrue(state.rows.size >= 10)
        assertTrue(state.rows.none { it.title == "Undefined" })
        assertTrue(state.featured.isNotEmpty())
        assertNotNull("offline notice expected", state.offlineNotice)

        val first = state.catalog.channels.first()
        vm.toggleFavorite(first.id)
        assertEquals(first.id, withTimeout(5_000) { vm.favorites.first { it.isNotEmpty() } }.single().id)

        // Hiding geo-blocked channels rebuilds the catalog but must not drop saved favourites.
        val settings = app.show.tv.data.SettingsStore(ctx.getSharedPreferences("vm-s", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val vm2 = MainViewModel(repo, lib, compute = Dispatchers.Default, settingsStore = settings)
        val full = withTimeout(30_000) { vm2.state.first { !it.loading && it.catalog.channels.isNotEmpty() } }.catalog.channels.size
        val geo = full - withTimeout(30_000) { settings.setHideGeoBlocked(true); vm2.state.first { it.catalog.channels.isNotEmpty() && it.catalog.channels.size < full } }.catalog.channels.size
        assertTrue("some geo-blocked channels hidden ($geo)", geo > 0)
        assertTrue(vm2.state.value.catalog.channels.none { it.isGeoBlocked })
        assertEquals(first.id, withTimeout(5_000) { vm2.favorites.first { it.isNotEmpty() } }.single().id)

        vm.onQueryChange("news")
        val results = withTimeout(10_000) { vm.search.first { it.query == "news" && it.results.isNotEmpty() } }
        assertTrue(results.results.size in 1..200)
    }

    @Test fun `shows an error when there is neither network nor cache`() = runBlocking {
        val dir = File(ctx.filesDir, "empty-${System.nanoTime()}")
        val vm = MainViewModel(PlaylistRepository(OkHttpClient(), dir, "https://127.0.0.1:9/none.m3u"),
            LibraryStore(ctx.getSharedPreferences("vm2", Context.MODE_PRIVATE)), compute = Dispatchers.Default)
        val s = withTimeout(30_000) { vm.state.first { !it.loading } }
        assertNotNull(s.error)
        assertTrue(s.catalog.channels.isEmpty())
    }
}
