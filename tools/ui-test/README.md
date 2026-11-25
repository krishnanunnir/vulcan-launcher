# Vulcan device UI sweep

Drives a real Android device over adb to exercise **every setting and every value** of the launcher's
`LOOKS` list, one factor at a time, and takes a screenshot per state. Records crashes and logcat
warnings, and is resumable: stop it any time and re-run.

## Files

| File | What it is |
| --- | --- |
| `gen_settings.py` | Parses `app/src/main/java/dev/vulcanlauncher/Looks.kt` (mirrors the `onOff`/`sizes`/`percent`/`color`/`range`/`listOf` constructors) into `settings.json`. Run it after changing `LOOKS`. |
| `settings.json` | The sweep table: 105 settings, 704 values. |
| `harness.py` | The sweep driver. |
| `montage.py` | Builds per-setting contact sheets from a finished run, for review. |
| `a11y.py` | Accessibility check of the current screen: clickable views with no label, touch targets under 48 dp. Exits 1 on a finding. |
| `restore.sh` | Puts a backed-up configuration (default: `backup-prefs/`) back on the device. |
| `out/`, `shots/`, `backup-prefs/`, `*.apk` | Local test artifacts, git-ignored. `backup-prefs/` holds a personal device backup. |

## Device setup (one-time)

The harness needs a **debuggable** build installed over the release build *without* losing data, plus
`run-as` for pref injection. Re-sign the debug APK with the release keystore so it carries the same
certificate as the installed release and `adb install -r` keeps `shared_prefs`:

```sh
./gradlew assembleDebug
apksigner sign --ks "$storeFile" --ks-pass "pass:$storePassword" --key-pass "pass:$keyPassword" \
  --ks-key-alias "$keyAlias" app/build/outputs/apk/debug/app-debug.apk
# keystore.properties: source it (set -a; . ./keystore.properties; set +a); build-tools and a JDK 21 on PATH
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Target device for the current run: Galaxy S23 (`SM-S911B`), 1080×2340 at 480 dpi, portrait.
The testbed layout references apps that must exist on the device (`CLOCK`, `CAMERA`, … at the top of
`harness.py`) — adjust those constants for another device.

## Running

```sh
python3 tools/ui-test/harness.py               # full sweep, ~700 states, ~4 s each
python3 tools/ui-test/harness.py --only cols   # a single setting (handy while iterating)
```

Per state the harness:

1. force-stops the launcher;
2. writes `shared_prefs/{layout,looks,data}.xml` through `run-as` (one factor at a time from a fixed
   testbed layout, plus per-setting `CONTEXT` overrides where the value only shows under some other
   setting, e.g. dock shadow needs a dock background);
3. cold-starts `MainActivity` and checks the process is alive;
4. runs a **probe** — `home`, `drawer`, `menu`, `settings`, `folder`, `popup`, `now`, `midswipe`,
   `page2`, `infinite`, `search`, `pullsearch`, `remember`, `launch`, `homekey`, `drag`, `dragwiggle`
   (see `PROBES` in `harness.py`). Taps find their target by text via `uiautomator dump`; gestures use
   `input swipe` / `input motionevent` / `input draganddrop`;
5. screenshots to `out/sweep1/<setting>__<value>/`;
6. greps the app's own logcat and appends a record to `out/sweep1/results.jsonl`.

With a phone and an emulator both attached, set `ANDROID_SERIAL=<serial>` (`adb devices -l`): the harness,
`restore.sh` and the `tools/perf` scripts stop at once otherwise.

**Your setup is put back.** Before the first state the harness copies the launcher's `shared_prefs/*.xml` and the
auto-rotate settings to `out/before/`, and puts them back when the sweep ends, fails or is stopped with Ctrl-C (or
`kill`, or a closed terminal), then deletes the copy. If it can't (the process was `kill -9`ed, the phone was
unplugged, the restore failed), `out/before/` stays, and the next run keeps it instead of saving the testbed over it.
To put it back by hand: `tools/ui-test/restore.sh tools/ui-test/out/before`, then delete `out/before/`. Not covered:
the launcher's own files (`files/`, such as its daily automatic backup, which may be taken of a testbed layout during
a sweep), and any `shared_prefs` file the sweep creates that wasn't there before stays.

States already in `results.jsonl` are skipped; crashed/errored entries are retried. `PROBE_FOR` maps
each setting to the probe(s) that surface it — everything else uses `home`.

## Reviewing

```sh
python3 tools/ui-test/montage.py    # -> out/sweep1/sheets/<setting>.png: all values side by side
```

Crashes are flagged in each tile's label; tiles whose probe missed show the screen where it stopped.

## Known limits

- Multi-finger gestures (pinch in/out, two-finger swipes, rotate) cannot be injected with
  `adb shell input`; those values are not exercised here.
- `input motionevent` cannot drive the system drag-and-drop — drags use `input draganddrop`.
- `--phase special` (fresh install, settings pages, dialogs, groups, hidden apps) and `--phase
  gestures` (gesture actions) are planned but not implemented yet.

## Status (2026-10-19)

- Mechanics verified on device: pref injection, cold start, screenshots, text taps, long-press,
  drag-and-drop.
- `glance` and `subgrid` states have been run (see `out/sweep1/results.jsonl`); the full sweep has not.
- Candidate finding from the dry runs: with `glance` on, the home glance clock/date stays visible
  behind the Now page's translucent panel, doubling the time and date text (clean with `glance` off).
