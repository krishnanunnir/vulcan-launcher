#!/bin/bash
# Before a release: cold start x5, memory (idle home, drawer, background) and drawer scroll frame stats on a phone.
# Usage: ANDROID_SERIAL=<serial> tools/perf/release-check.sh <label>    then: tools/perf/summ.py .local/perf/*.txt
# Installs nothing. Release build as the default home, phone unlocked and charged, portrait; swipes assume ~1080x2340.
set -u
LABEL=${1:?usage: release-check.sh <label>}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL (adb devices -l): with no serial adb may pick an emulator}"
export ANDROID_SERIAL
DIR="$(cd "$(dirname "$0")/../.." && pwd)/.local/perf"; mkdir -p "$DIR"
PKG=dev.vulcanlauncher
OUT="$DIR/$LABEL.txt"

run() {
mem() {
  echo "== meminfo: $1 =="
  local m; m=$(adb shell dumpsys meminfo $PKG)
  echo "$m" | grep -E '^ *(Java Heap|Native Heap|Graphics|Code|TOTAL PSS):|^ *Views:' | sed 's/^ */  /'
  echo "  -- graphics detail (meminfo Pss/PrivDirty/PrivClean/SwapPss/Rss) --"
  echo "$m" | grep -E '^ *(EGL mtrack|GL mtrack|Gfx dev|Other mtrack) ' | sed 's/^ */    /'
  echo "  -- graphics detail (gfxinfo memory) --"
  # from "Pipeline=" to the gralloc total, minus the per-buffer plane lines
  adb shell dumpsys gfxinfo $PKG | sed -n '/^Pipeline=/,/^Total imported by gralloc/p' | grep -vE '^[[:space:]]*planes:|^\+ name:' | sed 's/^/    /'
  adb shell dumpsys gfxinfo $PKG | grep -E 'views, .* of render nodes|^Total RenderNode' | sed 's/^ */    /'
}

echo "label: $LABEL  date: $(date '+%F %T')  serial: $ANDROID_SERIAL"
adb shell dumpsys package $PKG | grep -E 'versionName|versionCode' | sed 's/^ */  /'

echo "== a. cold start x5 =="
adb logcat -c
for i in 1 2 3 4 5; do
  adb shell am force-stop $PKG
  sleep 6
  # Android restarts the process (often for BadgeService) but does not always relaunch the activity: then press HOME
  n=$(adb logcat -d | grep -c "Displayed $PKG/.MainActivity")
  [ "$n" -ge "$i" ] || { echo "  run $i: no Displayed line after 6 s, pressing HOME"; adb shell input keyevent KEYCODE_HOME; sleep 4; }
done
adb logcat -d | grep -E "Displayed $PKG/.MainActivity|Start proc [0-9]+:$PKG/" | sed 's/^/  /'

echo "== b. idle home =="
adb shell input keyevent KEYCODE_HOME
sleep 20
mem "idle home #1"
sleep 5
mem "idle home #2"

echo "== c. open drawer =="
drawer_open() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q 'Search apps'; }
for try in 1 2 3; do
  adb shell input swipe 540 1500 540 400 200
  sleep 2
  drawer_open && break
  echo "  drawer not open after swipe $try, pressing HOME and retrying"
  adb shell input keyevent KEYCODE_HOME; sleep 2
done
drawer_open && echo "  drawer open: yes" || echo "  drawer open: NO (frame stats below are not drawer scrolling)"
adb exec-out screencap -p > "$DIR/$LABEL-drawer.png"
echo "  screenshot: $DIR/$LABEL-drawer.png"
mem "drawer open, not scrolled"

echo "== d. drawer scroll frame stats =="
adb shell dumpsys gfxinfo $PKG reset >/dev/null
# 55 apps scroll only ~4 rows, so one down-drag reaches the top and a second closes the drawer: alternate up/down
for i in 1 2 3 4; do
  adb shell input swipe 540 1700 540 800 300; sleep 1
  adb shell input swipe 540 1000 540 1600 300; sleep 1
done
adb shell dumpsys gfxinfo $PKG | grep -E 'Total frames rendered|Janky frames|(50|90|95|99)th percentile' | head -7 | sed 's/^ */  /'
adb exec-out screencap -p > "$DIR/$LABEL-after-scroll.png"
drawer_open && echo "  drawer still open after scroll: yes" || echo "  drawer still open after scroll: NO"

mem "after drawer"

echo "== f. background =="
adb shell input keyevent KEYCODE_HOME
adb shell am start -n com.android.settings/.Settings >/dev/null
sleep 25
mem "in background"
adb shell input keyevent KEYCODE_HOME
}

run 2>&1 | tee "$OUT"
