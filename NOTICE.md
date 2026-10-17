# Notices and attribution

## Show
Original source code, UI, palette, name and launcher mark were written for this project.
Not affiliated with VideoLAN, Netflix, or iptv-org.

## Channel data
* **iptv-org/iptv**: https://github.com/iptv-org/iptv, released under the **Unlicense** (public domain).
  The playlist is a list of links to publicly available streams. Channel names and logos belong to
  their broadcasters and are shown only to identify each channel (logos are loaded from the URLs
  in the index, never bundled). Broadcasters can ask iptv-org to remove a link: https://github.com/iptv-org/iptv#legal

* **Free-TV/IPTV**: https://github.com/Free-TV/IPTV (no licence declared). Show fetches its public playlist at run
  time and uses only entries whose stream URL passed Show's own verification (`assets/verified_extra.json`, a list of
  public stream addresses). Channel names and logos belong to their broadcasters.

## Third-party libraries (all Apache License 2.0)
* AndroidX Media3 (ExoPlayer, HLS, DASH, SmoothStreaming, RTSP, OkHttp and RTMP data sources, UI): The Android Open Source Project
* `media3-datasource-rtmp` bundles LibRtmp-Client-for-Android (Apache 2.0 wrapper of librtmp; librtmp is LGPL-2.1, dynamically loaded `.so`)
* AndroidX Core, Activity, Lifecycle, Navigation, Compose (UI, Material 3, Material Icons)
* Kotlin and kotlinx.coroutines: JetBrains
* OkHttp: Square
* Coil: Coil Contributors
* Haze (backdrop blur): Chris Banes
* Test-only: JUnit 4 (EPL-1.0), Robolectric (MIT), MockWebServer and okhttp-tls (Apache 2.0)

## VLC
VLC media player and VLC for Android (© VideoLAN and contributors, GPL-2.0+/LGPL-2.1+) were studied
through their public source and static inspection of a user-supplied APK with REA (MIT, morluto/rea)
and JADX (Apache 2.0). No VLC code, binaries, icons, strings or trademarks are included.
"VLC" is a trademark of VideoLAN.

## Design inspiration
The layout follows common streaming-app patterns (hero banner, carousels, bottom navigation).
No third-party branding, logos, typefaces, colour schemes or artwork are used.
