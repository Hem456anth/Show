# Changelog

## 1.5.1 (2026-10-17): Home overlay fix
* **Removed the grey overlay on Home.** 1.5.0's backdrop blur (Haze) drew a stray translucent panel across the hero and the brand
  bar on devices. The blur library is gone; the liquid glass (tab bar, logomark, Play buttons) is now drawn directly: a dark
  translucent body, veil, specular highlight and lens rim, with no offscreen layers.
* **Text and icons are white again** on Home (the hero title and the settings gear drew black once the old Scaffold was removed).

## 1.5.0 (2026-10-17): Zee fix, audio tracks, smoother 1080p, verified Free-TV, liquid glass
* **Zee channels (root cause):** iptv-org rotates Zee links almost daily, and Show's channel id is a hash of the URL, so My List and
  Up Next kept playing the old, dead snapshot (Zee News worked only because its link never changed). Saved channels now follow the
  channel by identity (`tvg-id` feed, else normalised name) to its current link, and ids migrate so the My List heart stays on.
  The playlist is re-checked when the app returns after 6 h. Live probe of all 41 Zee entries: 30 play in the app pipeline, 6 are
  India-only (403 outside India), 5 are dead upstream. User agent and Referer make no difference. See `docs/STREAMING_1.5.md`.
* **Audio tracks:** an Audio button in the player lists every audio track by language, role (Commentary, Audio description),
  layout (Mono, Stereo, 5.1) and codec, with a bitrate suffix for same-language renditions (Zee Tamil HD has three). The pick is
  remembered as the preferred language for the next channels. The channel line shows "N audio tracks".
* **Buffering at 1080p:** live playback no longer speeds up (1.04×) to chase the live edge, which drained the buffer on slow
  origins (Zee Cinema HD serves 5.3 Mbps at only 1.3× real time with a 12 s window). Resume after a rebuffer waits for 5 s
  instead of 3 s, the live buffer floor is 20 s, ABR budgets 65% of bandwidth, steps up after 10 s and drops within 15 s.
  New adaptive fallback: two stalls within a minute on Auto lower the quality one step and tell you ("Switched to 720p").
* **Free-TV/IPTV, verified:** 897 of 1,692 candidate links passed `tools/verify_extra_sources.py` (playlist, top variant and media
  bytes) and only those ship in `assets/verified_extra.json`. Matches become backup links (no duplicate tiles), new free-to-air
  channels are added and tagged Free-TV, the India source gains only Indian channels, YouTube (Ⓨ) and VOD entries are never
  used, Ⓖ becomes Geo-blocked. Settings has a toggle.
* **Liquid glass:** a floating capsule tab bar with a specular
  highlight, a lens-like rim and a glass "lens" that springs to the selected tab. The logomark sits on a glass tile.
* **Home:** glass now lives only on Play buttons (hero Play, Play Live, My List play). My List and Details are flat outlined circles,
  and the brand bar turns solid black on scroll instead of frosted.
* **Tests:** 13 new (rotated links, reconcile, Free-TV merge and allowlist, audio labels and listing, adaptive step-down, live
  speed, settings). `LiveStreamDiagnostic` is an opt-in live probe (`SHOW_LIVE_PLAYLIST`, `SHOW_LIVE_FILTER`).

## 1.4.0 (2026-10-16): Apple TV-inspired redesign and new logomark
* **New logomark:** the retro TV set the user supplied (two antennas, rounded screen, base with slot and dial), traced as an
  original vector (`ic_show_mark.xml`). It's used for the launcher, the Android TV banner and the Home brand tile. It replaces the 1.2.0 clapperboard.
* **Apple TV-style UI**, still strictly black and white:
  * True-black canvas, neutral greys, frosted glass (white 14%) and hairlines. Type ramp: Large Title 34, Title 22, Headline 17.
  * Top-shelf hero: 580 dp full-bleed artwork with a slow Ken Burns drift, swipe and scroll parallax, title art bottom-left,
    a white capsule Play button and frosted circular My List and Details buttons. Capsule page dots sit bottom-right, and it auto-advances every 8 s.
  * Cards lift on focus (spring scale 1.07, shadow, white ring) and sink on press. Long-press toggles My List with haptics. A frosted LIVE capsule.
  * Shelves have "Title ›" headers that open Live TV filtered to that shelf. "Continue watching" is now **Up Next** with larger tiles.
  * The tab bar is frosted black with a hairline and has no pill indicator. The brand bar turns to glass on scroll. Screens cross-dissolve with a subtle zoom. Grid items animate.
  * Detail is now a product page: blurred full-bleed backdrop, logo title art, "Play Live" capsule, facts as capsules and a "More in …" shelf.
