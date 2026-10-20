package app.show.tv

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.ui.screens.Metrics
import app.show.tv.ui.theme.ShowTheme
import app.show.tv.vod.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** 1.8.0 Studios & networks UI: the empty state without a TMDB key, and a studio's paged grid with a key. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class Release180UiTest {
    private val compose = createComposeRule()
    /** Same CI guard as Release160UiTest: GitHub's runners never report Compose idle at teardown (docs/MOVIES_1.6.md). */
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(org.junit.rules.TestRule { base, _ -> object : org.junit.runners.model.Statement() {
            override fun evaluate() { org.junit.Assume.assumeTrue(System.getenv("CI") == null); base.evaluate() }
        } })
        .around(compose)
    private val http = VodHttp(OkHttpClient())
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeTmdb : VodSource {
        override val provider = Provider.TMDB
        val pages = mutableListOf<Int>()
        override suspend fun rows() = listOf(VodRow("tmdb", "Trending", (1..3).map { VodTitle(Provider.TMDB, "$it", VodKind.MOVIE, "Trend $it", genres = listOf("Drama")) }, Provider.TMDB))
        override suspend fun search(query: String) = emptyList<VodTitle>()
        override suspend fun detail(title: VodTitle) = VodDetail(title)
        override suspend fun browsePage(shortcut: VodShortcut, page: Int): VodPage {
            pages += page
            return VodPage((0 until 4).map { VodTitle(Provider.TMDB, "${shortcut.id}_${page}_$it", VodKind.MOVIE, "${shortcut.name} film $page.$it") }, page, 2)
        }
    }

    /** No real image loads under Robolectric: a crossfade that never finishes would keep Compose busy. */
    @org.junit.Before fun images() {
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(ctx).crossfade(false).components {
            add(coil.intercept.Interceptor { chain -> coil.request.ErrorResult(null, chain.request, java.io.IOException("no images in tests")) })
        }.build())
    }

    private fun vm(withKey: Boolean, src: VodSource): VodViewModel {
        val prefs = ctx.getSharedPreferences("vod_ui180_${System.nanoTime()}", Context.MODE_PRIVATE)
        val settings = VodSettings(prefs)
        if (withKey) settings.setTmdbKey("0123456789abcdef0123456789abcdef")
        return VodViewModel(VodRepository(http, { settings.config.value }) { _, _ -> listOf(src) }, VodLibrary(prefs), settings)
    }

    @Test fun `studios without a TMDB key explain how to get one`() {
        val vm = vm(withKey = false, src = FakeTmdb())
        compose.mainClock.autoAdvance = false
        compose.setContent { ShowTheme { VodTab(vm, Metrics(156.dp, 150.dp, 16.dp)) } }
        settle { !vm.catalog.value.loading }
        compose.onNodeWithTag("vod_section_studios").performClick(); compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText(VodRepository.NEEDS_TMDB).assertIsDisplayed()
        compose.onNodeWithText("Open Movies settings").assertIsDisplayed()
        releaseClock()
    }

    @Test fun `a studio tile opens its discover grid, which can be saved to my list`() {
        val src = FakeTmdb()
        val vm = vm(withKey = true, src = src)
        compose.mainClock.autoAdvance = false
        compose.setContent { ShowTheme { VodTab(vm, Metrics(156.dp, 150.dp, 16.dp)) } }
        settle { !vm.catalog.value.loading }
        compose.onNodeWithTag("vod_genres").assertExists()
        compose.onNodeWithTag("vod_section_studios").performClick(); compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("vod_studios").assertIsDisplayed()
        compose.onNodeWithTag("vod_org_420").performClick(); compose.mainClock.advanceTimeBy(400)
        settle { vm.shortcut.value?.loading == false }
        compose.onNodeWithTag("vod_shortcut_page").assertIsDisplayed()
        compose.onNodeWithTag("vod_card_tmdb:movie:420_1_0").assertIsDisplayed()
        settle { src.pages.contains(2) && vm.shortcut.value?.loadingMore == false } // near the end of a short grid -> page 2
        assertEquals(8, vm.shortcut.value!!.items.size)
        assertEquals("Marvel Studios", vm.shortcut.value!!.shortcut.name)
        compose.onNodeWithTag("vod_shortcut_save").performClick(); compose.mainClock.advanceTimeBy(400)
        assertTrue(vm.library.shortcuts.value.any { it.id == "420" && it.type == VodShortcut.Type.COMPANY })
        compose.onNodeWithTag("vod_shortcut_back").performClick(); compose.mainClock.advanceTimeBy(400)
        settle { vm.shortcut.value == null }
        releaseClock()
    }

    /** See Release160UiTest.releaseClock: a manual clock at teardown can leave a late frame pending forever. */
    private fun releaseClock() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun settle(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!cond() && System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("condition not reached", cond())
        compose.mainClock.advanceTimeBy(500)
    }
}
