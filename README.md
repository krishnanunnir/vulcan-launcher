<div align="center">

<img src="docs/icon.svg" width="112" height="112" alt="Vulcan sunset logo">

# Vulcan

**A home screen for Android that does what Nova Launcher does, without ads, trackers or analytics.**<br>
It has no internet access at all, so nothing it does can leave your phone.

[![Latest release](https://img.shields.io/github/v/release/krishnanunnir/vulcan-launcher?label=release&color=E8721C)](https://github.com/krishnanunnir/vulcan-launcher/releases/latest)
[![Android 8+](https://img.shields.io/badge/Android-8%2B-4E8F3A?logo=android&logoColor=white)](#install)
[![No internet permission](https://img.shields.io/badge/internet%20permission-none-2A2A28)](#privacy-and-permissions)
[![License: MIT](https://img.shields.io/github/license/krishnanunnir/vulcan-launcher?color=595752)](LICENSE)

<a href="https://github.com/krishnanunnir/vulcan-launcher/releases/latest/download/vulcan-launcher.apk"><img src="https://img.shields.io/badge/Download%20APK-E8721C?style=for-the-badge&logo=android&logoColor=white" height="44" alt="Download the latest Vulcan APK"></a>
&nbsp;
<a href="https://github.com/krishnanunnir/vulcan-launcher/releases"><img src="https://img.shields.io/badge/All%20releases-2A2A28?style=for-the-badge&logo=github&logoColor=white" height="44" alt="All Vulcan releases on GitHub"></a>

<img src="docs/screenshots/home.jpg" width="19%" alt="Home screen: the sunset launcher shortcut, mail and photos, five dock apps, and the search bar over the dusk wallpaper">
<img src="docs/screenshots/menu.jpg" width="19%" alt="Long-press menu on the home screen: widgets, shortcuts, wallpaper, pages, lock and settings">
<img src="docs/screenshots/drawer.jpg" width="19%" alt="App drawer: an alphabetical app grid and search field on a warm-grey background">
<img src="docs/screenshots/settings.jpg" width="19%" alt="Settings: the sunset logo beside the No internet permission legend, above grouped layout and appearance options">
<img src="docs/screenshots/look.jpg" width="19%" alt="Look and feel settings: icon pack, shapes, toggles and the theme color">

</div>

## About

It looks like a well-made appliance rather than a phone skin: warm-grey panels (light or night), small printed labels, and one orange dot for whatever is live (the current page, the letter you're on). Your wallpaper and your apps' own icons are left alone. Every Nova-style option (icon packs, shapes, glass panels, wallpaper colours, AMOLED black, your own colours) is still in settings when you want it.

The wallpaper in the screenshots is [docs/wallpaper-dusk.jpg](docs/wallpaper-dusk.jpg), made for Vulcan. Use it freely.

## Features

- **Arrange your home screen your way:** grids up to 12 by 12, up to 9 pages, resizable widgets, and a dock you can hide or scroll.
- **Find apps fast:** an A to Z app drawer with search, an optional letter index, and tabs or folders to group apps. Hide the apps you don't want to see.
- **Use gestures:** swipe, double-tap or pinch on the home screen to open notifications, an app, the app drawer, the screen lock and more. Each app icon can have its own swipe-up action too.
- **Change the look:** icon packs, icon shapes, your own icons and labels, folder styles, and a night mode that follows the system or a schedule.
- **Long-press an app** for its shortcuts, notifications, app info or uninstall.
- **Swipe right on the first page** for the Now page: the date, music controls, upcoming events and favourite contacts.
- **Back up** your whole setup to a file and restore it later.

Long-press an empty spot on the home screen to open **Settings**.

## Install

Vulcan needs Android 8 or newer.

[Download the APK](https://github.com/krishnanunnir/vulcan-launcher/releases/latest/download/vulcan-launcher.apk), then:

1. Open it on your phone and tap **Install**. Android may first ask you to allow installs from your browser or file manager.
2. Press the **Home** button. Android asks which home app to use: pick **Vulcan**, then **Always**.
3. To go back to your old home screen at any time, open your phone's **Settings → Apps → Default apps → Home app**.

## Coming from Nova?

You can bring your Nova home screen with you:

1. In Nova, open **Nova Settings → Backup & import settings → Backup** and save the file.
2. In Vulcan, long-press an empty spot on the home screen, then tap **Settings → Backup & restore → Import a launcher backup (.novabackup)…** and pick that file.

Your apps, folders, dock, grid size and most shortcuts come over. Widgets have to be added again.

## Design your home screen with an AI assistant

A Vulcan backup is a plain JSON file that explains itself, so you can hand it to an AI assistant (or edit it yourself) and load the result:

1. **Settings → Backup & restore → Back up to a file…** and save it.
2. Give the file to an assistant with a request like "make a calm two-page layout: messaging and maps in the dock, a Work folder, monochrome labels".
3. Load the edited file with **Settings → Backup & restore → Restore from a file…**.

The file starts with `howTo` (how the grid, dock and folders work), and `reference` lists every setting with its allowed values, every gesture action, and the apps installed on your phone. The file also holds your drawer tabs and folders, hidden apps and renamed apps. Values Vulcan can't use are skipped and named when you restore. The assistant never needs to reach your phone: you move the file yourself.

## Compared with Nova

Nova Launcher changed owners in 2022 and again in 2026, and picked up ads, trackers and an activity log that uploads the networks, devices and apps you use. Vulcan covers the same features with no internet access, nothing recorded and public source code. [docs/nova-reference.md](docs/nova-reference.md) has what a static analysis of Nova found.

## What it won't do

Anything that needs the network: weather, news, Google Discover, web search suggestions, cloud backup, self-update. Searching the web hands your query to your browser instead. Importing from launchers other than Nova isn't possible either, since their layout databases aren't readable by other apps.

<details>
<summary>Known limitations</summary>

- Changing a setting restarts the launcher screen.
- App shortcuts only work while Vulcan is the default home app (an Android rule).
- "Close app" can only stop an app's background work (also an Android rule).
- Widgets whose setup screen hands off to another screen (Samsung's clock widgets) report "cancelled" at once, so Vulcan can't add them.
- A paused work profile hides its apps; the Private tab shows even when Android's "hide when locked" is on, since launchers can't read that setting.
- Items that have no room after the grid shrinks stay hidden until it grows again.
- In right-to-left languages, grids, menus and settings are mirrored, but pages still run left to right.

</details>

## Privacy and permissions

Vulcan has no internet permission, so it can't send anything anywhere. There are no ads, analytics or crash reports. Anyone can check the permission list of the APK.

Most permissions below stay **off until you turn on the feature that needs them**, and Vulcan says why when it asks.

<details>
<summary>Every permission Vulcan can ask for, and why</summary>

| What it's for | When it's used | Android permission |
|---|---|---|
| Opening the notification shade or quick settings with a gesture (swipe down, by default). | Always available. Granted at install. | `EXPAND_STATUS_BAR` |
| **Uninstall** in an app's long-press menu. It only opens Android's own uninstall prompt, which you confirm. | Always available. Granted at install. | `REQUEST_DELETE_PACKAGES` |
| **Close app** in the long-press menu. Android only lets it stop an app's background work. | Always available. Granted at install. | `KILL_BACKGROUND_PROCESSES` |
| Notification dots and counts on icons, notifications in the long-press menu, and music controls on the Now page. Notifications are counted in memory and never saved. | **Off** until you turn it on in Settings → Permissions. | Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) |
| Showing Private Space (Android 15+) in its own drawer tab, and locking or unlocking it. Android only lists it to your default home app, and unlocking always asks for your screen lock. | Always available. Granted at install. | `ACCESS_HIDDEN_PROFILES` |
| Searching your contacts from the app drawer, and favourite contacts on the Now page. | Asked only when you turn one of those on. | `READ_CONTACTS` |
| Upcoming events on the Now page. | Asked only when you turn it on. | `READ_CALENDAR` |
| "Sunset to sunrise" night mode. It uses the last location your phone already knows and works out the sun times on the phone. | Asked only when you pick that mode. | `ACCESS_COARSE_LOCATION` |
| The screen lock, recent apps and power menu gestures (Android offers no other way). It can't read anything on your screen. | **Off** until you turn it on. | Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent="false"`) |
| Hiding the status bar clock on the home screen. Apps can't give themselves this: you grant it once from a computer with `adb shell pm grant dev.vulcanlauncher android.permission.WRITE_SECURE_SETTINGS`. Vulcan only adds or removes the clock in the status bar. | Only if you grant it. Most people never need it. | `WRITE_SECURE_SETTINGS` |


</details>

Nothing else, and no `INTERNET`. App shortcuts need Vulcan to be your default home app, which is your choice rather than a permission. The `<queries>` entries in the manifest (launcher apps, icon packs, shortcut creators, voice and web search) only let Vulcan see those apps. Any new permission must be added to this table (see [CLAUDE.md](CLAUDE.md)).

## Building from source

Vulcan is written from scratch in Kotlin, with no libraries and no Nova code. [docs/BUILDING.md](docs/BUILDING.md) has the build steps, the on-device checks and the release steps; [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains how the code fits together.

## Translating

Every word Vulcan shows is in [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml) (English). To add a language, copy it to `values-<code>/strings.xml` (for example `values-de` for German) and translate the text between the tags. Leave the `name` attributes and placeholders such as `%1$s` and `%1$d` as they are; a string you leave out shows in English. Anything marked `translatable="false"` stays as it is. The unit tests check that every translated name and placeholder matches the English file.

The settings' stored values, your layout and backup files don't depend on the language, so switching languages changes nothing else. The explanation inside a backup file (`howTo`) stays in English; it's written for whoever edits the file.

## License

[MIT](LICENSE).
