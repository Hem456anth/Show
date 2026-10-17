#!/usr/bin/env python3
"""Live check of Show's official iptv-org sources.

For each playlist: download it, parse it the way Show does (one channel per stream URL, groups
merged), report entry/channel/group counts, then probe a random sample of streams for a
playable HLS/HTTP response. Usage: python3 tools/verify_sources.py [samples_per_source]
"""
import concurrent.futures as cf
import json
import random
import re
import sys
import time
import urllib.request

SOURCES = {
    "india": "https://iptv-org.github.io/iptv/countries/in.m3u",
    "global": "https://iptv-org.github.io/iptv/index.m3u",
    "countries": "https://iptv-org.github.io/iptv/index.country.m3u",
    "languages": "https://iptv-org.github.io/iptv/index.language.m3u",
    "categories": "https://iptv-org.github.io/iptv/index.category.m3u",
}
UA = "Show/1.3.0 (Android; Media3) source-verifier"
ATTR = re.compile(r'([\w-]+)="([^"]*)"')


def fetch(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read()


def url_key(u):
    u = u.strip()
    scheme, sep, rest = u.partition("://")
    if not sep:
        return u
    cut = min([i for i in (rest.find("/"), rest.find("?"), rest.find("#")) if i >= 0] or [len(rest)])
    host, tail = rest[:cut].lower(), rest[cut:]
    tail = "" if tail == "/" else (tail[:-1] if len(tail) > 1 and tail.endswith("/") else tail)
    return f"{scheme.lower()}://{host}{tail}"


def parse(text):
    chans, cur, entries = {}, None, 0
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            entries += 1
            attrs = dict(ATTR.findall(line))
            cur = (attrs, line.rsplit(",", 1)[-1].strip(), {})
        elif line.startswith("#EXTVLCOPT:") and cur:
            k, _, v = line[11:].partition("=")
            cur[2][k.strip().lower()] = v.strip()
        elif line and not line.startswith("#") and cur:
            key = url_key(line)
            groups = [g.strip() for g in cur[0].get("group-title", "").split(";") if g.strip()]
            if key in chans:
                chans[key]["groups"].update(groups)
            else:
                chans[key] = {"url": line, "name": cur[1], "groups": set(groups),
                              "ua": cur[2].get("http-user-agent"), "ref": cur[2].get("http-referrer")}
            cur = None
    return entries, chans


def probe(ch, timeout=8):
    headers = {"User-Agent": ch["ua"] or UA}
    if ch["ref"]:
        headers["Referer"] = ch["ref"]
    try:
        req = urllib.request.Request(ch["url"], headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            head = r.read(2048)
            ok = r.status < 400 and (b"#EXTM3U" in head or len(head) > 0)
            return "ok" if ok else f"http {r.status}"
    except urllib.error.HTTPError as e:
        return f"http {e.code}"
    except Exception as e:  # timeouts, DNS, TLS
        return type(e).__name__


def main():
    samples = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    random.seed(20261016)
    report = {}
    for sid, url in SOURCES.items():
        t0 = time.time()
        status, body = fetch(url)
        entries, chans = parse(body.decode("utf-8", "replace"))
        took = time.time() - t0
        pick = random.sample(list(chans.values()), min(samples, len(chans)))
        with cf.ThreadPoolExecutor(16) as ex:
            results = list(ex.map(probe, pick))
        ok = results.count("ok")
        groups = {g for c in chans.values() for g in c["groups"]}
        report[sid] = {"http": status, "bytes": len(body), "entries": entries, "channels": len(chans),
                       "duplicates_merged": entries - len(chans), "groups": len(groups),
                       "probed": len(pick), "reachable": ok, "seconds": round(took, 1),
                       "failures": {r: results.count(r) for r in set(results) if r != "ok"}}
        print(f"{sid:10s} HTTP {status}  {len(body)/1e6:5.2f} MB  {entries:6d} entries -> {len(chans):6d} channels "
              f"({entries - len(chans)} merged), {len(groups)} groups, reachable {ok}/{len(pick)}", flush=True)
    json.dump(report, open("source_report.json", "w"), indent=2)


if __name__ == "__main__":
    main()
