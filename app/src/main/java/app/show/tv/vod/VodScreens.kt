package app.show.tv.vod

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.show.tv.data.Embeds
import app.show.tv.player.EmbedPlayerActivity
import app.show.tv.ui.components.GlassPlayButton
import app.show.tv.ui.components.LocalBottomBarInset
import app.show.tv.ui.components.OutlineCircleButton
import app.show.tv.ui.components.ShowPrimaryButton
import app.show.tv.ui.components.ShowSecondaryButton
import app.show.tv.ui.components.lift
import app.show.tv.ui.screens.Metrics
import app.show.tv.ui.theme.ShowColors
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

enum class VodSection(val label: String) { BROWSE("Browse"), SEARCH("Search"), MYLIST("My List") }

/**
 * The Movies & Series tab (1.6.0). Its own sections (Browse, Search, My List), filters, detail page
 * and players; it shares only the design system, HTTP stack and image loader with Live TV.
 */
@Composable
fun VodTab(vm: VodViewModel, metrics: Metrics) {
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val shortcut by vm.shortcut.collectAsStateWithLifecycle()
    val progress by vm.library.progress.collectAsStateWithLifecycle()
    val config by vm.settings.config.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(VodSection.BROWSE) }
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(ShowColors.Ink).testTag("vod_tab")) {
        Column(Modifier.fillMaxSize()) {
            VodHeader(section, { section = it }, onSettings = { showSettings = true })
            when (section) {
                VodSection.BROWSE -> VodBrowse(catalog, filter, vm::setFilter, progress, metrics, config, onOpen = vm::open, onRetry = { vm.load(force = true) })
                VodSection.SEARCH -> VodSearch(vm, metrics)
                VodSection.MYLIST -> VodMyList(vm, metrics)
            }
        }
        shortcut?.let { s ->
            BackHandler { vm.closeShortcut() }
            ShortcutPage(s, metrics, onOpen = vm::open, onClose = vm::closeShortcut,
                saved = vm.library.hasShortcut(s.shortcut), onToggle = { vm.toggleShortcut(s.shortcut) })
        }
        detail?.let { d ->
            BackHandler { vm.back() }
            VodDetailPage(d, vm, metrics)
        }
    }
    if (showSettings) VodSettingsSheet(vm, onDismiss = { showSettings = false })
}

@Composable
private fun VodHeader(section: VodSection, onSection: (VodSection) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Movies & Series", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onSettings, modifier = Modifier.testTag("vod_settings")) { Icon(Icons.Rounded.Settings, "Movies settings", tint = Color.White) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VodSection.entries.forEach { s -> Pill(s.label, s == section, "vod_section_${s.name.lowercase()}") { onSection(s) } }
        }
    }
}

@Composable
internal fun Pill(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(shape).background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) Color.White else ShowColors.Hairline.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp).testTag(tag))
}

// ------------------------------------------------------------------ Browse

