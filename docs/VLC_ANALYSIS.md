# VLC streaming architecture: REA and source analysis

Show is an original app. This document records what was learned from VLC so that Show's
design decisions are traceable. Show includes no VLC code, assets, icons or strings.

## 1. Method

| Step | Tool | Input | Output |
|---|---|---|---|
| Static APK inspection | **REA 6.5.0** `rea inspect-android-package` (JADX headless 0.7.1; SHA-256 checked against REA's pinned `6e5eacf5…ff75`) | User-supplied `base.apk` = `org.videolan.vlc` **3.7.1** (versionCode 13070109, minSdk 30, targetSdk 36), sha256 `f38fb144…3946` | `rea-evidence/vlc-3.7.1-inspect-android-package.toon` (14,987 classes, 1,963 resources, decoded manifest) |
| Class inventory | `rea search-android-classes target.apk org.videolan.libvlc` | same APK | `rea-evidence/libvlc-classes.txt` (39 classes) |
| Member inventory | `rea inspect-android-class target.apk org.videolan.libvlc.MediaPlayer` | same APK | `rea-evidence/libvlc-MediaPlayer-members.txt` |
| Source cross-check | clones of `videolan/vlc-android` and a sparse `videolan/vlc` (`modules/access`, `modules/demux/adaptive`, `modules/demux/playlist`, `src/input`) | public GPL/LGPL source | findings below |

The APK is a split *base* APK with no `lib/*.so`, so native libvlc behaviour was read from public
source rather than disassembled. The analysis was static only: nothing from the APK was executed.

## 2. Transports (manifest intent filters and `modules/access`)

The manifest registers these schemes: `http, https, httplive, ftp, ftps, ftpes, sftp, smb, rtsp, rtp, rtmp, rtmps,
rtmpe, mms, mmsh, udp, icyx, file, content, vlc`. It also registers MIME types including `application/vnd.apple.mpegurl`,
`application/x-mpegURL`, `application/sdp`, `application/xspf+xml`, `application/x-matroska`, `video/*` and `audio/*`.

The matching access modules are `http/` (HTTP/1.1 and HTTP/2), `live555.cpp` (RTSP/RTP), `rtp/`, `udp.c`, `mms/`, `srt.c`,
`rist.c`, `ftp.c`, `sftp.c`, `smb2.c`, `nfs.c`, `satip.c`, `dtv/` and `dvb/`. Permissions VLC uses for playback include
`INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

## 3. Pipeline

```
URI → access (http/rtsp/rtmp/udp…) → stream filters (cache, prefetch)
    → demux (adaptive HLS/DASH/Smooth │ ts │ mp4 │ mkv │ playlist/m3u)
    → decoders (MediaCodec HW ▸ avcodec SW) → vout (android_display/gles2) + aout (AudioTrack)
                 ▲ es_out track selection (audio / video / SPU)
```

* The Java API (from REA) exposes `LibVLC`, `Media`, `MediaPlayer`, `MediaList`, `MediaDiscoverer`, `RendererDiscoverer` (cast),
  `Dialog` (TLS/login prompts), `util.VLCVideoLayout`, `util.HWDecoderUtil` and `interfaces.IVLCVout`.
* `MediaPlayer` controls (from REA): `setTime/setPosition/setRate`, `get/setAudioTrack(s)`, `get/setSpuTrack(s)`,
  `setSpuDelay`, `setAudioDelay`, `get/setVideoTrack(s)`, `addSlave` (side-loaded subs/audio), `setAspectRatio/setScale`,
  `setEqualizer`, `setRenderer`, `getChapters/getTitles`, `setTeletext` and `nativeRecord`.
* Process model: a foreground `PlaybackService` owns the player, and `VideoPlayerActivity` attaches a surface to it.

## 4. Buffering

* `--network-caching` is set in milliseconds. VLC-Android clamps it to `0..60000` (`VLCOptions.kt`), and the core default is 1000 ms.
* Adaptive (`modules/demux/adaptive/logic/BufferingLogic.cpp`) uses a 6 s minimum, a 30 s maximum and 15 s for live,
  with the tunables `adaptive-livedelay`, `adaptive-maxbuffer`, `adaptive-lowlatency` and `adaptive-bw` (250 kbps start).
* Rate-adaptation logics are Predictive, NearOptimal (BOLA), RateBased, AlwaysBest, AlwaysLowest and RoundRobin.

## 5. Playlists: `modules/demux/playlist/m3u.c`

`#EXTINF:<dur> attrs,<title>` handles `tvg-logo`, `tvg-name`, `tvg-language`, `tvg-id` and `group-title`, and the first
value wins. `#EXTGRP:` is the group fallback. `#EXTVLCOPT:key=value` applies to the next item only. In the October 2026
iptv-org snapshot, `http-user-agent` appears 610 times and `http-referrer` 244 times.

## 6. Subtitles, audio and fullscreen

* Subtitles: subsdec (SRT/SSA), webvtt, CEA-608/708 and DVB/teletext, styled with `--freetype-*`.
* Audio: track switching, delay, time-stretch, replay-gain, equalizer and passthrough.
* Fullscreen `VideoPlayerActivity`: gestures, aspect/zoom cycling, PiP, lock and background audio.

## 7. How Show maps it

| VLC concept | Show implementation |
|---|---|
| libVLC (LGPL) | **AndroidX Media3 ExoPlayer 1.5.1** (Apache-2.0): HLS, DASH, Smooth, RTSP, RTMP, progressive |
| access/http and `:http-user-agent`/`:http-referrer` | `OkHttpDataSource` with per-channel User-Agent and **Referer only**, sanitised against CR/LF |
| http access follows redirects | dedicated media OkHttp client that follows http→https upgrades |
| adaptive BufferingLogic (6 s / 15 s live) | `DefaultLoadControl`: 15 s min, 50 s max, 1.5 s to start, 3 s after rebuffer |
| http segment retries | `LoadErrorHandlingPolicy`: 5 tries, capped backoff, no retry on 4xx |
| input restart | `PlaybackController`: live-edge resync, HLS re-probe, 1/2/4 s backoff, retry when the network returns, 20 s stall watchdog |
| WAKE_LOCK held while streaming | `WAKE_MODE_NETWORK` plus the `WAKE_LOCK` permission (missing in 1.0, which caused the Play crash) |
| m3u.c | `M3uParser`: streaming, quote-aware, EXTVLCOPT, first value wins, categories merged |
| track/SPU selection | Media3 controls (subtitles, audio, speed) plus a Show quality picker |
| VideoPlayerActivity | `PlayerActivity`: immersive, fit/zoom/stretch, PiP, zapping, sleep timer |
| MMS / SRT / UDP | not in Media3, so the player offers "Open externally" |

## 8. Licensing

VLC is GPL-2.0+/LGPL-2.1+. Show copies no VLC code and links no libVLC. "VLC" and the cone are
VideoLAN trademarks and are not used. A libVLC fallback (LGPL, `org.videolan.android:libvlc-all`)
would be permitted with dynamic linking, licence text and a source offer, but it would add about 100 MB per universal APK.
