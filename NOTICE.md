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
  public stream addresses). Channel names and logos belong to their broadcasters. Because the project declares no licence,
  Show bundles none of its files: only the URLs Show itself verified, which are facts about public streams.

* **YouTube and Dailymotion live channels** (1.5.4) play only in each platform's own embedded player (YouTube IFrame Player
  API, Dailymotion embed player) under their terms of service. Show never downloads or extracts their media. Live status is
  checked with the YouTube Data API v3 and Dailymotion's public Data API. Ads, if any, are the platform's.

## Movies & Series data (1.6.0)
* Internet Archive (archive.org): public-domain and Creative Commons items only, as declared by each item's `licenseurl`.
* TMDB: "This product uses the TMDB API but is not endorsed or certified by TMDB." (themoviedb.org)
* TVmaze (tvmaze.com): TV data under CC BY-SA 4.0.
* Jellyfin (jellyfin.org): Show talks to the viewer's own server over its public API; no Jellyfin code is included.

## Movies & Series additions (1.8.0)
* TMDB collections and discover listings, plus studio and network logos: TMDB-hosted images, loaded at run time and shown
  only to identify each studio or network (never bundled). Logos and names belong to their owners. Same TMDB attribution as above.
* Trakt (trakt.tv): optional watchlist/history sync and scrobbling through Trakt's public API and OAuth device flow, with the
  viewer's consent. "Watch history sync by Trakt. Not endorsed by Trakt." Trakt supplies no video.
* Feature ideas were compared against other open listings of streaming-app features; no third-party app code, assets or strings are included.
* No new libraries: token encryption uses the Android Keystore and `javax.crypto` from the platform.

## Third-party libraries (all Apache License 2.0)
* AndroidX Media3 (ExoPlayer, HLS, DASH, SmoothStreaming, RTSP, OkHttp and RTMP data sources, UI): The Android Open Source Project
* `media3-datasource-rtmp` bundles LibRtmp-Client-for-Android (Apache 2.0 wrapper of librtmp; librtmp is LGPL-2.1, dynamically loaded `.so`)
* AndroidX Core, Activity, Lifecycle, Navigation, Compose (UI, Material 3, Material Icons)
* Kotlin and kotlinx.coroutines: JetBrains
* OkHttp: Square
* Coil: Coil Contributors
* Test-only: JUnit 4 (EPL-1.0), Robolectric (MIT), MockWebServer and okhttp-tls (Apache 2.0)

## VLC
VLC media player and VLC for Android (© VideoLAN and contributors, GPL-2.0+/LGPL-2.1+) were studied
through their public source and static inspection of a user-supplied APK with REA (MIT, morluto/rea)
and JADX (Apache 2.0). No VLC code, binaries, icons, strings or trademarks are included.
"VLC" is a trademark of VideoLAN.

## Design inspiration
The layout follows common streaming-app patterns (hero banner, carousels, bottom navigation).
No third-party branding, logos, typefaces, colour schemes or artwork are used.
