package app.show.tv.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Loads the iptv-org playlist with an offline-first cache.
 *
 *  - HTTPS only: a cleartext playlist URL is rejected before any socket is opened.
 *  - Conditional GET (ETag / Last-Modified) so refreshes cost a 304 when nothing changed.
 *  - Hard size cap protects memory against a hostile or broken endpoint.
 *  - The body is streamed to a temp file and atomically renamed, so a dropped connection
 *    never corrupts the last good copy, which is used as a fallback on any failure.
 */
class PlaylistRepository(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val playlistUrl: () -> String = { DEFAULT_PLAYLIST_URL },
    private val maxBytes: Long = MAX_PLAYLIST_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** What `group-title` means in the playlist being loaded (category, country or language). */
    private val grouping: () -> PlaylistSource.Grouping = { PlaylistSource.Grouping.CATEGORY },
) {
    sealed interface Source {
        data object Network : Source
        data object NotModified : Source
        data class Cache(val reason: Throwable?) : Source
    }

    data class Result(val playlist: Playlist, val source: Source, val fetchedAt: Long)

    class PlaylistException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Convenience for a fixed URL (tests, previews). */
    constructor(
        client: OkHttpClient, cacheDir: File, url: String, maxBytes: Long = MAX_PLAYLIST_BYTES,
        io: CoroutineDispatcher = Dispatchers.IO,
        grouping: PlaylistSource.Grouping = PlaylistSource.Grouping.CATEGORY,
    ) : this(client, cacheDir, { url }, maxBytes, io, System::currentTimeMillis, { grouping })

    /** Any fixed playlist (e.g. Free-TV), sharing this repository's client and cache folder. */
    fun forUrl(url: String, grouping: PlaylistSource.Grouping): PlaylistRepository =
        PlaylistRepository(client, cacheDir, url, maxBytes, io, grouping)

    /** A fixed iptv-org index, sharing this repository's client and cache folder. */
    fun forSource(source: PlaylistSource): PlaylistRepository =
        PlaylistRepository(client, cacheDir, source.url, maxBytes, io, source.grouping)

    /** One cache per source so switching playlists never shows another list's channels. */
    private val baseName: String get() = baseNameFor(playlistUrl())
    private val cacheFile get() = File(cacheDir, "$baseName.m3u")
    private val etagFile get() = File(cacheDir, "$baseName.etag")

    fun hasCache(): Boolean = cacheFile.isFile && cacheFile.length() > 0

    /** Fast path for app start: parse the cached copy if present. */
    suspend fun loadCached(): Result? = withContext(io) {
        if (!hasCache()) return@withContext null
        runCatching { Result(parseFile(cacheFile), Source.Cache(null), cacheFile.lastModified()) }.getOrNull()
    }

    /**
     * 1.5.3: for the secondary indexes (countries/languages, Free-TV), which only add metadata. A copy
     * younger than [maxAgeMs] is used as is: no request at all, instead of three conditional GETs on
     * every launch and resume.
     */
    suspend fun refreshIfOlderThan(maxAgeMs: Long): Result = withContext(io) {
        val age = clock() - cacheFile.lastModified()
        if (hasCache() && age in 0 until maxAgeMs) {
            runCatching { Result(parseFile(cacheFile), Source.NotModified, cacheFile.lastModified()) }.getOrNull()
                ?: refresh()
        } else refresh()
    }

    suspend fun refresh(): Result = withContext(io) {
        try {
            fetch()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (hasCache()) Result(parseFile(cacheFile), Source.Cache(t), cacheFile.lastModified())
            else throw (t as? PlaylistException ?: PlaylistException(t.message ?: "Network error", t))
        }
    }

    private fun fetch(): Result {
        val url = playlistUrl().toHttpUrlOrNull() ?: throw PlaylistException("Invalid playlist URL")
        if (!url.isHttps) throw PlaylistException("Refusing to load a playlist over cleartext HTTP")

        val request = Request.Builder().url(url).header("Accept", "audio/x-mpegurl, application/x-mpegurl, */*")
        if (hasCache() && etagFile.isFile) etagFile.readText().takeIf { it.isNotBlank() }?.let {
            request.header("If-None-Match", it)
        }

        client.newCall(request.build()).execute().use { response ->
            if (response.code == 304 && hasCache()) {
                cacheFile.setLastModified(clock())
                return Result(parseFile(cacheFile), Source.NotModified, clock())
            }
            if (!response.request.url.isHttps) throw PlaylistException("Playlist redirected to an insecure address")
            if (!response.isSuccessful) throw PlaylistException("Playlist server returned HTTP ${response.code}")
            val body = response.body ?: throw PlaylistException("Empty playlist response")
            val declared = body.contentLength()
            if (declared > maxBytes) throw PlaylistException("Playlist too large ($declared bytes)")

            cacheDir.mkdirs()
            val tmp = File(cacheDir, "$baseName.m3u.part") // per source: indexes download in parallel
            body.byteStream().use { input -> copyCapped(input, tmp) }
            val parsed = parseFile(tmp)
            if (parsed.channels.isEmpty()) {
                tmp.delete()
                throw PlaylistException("Playlist contained no playable channels")
            }
            if (!tmp.renameTo(cacheFile)) { tmp.copyTo(cacheFile, overwrite = true); tmp.delete() }
            response.header("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
            return Result(parsed, Source.Network, clock())
        }
    }

    private fun copyCapped(input: InputStream, target: File) {
        target.outputStream().buffered().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    out.close(); target.delete()
                    throw PlaylistException("Playlist exceeded ${maxBytes / (1024 * 1024)} MB limit")
                }
                out.write(buf, 0, read)
            }
        }
    }

    private fun parseFile(file: File): Playlist = file.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it, grouping()) }

    companion object {
        val DEFAULT_PLAYLIST_URL = PlaylistSource.DEFAULT.url
        const val MAX_PLAYLIST_BYTES = 32L * 1024 * 1024

        fun baseNameFor(url: String) = if (url == DEFAULT_PLAYLIST_URL) "playlist" else "playlist-" + M3uParser.stableId(url)
        fun cacheFileName(url: String) = baseNameFor(url) + ".m3u"
    }
}
