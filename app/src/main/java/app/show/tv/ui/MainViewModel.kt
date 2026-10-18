package app.show.tv.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.show.tv.data.CatalogEnricher
import app.show.tv.data.ExtraSources
import app.show.tv.data.Channel
import app.show.tv.data.PlaylistSource
import app.show.tv.data.ChannelCatalog
import app.show.tv.data.LibraryStore
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.Settings
import app.show.tv.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class HomeRow(val title: String, val channels: List<Channel>)

@Immutable
data class CatalogState(
    val loading: Boolean = true,
    val catalog: ChannelCatalog = ChannelCatalog.EMPTY,
    val featured: List<Channel> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
    val offlineNotice: String? = null,
    val updatedAt: Long = 0,
)

@Immutable
data class SearchState(val query: String = "", val category: String? = null, val results: List<Channel> = emptyList())

class MainViewModel(
    private val repository: PlaylistRepository,
    private val library: LibraryStore,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val daySeed: () -> Long = { System.currentTimeMillis() / 86_400_000L },
    private val settingsStore: SettingsStore? = null,
    /** Supplies the other iptv-org indexes used to add countries/languages/categories (null = off). */
    private val indexRepository: ((PlaylistSource) -> PlaylistRepository)? = null,
    /** The verified extra source (Free-TV) and its allowlist; null = off (tests, custom builds). */
    private val extraRepository: (() -> PlaylistRepository)? = null,
    private val allowlist: () -> ExtraSources.Allowlist = { ExtraSources.Allowlist.EMPTY },
) : ViewModel() {

    private val extraRepo by lazy { extraRepository?.invoke() }

    private val indexRepos = HashMap<PlaylistSource, PlaylistRepository>()

    /** Unfiltered playlist; favourites and history resolve against it so hiding never loses them. */
    private var raw: List<Channel> = emptyList()
    private var rawById: Map<String, Channel> = emptyMap()
    private var rawByIdentity: Map<String, Channel> = emptyMap()
    private var rawAt: Long = 0
    private var rawNotice: String? = null
    private var loadJob: Job? = null

    val settings: StateFlow<Settings> = settingsStore?.settings ?: MutableStateFlow(Settings())

    /** Channels whose last attempt in the past 24 h failed with a dead-link error. */
    val unavailableIds: StateFlow<Set<String>> = library.unavailable.map { library.recentlyUnavailable() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.recentlyUnavailable())

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    private val _searchCategory = MutableStateFlow<String?>(null)

    val favorites: StateFlow<List<Channel>> = combine(_state, library.favorites) { _, ids ->
        ids.mapNotNull(::resolveSaved).distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<Channel>> = combine(_state, library.history) { _, entries ->
        entries.mapNotNull { resolveSaved(it.channelId) }.distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteIds: StateFlow<Set<String>> = library.favorites.map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, library.favorites.value.toSet())

    @OptIn(FlowPreview::class)
    val search: StateFlow<SearchState> = combine(_query.debounce(180), _searchCategory, _state) { q, cat, s ->
        SearchState(q, cat, if (q.isBlank()) emptyList() else s.catalog.search(q, cat))
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    val queryText: StateFlow<String> = _query.asStateFlow()
    val searchCategory: StateFlow<String?> = _searchCategory.asStateFlow()

    init {
        load()
        settingsStore?.let { store ->
            viewModelScope.launch {
                var last = store.settings.value
                store.settings.collect { now ->
                    when {
                        now.playlistUrl != last.playlistUrl -> { raw = emptyList(); rawById = emptyMap(); _state.value = CatalogState(); load() }
                        now.hideGeoBlocked != last.hideGeoBlocked && raw.isNotEmpty() -> publish(raw, rawAt, rawNotice, _state.value.loading)
                        now.extraSources != last.extraSources || now.embeds != last.embeds -> load()
                    }
                    last = now
                }
            }
        }
    }

    /** Starts a (re)load, cancelling any load already in flight so results can't arrive out of order. */
    fun load(forceNetwork: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (!forceNetwork && _state.value.catalog.channels.isEmpty()) {
                repository.loadCached()?.let { cached -> publish(cached.playlist.channels, cached.fetchedAt, null, stillLoading = true) }
            }
            try {
                val result = repository.refresh()
                val notice = (result.source as? PlaylistRepository.Source.Cache)?.let {
                    "Offline — showing channels saved ${relativeAge(result.fetchedAt)}."
                }
                val base = withContext(compute) { CatalogEnricher.dedupe(result.playlist.channels) }
                publish(base, result.fetchedAt, notice, stillLoading = false)
                enrich(base, result.fetchedAt, notice)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(loading = false, error = if (it.catalog.channels.isEmpty()) friendly(t) else null,
                        offlineNotice = if (it.catalog.channels.isNotEmpty()) friendly(t) else null)
                }
            }
        }
    }

    /**
     * Second pass: fetch the indexes this source isn't grouped by (in parallel, conditional GET,
     * cached), then republish with every channel's categories, countries and languages filled in.
     * A failed index just leaves that dimension as the source had it.
     */
    private suspend fun enrich(base: List<Channel>, at: Long, notice: String?) {
        var channels = base
        val provider = indexRepository
        if (provider != null) {
            val sources = settings.value.source?.enrichFrom ?: PlaylistSource.CUSTOM_ENRICH
            val indexes = coroutineScope {
                sources.map { src ->
                    async {
                        val repo = synchronized(indexRepos) { indexRepos.getOrPut(src) { provider(src) } }
                        runCatching { src.grouping to repo.refreshIfOlderThan(SECONDARY_MAX_AGE_MS).playlist }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                            .getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            if (indexes.isNotEmpty()) {
                channels = withContext(compute) { CatalogEnricher.enrich(channels, indexes) }
                publish(channels, at, notice, stillLoading = false)
            }
        }
        mergeExtra(channels, at, notice)
    }

    /**
     * Third pass: verified Free-TV links. Matches become backup links (no duplicate tiles), the rest
     * are added as new channels. Skipped for the viewer's own playlist and when turned off.
     */
    private suspend fun mergeExtra(base: List<Channel>, at: Long, notice: String?) {
        val repo = extraRepo ?: return
        val s = settings.value
        val source = s.source ?: return
        if (!s.extraSources) return
        val allow = withContext(kotlinx.coroutines.Dispatchers.IO) { allowlist() } // may refresh over the network (daily)
        if (allow.isEmpty) return
        val extra = runCatching { repo.refreshIfOlderThan(SECONDARY_MAX_AGE_MS).playlist.channels }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return
        val merged = withContext(compute) {
            ExtraSources.merge(base, extra, allow, countryFilter = if (source == PlaylistSource.INDIA) "India" else null, includeEmbeds = s.embeds)
        }
        if (merged !== base) publish(merged, at, notice, stillLoading = false)
    }

    /** A saved id: the live entry, else the live entry for the same channel at a new address, else the snapshot. */
    private fun resolveSaved(id: String): Channel? =
        rawById[id] ?: library.snapshot(id)?.let { snap -> rawByIdentity[snap.identity] ?: snap }

    private suspend fun publish(channels: List<Channel>, at: Long, notice: String?, stillLoading: Boolean) {
        raw = channels; rawAt = at; rawNotice = notice
        val hideGeo = settings.value.hideGeoBlocked
        val built = withContext(compute) {
            rawById = channels.associateBy { it.id }
            rawByIdentity = HashMap<String, Channel>(channels.size * 2).also { m -> channels.forEach { m.putIfAbsent(it.identity, it) } }
            val catalog = ChannelCatalog(if (hideGeo) channels.filterNot { it.isGeoBlocked } else channels)
            val rows = catalog.rows(settings.value.grouping).map { HomeRow(it.first, it.second) }
            // Keep the hero on the same channels across the enrichment passes (cache, network, indexes,
            // Free-TV). 1.5.2 re-picked them on every pass, so the top shelf jumped 2-3 times after launch.
            val previous = _state.value.featured
            val byId = catalog.channels.associateBy { it.id }
            val kept = previous.mapNotNull { byId[it.id] }
            val featured = if (previous.isNotEmpty() && kept.size == previous.size) kept else catalog.featured(daySeed())
            Triple(catalog, featured, rows)
        }
        // Saved channels whose link was replaced upstream follow the channel to its new address.
        if (!stillLoading) library.reconcile(isCurrent = { it in rawById }, current = { rawByIdentity[it.identity] })
        // A search category from a previous source that this catalog doesn't have would match nothing.
        _searchCategory.value?.let { if (it !in built.first.categories) _searchCategory.value = null }
        _state.value = CatalogState(
            loading = stillLoading, catalog = built.first, featured = built.second, rows = built.third,
            error = null, offlineNotice = notice, updatedAt = at,
        )
    }

    fun onQueryChange(q: String) { _query.value = q.take(80) }
    fun onSearchCategory(c: String?) { _searchCategory.value = c }
    fun toggleFavorite(id: String) = library.toggleFavorite(id)
    fun toggleFavorite(channel: Channel) = library.toggleFavorite(channel)
    fun setSource(source: PlaylistSource) { settingsStore?.setSource(source) }
    fun clearHistory() = library.clearHistory()
    fun setPlaylistUrl(url: String): SettingsStore.UrlResult =
        settingsStore?.setPlaylistUrl(url) ?: SettingsStore.UrlResult.Invalid("Settings unavailable")
    fun setHideGeoBlocked(v: Boolean) { settingsStore?.setHideGeoBlocked(v) }
    fun setHideUnavailable(v: Boolean) { settingsStore?.setHideUnavailable(v) }
    fun setEmbeds(v: Boolean) = settingsStore?.setEmbeds(v)
    fun setExtraSources(v: Boolean) { settingsStore?.setExtraSources(v) }

    /** Called when the app returns to the foreground: links rotate daily, so re-check a stale list. */
    fun refreshIfStale(maxAgeMs: Long = STALE_AFTER_MS, now: Long = System.currentTimeMillis()) {
        val s = _state.value
        if (s.loading || s.catalog.channels.isEmpty()) return
        if (now - s.updatedAt >= maxAgeMs) load()
    }

    // 1.5.3: look through the repository's PlaylistException wrapper, which used to leak the raw
    // 'Unable to resolve host "iptv-org.github.io"' text onto the empty screen when offline.
    private fun friendly(t: Throwable): String = LoadMessages.friendlyMessage(t)


    private fun relativeAge(at: Long): String {
        val mins = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 1440 -> "${mins / 60} h ago"
            else -> "${mins / 1440} days ago"
        }
    }

    class Factory(
        private val repo: PlaylistRepository, private val lib: LibraryStore, private val settings: SettingsStore,
        private val indexes: ((PlaylistSource) -> PlaylistRepository)? = repo::forSource,
        private val extra: (() -> PlaylistRepository)? = null,
        private val allowlist: () -> ExtraSources.Allowlist = { ExtraSources.Allowlist.EMPTY },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo, lib, settingsStore = settings, indexRepository = indexes, extraRepository = extra, allowlist = allowlist) as T
    }

    companion object {
        const val STALE_AFTER_MS = 6L * 60 * 60 * 1000
        /** Countries/languages indexes and Free-TV only add metadata; re-check them at most this often. */
        const val SECONDARY_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}

internal object LoadMessages {
    fun friendlyMessage(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(6).toList()
        return when {
            chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException } ->
                "You're offline. Connect to the internet to load channels."
            chain.any { it is java.net.SocketTimeoutException } -> "The channel list is taking too long. Pull down to retry."
            chain.any { it is javax.net.ssl.SSLException } -> "Couldn't make a secure connection to the channel list. Check the date and time, then retry."
            else -> t.message?.takeIf { it.length < 120 && "Exception" !in it } ?: "Couldn't load channels."
        }
    }
}
