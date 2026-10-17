package app.show.tv.ui.screens

import androidx.compose.ui.res.painterResource
import app.show.tv.R
import app.show.tv.ui.components.ShowPrimaryButton
import app.show.tv.ui.components.ShowSecondaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.show.tv.data.Channel
import app.show.tv.data.PlaylistSource
import app.show.tv.ui.CatalogState
import app.show.tv.ui.SearchState
import app.show.tv.ui.components.ChannelArtwork
import app.show.tv.ui.components.ChannelCard
import app.show.tv.ui.components.ChannelRow
import app.show.tv.ui.components.HeroCarousel
import app.show.tv.ui.components.GlassPlayButton
import app.show.tv.ui.components.LocalBottomBarInset
import app.show.tv.ui.components.liquidGlass
import app.show.tv.ui.components.LocalUnavailableIds
import app.show.tv.ui.theme.ShowColors

/** Callbacks shared by every screen. */
class ChannelActions(
    val play: (Channel, List<Channel>) -> Unit,
    val details: (Channel) -> Unit,
    val toggleList: (Channel) -> Unit,
    /** "See all" on a Home shelf: open Live TV filtered to that category, country or language. */
    val seeAll: (LiveFilter) -> Unit = {},
)

/** A Live TV filter handed over from a Home shelf. */
@androidx.compose.runtime.Immutable
data class LiveFilter(val grouping: PlaylistSource.Grouping, val value: String)

/** Layout metrics for phone vs tablet widths. */
data class Metrics(val cardWidth: Dp, val gridMin: Dp, val gutter: Dp) {
    companion object {
        fun forWidth(width: Dp) = when {
            width >= 840.dp -> Metrics(220.dp, 210.dp, 24.dp)
            width >= 600.dp -> Metrics(196.dp, 180.dp, 20.dp)
            else -> Metrics(156.dp, 150.dp, 16.dp)
        }
    }
}

// ---------------------------------------------------------------- Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: CatalogState,
    history: List<Channel>,
    favorites: List<Channel>,
    favoriteIds: Set<String>,
    metrics: Metrics,
    actions: ChannelActions,
    onRetry: () -> Unit,
    onSettings: () -> Unit = {},
    source: PlaylistSource? = PlaylistSource.DEFAULT,
    onSource: (PlaylistSource) -> Unit = {},
) {
    val grouping = source?.grouping ?: PlaylistSource.Grouping.CATEGORY
    when {
        state.catalog.channels.isEmpty() && state.loading -> HomeSkeleton(metrics)
        state.catalog.channels.isEmpty() -> ErrorState(state.error ?: "No channels yet.", onRetry)
        else -> {
            val listState = rememberLazyListState()
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200 } }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRetry, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current), modifier = Modifier.fillMaxSize().testTag("home_list")) {
                    item(key = "hero") {
                        HeroCarousel(
                            state.featured, favoriteIds,
                            onPlay = { actions.play(it, state.featured) },
                            onToggleList = actions.toggleList,
                            onInfo = actions.details,
                            scrollOffset = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 100_000f },
                        )
                    }
                    item(key = "sources") { SourceChips(source, onSource) }
                    state.offlineNotice?.let { notice -> item(key = "notice") { OfflineBanner(notice, onRetry) } }
                    if (history.isNotEmpty()) item(key = "continue") {
                        // Apple TV's "Up Next": larger tiles that resume straight into playback.
                        ChannelRow("Up Next", history, actions.play, favoriteIds, metrics.cardWidth * 1.3f, onLongPress = actions.toggleList)
                    }
                    if (favorites.isNotEmpty()) item(key = "mylist") {
                        ChannelRow("My List", favorites, actions.play, favoriteIds, metrics.cardWidth, onLongPress = actions.toggleList)
                    }
                    items(state.rows, key = { "row_" + it.title }) { row ->
                        ChannelRow(row.title, row.channels, { ch, _ -> actions.details(ch) }, favoriteIds, metrics.cardWidth,
                            onLongPress = actions.toggleList, onSeeAll = { actions.seeAll(LiveFilter(grouping, row.title)) })
                    }
                    item(key = "credit") { Attribution() }
                }
                // Brand bar: transparent over the hero, then a clean solid black once content scrolls under it
                // (glass is reserved for the logomark, the tab bar and Play buttons).
                val barColor by androidx.compose.animation.animateColorAsState(if (scrolled) ShowColors.Ink.copy(alpha = 0.96f) else Color.Transparent, label = "bar")
                Column(Modifier.fillMaxWidth().background(barColor).testTag("brand_bar")) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.weight(1f))
                        Text("${state.catalog.channels.size} channels", style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist)
                        IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
            }
        }
    }
}