@Composable
private fun VodBrowse(
    state: VodCatalogState, filter: VodFilter, onFilter: (VodFilter) -> Unit, progress: Map<String, VodProgress>,
    metrics: Metrics, config: VodConfig, onOpen: (VodTitle) -> Unit, onRetry: () -> Unit,
) {
    val rows = remember(state.rows, filter) {
        state.rows.map { r -> r.copy(items = r.items.filter { filter == VodFilter.ALL || (filter == VodFilter.MOVIES) == (it.kind == VodKind.MOVIE) }) }
            .filter { it.items.size >= 3 }
    }
    val continueRow = remember(progress) {
        progress.filterKeys { '#' !in it }.values.filter { it.resumable || it.episodeId != null && it.fraction < VodProgress.FINISHED }
            .sortedByDescending { it.updatedAt }.take(VodLibrary.MAX_CONTINUE)
    }
    val hero = remember(rows) { rows.firstOrNull { r -> r.items.any { it.playable } }?.items?.firstOrNull { it.playable && it.backdropUrl != null } ?: rows.firstOrNull()?.items?.firstOrNull() }
    val bottom = LocalBottomBarInset.current
    when {
        state.loading && state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        state.rows.isEmpty() -> VodMessage(state.error ?: "Nothing to show yet.", "Try again", onRetry)
        else -> LazyColumn(Modifier.fillMaxSize().testTag("vod_rows"), contentPadding = PaddingValues(bottom = bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VodFilter.entries.forEach { f -> Pill(f.label, f == filter, "vod_filter_${f.name.lowercase()}") { onFilter(f) } }
            } }
            hero?.let { h -> item { VodHero(h, onOpen) } }
            if (continueRow.isNotEmpty()) item {
                VodRowHeader("Continue Watching")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("vod_continue")) {
                    items(continueRow, key = { it.title.key }) { p -> ContinueCard(p, metrics.cardWidth) { onOpen(p.title) } }
                }
            }
            items(rows, key = { it.id }) { r ->
                Column {
                    VodRowHeader(r.title, if (r.provider.streams) null else "Info & trailers")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("vod_row_${r.id}")) {
                        items(r.items, key = { it.key }) { t -> PosterCard(t, metrics.cardWidth * 0.72f, onOpen) }
                    }
                }
            }
            if (state.failed.isNotEmpty()) item {
                Text("Couldn't reach ${state.failed.joinToString { it.label }} right now.", color = ShowColors.Mist,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item { Attribution(config) }
        }
    }
}

@Composable
private fun Attribution(config: VodConfig) {
    val lines = buildList {
        if (config.tmdbKey != null) add(TmdbSource.ATTRIBUTION) else add(TvMazeSource.ATTRIBUTION)
        if (config.archive) add("Free films and TV from the Internet Archive: public domain or Creative Commons, as each uploader declares.")
        add("Titles marked Info & trailers aren't streamed by Show. Add your Jellyfin server to play your own library.")
    }
    Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, modifier = Modifier.padding(horizontal = 16.dp).testTag("vod_attribution"))
}

@Composable
private fun VodRowHeader(title: String, note: String? = null) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.semantics { heading() })
        note?.let { Text("  $it", style = MaterialTheme.typography.labelSmall, color = ShowColors.Mist) }
    }
}

@Composable
private fun VodHero(t: VodTitle, onOpen: (VodTitle) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(ShowColors.Surface).testTag("vod_hero")) {
        AsyncImage(t.backdropUrl ?: t.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(ShowColors.BottomScrim))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(t.subtitle, style = MaterialTheme.typography.labelMedium, color = ShowColors.Mist)
            Spacer(Modifier.height(10.dp))
            GlassPlayButton(onClick = { onOpen(t) }, label = if (t.playable) "Play" else "Details", height = 44.dp)
        }
    }
}

@Composable
internal fun PosterCard(t: VodTitle, width: Dp, onOpen: (VodTitle) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).testTag("vod_card_${t.key}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).lift(interaction, shape).clip(shape).background(ShowColors.Surface)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onOpen(t) }
            .semantics { contentDescription = "${t.title}, ${t.subtitle}${if (t.playable) "" else ", info only"}" }) {
            AsyncImage(t.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (t.posterUrl == null) Text(t.title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (!t.playable) Text("Info", style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 5.dp, vertical = 2.dp))
        }
        Text(t.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(t.subtitle, style = MaterialTheme.typography.labelSmall, color = ShowColors.Mist, maxLines = 1)
    }
}

@Composable
private fun ContinueCard(p: VodProgress, width: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).testTag("vod_continue_${p.title.key}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).lift(interaction, shape).clip(shape).background(ShowColors.Surface)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)) {
            AsyncImage(p.title.backdropUrl ?: p.title.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.fraction).height(3.dp).background(Color.White))
        }
        Text(p.title.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(p.episodeLabel ?: "${VodPlayerActivity.formatTime(p.durationMs - p.positionMs)} left", style = MaterialTheme.typography.labelSmall, color = ShowColors.Mist, maxLines = 1)
    }
}

