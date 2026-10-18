# Sources review, 1.5.4 (2026-10-18)

Four candidates were researched: Free-TV/IPTV, iptv-org's `streams/` folder, YouTube Live and Dailymotion Live.

| Source | Maintenance | Licence / terms | Compatibility with Show | Decision |
|---|---|---|---|---|
| **Free-TV/IPTV** | 21.2k stars, 3.1k forks, 2,046 commits, pushed 2026-10-15; daily PR flow | **No licence declared.** Policy: only officially free, mainstream channels, one URL each | 2,023 entries: 1,692 direct streams (HLS/DASH/TS), 133 YouTube, 3 Dailymotion, 195 VOD | **Kept** (already in since 1.5.0), verification rebuilt, list now refreshed weekly |
| **iptv-org `streams/`** | The source the iptv-org index playlists are generated from | Unlicense | Same URLs, user agents and referrers Show already reads through `index.*.m3u` | **Skipped**: duplicates the existing source, and the raw files have no categories, countries or languages |
| **YouTube Live** | 133 channels via Free-TV (mostly `/@handle/live`, `/channel/UC…/live`) | YouTube ToS forbids extracting streams; the **IFrame Player API** and **Data API v3** are the permitted routes | Not playable by Media3; plays in the official embedded player | **Integrated, gated**: only channels verified live and embeddable with the Data API. That needs a `YT_API_KEY`; with none, 0 are added |
| **Dailymotion Live** | 3 channels via Free-TV | Dailymotion terms: official embed player; public Data API, no key | The direct `cdn/live` HLS links are signed and expire, so they're not used | **Integrated**: official embed for channels the API reports `mode=live, onair, allow_embed`. 1 passes today (CNews); L'Équipe is off air and Gambero Rosso is refused (403) |

## Reverse-engineering notes (Free-TV)
* `lists/*.md`: one Markdown table per country. `make_playlist.py` turns rows whose URL cell starts with `[>]` into `playlist.m3u8`
  (plus per-country `playlists/`), with the `.md` H1 as group-title. Title marks: Ⓢ not HD, Ⓖ geo-blocked, Ⓨ YouTube, Ⓓ Dailymotion.
  Show already maps Ⓖ to Geo-blocked; **1.5.4 maps Ⓢ to an "SD" quality**.
* `check_channels.py` probes each URL several times and separates dead, blocked (geo), unreachable and flaky. Show's verifier now
  does the same (3 attempts, classified), and goes further by checking real media bytes.
* The header `x-tvg-url` lists about 100 epgshare01 XMLTV files (EPG). **Not used**: the per-country files are tens of MB of
  gzipped XML. Parsing them on a phone would cost the battery and memory the 1.5.3 audit just won back. It stays on the roadmap
  as a server-side, pre-trimmed now/next feed.

## Bug found in the 1.5.0 verifier
v1 accepted any 200 response over 1,000 bytes that wasn't an HLS playlist. So **web pages** (`https://lcp.fr/le-live-lcp-tnt-5433`,
`https://citizentv.co.ke/live`, …) and **DASH manifests** (never checked past the manifest) shipped as "verified", and the
app showed them as channels that always failed.

`tools/verify_extra_sources.py` v2 passes a link only when it delivers media:
* **HLS:** the top variant, then the `EXT-X-MAP` init (must be an MP4 box), then a recent segment with a media signature
  (TS sync bytes at 0 and 188, an fMP4 box, ID3, ADTS or MP3). SAMPLE-AES, FairPlay and Widevine are rejected (Show has no DRM).
  AES-128 is fine.
* **DASH:** the MPD is parsed. `ContentProtection` means DRM and is rejected. The first video Representation's
  SegmentTemplate init (or BaseURL) must be MP4 or WebM.
* **Progressive:** the body must start with a media signature. HTML, JSON and text are rejected.
* Transient failures (timeouts, resets, 5xx, 429) are retried up to 3 times.

## Verified results (run 2026-10-18 from the build sandbox)
* 1,692 direct candidates: **898 verified** (909 entries, some share a URL).
  Failures: 351 flaky (timeouts on all 3 tries), 223 dead (404, web page, DRM, not media), 209 blocked (401/403, mostly geo).
* Formats verified: HLS-TS 819, HLS-fMP4 29, HLS-AES 23, DASH 8, progressive TS 8, MP4 13, MP3 6, ADTS 1, ID3 2.
* Against the 1.5.0 list: 37 links dropped (9 now 404, **8 web pages**, 6 bad URLs, 5 broken variants, 3 DRM, 3 not media,
  3 now geo-blocked) and 38 newly verified.
* India section: 7 verified, 3 blocked from the sandbox's region. Indian viewers may reach more of them than the verifier can.
* Embeds: Dailymotion 1 of 3. YouTube 0 of 133, because no key was available (they're skipped, never guessed).
* Full per-URL report: `docs/freetv/verify-report.json`, `docs/freetv/embeds-report.json`.

## What changed in the app
* **Automatic updates:** `data/AllowlistStore.kt` fetches the re-verified allowlist from Show's own repository at most once
  a day and uses whichever copy was verified more recently, the bundled one or the downloaded one. The download is https only,
  fixed URL, capped at 2 MB and must parse. An allowlist can only narrow what plays. On any failure the last good copy stays.
  `.github/workflows/verify-free-tv.yml` re-runs both verifiers every Monday 08:00 IST, and refuses to publish if fewer than
  300 links pass, so an outage can't wipe the list.
* **Duplicate removal:** unchanged for direct links (same URL is skipped; same tvg-id or name+country becomes a backup link).
  An embed never becomes a backup link (Media3 can't fail over to it). If Show already has the channel natively, the embed
  is dropped.
* **Broken-stream detection:** the stricter verifier, the weekly refresh, and the existing dead-link memory
  ("Working channels only") and backup-link failover.
* **YouTube / Dailymotion player:** `player/EmbedPlayerActivity.kt`, a WebView with the official player:
  * Errors from the YouTube IFrame API become plain messages ("This channel isn't live right now"), with Try again and
    Open in YouTube/Dailymotion.
  * Off-screen, the page is unloaded, so nothing plays or polls in the background. Coming back reconnects at the live edge.
  * The screen stays on only while YouTube reports playing or buffering. For Dailymotion, whose embed has no state API without
    a partner player id, it stays on while the player is in front.
  * Hardened: no file or content access, no mixed content, Safe Browsing, no third-party cookies, no pop-ups. Any top-level
    navigation (logo, ad) opens outside the app. The JS bridge carries two ints.
  * `StreamProtocol.EMBED` accepts only the three official embed URL shapes. Embed channels are kept out of the Media3
    zapping queue.
* **Settings:** "Include YouTube & Dailymotion live" (on by default, under the Free-TV toggle).
* Search, categories, My List, history and the detail sheet work unchanged, because embeds are ordinary channels.

## Limitations
* **YouTube needs a free API key** (Google Cloud, "YouTube Data API v3", added as repo secret `YT_API_KEY`). Without it no
  YouTube channel is verified, so none is added. Show never scrapes YouTube pages: from datacenter IPs they answer
  `LOGIN_REQUIRED` anyway.
* Embedded players show the platform's own UI and may show ads. D-pad control on Android TV is limited to what the platform
  player supports.
* Geo-blocked links can't be verified from outside their country and are left out.
* The verifier proves a link delivered media at verification time, not that it'll stay up. The weekly refresh and the
  in-app dead-link memory cover the drift.
* Playback inside the WebView isn't covered by the JVM tests. The emulator smoke workflow installs and launches the app,
  but real-device playback of the embeds is still to be confirmed.
