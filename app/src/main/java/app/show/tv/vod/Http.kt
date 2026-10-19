package app.show.tv.vod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Small JSON-over-HTTPS helper shared by the VOD providers. Bodies are capped so a bad server can't exhaust memory. */
class VodHttp(private val client: OkHttpClient, private val maxBytes: Long = 4L * 1024 * 1024) {
    class HttpError(val code: Int, msg: String) : IOException(msg)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = call(Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.header("Accept", "application/json").build())

    suspend fun json(url: String, headers: Map<String, String> = emptyMap()) = JSONObject(get(url, headers))

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String = call(Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.post(body.toRequestBody("application/json".toMediaType())).build())

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}")
            val body = r.body ?: return@use ""
            if (body.contentLength() > maxBytes) throw IOException("response too large")
            val src = body.source()
            src.request(maxBytes + 1)
            if (src.buffer.size > maxBytes) throw IOException("response too large")
            src.buffer.readUtf8()
        }
    }
}

internal fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
internal fun JSONObject.str(k: String): String? = optString(k).takeIf { has(k) && !isNull(k) && it.isNotBlank() }
