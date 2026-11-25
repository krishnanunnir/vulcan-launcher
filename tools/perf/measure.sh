#!/bin/sh
# Release-build checks on a phone over adb: cold start, single instance, drawer frame stats.
# Usage: tools/perf/measure.sh   (Vulcan installed as the default home, phone unlocked, portrait)
[ -n "$ANDROID_SERIAL" ] || adb devices | awk 'NR > 1 && NF { n++ } END { exit n > 1 }' || { echo "More than one device attached: set ANDROID_SERIAL (adb devices -l)" >&2; exit 1; }
P=dev.vulcanlauncher
sh() { adb shell "$@"; }

echo "-- cold start: HOME intent after a force-stop, 10 runs, TotalTime ms"
for i in 1 2 3 4 5 6 7 8 9 10; do
  sh am start -W -a android.settings.SETTINGS >/dev/null 2>&1; sleep 1
  sh am force-stop $P; sleep 1
  sh am start -W -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep TotalTime | tr -d '\r' | awk '{printf "%s ", $2}'
  sleep 2
done; echo

echo "-- tasks after a cold explicit start (another launcher's icon, Settings > Open), then Home: expect only type=home"
sh am start -W -a android.settings.SETTINGS >/dev/null 2>&1; sleep 1
sh am force-stop $P; sleep 1
sh am start -W -n $P/.MainActivity >/dev/null 2>&1; sleep 1
sh input keyevent KEYCODE_HOME; sleep 2
sh dumpsys activity activities | grep -E "Task\{.*vulcanlauncher" | grep -oE "#[0-9]+ type=[a-z]+" | sort -u | tr '\n' ' '; echo

echo "-- drawer: 10 x (swipe open, fling down, fling up, back)"
sh input keyevent KEYCODE_HOME; sleep 1
sh dumpsys gfxinfo $P reset >/dev/null
for i in 1 2 3 4 5 6 7 8 9 10; do
  sh input swipe 540 2000 540 700 150; sleep 0.8
  sh input swipe 540 1700 540 500 80; sleep 0.8
  sh input swipe 540 500 540 1700 80; sleep 0.8
  sh input keyevent KEYCODE_BACK; sleep 0.8
done
sh dumpsys gfxinfo $P | grep -E "Total frames rendered|Janky frames:|9[05]th percentile|99th percentile|Number Slow UI"
