# Movies & Series (1.6.0): audit, source research and design

## 1. Architecture audit (before the change)

Show 1.5.4 is a single-module Android app (`app.show.tv`, Kotlin 2.0.21, Compose, Media3 1.5.1, minSdk 26).

| Layer | Files | Role |
|---|---|---|
| App graph | `ShowApp.kt` (`AppGraph`) | Hand-rolled DI: settings, OkHttp clients (playlist client never downgrades https; media client allows http→https), repositories, `LibraryStore`, `StreamEngine`, Coil loader |
| Live data | `data/M3uParser.kt`, `PlaylistRepository.kt`, `PlaylistSource.kt`, `CatalogEnricher.kt`, `ExtraSources.kt`, `AllowlistStore.kt`, `Embeds.kt`, `Channel.kt` | M3U (iptv-org indexes + verified Free-TV + YouTube/Dailymotion embeds), enrichment, dedupe/alternates, cache |
| Library | `data/LibraryStore.kt` | Local My List, Up Next history, channel snapshots, identity reconcile |
| Playback | `player/StreamEngine.kt`, `PlaybackController.kt`, `PlayerActivity.kt`, `EmbedPlayerActivity.kt` | Live-tuned ExoPlayer (live edge, rebuffer policy, auto step-down), backup failover, PiP, WebView official embeds |
| UI | `MainActivity.kt` (tabs Home / Live TV / Search / My List, rail ≥600 dp, floating glass tab bar), `ui/screens/Screens.kt`, `ui/components/*` | Apple TV-inspired black canvas, hero, rows, detail sheet, settings sheet |

Verified absent (searched the whole tree): XMLTV EPG, Firebase, a Schedule or "My Channels" tab, a schedule player and
configurable scraper addresses. The request's Live TV list described another app; per the user (2026-10-20) those are
**not** built here. Everything Live TV *does* have is untouched: no file under `data/`, `player/` or `ui/` changed except
`MainActivity.kt` (one new tab) and the version/manifest.

## 2. REA

REA (github.com/morluto/rea) is a binary/APK reverse-engineering harness; Show used it in 1.0 to study VLC's playback
pipeline (docs/VLC_ANALYSIS.md). For Movies & Series it wasn't needed for, and must not be used on, commercial streaming
apps: extracting their stream endpoints would break their terms and usually DRM law. Every provider chosen below publishes
a documented API and (Jellyfin) open-source clients, so integration followed the official docs and live API probes
instead. REA stays the tool for studying open-source players.

## 3. Sources: metadata vs playable video

| Source | Kind | Licence / terms | Auth | Verified (2026-10-20) | Used for |
|---|---|---|---|---|---|
| **Internet Archive** (`advancedsearch.php`, `/metadata/<id>`, `/download/…`, `/services/img/…`) | Metadata **and full playback** | Items declare `licenseurl`; Show lists and plays only public-domain marks/dedications and Creative Commons licences | None | `collection:feature_films` + licence filter: 7,669 films; `classic_tv` + licence filter: ~2,500 items; MP4 renditions served with HTTP range | Free classic movies and TV, episodes from `sNNeNN` file names, quality from derivative heights |
| **Jellyfin** (open source, GPL-2.0) | Metadata **and full playback** of the viewer's own server | Viewer's own media | `POST /Users/AuthenticateByName` → token (stored; password never stored) | Public demo `demo.jellyfin.org/stable` (12.2.0): auth, items, seasons/episodes, people, studios, remote trailers, `UserData` resume, `/Videos/{id}/stream?static=true` → 206 video/mp4. Transcoding is disabled on the demo (HLS returns HTML), so the player falls back to Original | Your library, Continue on Jellyfin, server-synced progress |
| **TMDB** (api.themoviedb.org/3) | **Metadata only** | Free non-commercial API; attribution required (shown on the Movies screen) | v3 key or v4 read token | Unauthenticated call → 401 as documented; parsing tested on recorded response shapes. **No key was available to verify live** | Trending, now playing, popular, top rated, on the air; details, cast/crew, trailers, recommendations, seasons/episodes, companies, networks, person filmographies |
| **TVmaze** | **Metadata only** (TV) | CC BY-SA 4.0, attribution shown | None | `/schedule/web`, `/search/shows`, `/shows/{id}?embed[]=cast…` live | Series info when no TMDB key |
| **YouTube** (official embed) | Trailers | YouTube embedded-player terms | None | Reuses 1.5.4's hardened `EmbedPlayerActivity` | TMDB/Jellyfin trailers |

