# Show 1.5.3: stability, performance and battery audit

Date: 17 Oct 2026. Baseline: 1.5.2 (versionCode 8). Candidate: 1.5.3 (versionCode 9).

## Method
1. **Static review** of every animation, coroutine, Handler, listener, wake lock, network call, permission and lifecycle path
   (`MainActivity`, `PlayerActivity`, `PlaybackController`, `StreamEngine`, `MainViewModel`, `PlaylistRepository`, every composable).
2. **Unit and Robolectric tests**: 104 tests in 19 suites, 0 failures, 1 skipped (the opt-in live probe). They include the new
   `Release153Test` (real Media3 pipeline against a local server).
3. **Device audit on emulators** (`.github/workflows/audit.yml` running `tools/device_audit.sh`) on Pixel 6 images for API 26
   (Android 8), 29 (10), 31 (12), 33 (13) and 35 (15). Each image ran both 1.5.2 and 1.5.3, plus a LeakCanary build on API 34.
   The audit covers cold start, frames drawn while Home sits idle, scroll jank, CPU ticks (idle, player in the background with
   the screen off, app away), wake locks, audio focus, services, jobs and alarms, memory across three player open/close cycles,
   crashes and ANRs, and screenshots of every tab plus the player.
   Results: `docs/audit/latest/SUMMARY.md` (run 39048187975) and `docs/audit/run-39042031996/` (first run).

Caveats: emulators use SwiftShader software rendering, so jank % reads about 100% for both builds and isn't a real device figure.
Compare frames drawn and CPU instead. The API 35 image had no DNS in CI, so its rows only cover the offline path. The audit
can't measure battery in mAh. CPU ticks and wake locks stand in for it.

## Issues found and fixed

| # | Symptom | Root cause | Fix | Verified by |
|---|---|---|---|---|
| 1 | Lag, heat and battery drain on Home | Hero Ken Burns was an **infinite** 16 s animation across 3 pager pages, each a ~1.6x-zoomed layer under a **40 dp RenderEffect blur**, so the GPU re-blurred full-screen artwork every frame while Home was open | Zoom is now a 1.2 s settle per page. The blur is done once per image on decode (`BackdropBlur`, cached by Coil), which also covers Android 8–11. Auto-advance pauses unless Home is RESUMED. No motion in Battery Saver or with reduced motion | Idle frames in 20 s: **171–270 → 45–62** (API 29–33), 246 → 122 on API 26. Idle CPU ticks: **1356–2072 → 87–392** |
| 2 | Scroll stutter in rows and grids | `SubcomposeAsyncImage` ran a subcomposition per tile, and every logo was decoded at a fixed 480x270 | `AsyncImage` decoded at the tile's measured size, with monogram fallback via `onError` | Code review. Emulator jank isn't meaningful |
| 3 | Battery and data drain after leaving the player | Pausing in `onStop` didn't cancel the pending retry, the stall timer, backup failover or the network callback. Each of these called `prepare()` + `play()`, so a failing stream reconnected and **audio could restart in the background**, holding the network wake lock and Wi-Fi lock | `PlaybackController.onBackground()` stops the player and cancels all retries. `prepare()` never plays while off-screen. `onForeground()` reconnects at the live edge, but not after a final error or the sleep timer | Release153Test (4 tests). Player in background, screen off, 60 s CPU ticks: **4–98 → 0–7**. Audio focus abandoned within 2 s of screen-off |
| 4 | Screen never turns off | `FLAG_KEEP_SCREEN_ON` (and PlayerView `keepScreenOn`) were set for the activity's whole life, including the error screen and the sleep timer's "Paused" card | Kept on only while `playWhenReady` and BUFFERING/READY | Code review + listener |
| 5 | Sleep timer still used data | A paused live player keeps polling the HLS playlist | The timer now calls `stop()`. "Keep watching" reloads at the live edge | Code review |
| 6 | Plays over calls and other apps | No audio focus was requested | `setAudioAttributes(USAGE_MEDIA, MOVIE, handleAudioFocus = true)` | Audit `audio-focus-playing.txt` shows `requestAudioFocus` from app.show.tv |
| 7 | Player lingered after closing PiP | Closing PiP with the X only paused the player | It now finishes the activity, which releases the decoder, locks and connection | Code review |
| 8 | Blue or teal dots and tints | `android:Theme.Material` accent (teal, replaced by system blue on Samsung, MIUI and ColorOS) tinted framework-drawn parts: PlayerView controls and their focus and press states, edge glows, scrollbars and popup checks | Theme pins `colorAccent`, `colorControl*`, `colorEdgeEffect` and `textColorHighlight` to the monochrome palette. The PlayerView time bar colours are explicit | Blue-pixel scan of every screenshot: the remaining blue is channel logos, the keyboard, video frames, and Android's one-time "Viewing full screen" card (system UI, shown until "Got it") |
| 9 | Hero jumped 2–3 times after launch | The featured channels were re-picked on every load pass (cache, network, indexes, Free-TV) | Featured channels are kept while all of them still exist | Code review |
| 10 | Network on every launch | The countries and languages indexes and Free-TV were re-requested every launch (3 extra requests and parses) | `refreshIfOlderThan(6 h)` for the metadata-only sources | PlaylistRepositoryTest |
| 11 | Raw error text when offline | `PlaylistException` wrapped `UnknownHostException`, so `friendly()` showed 'Unable to resolve host "iptv-org.github.io"' | The message is mapped through the cause chain (offline, timeout, TLS) | API 35 screenshot + Release153Test |
| 12 | Blocky hero backdrop (found during this audit, never shipped) | The first 1.5.3 attempt drew a 48x27 decode full-width, which looked pixelated on API 35 | Replaced by the cached box blur (#1) | Run 2 screenshots |

## Checked, no defect
- **Background services, jobs, alarms:** none (the `dumpsys` outputs are empty). There's no foreground service and no WorkManager.
- **Wake locks:** none held by the app in the background on any API level. ExoPlayer's network lock is held only during playback.
- **Memory leaks:** LeakCanary watched 4 destroyed `PlayerActivity` instances and reported **no retained objects**. Network
  callback, battery-saver receiver and player listeners are all unregistered. PSS stays within about 70–200 MB with no steady
  growth across cycles.
- **Crashes:** 0 on every API level and both builds. **ANRs:** 0 in Show. The only ANR logged was the emulator launcher.
- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, plus AndroidX's own non-exported receiver permission.
  There are no dangerous permissions. Only the launcher activity is exported, `allowBackup=false`, the playlist client refuses
  https→http redirects, and `isAcceptableUrl` only allows network schemes.
- **Cold start:** about 1.4–3.9 s on emulators, the same range as 1.5.2.
- **Lifecycle:** process-death restore of the player via Intent extras, singleTop re-targeting, PiP enter and exit, and
  rotation (configChanges) are covered by the existing PlayerLaunchRegressionTest and NavigationUiTest.

## Not verifiable here
Physical-device battery (mAh), GPU counters and OEM skins need a real phone. Run `tools/device_audit.sh <apk> out/` against a
USB-connected device (for example the iQOO Z9X) for the same metrics.
