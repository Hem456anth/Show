#!/usr/bin/env python3
"""
Verify every channel of the extra (non iptv-org) sources before the app may use it.

For each entry: skip YouTube/Dailymotion pages (not playable by Media3) and VOD groups, then
fetch the playlist, follow the highest-bandwidth variant, and download the first bytes of the
first media segment. Only entries whose segment answers 200/206 with media bytes are written to
app/src/main/assets/verified_extra.json, which the app uses as an allowlist (by URL).

Usage: python3 tools/verify_extra_sources.py [--workers 24]
Run it from a network close to your viewers; geo-blocked channels fail from elsewhere and are
then (correctly) left out.
"""
import argparse, concurrent.futures as cf, datetime, json, re, subprocess, sys, urllib.parse, pathlib

SOURCES = {
    "freetv": "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
}
UA = "Show (Linux; Android) Media3"
SKIP_HOSTS = ("youtube.com", "youtu.be", "dailymotion.com", "twitch.tv", "facebook.com")


def curl(url, maxb=200_000):
    p = subprocess.run(["curl", "-sS", "-L", "--max-time", "12", "-A", UA, "-r", f"0-{maxb}",
                        "-w", "\n__META__%{http_code} %{url_effective}", url], capture_output=True)
    out = p.stdout
    i = out.rfind(b"\n__META__")
    if i < 0:
        return "000", url, b""
    code, _, eff = out[i + 9:].decode().partition(" ")
    return code, eff, out[:i]


def verify(url):
    if any(ord(c) < 0x21 for c in url):
        return "bad url"
    code, eff, body = curl(url)
    if code not in ("200", "206"):
        return f"http {code}"
    text = body.decode("utf-8", "replace")
    if "#EXTM3U" not in text[:1024]:
        # Progressive stream (TS/MP4/AAC): bytes are enough.
        return "ok" if len(body) > 1000 else "empty"
    for _ in range(3):
        lines = text.splitlines()
        if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):
            best = max(((int(m.group(1)) if (m := re.search(r"BANDWIDTH=(\d+)", l)) else 0, lines[i + 1])
                        for i, l in enumerate(lines) if l.startswith("#EXT-X-STREAM-INF") and i + 1 < len(lines)), default=None)
            if not best:
                return "bad master"
            nxt = urllib.parse.urljoin(eff, best[1].strip())
            if any(ord(c) < 0x21 for c in nxt):
                return "bad url"
            code, eff, body = curl(nxt)
            if code not in ("200", "206"):
                return f"variant {code}"
            text = body.decode("utf-8", "replace")
            continue
        segs = [l.strip() for l in lines if l.strip() and not l.startswith("#")]
        if not segs:
            return "no segments"
        nxt = urllib.parse.urljoin(eff, segs[0])
        if any(ord(c) < 0x21 for c in nxt):
            return "bad url"
        code, _, seg = curl(nxt, 40_000)
        return "ok" if code in ("200", "206") and len(seg) > 1000 else f"segment {code}"
    return "too deep"


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
    ap.add_argument("--workers", type=int, default=24)
    a = ap.parse_args()
    out = {"verified_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "sources": {}}
    for key, src in SOURCES.items():
        code, _, body = curl(src, 20_000_000)
        if code not in ("200", "206"):
            sys.exit(f"{key}: playlist http {code}")
        cand = [(n, g, u) for n, g, u in entries(body.decode("utf-8", "replace"))
                if not g.upper().startswith("VOD") and u.startswith(("http://", "https://"))
                and not any(h in urllib.parse.urlparse(u).netloc for h in SKIP_HOSTS)]
        with cf.ThreadPoolExecutor(a.workers) as ex:
            def safe(e):
                try:
                    return e, verify(e[2])
                except Exception as x:  # one broken entry must not stop the run
                    return e, f"error {type(x).__name__}"
            results = list(ex.map(safe, cand))
        ok = sorted({u for (n, g, u), r in results if r == "ok"})
        stats = {}
        for _, r in results:
            stats[r.split(" ")[0]] = stats.get(r.split(" ")[0], 0) + 1
        out["sources"][key] = {"url": src, "candidates": len(cand), "verified": len(ok), "results": stats, "urls": ok}
        print(key, len(cand), "candidates,", len(ok), "verified", stats, file=sys.stderr)
    dest = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/verified_extra.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(out, indent=1))
    print("wrote", dest, file=sys.stderr)


if __name__ == "__main__":
    main()
