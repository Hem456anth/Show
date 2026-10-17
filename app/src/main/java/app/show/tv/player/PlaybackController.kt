package app.show.tv.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.show.tv.data.Channel
import app.show.tv.data.StreamProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns one ExoPlayer and the recovery state machine (VLC's input thread does the equivalent
 * with its access/demux restart logic):
 *
 *  1. BEHIND_LIVE_WINDOW  -> jump to live edge and re-prepare, no counter.
 *  2. Format sniff failure on an extension-less URL -> retry once forcing HLS
 *     (most IPTV "progressive" URLs are HLS behind a redirect or a PHP endpoint).
 *  3. Retryable network/server errors -> exponential backoff (1s, 2s, 4s), and an
 *     immediate retry when connectivity returns.
 *  4. Stall watchdog: buffering > [stallTimeoutMs] counts as a timeout.
 *  5. Dead link with backup streams ([Channel.alternates]) -> switch to the next backup.
 *  6. Non-retryable errors surface a [PlaybackIssue] with viewer-friendly copy and are
 *     reported through [onChannelFailed] so browsing can flag dead links.
 *  7. Next/previous zapping skips channels that failed recently ([isUnavailable]).
 *
 * Also hosts the sleep timer and the video quality override.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    private val engine: StreamEngine,
    val player: ExoPlayer,
    queue: List<Channel>,
    startIndex: Int,
    private val onChannelStarted: (Channel) -> Unit = {},
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val retry: RetryPolicy = RetryPolicy(),
    private val stallTimeoutMs: Long = 20_000,
    private val onChannelPlaying: (Channel) -> Unit = {},
    private val onChannelFailed: (Channel, PlaybackIssue) -> Unit = { _, _ -> },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val isUnavailable: (Channel) -> Boolean = { false },
) {
    data class UiState(
        val channel: Channel,
        val index: Int,
        val queueSize: Int,
        val buffering: Boolean = true,
        val issue: PlaybackIssue? = null,
        val retrying: Boolean = false,
        val attempt: Int = 0,
        /** elapsedRealtime when the sleep timer pauses playback, or null. */
        val sleepAt: Long? = null,
        val sleptByTimer: Boolean = false,
        /** Video heights offered by the stream (empty for audio-only / single rendition). */
        val qualities: List<Int> = emptyList(),
        /** Chosen max height, or null for automatic. */
        val quality: Int? = null,
        /** 0 = the channel's main stream, n = its n-th backup stream. */
        val backup: Int = 0,
    )

    private var queue: List<Channel> = queue.ifEmpty { error("queue must not be empty") }
    private var index = startIndex.coerceIn(0, this.queue.lastIndex)
    private var forcedHls = false
    private var released = false
    private var linkIndex = 0
    private var lastIndex = -1

    private val _state = MutableStateFlow(UiState(this.queue[index], index, this.queue.size))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val retryRunnable = Runnable { reload(keepCounter = true) }
    private val stallRunnable = Runnable {
        if (player.playbackState == Player.STATE_BUFFERING) {
            handleIssue(PlaybackIssue(PlaybackIssue.Kind.TIMEOUT, "The stream stalled", "Reconnecting…", true))
        }
    }
    private val sleepRunnable = Runnable {
        player.pause()
        _state.value = _state.value.copy(sleepAt = null, sleptByTimer = true)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            handler.removeCallbacks(stallRunnable)
            when (state) {
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(buffering = true)
                    handler.postDelayed(stallRunnable, stallTimeoutMs)
                }
                Player.STATE_READY -> {
                    retry.reset()
                    _state.value = _state.value.copy(buffering = false, issue = null, retrying = false, attempt = 0)
                    onChannelPlaying(current)
                }
                else -> _state.value = _state.value.copy(buffering = false)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(qualities = videoHeights(tracks))
        }

        override fun onPlayerError(error: PlaybackException) = onError(error)
    }

    init {
        player.addListener(listener)
        player.playWhenReady = true
    }

    val current: Channel get() = queue[index]

    /** What is actually being played: [current] on its main stream or the backup in use. */
    private val active: Channel get() = current.links.getOrNull(linkIndex)?.let(current::withLink) ?: current

    fun start() = load(index)

    fun next() = load(step(+1))
    fun previous() = load(step(-1))

    /** Next index in [direction], skipping recently dead channels unless every one is dead. */
    private fun step(direction: Int): Int {
        for (n in 1 until queue.size) {
            val i = Math.floorMod(index + direction * n, queue.size)
            if (!isUnavailable(queue[i])) return i
        }
        return Math.floorMod(index + direction, queue.size)
    }
    fun hasQueue() = queue.size > 1

    /** TV-remote "last channel": jump back to the channel watched before this one. */
    fun recall() { if (lastIndex in queue.indices && lastIndex != index) load(lastIndex) }

    /** A new channel was picked while the player was already open (singleTop / PiP). */
    fun replaceQueue(newQueue: List<Channel>, startIndex: Int) {
        if (newQueue.isEmpty()) return
        queue = newQueue
        lastIndex = -1
        load(startIndex.coerceIn(0, newQueue.lastIndex))
    }

    /** User tapped "Try again". */
    fun retryNow() { retry.reset(); reload(keepCounter = false) }

    /** Connectivity came back: retry immediately if we were failing for network reasons. */
    fun onNetworkAvailable() {
        val kind = _state.value.issue?.kind ?: return
        if (kind == PlaybackIssue.Kind.OFFLINE || kind == PlaybackIssue.Kind.TIMEOUT || kind == PlaybackIssue.Kind.SERVER) {
            handler.removeCallbacks(retryRunnable)
            retry.reset()
            reload(keepCounter = false)
        }
    }

    /** Pause after [minutes]; null or 0 cancels. */
    fun setSleepTimer(minutes: Int?) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes == null || minutes <= 0) {
            _state.value = _state.value.copy(sleepAt = null)
            return
        }
        val delay = minutes * 60_000L
        handler.postDelayed(sleepRunnable, delay)
        _state.value = _state.value.copy(sleepAt = clock() + delay, sleptByTimer = false)
    }

    fun resumeAfterSleep() {
        _state.value = _state.value.copy(sleptByTimer = false)
        // Live: jump back to the live edge rather than resuming minutes behind.
        if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
        player.play()
    }

    /** Cap video at [maxHeight] (one of [UiState.qualities]); null restores adaptive. */
    fun setQuality(maxHeight: Int?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (maxHeight == null) {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        } else {
            val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            val trackIndex = group?.let { g -> (0 until g.length).firstOrNull { g.getTrackFormat(it).height == maxHeight } }
            if (group != null && trackIndex != null) builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            else builder.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        }
        player.trackSelectionParameters = builder.build()
        _state.value = _state.value.copy(quality = maxHeight)
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(sleepRunnable)
        player.removeListener(listener)
        player.release()
    }

    private fun load(i: Int) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(stallRunnable)
        if (i != index) lastIndex = index
        index = i
        linkIndex = 0
        forcedHls = false
        retry.reset()
        val ch = queue[index]
        val keep = _state.value
        _state.value = UiState(ch, index, queue.size, sleepAt = keep.sleepAt)
        // Each channel starts adaptive; a quality cap from the previous channel rarely fits.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
        if (!ch.protocol.playableInApp) {
            player.stop()
            player.clearMediaItems()
            _state.value = _state.value.copy(buffering = false, issue = PlaybackIssue.unsupportedProtocol(ch.protocol.label))
            return
        }
        onChannelStarted(ch)
        prepare(ch, null)
    }

    private fun prepare(ch: Channel, mime: String?) {
        player.setMediaSource(engine.mediaSource(ch, mime))
        player.prepare()
        player.play()
    }

    private fun reload(keepCounter: Boolean) {
        val ch = active
        _state.value = _state.value.copy(issue = null, buffering = true, retrying = keepCounter, attempt = if (keepCounter) retry.attempts else 0)
        prepare(ch, if (forcedHls) MimeTypes.APPLICATION_M3U8 else null)
    }

    internal fun onError(error: PlaybackException) {
        handler.removeCallbacks(stallRunnable)
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val isSniffFailure = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (isSniffFailure && !forcedHls && active.protocol == StreamProtocol.PROGRESSIVE) {
            forcedHls = true
            prepare(active, MimeTypes.APPLICATION_M3U8)
            return
        }
        handleIssue(PlaybackIssue.from(error))
    }

    private fun handleIssue(issue: PlaybackIssue) {
        val delay = if (issue.autoRetry) retry.nextDelayMs() else null
        if (delay == null && linkIndex < current.links.lastIndex) {
            // This address is dead or exhausted its retries: move on to the next backup stream.
            linkIndex++
            forcedHls = false
            retry.reset()
            _state.value = _state.value.copy(issue = null, retrying = false, buffering = true, attempt = 0, backup = linkIndex)
            if (active.protocol.playableInApp) prepare(active, null) else handleIssue(PlaybackIssue.unsupportedProtocol(active.protocol.label))
            return
        }
        if (delay != null) {
            _state.value = _state.value.copy(issue = issue, retrying = true, attempt = retry.attempts, buffering = false)
            handler.postDelayed(retryRunnable, delay)
        } else {
            player.stop()
            _state.value = _state.value.copy(issue = issue, retrying = false, buffering = false)
            if (issue.kind in DEAD_LINK_KINDS) onChannelFailed(current, issue)
        }
    }

    companion object {
        /** Failures that say the link itself is bad, as opposed to the viewer's network. */
        val DEAD_LINK_KINDS = setOf(
            PlaybackIssue.Kind.NOT_FOUND, PlaybackIssue.Kind.FORBIDDEN, PlaybackIssue.Kind.FORMAT, PlaybackIssue.Kind.SERVER,
        )

        fun videoHeights(tracks: Tracks): List<Int> = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).asSequence().filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
            .filter { it > 0 }.distinct().sortedDescending().toList()
    }
}
