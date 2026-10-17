package app.show.tv

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.M3uParser
import androidx.compose.ui.semantics.getOrNull
import app.show.tv.player.PlayerActivity
import app.show.tv.ui.theme.ShowTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private const val PlaylistRepositoryUrl = "https://127.0.0.1:9/offline.m3u"

/** End-to-end navigation through the real UI tree, offline against the playlist snapshot. */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
// Robolectric's native graphics runtime has no linux-aarch64 build; legacy mode is portable.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<ShowApp>()

    @Before fun seedOfflineCatalog() {
        val dir = File(app.filesDir, "playlist").apply { mkdirs() }
        javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u")!!.use { it.copyTo(File(dir, PlaylistRepository.cacheFileName(PlaylistRepositoryUrl)).outputStream()) }
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        app.graph = AppGraph(app, playlistUrl = "https://127.0.0.1:9/offline.m3u")
    }

    private fun launch() {
        compose.setContent { ShowTheme { ShowRoot() } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("hero").fetchSemanticsNodes().isNotEmpty() }
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `phone shows bottom nav and every tab renders`() {
        launch()
        compose.onNodeWithTag("nav_bar").assertIsDisplayed()
        compose.onAllNodesWithTag("hero_play").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("brand_mark").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show").assertExists()

        compose.onNodeWithTag("tab_live").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("live_grid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chip_News").performClick()
        compose.onNodeWithTag("live_grid").assertIsDisplayed()

        compose.onNodeWithTag("tab_search").performClick()
        compose.onNodeWithTag("search_field").performTextInput("news")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search_results").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("tab_mylist").performClick()
        compose.onNodeWithTag("mylist_empty").assertIsDisplayed()
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `details sheet adds to My List and Play opens the player`() {
        launch()
        compose.onNodeWithTag("tab_live").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("channel_card").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("channel_card").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("detail_list").fetchSemanticsNodes().isNotEmpty() }
        // Popups get no touch injection under Robolectric legacy graphics, so invoke the click action directly.
        compose.onNodeWithTag("detail_list").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.graph.libraryStore.favorites.value.size == 1 }
        // Sheet is a popup window, so existence is the reliable check. The frosted circle flips its label.
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Remove from My List")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("detail_play").performSemanticsAction(SemanticsActions.OnClick)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, started.component!!.className)
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `settings sheet validates the playlist url and toggles filters`() {
        launch()
        compose.onNodeWithTag("open_settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("playlist_url").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist_url").performTextInput("http://insecure.example/x.m3u")
        compose.onNodeWithTag("save_playlist").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("For your security, playlists must use https://").assertExists()
        compose.onNodeWithTag("toggle_geo").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { app.graph.settings.settings.value.hideGeoBlocked }
        assertEquals(PlaylistRepositoryUrl, app.graph.settings.playlistUrl)
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `live tv working-only chip and country filter`() {
        launch()
        compose.onNodeWithTag("tab_live").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chip_working").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chip_working").performClick()
        compose.waitUntil(5_000) { app.graph.settings.settings.value.hideUnavailable }
        compose.onNodeWithTag("country_menu").assertIsDisplayed()
        compose.onNodeWithTag("live_grid").assertIsDisplayed()
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `see all on a home shelf opens Live TV filtered to that shelf`() {
        launch()
        compose.onNodeWithTag("home_list").performScrollToIndex(3)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTagPrefix("see_all_")).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(hasTestTagPrefix("see_all_")).onFirst()
        val title = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix("see_all_")
        node.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("live_grid").fetchSemanticsNodes().isNotEmpty() }
        val snapshot = javaClass.classLoader!!.getResourceAsStream("iptv_org_index_category_snapshot.m3u")!!.bufferedReader().use { r -> M3uParser.parse(r.readText()) }
        val expected = snapshot.channels.count { title in it.categories }
        assertTrue(expected in 1 until snapshot.channels.size)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("$expected channels").fetchSemanticsNodes().isNotEmpty() }
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `long-press on a card toggles My List`() {
        launch()
        compose.onNodeWithTag("tab_live").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("channel_card").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("channel_card").onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { app.graph.libraryStore.favorites.value.size == 1 }
        compose.onAllNodesWithTag("channel_card").onFirst().performTouchInput { longClick() }
        compose.waitUntil(5_000) { app.graph.libraryStore.favorites.value.isEmpty() }
    }

    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun `screens carry large titles and the home source chips`() {
        launch()
        compose.onNodeWithTag("source_chips").assertExists()
        compose.onAllNodesWithTag("hero_list").onFirst().assertExists()
        compose.onAllNodesWithTag("hero_info").onFirst().assertExists()
        compose.onNodeWithTag("tab_search").performClick()
        compose.onAllNodesWithText("Search").onFirst().assertExists()
        compose.onNodeWithTag("tab_live").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("language_menu").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("country_menu").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Test fun `tablet uses navigation rail`() {
        launch()
        compose.onNodeWithTag("nav_rail").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("nav_bar")).fetchSemanticsNodes().size)
    }
}