@Composable
private fun VodMessage(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("vod_message"))
        action?.let { Spacer(Modifier.height(12.dp)); ShowPrimaryButton(onClick = onAction) { Text(it) } }
    }
}

// ------------------------------------------------------------------ Search & My List

@Composable
private fun VodSearch(vm: VodViewModel, metrics: Metrics) {
    val q by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, vm::onQuery, singleLine = true, placeholder = { Text("Movies, series, people's work…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = ShowColors.Hairline.copy(alpha = 0.4f), cursorColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("vod_search_field"))
        when {
            searching && results == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            results == null -> Text("Search Internet Archive classics, your Jellyfin library and ${if (vm.settings.config.value.tmdbKey != null) "TMDB" else "TVmaze"} at once.",
                color = ShowColors.Mist, modifier = Modifier.padding(16.dp))
            results!!.isEmpty() -> Text("No matches for \u201c$q\u201d.", color = ShowColors.Mist, modifier = Modifier.padding(16.dp).testTag("vod_no_results"))
            else -> TitleGrid(results!!, metrics, vm::open, Modifier.testTag("vod_results"))
        }
    }
}

@Composable
internal fun TitleGrid(items: List<VodTitle>, metrics: Metrics, onOpen: (VodTitle) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(GridCells.Adaptive(metrics.gridMin * 0.72f), modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomBarInset.current + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items, key = { it.key }) { PosterCard(it, Dp.Unspecified, onOpen) }
    }
}

@Composable
private fun VodMyList(vm: VodViewModel, metrics: Metrics) {
    val list by vm.library.myList.collectAsStateWithLifecycle()
    val shortcuts by vm.library.shortcuts.collectAsStateWithLifecycle()
    if (list.isEmpty() && shortcuts.isEmpty()) {
        VodMessage("Save movies, series, studios, networks and cast here with the + on any title.", null) {}
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (shortcuts.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("vod_shortcuts")) {
            items(shortcuts, key = { it.key }) { s ->
                val icon = when (s.type) { VodShortcut.Type.PERSON -> Icons.Rounded.Person; VodShortcut.Type.NETWORK -> Icons.Rounded.Tv; VodShortcut.Type.COMPANY -> Icons.Rounded.Business }
                AssistChip(onClick = { vm.openShortcut(s) }, label = { Text(s.name) }, leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, leadingIconContentColor = Color.White))
            }
        }
        TitleGrid(list, metrics, vm::open, Modifier.testTag("vod_mylist"))
    }
}

@Composable
private fun ShortcutPage(s: ShortcutState, metrics: Metrics, onOpen: (VodTitle) -> Unit, onClose: () -> Unit, saved: Boolean, onToggle: () -> Unit) {
    var isSaved by remember(s.shortcut) { mutableStateOf(saved) }
    Column(Modifier.fillMaxSize().background(ShowColors.Ink).statusBarsPadding().testTag("vod_shortcut_page")) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text(s.shortcut.name, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            IconButton(onClick = { onToggle(); isSaved = !isSaved }) { Icon(if (isSaved) Icons.Rounded.Check else Icons.Rounded.Add, if (isSaved) "Remove from My List" else "Add to My List", tint = Color.White) }
        }
        when {
            s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            s.items.isEmpty() -> VodMessage(s.error ?: "Nothing here yet.", null) {}
            else -> TitleGrid(s.items, metrics, onOpen)
        }
    }
}

// ------------------------------------------------------------------ Detail

