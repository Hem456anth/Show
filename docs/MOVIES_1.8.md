# Movies & Series 1.8.0: collections, studios, tracks, picture modes, Trakt

1.8.0 builds on the 1.6.0 Movies tab (`docs/MOVIES_1.6.md` stays the reference for the architecture, sources and
rejected sources). There is no 1.7.0: the version went from 1.6.0 (code 11) to 1.8.0 (code 12).
Live TV is untouched: no file under `data/`, `player/` or `ui/` changed; `MainActivity.kt` only passes the Trakt client to
the Movies view model.

Every feature uses an official, documented API. Nothing scrapes, sniffs WebView traffic, aggregates providers, bypasses
DRM or uses Firebase. KiduyuTv (kiduyu-klaus/KiduyuTv_final) was read as a feature checklist only; no code was copied.

## What's new

| Feature | Where | Source / API | Needs |
|---|---|---|---|
| **Collections** | Movie detail → "The … Collection" row (release order) → full page; saveable to My List | TMDB `belongs_to_collection` → `GET /collection/{id}` | TMDB key |
| **Studios & networks** | New **Studios** section: 22 studios, 21 TV networks with TMDB logos → paged discover grid; saveable to My List | `GET /discover/movie?with_companies=` and `/discover/tv?with_networks=` (`page`, `sort_by=popularity.desc`, `include_adult=false`) | TMDB key (clear empty state without one) |
| **Audio & subtitle menus** | VOD player top bar | Media3 `Tracks` / `TrackSelectionOverride`; embedded tracks of Jellyfin/Archive files; Jellyfin **external** text subtitles side-loaded as WebVTT (`/Videos/{id}/{source}/Subtitles/{index}/0/Stream.vtt`) | — |
| **Remembered languages** | Picked audio / subtitle language (or Off) seeds the next video | `VodSettings.player` (`setPreferredAudioLanguage` / `setPreferredTextLanguage`) | — |
| **Fit / Fill / Zoom** | Player button cycles, remembered | `AspectRatioFrameLayout.RESIZE_MODE_FIT/FILL/ZOOM` | — |
| **Playback speed** | Player menu 0.5×–2× | `Player.setPlaybackSpeed` | — |
| **Skip intro / recap / credits** | Button over the player while inside a segment | Jellyfin 10.10+ `GET /MediaSegments/{itemId}` | Jellyfin with segments |
| **Mark as watched** | Movie detail (eye button) and per episode (circle) | Local progress; Jellyfin `POST /UserPlayedItems/{id}` (falls back to `/Users/{u}/PlayedItems/{id}`); Trakt `/sync/history` | — |
| **Trakt** | Movies settings → Trakt | Official OAuth **device-code** flow, `api.trakt.tv` (see below) | Trakt client id + secret |
| **Genre chips** | Browse, under All / Movies / Series | Genres of the loaded titles (≥3 titles each) | — |
| **Offline state** | Browse/empty pages | Cloud-off icon + "saved titles come back" line | — |
| **D-pad focus** | Pills and studio tiles show a white focus ring; tiles lift on focus | Compose focus interactions | — |

## Trakt

* **Credentials:** `-PTRAKT_CLIENT_ID=… -PTRAKT_CLIENT_SECRET=…`, env `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET`, or the CI
  secrets of the same names (wired into `.github/workflows/android.yml`). Only `[0-9a-f]` is kept when baking them into
  `BuildConfig`. Without them, Movies settings offers fields for the viewer's own Trakt app (64-hex id and secret,
  redirect uri `urn:ietf:wg:oauth:2.0:oob`). With neither, no Trakt UI or request exists beyond that setup card.
* **Sign-in:** `POST /oauth/device/code` → show `user_code` + `verification_url` → poll `POST /oauth/device/token` at the
  given interval: 400 pending, 429 slow down (+1 s), 404 invalid, 409 used, 410 expired, 418 denied, 200 tokens.
* **Tokens:** `KeystoreSecretStore` encrypts them with an AES-256-GCM key in the Android Keystore and stores them in
  app-private prefs (`vod_secrets`; backups are off app-wide). Robolectric/broken keystores fall back to plain private prefs.
  Tokens refresh a day before expiry (`POST /oauth/token`, `grant_type=refresh_token`) and once on any 401; a refused refresh
  signs out locally. Disconnect calls `POST /oauth/revoke`.
* **Sync:** "Sync now" (and right after sign-in) imports the Trakt watchlist into My List, pushes My List titles with a TMDB
  id to the watchlist, and shows "Recently watched on Trakt" in My List. Adding/removing a title in My List mirrors it to
  the watchlist while connected.
* **Scrobble:** the VOD player sends `/scrobble/start` on play, `/pause` on pause or leaving the screen, `/stop` at the end,
  on episode change and on close, with progress in %. Rebuffering does not send a pause. Trakt marks ≥80 % stops watched.
* **Scope limits:** only titles with a TMDB id can be matched (Jellyfin items with a TMDB provider id, TMDB info pages);
  Internet Archive items have none and are not scrobbled.

## Verification

| Item | Status |
|---|---|
| Studio/network ids and logo paths | Read from each public themoviedb.org/company|network page on 2026-10-20; logo URL with the negate filter returns 200 image/png |
| TMDB collection / discover parsing | Unit-tested on TMDB's documented shapes; **not live-verified (no TMDB key in the sandbox)** |
| Trakt device flow, refresh, revoke, sync, scrobble | Request building and status handling unit-tested against MockWebServer; **not live-verified (no Trakt app credentials)** |
| Jellyfin subtitles / segments / played | Unit-tested on documented shapes; demo server has no external subtitles or segments to probe |
| Track menus, picture modes | Logic unit-tested over real Media3 `Tracks` and `TrackSelectionParameters`; real-device QA still needed |

## Known limits
* Trakt can't match Internet Archive titles (no TMDB/IMDb ids in Archive metadata).
* Studios and collections need a TMDB key; without one the Studios page explains how to add it.
* Embedded subtitles in Jellyfin **transcodes** (HLS) depend on the server; external subtitles are side-loaded for every rendition.
* Release160UiTest and Release180UiTest run locally and are skipped on GitHub Actions (Compose idle issue on those runners).
