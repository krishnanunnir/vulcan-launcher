#!/bin/sh
# Debug-build check (am dumpheap needs a debuggable app): recreates the launcher 10 times by flipping night mode,
# forces a GC with a heap dump and prints the Activity count, which should be 1 or 2 (one may still be finishing).
# Usage: tools/perf/leak.sh [heap.hprof]   (keeps the dump for `shark-cli --hprof heap.hprof analyze` when named)
[ -n "$ANDROID_SERIAL" ] || adb devices | awk 'NR > 1 && NF { n++ } END { exit n > 1 }' || { echo "More than one device attached: set ANDROID_SERIAL (adb devices -l)" >&2; exit 1; }
P=dev.vulcanlauncher
adb shell input keyevent KEYCODE_HOME; sleep 3
night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $3}')
for i in 1 2 3 4 5; do adb shell cmd uimode night no >/dev/null; sleep 2; adb shell cmd uimode night yes >/dev/null; sleep 2; done
adb shell cmd uimode night "$night" >/dev/null; sleep 2
adb shell am dumpheap $P /data/local/tmp/leak.hprof >/dev/null || exit 1; sleep 6
[ -n "$1" ] && adb pull /data/local/tmp/leak.hprof "$1" >/dev/null
adb shell rm -f /data/local/tmp/leak.hprof
adb shell dumpsys meminfo $P | grep -E "TOTAL PSS|Activities" | tr -s ' '
