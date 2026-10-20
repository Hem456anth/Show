package app.show.tv.vod

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * 1.9.0 playback hardening for Movies & Series. Clean-room ideas from the 1.9 research (docs/RESEARCH_1.9.md);
 * no third-party code was copied.
 *
 *  - [BufferPreset]: user-chosen buffer depth, with a back buffer so a short seek back plays from memory.
 *  - [VodRetry]: a network blip retries the same rendition with backoff before falling to the next one.
 *  - [RenditionHealth]: renditions that keep failing are remembered for 14 days and tried last.
 *  - [VodErrors]: plain-language errors that name the HTTP status when there is one.
 */
enum class BufferPreset(val label: String, val detail: String, val minMs: Int, val maxMs: Int, val backMs: Int) {
    DATA_SAVER("Data saver", "Buffers 30 s ahead. Least data on mobile.", 10_000, 30_000, 0),
    BALANCED("Balanced", "Buffers up to 1 min ahead. The default.", 15_000, 60_000, 15_000),
    SMOOTH("Smooth", "Buffers up to 2 min ahead. Best on slow or patchy Wi-Fi.", 30_000, 120_000, 30_000);

    /** Media3 asserts playback-start <= min <= max, so the start thresholds are clamped, never trusted. */
    @OptIn(UnstableApi::class)
    fun loadControl(): LoadControl {
        val min = minMs.coerceIn(1, maxMs)
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, maxMs, START_MS.coerceAtMost(min), REBUFFER_MS.coerceAtMost(min))
            .apply { if (backMs > 0) setBackBuffer(backMs, true) }
            .build()
    }

    companion object {
        const val START_MS = 2_500
        const val REBUFFER_MS = 5_000
        fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: BALANCED
    }
}

/** Which failures are worth retrying on the same rendition, and how long to wait. */
object VodRetry {
    const val MAX_ATTEMPTS = 3

    fun isTransient(e: PlaybackException): Boolean = isTransient(e.errorCode, httpStatus(e))

    /** Connection drops, timeouts and 5xx/429 can clear; 4xx, codec and container errors will not. */
    fun isTransient(code: Int, http: Int?): Boolean = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> true
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> http == null || http == 429 || http in 500..599
        else -> false
    }

    /** 1 s, 2 s, 4 s, then null (give up on this rendition). */
    fun delayMs(attempt: Int): Long? = if (attempt in 0 until MAX_ATTEMPTS) 1_000L shl attempt else null

    fun httpStatus(e: PlaybackException): Int? {
        var c: Throwable? = e.cause
        while (c != null) { if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode; c = c.cause }
        return null
    }
}

/**
 * Per-rendition failure memory (app-private prefs). A URL that failed twice in [WINDOW_MS] is "suspect" and ordered
 * after healthy ones; one success clears it. Keys are hashes, so no viewing history is readable from the store.
 */
class RenditionHealth(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    private fun key(url: String) = "h_" + Integer.toHexString(url.hashCode())

    fun markFailed(url: String) {
        val now = clock()
        val (n, at) = read(url)
        val count = if (now - at > WINDOW_MS) 1 else n + 1
        prefs.edit().putString(key(url), "$count:$now").apply()
        prune()
    }

    fun markOk(url: String) { if (prefs.contains(key(url))) prefs.edit().remove(key(url)).apply() }

    fun isSuspect(url: String): Boolean { val (n, at) = read(url); return n >= SUSPECT_AFTER && clock() - at <= WINDOW_MS }

    /** The first index to try: [preferred] unless it is suspect, then the first healthy one, else [preferred]. */
    fun firstHealthy(urls: List<String>, preferred: Int): Int {
        if (urls.isEmpty()) return 0
        val p = preferred.coerceIn(0, urls.lastIndex)
        if (!isSuspect(urls[p])) return p
        return (urls.indices.drop(p + 1) + urls.indices.take(p)).firstOrNull { !isSuspect(urls[it]) } ?: p
    }

    private fun read(url: String): Pair<Int, Long> {
        val v = prefs.getString(key(url), null) ?: return 0 to 0L
        val parts = v.split(':')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun prune() {
        val all = prefs.all.filterKeys { it.startsWith("h_") }
        if (all.size <= MAX_ENTRIES) return
        val now = clock()
        val e = prefs.edit()
        all.forEach { (k, v) -> val at = (v as? String)?.substringAfter(':')?.toLongOrNull() ?: 0L; if (now - at > WINDOW_MS) e.remove(k) }
        e.apply()
    }

    companion object {
        const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        const val SUSPECT_AFTER = 2
        const val MAX_ENTRIES = 300
    }
}

object VodErrors {
    fun message(e: PlaybackException): String = message(e.errorCode, VodRetry.httpStatus(e))

    fun message(code: Int, http: Int?): String = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> "Connection lost. Check your network and try again."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (http) {
            401, 403 -> "The server refused this video (HTTP $http). Sign in again or check the item's access."
            404, 410 -> "This video file is gone from the server (HTTP $http)."
            null -> "The server refused this video."
            else -> if (http >= 500) "The video server is having trouble (HTTP $http). Try again in a minute." else "The server refused this video (HTTP $http)."
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This video file is gone from the server."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This device can't play this video's format."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This device's video decoder couldn't start. Try a lower quality."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This video file is damaged."
        else -> "This video couldn't play."
    }
}
