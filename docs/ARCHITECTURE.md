# Show: architecture

```
app/src/main/java/app/show/tv
├── ShowApp.kt          AppGraph: playlist + media OkHttp clients, Coil (lazy), repos, StreamEngine, settings
├── MainActivity.kt     Compose root, NavHost, bottom bar (<600dp) / rail (≥600dp), detail and settings sheets
├── data/
│   ├── Channel.kt          immutable model and Playlist
│   ├── M3uParser.kt        streaming Extended-M3U parser (VLC m3u.c semantics + iptv-org tags)
│   ├── StreamProtocol.kt   URL → transport, in-app vs external
│   ├── PlaylistRepository  HTTPS-only, ETag, 32 MB cap, atomic per-source cache, offline fallback
│   ├── ChannelCatalog.kt   indexes: categories, countries, rows, featured, ranked search, browse filters
│   ├── LibraryStore.kt     My List, watch history, dead-link memory (ids only)
│   └── SettingsStore.kt    playlist source, hide geo-blocked, working-only
├── player/
│   ├── StreamEngine.kt     ExoPlayer, LoadControl, per-channel sanitised headers, retry policy
│   ├── PlaybackController  recovery state machine, sleep timer, quality override, health callbacks
│   ├── PlaybackIssue.kt    error → viewer message + retry decision; RetryPolicy
│   ├── PlaybackQueue.kt    in-memory zapping queue (Binder-safe) with Intent fallback
│   └── PlayerActivity.kt   fullscreen, PiP, singleTop re-targeting, connectivity callback, menus
└── ui/
    ├── MainViewModel.kt    catalog/search/settings/health flows; cancellable loads
    ├── theme/Theme.kt      ShowColors monochrome palette (black and white on ink) and type scale
    ├── components/         Buttons (ShowPrimaryButton / ShowSecondaryButton), Hero, ChannelCard/Row/Artwork, DetailSheet, SettingsSheet
    └── screens/Screens.kt  Home (pull-to-refresh), Live TV (country + working-only), Search, My List
```

## Data flow

```
playlist URL (Settings) ─▶ PlaylistRepository ─(cache per source)─▶ M3uParser ─▶ raw channels
                                                                       │ hide geo? ─▶ ChannelCatalog
LibraryStore (favourites, history, dead links) ─────────▶ MainViewModel ─▶ Compose screens
                                                                       │ play(channel, queue)
PlaybackQueue ─▶ PlayerActivity ─▶ PlaybackController ─▶ StreamEngine / ExoPlayer
        ▲                                   │ READY → markAvailable · dead link → markUnavailable
        └──── onNewIntent (singleTop) ──────┘
```

* **Cold start:** the cached playlist renders at once, then a conditional GET refreshes it. A new load
  cancels any load in flight, so stale results never overwrite fresh ones.
* **Favourites and history** resolve against the *unfiltered* list, so hiding geo-blocked channels never loses saved items.
* **Dead links:** a 404, 403/451, format failure or persistent 5xx marks the channel id with a timestamp (cap 3,000, 24 h window).
  Cards dim and show OFFLINE. "Working only" hides those channels. A later successful start clears the mark.

## Playlist sources (1.3.0)
`PlaylistSource` lists the official iptv-org playlists and what their `group-title` means (category, country or language).
Load is two-pass. First the selected source is fetched or read from cache, parsed with its grouping, de-duplicated
(`CatalogEnricher.dedupe`) and published. Then the other two indexes (`enrichFrom`) are refreshed in parallel and joined by
`M3uParser.urlKey`, and the catalog is republished with categories, countries and languages for every channel. A failed index
leaves that dimension as the source had it. `ChannelCatalog` indexes all three dimensions for the Live TV filters and Home rows.

## Playback recovery

