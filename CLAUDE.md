# CLAUDE.md

A from-scratch Android launcher aiming at Nova Launcher feature parity with **no network access**. Read `docs/ARCHITECTURE.md` before changing structure.

## Build and test

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
```

- APK: `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r <apk>`.
- The first build downloads Gradle, AGP and Kotlin (versions in `gradle/wrapper/gradle-wrapper.properties` and the build files). In the Claude Code sandbox, Java's network access times out (even though curl works), so run Gradle with the sandbox disabled when dependencies must be fetched.
- Emulators: `tools/emulators.sh setup` once, then `tools/emulators.sh check` after `assembleDebug`. `tools/emulators.sh flows` runs the Maestro scenarios in `flows/`. Run with the sandbox disabled.
- Before a release, on the phone with the release build as home: `ANDROID_SERIAL=<serial> tools/perf/release-check.sh <version>`, then `tools/perf/summ.py .local/perf/*.txt` to compare with earlier versions (0.32 on a Galaxy S23: cold start ~200 ms, drawer scroll 1.4% janky). A clear jump is a regression to find before shipping. `tools/perf/screens.py <version>` does the same for every screen and gesture (drawer, folders, menus, Now page, each Settings page). For a slow one, trace it with Perfetto (`.local/tools/trace_processor`).
- Stress, also before a release: `ANDROID_SERIAL=<serial> tools/stress/endurance.py <version> [rounds] [folder label]` loops the drawer, Now page, overview, a folder and app launches, and fails on a crash, a killed process or growing memory (about 100 s a round). `tools/stress/hostile-backups.py <backup.json>` makes broken copies of a backup to restore by hand; the ones the app accepts replace the layout, so try those on an emulator.
- To check permissions after a build: `/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/aapt2 dump permissions <apk>`.

## Hard rules

- **Never add the `INTERNET` permission or any networking, analytics, ads or crash-reporting library.** That's the project's reason to exist.
- Every permission must be listed and justified in the Permissions table in docs/PERMISSIONS.md. Prefer a permission the user explicitly turns on (accessibility, notification listener) over one granted silently.
- **Never copy code, resources or assets from Nova Launcher.** `docs/nova-reference.md` is a feature list only. The Nova APK and decompiled sources live outside this repo (`../nova-launcher-*.apk`) and must never be committed.
- No runtime dependencies (no AndroidX, no Compose) unless a feature truly cannot be done with the platform API. Discuss it before adding one. Test-only dependencies are fine.
- **Every new setting goes in the right place, in two spots.** Add it to `LOOKS` with the right group, and add its key to `SECTIONS` (both in `Looks.kt`), in the section of that group where it belongs. Put it next to the settings it relates to, not at the end. A setting missing from `SECTIONS` fails `LooksTest.sectionsCoverEverySetting`. Don't make that test pass by dropping it into whatever section is nearest.
- **Settings follow Nova 8.9.4's structure.** Before you place a new setting, check where Nova puts the same option, and use that category and section. Nova 8.9.4 is installed on the emulator; its settings screen is `com.teslacoilsw.launcher/.preferences.SettingsActivity`. Settings has two parts: Settings (Home screen with Dock inside it, App drawer, Folders, Search, Gestures & inputs, Now page, Badges) and Style (Look & feel, Night mode). Add a new category or section only when Nova has no home for the option, and say so in the commit message.
- **Settings must not grow without end.** "Calm by default" means fewer, better settings. Before adding one, ask whether a better default, or an existing setting with one more value, would do. A new setting should replace or merge an old one. `LooksTest.settingsStayWithinBudget` caps the total (`SETTINGS_BUDGET`, 119 as of 0.32) and each section at 8 rows. Raise the cap only when the user explicitly says yes, and say so in the commit message.

## Conventions

- Plain Views, built in Kotlin code (no XML layouts). Match the existing style: small files, `apply {}` builders, sizes in dp via `dp`.
- `pageLayout` in `MainActivity` is the single source of truth, changed only through `editLayout { … }` (see docs/ARCHITECTURE.md, Data flow).
- Anything shown over home is an `Overlay`, opened with `showOverlay` and closed with `closeOverlay` or its own `close()`; don't keep your own "is open" field. A panel that shows model data gives a `refresh`.
- Anything that changes home from outside a touch (HOME, BACK, a rebuild, a broadcast) calls `cutTouch()` first, so a finger still down can't carry on a gesture into the new state (docs/ARCHITECTURE.md, Who owns a touch).
- Deferred work against home's views goes through `later`, `afterLayout` or a `lifetime.token()` check, never a bare `post`/`postDelayed`, so a rebuild drops it. Keep an `onDetachedFromWindow` cancel only for a view that is re-parented without a rebuild (a widget frame).
- A new setting declares its `reload` level only when it's lower than `REBUILD` and home provably reads it at use time; pin it in `LooksTest.onlyUseTimeSettingsSkipTheRebuild`.
- User-facing text goes in `res/values/strings.xml` (English) and is read with `getString(R.string.…)`; counts use `<plurals>`. Settings keep English keys and values in `LOOKS` (stored, backed up) and take their titles and names from resources. Debug builds have the `en-XA` pseudo-locale (`adb shell cmd locale set-app-locales dev.vulcanlauncher --locales en-XA`): text that isn't accented is hardcoded.
- Mark deliberate shortcuts with a `// ponytail:` comment that names the limit and the upgrade path.
- Pure logic (geometry, parsing, settings mapping) goes in top-level functions with a JUnit test in `app/src/test`. View code isn't unit-tested.
- When finishing a user-visible feature, update the README (features), docs/GUIDE.md (how to, won't do) or docs/PERMISSIONS.md (permissions) if it changes them.
- `minSdk 26`, `targetSdk 36`, Java/Kotlin target 17.
