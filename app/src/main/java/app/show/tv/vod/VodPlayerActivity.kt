package app.show.tv.vod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.show.tv.R
import app.show.tv.appGraph
import app.show.tv.ui.components.ShowPrimaryButton
import app.show.tv.ui.components.ShowSecondaryButton
import app.show.tv.ui.theme.ShowColors
import app.show.tv.ui.theme.ShowTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What to play. Kept in memory (episode lists can be long); the activity finishes if it was lost to process death. */
data class VodPlayRequest(
    val title: VodTitle,
    /** Movie renditions; ignored for series (each episode carries its own). */
    val videos: List<VodVideo> = emptyList(),
    val episodes: List<VodEpisode> = emptyList(),
    val episodeIndex: Int = 0,
    val startMs: Long = 0,
) {
    val isEpisodic: Boolean get() = episodes.isNotEmpty()
    val episode: VodEpisode? get() = episodes.getOrNull(episodeIndex)
    val currentVideos: List<VodVideo> get() = episode?.sources ?: videos
}

/**
 * On-demand player for Movies & Series, separate from the live [app.show.tv.player.PlayerActivity]:
 * VOD wants seeking, resume and a deep buffer, not live-edge tuning. Two layouts: a movie (title,
 * quality) and an episodic one (series, S·E code, previous/next, episode list, auto-advance).
 *
 * Progress is saved every 10 s, on pause and on exit ([VodLibrary]) and reported to Jellyfin for
 * Jellyfin titles. Off-screen the player is stopped (no buffering, network or wake lock in the
 * background) and resumes at the same position. The screen stays on only while video plays.
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var request: VodPlayRequest
    private var renditionIndex by mutableIntStateOf(0)
    private var episodeIndex by mutableIntStateOf(0)
    private var error by mutableStateOf<String?>(null)
    private var buffering by mutableStateOf(true)
    private var resumedAt by mutableStateOf<Long?>(null)
    private var tickJob: Job? = null
    private val failedRenditions = mutableSetOf<Int>()

    private val library get() = appGraph.vodLibrary
    private val jellyfin: JellyfinSource? get() = appGraph.vodRepository.source(Provider.JELLYFIN) as? JellyfinSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = pending
        if (req == null || req.currentVideos.isEmpty() && req.episodes.all { it.sources.isEmpty() }) { finish(); return }
        request = req
        episodeIndex = savedInstanceState?.getInt(KEY_EP, req.episodeIndex) ?: req.episodeIndex
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val p = buildPlayer()
        player = p
        renditionIndex = pickRendition(videos())
        val start = savedInstanceState?.getLong(KEY_POS) ?: req.startMs
        prepare(start, playWhenReady = true)
        if (start > 0) resumedAt = start
        setContent { ShowTheme { Screen(p) } }
    }

    private fun videos(): List<VodVideo> = request.episodes.getOrNull(episodeIndex)?.sources ?: request.videos

    /** The viewer's preferred height (Movies settings), else the best rendition. */
    private fun pickRendition(list: List<VodVideo>): Int {
        val pref = appGraph.vodSettings.config.value.preferredHeight ?: return 0
        val i = list.indexOfFirst { it.height != null && it.height <= pref }
        return if (i >= 0) i else 0
    }

    private fun buildPlayer(): ExoPlayer {
        val headers = videos().firstOrNull()?.headers ?: emptyMap()
        val ds = OkHttpDataSource.Factory(appGraph.mediaClient).setUserAgent("Show/${app.show.tv.BuildConfig.VERSION_NAME}").setDefaultRequestProperties(headers)
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_500, 5_000).build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(ds))
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000)
            .build().also { it.addListener(listener) }
    }

    private fun prepare(startMs: Long, playWhenReady: Boolean) {
        val p = player ?: return
        val v = videos().getOrNull(renditionIndex) ?: return
        error = null; buffering = true
        p.setMediaItem(MediaItem.Builder().setUri(v.url).apply {
            if (v.url.contains(".m3u8")) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }.build(), startMs)
        p.prepare()
        p.playWhenReady = playWhenReady
        jellyfinItem()?.let { id -> lifecycleScope.launch { jellyfin?.report(id, startMs, "start") } }
    }

    private fun jellyfinItem(): String? = if (request.title.provider != Provider.JELLYFIN) null
        else request.episodes.getOrNull(episodeIndex)?.id ?: request.title.providerId

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) onEnded()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keepAwake(isPlaying)
            if (isPlaying) startTicks() else { tickJob?.cancel(); save() }
        }
        override fun onPlayerError(e: PlaybackException) {
            // Fall back through the other renditions (Jellyfin: transcode -> original) before giving up.
            failedRenditions += renditionIndex
            val next = videos().indices.firstOrNull { it !in failedRenditions }
            val pos = player?.currentPosition ?: 0
            if (next != null) { renditionIndex = next; prepare(pos, true); return }
            keepAwake(false)
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connection lost. Check your network and try again."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this video."
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This device can't play this video's format."
                else -> "This video couldn't play."
            }
        }
    }

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                save()
                jellyfinItem()?.let { id -> jellyfin?.report(id, player?.currentPosition ?: 0, "progress") }
            }
        }
    }

    private fun save() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val ep = request.episodes.getOrNull(episodeIndex)
        library.record(request.title, p.currentPosition, dur, ep?.id, ep?.let { "${it.code} · ${it.title}" })
    }

    private fun onEnded() {
        save()
        if (request.isEpisodic && episodeIndex < request.episodes.lastIndex) playEpisode(episodeIndex + 1)
        else { keepAwake(false); finish() }
    }

    private fun playEpisode(i: Int) {
        if (i !in request.episodes.indices || request.episodes[i].sources.isEmpty()) return
        save()
        jellyfinItem()?.let { id -> val pos = player?.currentPosition ?: 0; lifecycleScope.launch { jellyfin?.report(id, pos, "stop") } }
        episodeIndex = i
        failedRenditions.clear()
        renditionIndex = pickRendition(videos())
        val saved = library.positionFor(request.title, request.episodes[i].id)?.takeIf { it.resumable }?.positionMs ?: 0
        resumedAt = saved.takeIf { it > 0 }
        prepare(saved, true)
    }

    private fun selectRendition(i: Int) {
        if (i == renditionIndex) return
        val pos = player?.currentPosition ?: 0
        failedRenditions.clear()
        renditionIndex = i
        prepare(pos, true)
    }

    @Composable
    private fun Screen(p: ExoPlayer) {
        var controls by remember { mutableStateOf(true) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.show_player_view, null) as PlayerView).apply {
                    player = p
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShowSubtitleButton(true)
                    setShowNextButton(false); setShowPreviousButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
                }
            }, modifier = Modifier.fillMaxSize().testTag("vod_player"))
            if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            AnimatedVisibility(controls || error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) { TopBar() }
            resumedAt?.let { at ->
                LaunchedEffect(at) { delay(6_000); resumedAt = null }
                Row(Modifier.align(Alignment.BottomStart).padding(24.dp, 96.dp).clip(RoundedCornerShape(50)).background(ShowColors.GlassStrong)
                    .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Resumed at ${formatTime(at)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { player?.seekTo(0); resumedAt = null }, modifier = Modifier.testTag("vod_start_over")) { Text("Start over", color = Color.White) }
                }
            }
            error?.let { msg ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                    Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(msg, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("vod_error"))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ShowPrimaryButton(onClick = { failedRenditions.clear(); renditionIndex = 0; prepare(player?.currentPosition ?: 0, true) }) { Text("Try again") }
                            ShowSecondaryButton(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TopBar() {
        val ep = request.episodes.getOrNull(episodeIndex)
        var qualityMenu by remember { mutableStateOf(false) }
        var episodeMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(ShowColors.TopScrim).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finish() }, modifier = Modifier.testTag("vod_back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                if (ep != null) {
                    Text(request.title.title, color = ShowColors.Mist, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text("${ep.code} · ${ep.title}", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("vod_episode_title"))
                } else {
                    Text(request.title.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("vod_movie_title"))
                }
            }
            if (request.isEpisodic) {
                IconButton(onClick = { playEpisode(episodeIndex - 1) }, enabled = episodeIndex > 0, modifier = Modifier.testTag("vod_prev")) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous episode", tint = if (episodeIndex > 0) Color.White else ShowColors.Mist)
                }
                IconButton(onClick = { playEpisode(episodeIndex + 1) }, enabled = episodeIndex < request.episodes.lastIndex, modifier = Modifier.testTag("vod_next")) {
                    Icon(Icons.Rounded.SkipNext, "Next episode", tint = if (episodeIndex < request.episodes.lastIndex) Color.White else ShowColors.Mist)
                }
                Box {
                    IconButton(onClick = { episodeMenu = true }, modifier = Modifier.testTag("vod_episodes")) { Icon(Icons.Rounded.List, "Episodes", tint = Color.White) }
                    DropdownMenu(episodeMenu, onDismissRequest = { episodeMenu = false }, containerColor = ShowColors.Surface) {
                        request.episodes.forEachIndexed { i, e ->
                            DropdownMenuItem(text = { Text((if (i == episodeIndex) "▶ " else "") + "${e.code} · ${e.title}", color = Color.White, maxLines = 1) },
                                onClick = { episodeMenu = false; playEpisode(i) })
                        }
                    }
                }
            }
            val renditions = videos()
            if (renditions.size > 1) Box {
                TextButton(onClick = { qualityMenu = true }, modifier = Modifier.testTag("vod_quality")) {
                    Icon(Icons.Rounded.HighQuality, null, tint = Color.White); Spacer(Modifier.padding(2.dp))
                    Text(renditions.getOrNull(renditionIndex)?.label ?: "", color = Color.White)
                }
                DropdownMenu(qualityMenu, onDismissRequest = { qualityMenu = false }, containerColor = ShowColors.Surface) {
                    renditions.forEachIndexed { i, v ->
                        DropdownMenuItem(text = { Text((if (i == renditionIndex) "✓ " else "") + v.label + (v.sizeBytes?.let { "  ·  ${it / 1_000_000} MB" } ?: ""), color = Color.White) },
                            onClick = { qualityMenu = false; selectRendition(i); appGraph.vodSettings.setPreferredHeight(v.height) }, modifier = Modifier.testTag("vod_quality_$i"))
                    }
                }
            }
        }
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        // Back on screen: reconnect where the viewer left off, but don't auto-play (they paused by leaving).
        player?.let { if (it.playbackState == Player.STATE_IDLE && error == null) it.prepare() }
    }

    override fun onStop() {
        save()
        player?.let { p ->
            jellyfinItem()?.let { id -> val pos = p.currentPosition; lifecycleScope.launch { jellyfin?.report(id, pos, "progress", paused = true) } }
            p.playWhenReady = false
            p.stop() // keeps the item and position; frees the network, decoder and buffer while off-screen
        }
        tickJob?.cancel()
        keepAwake(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_EP, episodeIndex)
        player?.let { outState.putLong(KEY_POS, it.currentPosition) }
    }

    override fun onDestroy() {
        player?.let { p ->
            jellyfinItem()?.let { id -> val pos = p.currentPosition; appGraph.vodScope.launch { jellyfin?.report(id, pos, "stop") } }
            p.removeListener(listener); p.release()
        }
        player = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_EP = "ep"; private const val KEY_POS = "pos"
        @Volatile internal var pending: VodPlayRequest? = null

        fun start(context: Context, req: VodPlayRequest) {
            pending = req
            val i = Intent(context, VodPlayerActivity::class.java)
            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
