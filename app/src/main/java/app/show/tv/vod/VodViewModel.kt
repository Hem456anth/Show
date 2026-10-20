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
    /** Parts of the movie's TMDB collection, release order (1.8.0); null until loaded or when there is none. */
    val collection: List<VodTitle>? = null,
)

data class ShortcutState(
    val shortcut: VodShortcut,
    val loading: Boolean = true,
    val items: List<VodTitle> = emptyList(),
    val error: String? = null,
    val page: Int = 0,
    val totalPages: Int = 1,
    val loadingMore: Boolean = false,
) {
    val hasMore: Boolean get() = page < totalPages
}

/** Trakt connection state for Movies settings (1.8.0). */
sealed class TraktUi {
    data object Idle : TraktUi()
    data class Code(val userCode: String, val url: String, val expiresAtMs: Long) : TraktUi()
    data class Busy(val text: String) : TraktUi()
    data class Message(val text: String, val error: Boolean = false) : TraktUi()
}

class VodViewModel(
    private val repo: VodRepository,
    val library: VodLibrary,
    val settings: VodSettings,
    val trakt: TraktClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
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
    private val _genre = MutableStateFlow<String?>(null)
    /** Genre chip on Browse (1.8.0); null = all genres. */
    val genre: StateFlow<String?> = _genre.asStateFlow()
    private val _traktUi = MutableStateFlow<TraktUi>(TraktUi.Idle)
    val traktUi: StateFlow<TraktUi> = _traktUi.asStateFlow()
    private val _traktHistory = MutableStateFlow<List<VodTitle>>(emptyList())
    val traktHistory: StateFlow<List<VodTitle>> = _traktHistory.asStateFlow()
    private var traktJob: Job? = null
    private var shortcutJob: Job? = null
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
    fun ensureLoaded() {
        if (loaded) return
        loaded = true; load()
        val tr = trakt
        if (tr != null && tr.configured && tr.signedIn.value) viewModelScope.launch { _traktHistory.value = runCatching { tr.history() }.getOrDefault(emptyList()) }
    }

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
    fun setGenre(g: String?) { _genre.value = if (_genre.value == g) null else g }
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
            d?.collection?.let { ref ->
                val parts = runCatching { repo.collection(ref) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                val cur = _detail.value
                if (cur != null && cur.title.key == t.key && !parts.isNullOrEmpty()) _detail.value = cur.copy(collection = parts)
            }
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

    /** Opens a studio, network, person or collection page; TMDB discover pages load more with [loadMoreShortcut]. */
    fun openShortcut(s: VodShortcut) {
        shortcutJob?.cancel()
        _shortcut.value = ShortcutState(s)
        shortcutJob = viewModelScope.launch {
            val r = runCatching { repo.browsePage(s, 1) }.onFailure { if (it is CancellationException) throw it }
            val p = r.getOrNull()
            _shortcut.value = ShortcutState(s, false, p?.items.orEmpty(), r.exceptionOrNull()?.let(::friendly)
                ?: if (p?.items.isNullOrEmpty()) "Nothing to show for ${s.name} yet." else null, p?.page ?: 0, p?.totalPages ?: 1)
        }
    }

    fun loadMoreShortcut() {
        val st = _shortcut.value ?: return
        if (st.loading || st.loadingMore || !st.hasMore) return
        _shortcut.value = st.copy(loadingMore = true)
        shortcutJob = viewModelScope.launch {
            val r = runCatching { repo.browsePage(st.shortcut, st.page + 1) }.onFailure { if (it is CancellationException) throw it }
            val cur = _shortcut.value ?: return@launch
            if (cur.shortcut.key != st.shortcut.key) return@launch
            val p = r.getOrNull()
            _shortcut.value = if (p == null) cur.copy(loadingMore = false, totalPages = cur.page) // stop paging after an error
            else cur.copy(loadingMore = false, items = (cur.items + p.items).distinctBy { it.key }, page = p.page, totalPages = p.totalPages)
        }
    }

    fun closeShortcut() { shortcutJob?.cancel(); _shortcut.value = null }

    fun toggleList(t: VodTitle) {
        val adding = !library.inList(t.key)
        library.toggle(t)
        // Keep the Trakt watchlist in step with My List (best effort; titles need a TMDB id).
        val tr = trakt ?: return
        if (tr.signedIn.value && tr.configured && t.tmdbId != null) viewModelScope.launch {
            runCatching { if (adding) tr.addToWatchlist(listOf(t)) else tr.removeFromWatchlist(listOf(t)) }
        }
    }
    fun toggleShortcut(s: VodShortcut) = library.toggle(s)

    /** Marks a movie or an episode watched here, on Jellyfin and on Trakt when connected (1.8.0). */
    fun markWatched(t: VodTitle, ep: VodEpisode? = null) {
        library.markWatched(t, ep?.id, ep?.let { "${it.code} · ${it.title}" })
        viewModelScope.launch {
            if (t.provider == Provider.JELLYFIN) (repo.source(Provider.JELLYFIN) as? JellyfinSource)?.markPlayed(ep?.id ?: t.providerId)
            val tr = trakt
            val item = TraktItem.from(t, ep)
            if (tr != null && tr.signedIn.value && tr.configured && item != null) runCatching { tr.addToHistory(item) }
        }
    }

    // ---------------------------------------------------------------- Trakt (1.8.0)

    /** Device-code sign-in: shows the code, polls at Trakt's interval until approved, denied or expired. */
    fun connectTrakt() {
        val tr = trakt ?: return
        traktJob?.cancel()
        traktJob = viewModelScope.launch {
            _traktUi.value = TraktUi.Busy("Asking Trakt for a code…")
            val code = runCatching { tr.deviceCode() }.onFailure { if (it is CancellationException) throw it }.getOrElse {
                _traktUi.value = TraktUi.Message(friendly(it), error = true); return@launch
            }
            val expires = clock() + code.expiresInSec * 1000L
            _traktUi.value = TraktUi.Code(code.userCode, code.verificationUrl, expires)
            var interval = code.intervalSec * 1000L
            while (clock() < expires) {
                kotlinx.coroutines.delay(interval)
                val r = runCatching { tr.poll(code) }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: continue
                when (r) {
                    TraktPoll.Pending -> {}
                    TraktPoll.SlowDown -> interval += 1000
                    is TraktPoll.Success -> { _traktUi.value = TraktUi.Busy("Connected. Syncing…"); tr.loadUser(); syncTraktNow(); return@launch }
                    TraktPoll.Denied -> { _traktUi.value = TraktUi.Message("Trakt sign-in was declined.", true); return@launch }
                    TraktPoll.Expired, TraktPoll.Invalid, TraktPoll.AlreadyUsed -> break
                }
            }
            _traktUi.value = TraktUi.Message("The code expired. Try connecting again.", true)
        }
    }

    fun cancelTrakt() { traktJob?.cancel(); _traktUi.value = TraktUi.Idle }

    fun signOutTrakt() {
        traktJob?.cancel()
        viewModelScope.launch { trakt?.signOut(); _traktHistory.value = emptyList(); _traktUi.value = TraktUi.Message("Disconnected from Trakt.") }
    }

    /** Watchlist -> My List, My List -> watchlist, then recent history. */
    fun syncTrakt() { traktJob?.cancel(); traktJob = viewModelScope.launch { _traktUi.value = TraktUi.Busy("Syncing with Trakt…"); syncTraktNow() } }

    private suspend fun syncTraktNow() {
        val tr = trakt ?: return
        val r = runCatching {
            val imported = library.importTitles(tr.watchlist())
            val sent = tr.addToWatchlist(library.myList.value)
            _traktHistory.value = runCatching { tr.history() }.getOrDefault(emptyList())
            imported to sent
        }.onFailure { if (it is CancellationException) throw it }
        _traktUi.value = r.fold({ (i, s) -> TraktUi.Message("Synced: $i new in My List, $s on your Trakt watchlist.") }, { TraktUi.Message(friendly(it), true) })
    }

    suspend fun signInJellyfin(server: String, user: String, password: String): String? = runCatching {
        settings.setJellyfin(JellyfinSource.signIn(repo.http(), server, user, password, settings.deviceId))
    }.fold({ null }, { friendly(it) })

    val hasTmdb: Boolean get() = repo.hasTmdb

    class Factory(private val repo: VodRepository, private val library: VodLibrary, private val settings: VodSettings, private val trakt: TraktClient? = null) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VodViewModel(repo, library, settings, trakt) as T
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
