# Building Vulcan

Requirements (macOS, Homebrew):

```sh
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

Gradle runs on the JDK in `JAVA_HOME`. Releases are built with JDK 21, the JDK on F-Droid's build server; F-Droid rebuilds each tag and compares it with the published APK, so use JDK 21 for releases. Create `local.properties` (it is git-ignored):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Then:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Press Home on the phone and pick **Vulcan**.

On-device gesture check (13 gestures with real multi-touch events) and Nova-import check:

```sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.vulcanlauncher.test/dev.vulcanlauncher.DeviceCheck
```

Scenario flows in `flows/`, run with [Maestro](https://maestro.mobile.dev) (`brew install mobile-dev-inc/tap/maestro`). Install the debug APK first. The flows match English labels. Maestro sends analytics unless the variable below is set.

Flows tagged `edits` (removing and renaming icons, lock layout, pages, widget picker, hiding, sorting, folders, tabs, drawer style, dock settings, app info) start from a wiped, fresh-install layout. They run on the emulators; on a phone you use, leave them out with `--exclude-tags edits`. The others never stop or wipe Vulcan.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 MAESTRO_CLI_NO_ANALYTICS=1
maestro test --exclude-tags edits flows/  # on your phone; add --device <serial> when several are connected
```

On the emulators instead (boots each in turn, installs the debug APK, runs the flows, shuts it down): `tools/emulators.sh flows [--show] [NAME…]`. `--show` opens the emulator window so you can watch.

Each flow in `flows/` is one scenario; shared steps live in `flows/common/` (not run on their own). Maestro can't drag (long-press then move) or send multi-touch, so moving icons, making folders by dropping and gestures stay manual or in DeviceCheck.

## Release build

`./gradlew assembleRelease` signs the APK when a git-ignored `keystore.properties` sits in the repo root:

```
storeFile=/absolute/path/to/vulcan-release.jks
storePassword=…
keyAlias=vulcan
keyPassword=…
```

Without it the release APK is built unsigned. Publish it as `vulcan-launcher.apk` on a GitHub release so the README's "latest" download link keeps working.

Commit the version bump and changelog, tag that commit, and then run `./gradlew clean assembleRelease` from the tagged checkout. AGP embeds the current Git revision in `META-INF/version-control-info.textproto`; building before committing embeds the previous revision and prevents F-Droid from reproducing the published APK. Verify a separate clean, unsigned build of the tag with `apksigcopier compare --unsigned <signed-apk> <unsigned-apk>` before uploading the APK and its `SHA256SUMS`.

Keep both dependenciesInfo options disabled in app/build.gradle.kts. AGP otherwise adds encrypted dependency metadata to the APK signing block, which F-Droid rejects. Run `fdroid scanner --exit-code <signed-apk>` before publishing.

## F-Droid

F-Droid builds each version tag itself and checks it against the published APK, so a release is only accepted if the steps above reproduce it. The recipe is [docs/fdroid/dev.vulcanlauncher.yml](fdroid/dev.vulcanlauncher.yml), and changes go through the fork [bizzaretor/fdroiddata](https://gitlab.com/bizzaretor/fdroiddata) (branch dev.vulcanlauncher): update the existing merge request rather than opening a new one. IzzyOnDroid isn't an option while its inclusion policy rejects AI-written app code.

## Logo exports

The approved sunset is shared by the app, settings and About, README, website, browser and Apple shortcuts, and store metadata. Its source is `app/src/main/res/drawable/ic_launcher_foreground.xml` with `ic_launcher_background.xml`. After editing those vectors, run `python3 tools/export-logo.py` (requires Pillow) to regenerate `docs/icon.svg`, `docs/icon.png`, `docs/favicon.ico`, `docs/apple-touch-icon.png`, and the Fastlane store icon together. The exports use the same central 72/108 crop as Android's adaptive icon; Android controls its native outer mask.

## Project docs

- [docs/ARCHITECTURE.md](ARCHITECTURE.md): how the code fits together.
- [docs/nova-reference.md](nova-reference.md): a snapshot of what static analysis of Nova Launcher 8.9.4 found, including its backup format. Used as a reference only; no Nova code is used here.

The contributor rules (no network, every permission justified in the README) are in [CLAUDE.md](../CLAUDE.md).
