package app.show.tv.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

enum class VodFilter(val label: String) { ALL("All"), MOVIES("Movies"), SERIES("Series") }

data class VodCatalogState(
    val loading: Boolean = true,
    val rows: List<VodRow> = emptyList(),
    val failed: List<Provider> = emptyList(),
    val error: String? = null,
)

data class VodDetailState(
    val title: VodTitle,
    val loading: Boolean = true,
    val detail: VodDetail? = null,
    val error: String? = null,
    /** The viewer's Jellyfin copy of an info-only title. */
    val twin: VodTitle? = null,
    val season: Int? = null,
    val seasonLoading: Boolean = false,
    val seasonError: String? = null,
)

data class ShortcutState(val shortcut: VodShortcut, val loading: Boolean = true, val items: List<VodTitle> = emptyList(), val error: String? = null)

class VodViewModel(
    private val repo: VodRepository,
    val library: VodLibrary,
    val settings: VodSettings,
) : ViewModel() {
    private val _catalog = MutableStateFlow(VodCatalogState())
    val catalog: StateFlow<VodCatalogState> = _catalog.asStateFlow()
    private val _filter = MutableStateFlow(VodFilter.ALL)
    val filter: StateFlow<VodFilter> = _filter.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<VodTitle>?>(null)
    val results: StateFlow<List<VodTitle>?> = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _detail = MutableStateFlow<VodDetailState?>(null)
    val detail: StateFlow<VodDetailState?> = _detail.asStateFlow()
    private val _shortcut = MutableStateFlow<ShortcutState?>(null)
    val shortcut: StateFlow<ShortcutState?> = _shortcut.asStateFlow()
    private val history = ArrayDeque<VodTitle>()
    private var detailJob: Job? = null
    private var loaded = false

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _query.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) { _results.value = null; _searching.value = false; return@collectLatest }
                _searching.value = true
                _results.value = runCatching { repo.search(q) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
                _searching.value = false
            }
        }
        viewModelScope.launch { settings.config.drop(1).collect { repo.invalidate(); load(force = true) } }
    }

    /** Loads lazily the first time the tab is opened, so Live TV launches pay nothing for it. */
    fun ensureLoaded() { if (!loaded) { loaded = true; load() } }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _catalog.value = _catalog.value.copy(loading = true, error = null)
            val r = runCatching { repo.catalog(force) }.onFailure { if (it is CancellationException) throw it }
            _catalog.value = r.fold(
                { c -> VodCatalogState(false, c.rows, c.failed, if (c.rows.isEmpty()) "Movies couldn't load. Check your connection." else null) },
                { e -> VodCatalogState(false, _catalog.value.rows, error = friendly(e)) },
            )
        }
    }

    fun setFilter(f: VodFilter) { _filter.value = f }
    fun onQuery(q: String) { _query.value = q }

    fun open(t: VodTitle) {
        _detail.value?.title?.let { if (it.key != t.key) history.addLast(it) }
        if (history.size > 20) history.removeFirst()
        load(t)
    }

    private fun load(t: VodTitle) {
        detailJob?.cancel()
        _detail.value = VodDetailState(t)
        detailJob = viewModelScope.launch {
            val r = runCatching { repo.detail(t) }.onFailure { if (it is CancellationException) throw it }
            val d = r.getOrNull()
            val twin = if (d != null && !t.playable) runCatching { repo.jellyfinTwin(d.title) }.getOrNull() else null
            val first = d?.seasons?.firstOrNull()?.number
            _detail.value = VodDetailState(t, false, d, r.exceptionOrNull()?.let(::friendly), twin, first)
            if (first != null && d.seasons.first().episodes == null) selectSeason(first)
        }
    }

    /** Back from a detail page: the previous one (recommendation chains), else close. */
    fun back(): Boolean {
        val prev = history.removeLastOrNull()
        return if (prev != null) { load(prev); true } else { _detail.value = null; false }
    }

    fun closeDetail() { history.clear(); detailJob?.cancel(); _detail.value = null }

    fun selectSeason(n: Int) {
        val st = _detail.value ?: return
        val d = st.detail ?: return
        val s = d.seasons.firstOrNull { it.number == n } ?: return
        _detail.value = st.copy(season = n, seasonError = null, seasonLoading = s.episodes == null)
        if (s.episodes != null) return
        viewModelScope.launch {
            val r = runCatching { repo.season(st.title, s) }.onFailure { if (it is CancellationException) throw it }
            val cur = _detail.value ?: return@launch
            if (cur.title.key != st.title.key) return@launch
            val loadedSeason = r.getOrNull()
            _detail.value = cur.copy(
                seasonLoading = false, seasonError = r.exceptionOrNull()?.let(::friendly),
                detail = cur.detail?.copy(seasons = cur.detail.seasons.map { if (it.number == n && loadedSeason != null) loadedSeason else it }),
            )
        }
    }

    fun openShortcut(s: VodShortcut) {
        _shortcut.value = ShortcutState(s)
        viewModelScope.launch {
            val r = runCatching { repo.browse(s) }.onFailure { if (it is CancellationException) throw it }
            _shortcut.value = ShortcutState(s, false, r.getOrDefault(emptyList()), r.exceptionOrNull()?.let(::friendly)
                ?: if (r.getOrNull().isNullOrEmpty()) "Nothing to show for ${s.name} yet." else null)
        }
    }
    fun closeShortcut() { _shortcut.value = null }

    fun toggleList(t: VodTitle) = library.toggle(t)
    fun toggleShortcut(s: VodShortcut) = library.toggle(s)

    /** TMDB company/network ids, so a detail page's companies can be saved as shortcuts. */
    suspend fun companyIds(t: VodTitle): Map<String, String> =
        (repo.source(Provider.TMDB) as? TmdbSource)?.let { runCatching { it.companyIds(t) }.getOrNull() } ?: emptyMap()

    suspend fun signInJellyfin(server: String, user: String, password: String): String? = runCatching {
        settings.setJellyfin(JellyfinSource.signIn(repo.http(), server, user, password, settings.deviceId))
    }.fold({ null }, { friendly(it) })

    class Factory(private val repo: VodRepository, private val library: VodLibrary, private val settings: VodSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VodViewModel(repo, library, settings) as T
    }

    companion object {
        fun friendly(e: Throwable): String = when {
            e is java.net.UnknownHostException -> "You're offline. Check your connection."
            e is java.net.SocketTimeoutException -> "The server took too long to answer."
            e is VodHttp.HttpError && e.code == 401 -> "The provider refused the key or session. Check Movies settings."
            e is VodHttp.HttpError && e.code == 429 -> "Too many requests. Try again in a moment."
            e is VodHttp.HttpError -> "The server answered ${e.code}."
            e is IllegalStateException || e is IllegalArgumentException -> e.message ?: "Something went wrong."
            else -> "Something went wrong loading this."
        }
    }
}
