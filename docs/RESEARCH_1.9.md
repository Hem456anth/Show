# Research 1.9: three streaming repos reviewed

Reviewed 2026-10-20 (shallow clones of each default branch). All three ship as source, so the source was read directly.
REA (morluto/rea) decompiles binaries (APK/DEX), which wasn't needed here. Nothing from any of the three was copied into Show.

## Verdict

| Repo | What it is | Licence | Streams from | Integrate? |
|---|---|---|---|---|
| Anshu78780/json | Config feed for the "ScreenScape" app: `providers.json` (40+ piracy sites such as moviesmod, uhdmovies, hdhub4u, world4ufree, moviesdrive), `servers.json`, plus `cookie.json` / `webcookie.json` / `autocook.json` holding **live session cookies** for third-party accounts | None (all rights reserved) | Unlicensed piracy sites | **No.** Unlicensed content, and it publishes other people's session tokens (credential leak). Nothing usable. |
| Spyou/Zangetsu | Flutter app (Android/TV/iOS) that hosts CloudStream `.cs3`, Aniyomi and Mihon extensions, JS providers (`netmirror.js`, `allanime.js`) and embed extractors (doodstream, mp4upload, streamlare, okru), torrents, Cloudflare bypass (`CloudflareKiller`, `CfClearance`) | GPL-3.0 (combined work, because it bundles CloudStream) | Whatever the installed extensions scrape | **Sources and extractors: no.** Player ideas only, re-implemented clean-room (see below). Copying code would force Show to become GPL-3.0. |
| truelockmc/streambert | Electron desktop app; plays third-party embed players (vidking.net, player.videasy.to, vsembed.su), allmanga.to for anime, with an ad-blocker in front | GPL-3.0 | Unlicensed embed aggregators | **No** for sources. Its TMDB, AniSkip and Wyzie subtitle use don't add anything Show lacks or can use lawfully today. |

## Security notes
* **json**: anyone using this feed sends requests with another person's account cookies. Treat as hostile.
* **Zangetsu**: loads and runs downloaded third-party code (DEX via class loaders for CloudStream/Aniyomi/Mihon, JS providers in an embedded engine). Remote code execution by design; every extension repo is a supply-chain risk. To its credit: cleartext only on loopback, extra CA roots added (not trust-all), and its own tests.
* **streambert**: Electron windows use `contextIsolation: true` and `nodeIntegration: false` (good), but the app's job is loading ad-heavy pirate embed pages, which is why it needs an ad-blocker.
* None of this belongs in Show: Show only plays Internet Archive items with a PD/CC licence and the viewer's own Jellyfin server.

## Reliability
The scraped sources rotate domains constantly (`providers.json` changed today; "source not working" is its own issue template in Zangetsu). That's the opposite of what Show needs.

## What Show took (ideas only, written from scratch)

| Idea (where seen) | Show 1.9.0 implementation |
|---|---|
| User-selectable buffer presets with a back buffer, values clamped so a bad setting can't crash Media3 (Zangetsu `BufferPresets`) | `vod/VodPlayback.kt` `BufferPreset` Data saver 10–30 s / Balanced 15–60 s + 15 s back / Smooth 30–120 s + 30 s back; Movies settings → Buffering |
| Source health memory: failures remembered with a time window, failing sources ranked last (Zangetsu `source_health_store`) | `RenditionHealth`: a rendition URL that fails twice in 14 days is tried after healthy ones; one success clears it; keys are hashes; capped at 300 |
| Retry the decoder once before failing (Zangetsu `playback_failover`) | `DefaultRenderersFactory.setEnableDecoderFallback(true)`: Media3 moves to the next (often software) decoder if a hardware one fails to start |
| Retry the network before switching source | `VodRetry`: connection drops, timeouts, HTTP 5xx and 429 retry the **same** rendition after 1 s, 2 s, 4 s; 4xx, codec and container errors skip straight to the next rendition |
| Errors that say what went wrong | `VodErrors`: names the HTTP status (404 "gone", 403 "refused", 5xx "server trouble"), the decoder or a damaged file |

Behaviour kept: rendition fallback order, resume, Jellyfin reporting, Trakt scrobbling, stop-when-off-screen (a pending retry is cancelled in `onStop`). Live TV is untouched.

## Tests
`Release190Test`: all presets build valid LoadControls and persist; only transient errors retry, with 1/2/4 s backoff then give up;
health ranking, all-suspect fallback, success reset, 14-day expiry; error wording. CI also installs and launches on an API 34 emulator.
Real-device playback, buffering and error recovery still need a check on the phone.