* **Android TV:** installable (leanback launcher and banner), D-pad focus on every card. In the player, Channel +/−, media next/previous and Page up/down zap, and **Last** recalls the previous channel.
* **Fixes:** stream URLs restricted to network schemes (security). Related channels no longer fall into "Undefined". Stale Live TV
  and Search filters are cleared after a source switch, without composition-time writes.
* **Accessibility:** visible focus, heading semantics, merged card descriptions, 7.9:1 secondary text and reduce-motion support.
* Docs: `docs/AUDIT_1.4.md` (architecture, competitor scan, findings). Tests: 8 new (TV keys and recall, TV manifest, See all,
  long-press, large titles and chips, scheme allowlist, related channels).

## 1.3.0 (2026-10-16): Official iptv-org sources
* **Five sources, one tap apart:** India (`countries/in.m3u`), Global (`index.m3u`), By country (`index.country.m3u`), By language
  (`index.language.m3u`) and the existing By category index. You switch with the source chips on Home or in Settings, and custom HTTPS M3U still works.
  Each source has its own offline cache and conditional-GET refresh (`PlaylistSource`, `PlaylistRepository.forSource`).
* **Grouping-aware parsing:** `group-title` is filed as a category, a country or a language depending on the source. Home rows follow it,
  so you get rows by country, by language or by genre.
* **Enrichment:** after the source loads, Show fetches the other two indexes in parallel and joins them by stream URL (`CatalogEnricher`).
  Every source then gets category, country and language filters. In the snapshots, coverage is over 95% for country and language on all four new sources.
* **Live TV filters:** a country dropdown and a new language dropdown (e.g. Telugu, Hindi, Tamil), combined with the category chips and "Working only".
  Search also matches every country and language a channel belongs to.
* **No duplicates:** the grouped indexes list a stream once per group (12,873 country entries for 11,346 streams), and these collapse into one channel.
  URLs that differ only by scheme case, host case or a trailing slash count as one stream. The same feed (`tvg-id` + name) under two URLs becomes one channel with a backup link.
* **Unavailable streams:** when the main stream is dead, the player switches to the channel's backup ("Main stream is down. Trying backup 1…").
  A channel counts as dead only after every link fails, and Next/Previous skip channels that failed in the last 24 h.
* **Favourites across sources:** My List and Continue watching keep a small snapshot of each saved channel. A favourite saved from India
  still plays after you switch to a source or custom list that doesn't include it.
* Countries use iptv-org's spelling ("United Kingdom", not "UK"). The table was generated from the live index.
* Black-and-white UI is preserved: the new chips, dropdowns and radio buttons use the white/black system from 1.2.0.
* Tests: 15 new tests across `OfficialSourcesTest` and `SourcesAndroidTest`, run on real snapshots of all four playlists (2026-10-16). They cover parsing, de-duplication,
  enrichment, rows, a view-model load per source, a player hand-off for about 170 channels per source, backup fallback and dead-channel skipping.
  `tools/verify_sources.py` checks the live feeds.

## 1.2.0 (2026-10-16): Logomark and black-and-white theme
* **New logomark:** a film clapperboard (open striped arm, play wedge cut into the slate). It's mark #1 on the user's reference sheet. It's redrawn from scratch as an
  original vector (`ic_show_mark.xml`) because the reference was a watermarked stock preview. It's used for the launcher icon (black on white,
  with an Android 13+ themed/monochrome layer) and the in-app brand tile on Home.
* **Orange removed everywhere.** The ember/gold palette is replaced by a strict monochrome system:
  * Primary buttons are white with black text, secondary buttons are black with white text and a white outline (`ui/components/Buttons.kt`).
    All 12 buttons across Home hero, details, Settings, error states and the player now use one of the two.
  * Selected category and "Working only" chips, nav bar and rail indicators, switches, progress spinners, hero dots, the LIVE NOW dot,
    the My List ribbon, the player's quality and sleep icons, and the search field focus ring are all white, with black content where it sits on white.
  * Logo-less channel tiles use graphite gradients instead of coloured ones. Error text keeps its red for accessibility.
