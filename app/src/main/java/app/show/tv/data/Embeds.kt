package app.show.tv.data

/**
 * Live channels that play only in a platform's own embedded player (1.5.4): YouTube and Dailymotion.
 *
 * Show never extracts raw media from either platform (their terms forbid it). A channel's stream URL
 * is the platform's official embed URL, in exactly one of three shapes, and anything else is rejected:
 *
 *  - https://www.youtube.com/embed/live_stream?channel=UC…   (a channel's current live broadcast)
 *  - https://www.youtube.com/embed/<11-char video id>
 *  - https://geo.dailymotion.com/player.html?video=x…
 *
 * They're verified as live and embeddable by `tools/verify_embeds.py` through each platform's public
 * API, and played by [app.show.tv.player.EmbedPlayerActivity].
 */
object Embeds {
    enum class Kind(val label: String) { YOUTUBE("YouTube"), DAILYMOTION("Dailymotion") }

    data class Target(val kind: Kind, val id: String, val isChannel: Boolean) {
        /** The platform's own page, for "Open in YouTube/Dailymotion". */
        val watchUrl: String get() = when {
            kind == Kind.DAILYMOTION -> "https://www.dailymotion.com/video/$id"
            isChannel -> "https://www.youtube.com/channel/$id/live"
            else -> "https://www.youtube.com/watch?v=$id"
        }
    }

    private val ytChannel = Regex("""^https://www\.youtube\.com/embed/live_stream\?channel=(UC[A-Za-z0-9_-]{22})$""")
    private val ytVideo = Regex("""^https://www\.youtube\.com/embed/([A-Za-z0-9_-]{11})$""")
    private val dm = Regex("""^https://geo\.dailymotion\.com/player\.html\?video=(x[a-z0-9]{3,12})$""")

    fun parse(url: String?): Target? {
        val u = url?.trim() ?: return null
        ytChannel.matchEntire(u)?.let { return Target(Kind.YOUTUBE, it.groupValues[1], true) }
        ytVideo.matchEntire(u)?.let { return Target(Kind.YOUTUBE, it.groupValues[1], false) }
        dm.matchEntire(u)?.let { return Target(Kind.DAILYMOTION, it.groupValues[1], false) }
        return null
    }

    fun isEmbed(url: String?) = parse(url) != null

    /** Origin the wrapper page is loaded under; YouTube's embed refuses to play without a referrer (error 153). */
    const val ORIGIN = "https://app.show.tv"

    /**
     * The wrapper page: one full-bleed iframe with the platform's player. For YouTube the official
     * IFrame Player API reports state and errors to the app through the `ShowEmbed` bridge
     * (state codes and error codes only). Ids are validated by [parse] before they get here.
     */
    fun html(t: Target): String {
        val src = when {
            t.kind == Kind.DAILYMOTION -> "https://geo.dailymotion.com/player.html?video=${t.id}&mute=false"
            t.isChannel -> "https://www.youtube.com/embed/live_stream?channel=${t.id}&autoplay=1&playsinline=1&enablejsapi=1&rel=0&origin=$ORIGIN"
            else -> "https://www.youtube.com/embed/${t.id}?autoplay=1&playsinline=1&enablejsapi=1&rel=0&origin=$ORIGIN"
        }
        val api = if (t.kind == Kind.YOUTUBE) """
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', { events: {
    onReady: function(e) { e.target.playVideo(); },
    onStateChange: function(e) { ShowEmbed.state(e.data); },
    onError: function(e) { ShowEmbed.error(e.data); }
  }});
}
function showPause() { if (player && player.pauseVideo) player.pauseVideo(); }
</script>""" else """
<script>function showPause() {}</script>
<script>ShowEmbed.state(1);</script>"""
        return """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
</head><body>
<iframe id="p" src="$src" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
$api
</body></html>"""
    }

    /** YouTube IFrame API error codes -> plain words. */
    fun errorText(code: Int): String = when (code) {
        2 -> "This channel's live link is invalid."
        5 -> "This live stream can't play in this player."
        100 -> "This channel isn't live right now."
        101, 150 -> "The broadcaster doesn't allow this stream outside YouTube."
        152, 153 -> "YouTube refused the embedded player. Try opening it in YouTube."
        else -> "This live stream couldn't start."
    }
}
