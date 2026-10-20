package app.show.tv.vod

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VodConfig(
    val tmdbKey: String? = null,
    val jellyfin: JellyfinAccount? = null,
    val archive: Boolean = true,
    /** Preferred rendition height for files that offer several; null = best available. */
    val preferredHeight: Int? = null,
)

/**
 * Player preferences (1.8.0). Kept apart from [VodConfig] on purpose: changing a subtitle language must not
 * rebuild the providers or reload the catalog the way a new key or server does.
 */
data class VodPlayerPrefs(
    /** Remembered audio language (ISO 639-1), null = the stream's default. */
    val audioLang: String? = null,
    /** Remembered subtitle language, [VodTracks.OFF] = off, null = the stream's default. */
    val subtitleLang: String? = null,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    /** Buffer depth (1.9.0). */
    val buffer: BufferPreset = BufferPreset.BALANCED,
)

/** Trakt app credentials: build-time values, or the viewer's own from Movies settings (1.8.0). */
data class TraktApp(val clientId: String, val clientSecret: String)

/** Movies & Series settings: credentials stay in app-private storage and are never logged. */
class VodSettings(
    private val prefs: SharedPreferences,
    private val builtInTmdbKey: String? = null,
    private val builtInTrakt: TraktApp? = null,
    /** Where session tokens go (Keystore-encrypted in the app). Null keeps the 1.6 plain-prefs behaviour (tests). */
    private val secrets: SecretStore? = null,
) {
    init {
        // 1.8.0 audit: move a 1.6-era plain Jellyfin token into the encrypted store.
        if (secrets != null) prefs.getString(K_JF_TOKEN, null)?.let { secrets.put(K_JF_TOKEN, it); prefs.edit().remove(K_JF_TOKEN).apply() }
    }

    private val _config = MutableStateFlow(read())
    val config: StateFlow<VodConfig> = _config.asStateFlow()
    private val _player = MutableStateFlow(readPlayer())
    val player: StateFlow<VodPlayerPrefs> = _player.asStateFlow()
    private val _trakt = MutableStateFlow(readTrakt())
    /** Null when no client id is configured: Trakt is then hidden. */
    val traktApp: StateFlow<TraktApp?> = _trakt.asStateFlow()

    val deviceId: String get() = prefs.getString(K_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(K_DEVICE, it).apply() }

    /** Returns null when accepted, else the reason. Blank clears the viewer's key (the built-in one, if any, applies). */
    fun setTmdbKey(raw: String): String? {
        val k = raw.trim()
        if (k.isNotEmpty() && !(k.matches(Regex("[0-9a-f]{32}")) || k.matches(Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")))) {
            return "That doesn't look like a TMDB API key (32 hex characters) or read access token."
        }
        prefs.edit().putString(K_TMDB, k.ifEmpty { null }).apply()
        _config.value = read(); return null
    }

    fun setJellyfin(a: JellyfinAccount?) {
        prefs.edit().apply {
            if (a == null) listOf(K_JF_SERVER, K_JF_USER, K_JF_TOKEN, K_JF_NAME).forEach(::remove)
            else {
                putString(K_JF_SERVER, a.server).putString(K_JF_USER, a.userId).putString(K_JF_NAME, a.userName)
                if (secrets == null) putString(K_JF_TOKEN, a.token)
            }
        }.apply()
        secrets?.put(K_JF_TOKEN, a?.token)
        _config.value = read()
    }

    fun setArchive(on: Boolean) { prefs.edit().putBoolean(K_ARCHIVE, on).apply(); _config.value = read() }
    fun setPreferredHeight(h: Int?) { prefs.edit().putInt(K_HEIGHT, h ?: 0).apply(); _config.value = read() }

    fun setAudioLang(lang: String?) { prefs.edit().putString(K_AUDIO, lang).apply(); _player.value = readPlayer() }
    fun setSubtitleLang(lang: String?) { prefs.edit().putString(K_SUBS, lang).apply(); _player.value = readPlayer() }
    fun setBufferPreset(b: BufferPreset) { prefs.edit().putString(K_BUFFER, b.name).apply(); _player.value = readPlayer() }
    fun setResizeMode(m: ResizeMode) { prefs.edit().putString(K_RESIZE, m.name).apply(); _player.value = readPlayer() }

    /** The viewer's own Trakt app (from trakt.tv/oauth/applications). Blank id clears it (the built-in one, if any, applies). */
    fun setTraktApp(clientId: String, clientSecret: String): String? {
        val id = clientId.trim(); val secret = clientSecret.trim()
        if (id.isNotEmpty() && (!id.matches(HEX64) || !secret.matches(HEX64))) return "Trakt client id and secret are 64 hex characters each."
        prefs.edit().putString(K_TRAKT_ID, id.ifEmpty { null }).putString(K_TRAKT_SECRET, secret.ifEmpty { null }.takeIf { id.isNotEmpty() }).apply()
        _trakt.value = readTrakt(); return null
    }
    val hasOwnTraktApp: Boolean get() = prefs.getString(K_TRAKT_ID, null) != null

    private fun readPlayer() = VodPlayerPrefs(prefs.getString(K_AUDIO, null), prefs.getString(K_SUBS, null), ResizeMode.parse(prefs.getString(K_RESIZE, null)),
        BufferPreset.parse(prefs.getString(K_BUFFER, null)))

    private fun readTrakt(): TraktApp? {
        val id = prefs.getString(K_TRAKT_ID, null); val secret = prefs.getString(K_TRAKT_SECRET, null)
        if (id != null && secret != null) return TraktApp(id, secret)
        return builtInTrakt?.takeIf { it.clientId.isNotBlank() && it.clientSecret.isNotBlank() }
    }

    private fun read(): VodConfig {
        val server = prefs.getString(K_JF_SERVER, null); val user = prefs.getString(K_JF_USER, null)
        val token = secrets?.get(K_JF_TOKEN) ?: prefs.getString(K_JF_TOKEN, null)
        return VodConfig(
            tmdbKey = prefs.getString(K_TMDB, null) ?: builtInTmdbKey?.takeIf { it.isNotBlank() },
            jellyfin = if (server != null && user != null && token != null) JellyfinAccount(server, user, token, prefs.getString(K_JF_NAME, "") ?: "", deviceId) else null,
            archive = prefs.getBoolean(K_ARCHIVE, true),
            preferredHeight = prefs.getInt(K_HEIGHT, 0).takeIf { it > 0 },
        )
    }

    val hasOwnTmdbKey: Boolean get() = prefs.getString(K_TMDB, null) != null

    companion object {
        private const val K_TMDB = "tmdb_key"; private const val K_JF_SERVER = "jf_server"; private const val K_JF_USER = "jf_user"
        private const val K_JF_TOKEN = "jf_token"; private const val K_JF_NAME = "jf_name"; private const val K_ARCHIVE = "archive"
        private const val K_HEIGHT = "pref_height"; private const val K_DEVICE = "device_id"
        private const val K_AUDIO = "pref_audio_lang"; private const val K_SUBS = "pref_sub_lang"; private const val K_RESIZE = "pref_resize"; private const val K_BUFFER = "pref_buffer"
        private const val K_TRAKT_ID = "trakt_client_id"; private const val K_TRAKT_SECRET = "trakt_client_secret"
        private val HEX64 = Regex("[0-9a-f]{64}")
    }
}
