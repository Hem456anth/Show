# Show 1.8.0 audit

Scope: the whole Movies & Series module (`app/src/main/java/app/show/tv/vod/`, 1.6.0 code and the 1.8.0 additions), its
wiring in `ShowApp.kt` / `MainActivity.kt`, the build and CI files, plus a Live TV regression check. Method: line-by-line
code review, `git grep` sweeps (secrets, `http://`, logging, WebView settings), Android lint, the unit and Robolectric UI
suites, and `:app:assembleRelease`. The sandbox has no device, TMDB key or Trakt app, so items that need them are marked
**not live-verified**.

Status legend: **Fixed** (changed in 1.8.0), **OK** (checked, nothing to change), **Accepted** (known, documented trade-off).

## 1. Correctness

| # | Finding | Status / fix |
|---|---|---|
| C1 | Studio/network/person page kept its own copy of "saved" state; toggling the shortcut elsewhere (My List) left the + / ✓ wrong | **Fixed**: `ShortcutPage` reads the library flow |
| C2 | `openShortcut` launched untracked jobs: opening A then B quickly could show A's titles under B's header | **Fixed**: one cancellable `shortcutJob`; load-more checks the key before applying |
| C3 | Every TMDB detail page fired a second full `/movie/{id}` (or `/tv/{id}`) request just to learn company/network ids | **Fixed**: ids parsed from the same response (`VodDetail.orgIds`); one request per page, half the TMDB quota |
| C4 | Jellyfin client header reported `Version="1.6.0"` forever | **Fixed**: `BuildConfig.VERSION_NAME` |
| C5 | `tmdbIndex()` pulled every library item with full fields; large libraries exceed `VodHttp`'s 4 MB cap and "Play on Jellyfin" silently disappeared | **Fixed**: `Fields=ProviderIds,ProductionYear&EnableImages=false&EnableUserData=false` |
| C6 | PlayerView's built-in subtitle button duplicated the new menu and didn't remember a choice | **Fixed**: hidden; Show's Audio/Subtitles menus remember languages |
| C7 | Trakt scrobble could have sent a "pause" on every rebuffer if tied to `isPlaying` | **Fixed by design**: start/pause follow play intent (`onPlayWhenReadyChanged`, READY), each state sent once, pause/stop only after a start |
| C8 | Collection parts with no release date | **OK**: listed last, then by title; adult parts filtered (tested) |
| C9 | TMDB discover beyond page 500 returns an error | **OK**: pages clamped to 500 (`TmdbSource.MAX_PAGE`) |

## 2. Crashes, null and network error paths

| # | Finding | Status / fix |
|---|---|---|
| N1 | All new JSON parsing (collection, discover, Trakt, Jellyfin `MediaSources`/segments) | **OK**: `opt*`/`str()` only; null/absent ids return null (tests cover `{"id":null}`, missing TMDB ids, empty bodies) |
| N2 | Trakt poll status codes | **OK**: 400/404/409/410/418/429 mapped; any other code surfaces as a friendly message, never a crash |
| N3 | Refresh token refused (400/401) | **Fixed by design**: signs out locally; a network error keeps the old tokens |
| N4 | Undecryptable secret (keystore reset after a device restore) | **OK**: reads as absent, the viewer signs in again (tested) |
| N5 | Unsupported tracks in a stream | **OK**: skipped in menus; unknown languages shown as "Unknown" |
| N6 | `VodPlayerActivity` finishing in `onCreate` (no request after process death) then `onDestroy` | **OK**: `player` is null; the new pending-request cleanup checks `::request.isInitialized` |
| N7 | Studios/collections without a TMDB key | **Fixed**: clear empty state + "Open Movies settings" button; the repository throws a readable `NEEDS_TMDB` message instead of returning an empty grid |
| N8 | Offline | **Fixed**: cloud-off icon and a line explaining saved titles come back; existing friendly messages kept |
| N9 | Paging error mid-grid | **OK**: keeps loaded items and stops paging instead of retrying in a loop |

## 3. Threading and lifecycle

| # | Finding | Status / fix |
|---|---|---|
| T1 | Player off-screen | **OK** (1.6): `onStop` saves, reports to Jellyfin, `stop()`s (no buffering/network/decoder); 1.8 adds a Trakt pause |
| T2 | Player release | **Fixed**: `onDestroy` also clears `PlayerView.player` and the view reference |
| T3 | Skip-intro position polling | **OK**: 500 ms loop runs only while the item has segments **and** is playing |
| T4 | Trakt calls from the player | **OK**: app-scoped `vodScope` (IO), fire-and-forget, `runCatching` |
| T5 | Concurrent token refreshes (player + settings) | **Fixed by design**: refresh serialised with a `Mutex` |
| T6 | Device-code polling continues if the settings sheet is closed | **Accepted**: bounded by Trakt's code expiry (≤10 min) at Trakt's interval; "Cancel" stops it; viewers often close the sheet to approve on a phone |
| T7 | Network on the main thread | **OK**: `VodHttp` always switches to `Dispatchers.IO` |

