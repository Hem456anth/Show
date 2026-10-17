package app.show.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.MaterialTheme
import app.show.tv.ui.screens.LiveFilter
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.show.tv.data.Channel
import app.show.tv.player.PlayerActivity
import app.show.tv.ui.MainViewModel
import app.show.tv.ui.components.ChannelDetailSheet
import app.show.tv.ui.components.LocalUnavailableIds
import app.show.tv.ui.components.SettingsSheet
import app.show.tv.ui.screens.ChannelActions
import app.show.tv.ui.screens.HomeScreen
import app.show.tv.ui.screens.LiveTvScreen
import app.show.tv.ui.screens.Metrics
import app.show.tv.ui.screens.MyListScreen
import app.show.tv.ui.screens.SearchScreen
import app.show.tv.ui.theme.ShowColors
import app.show.tv.ui.theme.ShowTheme
import app.show.tv.ui.components.FloatingGlassTabBar
import app.show.tv.ui.components.GlassTab
import app.show.tv.ui.components.LocalBottomBarInset
import app.show.tv.ui.components.TabBarHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Rounded.Home),
    Live("live", "Live TV", Icons.Rounded.LiveTv),
    Search("search", "Search", Icons.Rounded.Search),
    MyList("mylist", "My List", Icons.Rounded.Bookmarks),
}

private val glassTabs = Tab.entries.map { GlassTab(it.route, it.label, it.icon) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { ShowTheme { ShowRoot() } }
    }
}

@Composable
fun ShowRoot() {
    val context = LocalContext.current
    val graph = context.appGraph
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(graph.playlistRepository, graph.libraryStore, graph.settings, graph.indexRepository, graph.extraRepository, { graph.extraAllowlist }))
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val query by vm.queryText.collectAsStateWithLifecycle()
    val searchCategory by vm.searchCategory.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unavailable by vm.unavailableIds.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    // Back in the foreground after hours (TV boxes stay "open" for days): links may have rotated.
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) { vm.refreshIfStale(); onPauseOrDispose { } }

    var detail by remember { mutableStateOf<Channel?>(null) }
    var liveFilter by remember { mutableStateOf<LiveFilter?>(null) }
    val nav = rememberNavController()
    val actions = remember(vm, nav) {
        ChannelActions(
            play = { ch, queue ->
                detail = null
                PlayerActivity.start(context, ch, queue)
            },
            details = { detail = it },
            toggleList = { vm.toggleFavorite(it) },
            seeAll = { filter ->
                liveFilter = filter
                nav.navigate(Tab.Live.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            },
        )
    }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Tab.Home.route
    val go: (Tab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalUnavailableIds provides unavailable) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = Metrics.forWidth(maxWidth)
        val useRail = maxWidth >= 600.dp

        val host: @Composable (Modifier) -> Unit = { mod ->
            NavHost(
                nav, startDestination = Tab.Home.route, modifier = mod,
                // Apple-style cross-dissolve with a slight zoom; tab content never slides sideways.
                enterTransition = { fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.985f) },
                exitTransition = { fadeOut(tween(180)) },
            ) {
                composable(Tab.Home.route) {
                    HomeScreen(state, history, favorites, favoriteIds, metrics, actions, onRetry = { vm.load(forceNetwork = true) }, onSettings = { showSettings = true },
                        source = settings.source, onSource = vm::setSource)
                }
                composable(Tab.Live.route) {
                    LiveTvScreen(state, favoriteIds, metrics, actions, onRetry = { vm.load(forceNetwork = true) },
                        hideUnavailable = settings.hideUnavailable, onHideUnavailable = vm::setHideUnavailable,
                        preset = liveFilter, onPresetConsumed = { liveFilter = null })
                }
                composable(Tab.Search.route) {
                    SearchScreen(search, query, state.catalog.categories, searchCategory, favoriteIds, metrics, actions, vm::onQueryChange, vm::onSearchCategory, history)
                }
                composable(Tab.MyList.route) { MyListScreen(favorites, history, favoriteIds, metrics, actions, vm::clearHistory) }
            }
        }

        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = ShowColors.Ink, modifier = Modifier.testTag("nav_rail")) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = current == tab.route, onClick = { go(tab) },
                            icon = { Icon(tab.icon, null) }, label = { Text(tab.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Color.Black, indicatorColor = ShowColors.Accent, selectedTextColor = Color.White,
                                unselectedIconColor = ShowColors.Mist, unselectedTextColor = ShowColors.Mist,
                            ),
                            modifier = Modifier.testTag("tab_${tab.route}"),
                        )
                    }
                }
                host(Modifier.weight(1f))
            }
        } else {
            // Floating liquid-glass tab bar; the content runs underneath it and is blurred through it.
            val navHaze = remember { HazeState() }
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(Modifier.fillMaxSize().background(ShowColors.Ink)) {
                CompositionLocalProvider(LocalBottomBarInset provides TabBarHeight + 16.dp + navBottom) {
                    host(Modifier.fillMaxSize().haze(navHaze))
                }
                FloatingGlassTabBar(
                    tabs = glassTabs, selectedKey = current, onSelect = { t -> go(Tab.entries.first { it.route == t.key }) }, haze = navHaze,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    }

    if (showSettings) SettingsSheet(
        settings = settings, onDismiss = { showSettings = false },
        onPlaylistUrl = vm::setPlaylistUrl, onHideGeo = vm::setHideGeoBlocked,
        onHideUnavailable = vm::setHideUnavailable, onClearHistory = vm::clearHistory,
        onExtraSources = vm::setExtraSources,
        onSource = { vm.setSource(it); showSettings = false },
    )

    detail?.let { ch ->
        val related = remember(ch, state.catalog) { state.catalog.related(ch, 16) }
        ChannelDetailSheet(
            channel = ch, related = related, inList = ch.id in favoriteIds, favoriteIds = favoriteIds,
            onDismiss = { detail = null },
            onPlay = { actions.play(it, listOf(it) + related) },
            onToggleList = actions.toggleList,
            onOpen = { detail = it },
            relatedTitle = state.catalog.relatedTitle(ch),
        )
    }
}