/** The retro-TV logomark on a liquid-glass tile, white on glass, with the wordmark. */
@Composable
fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).liquidGlass(RoundedCornerShape(10.dp), elevation = 6.dp).testTag("brand_mark"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_show_mark), contentDescription = "Show", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text("Show", style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}

@Composable
private fun OfflineBanner(text: String, onRetry: () -> Unit) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ShowColors.Surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = ShowColors.Accent)
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun Attribution() {
    Text(
        "Channel index by the iptv-org community (github.com/iptv-org/iptv), plus verified links from Free-TV " +
            "(github.com/Free-TV/IPTV). Show hosts no streams; " +
            "every channel is a public link owned by its broadcaster.",
        style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 28.dp),
    )
}

@Composable
private fun HomeSkeleton(metrics: Metrics) {
    Column(Modifier.fillMaxSize().testTag("loading")) {
        Box(Modifier.fillMaxWidth().height(520.dp).background(ShowColors.Night))
        repeat(3) {
            Box(Modifier.padding(16.dp).size(140.dp, 18.dp).clip(RoundedCornerShape(4.dp)).background(ShowColors.Surface))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                items(6) { Box(Modifier.size(metrics.cardWidth, metrics.cardWidth * 9f / 16f).clip(RoundedCornerShape(14.dp)).background(ShowColors.Surface)) }
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("error"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, tint = ShowColors.Accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        ShowPrimaryButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.size(6.dp)); Text("Try again") }
    }
}

// ---------------------------------------------------------------- Live TV

@Composable
fun LiveTvScreen(
    state: CatalogState,
    favoriteIds: Set<String>,
    metrics: Metrics,
    actions: ChannelActions,
    onRetry: () -> Unit,
    hideUnavailable: Boolean = false,
    onHideUnavailable: (Boolean) -> Unit = {},
    preset: LiveFilter? = null,
    onPresetConsumed: () -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(preset) {
        val p = preset ?: return@LaunchedEffect
        category = null; country = null; language = null
        when (p.grouping) {
            PlaylistSource.Grouping.CATEGORY -> category = p.value
            PlaylistSource.Grouping.COUNTRY -> country = p.value
            PlaylistSource.Grouping.LANGUAGE -> language = p.value
        }
        onPresetConsumed()
    }
    // A filter value the new catalog doesn't have (after a source switch) is dropped.
    LaunchedEffect(state.catalog) {
        if (state.catalog.channels.isEmpty()) return@LaunchedEffect
        if (country != null && country !in state.catalog.countries) country = null
        if (language != null && language !in state.catalog.languages) language = null
        if (category != null && category !in state.catalog.categories) category = null
    }
    val dead = LocalUnavailableIds.current
    val exclude = if (hideUnavailable) dead else emptySet()
    val channels = remember(state.catalog, category, country, language, exclude) { state.catalog.browse(category, country, exclude, language) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Live TV", "${channels.size} channels")
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterMenu(Icons.Rounded.Public, "All countries", state.catalog.countries, country, "country_menu") { country = it }
            FilterMenu(Icons.Rounded.Translate, "All languages", state.catalog.languages, language, "language_menu") { language = it }
        }
        CategoryChips(state.catalog.categories, category, leading = {
            FilterChip(
                selected = hideUnavailable, onClick = { onHideUnavailable(!hideUnavailable) },
                label = { Text("Working only") },
                leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ShowColors.Accent, selectedLabelColor = Color.Black,
                    selectedLeadingIconColor = Color.Black, containerColor = ShowColors.Surface, labelColor = Color.White),
                border = null, modifier = Modifier.testTag("chip_working"),
            )
        }) { category = it }
        if (state.catalog.channels.isEmpty()) {
            if (state.loading) Box(Modifier.fillMaxSize()) else ErrorState(state.error ?: "No channels", onRetry)
        } else if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp).testTag("live_empty"), contentAlignment = Alignment.Center) {
                Text("No channels match these filters.", color = ShowColors.Mist)
            }
        } else ChannelGrid(channels, favoriteIds, metrics, actions, Modifier.testTag("live_grid"))
    }
}

/** A dropdown filter (countries, languages) styled as a white text button. */
@Composable
private fun FilterMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector, allLabel: String, values: List<String>, selected: String?,
    tag: String, onSelect: (String?) -> Unit,
) {
    if (values.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
            Text(selected ?: allLabel, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp).testTag("${tag}_list")) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(null); open = false })
            values.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v, fontWeight = if (v == selected) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                    onClick = { onSelect(v); open = false },
                )
            }
        }
    }
}

