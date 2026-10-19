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

/** Movies & Series settings: credentials stay in app-private storage and are never logged. */
class VodSettings(private val prefs: SharedPreferences, private val builtInTmdbKey: String? = null) {
    private val _config = MutableStateFlow(read())
    val config: StateFlow<VodConfig> = _config.asStateFlow()

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
            else putString(K_JF_SERVER, a.server).putString(K_JF_USER, a.userId).putString(K_JF_TOKEN, a.token).putString(K_JF_NAME, a.userName)
        }.apply()
        _config.value = read()
    }

    fun setArchive(on: Boolean) { prefs.edit().putBoolean(K_ARCHIVE, on).apply(); _config.value = read() }
    fun setPreferredHeight(h: Int?) { prefs.edit().putInt(K_HEIGHT, h ?: 0).apply(); _config.value = read() }

    private fun read(): VodConfig {
        val server = prefs.getString(K_JF_SERVER, null); val user = prefs.getString(K_JF_USER, null); val token = prefs.getString(K_JF_TOKEN, null)
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
    }
}