@Composable
private fun VodDetailPage(st: VodDetailState, vm: VodViewModel, metrics: Metrics) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = st.detail?.title ?: st.title
    val listState by vm.library.myList.collectAsStateWithLifecycle()
    val saved = listState.any { it.key == t.key }
    val progress by vm.library.progress.collectAsStateWithLifecycle()
    val resume = progress[t.key]
    var companyIds by remember(t.key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(t.key, st.detail) { if (t.provider == Provider.TMDB && st.detail != null) companyIds = vm.companyIds(t) }

    Box(Modifier.fillMaxSize().background(ShowColors.Ink).testTag("vod_detail")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                    AsyncImage(t.backdropUrl ?: t.posterUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(ShowColors.BottomScrim))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(t.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.testTag("vod_detail_title"))
                        Text(listOfNotNull(t.year?.toString(), if (t.kind == VodKind.SHOW) "Series" else "Movie",
                            t.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }, t.rating?.let { "★ %.1f".format(it) },
                            if (t.playable) t.provider.label else "Info from ${t.provider.label}").joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = ShowColors.Mist)
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val d = st.detail
                    when {
                        st.loading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                        d != null && t.playable && t.kind == VodKind.MOVIE && d.sources.isNotEmpty() -> {
                            val start = resume?.takeIf { it.resumable }?.positionMs ?: d.remotePositionMs ?: 0
                            GlassPlayButton(onClick = { VodPlayerActivity.start(ctx, VodPlayRequest(t, d.sources, startMs = start)) },
                                label = if (start > 0) "Resume" else "Play", modifier = Modifier.testTag("vod_play"))
                        }
                        d != null && t.playable && t.kind == VodKind.SHOW -> {
                            val season = d.seasons.firstOrNull { it.number == st.season }
                            val eps = season?.episodes.orEmpty().filter { it.sources.isNotEmpty() }
                            if (eps.isNotEmpty()) {
                                val resumeIdx = resume?.episodeId?.let { id -> eps.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                                GlassPlayButton(onClick = { playEpisode(ctx, vm, t, eps, resumeIdx ?: 0) },
                                    label = if (resumeIdx != null) "Resume ${eps[resumeIdx].code}" else "Play ${eps.first().code}", modifier = Modifier.testTag("vod_play"))
                            }
                        }
                        st.twin != null -> GlassPlayButton(onClick = { vm.open(st.twin) }, label = "Play on Jellyfin", modifier = Modifier.testTag("vod_play_twin"))
                        d != null && t.playable -> Text("No playable file in this item.", color = ShowColors.Mist)
                    }
                    d?.trailerYoutubeId?.let { id ->
                        ShowSecondaryButton(onClick = { ctx.startActivity(EmbedPlayerActivity.intent(ctx, "https://www.youtube.com/embed/$id", "${t.title} trailer")) },
                            modifier = Modifier.testTag("vod_trailer")) { Icon(Icons.Rounded.Movie, null); Spacer(Modifier.width(6.dp)); Text("Trailer") }
                    }
                    OutlineCircleButton(if (saved) "Remove from My List" else "Add to My List", { vm.toggleList(t) }, "vod_list_toggle", size = 44.dp) {
                        Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, null)
                    }
                }
            }
            st.error?.let { e -> item { Column(Modifier.padding(horizontal = 16.dp)) {
                Text(e, color = Color.White, modifier = Modifier.testTag("vod_detail_error"))
                TextButton(onClick = { vm.open(st.title) }) { Text("Try again", color = Color.White) }
            } } }
            val d = st.detail ?: return@LazyColumn
            if (!t.playable && st.twin == null) item {
                Text("Show can't stream this title. Watch the trailer here, or add it to your Jellyfin library to play it.",
                    style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, modifier = Modifier.padding(horizontal = 16.dp).testTag("vod_info_only"))
            }
            t.overview?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.padding(16.dp)) } }
            if (t.genres.isNotEmpty()) item {
                Text(t.genres.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = ShowColors.Mist, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (d.seasons.isNotEmpty()) item { SeasonsBlock(st, d, onSeason = vm::selectSeason, onPlay = { eps, i -> playEpisode(ctx, vm, t, eps, i) }, progress = progress, title = t) }
            if (d.cast.isNotEmpty()) item {
                VodRowHeader("Cast")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("vod_cast")) {
                    items(d.cast, key = { it.name + it.role }) { p ->
                        PersonChip(p) { if (t.provider == Provider.TMDB && p.id != null) vm.openShortcut(VodShortcut(VodShortcut.Type.PERSON, p.name, Provider.TMDB, p.id)) }
                    }
                }
            }
            if (d.crew.isNotEmpty()) item {
                Text(d.crew.groupBy { it.role ?: "Crew" }.entries.joinToString("\n") { (role, people) -> "$role: ${people.joinToString { it.name }}" },
                    style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, modifier = Modifier.padding(16.dp))
            }
            val orgs = d.companies.map { it to VodShortcut.Type.COMPANY } + d.networks.map { it to VodShortcut.Type.NETWORK }
            if (orgs.isNotEmpty()) item {
                VodRowHeader(if (d.networks.isNotEmpty()) "Networks & Studios" else "Production")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("vod_orgs")) {
                    items(orgs, key = { it.second.name + it.first }) { (name, type) ->
                        val id = companyIds[name]
                        val sc = VodShortcut(type, name, if (id != null) Provider.TMDB else t.provider, id)
                        AssistChip(onClick = { if (id != null) vm.openShortcut(sc) else vm.toggleShortcut(sc) }, label = { Text(name) },
                            leadingIcon = { Icon(if (type == VodShortcut.Type.NETWORK) Icons.Rounded.Tv else Icons.Rounded.Business, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(labelColor = Color.White, leadingIconContentColor = Color.White))
                    }
                }
            }
            if (d.recommendations.isNotEmpty()) item {
                VodRowHeader("More Like This")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("vod_recs")) {
                    items(d.recommendations, key = { it.key }) { r -> PosterCard(r, metrics.cardWidth * 0.62f, vm::open) }
                }
            }
            item {
                Text(listOfNotNull(d.license?.let { "Licence: $it" }, d.sourcePage?.let { "Source: $it" }).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, modifier = Modifier.padding(16.dp).testTag("vod_source_line"))
            }
        }
        IconButton(onClick = { vm.closeDetail() }, modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).testTag("vod_detail_close")) {
            Icon(Icons.Rounded.Close, "Close", tint = Color.White)
        }
    }
}

