package app.show.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** What went wrong, phrased for viewers, plus what the recovery loop should do about it. */
data class PlaybackIssue(
    val kind: Kind,
    val title: String,
    val detail: String,
    val autoRetry: Boolean,
) {
    enum class Kind { OFFLINE, TIMEOUT, BEHIND_LIVE_WINDOW, FORBIDDEN, NOT_FOUND, SERVER, FORMAT, DECODER, UNSUPPORTED_PROTOCOL, UNKNOWN }

    companion object {
        @OptIn(UnstableApi::class)
        fun from(error: PlaybackException): PlaybackIssue {
            val http = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            if (http != null) return fromHttpCode(http.responseCode)
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    PlaybackIssue(Kind.BEHIND_LIVE_WINDOW, "Catching up to live", "Jumping back to the live edge.", true)
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    PlaybackIssue(Kind.OFFLINE, "Connection lost", "Check your internet. We'll reconnect automatically.", true)
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
                    PlaybackIssue(Kind.TIMEOUT, "The stream is slow to respond", "Retrying with a fresh connection.", true)
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    PlaybackIssue(Kind.FORBIDDEN, "Insecure stream blocked", "This channel is not allowed over plain HTTP.", false)
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    PlaybackIssue(Kind.FORMAT, "Unsupported stream format", "The broadcaster sent data we can't read.", false)
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                    PlaybackIssue(Kind.DECODER, "Your device can't decode this channel", "Try another channel or a lower quality feed.", false)
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    PlaybackIssue(Kind.NOT_FOUND, "Channel is offline", "The stream address no longer exists.", false)
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                    PlaybackIssue(Kind.SERVER, "Stream interrupted", "Reconnecting…", true)
                else ->
                    PlaybackIssue(Kind.UNKNOWN, "Playback failed", error.errorCodeName, true)
            }
        }

        fun fromHttpCode(code: Int): PlaybackIssue = when (code) {
            401, 403, 451 -> PlaybackIssue(Kind.FORBIDDEN, "Not available in your region", "The broadcaster refused access (HTTP $code). It may be geo-blocked.", false)
            404, 410 -> PlaybackIssue(Kind.NOT_FOUND, "Channel is offline", "The stream address returned HTTP $code.", false)
            408, 429 -> PlaybackIssue(Kind.TIMEOUT, "The server is busy", "HTTP $code. Retrying shortly.", true)
            in 500..599 -> PlaybackIssue(Kind.SERVER, "Broadcaster server error", "HTTP $code. Retrying shortly.", true)
            else -> PlaybackIssue(Kind.UNKNOWN, "Stream unavailable", "HTTP $code.", false)
        }

        fun unsupportedProtocol(label: String) = PlaybackIssue(
            Kind.UNSUPPORTED_PROTOCOL,
            "$label streams need an external player",
            "Open it in an app that supports $label, such as VLC.",
            false,
        )
    }
}

/** Exponential backoff for whole-player recovery: 1s, 2s, 4s, then give up. */
class RetryPolicy(private val maxAttempts: Int = 3, private val baseDelayMs: Long = 1_000) {
    var attempts = 0
        private set

    fun nextDelayMs(): Long? = if (attempts >= maxAttempts) null else baseDelayMs shl attempts++

    fun reset() { attempts = 0 }
}
