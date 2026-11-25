#!/bin/sh
# Perfetto trace of 3 drawer open/fling/close cycles (drawer.cfg), then the launcher's main-thread frame times.
# Usage: tools/perf/trace.sh out.pftrace   (needs Perfetto's trace_processor on PATH: curl -LO https://get.perfetto.dev/trace_processor)
[ -n "$ANDROID_SERIAL" ] || adb devices | awk 'NR > 1 && NF { n++ } END { exit n > 1 }' || { echo "More than one device attached: set ANDROID_SERIAL (adb devices -l)" >&2; exit 1; }
D=$(dirname "$0")
adb push "$D/drawer.cfg" /data/local/tmp/drawer.cfg >/dev/null
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell 'cat /data/local/tmp/drawer.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/drawer.pftrace' >/dev/null 2>&1 &
sleep 2
for i in 1 2 3; do
  adb shell input swipe 540 2000 540 700 150; sleep 0.8
  adb shell input swipe 540 1700 540 500 80; sleep 0.8
  adb shell input swipe 540 500 540 1700 80; sleep 0.8
  adb shell input keyevent KEYCODE_BACK; sleep 0.8
done
wait
adb pull /data/misc/perfetto-traces/drawer.pftrace "$1" >/dev/null
trace_processor -q "$D/frames.sql" "$1" 2>/dev/null