| Signal | Action |
|---|---|
| `BEHIND_LIVE_WINDOW` | seek to live edge + prepare |
| container sniff failure, extension-less URL | retry once as HLS |
| network / timeout / 5xx / 408 / 429 / 20 s stall | backoff 1, 2, 4 s ("attempt n of 3"), immediate retry on network return |
| 401/403/451 | "Not available in your region" + Next channel; marked unavailable |
| 404/410, format | "Channel is offline" + Try again / Next; marked unavailable |
| MMS / SRT / UDP | never handed to the engine; "Open externally" |

Backup links: when a dead-link error (or exhausted retries) hits a channel with `alternates`, the controller moves to the
next link before reporting the channel as unavailable. Zapping skips channels that failed in the last 24 h.

## Lifecycle

* `WAKE_LOCK` is declared because `WAKE_MODE_NETWORK` acquires wake and Wi-Fi locks. Without it, playback crashed (see CHANGELOG 1.1.0).
* `onStop` pauses unless the activity is really in PiP (`isInPictureInPictureMode`). Dismissing the PiP window pauses as well.
* `onStart` resumes only if there's no error and the sleep timer didn't pause playback.
* `singleTop` + `onNewIntent`: choosing a channel while the player is open (for example from PiP) switches it.

## Security

* Playlist: HTTPS enforced at input (SettingsStore) and in the repository, including after redirects. `iptv-org.github.io` is cleartext-off.
  No cross-scheme redirects on the playlist client, a 32 MB cap, the file must parse to channels, and URLs with userinfo are rejected.
* Media: a separate client follows http→https upgrades. Cleartext is allowed for media only (about 20% of channels). Only system CAs are trusted.
* Headers from playlists are stripped of CR/LF/NUL and capped at 1 KB. Logos must be http(s). Stream URLs with whitespace are dropped.
* `allowBackup=false` and backup/transfer exclude everything. No accounts or analytics. Only hashed ids are stored. The player activity is not exported.

## Performance

* Line-by-line parsing (the 2.7 MB file is never held as one string). Indexes and normalised search keys are built once off the main thread.
* Coil is created lazily, with a 15% memory cache, a 64 MB disk cache and logos at ≤480×270. Lazy lists use stable keys and `contentType`.
* ExoPlayer: a 48 MB target buffer with time priority. The player is released on destroy and the quality cap resets per channel.

## Responsive layout

| Width | Navigation | Card | Grid min |
|---|---|---|---|
| < 600 dp | bottom bar | 156 dp | 150 dp |
| 600–839 dp | rail | 196 dp | 180 dp |
| ≥ 840 dp | rail | 220 dp | 210 dp |

## Visual system (1.4.0, Apple TV-inspired)
True black (`ShowColors.Ink`), neutral greys, `Glass` and `Hairline` for floating controls. `components/Motion.kt` holds `Modifier.lift`
(focus/press spring) and `rememberReduceMotion()`. The hero is a top-shelf pager with Ken Burns and graphicsLayer parallax. Shelves use
`ChannelRow(onSeeAll, onLongPress)`, and `ChannelActions.seeAll(LiveFilter)` routes to a pre-filtered Live TV.

## Visual system (1.2.0)

* **Logomark:** `res/drawable/ic_show_mark.xml` is a film clapperboard with an open striped arm and a play wedge cut into the slate (black glyph, the user's choice #1). It's used for the
  launcher (black on white, adaptive and themed-monochrome) and in-app as the brand tile (white rounded square + "SHOW").
* **Colour:** black, white and greys only. `ShowColors.Accent` = white and `OnAccent` = black. Channel tiles use seeded graphite
  gradients. The only hue is `Danger`, kept for error text.
* **Buttons:** every button is one of two composables in `ui/components/Buttons.kt`:

| Style | Fill | Label | Border | Use |
|---|---|---|---|---|
| `ShowPrimaryButton` | white | black | none | main action: Play, Try again, Load playlist, Keep watching, Open externally |
| `ShowSecondaryButton` | black | white | 1.5 dp white | other action: My List, Details, Next channel, Use iptv-org |

  Both use a 10 dp corner, 20×10 dp padding and a minimum height of 48 dp. Contrast is 21:1 in each direction. Selected chips, nav indicators,
  switches and progress use white with black content. Text buttons are white.
