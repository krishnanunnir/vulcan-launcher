# Privacy and permissions

Read the [privacy policy](privacy.html), also available offline in Settings → About → Privacy policy. Google Play preparation and submission steps are in [docs/play-store/RELEASE.md](play-store/RELEASE.md).

Vulcan has no internet permission, so it can't send anything anywhere. There are no ads, analytics or crash reports. Anyone can check the permission list of the APK.

Most permissions below stay **off until you turn on the feature that needs them**, and Vulcan says why when it asks.

| What it's for | When it's used | Android permission |
|---|---|---|
| Opening the notification shade or quick settings with a gesture (swipe down, by default). | Always available. Granted at install. | `EXPAND_STATUS_BAR` |
| **Uninstall** in an app's long-press menu. It only opens Android's own uninstall prompt, which you confirm. | Always available. Granted at install. | `REQUEST_DELETE_PACKAGES` |
| Notification dots and counts on icons, notifications in the long-press menu, and music controls on the Now page. Notifications are counted in memory and never saved. | **Off** until you turn it on in Settings → Permissions. | Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) |
| Showing Private Space (Android 15+) in its own drawer tab, and locking or unlocking it. Android only lists it to your default home app, and unlocking always asks for your screen lock. | Always available. Granted at install. | `ACCESS_HIDDEN_PROFILES` |
| Searching your contacts from the app drawer, and favourite contacts on the Now page. | Asked only when you turn one of those on. | `READ_CONTACTS` |
| Setting a timer or alarm from drawer search ("timer 5m"). Vulcan only hands the time to your clock app, which shows it before starting. | Always available. Granted at install. | `SET_ALARM` |
| Upcoming events on the Now page. | Asked only when you turn it on. | `READ_CALENDAR` |
| "Sunset to sunrise" night mode. It uses the last location your phone already knows and works out the sun times on the phone. | Asked only when you pick that mode. | `ACCESS_COARSE_LOCATION` |
| The screen lock, recent apps and power menu gestures (Android offers no other way). It can't read anything on your screen. | **Off** until you turn it on. | Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, `canRetrieveWindowContent="false"`) |
| Hiding the status bar clock on the home screen. Apps can't give themselves this: you grant it once from a computer with `adb shell pm grant dev.vulcanlauncher android.permission.WRITE_SECURE_SETTINGS`. Vulcan only adds or removes the clock in the status bar. | Only if you grant it. Most people never need it. | `WRITE_SECURE_SETTINGS` |

Nothing else, and no `INTERNET`. App shortcuts need Vulcan to be your default home app, which is your choice rather than a permission. The `<queries>` entries in the manifest (launcher apps, widget providers, icon packs, shortcut creators, voice and web search) only let Vulcan see those apps. Any new permission must be added to this table (see [CLAUDE.md](../CLAUDE.md)).
