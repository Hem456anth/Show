package app.show.tv.live

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.show.tv.data.M3uParser
import app.show.tv.player.PlaybackController
import app.show.tv.player.RetryPolicy
import app.show.tv.player.StreamEngine
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opt-in live diagnostic (needs network): SHOW_LIVE_PLAYLIST=/path/to.m3u SHOW_LIVE_FILTER=regex.
 * Plays each matching channel through the app's real parser, engine and controller and writes
 * one line per channel (READY / error code / tracks) to SHOW_LIVE_OUT.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveStreamDiagnostic {
    @Test fun probe() {
        val path = System.getenv("SHOW_LIVE_PLAYLIST")
        assumeTrue(path != null)
        val filter = Regex(System.getenv("SHOW_LIVE_FILTER") ?: ".*", RegexOption.IGNORE_CASE)
        val out = File(System.getenv("SHOW_LIVE_OUT") ?: "/tmp/live_diag.txt")
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val engine = StreamEngine(ctx, client, segmentRetries = 1, retryBaseDelayMs = 100)
        val channels = M3uParser.parse(File(path).readText()).channels.filter { filter.containsMatchIn(it.name) }
        out.writeText("")
        for (ch in channels) {
            val c = PlaybackController(engine, engine.buildPlayer(), listOf(ch), 0, retry = RetryPolicy(0), stallTimeoutMs = 25_000)
            c.start()
            val end = System.currentTimeMillis() + 25_000
            var result = "TIMEOUT"
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS); Thread.sleep(10)
                val s = c.state.value
                if (s.issue != null && !s.retrying) { result = "ISSUE ${s.issue!!.kind} ${s.issue!!.title} | ${c.player.playerError?.errorCodeName} ${c.player.playerError?.cause}"; break }
                if (c.player.playbackState == Player.STATE_READY) {
                    val t = c.player.currentTracks.groups
                    val audio = t.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
                    val video = t.filter { it.type == C.TRACK_TYPE_VIDEO }.joinToString { g -> (0 until g.length).joinToString("/") { "${g.getTrackFormat(it).height}" } }
                    result = "READY audio=$audio video=[$video] links=${ch.links.size}"; break
                }
            }
            out.appendText("${ch.name}\t${ch.protocol}\t$result\t${ch.streamUrl.take(80)}\n")
            c.release()
        }
    }
}
