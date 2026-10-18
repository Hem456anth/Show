#!/usr/bin/env python3
"""
Verify every channel of the extra (non iptv-org) sources before the app may use it. (v2, Show 1.5.4)

A link passes only if it delivers real, decodable-looking media that Media3 can play without DRM:

  HLS        playlist -> highest-bandwidth variant -> (EXT-X-MAP init) -> first segment, whose first
             bytes must carry a media signature (MPEG-TS sync, fMP4 box, ID3/ADTS/MP3, ...).
             SAMPLE-AES / FairPlay / Widevine keys are rejected (Show has no DRM); AES-128 is fine.
  DASH       MPD -> first video (else audio) Representation -> SegmentTemplate initialization (or
             BaseURL / SegmentBase) bytes with an MP4 box signature. ContentProtection = DRM = rejected.
  Progressive the response body itself must start with a media signature.

v1 accepted any 200 response over 1000 bytes that was not an HLS playlist, so HTML pages
("https://lcp.fr/le-live-...", "https://citizentv.co.ke/live") and DASH manifests passed unchecked.

Transient failures (timeouts, connection errors, 5xx, 429) are retried up to --attempts times, the
way Free-TV's own check_channels.py separates "flaky" from "dead". Each failure is classified:
  dead     404/410/DNS failure or not media: safe to drop
  blocked  401/403/451: usually geo-restricted (Free-TV marks these Ⓖ); dropped, as they can't be verified here
  flaky    timed out / 5xx on every attempt
Only "ok" URLs are written to app/src/main/assets/verified_extra.json (the app's allowlist).
The full per-URL report goes to docs/freetv/verify-report.json.

Usage: python3 tools/verify_extra_sources.py [--workers 32] [--attempts 3] [--limit N]
Run it from a network close to your viewers; geo-blocked channels fail from elsewhere and are
then (correctly) left out.
"""
import argparse, concurrent.futures as cf, datetime, json, re, subprocess, sys, time, urllib.parse, pathlib
import xml.etree.ElementTree as ET

SOURCES = {
    "freetv": "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
}
UA = "Show (Linux; Android) Media3"
SKIP_HOSTS = ("youtube.com", "youtu.be", "dailymotion.com", "twitch.tv", "facebook.com")
ROOT = pathlib.Path(__file__).resolve().parent.parent


class Fail(Exception):
    def __init__(self, kind, why):
        super().__init__(why)
        self.kind, self.why = kind, why


def curl(url, maxb=200_000, ua=UA, ref=None):
    if any(ord(c) < 0x21 for c in url) or not url.startswith(("http://", "https://")):
        raise Fail("dead", "bad url")
    args = ["curl", "-sS", "-L", "--max-redirs", "5", "--max-time", "15", "--connect-timeout", "8",
            "-A", ua, "-r", f"0-{maxb}", "-w", "\n__META__%{http_code} %{content_type} %{url_effective}"]
    if ref:
        args += ["-e", ref]
    p = subprocess.run(args + [url], capture_output=True)
    out = p.stdout
    i = out.rfind(b"\n__META__")
    if i < 0:
        raise Fail("flaky", f"curl {p.returncode}")
    meta = out[i + 9:].decode(errors="replace").split(" ", 2)
    code = meta[0]
    ctype = meta[1] if len(meta) > 1 else ""
    eff = meta[2] if len(meta) > 2 else url
    if code == "000":
        # 6 = DNS, 7 = refused: the host is gone; 28 = timeout, 35/56 = TLS/reset: transient
        raise Fail("dead" if p.returncode in (6,) else "flaky", f"curl {p.returncode}")
    if code in ("404", "410"):
        raise Fail("dead", f"http {code}")
    if code in ("401", "403", "451"):
        raise Fail("blocked", f"http {code}")
    if code not in ("200", "206"):
        raise Fail("flaky" if code.startswith("5") or code == "429" else "dead", f"http {code}")
    return eff, ctype.lower(), out[:i]


