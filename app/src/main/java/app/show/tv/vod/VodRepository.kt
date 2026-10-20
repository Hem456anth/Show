package app.show.tv.vod

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Combines the providers for the Movies & Series tab. Playback providers (Jellyfin, Internet
 * Archive) and metadata providers (TMDB, or TVmaze without a key) feed one catalog, but a title
 * only offers Play when its provider streams it, or when the viewer's Jellyfin holds the same TMDB id.
 */
class VodRepository(
    private val http: VodHttp,
    private val config: () -> VodConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val factory: SourceFactory = SourceFactory.Default,
) {
    fun interface SourceFactory {
        fun create(http: VodHttp, c: VodConfig): List<VodSource>
        companion object {
            val Default = SourceFactory { http, c ->
                buildList {
                    c.jellyfin?.let { add(JellyfinSource(http, it)) }
                    if (c.tmdbKey != null) add(TmdbSource(http, c.tmdbKey)) else add(TvMazeSource(http))
                    if (c.archive) add(ArchiveSource(http))
                }
            }
        }
    }

    private val lock = Mutex()
    private var cached: Triple<VodConfig, Long, List<VodRow>>? = null
    private var sources: Pair<VodConfig, List<VodSource>>? = null
    private var twins: Pair<VodConfig, Map<String, VodTitle>>? = null

    fun sources(): List<VodSource> {
        val c = config()
        sources?.let { (cc, list) -> if (cc == c) return list }
        return factory.create(http, c).also { sources = c to it }
    }

    fun source(p: Provider): VodSource? = sources().firstOrNull { it.provider == p }

    data class Catalog(val rows: List<VodRow>, val failed: List<Provider>)

    /** All rows, cached for [TTL_MS]. A provider that fails is reported, the others still show. */
    suspend fun catalog(force: Boolean = false): Catalog = lock.withLock {
        val c = config()
        cached?.let { (cc, at, rows) -> if (!force && cc == c && clock() - at < TTL_MS) return Catalog(rows, emptyList()) }
        val results = coroutineScope { sources().map { s -> async { s.provider to runCatching { s.rows() } } }.awaitAll() }
        val rows = results.flatMap { it.second.getOrNull() ?: emptyList() }
        val failed = results.filter { it.second.isFailure || it.second.getOrNull().isNullOrEmpty() }.map { it.first }
        if (rows.isNotEmpty()) cached = Triple(c, clock(), rows)
        Catalog(rows, failed)
    }

    /** Searches every provider at once; playable copies win over info-only ones of the same title. */
    suspend fun search(query: String): List<VodTitle> {
        if (query.trim().length < 2) return emptyList()
        val all = coroutineScope { sources().map { s -> async { runCatching { s.search(query) }.getOrDefault(emptyList()) } }.awaitAll() }
        return dedupe(all.flatten())
    }

    suspend fun detail(t: VodTitle): VodDetail = (source(t.provider) ?: sourceFor(t)).detail(t)
    suspend fun season(t: VodTitle, s: VodSeason): VodSeason = (source(t.provider) ?: sourceFor(t)).season(t, s)
    suspend fun browse(s: VodShortcut): List<VodTitle> = browsePage(s, 1).items

    /** One page of a studio, network, person or collection. TMDB pages need a key: without one this says so. */
    suspend fun browsePage(s: VodShortcut, page: Int): VodPage {
        val src = source(s.provider)
        if (src == null && s.provider == Provider.TMDB) throw IllegalStateException(NEEDS_TMDB)
        return src?.browsePage(s, page) ?: VodPage(emptyList())
    }

    /** A movie's TMDB collection, parts in release order. Empty without a TMDB key. */
    suspend fun collection(ref: VodCollectionRef): List<VodTitle> = (source(Provider.TMDB) as? TmdbSource)?.collection(ref.id) ?: emptyList()

    val hasTmdb: Boolean get() = config().tmdbKey != null

    /** Sources are rebuilt from config, but a saved title must still open if its provider is now off. */
    private fun sourceFor(t: VodTitle): VodSource = when (t.provider) {
        Provider.ARCHIVE -> ArchiveSource(http)
        Provider.TVMAZE -> TvMazeSource(http)
        Provider.TMDB -> config().tmdbKey?.let { TmdbSource(http, it) } ?: throw IllegalStateException("Add a TMDB key in Movies settings to open this title.")
        Provider.JELLYFIN -> throw IllegalStateException("Sign in to Jellyfin in Movies settings to play this title.")
    }

    /** The viewer's Jellyfin copy of a TMDB title, if any. */
    suspend fun jellyfinTwin(t: VodTitle): VodTitle? {
        if (t.provider == Provider.JELLYFIN || t.tmdbId == null) return null
        val jf = source(Provider.JELLYFIN) as? JellyfinSource ?: return null
        val c = config()
        val index = twins?.takeIf { it.first == c }?.second ?: runCatching { jf.tmdbIndex() }.getOrDefault(emptyMap()).also { twins = c to it }
        return index["${t.kind}:${t.tmdbId}"]
    }

    fun http(): VodHttp = http

    fun invalidate() { cached = null; twins = null }

    companion object {
        const val TTL_MS = 30 * 60 * 1000L
        const val NEEDS_TMDB = "Studios, networks and collections come from TMDB. Add a free TMDB key in Movies settings to browse them."

        internal fun normTitle(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")

        /** One entry per (kind, title, year); providers that stream rank first. */
        fun dedupe(items: List<VodTitle>): List<VodTitle> {
            val rank = mapOf(Provider.JELLYFIN to 0, Provider.ARCHIVE to 1, Provider.TMDB to 2, Provider.TVMAZE to 3)
            return items.withIndex()
                .groupBy { (_, t) -> Triple(t.kind, normTitle(t.title), t.year) }
                .values.map { g -> g.minOf { it.index } to g.minWith(compareBy({ rank[it.value.provider] }, { it.index })).value }
                .sortedBy { it.first }.map { it.second }
        }
    }
}
