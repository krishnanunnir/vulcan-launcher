# Nova Launcher reference (snapshot of 8.9.4)

These are notes from a static analysis of `nova-launcher-89400-8-9-4.apk` (Nova Launcher 8.9.4, package `com.teslacoilsw.launcher`, SHA-256 `cd97d8af43ce369fd12874a50077eba1c5f2157008eced681669b0669bcc8143`), done on 2026-10-18 with REA, JADX and apktool. Nothing was executed. This is a dated snapshot: later Nova versions may differ.

**Nova's code is proprietary. Do not copy code, resources or assets from it into this repo.** Use this file only to decide *what* to build. The APK and decompiled output live outside the repo.

## Backup format (for import only)

From strings in Nova 8.9.4's DEX files (static, not executed):
- A `.novabackup` is a zip containing `backup.db` and `shared_prefs/com.teslacoilsw.launcher_preferences.xml`.
- `backup.db` holds Launcher3-style tables: `favorites` (`_id, title, intent, container, screen, cellX REAL, cellY REAL, spanX REAL, spanY REAL, itemType, appWidgetId, profileId, rank, …`) and `workspaceScreens` (`_id, screenRank`).
  - `container`: -100 is the desktop, -101 the dock, and otherwise the `_id` of a folder.
  - `itemType`: 0 app, 1 shortcut, 2 folder, 4 widget, 6 deep shortcut.
  - Fractional cells mean subgrid positions.
- Grid preference keys: `desktop_grid_cols/rows`, `desktop_page_count`, `dock_grid_cols`, `dock_pages_count`, `drawer_app_grid_cols/rows`.

## Things to deliberately *not* replicate

What Nova 8.9.4 does, and why this project avoids each one:

- **Activity logger:**
  - What it records: Wi-Fi on/off and connected network (SSID), Bluetooth on/off and devices (name and MAC), every app or shortcut launched from the launcher, app usage, and package installs and removals.
  - Where it goes: uploaded every 8 hours to `api.novalauncher.com/stats/rawDataLog`.
  - Protection: identifiers are hashed with unsalted MurmurHash3, which is pseudonymous rather than anonymous.
  - When it runs: skipped only when the user is in Korea, judged by an IP lookup. No user toggle was found.
- **Ads:** mediation configured from the server (Pangle, Vungle, Moloco, Fyber, Meta, AdMob). Ads are suppressed for Prime users and for users who have limited ad tracking.
- **Analytics:** Firebase, Sentry, Amplitude, TikTok, Branch.
- **Instabridge AI:** context collectors for contacts, calendar, location, apps, Wi-Fi and Bluetooth, synced to `ai.instabridge.com` when the user consents. The SMS and call-log collectors exist but the permissions aren't declared.