def media_kind(b):
    """Returns a label when the bytes start like a media container Media3 plays, else None."""
    if len(b) < 188:
        return None
    if b[0] == 0x47 and (len(b) < 377 or b[188] == 0x47):
        return "ts"
    if b[4:8] in (b"ftyp", b"styp", b"moof", b"sidx", b"moov", b"mdat", b"free", b"emsg", b"prft"):
        return "mp4"
    if b[:3] == b"ID3":
        return "id3"
    if b[0] == 0xFF and (b[1] & 0xF6) == 0xF0:
        return "adts"
    if b[0] == 0xFF and (b[1] & 0xE0) == 0xE0:
        return "mp3"
    if b[:3] == b"FLV":
        return "flv"
    if b[:4] == b"OggS":
        return "ogg"
    if b[:4] == b"\x1aE\xdf\xa3":
        return "mkv"
    if b[:4] == b"WEBVTT":
        return None
    return None


def check_hls(eff, text, ref):
    for _ in range(4):
        lines = text.splitlines()
        if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):
            best = max(((int(m.group(1)) if (m := re.search(r"BANDWIDTH=(\d+)", l)) else 0, lines[i + 1])
                        for i, l in enumerate(lines) if l.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines)), default=None)
            if not best:
                raise Fail("dead", "bad master")
            eff, _, body = curl(urllib.parse.urljoin(eff, best[1].strip()), ref=ref)
            text = body.decode("utf-8", "replace")
            if "#EXTM3U" not in text[:1024]:
                raise Fail("dead", "variant not a playlist")
            continue
        for l in lines:
            if l.startswith("#EXT-X-KEY") and "METHOD=NONE" not in l and "METHOD=AES-128" not in l:
                raise Fail("dead", "drm")
        mp = next((l for l in lines if l.startswith("#EXT-X-MAP")), None)
        if mp and (m := re.search(r'URI="([^"]+)"', mp)):
            _, _, init = curl(urllib.parse.urljoin(eff, m.group(1)), 4_000, ref=ref)
            if media_kind(init + b"\0" * 200) != "mp4":
                raise Fail("dead", "init not mp4")
        segs = [l.strip() for l in lines if l.strip() and not l.startswith("#")]
        if not segs:
            raise Fail("flaky", "no segments")
        encrypted = any(l.startswith("#EXT-X-KEY") and "AES-128" in l for l in lines)
        _, _, seg = curl(urllib.parse.urljoin(eff, segs[-1 if len(segs) < 3 else -3]), 40_000, ref=ref)
        if encrypted:
            if len(seg) < 1000:
                raise Fail("dead", "empty segment")
            return "hls-aes"
        k = media_kind(seg)
        if not k and not mp:
            raise Fail("dead", "segment not media")
        return "hls-" + (k or "fmp4")
    raise Fail("dead", "too deep")


def _ns(tag):
    return tag.split("}", 1)[-1]


def check_dash(eff, text, ref):
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        raise Fail("dead", "bad mpd")
    for el in root.iter():
        if _ns(el.tag) == "ContentProtection":
            raise Fail("dead", "drm")
    period = next((e for e in root.iter() if _ns(e.tag) == "Period"), None)
    if period is None:
        raise Fail("dead", "no period")

    def base(el, cur):
        b = next((c for c in el if _ns(c.tag) == "BaseURL"), None)
        return urllib.parse.urljoin(cur, b.text.strip()) if b is not None and b.text else cur

    cur = base(period, base(root, eff))
    sets = [a for a in period if _ns(a.tag) == "AdaptationSet"]
    def is_video(a):
        return "video" in (a.get("contentType", "") + a.get("mimeType", "") +
                           "".join(r.get("mimeType", "") for r in a if _ns(r.tag) == "Representation"))
    sets.sort(key=lambda a: 0 if is_video(a) else 1)
    for a in sets:
        reps = [r for r in a if _ns(r.tag) == "Representation"]
        if not reps:
            continue
        rep = min(reps, key=lambda r: int(r.get("bandwidth", "0") or 0))
        tmpl = next((c for c in rep if _ns(c.tag) == "SegmentTemplate"), None) or next((c for c in a if _ns(c.tag) == "SegmentTemplate"), None)
        url_base = base(rep, base(a, cur))
        if tmpl is not None and tmpl.get("initialization"):
            init = tmpl.get("initialization").replace("$RepresentationID$", rep.get("id", "")).replace("$Bandwidth$", rep.get("bandwidth", ""))
            _, _, b = curl(urllib.parse.urljoin(url_base, init), 4_000, ref=ref)
        elif url_base != cur or next((c for c in rep if _ns(c.tag) == "BaseURL"), None) is not None:
            _, _, b = curl(url_base, 4_000, ref=ref)
        else:
            continue
        if media_kind(b + b"\0" * 200) in ("mp4", "mkv"):
            return "dash"
        raise Fail("dead", "dash init not media")
    raise Fail("dead", "no usable representation")


