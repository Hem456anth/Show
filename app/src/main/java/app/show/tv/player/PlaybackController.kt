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
    private val onAudioLanguage: (String?) -> Unit = {},
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
        /** Audio renditions the stream offers (languages, commentary, surround…), in stream order. */
        val audioTracks: List<AudioTrack> = emptyList(),
        /** Set when the player lowered the quality cap itself after repeated stalls. */
        val autoQuality: Int? = null,
    )

    /** One selectable audio track: [group]/[track] index into [Tracks.groups]. */
    data class AudioTrack(val key: String, val label: String, val language: String?, val selected: Boolean, val group: Int, val track: Int)

    private var queue: List<Channel> = queue.ifEmpty { error("queue must not be empty") }
    private var index = startIndex.coerceIn(0, this.queue.lastIndex)
    private var forcedHls = false
    private var released = false
    /** False while the player screen is stopped (not visible, not in PiP): nothing may load or retry. */
    private var foreground = true
    private var linkIndex = 0
    private var lastIndex = -1
    private var hasPlayed = false
    private val rebuffers = ArrayDeque<Long>()

    private val _state = MutableStateFlow(UiState(this.queue[index], index, this.queue.size))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val retryRunnable = Runnable { if (foreground) reload(keepCounter = true) }
    private val stallRunnable = Runnable {
        // Only a stall the viewer is waiting through counts: not one while paused or in the background.
        if (foreground && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            handleIssue(PlaybackIssue(PlaybackIssue.Kind.TIMEOUT, "The stream stalled", "Reconnecting…", true))
        }
    }
    private val sleepRunnable = Runnable {
        // stop(), not just pause(): a paused live player keeps polling the playlist all night.
        player.pause()
        player.stop()
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(stallRunnable)
        _state.value = _state.value.copy(sleepAt = null, sleptByTimer = true)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            handler.removeCallbacks(stallRunnable)
            when (state) {
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(buffering = true)
                    handler.postDelayed(stallRunnable, stallTimeoutMs)
                    if (hasPlayed) onRebuffer()
                }
                Player.STATE_READY -> {
                    hasPlayed = true
                    retry.reset()
                    _state.value = _state.value.copy(buffering = false, issue = null, retrying = false, attempt = 0)
                    onChannelPlaying(current)
                }
                else -> _state.value = _state.value.copy(buffering = false)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(qualities = videoHeights(tracks), audioTracks = audioTracks(tracks))
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
        if (!foreground || released) return
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
        // The timer stopped the player; reload at the live edge.
        reload(keepCounter = false)
    }

    /**
     * The player screen left the foreground (Home, another app, screen off) without PiP. Stops loading
     * entirely and cancels retries, so a stalled or failing stream can't reconnect and start playing
     * audio in the background (1.5.2 and earlier did: a retry or backup failover called play()).
     */
    fun onBackground() {
        if (released || !foreground) return
        foreground = false
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(stallRunnable)
        player.pause()
        player.stop()
    }

    /** Back on screen: reconnect at the live edge unless a final error or the sleep timer is showing. */
    fun onForeground() {
        if (released || foreground) return
        foreground = true
        val s = _state.value
        if (s.sleptByTimer) return
        if (s.issue != null && !s.retrying) return
        if (!active.protocol.playableInApp) return
        reload(keepCounter = false)
    }

    internal val isForeground: Boolean get() = foreground

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

    /** Plays [track]; its language becomes the preference for the next channels too. */
    fun selectAudio(track: AudioTrack) {
        val group = player.currentTracks.groups.getOrNull(track.group)?.takeIf { it.type == C.TRACK_TYPE_AUDIO } ?: return
        if (track.track !in 0 until group.length) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.track))
            .setPreferredAudioLanguage(track.language)
            .build()
        onAudioLanguage(track.language)
    }

    /**
     * Adaptive streams that keep stalling (two rebuffers within a minute while on Auto) are capped
     * one rendition lower, like YouTube's "playback is choppy" fallback. Manual picks are respected.
     */
    private fun onRebuffer() {
        val now = clock()
        rebuffers.addLast(now)
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > REBUFFER_WINDOW_MS) rebuffers.removeFirst()
        val s = _state.value
        if (rebuffers.size < 2 || s.quality != null) return
        val playing = player.videoFormat?.height?.takeIf { it > 0 } ?: return
        val lower = stepDown(s.qualities, playing) ?: return
        rebuffers.clear()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, lower).build()
        _state.value = s.copy(autoQuality = lower)
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
        hasPlayed = false
        rebuffers.clear()
        // Each channel starts adaptive; a quality cap or audio pick from the previous channel rarely fits
        // (the preferred audio language is kept, so a Telugu pick prefers Telugu on the next channel).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).build()
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
        if (released) return
        player.setMediaSource(engine.mediaSource(ch, mime))
        if (!foreground) return // loaded on return to the foreground
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
        if (!foreground || released) return
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

        const val REBUFFER_WINDOW_MS = 60_000L

        /** The next rendition below [playing], or null at the bottom. */
        fun stepDown(qualities: List<Int>, playing: Int): Int? = qualities.filter { it < playing }.maxOrNull()

        /**
         * Lists every supported audio track. HLS often repeats one language at several bitrates
         * (Zee Tamil HD has three "und" AAC renditions); those get a bitrate suffix so each row is distinct.
         */
        fun audioTracks(tracks: Tracks): List<AudioTrack> {
            val raw = ArrayList<AudioTrack>()
            tracks.groups.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti, /* allowExceedsCapabilities = */ true)) continue
                    val f = g.getTrackFormat(ti)
                    raw += AudioTrack("$gi:$ti", AudioLabels.label(f), AudioLabels.language(f.language), g.isTrackSelected(ti), gi, ti)
                }
            }
            val counts = raw.groupingBy { it.label }.eachCount()
            return raw.mapIndexed { i, t ->
                if ((counts[t.label] ?: 0) < 2) t else {
                    val f = tracks.groups[t.group].getTrackFormat(t.track)
                    val br = f.bitrate.takeIf { it > 0 } ?: f.averageBitrate.takeIf { it > 0 } ?: f.peakBitrate.takeIf { it > 0 }
                    t.copy(label = t.label + if (br != null) " · ${br / 1000} kbps" else " · ${i + 1}")
                }
            }
        }

        fun videoHeights(tracks: Tracks): List<Int> = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).asSequence().filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
            .filter { it > 0 }.distinct().sortedDescending().toList()
    }
}
