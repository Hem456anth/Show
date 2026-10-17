#!/usr/bin/env bash
# Device audit for Show (docs/AUDIT_1.5.3.md). Runs on an emulator or a USB device with adb.
#   tools/device_audit.sh <apk> <out-dir> [package]
# Collects: cold start, frames rendered while Home sits idle (should be ~0), scroll jank, CPU,
# memory across player open/close cycles, wake locks and CPU while the player is in the background
# (screen off), audio focus, crashes/ANRs, and screenshots of every tab plus the player.
set -u
APK="$1"; OUT="$2"; PKG="${3:-app.show.tv}"
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/steps.log"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
ticks() { local p; p=$(pid); [ -n "$p" ] && adb shell cat /proc/$p/stat 2>/dev/null | awk '{print $14+$15}' || echo 0; }

# Tap the first node whose text or content-desc equals $1 (Compose exposes both to uiautomator).
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/.ui.xml" 2>/dev/null
  local xy
  xy=$(python3 - "$1" "$OUT/.ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1], sys.argv[2]
try: xml = open(path, encoding="utf-8", errors="ignore").read()
except Exception: sys.exit(0)
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; log "tap '$1' at $xy"; return 0; fi
  log "tap '$1': not found"; return 1
}

adb shell getprop ro.build.version.sdk > "$OUT/sdk.txt"
adb shell wm size > "$OUT/display.txt"
adb shell dumpsys battery unplug >/dev/null 2>&1 || true
adb shell dumpsys batterystats --reset >/dev/null 2>&1 || true
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
adb shell dumpsys package "$PKG" | grep -E "permission|granted=true" > "$OUT/permissions.txt" || true
adb logcat -c

log "cold start"
adb shell am start -W -n "$PKG/app.show.tv.MainActivity" > "$OUT/launch.txt" 2>&1
sleep 50   # playlist download + enrichment passes
shot 01-home
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo-start.txt"

log "idle Home 20 s"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
T0=$(ticks); sleep 20; T1=$(ticks)
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfx-idle.txt"
echo "$((T1 - T0))" > "$OUT/cpu-ticks-idle-20s.txt"

log "scroll Home"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in 1 2 3 4 5 6; do adb shell input swipe 540 1800 540 700 250; sleep 0.7; done
shot 02-home-scrolled
for i in 1 2 3 4 5 6; do adb shell input swipe 540 700 540 1800 250; sleep 0.7; done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfx-scroll.txt"

tap "Live TV" && sleep 4 && shot 03-live-tv
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in 1 2 3 4 5 6; do adb shell input swipe 540 1800 540 600 200; sleep 0.5; done
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfx-livetv-scroll.txt"
tap "Search" && sleep 3 && adb shell input text "zee" && sleep 3 && shot 04-search
adb shell input keyevent KEYCODE_BACK; sleep 1
tap "My List" && sleep 3 && shot 05-my-list
tap "Home" && sleep 3
tap "Settings" && sleep 3 && shot 06-settings && adb shell input keyevent KEYCODE_BACK && sleep 2

log "player cycles (leak / memory growth check)"
for cyc in 1 2 3; do
  tap "Play" || tap "Home"
  sleep 25
  [ "$cyc" = 1 ] && { shot 07-player; adb shell dumpsys power | grep -iE "wake lock|PARTIAL|SCREEN|WIFI" > "$OUT/power-playing.txt"; adb shell dumpsys audio | grep -iE "focus|app.show" | head -40 > "$OUT/audio-focus-playing.txt"; }
  adb shell input keyevent KEYCODE_BACK; sleep 5
done
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo-after-cycles.txt"

log "background with the player open (screen off, no PiP)"
tap "Play"; sleep 20
adb shell input keyevent KEYCODE_SLEEP; sleep 5
T0=$(ticks); sleep 60; T1=$(ticks)
echo "$((T1 - T0))" > "$OUT/cpu-ticks-bg-60s.txt"
adb shell dumpsys power | grep -iE "wake lock|PARTIAL_WAKE|app.show|ExoPlayer" > "$OUT/power-background.txt"
adb shell dumpsys wifi | grep -iE "WifiLock|app.show|ExoPlayer" | head -20 > "$OUT/wifi-locks-background.txt" 2>/dev/null || true
adb shell dumpsys audio | grep -iE "focus|app.show" | head -40 > "$OUT/audio-background.txt"
adb shell input keyevent KEYCODE_WAKEUP; sleep 2; adb shell wm dismiss-keyguard >/dev/null 2>&1; adb shell input keyevent 82; sleep 15
shot 08-player-resumed
adb shell input keyevent KEYCODE_BACK; sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 30
T0=$(ticks); sleep 30; T1=$(ticks)
echo "$((T1 - T0))" > "$OUT/cpu-ticks-app-in-background-30s.txt"
adb shell dumpsys activity services "$PKG" > "$OUT/services.txt"
adb shell dumpsys jobscheduler | grep -i "$PKG" > "$OUT/jobs.txt" || true
adb shell dumpsys alarm | grep -i "$PKG" > "$OUT/alarms.txt" || true

adb shell dumpsys batterystats "$PKG" > "$OUT/batterystats.txt" 2>/dev/null || true
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null || true
adb logcat -d | grep -iE "ANR in|FATAL|StrictMode|Choreographer.*Skipped|LeakCanary|HeapAnalysis" > "$OUT/logcat-flags.txt" || true
adb shell dumpsys battery reset >/dev/null 2>&1 || true
rm -f "$OUT/.ui.xml"
log "done"
