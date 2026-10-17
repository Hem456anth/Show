package app.show.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.show.tv.BuildConfig
import app.show.tv.data.Channel
import app.show.tv.data.StreamProtocol
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.math.min

/**
 * Builds the playback pipeline. The design maps VLC's layered architecture onto Media3:
 *
 *   VLC access module (http/rtsp/rtmp)  -> DataSource (OkHttp, RTMP, RTSP)
 *   VLC demux (adaptive/ts/mp4)          -> MediaSource (HLS/DASH/SS/Progressive)
 *   VLC "network-caching" + adaptive
 *     BufferingLogic (6s min / 15s live) -> DefaultLoadControl buffer targets
 *   :http-user-agent / :http-referrer    -> per-channel default request properties
 *   VLC decoders (MediaCodec/avcodec)    -> DefaultRenderersFactory (MediaCodec + fallback)
 */
@OptIn(UnstableApi::class)
class StreamEngine(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val defaultUserAgent: String = DEFAULT_USER_AGENT,
    segmentRetries: Int = 5,
    retryBaseDelayMs: Long = 500,
) {
    private val loadErrorPolicy = loadErrorPolicy(segmentRetries, retryBaseDelayMs)


    fun buildPlayer(preferredAudioLanguage: String? = null): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ LIVE_MIN_BUFFER_MS,
                /* maxBufferMs = */ MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ START_BUFFER_MS,
                /* bufferForPlaybackAfterRebufferMs = */ REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .build()

        val trackSelector = DefaultTrackSelector(context, adaptiveFactory()).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(preferredAudioLanguage)
                    .setPreferredTextLanguage(null)
                    .setSelectUndeterminedTextLanguage(false)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setTunnelingEnabled(false),
            )
        }
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /** Data source honouring the channel's VLC-style UA / referrer options. */
    fun dataSourceFactory(channel: Channel): DataSource.Factory {
        val headers = requestHeaders(channel)
        val http = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(channel.userAgent?.let(::sanitizeHeader)?.takeIf { it.isNotBlank() } ?: defaultUserAgent)
            .setDefaultRequestProperties(headers)
        // DefaultDataSource adds file/asset/content plus RTMP (media3-datasource-rtmp) by scheme.
        return DefaultDataSource.Factory(context, http)
    }

    fun mediaSource(channel: Channel, forceMimeType: String? = null): MediaSource =
        DefaultMediaSourceFactory(dataSourceFactory(channel))
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(mediaItem(channel, forceMimeType))

    companion object {
        /*
         * Buffer targets (see docs/STREAMING_1.5.md). Live 1080p IPTV is often one 5+ Mbps rendition
         * from an origin that serves it at only ~1.3x real time, with a 4-segment (~12 s) window. With
         * 1.4's 3 s after-rebuffer threshold the player resumed on one segment and stalled again
         * ("stutter loop"); 5 s rides out a slow segment. Zapping still starts after 1.5 s.
         */
        const val LIVE_MIN_BUFFER_MS = 20_000
        const val MAX_BUFFER_MS = 50_000
        const val START_BUFFER_MS = 1_500
        const val REBUFFER_MS = 5_000
        const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
        const val MAX_LIVE_SPEED = 1.0f
        const val MIN_LIVE_SPEED = 0.97f
        /**
         * Adaptive switching tuned for live TV: step up only after 10 s of headroom, drop quickly,
         * and budget 65% of measured bandwidth (Media3 default 70%) because IPTV origins are bursty.
         */
        fun adaptiveFactory() = androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 10_000,
            /* maxDurationForQualityDecreaseMs = */ 15_000,
            /* minDurationToRetainAfterDiscardMs = */ 15_000,
            /* bandwidthFraction = */ 0.65f,
        )

        val DEFAULT_USER_AGENT = "Show/${BuildConfig.VERSION_NAME} (Linux; Android) Media3"

        /**
         * Only `Referer`, exactly like VLC's `:http-referrer`. (1.0 also sent `Origin`, which some
         * CDNs treat as a cross-origin request and answer with 403.) Header values are stripped of
         * CR/LF so a hostile playlist can't inject extra headers.
         */
        fun requestHeaders(channel: Channel): Map<String, String> = buildMap {
            channel.referrer?.let(::sanitizeHeader)?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }

        fun sanitizeHeader(value: String): String = value.filter { it != '\r' && it != '\n' && it != '\u0000' }.trim().take(1024)

        fun mimeTypeFor(protocol: StreamProtocol): String? = when (protocol) {
            StreamProtocol.HLS -> MimeTypes.APPLICATION_M3U8
            StreamProtocol.DASH -> MimeTypes.APPLICATION_MPD
            StreamProtocol.SMOOTH -> MimeTypes.APPLICATION_SS
            StreamProtocol.RTSP -> MimeTypes.APPLICATION_RTSP
            else -> null
        }

        fun mediaItem(channel: Channel, forceMimeType: String? = null): MediaItem =
            MediaItem.Builder()
                .setMediaId(channel.id)
                .setUri(channel.streamUrl)
                .setMimeType(forceMimeType ?: mimeTypeFor(channel.protocol))
                .setLiveConfiguration(
                    // Never speed up to chase the live edge: doing so drains the buffer faster than a
                    // slow origin refills it (the main cause of 1080p rebuffering in 1.4). Slowing
                    // down slightly is allowed, which rebuilds the buffer when it runs low.
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(MAX_LIVE_SPEED)
                        .setMinPlaybackSpeed(MIN_LIVE_SPEED)
                        .build(),
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(channel.name)
                        .setGenre(channel.primaryCategory)
                        .setArtworkUri(channel.logoUrl?.let(android.net.Uri::parse))
                        .build(),
                )
                .build()

        /**
         * Segment-level retries (VLC's http access retries + adaptive's segment refetch):
         * up to [retries] attempts with capped exponential backoff, no retry on 4xx client errors.
         */
        fun loadErrorPolicy(retries: Int, baseDelayMs: Long): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(retries) {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val e: IOException = info.exception
                if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                    e.responseCode in 400..499 && e.responseCode != 408 && e.responseCode != 429
                ) return C.TIME_UNSET
                return min(baseDelayMs shl (info.errorCount - 1).coerceIn(0, 4), 5_000L)
            }
        }
    }
}