## 4. Memory

| # | Finding | Status / fix |
|---|---|---|
| M1 | `VodPlayerActivity.pending` (static) kept the last request (episode lists) for the process lifetime | **Fixed**: cleared when the activity finishes, only if it is still this activity's request |
| M2 | PlayerView ↔ player link after release | **Fixed** (T2) |
| M3 | Response size | **OK**: 4 MB cap per response in `VodHttp` |
| M4 | View models and composables hold no Activity/Context | **OK** (reviewed) |
| M5 | Test-only: view models created in Release180Test kept collectors alive into later tests | **Fixed**: the test cancels their scopes in `@After` |

## 5. Battery

| # | Finding | Status / fix |
|---|---|---|
| B1 | The 10 s progress tick re-serialised My List and shortcuts with every save | **Fixed**: `VodLibrary` writes only the blob that changed |
| B2 | No polling while paused (skip markers), no Trakt traffic except state changes | **OK** |
| B3 | Screen-on only while playing, audio focus, becoming-noisy | **OK** (1.6, unchanged) |

## 6. Security

| # | Finding | Status / fix |
|---|---|---|
| S1 | Secrets in the repo | **OK**: `git grep` for keys/tokens finds only a fake test JWT; TMDB/Trakt values come from Gradle properties, env or CI secrets; `.gitignore` covers keystores |
| S2 | Build-time values in `BuildConfig` | **Fixed**: Trakt id/secret filtered to `[0-9a-f]` so nothing can break out of the Java string |
| S3 | App credentials inside the APK (TMDB key, Trakt client secret) | **Accepted**: inherent to installed apps using these APIs; both are revocable app credentials, never user credentials |
| S4 | Trakt tokens | **Fixed (new)**: AES-256-GCM, key in the Android Keystore (`KeystoreSecretStore`); falls back to private prefs only where the Keystore is unavailable |
| S5 | Jellyfin token was plain app-private prefs (1.6) | **Fixed**: moved to the same encrypted store, with a one-time migration of existing sign-ins (tested) |
| S6 | Backups | **OK**: `allowBackup=false` and data-extraction rules exclude everything |
| S7 | https | **OK**: TMDB, Trakt, Archive, TVmaze (images upgraded to https) and TMDB logos are https. A Jellyfin server the viewer types as `http://` stays allowed for LAN use; **Fixed**: settings now warn when the signed-in server is cleartext |
| S8 | Tokens in URLs or logs | **OK**: Jellyfin and Trakt auth travel in headers only (tested: no token in stream/subtitle URLs); no `Log.*` in `vod/` |
| S9 | WebView | **OK**: no new WebView. Trailers reuse `EmbedPlayerActivity` (JS on for the official player, file/content access off, mixed content never, no auto-opened windows, a minimal JS bridge, YouTube/Dailymotion embed URLs only) |
| S10 | Exported components | **OK**: `VodPlayerActivity` `exported=false` |
| S11 | Password handling | **OK**: Jellyfin password never stored; Trakt uses a device code, so Show never sees a Trakt password |

## 7. Accessibility

| # | Finding | Status / fix |
|---|---|---|
| A1 | Player buttons for Audio/Subtitles/Speed/Picture must say their current value | **Fixed (new)**: e.g. "Subtitles: English", "Playback speed, Normal", "Picture: Zoom. Change" |
| A2 | Continue Watching cards had no description | **Fixed**: "Continue <title>, <episode or time left>" |
| A3 | Cast chips and episode rows were clickable with no role/label | **Fixed**: role Button + "Open filmography" / "Play S1:E2" |
| A4 | Pills (sections, filters, genres) | **Fixed**: `selected` semantics (role Tab already), visible 2 dp focus ring for D-pad |
| A5 | Studio tiles | **OK (new)**: content description = studio name, focus lift + ring, text fallback when a logo fails |
| A6 | Trakt code | **OK (new)**: TalkBack reads it character by character |
| A7 | Mark-watched controls | **OK (new)**: "Mark S1:E3 as watched" / "S1:E3 watched" |

## 8. Performance

