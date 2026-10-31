#!/bin/bash
# One Android emulator for checking the launcher: a Pixel 8 on API 35 (targetSdk). Apple silicon (arm64 image).
# To try another version or screen, add a line to AVDS (images: sdkmanager --list; screens: avdmanager list device -c).
#
#   tools/emulators.sh setup           download the images and create the AVDs (safe to re-run)
#   tools/emulators.sh start NAME      boot one in a window, e.g. vulcan-35-phone
#   tools/emulators.sh check [NAME…]   boot each (default: all) without a window, install the debug APK, make it the home
#                                      app, go home and open the drawer, screenshot to build/emulators/, report crashes
#   tools/emulators.sh flows [--show] [NAME…]  boot each (default: all), install the debug APK and run the Maestro
#                                      flows in flows/ on it (see docs/BUILDING.md); --show opens the emulator window
set -eu

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export ANDROID_SDK_ROOT=$ANDROID_HOME
EMULATOR=$ANDROID_HOME/emulator/emulator
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
OUT=$ROOT/build/emulators

# name  system image  hardware profile
AVDS="
vulcan-35-phone   system-images;android-35;google_apis;arm64-v8a  pixel_8
vulcan-30-phone   system-images;android-30;google_apis;arm64-v8a  pixel_4
"

setup() {
    yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
    # One package at a time, retried: the image downloads are large and a dropped connection fails the whole install.
    for p in emulator platform-tools $(echo "$AVDS" | awk 'NF {print $2}' | sort -u); do
        for try in 1 2 3; do
            sdkmanager --sdk_root="$ANDROID_HOME" --install "$p" 2>&1 | grep -qiE "warning: an error|error:" || break
            echo "$p: download failed (try $try)"
            [ "$try" -lt 3 ] || { echo "$p: giving up" >&2; return 1; }
        done
        echo "$p: installed"
    done
    echo "$AVDS" | while read -r name image device; do
        [ -n "$name" ] || continue
        if avdmanager list avd -c 2>/dev/null | grep -qx "$name"; then echo "$name: exists"; continue; fi
        echo no | avdmanager create avd -n "$name" -k "$image" -d "$device" >/dev/null && echo "$name: created"
    done
}

boot() { # name port [extra emulator flags…]
    local name=$1 port=$2; shift 2
    "$EMULATOR" -avd "$name" -port "$port" -no-audio -no-boot-anim -no-snapshot-save "$@" >"$OUT/$name.emulator.log" 2>&1 &
    local t=0
    until [ "$(adb -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        sleep 2; t=$((t + 2))
        [ "$t" -lt 600 ] || { echo "   $name didn't boot in 10 minutes; see $OUT/$name.emulator.log"; return 1; }
    done
}

check_one() {
    local name=$1 port=5560 s=emulator-5560 failed=0
    echo "== $name"
    boot "$name" "$port" -no-window || { adb -s "$s" emu kill >/dev/null 2>&1; return 1; }
    adb -s "$s" install -r "$APK" >/dev/null
    adb -s "$s" shell cmd package set-home-activity dev.vulcanlauncher/.MainActivity >/dev/null
    adb -s "$s" shell input keyevent KEYCODE_WAKEUP
    adb -s "$s" shell wm dismiss-keyguard 2>/dev/null || adb -s "$s" shell input keyevent KEYCODE_MENU
    adb -s "$s" logcat -c
    adb -s "$s" shell input keyevent KEYCODE_HOME; sleep 6
    adb -s "$s" exec-out screencap -p >"$OUT/$name-home.png"
    read -r w h < <(adb -s "$s" shell wm size | tail -1 | sed 's/.*: //; s/x/ /')
    adb -s "$s" shell input swipe $((w / 2)) $((h * 4 / 5)) $((w / 2)) $((h / 5)) 300; sleep 3
    adb -s "$s" exec-out screencap -p >"$OUT/$name-drawer.png"
    adb -s "$s" shell input keyevent KEYCODE_BACK; sleep 2
    adb -s "$s" logcat -d -b crash >"$OUT/$name.crash.log"
    if grep -q dev.vulcanlauncher "$OUT/$name.crash.log"; then echo "   CRASH: see $OUT/$name.crash.log"; failed=1; fi
    if [ -z "$(adb -s "$s" shell pidof dev.vulcanlauncher | tr -d '\r')" ]; then echo "   NOT RUNNING after the check"; failed=1; fi
    [ "$failed" = 0 ] && echo "   ok: $OUT/$name-home.png, $OUT/$name-drawer.png"
    adb -s "$s" emu kill >/dev/null 2>&1 || true
    sleep 3
    return "$failed"
}

check() {
    [ -f "$APK" ] || { echo "No debug APK; run ./gradlew assembleDebug first" >&2; exit 1; }
    local names=("$@") bad=0
    [ ${#names[@]} -gt 0 ] || read -r -a names <<<"$(echo "$AVDS" | awk 'NF {print $1}' | tr '\n' ' ')"
    for n in "${names[@]}"; do check_one "$n" || bad=$((bad + 1)); done
    echo "$((${#names[@]} - bad))/${#names[@]} passed"
    [ "$bad" = 0 ]
}

flows_one() { # name [extra emulator flags…]
    local name=$1 port=5560 s=emulator-5560 failed=0; shift
    echo "== $name"
    boot "$name" "$port" "$@" || { adb -s "$s" emu kill >/dev/null 2>&1; return 1; }
    adb -s "$s" install -r "$APK" >/dev/null
    adb -s "$s" shell cmd package set-home-activity dev.vulcanlauncher/.MainActivity >/dev/null
    adb -s "$s" shell input keyevent KEYCODE_WAKEUP
    adb -s "$s" shell wm dismiss-keyguard 2>/dev/null || true
    MAESTRO_CLI_NO_ANALYTICS=1 maestro --device "$s" test -e VULCAN_HOME=true "$ROOT/flows" || failed=1
    adb -s "$s" emu kill >/dev/null 2>&1 || true
    sleep 3
    return "$failed"
}

flows() {
    [ -f "$APK" ] || { echo "No debug APK; run ./gradlew assembleDebug first" >&2; exit 1; }
    local flags=(-no-window) names=() bad=0
    [ "${1:-}" = --show ] && { flags=(); shift; }
    names=("$@")
    [ ${#names[@]} -gt 0 ] || read -r -a names <<<"$(echo "$AVDS" | awk 'NF {print $1}' | tr '\n' ' ')"
    for n in "${names[@]}"; do flows_one "$n" ${flags[@]+"${flags[@]}"} || bad=$((bad + 1)); done
    echo "$((${#names[@]} - bad))/${#names[@]} emulators passed"
    [ "$bad" = 0 ]
}

mkdir -p "$OUT"
case "${1:-}" in
    setup) setup ;;
    start) [ -n "${2:-}" ] || { echo "usage: $0 start NAME" >&2; exit 2; }; "$EMULATOR" -avd "$2" -no-snapshot-save & ;;
    check) shift; check "$@" ;;
    flows) shift; flows "$@" ;;
    *) sed -n '2,9p' "$0"; exit 2 ;;
esac
