package app.show.tv.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Viewer preferences. Everything here is optional and safe to lose. */
data class Settings(
    val playlistUrl: String = PlaylistRepository.DEFAULT_PLAYLIST_URL,
    val hideGeoBlocked: Boolean = false,
    val hideUnavailable: Boolean = false,
    /** Audio language the viewer last picked in the player (BCP-47), preferred on every channel. */
    val audioLanguage: String? = null,
    /** Merge independently verified Free-TV links into the catalog (new channels + backup links). */
    val extraSources: Boolean = true,
    /** Include verified YouTube / Dailymotion live channels (official embedded players, 1.5.4). */
    val embeds: Boolean = true,
) {
    /** The iptv-org index in use, or null for the viewer's own playlist. */
    val source: PlaylistSource? get() = PlaylistSource.byUrl(playlistUrl)
    val isCustomPlaylist: Boolean get() = source == null
    val grouping: PlaylistSource.Grouping get() = source?.grouping ?: PlaylistSource.Grouping.CATEGORY
}

class SettingsStore(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val playlistUrl: String get() = _settings.value.playlistUrl

    sealed interface UrlResult {
        data object Ok : UrlResult
        data class Invalid(val reason: String) : UrlResult
    }

    /** Accepts only well-formed https URLs; blank resets to the iptv-org index. */
    fun setPlaylistUrl(raw: String): UrlResult {
        val trimmed = raw.trim()
        val url = if (trimmed.isEmpty()) PlaylistRepository.DEFAULT_PLAYLIST_URL else trimmed
        validate(url)?.let { return UrlResult.Invalid(it) }
        update(_settings.value.copy(playlistUrl = url))
        return UrlResult.Ok
    }

    fun setSource(source: PlaylistSource) = update(_settings.value.copy(playlistUrl = source.url))

    fun setHideGeoBlocked(v: Boolean) = update(_settings.value.copy(hideGeoBlocked = v))
    fun setHideUnavailable(v: Boolean) = update(_settings.value.copy(hideUnavailable = v))
    fun setAudioLanguage(tag: String?) {
        val clean = tag?.trim()?.takeIf { it.length in 2..35 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        if (clean != _settings.value.audioLanguage) update(_settings.value.copy(audioLanguage = clean))
    }
    fun setExtraSources(v: Boolean) = update(_settings.value.copy(extraSources = v))
    fun setEmbeds(v: Boolean) = update(_settings.value.copy(embeds = v))

    private fun update(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString(KEY_URL, s.playlistUrl)
            .putBoolean(KEY_GEO, s.hideGeoBlocked)
            .putBoolean(KEY_DEAD, s.hideUnavailable)
            .putString(KEY_AUDIO, s.audioLanguage)
            .putBoolean(KEY_EXTRA, s.extraSources)
            .putBoolean(KEY_EMBEDS, s.embeds)
            .apply()
    }

    private fun read(): Settings {
        val url = prefs.getString(KEY_URL, null)?.takeIf { validate(it) == null } ?: PlaylistRepository.DEFAULT_PLAYLIST_URL
        return Settings(
            url, prefs.getBoolean(KEY_GEO, false), prefs.getBoolean(KEY_DEAD, false),
            audioLanguage = prefs.getString(KEY_AUDIO, null), extraSources = prefs.getBoolean(KEY_EXTRA, true),
            embeds = prefs.getBoolean(KEY_EMBEDS, true),
        )
    }

    companion object {
        private const val KEY_URL = "playlist_url"
        private const val KEY_GEO = "hide_geo"
        private const val KEY_DEAD = "hide_dead"
        private const val KEY_AUDIO = "audio_language"
        private const val KEY_EXTRA = "extra_sources"
        private const val KEY_EMBEDS = "embeds"

        /** Returns a reason the URL is unusable, or null when it's fine. */
        fun validate(url: String): String? {
            if (url.length > 2048) return "That address is too long."
            val parsed = url.toHttpUrlOrNull() ?: return "Enter a full address starting with https://"
            if (!parsed.isHttps) return "For your security, playlists must use https://"
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "Addresses with embedded passwords aren't supported."
            return null
        }
    }
}