| # | Finding | Status / fix |
|---|---|---|
| P1 | Extra TMDB request per detail page | **Fixed** (C3) |
| P2 | Genre chips and row filtering | **OK**: computed in `remember(rows, filter, genre)` |
| P3 | Paging trigger | **OK**: `derivedStateOf` on the grid's last visible index, so scrolling doesn't recompose the grid |
| P4 | Image sizes | **OK**: posters w342, backdrops w780, logos h60 (TMDB negate filter, ~3 KB), Jellyfin images capped by height/width; Coil memory + 64 MB disk cache (1.5) |
| P5 | Track label building | **OK**: ISO 639-2 table built once (lazy) |

## 9. Live TV regression

* No file under `app/src/main/java/app/show/tv/data`, `player` or `ui` changed (`git diff --stat` against 1.6.0 is empty there).
* `MainActivity.kt`: one line (the Movies view model factory gets the Trakt client). `ShowApp.kt`: Movies-only additions.
* All Live TV tests (parsers, playback controller, player launch, 1.5.x release tests, navigation UI) pass unchanged.

## 10. Licence and compliance of every source

| Source | Use | Terms followed |
|---|---|---|
| Internet Archive | Playback of public-domain / CC items only | Licence filter in the query and per item; taken-down items refused (1.6) |
| Jellyfin | The viewer's own server | Public REST API; no Jellyfin code included |
| TMDB | Metadata, collections, discover, studio/network logos (TMDB-hosted images, shown to identify the studio) | Free non-commercial API; attribution on Browse and Studios; nothing streamed |
| TVmaze | Series metadata without a TMDB key | CC BY-SA 4.0 attribution |
| Trakt | Watchlist/history sync and scrobbling | Official API + OAuth device flow; attribution "not endorsed by Trakt"; credentials from the build or the viewer's own app |
| YouTube | Trailers | Official embed player only |
| KiduyuTv_final | Feature checklist only | Proprietary: **no code, assets or strings copied** |

No scraped or unlicensed streams, WebView sniffing, provider aggregation, DRM bypass or Firebase were added.

## 11. Dependencies and NOTICE

* **No new runtime dependency.** Encryption uses the platform Keystore and `javax.crypto` (androidx.security-crypto was
  considered and rejected: deprecated upstream). Picture modes use Media3 UI's `AspectRatioFrameLayout`, already shipped.
* NOTICE.md gains Trakt and the TMDB studio-logo note; the library list is unchanged.

## 12. Tests found during the audit

| # | Finding | Status / fix |
|---|---|---|
| Q1 | Release160UiTest (and the new Release180UiTest) passed alone but hit `AppNotIdleException` at teardown in the full local suite, the same symptom the 1.6 notes saw on CI. Cause: the tests drive a manual Compose clock; a state change that lands after the last step waits for a frame that never comes, so the rule's final idle check spins | **Fixed**: both tests hand the clock back (`autoAdvance = true` + `waitForIdle()`) before teardown; the final screens have no spinner. The CI skip is kept until a CI run proves the fix there |
| Q2 | UI tests should not load real images under Robolectric | **Fixed**: Release180UiTest installs an image loader that fails fast |

## 13. Lint (`:app:lintRelease`)

First run: **7 errors, 31 warnings**.

| Errors | Where | Fix |
|---|---|---|
| 3 × `UnsafeOptInUsageError` | `VodTracks.kt` `ResizeMode` uses Media3's `@UnstableApi` `AspectRatioFrameLayout.RESIZE_MODE_*` | **Fixed**: `@OptIn(UnstableApi::class)` on the enum |
| 1 × `StateFlowValueCalledInComposition` | `VodScreens.kt` search hint read `settings.config.value` during composition (1.6 code), so it never updated | **Fixed**: collected with `collectAsStateWithLifecycle()` |
| 3 × `RestrictedApi` | Live TV `PlayerActivity.dispatchKeyEvent` override calling `super` (1.5 code) | **Accepted / suppressed**: a known false positive for overriding `ComponentActivity.dispatchKeyEvent`; scoped ignore in `app/lint.xml` so Live TV code and behaviour stay untouched |

Warnings left as they are (none new from 1.8 except one `InflateParams`):
* 17 × `GradleDependency` (newer AndroidX/Media3 available): **Accepted**. Upgrading Media3 1.5 → 1.11 and Compose/AndroidX
  mid-release would change playback behaviour for Live TV; planned as its own release with a device QA pass.
* `InflateParams` × 2 (Live and VOD `PlayerView` inflated for `AndroidView` with no parent): **OK**, the view gets its layout
  params from `AndroidView`.
* `ObsoleteSdkInt`, `DiscouragedApi`, `Overdraw`, `UnusedResources`, `PictureInPictureIssue`, `DataExtractionRules`,
  `InsecureBaseConfiguration` (cleartext allowed for http live streams, documented in `network_security_config.xml`): pre-existing
  Live TV items, **Accepted**, unchanged to keep Live TV behaviour identical.
