#!/bin/sh
# Put a configuration backed up from the test device back and restart Vulcan.
# Usage: ./restore.sh [backup-dir]   (default: ./backup-prefs; expects shared_prefs/*.xml and files/*)
set -e
[ -n "$ANDROID_SERIAL" ] || adb devices | awk 'NR > 1 && NF { n++ } END { exit n > 1 }' || { echo "More than one device attached: set ANDROID_SERIAL (adb devices -l)" >&2; exit 1; }
PKG=dev.vulcanlauncher
DIR=${1:-$(cd "$(dirname "$0")" && pwd)/backup-prefs}
[ -d "$DIR/shared_prefs" ] || { echo "no shared_prefs in $DIR" >&2; exit 1; }
# The system restarts the home app at once and its onCreate re-saves the old layout: let that land first,
# write atomically, then kill the process that cached the old prefs.
adb shell "am force-stop $PKG"; sleep 1.5
for f in "$DIR"/shared_prefs/*.xml; do
    p=/data/data/$PKG/shared_prefs/$(basename "$f")
    adb shell "run-as $PKG sh -c 'cat > $p.tmp && chmod 660 $p.tmp && mv $p.tmp $p'" < "$f"
done
for f in "$DIR"/files/*; do
    [ -e "$f" ] || continue
    name=$(basename "$f")
    adb shell "run-as $PKG sh -c 'cat > /data/data/$PKG/files/$name'" < "$f"
done
adb shell "am force-stop $PKG"; sleep 1.5
adb shell "am start -n $PKG/.MainActivity" >/dev/null
echo "Restored $DIR and restarted Vulcan."
