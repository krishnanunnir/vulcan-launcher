# Architecture

Plain Android Views plus the Kotlin standard library. No AndroidX, no Compose, no third-party libraries at runtime; JUnit and org.json are used only for tests.

```
MainActivity root (FrameLayout, handles window insets itself)
├── content (LinearLayout, vertical)
│   ├── search bar (optional, top)
│   ├── glance strip (date and status, scrolls with the first page)
│   ├── Workspace ─ CellLayout × pages        home pages, paged sideways with a transition effect
│   ├── Indicator                              page dots / line
│   ├── dockBox ─ Workspace ─ CellLayout × dock pages (one row each)
│   └── search bar (optional, bottom)
├── Drawer.root                                overlay, GONE until opened
├── dropBar (Remove | App info | Uninstall)    shown only while dragging
└── overlay (folder window, desktop previews or widget resize frame), added on demand
```

## Where things live

One Kotlin file per area (home grid, drawer, settings, icons, theme, Now page, Nova import, and so on) named after it. `MainActivity` builds the screen and holds the items. Pure logic (grid geometry, gestures, page effects, parsing, colour) is kept in top-level functions so it can be unit-tested without a device.

Settings are declared once, as `Choice` entries in `LOOKS` (`Looks.kt`): stored keys and values are English identifiers, and titles come from string resources. The model and storage are in `Store.kt`, in three SharedPreferences files: `layout`, `looks` and `data`. The backup file (format 2) is a readable JSON with a `howTo` and a `reference` section; it's a user-facing contract, so older formats stay readable.

## Data flow

1. `items` in `MainActivity` is the single source of truth. Every change edits it, saves it, then re-renders. Only one activity edits it: a second one started outside the home task hands off to the home one and finishes.
2. Rendering rebuilds the views from `items`; widget views are kept and re-attached so widgets don't reload.
3. Apps that are missing for now (mid-update, disabled, on an unmounted SD card) are skipped, never deleted. Items are removed only when Android says the package or profile is gone for good.
4. Items that no longer fit a smaller grid, dock or page count stay stored and come back when it grows.
5. The layout file records its own format version and subgrid factor, so it reads correctly whatever the settings say. If it can't be read, the launcher falls back to the newest readable automatic backup; a single bad item is dropped on its own.
6. Changing a setting recreates the activity and reopens the same settings page. Every colour comes from one palette built at startup from the theme setting and night mode.

## Apps and profiles

Apps come from `LauncherApps` for every profile, so work-profile and Private Space apps are included. An app's key is package, class and user serial. A package callback reloads the list when apps change.

## Drag and drop

This uses the framework's `View.startDragAndDrop`. The payload says what's moving: a home item, an app from the drawer, an item out of a folder, or an app shortcut from the long-press menu. A drag that barely moves is a long-press and opens the menu instead. Dropping an app-like payload on another merges them into a folder; otherwise the drop needs free cells, unless widget overlap is on.

## Widgets

The flow is pick, bind (silently, or through Android's permission prompt), configure, then place. Cancelling at any step frees the widget ID, and so does startup for any ID no item holds. The widget mid-setup is saved, so its result still lands if the launcher is killed meanwhile. Resizing stays within the provider's limits.

## Tests

- **JVM unit tests** (`app/src/test`) cover all pure logic, plus a check that every translation's names and placeholders match English.
- **DeviceCheck** (`app/src/androidTest`) is a plain `Instrumentation` that sends real multi-touch events into the launcher window, for what can't be tested off-device (gestures, the real import path).
- **Maestro flows** (`flows/`) drive whole scenarios through the UI.
- **Device scripts** in `tools/` cover emulators, performance, leaks and accessibility checks.

[BUILDING.md](BUILDING.md) has the commands.

## Shortcuts taken on purpose

Simplifications are marked in the code with `ponytail:` comments. Each one says where it stops working and what the upgrade is. Find them with `grep -rn "ponytail:" app/src`.
