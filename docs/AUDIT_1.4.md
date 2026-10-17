# Show 1.4.0: audit, research and redesign notes (2026-10-16)

## 1. Architecture snapshot
- **Single-module Compose app** (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, minSdk 26 / target 35).
- **data/**: `M3uParser` (streaming, VLC-compatible), `PlaylistSource` (official iptv-org indexes), `PlaylistRepository`
  (HTTPS-only, conditional GET, atomic cache, size cap), `CatalogEnricher` (cross-index join and dedupe), `ChannelCatalog`
  (pre-built indexes for category, country, language and search), `LibraryStore` (My List, history, dead links, snapshots), `SettingsStore`.
- **player/**: `StreamEngine` (Media3 ExoPlayer, OkHttp, per-channel headers), `PlaybackController` (recovery state machine,
  backup links, zapping, recall), `PlaybackQueue` (Binder-safe hand-off), `PlayerActivity` (immersive, PiP, TV keys).
- **ui/**: `MainViewModel` (two-pass load), screens (Home, Live TV, Search, My List), components (Hero, cards, rows,
  detail sheet, settings sheet, buttons, motion).
- **Dependencies:** AndroidX Compose and Material 3, Navigation Compose 2.8.5, Media3 1.5.1 (HLS, DASH, Smooth, RTSP, RTMP, OkHttp),
  OkHttp 4.12, Coil 2.7. There's no analytics, ads or account SDK.

## 2. Competitor scan (what Show borrows, and why)
| App | Strength worth learning from | Show 1.4.0 response |
|---|---|---|
| Apple TV app / tvOS | Top-shelf full-bleed artwork, focus "lift" with parallax, Up Next row, large titles, frosted bars, quiet black canvas | Top-shelf hero with Ken Burns and parallax, focus/press lift on every card, Up Next row, 34 sp large titles, frosted tab bar and brand bar |
| TiviMate | TV-remote first: channel up/down, last-channel recall, fast zapping | Channel +/- and media keys zap, LAST_CHANNEL recalls, zapping skips dead links |
| IPTV Smarters / Televizo | Long-press favourites, per-source playlists | Long-press any card to add to or remove from My List (with haptics). Five official sources |
| Pluto TV / Samsung TV Plus | Channel guide (EPG) | Still on the roadmap (iptv-org EPG needs a guide mirror) |
| VLC | Protocol breadth, robust demux | Already mirrored in 1.0 (see VLC_ANALYSIS.md). External hand-off for MMS/SRT/UDP |

## 3. Findings and fixes in 1.4.0
| # | Type | Finding | Fix |
|---|---|---|---|
| 1 | Security | `isAcceptableUrl` accepted any scheme, so a custom M3U could point the player or "Open externally" at `file://`, `content://` or `intent://` | Allowlist of network streaming schemes only. Control characters are rejected. Test added |
| 2 | Bug | Detail "More in Undefined" for channels from the language/country indexes before enrichment | `ChannelCatalog.related()` falls back to language, then country, and puts same-language channels first. `relatedTitle()` |
| 3 | Bug | Live TV reset stale filters by writing state during composition, which could re-compose in a loop on some devices | Moved into `LaunchedEffect(state.catalog)`. Stale categories are reset too |
| 4 | Bug | Search category from a previous source stayed selected and matched nothing | View model drops it on publish if the new catalog lacks it |
| 5 | UX | Home shelf titles weren't actionable | "Title ›" header opens Live TV pre-filtered (category, country or language, depending on source) |
| 6 | Accessibility | Focus wasn't visible for D-pad or keyboard users, and section titles weren't headings | Focus lift with a white ring and shadow. `heading()` semantics on screen and shelf titles. Cards merge their semantics and announce "in My List" and "recently unavailable". Long-press has an accessibility label |
| 7 | Accessibility | Secondary grey was #B9B9C9 on #0B0B10 | Neutral #A1A1A6 on true black (7.9:1, AAA). Reduce-motion (animator scale 0) disables Ken Burns, auto-advance and lift |
| 8 | Platform | No Android TV support | `leanback`/`touchscreen` set to not required, LEANBACK_LAUNCHER, 320×180 banner, remote key handling in the player |
| 9 | Perf | Hero recomposed its whole page on scroll | Scroll parallax runs in `graphicsLayer` lambdas (draw phase only) with no recomposition |

Checked and left as is: no exported component besides the launcher, `allowBackup=false` with extraction rules excluding everything,
user CAs not trusted, HTTPS-only playlist hosts, header CR/LF sanitising, Binder-safe queue, network callback unregistered in `onDestroy`.

## 4. Authentication
Show has **no accounts or sign-in** by design: the iptv-org streams are public. There's nothing to authenticate, so no
credentials are stored. Privacy stays as before (local-only My List and history).

## 5. Still not verifiable here
Real-device rendering, hardware decoding and live playback (no emulator in the build sandbox). `tools/verify_sources.py`
checks feed reachability live. Robolectric covers the UI tree and the Media3 pipeline against a local server.
