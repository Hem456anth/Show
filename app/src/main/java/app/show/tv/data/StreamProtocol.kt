package app.show.tv.data

/**
 * Transport/container classification, modelled on the access + demux module split in
 * VLC (`modules/access/` for transports, `modules/demux/adaptive` for HLS/DASH/Smooth).
 *
 * [playableInApp] is true for every protocol the bundled Media3 engine can open.
 * The rest (MMS, SRT, UDP/RTP multicast) are VLC-only transports; the player offers
 * to hand them to an external app instead of failing silently.
 */
enum class StreamProtocol(val label: String, val playableInApp: Boolean) {
    HLS("HLS", true),
    DASH("MPEG-DASH", true),
    SMOOTH("Smooth Streaming", true),
    RTSP("RTSP", true),
    RTMP("RTMP", true),
    PROGRESSIVE("HTTP progressive", true),
    MMS("MMS", false),
    SRT("SRT", false),
    UDP_RTP("UDP/RTP multicast", false),
    UNKNOWN("Unknown", false);

    companion object {
        fun of(url: String): StreamProtocol {
            val lower = url.trim().lowercase()
            val scheme = lower.substringBefore("://", missingDelimiterValue = "")
            val path = lower.substringAfter("://").substringBefore('?').substringBefore('#')
            return when (scheme) {
                "rtsp", "rtsps" -> RTSP
                "rtmp", "rtmps", "rtmpe", "rtmpt" -> RTMP
                "mms", "mmsh", "mmst", "mmsu" -> MMS
                "srt" -> SRT
                "udp", "rtp" -> UDP_RTP
                "http", "https" -> when {
                    path.endsWith(".m3u8") || path.endsWith(".m3u") || "/hls/" in path ||
                        "format=m3u8" in lower -> HLS
                    path.endsWith(".mpd") || "format=mpd" in lower -> DASH
                    path.endsWith("/manifest") || path.endsWith(".ism/manifest") || ".ism/" in path -> SMOOTH
                    // Most IPTV endpoints without an extension are HLS; Media3 sniffs content anyway.
                    else -> PROGRESSIVE
                }
                else -> UNKNOWN
            }
        }
    }
}
