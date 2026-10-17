#!/usr/bin/env python3
"""
Live stream health probe for the iptv-org playlist.

Mirrors the app's first playback steps without decoding video:
  1. Parse the playlist the same way the app does (EXTINF attrs, EXTVLCOPT headers).
  2. For a random sample, request the stream URL with the channel's User-Agent / Referer.
  3. For HLS, follow a master playlist to its first variant and fetch the first segment's
     headers, which is where most "dead" IPTV channels actually fail.

Usage: python3 tools/probe_streams.py [--sample 60] [--seed 7] [--playlist URL]
Outputs a JSON summary on stdout. Standard library only.
"""
import argparse, json, random, re, ssl, sys, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

DEFAULT = "https://iptv-org.github.io/iptv/index.category.m3u"
UA = "Show/1.0.0 (Linux; Android) Media3"
ATTR = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')


def parse(text):
    out, pending, opts = [], None, {}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            body = line[8:]
            q, split = False, -1
            for i, c in enumerate(body):
                if c == '"': q = not q
                elif c == "," and not q: split = i; break
            attrs = dict((k.lower(), v) for k, v in ATTR.findall(body[:split] if split >= 0 else body))
            pending = (body[split + 1:].strip() if split >= 0 else "", attrs)
        elif line.startswith("#EXTVLCOPT:"):
            k, _, v = line[11:].partition("=")
            opts[k.strip().lower()] = v.strip()
        elif line and not line.startswith("#") and pending:
            name, attrs = pending
            out.append({"name": name, "url": line, "group": attrs.get("group-title", ""),
                        "ua": opts.get("http-user-agent") or attrs.get("http-user-agent"),
                        "ref": opts.get("http-referrer") or attrs.get("http-referrer")})
            pending, opts = None, {}
    return out


def fetch(url, ch, method="GET", limit=65536, timeout=8):
    headers = {"User-Agent": ch.get("ua") or UA}
    if ch.get("ref"): headers["Referer"] = ch["ref"]
    req = urllib.request.Request(url, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout, context=ssl.create_default_context()) as r:
        return r.status, r.geturl(), r.headers.get("Content-Type", ""), r.read(limit)


def probe(ch):
    t0 = time.time()
    url = ch["url"]
    if not url.startswith(("http://", "https://")):
        return {**ch, "result": "non-http", "ms": 0}
    try:
        status, final, ctype, body = fetch(url, ch)
        text = body.decode("utf-8", "replace")
        if "#EXTM3U" in text[:64]:
            lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
            if "#EXT-X-STREAM-INF" in text and lines:  # master -> first variant
                variant = urllib.parse.urljoin(final, lines[0])
                _, final, _, body = fetch(variant, ch)
                text = body.decode("utf-8", "replace")
                lines = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
            if lines:
                seg = urllib.parse.urljoin(final, lines[0])
                fetch(seg, ch, limit=2048)
            res = "ok-hls"
        else:
            res = "ok-other" if status == 200 else f"http-{status}"
    except urllib.error.HTTPError as e:
        res = f"http-{e.code}"
    except Exception as e:  # timeouts, DNS, TLS
        res = type(e).__name__
    return {**ch, "result": res, "ms": int((time.time() - t0) * 1000)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=60)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--playlist", default=DEFAULT)
    a = ap.parse_args()
    text = urllib.request.urlopen(urllib.request.Request(a.playlist, headers={"User-Agent": UA}), timeout=30).read().decode("utf-8", "replace")
    chans = parse(text)
    random.Random(a.seed).shuffle(chans)
    sample = chans[: a.sample]
    with ThreadPoolExecutor(12) as ex:
        results = list(ex.map(probe, sample))
    summary = {}
    for r in results: summary[r["result"]] = summary.get(r["result"], 0) + 1
    ok = sum(v for k, v in summary.items() if k.startswith("ok"))
    print(json.dumps({"playlist_channels": len(chans), "sampled": len(sample), "reachable": ok,
                      "reachable_pct": round(100 * ok / max(1, len(sample)), 1), "by_result": summary,
                      "examples_ok": [r["name"] for r in results if r["result"].startswith("ok")][:8]}, indent=2))


if __name__ == "__main__":
    sys.exit(main())