* Accessibility: 21:1 label contrast, 48 dp minimum touch targets and consistent shapes. The brand tile has a content description.
* Tests: UI tests assert the brand mark renders and the Play button is present. Everything else is unchanged.

## 1.1.0 (2026-10-16): Show

### Renamed: Lumora TV → Show
* Launcher label, in-app brand mark, About text, User-Agent (`Show/1.1.0`), docs and the Gradle project name all now say Show.
* Package and applicationId changed to `app.show.tv`, with code identifiers to match (`ShowApp`, `ShowTheme`, `ShowColors`, `Theme.Show*`,
  `show_player_view`). Show installs alongside the old `app.lumora.tv` build, so uninstall that one by hand.

### Critical fix: tapping Play bounced back to the previous screen
* **Root cause:** `StreamEngine` sets `ExoPlayer.setWakeMode(C.WAKE_MODE_NETWORK)`, but the manifest didn't declare
  `android.permission.WAKE_LOCK`. Media3 1.5.1's `WakeLockManager.updateWakeLock()` calls `PowerManager.WakeLock.acquire()`
  without catching `SecurityException` (confirmed with `javap -c` on the shipped `media3-exoplayer-1.5.1` classes). The moment playback
  started, the process crashed and Android restored the previous activity, so it looked like the app navigating back.
  Robolectric doesn't enforce permissions, so the 1.0 test suite couldn't see it.
* **Fix:** declare `WAKE_LOCK`. Regression tests assert the permission in the merged manifest and launch `PlayerActivity`
  to check that it stays open, starts playback and records history.

### Other bugs fixed
* The player didn't switch channels when you picked another one while it was already open (for example from PiP). It's `singleTop`, but `onNewIntent` was ignored. Now it re-targets.
* Closing the PiP window kept streaming audio and data in the background. It now pauses when the activity isn't really in PiP.
* Returning to the player auto-resumed even after an error or the sleep timer. It now resumes only after an ordinary interruption.
* http→https redirects on streams failed because the shared client refused cross-scheme redirects. Streams now use a media client that allows the upgrade.
  The playlist client still refuses, and the repository rejects any playlist whose final URL isn't https.
* Removed the `Origin` header that 1.0 added next to `Referer`. VLC sends Referer only, and Origin can trigger CORS 403s on CDNs.
* Header injection: user-agent and referrer values from playlists are now stripped of CR/LF/NUL and capped at 1 KB.
* Overlapping playlist loads (pull to refresh during a load) could finish out of order. Loads are now cancellable and serialised.
* The error copy said "Pull to retry" but there was no pull-to-refresh. Home now has pull-to-refresh.
* Image loader construction was eager at app start. It's now lazy.
* A flaky backoff test is now deterministic.

### New features (from competitor research: TiviMate, OTT Navigator, IPTV Smarters, VLC)
* **Settings sheet** (gear on Home):
  * Bring your own M3U playlist, https only and validated. Each source gets its own cache.
  * Hide geo-blocked channels. Favourites and history keep resolving against the full list.
  * "Working channels only".
  * Clear watch history, privacy statement and attribution.
* **Dead-link memory:** channels that fail with 404, 403/451, a format error or a persistent 5xx are marked for 24 h.
  Their cards dim with an OFFLINE badge, the Live TV "Working only" chip hides them, and a successful start clears the mark.
* **Country filter** on Live TV, from tvg-id, sorted by channel count, with localised country names.
* **Quality picker** in the player (Auto or a fixed rendition) when a stream offers two or more video heights. It resets per channel.
* **Sleep timer:** 15, 30, 60 or 90 min, with "Keep watching" to resume at the live edge.

### Tests
* 12 suites, 61 tests. New coverage: player launch regression, onNewIntent, manifest permissions, settings validation and persistence,
  dead-link expiry, header sanitising, per-source cache, https→http redirect refusal, geo-hide keeps favourites,
  catalog browse/countries, sleep timer, dead-link callback, settings sheet UI and the Live TV filter UI.

## 1.0.0 (2026-10-16)
Initial release as Lumora TV.
