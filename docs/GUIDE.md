# Vulcan guide

Everything the [README](../README.md) leaves out: the full feature list, installing, moving from another launcher, sharing a style, and what Vulcan can't do.

## The look

It looks like a well-made appliance rather than a phone skin: warm-grey panels (light or night), small printed labels, and one orange dot for whatever is live (the current page, the letter you're on). Your wallpaper and your apps' own icons are left alone. Every Nova-style option (icon packs, shapes, glass panels, wallpaper colours, AMOLED black, your own colours) is still in settings when you want it.

The wallpaper in the screenshots is [docs/wallpaper-ridge.jpg](wallpaper-ridge.jpg). [Dusk](wallpaper-dusk.jpg), [Backwater](wallpaper-backwater.jpg), [Dunes](wallpaper-dunes.jpg), [Aurora](wallpaper-aurora.jpg), [Fluid](wallpaper-fluid.jpg) and [Topo](wallpaper-topo.jpg) are in the same folder. All were made for Vulcan. Use them freely.

## All features

- **Arrange your home screen your way:** grids up to 12 by 12, up to 9 pages, resizable widgets, and a dock with pages of its own that you can swipe through or hide. Pick how pages turn from transition effects such as Cube, Card stack and Zoom fade. Drop a widget on one of the same size to stack them, then swipe sideways to flip through. Hold a widget and choose **Resize**: it redraws as you drag the handles. **Settings → Home screen → Widget padding** sets the gap around widgets, down to none, and **Widget overlap when placing** lets widgets sit on top of each other. Lock the layout to prevent accidental icon and page edits.
- **Find apps fast:** an A to Z app drawer with search, an optional letter index, and tabs or folders to group apps. Show it as a vertical grid, a list or horizontal pages, with an optional row of your frequently used apps. Swipe sideways and the next tab slides in under your finger (round from the last to the first with Home screen › Infinite scroll); drag tabs and folders into place, and hide any tab, including All apps and Work profile, without losing it. Rename All apps and give it a colour of its own from its row on the Tabs page. Tabs and folders each have their own page under App drawer › Apps. Show the tab bar as one scrolling row or as chips that wrap (the default). Slide a tab or folder left to delete it; it asks first, showing the apps inside. Suggest folders shows the folders it would make before you create them. Arrange the drawer and each tab in your own order, and pin favourites above the tabs, on every tab. Hide the apps you don't want to see; turn on **Settings → Search → Include hidden apps** to still find them by name.
- **Search does more:** it forgives typos ("whtsapp") and finds apps by initials ("gm" for Google Maps). It also finds app shortcuts ("new tab") and Android settings pages ("wifi"), works out sums ("12*3-4", tap to copy) and conversions ("5 km in mi", "70 f in c"), sets timers and alarms ("timer 5m", "alarm 7:30pm") in your clock app, and offers to call or message a phone number or open a web address. With **Search contacts** on, it finds your contacts too. It finds your folders and drawer tabs, Vulcan's own settings ("icon pack"), and, with **Search calendar** on, your coming events. When no app matches, it offers to search your app store. Turn any of these off in **Settings → Search → Results**. The mic uses your voice search; on a phone without Google, a speech-to-text app (such as FUTO Voice Input) fills the search box instead.
- **Use gestures:** swipe, double-tap or pinch on the home screen to open notifications, an app, the app drawer, the screen lock and more. Two-finger swipes, two-finger rotation and a double-tap followed by a swipe are gestures too, and the actions include the flashlight, recent apps, quick settings and the power menu. Any of them can open an app shortcut, such as Direct dial to a contact or a new browser tab. Each app icon or folder can have its own swipe-up action too, and gestures can lock the layout or pause work apps.
- **Change the look:** icon packs, themed one-colour icons (Android 13+), icon shapes, your own icons (from a pack, another app or a photo, for apps and folders) and labels, folder styles and previews (Grid, Stack, Line, Fan or Radial), app launch animations, and a night mode that follows the system or a schedule. A folder can open with a swipe, so that a tap launches its first app. A photo icon follows the icon shape like any other; untick **Reshape icon** in its edit dialog to keep the picture as it is. An icon picked from a pack is shaped like that pack's other icons: set **Reshape legacy icons** to Always to fit them to the shape too.
- **See what's new:** notification badges on app icons, as dots, counts or the notification's own picture, with a position, shape, size and colour you choose. They need notification access.
- **Long-press an app** for its shortcuts, notifications, app info or uninstall.
- **Swipe right on the first page** for the Now page: the date, music controls, upcoming events and favourite contacts. Add cards of your own, a row of apps or a widget, with **Add a card** at the bottom; an apps card gets a name of your choice. Cards with nothing to show stay hidden. Hold a card to move it, or hold and let go to remove one of your own or resize a widget card: drag the handle under it.
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

## Coming from another launcher

You can bring your home screen with you from Nova, Lawnchair or Octopi:

1. Save a backup in your old launcher:
   - **Nova:** Nova Settings → Backup & import settings → Backup.
   - **Lawnchair:** Lawnchair settings → ⋮ → Create backup, with "Layout and settings" ticked.
   - **Octopi:** Octopi settings → General → Backup settings, then save a backup (an `OtpLauncher_….zip`).
2. The first time Vulcan opens, tap **Import from another launcher** and pick that file. Later, it's in **Settings → Backup & restore → Import from another launcher** (long-press an empty spot on the home screen for Settings).
3. Vulcan works out which launcher the file is from and shows what will come over before anything changes. Tap **Import** to replace your home screen; the old one is kept as an automatic backup.

Your apps, folders, dock, grid size (with half cells when the old layout used them), most shortcuts and widgets come over. Each widget is made new in its old spot: Android asks once whether Vulcan may add widgets, and widgets with options (a clock face, a note) ask for them again. A widget you skip stays as a "Tap to add" spot.

Your app drawer comes too: hidden apps, renamed apps, and from Nova its tabs and drawer folders (joined to a Vulcan tab of the same name). Gestures come over when Vulcan has the same action, along with the icon shape and the drawer's transparency, plus Nova's drawer search, keyboard and search bar placement.

What stays behind: the old launcher's own widgets and icons (Octopi's "open drawer" button, for one), apps that aren't on this phone, gestures Vulcan has no action for (Google Lens, for one), smart folders, icon packs, custom icons, themes and the wallpaper. From Octopi, drawer folders, folders in the dock and widget stacks don't come over either; from Lawnchair, work apps' drawer settings stay behind.

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

Anything that needs the network: weather, news, Google Discover, web search suggestions, cloud backup, self-update. Searching the web hands your query to your browser instead. Importing from a launcher other than Nova, Lawnchair or Octopi isn't possible either: a launcher's layout is private to it, so Vulcan can only read the backup files those three save.

## Known limitations

- Changing a setting restarts the launcher screen.
- App shortcuts only work while Vulcan is the default home app (an Android rule).
- "Close app" can only stop an app's background work (also an Android rule).
- Widgets whose setup screen hands off to another screen (Samsung's clock widgets) report "cancelled" at once, so Vulcan can't add them.
- A paused work profile greys out its apps; the Private tab shows even when Android's "hide when locked" is on, since launchers can't read that setting.
- Items that have no room after the grid shrinks stay hidden until it grows again.
- In right-to-left languages, grids, menus and settings are mirrored, but pages still run left to right.