A TMDB title becomes playable only when the viewer's Jellyfin has the same TMDB id ("Play on Jellyfin").

### Rejected
* **Scraper/"free movie" sites and addon indexes** (e.g. sites that wrap unlicensed hosts, torrent/debrid add-ons): unlicensed content.
* **Extracting streams from Netflix, Prime Video, JioCinema/Hotstar, Tubi, Pluto, Plex FAST, etc.**: no public playback API; DRM; ToS violation.
* **Archive `classic_tv` without the licence filter**: it holds copyrighted box sets (e.g. later sitcoms, documentaries).
* **Plex**: playback needs a Plex account/token flow and its API is not openly documented for third-party clients; deferred.
* **Emby**: same API shape as Jellyfin but closed-source premium features; deferred.
* **OMDb / Trakt**: OMDb is paid for posters; Trakt is a history-sync service. Neither adds playback. Trakt is a candidate for cross-device history later.
* **Firebase sync**: not built, per the user. Synced history is via Jellyfin.

## 4. Design

`vod/` is an independent module inside the app:

* `VodModels.kt`: `Provider` (with `streams`), `VodTitle`, `VodDetail`, seasons/episodes, renditions, `VodShortcut`.
* `ArchiveSource.kt`, `JellyfinSource.kt`, `TmdbSource.kt`, `TvMazeSource.kt` implement `VodSource`.
* `VodRepository.kt`: builds sources from config, 30-min catalog cache, partial-failure reporting, parallel search with dedupe that prefers playable copies, Jellyfin TMDB-id twin lookup.
* `VodLibrary.kt`: separate My List (titles + company/network/cast shortcuts) and progress store (per title and per episode, capped at 300, resumable = >1 min or 3 % and <95 %).
* `VodSettings.kt`: TMDB key (validated), Jellyfin token, Archive toggle, preferred quality.
* `VodViewModel.kt`, `VodScreens.kt`: the Movies tab: Browse (All/Movies/Series filter, hero, Continue Watching, themed rows), Search, My List, full-page detail with back-stack through recommendations, seasons/episodes, cast, crew, studios/networks, trailers, licence and source line, settings sheet.
* `VodPlayerActivity.kt`: on-demand player separate from the live player: deep VOD buffer (15–60 s), resume prompt with Start over, quality menu (falls back through renditions on error), movie and episodic layouts (S·E title, previous/next, episode list, auto-advance), progress saved every 10 s/pause/exit and reported to Jellyfin, stops in the background, screen kept on only while playing, audio focus and becoming-noisy handled.

## 5. Credentials

| What | Needed for | Where |
|---|---|---|
| TMDB API key or read token (free, themoviedb.org → Settings → API) | Movie discovery, full metadata, trailers | Movies → ⚙ → TMDB, or build with `-PTMDB_API_KEY=…` / env `TMDB_API_KEY` (CI secret) |
| Jellyfin server + account | Playing your own library | Movies → ⚙ → Jellyfin |
| Nothing | Internet Archive films/TV, TVmaze info | Works out of the box |

## 6. Known limits
* TMDB not verified live (no key); parsing is unit-tested against TMDB's documented response shapes.
* Archive licences are uploader-declared; Show trusts the Archive's metadata and re-checks it per item.
* Jellyfin transcoded qualities depend on the server allowing transcoding.
* Real-device playback still needs a QA pass (sandbox has no device; CI runs emulator install/launch).
