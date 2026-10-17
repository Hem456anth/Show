# Show 1.5: streaming investigation and fixes

Investigation date: 2026-10-17. All probes ran from a US cloud network (AWS us-west-2) against the live
iptv-org index (`index.m3u`, 23,522 lines) and the Free-TV playlist. Raw results live in `docs/probe-1.5/`.

## 1. Zee channels

### Method
1. `curl` probe of all 41 Zee entries in the live index with three user agents (Show's, Chrome Android,
   ExoPlayer): playlist → highest-bandwidth variant → first media segment.
2. **In-app diagnostic** (`LiveStreamDiagnostic`, opt-in Robolectric test): each Zee channel parsed by
   `M3uParser`, then played through the real `StreamEngine` + `PlaybackController` + Media3 pipeline until
   `STATE_READY` or a classified `PlaybackIssue`.
3. Review of the iptv-org issue tracker (`repo:iptv-org/iptv zee`).

### Findings
| Result in the app pipeline | Count | Channels |
|---|---|---|
| Plays (READY) | 30 | Zee TV, Zee TV HD, Zee Cinema HD, Zee Bangla (SD/HD), Zee Talkies, Zee Action, Zee Classic, Zee Tamil HD, Zee One (+FR/DE), Zee Kannada HD, Zee Marathi HD, all Zee regional news feeds, Zee Business, Zee News, … |
| Region-locked to India (403 / DNS from US) | 6 | Zee Cine Classic, Zee Comedy Nation, Zee Dil Se, Zee Horror Nights, Zee South Flix (Zee's own FAST feeds on Amagi), Zee Nung (Thailand) |
| Dead upstream | 5 | Zee 24 Ghanta (404), Zee Cinemalu HD (404), Zee Telugu HD (404), Zee Tamil News (host gone), Zee TV Asia Pacific (redirector 404) |

* User agent and Referer make **no difference** for any Zee stream (identical results for all three UAs), so
  header spoofing is not the fix.
* Codecs are plain H.264 + AAC in MPEG-TS; nothing Media3 can't decode.
* **Root cause: link rotation.** Zee links in iptv-org change constantly (Zee TV, Zee Bangla HD, Zee Cinema HD,
  Zee TV HD were all re-added between Oct 2 and Oct 6 2026; Zee Telugu was reported dead Oct 13). Show's channel
  id is a hash of the stream URL, so when a link rotates:
  * My List and Up Next kept the **old snapshot** (dead URL) because the id no longer matched the catalog.
    Zee News kept working because its CloudFront URL has been stable, which is why "only Zee News works".
  * A TV box left open for days kept the playlist it loaded at start.
* Secondary: Zee Tamil HD and Zee TV HD carry several audio renditions (see §2), and Zee Bangla Sonar/Zee Cinema
  point at Colors HD/NatGeo HD feeds in the upstream data (mislabelled upstream; reported, not changed).

### Fixes
* `Channel.identity` (tvg-id feed, else normalised name) + `LibraryStore.reconcile`: saved channels follow
  the channel to its new address; ids are migrated so the My List heart stays on.
* `MainViewModel.resolveSaved`: live entry → same channel at a new address → snapshot (last resort).
* `refreshIfStale`: the playlist is re-checked (conditional GET) when the app returns after 6 h.
* Free-TV backup links (§3) give channels a second, independently verified address where one exists.

Region-locked feeds still show "Not available in your region" outside India; they are expected to play in India.

## 2. Multiple audio tracks
Zee TV HD exposes two Hindi renditions (`hin a1`, `hin a2`), Zee Tamil HD three AAC renditions (all `und`).
1.4 only offered audio through Media3's buried settings menu. 1.5 adds:
* `PlaybackController.audioTracks` listing every supported track with readable labels (`AudioLabels`:
  language name, role such as Commentary or Audio description, Mono/Stereo/5.1, Dolby/DTS codec, and a
  bitrate suffix when two tracks would otherwise look the same).
* An **Audio** button in the player top bar (only when there are 2+ tracks), a checkmark on the playing
  track, and "N audio tracks" in the channel line.
* `selectAudio` applies a `TrackSelectionOverride` and stores the language as the preferred audio language,
  so choosing Telugu prefers Telugu on the next channel too (`Settings.audioLanguage`).

## 3. Buffering at 1080p
Measurements (live, highest variant):

| Stream | Segments | Window | Bitrate | Download speed |
|---|---|---|---|---|
| Zee Cinema HD (raw IP origin, single 1080p rendition) | 2.9 s | 4 segments ≈ 12 s | 5.3 Mbps | 1.3× real time |
| Zee News (CloudFront) | 6 s | 12 segments | 3.2 Mbps | 21× real time |

Causes found in 1.4's configuration:
1. **Live catch-up speed (max 1.04×).** Media3 sped playback up to reach the live edge, consuming the buffer
   faster than a 1.3× origin refills it. Now capped at 1.0× (slowing to 0.97× is still allowed and rebuilds buffer).
2. **3 s resume threshold after a rebuffer** meant resuming on a single 2.9 s segment and stalling again.
   Now 5 s; first start stays at 1.5 s so zapping is unchanged. Minimum live buffer 15 → 20 s.
3. **ABR too optimistic for bursty origins.** `AdaptiveTrackSelection` now budgets 65% of measured bandwidth,
   steps up after 10 s of headroom and drops within 15 s.
4. **New: adaptive fallback.** Two rebuffers within 60 s on Auto cap the stream one rendition lower and say so
   ("Connection is slow. Switched to 720p"); the channel line shows "Auto 720p". Manual quality picks are never overridden.

Single-rendition 1080p streams can't adapt; the larger resume threshold and no catch-up are what help there.

## 4. Additional sources
Candidates reviewed: Free-TV/IPTV, iptv-org's own per-country/language lists (already used), Pluto/Samsung TV Plus
scrapers (rejected: unofficial re-hosts of platform APIs), YouTube live links (rejected: not playable by Media3).

**Accepted: Free-TV/IPTV** (github.com/Free-TV/IPTV): ~21k stars, updated daily, policy "only channels which are
officially provided for free", mainstream only, no paid/adult channels, one URL per channel.

Verification (`tools/verify_extra_sources.py`, 2026-10-17): 2,023 entries → 1,692 candidates after removing
YouTube/Dailymotion (Ⓨ) and VOD sections → **897 verified** (playlist, top variant and media bytes all fetched).
Only those URLs ship in `assets/verified_extra.json`; anything else in the live Free-TV file is ignored, so a
compromised or changed upstream playlist can't inject links. Merge rules (`ExtraSources.merge`):
* a Free-TV entry for a channel the catalog already has (tvg-id base or normalised name + country) becomes a
  **backup link**, never a duplicate tile;
* exact URL duplicates are dropped;
* new channels are tagged "Free-TV"; the India source only gains Indian channels; Ⓖ becomes the Geo-blocked flag;
* Settings → "Add verified Free-TV channels" turns it off.

Dead streams are still handled by backup links, the 24 h dead-link memory, "Working only", and next/previous skipping.
Re-run the verifier from a network near your viewers before each release (geo-blocked channels fail elsewhere and
are correctly left out).
