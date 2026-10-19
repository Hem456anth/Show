package app.show.tv.vod

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Where the viewer is in a title: the episode (for series), position and duration. */
data class VodProgress(
    val title: VodTitle,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val episodeId: String? = null,
    val episodeLabel: String? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    /** Worth resuming: started (> 1 min or 3 %) and not finished (< 95 %). */
    val resumable: Boolean get() = durationMs > 0 && (positionMs > 60_000 || fraction > 0.03f) && fraction < FINISHED

    fun toJson(): JSONObject = JSONObject().put("t", title.toJson()).put("pos", positionMs).put("dur", durationMs)
        .put("at", updatedAt).putOpt("ep", episodeId).putOpt("epl", episodeLabel)

    companion object {
        const val FINISHED = 0.95f
        fun fromJson(o: JSONObject): VodProgress? {
            val t = VodTitle.fromJson(o.optJSONObject("t") ?: return null) ?: return null
            return VodProgress(t, o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optString("ep").ifEmpty { null }, o.optString("epl").ifEmpty { null })
        }
    }
}

/**
 * Movies & Series library on this device: My List (titles + company/network/cast shortcuts) and
 * watch progress. Separate from the Live TV [app.show.tv.data.LibraryStore] so the two experiences
 * never mix. Jellyfin titles are also synced to the server by the player.
 */
class VodLibrary(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    private val _list = MutableStateFlow(readList())
    private val _shortcuts = MutableStateFlow(readShortcuts())
    private val _progress = MutableStateFlow(readProgress())
    val myList: StateFlow<List<VodTitle>> = _list.asStateFlow()
    val shortcuts: StateFlow<List<VodShortcut>> = _shortcuts.asStateFlow()
    /** Newest first, keyed by title key; also per-episode positions under "<title key>#<episode id>". */
    val progress: StateFlow<Map<String, VodProgress>> = _progress.asStateFlow()

    fun continueWatching(): List<VodProgress> =
        _progress.value.filterKeys { '#' !in it }.values.filter { it.resumable || it.episodeId != null && it.fraction < VodProgress.FINISHED }
            .sortedByDescending { it.updatedAt }.take(MAX_CONTINUE)

    fun inList(key: String) = _list.value.any { it.key == key }

    fun toggle(title: VodTitle) {
        _list.value = if (inList(title.key)) _list.value.filterNot { it.key == title.key } else listOf(title) + _list.value
        save()
    }

    fun toggle(shortcut: VodShortcut) {
        val has = _shortcuts.value.any { it.key == shortcut.key }
        _shortcuts.value = if (has) _shortcuts.value.filterNot { it.key == shortcut.key } else listOf(shortcut) + _shortcuts.value
        save()
    }

    fun hasShortcut(s: VodShortcut) = _shortcuts.value.any { it.key == s.key }

    fun positionFor(title: VodTitle, episodeId: String? = null): VodProgress? =
        _progress.value[if (episodeId == null) title.key else "${title.key}#$episodeId"]

    /** Saves a position; for series, also moves the series' Continue Watching entry to this episode. */
    fun record(title: VodTitle, positionMs: Long, durationMs: Long, episodeId: String? = null, episodeLabel: String? = null) {
        if (durationMs <= 0) return
        val now = clock()
        val p = VodProgress(title, positionMs.coerceIn(0, durationMs), durationMs, now, episodeId, episodeLabel)
        val m = _progress.value.toMutableMap()
        if (episodeId != null) m["${title.key}#$episodeId"] = p
        m[title.key] = p
        // Bound storage: keep the newest entries.
        _progress.value = m.entries.sortedByDescending { it.value.updatedAt }.take(MAX_PROGRESS).associate { it.key to it.value }
        save()
    }

    fun removeProgress(key: String) {
        _progress.value = _progress.value.filterKeys { it != key && !it.startsWith("$key#") }
        save()
    }

    fun clearHistory() { _progress.value = emptyMap(); save() }

    private fun save() {
        prefs.edit()
            .putString(K_LIST, JSONArray(_list.value.map { it.toJson() }).toString())
            .putString(K_SHORTCUTS, JSONArray(_shortcuts.value.map { it.toJson() }).toString())
            .putString(K_PROGRESS, JSONObject().also { o -> _progress.value.forEach { (k, v) -> o.put(k, v.toJson()) } }.toString())
            .apply()
    }

    private fun readList() = runCatching { JSONArray(prefs.getString(K_LIST, "[]")).objs().mapNotNull(VodTitle::fromJson) }.getOrDefault(emptyList())
    private fun readShortcuts() = runCatching { JSONArray(prefs.getString(K_SHORTCUTS, "[]")).objs().mapNotNull(VodShortcut::fromJson) }.getOrDefault(emptyList())
    private fun readProgress(): Map<String, VodProgress> = runCatching {
        val o = JSONObject(prefs.getString(K_PROGRESS, "{}")!!)
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let(VodProgress::fromJson)?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        private const val K_LIST = "vod_list"
        private const val K_SHORTCUTS = "vod_shortcuts"
        private const val K_PROGRESS = "vod_progress"
        const val MAX_PROGRESS = 300
        const val MAX_CONTINUE = 20
    }
}
