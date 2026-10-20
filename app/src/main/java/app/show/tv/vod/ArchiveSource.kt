package app.show.tv.vod

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Internet Archive (archive.org): public-domain and Creative Commons feature films and classic TV.
 *
 * Only items whose uploader-declared `licenseurl` is a public-domain mark/dedication or a Creative
 * Commons licence are listed or played ([isRightsClear]). The `classic_tv` collection also holds
 * copyrighted uploads (whole box sets of later shows), so the licence filter is applied in the
 * search query AND again on each item's metadata before anything plays. Items the Archive has
 * taken down (`is_dark`) are refused.
 *
 * APIs: advancedsearch.php (catalog), /metadata/<id> (files), /services/img/<id> (artwork),
 * /download/<id>/<file> (the MP4 itself, HTTP range-capable). No key, no account.
 */
class ArchiveSource(private val http: VodHttp, private val base: String = "https://archive.org") : VodSource {
    override val provider = Provider.ARCHIVE

    private data class Shelf(val id: String, val title: String, val query: String, val kind: VodKind, val sort: String = "downloads desc")

    private val shelves = listOf(
        Shelf("ia_popular", "Classic Movies", "collection:feature_films", VodKind.MOVIE),
        Shelf("ia_noir", "Film Noir", "collection:feature_films AND subject:(\"film noir\")", VodKind.MOVIE),
        Shelf("ia_scifi", "Sci-Fi & Horror", "collection:(scifi_horror OR feature_films) AND subject:(horror OR \"science fiction\" OR sci-fi)", VodKind.MOVIE),
        Shelf("ia_comedy", "Comedy Classics", "collection:feature_films AND subject:(comedy)", VodKind.MOVIE),
        Shelf("ia_silent", "Silent Era", "collection:silent_films", VodKind.MOVIE),
        Shelf("ia_tv", "Classic TV Series", "collection:classic_tv", VodKind.SHOW),
        Shelf("ia_new", "Recently Added", "collection:feature_films", VodKind.MOVIE, sort = "addeddate desc"),
    )

    // 1.8.1: also require an MP4 derivative, so dead items (video removed, metadata only) never list.
    private val rights = "licenseurl:(*publicdomain* OR *creativecommons*) AND " +
        MP4_FORMATS.joinToString(" OR ", "format:(", ")") { "\"$it\"" }

    internal fun searchUrl(q: String, rows: Int, sort: String) =
        "$base/advancedsearch.php?q=${"($q) AND mediatype:movies AND $rights".enc()}" +
            listOf("identifier", "title", "year", "description", "subject", "collection", "licenseurl", "runtime")
                .joinToString("") { "&fl%5B%5D=$it" } +
            "&sort%5B%5D=${sort.enc()}&rows=$rows&page=1&output=json"

