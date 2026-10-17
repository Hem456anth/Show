package app.show.tv.player

import android.content.Context
import android.content.Intent
import app.show.tv.data.Channel

/**
 * Hands a channel queue to [PlayerActivity]. The queue (possibly hundreds of channels) is kept
 * in memory to avoid the 1 MB Binder transaction limit; the selected channel is also written
 * to the Intent so playback still works after process death restores the activity.
 */
object PlaybackQueue {
    private const val MAX_QUEUE = 200
    private const val EXTRA_TOKEN = "queue_token"
    private const val EXTRA_ID = "ch_id"
    private const val EXTRA_NAME = "ch_name"
    private const val EXTRA_URL = "ch_url"
    private const val EXTRA_LOGO = "ch_logo"
    private const val EXTRA_CATS = "ch_cats"
    private const val EXTRA_UA = "ch_ua"
    private const val EXTRA_REF = "ch_ref"
    private const val EXTRA_RES = "ch_res"

    @Volatile private var pending: Pair<Long, List<Channel>>? = null

    fun intent(context: Context, channel: Channel, queue: List<Channel>): Intent {
        val window = buildWindow(channel, queue)
        val token = System.nanoTime()
        pending = token to window
        return Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra(EXTRA_ID, channel.id)
            .putExtra(EXTRA_NAME, channel.name)
            .putExtra(EXTRA_URL, channel.streamUrl)
            .putExtra(EXTRA_LOGO, channel.logoUrl)
            .putExtra(EXTRA_CATS, channel.categories.joinToString(";"))
            .putExtra(EXTRA_UA, channel.userAgent)
            .putExtra(EXTRA_REF, channel.referrer)
            .putExtra(EXTRA_RES, channel.resolution)
    }

    /** Queue centred on the chosen channel, capped so zapping memory stays bounded. */
    internal fun buildWindow(channel: Channel, queue: List<Channel>): List<Channel> {
        val list = if (queue.any { it.id == channel.id }) queue else listOf(channel) + queue
        if (list.size <= MAX_QUEUE) return list
        val i = list.indexOfFirst { it.id == channel.id }
        val from = (i - MAX_QUEUE / 2).coerceIn(0, list.size - MAX_QUEUE)
        return list.subList(from, from + MAX_QUEUE).toList()
    }

    /** Returns the queue and start index, or null if the intent is unusable. */
    fun take(intent: Intent): Pair<List<Channel>, Int>? {
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        val token = intent.getLongExtra(EXTRA_TOKEN, -1)
        val held = pending
        if (held != null && held.first == token) {
            val idx = held.second.indexOfFirst { it.id == id }
            if (idx >= 0) return held.second to idx
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        val single = Channel(
            id = id, name = intent.getStringExtra(EXTRA_NAME) ?: url, streamUrl = url,
            logoUrl = intent.getStringExtra(EXTRA_LOGO),
            categories = intent.getStringExtra(EXTRA_CATS)?.split(';')?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf(Channel.UNCATEGORISED) },
            tvgId = null, language = null, country = null, resolution = intent.getStringExtra(EXTRA_RES), flags = emptySet(),
            userAgent = intent.getStringExtra(EXTRA_UA), referrer = intent.getStringExtra(EXTRA_REF),
        )
        return listOf(single) to 0
    }
}
