package app.show.tv.player

import app.show.tv.ui.components.ShowPrimaryButton
import app.show.tv.ui.components.ShowSecondaryButton
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.util.Rational
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.show.tv.R
import app.show.tv.appGraph
import app.show.tv.data.Channel
import app.show.tv.ui.theme.ShowColors
import app.show.tv.ui.theme.ShowTheme

/**
 * Dedicated fullscreen player (VLC's VideoPlayerActivity equivalent): immersive landscape,
 * Media3 controls (play/pause, live seek, subtitle + audio-track + speed pickers), channel
 * zapping, resize modes (fit/fill/zoom), picture-in-picture and connectivity-aware recovery.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var controller: PlaybackController? = null

    @androidx.annotation.VisibleForTesting
    internal fun currentController(): PlaybackController? = controller
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var controlsVisible by mutableStateOf(true)
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val graph = appGraph
        val queue = PlaybackQueue.take(intent)
        if (queue == null) { finish(); return }
        val lib = graph.libraryStore
        val c = PlaybackController(
            graph.streamEngine, graph.streamEngine.buildPlayer(), queue.first, queue.second,
            onChannelStarted = { lib.recordWatched(it) },
            onChannelPlaying = { lib.markAvailable(it.id) },
            onChannelFailed = { ch, _ -> lib.markUnavailable(ch.id) },
            isUnavailable = { it.id in lib.recentlyUnavailable() },
        )
        controller = c
        savedInstanceState?.getInt(KEY_RESIZE)?.let { resizeMode = it }
        c.start()
        registerNetworkCallback(c)

        setContent { ShowTheme { PlayerScreen(c) } }
    }

    /** singleTop: picking another channel (e.g. from the app while in PiP) re-targets this player. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val queue = PlaybackQueue.take(intent) ?: return
        controller?.replaceQueue(queue.first, queue.second)
    }

    @Composable
    private fun PlayerScreen(c: PlaybackController) {
        val ui by c.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_root")) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.show_player_view, null) as PlayerView).apply {
                        player = c.player
                        setShowSubtitleButton(true)
                        setShowNextButton(false); setShowPreviousButton(false)
                        setShowFastForwardButton(false); setShowRewindButton(false)
                        controllerShowTimeoutMs = 3_500
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // we draw our own
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                    }
                },
                update = { it.resizeMode = resizeMode; it.useController = !inPip },
            )

            if (ui.buffering && ui.issue == null) {
                CircularProgressIndicator(color = ShowColors.Accent, modifier = Modifier.align(Alignment.Center).size(56.dp).testTag("buffering"))
                if (ui.backup > 0) Text(
                    "Main stream is down. Trying backup ${ui.backup}…", color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(top = 96.dp).testTag("backup_notice"),
                )
            }

            AnimatedVisibility(controlsVisible && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                TopBar(ui.channel, c)
            }

            ui.issue?.let { issue -> if (!inPip) ErrorOverlay(issue, ui, c, Modifier.align(Alignment.Center)) }

            if (ui.sleptByTimer && ui.issue == null && !inPip) {
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(ShowColors.Night.copy(alpha = 0.94f)).padding(24.dp).testTag("slept"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Paused by sleep timer", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.size(14.dp))
                    ShowPrimaryButton(onClick = { c.resumeAfterSleep() }) { Text("Keep watching") }
                }
            }
        }
    }

    @Composable
    private fun TopBar(ch: Channel, c: PlaybackController) {
        Row(
            Modifier.fillMaxWidth().background(ShowColors.TopScrim).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                Text(listOfNotNull("LIVE", ch.primaryCategory, ch.resolution, ch.protocol.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = ShowColors.Mist)
            }
            if (c.hasQueue()) {
                IconButton(onClick = c::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous channel", tint = Color.White) }
                IconButton(onClick = c::next, modifier = Modifier.testTag("next_channel")) { Icon(Icons.Rounded.SkipNext, "Next channel", tint = Color.White) }
            }
            QualityMenu(c)
            SleepMenu(c)
            IconButton(onClick = { cycleResize() }) { Icon(Icons.Rounded.AspectRatio, "Resize: ${resizeLabel()}", tint = Color.White) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature("android.software.picture_in_picture")) {
                IconButton(onClick = { enterPip() }) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
            }
        }
    }

    @Composable
    private fun QualityMenu(c: PlaybackController) {
        val ui by c.state.collectAsStateWithLifecycle()
        if (ui.qualities.size < 2) return
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag("quality")) {
                Icon(Icons.Rounded.HighQuality, "Quality: ${ui.quality?.let { "${it}p" } ?: "Auto"}", tint = if (ui.quality != null) ShowColors.Accent else Color.White)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Auto" + if (ui.quality == null) "  ✓" else "") }, onClick = { c.setQuality(null); open = false })
                ui.qualities.forEach { h ->
                    DropdownMenuItem(text = { Text("${h}p" + if (ui.quality == h) "  ✓" else "") }, onClick = { c.setQuality(h); open = false })
                }
            }
        }
    }

    @Composable
    private fun SleepMenu(c: PlaybackController) {
        val ui by c.state.collectAsStateWithLifecycle()
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag("sleep")) {
                Icon(Icons.Rounded.Bedtime, if (ui.sleepAt != null) "Sleep timer on" else "Sleep timer", tint = if (ui.sleepAt != null) ShowColors.Accent else Color.White)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                listOf(15, 30, 60, 90).forEach { m ->
                    DropdownMenuItem(text = { Text("Pause in $m min") }, onClick = { c.setSleepTimer(m); open = false; toast("Pausing in $m minutes") })
                }
                if (ui.sleepAt != null) DropdownMenuItem(text = { Text("Turn off timer") }, onClick = { c.setSleepTimer(null); open = false })
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Composable
    private fun ErrorOverlay(issue: PlaybackIssue, ui: PlaybackController.UiState, c: PlaybackController, modifier: Modifier) {
        Column(
            modifier.widthIn(max = 420.dp).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(ShowColors.Night.copy(alpha = 0.94f)).padding(24.dp).testTag("player_error"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(issue.title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.size(8.dp))
            Text(
                if (ui.retrying) "${issue.detail} (attempt ${ui.attempt} of 3)" else issue.detail,
                style = MaterialTheme.typography.bodyMedium, color = ShowColors.Mist, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(18.dp))
            if (ui.retrying) CircularProgressIndicator(color = ShowColors.Accent, modifier = Modifier.size(32.dp))
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (issue.kind == PlaybackIssue.Kind.UNSUPPORTED_PROTOCOL) {
                    ShowPrimaryButton(onClick = { openExternally(ui.channel) }) { Text("Open externally") }
                } else {
                    ShowPrimaryButton(onClick = c::retryNow, modifier = Modifier.testTag("retry")) { Text("Try again") }
                }
                if (c.hasQueue()) ShowSecondaryButton(onClick = c::next) { Text("Next channel") }
            }
        }
    }

    private fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        toast(resizeLabel())
    }

    private fun resizeLabel() = when (resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom to fill"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
        else -> "Fit to screen"
    }

    private fun openExternally(ch: Channel) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(ch.streamUrl), "video/*")
        try { startActivity(Intent.createChooser(intent, "Open ${ch.name} with")) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app can open this stream", Toast.LENGTH_LONG).show() }
    }

    /**
     * TV remotes and keyboards: Channel +/− and media next/previous zap, "Last" recalls the
     * previous channel. Everything else (D-pad, OK, play/pause) goes to the Media3 controls.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = controller
        if (c != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP -> { c.next(); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> { c.previous(); return true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { c.recall(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controller?.player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // PiP window dismissed with its X: the activity is stopped; don't keep streaming in the background.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            controller?.player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving (not PiP): pause so we don't burn data in the background.
        if (!isInPictureInPictureMode) controller?.player?.pause()
    }

    override fun onStart() {
        super.onStart()
        // Resume only if playback was interrupted by leaving, not after an error or the sleep timer.
        controller?.let { c -> if (c.state.value.issue == null && !c.state.value.sleptByTimer) c.player.play() }
        hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_RESIZE, resizeMode)
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        controller?.release()
        controller = null
        super.onDestroy()
    }

    private fun registerNetworkCallback(c: PlaybackController) {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { c.onNetworkAvailable() } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val KEY_RESIZE = "resize"

        fun start(context: Context, channel: Channel, queue: List<Channel>) {
            context.startActivity(PlaybackQueue.intent(context, channel, queue))
        }
    }
}