/** Switches between the iptv-org playlists (India, global, by country, by language, by category). */
@Composable
fun SourceChips(current: PlaylistSource?, onSelect: (PlaylistSource) -> Unit) {
    val order = listOf(PlaylistSource.INDIA, PlaylistSource.GLOBAL, PlaylistSource.COUNTRIES, PlaylistSource.LANGUAGES, PlaylistSource.CATEGORIES)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source_chips"),
    ) {
        items(order, key = { it.id }) { src ->
            Chip(src.title, current == src, tag = "source_${src.id}") { if (current != src) onSelect(src) }
        }
    }
}

@Composable
fun CategoryChips(categories: List<String>, selected: String?, leading: (@Composable () -> Unit)? = null, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("category_chips"),
    ) {
        leading?.let { l -> item(key = "leading") { l() } }
        item { Chip("All", selected == null) { onSelect(null) } }
        items(categories, key = { it }) { c -> Chip(c, selected == c) { onSelect(if (selected == c) null else c) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String = "chip_$label", onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ShowColors.Accent, selectedLabelColor = Color.Black,
            containerColor = ShowColors.Surface, labelColor = Color.White,
        ),
        border = null,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
fun ChannelGrid(channels: List<Channel>, favoriteIds: Set<String>, metrics: Metrics, actions: ChannelActions, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(metrics.gridMin),
        contentPadding = PaddingValues(start = metrics.gutter, top = metrics.gutter, end = metrics.gutter, bottom = metrics.gutter + LocalBottomBarInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { h -> item(span = { GridItemSpan(maxLineSpan) }) { h() } }
        items(channels, key = { it.id }, contentType = { "card" }) { ch ->
            ChannelCard(
                ch, onClick = { actions.details(ch) }, width = null, isFavorite = ch.id in favoriteIds,
                onLongClick = { actions.toggleList(ch) }, modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        subtitle?.let {
            Spacer(Modifier.size(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, modifier = Modifier.padding(bottom = 7.dp))
        }
    }
}

// ---------------------------------------------------------------- Search

@Composable
fun SearchScreen(
    search: SearchState,
    query: String,
    categories: List<String>,
    selectedCategory: String?,
    favoriteIds: Set<String>,
    metrics: Metrics,
    actions: ChannelActions,
    onQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    history: List<Channel>,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Search")
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Channels, countries, genres…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ShowColors.Surface, unfocusedContainerColor = ShowColors.Surface,
                focusedBorderColor = ShowColors.Accent, unfocusedBorderColor = Color.Transparent, cursorColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus).testTag("search_field"),
        )
        CategoryChips(categories, selectedCategory, onSelect = onCategory)
        when {
            query.isBlank() && history.isNotEmpty() -> ChannelGrid(history.take(12), favoriteIds, metrics, actions, header = { SectionLabel("Recently watched") })
            query.isBlank() -> Hint("Search ${categories.size} categories of free live TV.")
            search.query == query && search.results.isEmpty() -> Hint("No channels match \"$query\".")
            else -> ChannelGrid(search.results, favoriteIds, metrics, actions, Modifier.testTag("search_results"))
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ShowColors.Mist, textAlign = TextAlign.Center, modifier = Modifier.testTag("search_hint"))
    }
}

// ---------------------------------------------------------------- My List

@Composable
fun MyListScreen(
    favorites: List<Channel>,
    history: List<Channel>,
    favoriteIds: Set<String>,
    metrics: Metrics,
    actions: ChannelActions,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("My List", "${favorites.size} saved")
        if (favorites.isEmpty() && history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp).testTag("mylist_empty"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.BookmarkBorder, null, tint = ShowColors.Accent, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Your list is empty", style = MaterialTheme.typography.titleMedium)
                Text("Tap + on any channel to save it here.", color = ShowColors.Mist)
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize().testTag("mylist"), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarInset.current)) {
            if (favorites.isNotEmpty()) item {
                ChannelRow("Saved channels", favorites, actions.play, favoriteIds, metrics.cardWidth, onLongPress = actions.toggleList)
            }
            if (history.isNotEmpty()) item {
                ChannelRow("Watch history", history, actions.play, favoriteIds, metrics.cardWidth, trailing = {
                    TextButton(onClick = onClearHistory, modifier = Modifier.testTag("clear_history")) { Text("Clear") }
                })
            }
            if (favorites.isNotEmpty()) item {
                Text("All saved", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
            }
            items(favorites, key = { "fav_" + it.id }) { ch ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(10.dp))) { ChannelArtwork(ch, Modifier.fillMaxSize(), logoPadding = 8.dp, monogramSize = 18) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(ch.categories.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, maxLines = 1)
                    }
                    GlassPlayButton(onClick = { actions.play(ch, favorites) }, label = "Play ${ch.name}", compact = true, height = 40.dp)
                    IconButton(onClick = { actions.toggleList(ch) }) { Icon(Icons.Rounded.Close, "Remove ${ch.name}") }
                }
            }
        }
    }
}