private fun playEpisode(ctx: Context, vm: VodViewModel, t: VodTitle, eps: List<VodEpisode>, index: Int) {
    val start = vm.library.positionFor(t, eps[index].id)?.takeIf { it.resumable }?.positionMs ?: 0
    VodPlayerActivity.start(ctx, VodPlayRequest(t, episodes = eps, episodeIndex = index, startMs = start))
}

@Composable
private fun SeasonsBlock(st: VodDetailState, d: VodDetail, onSeason: (Int) -> Unit, onPlay: (List<VodEpisode>, Int) -> Unit, progress: Map<String, VodProgress>, title: VodTitle) {
    Column(Modifier.testTag("vod_seasons")) {
        VodRowHeader("Episodes")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.seasons, key = { it.number }) { s -> Pill(s.name, s.number == st.season, "vod_season_${s.number}") { onSeason(s.number) } }
        }
        val season = d.seasons.firstOrNull { it.number == st.season }
        when {
            st.seasonLoading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(16.dp))
            st.seasonError != null -> Text(st.seasonError, color = Color.White, modifier = Modifier.padding(16.dp))
            season?.episodes != null -> {
                val playable = season.episodes.filter { it.sources.isNotEmpty() }
                season.episodes.forEach { e ->
                    val p = progress["${title.key}#${e.id}"]
                    val idx = playable.indexOf(e)
                    Row(Modifier.fillMaxWidth().clickable(enabled = idx >= 0) { onPlay(playable, idx) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("vod_episode_${e.season}_${e.number}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(ShowColors.Surface)) {
                            AsyncImage(e.stillUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (idx >= 0) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                            p?.let { Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it.fraction).height(3.dp).background(Color.White)) }
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("${e.number}. ${e.title}", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(e.runtimeSec?.let { "${it / 60} min" }, if (idx < 0) "Info only" else null).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = ShowColors.Mist)
                            e.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonChip(p: VodPerson, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(ShowColors.Surface), contentAlignment = Alignment.Center) {
            if (p.photoUrl != null) AsyncImage(p.photoUrl, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(p.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), color = Color.White)
        }
        Text(p.name, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        p.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ShowColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ------------------------------------------------------------------ Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VodSettingsSheet(vm: VodViewModel, onDismiss: () -> Unit) {
    val config by vm.settings.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf(config.jellyfin?.server ?: "") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var jfMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = ShowColors.Hairline.copy(alpha = 0.4f), cursorColor = Color.White)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ShowColors.Night) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("vod_settings_sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Movies & Series", style = MaterialTheme.typography.titleLarge, color = Color.White)

            Text("Jellyfin (your own library, played in full)", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (config.jellyfin != null) {
                Text("Signed in to ${config.jellyfin!!.server} as ${config.jellyfin!!.userName}.", color = ShowColors.Mist, style = MaterialTheme.typography.bodySmall)
                ShowSecondaryButton(onClick = { vm.settings.setJellyfin(null) }, modifier = Modifier.testTag("jf_sign_out")) { Text("Sign out") }
            } else {
                OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth().testTag("jf_server"))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth().testTag("jf_user"))
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, colors = fieldColors,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("jf_pass"))
                ShowPrimaryButton(enabled = !busy && server.isNotBlank() && user.isNotBlank(), onClick = {
                    busy = true; jfMsg = null
                    scope.launch { jfMsg = vm.signInJellyfin(server, user, pass); busy = false; if (jfMsg == null) pass = "" }
                }, modifier = Modifier.testTag("jf_sign_in")) { Text(if (busy) "Signing in…" else "Sign in") }
                Text("Only the session token is stored, never your password.", color = ShowColors.Mist, style = MaterialTheme.typography.bodySmall)
            }
            jfMsg?.let { Text(it, color = ShowColors.Danger, modifier = Modifier.testTag("jf_error")) }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Text("TMDB (info, cast, trailers)", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(if (config.tmdbKey != null) "A TMDB key is set${if (vm.settings.hasOwnTmdbKey) " (yours)" else " (built in)"}." else "No key: series info comes from TVmaze and there's no movie discovery. Get a free key at themoviedb.org → Settings → API.",
                color = ShowColors.Mist, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, label = { Text("TMDB API key or read token") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth().testTag("tmdb_key"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShowPrimaryButton(onClick = { keyMsg = vm.settings.setTmdbKey(key) ?: "Saved."; if (keyMsg == "Saved.") key = "" }, modifier = Modifier.testTag("tmdb_save")) { Text("Save key") }
                if (vm.settings.hasOwnTmdbKey) ShowSecondaryButton(onClick = { vm.settings.setTmdbKey(""); keyMsg = "Removed." }) { Text("Remove") }
            }
            keyMsg?.let { Text(it, color = if (it == "Saved." || it == "Removed.") ShowColors.Mist else ShowColors.Danger, modifier = Modifier.testTag("tmdb_msg")) }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Internet Archive classics", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Public-domain and Creative Commons films and TV, free to stream.", color = ShowColors.Mist, style = MaterialTheme.typography.bodySmall)
                }
                Switch(config.archive, vm.settings::setArchive, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.White),
                    modifier = Modifier.testTag("toggle_archive"))
            }
            TextButton(onClick = { vm.library.clearHistory() }, modifier = Modifier.testTag("vod_clear_history")) { Text("Clear Movies watch history", color = Color.White) }
        }
    }
}