    override suspend fun rows(): List<VodRow> = coroutineScope {
        shelves.map { s ->
            async {
                runCatching { VodRow(s.id, s.title, query(s.query, 30, s.sort, s.kind), provider) }.getOrNull()
            }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    }

    override suspend fun search(query: String): List<VodTitle> {
        val clean = query.replace(Regex("""[^\p{L}\p{N} ]"""), " ").trim()
        if (clean.isEmpty()) return emptyList()
        return query("title:($clean) AND collection:(feature_films OR classic_tv OR silent_films OR scifi_horror)", 30, "downloads desc", null)
    }

    private suspend fun query(q: String, rows: Int, sort: String, kind: VodKind?): List<VodTitle> {
        val docs = http.json(searchUrl(q, rows, sort)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { parseDoc(docs.getJSONObject(it), kind) }
    }

    internal fun parseDoc(d: JSONObject, kind: VodKind?): VodTitle? {
        val id = d.str("identifier") ?: return null
        if (!isRightsClear(d.str("licenseurl"))) return null
        val collections = d.strings("collection")
        val k = kind ?: if ("classic_tv" in collections) VodKind.SHOW else VodKind.MOVIE
        val img = "$base/services/img/$id"
        return VodTitle(
            Provider.ARCHIVE, id, k, cleanTitle(d.str("title") ?: id),
            year = d.str("year")?.take(4)?.toIntOrNull(),
            overview = d.strings("description").firstOrNull()?.let(::stripHtml),
            posterUrl = img, backdropUrl = img,
            genres = d.strings("subject").flatMap { it.split(';', ',') }.map { it.trim() }
                .filter { it.length in 3..24 && !it.contains("public domain", true) && !it.contains("feature", true) }
                .map { it.replaceFirstChar(Char::uppercase) }.distinct().take(3),
            runtimeMin = parseRuntime(d.str("runtime")),
        )
    }

    override suspend fun detail(title: VodTitle): VodDetail {
        val meta = http.json("$base/metadata/${title.providerId.enc()}")
        val md = meta.optJSONObject("metadata") ?: throw IllegalStateException("This title is no longer on the Internet Archive.")
        if (meta.optBoolean("is_dark")) throw IllegalStateException("This title was taken down by the Internet Archive.")
        val license = md.strings("licenseurl").firstOrNull()
        val clear = isRightsClear(license)
        val parts = if (clear) videoParts(title.providerId, meta.optJSONArray("files") ?: JSONArray()) else emptyList()
        val enriched = title.copy(
            overview = md.strings("description").firstOrNull()?.let(::stripHtml) ?: title.overview,
            year = title.year ?: md.str("year")?.take(4)?.toIntOrNull() ?: md.str("date")?.take(4)?.toIntOrNull(),
        )
        val people = (md.strings("creator") + md.strings("director")).distinct().map { VodPerson(it, "Credited", null) }
        val cast = md.strings("contributor").map { VodPerson(it, null, null) }
        return if (title.kind == VodKind.SHOW) {
            val eps = parts.mapIndexed { i, p ->
                val (s, e) = episodeNumbers(p.name) ?: (1 to i + 1)
                VodEpisode(p.name, s, e, p.title, stillUrl = "$base/services/img/${title.providerId}", runtimeSec = p.lengthSec, sources = p.videos)
            }.sortedWith(compareBy({ it.season }, { it.number }))
            val seasons = eps.groupBy { it.season }.map { (n, list) -> VodSeason(n, "Season $n", episodes = list, episodeCount = list.size) }
            VodDetail(enriched, cast = cast, crew = people, companies = md.strings("publisher"), license = license,
                sourcePage = "$base/details/${title.providerId}", seasons = seasons)
        } else {
            // A film item can also carry trailers or reels: the film is the longest part.
            val film = parts.maxByOrNull { it.lengthSec ?: 0 }
            VodDetail(enriched.copy(runtimeMin = enriched.runtimeMin ?: film?.lengthSec?.div(60)), cast = cast, crew = people,
                companies = md.strings("publisher"), license = license, sourcePage = "$base/details/${title.providerId}",
                sources = film?.videos ?: emptyList())
        }
    }

    internal data class Part(val name: String, val title: String, val lengthSec: Int?, val videos: List<VodVideo>)

    /** Groups an item's MP4 renditions by the original they were derived from; best (tallest) first. */
    internal fun videoParts(id: String, files: JSONArray): List<Part> {
        val mp4 = (0 until files.length()).map { files.getJSONObject(it) }.filter { f ->
            val name = f.optString("name")
            name.endsWith(".mp4", true) && f.optString("format") in MP4_FORMATS
        }
        return mp4.groupBy { f -> if (f.optString("source") == "derivative") f.optString("original") else f.optString("name") }
            .map { (orig, renditions) ->
                val videos = renditions.sortedByDescending { it.optString("height").toIntOrNull() ?: 0 }.map { f ->
                    val h = f.optString("height").toIntOrNull()
                    VodVideo(label = h?.let { "${it}p" } ?: f.optString("format"), url = "$base/download/${id.enc()}/${f.optString("name").split('/').joinToString("/") { it.enc() }}",
                        height = h, sizeBytes = f.optString("size").toLongOrNull())
                }.distinctBy { it.label }
                val first = renditions.first()
                Part(orig, cleanTitle(first.str("title") ?: orig.substringBeforeLast('.').substringAfterLast('/')),
                    renditions.mapNotNull { it.optString("length").toDoubleOrNull() }.maxOrNull()?.toInt(), videos)
            }
            .filter { it.videos.isNotEmpty() }
    }

    companion object {
        val MP4_FORMATS = setOf("h.264", "h.264 IA", "MPEG4", "512Kb MPEG4", "HiRes MPEG4", "h.264 HD")

        fun isRightsClear(license: String?): Boolean {
            val l = license?.lowercase() ?: return false
            return "creativecommons.org" in l && ("publicdomain" in l || "/licenses/" in l)
        }

        internal fun episodeNumbers(name: String): Pair<Int, Int>? {
            Regex("""[sS](\d{1,2})[ ._-]?[eE](\d{1,3})""").find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            Regex("""(\d{1,2})x(\d{2})""").find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            return null
        }

        internal fun cleanTitle(t: String): String = t
            .replace(Regex("""^[sS]\d{1,2}[eE]\d{1,3}[ _-]*"""), "")
            .replace('_', ' ').replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ").replace(Regex("""\s+"""), " ").trim()
            .ifEmpty { t }

        internal fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("""\s+"""), " ").trim().take(1200)

        internal fun parseRuntime(r: String?): Int? {
            val s = r?.trim() ?: return null
            val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
            return when {
                parts.size == 3 -> parts[0] * 60 + parts[1]
                parts.size == 2 -> parts[0]
                else -> Regex("""(\d+)\s*min""").find(s)?.groupValues?.get(1)?.toInt()
            }
        }
    }
}

/** advancedsearch returns a field as a string or an array; read both. */
internal fun JSONObject.strings(k: String): List<String> = when (val v = opt(k)) {
    is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).ifBlank { null } }
    is String -> listOf(v).filter { it.isNotBlank() }
    null -> emptyList()
    else -> listOf(v.toString())
}
