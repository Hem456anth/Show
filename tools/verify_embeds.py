#!/usr/bin/env python3
"""
Verify Free-TV's YouTube and Dailymotion live channels for Show's official embedded player (1.5.4).

Show never extracts raw streams from YouTube or Dailymotion (both forbid it in their terms). These
channels play only inside the platforms' own embedded players, so the checks use the platforms' own
public APIs:

  Dailymotion  GET https://api.dailymotion.com/video/<id>?fields=mode,onair,allow_embed,private,status
               (public, no key). Passes when mode=live, onair, allow_embed, published, not private.
  YouTube      YouTube Data API v3 (needs YT_API_KEY; ~4 quota units per channel of the 10,000/day free
               quota): resolve the channel id (channels.list forHandle/forUsername, or the id in the URL),
               read the newest uploads (playlistItems.list), then videos.list status+liveStreamingDetails.
               Passes when a video is live now (liveBroadcastContent=live, started, not ended), public
               and status.embeddable. Without a key, YouTube channels are skipped, never guessed.

Writes sources.freetv.embeds into app/src/main/assets/verified_extra.json:
  [{"source_url": <Free-TV URL>, "embed_url": <official embed URL>, "kind": "youtube"|"dailymotion", "name": ...}]
The embed URL for YouTube is the channel's live_stream embed, so it keeps working when the channel
restarts its broadcast under a new video id.

Usage: [YT_API_KEY=...] python3 tools/verify_embeds.py
"""
import datetime, json, os, re, sys, time, urllib.parse, urllib.request, pathlib, concurrent.futures as cf

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAYLIST = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
KEY = os.environ.get("YT_API_KEY", "").strip()


def get(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(3_000_000).decode("utf-8", "replace")


def getj(url):
    try:
        return json.loads(get(url))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode())
        except Exception:
            return {"error": {"code": e.code}}


def entries(text):
    lines = text.splitlines()
    for i, l in enumerate(lines):
        if l.startswith("#EXTINF"):
            j = i + 1
            while j < len(lines) and lines[j].startswith("#"):
                j += 1
            if j < len(lines):
                g = (re.search(r'group-title="([^"]*)"', l) or [None, ""])[1]
                n = (re.search(r'tvg-name="([^"]*)"', l) or [None, ""])[1] or l.split(",", 1)[-1].strip()
                yield n, g, lines[j].strip()


DM_ID = re.compile(r"dailymotion\.com/(?:video|embed/video|cdn/live/video)/(x[a-z0-9]+)", re.I)


def dailymotion(url):
    m = DM_ID.search(url)
    if not m:
        return None, "no video id"
    vid = m.group(1).lower()
    d = getj(f"https://api.dailymotion.com/video/{vid}?fields=id,mode,onair,allow_embed,private,status")
    if "error" in d:
        return None, f"api {d['error'].get('code')}"
    if d.get("mode") != "live":
        return None, "not a live stream"
    if not d.get("onair"):
        return None, "off air"
    if not d.get("allow_embed") or d.get("private") or d.get("status") != "published":
        return None, "not embeddable"
    return f"https://geo.dailymotion.com/player.html?video={vid}", "ok"


def yt(path, **q):
    q["key"] = KEY
    return getj("https://www.googleapis.com/youtube/v3/" + path + "?" + urllib.parse.urlencode(q))


def yt_channel_id(url):
    p = urllib.parse.urlparse(url)
    parts = [x for x in p.path.split("/") if x]
    if parts and parts[0] == "channel" and len(parts) > 1:
        return parts[1]
    if parts and parts[0].startswith("@"):
        d = yt("channels", part="id", forHandle=parts[0])
    elif parts and parts[0] == "user" and len(parts) > 1:
        d = yt("channels", part="id", forUsername=parts[1])
    else:
        name = parts[1] if parts and parts[0] == "c" and len(parts) > 1 else (parts[0] if parts else "")
        d = yt("channels", part="id", forHandle="@" + name)  # most legacy /c/ names became handles
    items = d.get("items") or []
    return items[0]["id"] if items else None


def youtube(url):
    if not KEY:
        return None, "skipped: no YT_API_KEY"
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    if "v" in q:
        ids = q["v"][:1]
        ch = None
    else:
        ch = yt_channel_id(url)
        if not ch:
            return None, "channel not found"
        uploads = "UU" + ch[2:]
        d = yt("playlistItems", part="contentDetails", playlistId=uploads, maxResults=15)
        ids = [i["contentDetails"]["videoId"] for i in d.get("items") or []]
        if not ids:
            return None, "no uploads"
    d = yt("videos", part="snippet,status,liveStreamingDetails", id=",".join(ids))
    for v in d.get("items") or []:
        live = v.get("liveStreamingDetails") or {}
        st = v.get("status") or {}
        if v["snippet"].get("liveBroadcastContent") == "live" and live.get("actualStartTime") and not live.get("actualEndTime"):
            if not st.get("embeddable") or st.get("privacyStatus") != "public":
                return None, "live but not embeddable"
            ch = ch or v["snippet"]["channelId"]
            return f"https://www.youtube.com/embed/live_stream?channel={ch}", "ok"
    return None, "not live"


def main():
    allent = list(entries(get(PLAYLIST, 60)))
    cand = [(n, g, u) for n, g, u in allent if not g.upper().startswith("VOD") and
            re.search(r"(youtube\.com|youtu\.be|dailymotion\.com)", urllib.parse.urlparse(u).netloc)]

    def run(e):
        n, g, u = e
        try:
            r = dailymotion(u) if "dailymotion" in u else youtube(u)
        except Exception as x:
            r = (None, f"error {type(x).__name__}")
        return {"name": n, "group": g, "source_url": u, "kind": "dailymotion" if "dailymotion" in u else "youtube",
                "embed_url": r[0], "result": r[1]}

    with cf.ThreadPoolExecutor(8) as ex:
        res = list(ex.map(run, cand))
    now = int(time.time())
    ok = [{**{k: r[k] for k in ("name", "group", "source_url", "embed_url", "kind")}, "verified_epoch": now} for r in res if r["embed_url"]]
    stats = {}
    for r in res:
        stats[r["kind"] + ": " + r["result"]] = stats.get(r["kind"] + ": " + r["result"], 0) + 1
    print(len(cand), "embed candidates,", len(ok), "verified", stats, file=sys.stderr)
    dest = ROOT / "app/src/main/assets/verified_extra.json"
    data = json.loads(dest.read_text())
    src = data["sources"]["freetv"]
    if not KEY:
        # Keep YouTube entries a keyed run verified recently rather than dropping them for lack of a key.
        # Only for 14 days: past that an unchecked live channel is no longer "verified".
        ok += [e for e in src.get("embeds", []) if e["kind"] == "youtube" and now - e.get("verified_epoch", 0) < 14 * 86400]
    src["embeds"] = ok
    src["embed_results"] = stats
    dest.write_text(json.dumps(data, indent=1))
    rep = ROOT / "docs/freetv/embeds-report.json"
    rep.write_text(json.dumps(res, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
