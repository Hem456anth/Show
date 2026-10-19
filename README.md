# Show

[![Android CI](https://github.com/Hem456anth/Show/actions/workflows/android.yml/badge.svg)](https://github.com/Hem456anth/Show/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/Hem456anth/Show)](https://github.com/Hem456anth/Show/releases/latest)

> Version 1.5.4: stricter Free-TV verification refreshed weekly, plus verified YouTube and Dailymotion live channels in their official players (see `docs/SOURCES_1.5.4.md`). 1.5.3: battery and lag audit. 1.5.2 fixed the grey overlay on the Home hero. 1.5.0: Zee channels fixed at the root (saved channels follow rotated links), an audio-track picker, smoother 1080p, verified Free-TV channels and a liquid-glass tab bar. See `CHANGELOG.md` and `docs/STREAMING_1.5.md`.

## Download

Grab `Show-<version>.apk` from **[Releases](https://github.com/Hem456anth/Show/releases/latest)** and open it on your
phone, tablet or Android TV (Android 8.0+, API 26). Allow "Install unknown apps" for your browser or file manager when
prompted. Each release also ships `SHA256SUMS.txt`, so you can check `sha256sum -c SHA256SUMS.txt`.

> If an earlier Show build signed with a different key is installed, uninstall it first (Android refuses to update an app across signing keys).

An original Android live-TV app for the free channels indexed by the
[iptv-org](https://github.com/iptv-org/iptv) community. It has a dark, cinematic interface
and a streaming core modelled on what VLC does (see `docs/VLC_ANALYSIS.md`), built on AndroidX Media3.

* **Sources:** India, Global, By country, By language or By category (iptv-org), switchable from Home chips or Settings, each enriched with the other indexes so every channel has categories, countries and languages
* **Verified Free-TV channels:** free-to-air links from [Free-TV/IPTV](https://github.com/Free-TV/IPTV), used only when they passed `tools/verify_extra_sources.py`; duplicates become backup links
* **Home:** rotating hero banner with a liquid-glass Play button, My List and Details, plus *Up Next*, *My List* and 14 carousels grouped the way the source is (genre, country or language)
* **Live TV:** category chips, country and language dropdowns and a "Working only" chip over an adaptive poster grid
* **Search:** ranked, accent-insensitive search by name, country, language or genre, with a category filter
* **My List:** saved channels and watch history (clearable)
* **Settings** (gear on Home): your own https M3U playlist, hide geo-blocked channels, working channels only, privacy and about
* **Dead-link memory:** channels that failed in the last 24 h are dimmed, badged OFFLINE and skipped when zapping. Backup links are tried before a channel counts as dead
* **Player:** fullscreen immersive playback with live seek, an **Audio** picker for multilingual streams, subtitle and speed pickers, fit/zoom/stretch,
  channel zapping, picture-in-picture, a quality picker with automatic step-down after repeated stalls, a sleep timer, automatic recovery and clear error messages
* **Android TV:** leanback launcher, D-pad focus lift, and Channel +/- and Last-channel keys in the player
* **Phones and tablets:** floating liquid-glass tab bar under 600 dp, navigation rail above, with scaled cards and hero

## Setup and build

Requirements: JDK 17+ (21 tested), Android SDK Platform 35 and Build-Tools 35.0.0. No API keys or accounts are needed.

```bash
git clone https://github.com/Hem456anth/Show.git && cd Show
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew :app:testDebugUnitTest      # JVM + Robolectric test suite
./gradlew :app:assembleRelease        # R8-minified APK → app/build/outputs/apk/release/
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or open the folder in Android Studio (Ladybug or newer) and press Run.

### Signing

Without configuration the release APK is signed with the **debug key**, so it installs straight away. To sign with your
own key, export these before building (never commit the keystore):

```bash
export SHOW_KEYSTORE_FILE=/abs/path/show-release.jks
export SHOW_KEYSTORE_PASSWORD=...  SHOW_KEY_ALIAS=show  SHOW_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

## Continuous integration and releases

`.github/workflows/android.yml` runs on every push and pull request:

1. **build:** JDK 17, the full unit/Robolectric suite, then the R8-minified release APK, `apksigner verify` and SHA-256 sums (uploaded as artifacts).
2. **install-smoke:** boots an API 34 emulator, `adb install`s the APK, launches `MainActivity`, waits 25 s, checks the process is
   alive and the crash buffer is clean, and saves a screenshot artifact.
3. **release** (tags `v*` only): publishes a GitHub Release with the APK, `SHA256SUMS.txt` and the CHANGELOG section as notes.

To cut a release: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a CHANGELOG entry, then
`git tag v1.4.1 && git push origin v1.4.1`. For release signing in CI add the repository secrets `SHOW_KEYSTORE_BASE64`
(`base64 -w0 show-release.jks`), `SHOW_KEYSTORE_PASSWORD`, `SHOW_KEY_ALIAS` and `SHOW_KEY_PASSWORD`.

**ARM64 Linux hosts** (Google ships x86-64 `aapt2` only): install `qemu-user` + amd64 libc and point
AGP at a wrapper named `aapt2` that runs build-tools' aapt2 under `qemu-x86_64`. Put
`android.aapt2FromMavenOverride=/abs/path/aapt2` in `~/.gradle/gradle.properties`. This is how the
delivered APK was built.

## Tests

`./gradlew :app:testDebugUnitTest` runs:

| Suite | Covers |
|---|---|
| `M3uParserTest` | quote-aware EXTINF, EXTVLCOPT scoping, flags/resolution, category merge, BOM/CRLF/junk, unsafe logos, **the full real iptv-org snapshot** (>10k channels, < 5 s) |
| `CatalogAndProtocolTest` | protocol detection, category ordering, search ranking/accents/filters, featured picks, real-catalog search speed, retry backoff, error mapping, zapping queue window |
| `PlaylistRepositoryTest` | HTTPS MockWebServer: download+cache, ETag 304, offline fallback, cleartext refusal, size cap, captive-portal rejection |
| `PlaybackControllerTest` | real Media3 pipeline vs a local server: UA/Referer/Origin headers, 404/403 mapping, 5xx backoff then manual retry, unsupported protocols, zapping, live-window resync, history |
| `LibraryAndViewModelTest` | favourites/history persistence, dedupe/cap, corrupt data, ViewModel offline load and search |
| `Release15Test` (`RotatedLinkTest`, `ExtraSourcesTest`, `AudioTracksTest`) | saved channels following rotated links, Free-TV allowlist and merge, audio labels and listing, adaptive step-down, live speed |
| `NavigationUiTest` | Compose UI: bottom nav across all tabs, category chips, search results, detail sheet → My List → Player launch, tablet navigation rail |

Live stream reachability probe (no decoding): `python3 tools/probe_streams.py --sample 80`.
In-app live diagnostic (real parser, engine and Media3, needs network):
`SHOW_LIVE_PLAYLIST=/path/index.m3u SHOW_LIVE_FILTER='^zee' ./gradlew :app:testDebugUnitTest --tests '*LiveStreamDiagnostic'`.
Re-verify Free-TV before a release: `python3 tools/verify_extra_sources.py && YT_API_KEY=... python3 tools/verify_embeds.py`. `.github/workflows/verify-free-tv.yml` does this every Monday and installed apps pick up the result within a day.

## Project layout and design

See `docs/ARCHITECTURE.md` (modules, data flow, recovery state machine, security, memory) and
`docs/VLC_ANALYSIS.md` (REA evidence and the VLC-to-Show mapping).

## Legal

See `NOTICE.md`. Show hosts and redistributes no video. It reads a public index of links that
broadcasters make freely available. Some streams are geo-restricted or intermittent, and availability is up to each broadcaster.


## Movies & Series (1.6.0)
A separate Movies tab plays free public-domain / Creative Commons films and TV from the Internet Archive and your own
Jellyfin library, with TMDB (or TVmaze) for discovery, cast and trailers. Setup: open Movies → ⚙ and add a free TMDB key
and/or your Jellyfin server. To bake a TMDB key into a build: `./gradlew assembleRelease -PTMDB_API_KEY=<key>` (CI: repository
secret `TMDB_API_KEY`). Details and source decisions: `docs/MOVIES_1.6.md`.
