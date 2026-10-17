package app.show.tv.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.show.tv.data.CatalogEnricher
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
) : ViewModel() {

    private val indexRepos = HashMap<PlaylistSource, PlaylistRepository>()

    /** Unfiltered playlist; favourites and history resolve against it so hiding never loses them. */
    private var raw: List<Channel> = emptyList()
    private var rawById: Map<String, Channel> = emptyMap()
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
        ids.mapNotNull { rawById[it] ?: library.snapshot(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<Channel>> = combine(_state, library.history) { _, entries ->
        entries.mapNotNull { rawById[it.channelId] ?: library.snapshot(it.channelId) }
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
        val provider = indexRepository ?: return
        val sources = settings.value.source?.enrichFrom ?: PlaylistSource.CUSTOM_ENRICH
        val indexes = coroutineScope {
            sources.map { src ->
                async {
                    val repo = synchronized(indexRepos) { indexRepos.getOrPut(src) { provider(src) } }
                    runCatching { src.grouping to repo.refresh().playlist }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (indexes.isEmpty()) return
        val enriched = withContext(compute) { CatalogEnricher.enrich(base, indexes) }
        publish(enriched, at, notice, stillLoading = false)
    }

    private suspend fun publish(channels: List<Channel>, at: Long, notice: String?, stillLoading: Boolean) {
        raw = channels; rawAt = at; rawNotice = notice
        val hideGeo = settings.value.hideGeoBlocked
        val built = withContext(compute) {
            rawById = channels.associateBy { it.id }
            val catalog = ChannelCatalog(if (hideGeo) channels.filterNot { it.isGeoBlocked } else channels)
            val rows = catalog.rows(settings.value.grouping).map { HomeRow(it.first, it.second) }
            Triple(catalog, catalog.featured(daySeed()), rows)
        }
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

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "You're offline. Connect to the internet to load channels."
        is java.net.SocketTimeoutException -> "The channel list is taking too long. Pull down to retry."
        else -> t.message ?: "Couldn't load channels."
    }

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo, lib, settingsStore = settings, indexRepository = indexes) as T
    }
}
