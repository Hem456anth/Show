package app.show.tv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 1.5.4: keeps the Free-TV verification allowlist current between app releases.
 *
 * Free-TV links rotate weekly, so a list frozen into the APK slowly goes stale: dead links stay
 * allowed, and newly verified channels never appear. `.github/workflows/verify-free-tv.yml`
 * re-runs `tools/verify_extra_sources.py` every week and commits the result; this store fetches
 * that file from the Show repository at most once a day and uses whichever copy (bundled asset or
 * downloaded) was verified most recently.
 *
 * Safety: the URL is fixed (https, Show's own repository), the body is capped at [MAX_BYTES] and must
 * parse as an allowlist, and an allowlist can only *narrow* what Show plays. A URL must still be in
 * Free-TV's live playlist and pass every other filter in [ExtraSources.merge]. On any failure the
 * last good copy is kept.
 */
class AllowlistStore(
    private val client: OkHttpClient,
    private val dir: File,
    private val bundled: () -> String?,
    private val remoteUrl: String = REMOTE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
    /** Tests only (MockWebServer speaks plain http on loopback). */
    internal val requireHttps: Boolean = true,
) {
    private val cache get() = File(dir, "verified_extra.json")
    @Volatile private var memo: Pair<Long, ExtraSources.Allowlist>? = null

    /** Blocking: call off the main thread. */
    fun current(): ExtraSources.Allowlist {
        val now = clock()
        memo?.let { (at, list) -> if (now - at < maxAgeMs) return list }
        val bundledList = bundled()?.let(::parseVerified)
        var cached = cache.takeIf { it.isFile }?.let { runCatching { parseVerified(it.readText()) }.getOrNull() }
        val age = if (cached != null) now - cache.lastModified() else Long.MAX_VALUE
        if (cached == null || age !in 0 until maxAgeMs) fetch()?.let { cached = it }
        val best = listOfNotNull(bundledList, cached).maxByOrNull { it.first }?.second ?: ExtraSources.Allowlist.EMPTY
        memo = now to best
        return best
    }

    private fun fetch(): Pair<Long, ExtraSources.Allowlist>? = runCatching {
        require(!requireHttps || remoteUrl.startsWith("https://"))
        client.newCall(Request.Builder().url(remoteUrl).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) return null
            val text = bytes.toString(Charsets.UTF_8)
            val parsed = parseVerified(text)?.takeIf { it.first > 0 } ?: return null // a published list is always dated
            dir.mkdirs()
            val tmp = File(dir, "verified_extra.json.part")
            tmp.writeText(text)
            if (!tmp.renameTo(cache)) { tmp.copyTo(cache, overwrite = true); tmp.delete() }
            cache.setLastModified(clock())
            parsed
        }
    }.getOrNull()

    companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/Hem456anth/Show/main/app/src/main/assets/verified_extra.json"
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_BYTES = 2 * 1024 * 1024

        /** (verified_epoch, allowlist), or null when the text isn't a usable allowlist. */
        fun parseVerified(text: String): Pair<Long, ExtraSources.Allowlist>? {
            val list = ExtraSources.parseAllowlist(text)
            if (list.isEmpty) return null
            val epoch = runCatching { JSONObject(text).optLong("verified_epoch", 0L) }.getOrDefault(0L)
            return epoch to list
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
