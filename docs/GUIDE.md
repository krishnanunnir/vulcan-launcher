# Vulcan guide

Everything the [README](../README.md) leaves out: the full feature list, installing, moving from Nova, sharing a style, and what Vulcan can't do.

## The look

It looks like a well-made appliance rather than a phone skin: warm-grey panels (light or night), small printed labels, and one orange dot for whatever is live (the current page, the letter you're on). Your wallpaper and your apps' own icons are left alone. Every Nova-style option (icon packs, shapes, glass panels, wallpaper colours, AMOLED black, your own colours) is still in settings when you want it.

The wallpaper in the screenshots is [docs/wallpaper-dusk.jpg](wallpaper-dusk.jpg), made for Vulcan. Use it freely.

## All features

- **Arrange your home screen your way:** grids up to 12 by 12, up to 9 pages, resizable widgets, and a dock you can hide or scroll. Drop a widget on one of the same size to stack them, then swipe sideways to flip through. Lock the layout to prevent accidental icon and page edits.
- **Find apps fast:** an A to Z app drawer with search, an optional letter index, and tabs or folders to group apps. Swipe sideways and the next tab slides in under your finger; drag tabs and folders into place, and hide any tab, including All apps and Work profile, without losing it. Tabs and folders each have their own page under App drawer › Apps. Show the tab bar as one scrolling row or as chips that wrap (the default). Slide a tab or folder left to delete it; it asks first, showing the apps inside. Suggest folders shows the folders it would make before you create them. Arrange the drawer and each tab in your own order, and pin favourites above the tabs, on every tab. Hide the apps you don't want to see.
- **Search does more:** it forgives typos ("whtsapp") and finds apps by initials ("gm" for Google Maps). It also finds app shortcuts ("new tab") and Android settings pages ("wifi"), works out sums ("12*3-4", tap to copy), and sets timers and alarms ("timer 5m", "alarm 7:30pm") in your clock app.
- **Use gestures:** swipe, double-tap or pinch on the home screen to open notifications, an app, the app drawer, the screen lock and more. Any of them can open an app shortcut, such as Direct dial to a contact or a new browser tab. Each app icon or folder can have its own swipe-up action too, and gestures can lock the layout or pause work apps.
- **Change the look:** icon packs, themed one-colour icons (Android 13+), icon shapes, your own icons (from a pack, another app or a photo, for apps and folders) and labels, folder styles, and a night mode that follows the system or a schedule.
- **Long-press an app** for its shortcuts, notifications, app info or uninstall.
- **Swipe right on the first page** for the Now page: the date, music controls, upcoming events and favourite contacts. Add cards of your own, a row of apps or a widget, with **Add a card** at the bottom; an apps card gets a name of your choice. Cards with nothing to show stay hidden. Hold a card to move it, or hold and let go to remove one of your own.
- **Back up** your whole setup to a file and restore it later.
- **Meet the contributors:** tap someone in Settings → Contributors to see their illustrated persona, contribution and personality. Share their character card through Android’s share sheet. Everything is generated on your device and needs no new permission.

## Install

Vulcan needs Android 8 or newer.

The easiest way is [F-Droid](https://f-droid.org/packages/dev.vulcanlauncher/): install it from there and it keeps Vulcan up to date. F-Droid can lag a few days behind new releases.

For updates as soon as they're released, add Vulcan to [Obtainium](https://github.com/ImranR98/Obtainium) ([one-tap link](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/krishnanunnir/vulcan-launcher)). It installs the APK straight from GitHub Releases. Pick one source and stay with it: Android may refuse to update an install from one source with an APK from another, and switching then means uninstalling. Back up your layout first (Settings → Backup & restore).

Or [download the APK](https://github.com/krishnanunnir/vulcan-launcher/releases/latest/download/vulcan-launcher.apk), then:

1. Open it on your phone and tap **Install**. Android may first ask you to allow installs from your browser or file manager.
2. Press the **Home** button. Android asks which home app to use: pick **Vulcan**, then **Always**.
3. To go back to your old home screen at any time, open your phone's **Settings → Apps → Default apps → Home app**.

## Tips

In **Pages**, tap **+** to add screens while keeping the previews open. Hold a preview and drag it onto **Remove page**;
the target and the dragged preview turn red before you release.

Long-press an empty spot on the home screen to open **Settings**.

## Coming from Nova

You can bring your Nova home screen with you:

1. In Nova, open **Nova Settings → Backup & import settings → Backup** and save the file.
2. The first time Vulcan opens, tap **Bring your Nova home screen** and pick that file. Later, it's in **Settings → Backup & restore → Import a launcher backup (.novabackup)…** (long-press an empty spot on the home screen for Settings).

Your apps, folders, dock, grid size, most shortcuts and widgets come over. Each widget is made new in its old spot: Android asks once whether Vulcan may add widgets, and widgets with options (a clock face, a note) ask for them again. A widget you skip stays as a "Tap to add" spot. Nova's own widgets can't come over.

## Share your style

To share your style, go to **Settings → Style → Share as a URL** and copy or send the link. It opens in [Vulcan Designer](https://vulcanlauncher.dev/design/) for a browser preview. To apply a link from the app or Designer, go to **Settings → Backup & restore → Import from URL**, paste the link, and review the settings before applying them offline. The link applies the supported settings it contains and preserves your apps, folders and widgets. Use a backup file to move a whole setup.

## Design your home screen with an AI assistant

A Vulcan backup is a plain JSON file that explains itself, so you can hand it to an AI assistant (or edit it yourself) and load the result:

1. **Settings → Backup & restore → Back up to a file…** and save it.
2. Give the file to an assistant with a request like "make a calm two-page layout: messaging and maps in the dock, a Work folder, monochrome labels".
3. Load the edited file with **Settings → Backup & restore → Restore from a file…**.

The file starts with `howTo` (how the grid, dock and folders work), and `reference` lists every setting with its allowed values, every gesture action, and the apps installed on your phone. The file also holds your drawer tabs and folders, hidden apps and renamed apps. Values Vulcan can't use are skipped and named when you restore. The assistant never needs to reach your phone: you move the file yourself.

## Compared with Nova

Nova Launcher changed owners in 2022 and again in 2026, and picked up ads, trackers and an activity log that uploads the networks, devices and apps you use. Vulcan covers the same features with no internet access, nothing recorded and public source code. [nova-reference.md](nova-reference.md) has what a static analysis of Nova found.

## What it won't do

Anything that needs the network: weather, news, Google Discover, web search suggestions, cloud backup, self-update. Searching the web hands your query to your browser instead. Importing from launchers other than Nova isn't possible either, since their layout databases aren't readable by other apps.

## Known limitations

- Changing a setting restarts the launcher screen.
- App shortcuts only work while Vulcan is the default home app (an Android rule).
- "Close app" can only stop an app's background work (also an Android rule).
- Widgets whose setup screen hands off to another screen (Samsung's clock widgets) report "cancelled" at once, so Vulcan can't add them.
- A paused work profile hides its apps; the Private tab shows even when Android's "hide when locked" is on, since launchers can't read that setting.
- Items that have no room after the grid shrinks stay hidden until it grows again.
- In right-to-left languages, grids, menus and settings are mirrored, but pages still run left to right.
