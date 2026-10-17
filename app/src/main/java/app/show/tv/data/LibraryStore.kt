package app.show.tv.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A history row: channel id + when it was last opened. */
data class HistoryEntry(val channelId: String, val watchedAt: Long)

/**
 * "My List" favourites and watch history, persisted to private SharedPreferences.
 * Favourites and history are channel ids (hashed stream URLs). A small snapshot of each saved
 * channel (name, public stream link, logo, groups) is kept too, so My List and Continue watching
 * still work after switching to a playlist source that doesn't contain that channel.
 * Nothing personal is stored.
 */
class LibraryStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxHistory: Int = MAX_HISTORY,
) {
    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _unavailable = MutableStateFlow(readUnavailable())
    /** Channel id -> when it last failed with a non-recoverable error (404, 403, format…). */
    val unavailable: StateFlow<Map<String, Long>> = _unavailable.asStateFlow()

    fun isFavorite(id: String) = id in _favorites.value

    private var snapshots: Map<String, Channel> = readSnapshots()

    /** A saved channel's last known details, for ids the current playlist doesn't contain. */
    fun snapshot(id: String): Channel? = snapshots[id]

    /** Adds to My List (or removes), remembering the channel so it survives a source switch. */
    @Synchronized
    fun toggleFavorite(channel: Channel): Boolean {
        val added = toggleFavorite(channel.id)
        if (added) remember(channel) else prune()
        return added
    }

    @Synchronized
    fun recordWatched(channel: Channel) {
        recordWatched(channel.id)
        remember(channel)
    }

    private fun remember(channel: Channel) {
        snapshots = snapshots + (channel.id to channel)
        prune()
    }

    /** Keeps snapshots only for channels still in My List or history. */
    private fun prune() {
        val keep = _favorites.value.toSet() + _history.value.map { it.channelId }
        snapshots = snapshots.filterKeys { it in keep }
        prefs.edit().putString(KEY_SNAPSHOTS, snapshots.values.joinToString(REC) { encode(it) }).apply()
    }

    private fun readSnapshots(): Map<String, Channel> =
        prefs.getString(KEY_SNAPSHOTS, null).orEmpty().split(REC).mapNotNull(::decode).associateBy { it.id }

    /**
     * Re-attaches saved channels whose stream address changed upstream. Ids are URL hashes, so
     * when iptv-org replaces a channel's link (common for Zee, Colors, Star feeds) My List and
     * Up Next would keep playing the old, dead snapshot. [current] maps a saved channel to the
     * catalog's entry for the same [Channel.identity], or null when it isn't there.
     * Returns how many entries were moved to a new id.
     */
    @Synchronized
    fun reconcile(isCurrent: (String) -> Boolean, current: (Channel) -> Channel?): Int {
        val moves = HashMap<String, Channel>()
        for (id in _favorites.value + _history.value.map { it.channelId }) {
            if (id in moves || isCurrent(id)) continue
            val snap = snapshots[id] ?: continue
            val now = current(snap) ?: continue
            if (now.id != id) moves[id] = now
        }
        if (moves.isEmpty()) return 0
        val favs = _favorites.value.map { moves[it]?.id ?: it }.distinct()
        val hist = _history.value.map { e -> moves[e.channelId]?.let { HistoryEntry(it.id, e.watchedAt) } ?: e }.distinctBy { it.channelId }
        _favorites.value = favs
        _history.value = hist
        snapshots = snapshots + moves.values.associateBy { it.id }
        prefs.edit()
            .putString(KEY_FAVORITES, favs.joinToString(SEP))
            .putString(KEY_HISTORY, hist.joinToString(SEP) { "${it.channelId}:${it.watchedAt}" })
            .apply()
        prune()
        return moves.size
    }

    /** Remember a dead link so browsing can flag or hide it; cleared on the next success. */
    @Synchronized
    fun markUnavailable(id: String) {
        val now = clock()
        val next = LinkedHashMap<String, Long>(_unavailable.value).apply { remove(id); put(id, now) }
        while (next.size > MAX_UNAVAILABLE) next.remove(next.keys.first())
        writeUnavailable(next)
    }

    @Synchronized
    fun markAvailable(id: String) {
        if (id !in _unavailable.value) return
        writeUnavailable(_unavailable.value - id)
    }

    /** Ids that failed within [windowMs]; older failures are given another chance. */
    fun recentlyUnavailable(windowMs: Long = UNAVAILABLE_WINDOW_MS): Set<String> {
        val cutoff = clock() - windowMs
        return _unavailable.value.filterValues { it >= cutoff }.keys
    }

    private fun writeUnavailable(map: Map<String, Long>) {
        _unavailable.value = map
        prefs.edit().putString(KEY_UNAVAILABLE, map.entries.joinToString(SEP) { "${it.key}:${it.value}" }).apply()
    }

    private fun readUnavailable(): Map<String, Long> =
        prefs.getString(KEY_UNAVAILABLE, null).orEmpty().split(SEP).mapNotNull { token ->
            val parts = token.split(':')
            if (parts.size != 2 || parts[0].isBlank()) null else parts[1].toLongOrNull()?.let { parts[0] to it }
        }.toMap(LinkedHashMap())

    /** Returns the new state (true = now in My List). */
    @Synchronized
    fun toggleFavorite(id: String): Boolean {
        val current = _favorites.value
        val next = if (id in current) current - id else listOf(id) + current
        _favorites.value = next
        prefs.edit().putString(KEY_FAVORITES, next.joinToString(SEP)).apply()
        return id in next
    }

    @Synchronized
    fun recordWatched(id: String) {
        val now = clock()
        val next = (listOf(HistoryEntry(id, now)) + _history.value.filter { it.channelId != id }).take(maxHistory)
        _history.value = next
        prefs.edit().putString(KEY_HISTORY, next.joinToString(SEP) { "${it.channelId}:${it.watchedAt}" }).apply()
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        prune()
    }

    private fun readFavorites(): List<String> =
        prefs.getString(KEY_FAVORITES, null).orEmpty().split(SEP).filter { it.isNotBlank() }.distinct()

    private fun readHistory(): List<HistoryEntry> =
        prefs.getString(KEY_HISTORY, null).orEmpty().split(SEP).mapNotNull { token ->
            val parts = token.split(':')
            if (parts.size != 2) return@mapNotNull null
            parts[1].toLongOrNull()?.let { HistoryEntry(parts[0], it) }
        }.distinctBy { it.channelId }.take(maxHistory)

    companion object {
        const val MAX_HISTORY = 40
        const val MAX_UNAVAILABLE = 3000
        const val UNAVAILABLE_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val KEY_UNAVAILABLE = "unavailable"
        private const val SEP = ","
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HISTORY = "history"
        private const val KEY_SNAPSHOTS = "snapshots"
        // ASCII record/unit separators: can't occur in names or URLs once control chars are stripped.
        private const val REC = "\u001D"
        private const val FIELD = "\u001F"
        private const val LIST = "\u001E"

        private fun clean(s: String?) = s.orEmpty().filter { it >= ' ' }.take(2048)

        internal fun encode(c: Channel): String = listOf(
            c.id, c.name, c.streamUrl, c.logoUrl, c.categories.joinToString(LIST) { clean(it) },
            c.countries.joinToString(LIST) { clean(it) }, c.languages.joinToString(LIST) { clean(it) },
            c.resolution, c.userAgent, c.referrer, c.tvgId, c.flags.joinToString(LIST) { clean(it) },
        ).joinToString(FIELD) { if (it != null && (LIST in it)) it else clean(it) }

        internal fun decode(rec: String): Channel? {
            val f = rec.split(FIELD)
            if (f.size < 12 || f[0].isBlank() || f[2].isBlank()) return null
            fun opt(i: Int) = f[i].ifEmpty { null }
            fun list(i: Int) = f[i].split(LIST).filter { it.isNotEmpty() }
            val countries = list(5); val languages = list(6)
            return Channel(
                id = f[0], name = f[1].ifEmpty { f[2] }, streamUrl = f[2], logoUrl = opt(3),
                categories = list(4).ifEmpty { listOf(Channel.UNCATEGORISED) }, tvgId = opt(10),
                language = languages.firstOrNull(), country = countries.firstOrNull(), resolution = opt(7),
                flags = list(11).toSet(), userAgent = opt(8), referrer = opt(9), countries = countries, languages = languages,
            )
        }
    }
}