def verify_once(url):
    eff, ctype, body = curl(url)
    head = body[:2048].lstrip()
    text = body.decode("utf-8", "replace")
    if "#EXTM3U" in text[:1024]:
        return check_hls(eff, text, None)
    if (head.startswith(b"<?xml") and b"<MPD" in body[:4096]) or head.startswith(b"<MPD"):
        _, _, full = curl(eff, 2_000_000)
        return check_dash(eff, full.decode("utf-8", "replace"), None)
    if head[:1] in (b"<", b"{", b"[") or "text/html" in ctype or "json" in ctype:
        raise Fail("dead", "web page, not a stream")
    k = media_kind(body)
    if not k:
        raise Fail("dead", "not media")
    return "progressive-" + k


def verify(url, attempts):
    last = None
    for n in range(attempts):
        try:
            return {"result": "ok", "format": verify_once(url), "attempts": n + 1}
        except Fail as f:
            last = f
            if f.kind != "flaky":
                break
            time.sleep(1.5 * (n + 1))
        except Exception as x:  # one broken entry must not stop the run
            last = Fail("flaky", f"error {type(x).__name__}")
    return {"result": last.kind, "why": last.why, "attempts": n + 1}


def entries(text):
    lines = text.splitlines()
    for i, l in enumerate(lines):
        if l.startswith("#EXTINF"):
            j = i + 1
            while j < len(lines) and lines[j].startswith("#"):
                j += 1
            if j < len(lines):
                group = (re.search(r'group-title="([^"]*)"', l) or [None, ""])[1]
                yield l.split(",", 1)[-1].strip(), group, lines[j].strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", type=int, default=32)
    ap.add_argument("--attempts", type=int, default=3)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--no-write", action="store_true")
    a = ap.parse_args()
    now = datetime.datetime.now(datetime.timezone.utc)
    out = {"verified_at": now.isoformat(timespec="seconds"), "verified_epoch": int(now.timestamp()), "verifier": 2, "sources": {}}
    report = {"verified_at": out["verified_at"], "sources": {}}
    for key, src in SOURCES.items():
        _, _, body = curl(src, 20_000_000)
        allent = list(entries(body.decode("utf-8", "replace")))
        cand = [(n, g, u) for n, g, u in allent
                if not g.upper().startswith("VOD") and u.startswith(("http://", "https://"))
                and not any(h in urllib.parse.urlparse(u).netloc for h in SKIP_HOSTS)]
        if a.limit:
            cand = cand[: a.limit]
        with cf.ThreadPoolExecutor(a.workers) as ex:
            results = list(ex.map(lambda e: (e, verify(e[2], a.attempts)), cand))
        ok = sorted({u for (n, g, u), r in results if r["result"] == "ok"})
        stats, fmts = {}, {}
        for _, r in results:
            stats[r["result"]] = stats.get(r["result"], 0) + 1
            if r["result"] == "ok":
                fmts[r["format"]] = fmts.get(r["format"], 0) + 1
        out["sources"][key] = {"url": src, "entries": len(allent), "candidates": len(cand), "verified": len(ok),
                               "results": stats, "formats": fmts, "urls": ok}
        report["sources"][key] = [{"name": n, "group": g, "url": u, **r} for (n, g, u), r in results]
        print(key, len(allent), "entries,", len(cand), "candidates,", len(ok), "verified", stats, fmts, file=sys.stderr)
    if a.no_write:
        return
    dest = ROOT / "app/src/main/assets/verified_extra.json"
    try:  # keep the embed section tools/verify_embeds.py maintains
        old = json.loads(dest.read_text())["sources"]
        for key, src in out["sources"].items():
            for k in ("embeds", "embed_results"):
                if k in old.get(key, {}):
                    src[k] = old[key][k]
    except Exception:
        pass
    dest.write_text(json.dumps(out, indent=1))
    rep = ROOT / "docs/freetv/verify-report.json"
    rep.parent.mkdir(parents=True, exist_ok=True)
    rep.write_text(json.dumps(report, indent=1, ensure_ascii=False))
    print("wrote", dest, "and", rep, file=sys.stderr)


if __name__ == "__main__":
    main()
