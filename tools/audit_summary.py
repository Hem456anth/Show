#!/usr/bin/env python3
"""Summarise tools/device_audit.sh output folders (one per API level x build) into a markdown table.

    tools/audit_summary.py <root> > summary.md

Each folder name is "<label>-api<N>". Also scans every screenshot for saturated blue pixels, the
"persistent blue dots" report: framework accents (teal/OEM blue) that bypass the black-and-white theme.
"""
import os, re, sys

try:
    from PIL import Image
except ImportError:  # summary still works without the blue scan
    Image = None


def read(p):
    try:
        return open(p, encoding="utf-8", errors="ignore").read()
    except OSError:
        return ""


def gfx(text):
    total = re.search(r"Total frames rendered: (\d+)", text)
    jank = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", text)
    p90 = re.search(r"90th percentile: (\d+)ms", text)
    return (int(total.group(1)) if total else None,
            float(jank.group(2)) if jank else None,
            int(p90.group(1)) if p90 else None)


def pss(text):
    m = re.search(r"TOTAL PSS:\s+(\d+)", text) or re.search(r"TOTAL\s+(\d+)", text)
    return int(m.group(1)) // 1024 if m else None


def blue(path):
    if Image is None or not os.path.exists(path):
        return None
    try:
        im = Image.open(path).convert("RGB")
    except Exception:  # empty or partial screencap
        return None
    w, h = im.size
    px = im.load()
    n = 0
    status = int(h * 0.04)  # skip the system status bar (emulator icons)
    nav = int(h * 0.96)
    for y in range(status, nav, 2):
        for x in range(0, w, 2):
            r, g, b = px[x, y]
            if b > 120 and b > r + 60 and b > g + 30:
                n += 1
    return n * 4


def main(root):
    rows = []
    for name in sorted(os.listdir(root)):
        d = os.path.join(root, name)
        if not os.path.isdir(d):
            continue
        launch = re.search(r"TotalTime: (\d+)", read(f"{d}/launch.txt"))
        idle = gfx(read(f"{d}/gfx-idle.txt"))
        scroll = gfx(read(f"{d}/gfx-scroll.txt"))
        live = gfx(read(f"{d}/gfx-livetv-scroll.txt"))
        bgpow = read(f"{d}/power-background.txt")
        locks = len(re.findall(r"(?i)PARTIAL_WAKE_LOCK.*(ExoPlayer|app\.show)", bgpow))
        audio_bg = read(f"{d}/audio-background.txt")
        crash = read(f"{d}/crash.txt")
        flags = read(f"{d}/logcat-flags.txt")
        shots = sorted(f for f in os.listdir(d) if f.endswith(".png"))
        blues = {s: blue(os.path.join(d, s)) for s in shots}
        rows.append(dict(
            name=name,
            launch=launch.group(1) if launch else "?",
            idle_frames=idle[0], scroll_jank=scroll[1], scroll_p90=scroll[2], live_jank=live[1],
            cpu_idle=read(f"{d}/cpu-ticks-idle-20s.txt").strip(),
            cpu_bg=read(f"{d}/cpu-ticks-bg-60s.txt").strip(),
            cpu_away=read(f"{d}/cpu-ticks-app-in-background-30s.txt").strip(),
            locks=locks,
            audio_bg="yes" if re.search(r"app\.show", audio_bg) else "no",
            pss0=pss(read(f"{d}/meminfo-start.txt")), pss1=pss(read(f"{d}/meminfo-after-cycles.txt")),
            crashes=len(re.findall(r"FATAL EXCEPTION", crash)), anrs=len(re.findall(r"ANR in", flags)),
            skipped=len(re.findall(r"Skipped \d+ frames", flags)),
            blue=sum(v for v in blues.values() if v) if any(blues.values()) else 0,
            blue_detail=", ".join(f"{k}:{v}" for k, v in blues.items() if v),
        ))
    print("| Build / API | Cold start ms | Idle frames (20 s) | Home scroll jank % (p90 ms) | Live TV jank % | CPU ticks idle 20 s | CPU ticks player bg 60 s | CPU ticks app away 30 s | App wake locks in bg | Audio focus in bg | PSS MB start→after 3 player cycles | Crashes | ANRs | Skipped-frame warnings | Blue px |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['name']} | {r['launch']} | {r['idle_frames']} | {r['scroll_jank']} ({r['scroll_p90']}) | {r['live_jank']} | {r['cpu_idle']} | {r['cpu_bg']} | {r['cpu_away']} | {r['locks']} | {r['audio_bg']} | {r['pss0']}→{r['pss1']} | {r['crashes']} | {r['anrs']} | {r['skipped']} | {r['blue']} |")
    detail = [r for r in rows if r["blue_detail"]]
    if detail:
        print("\nBlue pixels by screenshot:\n")
        for r in detail:
            print(f"- {r['name']}: {r['blue_detail']}")


if __name__ == "__main__":
    main(sys.argv[1])
