package app.show.tv

import android.app.Application
import android.content.Context
import app.show.tv.data.LibraryStore
import app.show.tv.data.PlaylistRepository
import app.show.tv.data.SettingsStore
import app.show.tv.player.StreamEngine
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled service locator: tiny graph, no reflection, trivial to swap in tests. */
class AppGraph(
    context: Context,
    playlistUrl: String? = null,
    /** Fetch the other iptv-org indexes to add countries/languages. Off when a test pins the URL. */
    enrichFromIndexes: Boolean = playlistUrl == null,
) {
    private val app = context.applicationContext

    val settings = SettingsStore(app.getSharedPreferences("settings", Context.MODE_PRIVATE)).also { s ->
        playlistUrl?.let { s.setPlaylistUrl(it) }
    }

    /** Playlist client: never follows a redirect across schemes, so https can't be downgraded. */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(false) // never downgrade https -> http on redirect
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Media client: many broadcasters redirect http:// to https:// (an upgrade), which the
     * playlist client would refuse with "too many follow-up requests" style failures.
     * Cleartext itself is still governed by network_security_config.xml.
     */
    val mediaClient: OkHttpClient = httpClient.newBuilder().followSslRedirects(true).build()

    val playlistRepository = PlaylistRepository(
        httpClient, File(app.filesDir, "playlist"), playlistUrl = settings::playlistUrl,
        grouping = { settings.settings.value.grouping },
    )
    val indexRepository: ((app.show.tv.data.PlaylistSource) -> PlaylistRepository)? =
        if (enrichFromIndexes) playlistRepository::forSource else null

    val libraryStore = LibraryStore(app.getSharedPreferences("library", Context.MODE_PRIVATE))
    val streamEngine = StreamEngine(app, mediaClient)

    val imageLoader: ImageLoader by lazy { ImageLoader.Builder(app)
        .okHttpClient(mediaClient.newBuilder().readTimeout(10, TimeUnit.SECONDS).build())
        .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("artwork")).maxSizeBytes(64L * 1024 * 1024).build() }
        .respectCacheHeaders(false) // logos rarely change; keep them for the disk-cache lifetime
        .crossfade(180)
        .build() }
}

class ShowApp : Application(), ImageLoaderFactory {
    /** Swappable so tests can point the app at a local server. */
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(): ImageLoader = graph.imageLoader
}

val Context.appGraph: AppGraph get() = (applicationContext as ShowApp).graph
