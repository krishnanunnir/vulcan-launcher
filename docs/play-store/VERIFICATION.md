# Google Play preparation verification

Verified 7 November 2026. Vulcan 0.30 (22), package `dev.vulcanlauncher`, min SDK 26, target/compile SDK 36.

## Build and automated checks

- `testDebugUnitTest`: 68 tests, no failures or errors.
- `lintRelease`: passed, zero errors; 141 warnings remain, including 80 incomplete-translation warnings. Partial translations intentionally fall back to English. The updated accessibility disclosure currently falls back to English for German and Russian.
- `bundleRelease`, `assembleRelease`, `assembleDebug`, `assembleDebugAndroidTest`: passed.
- Android CLI bundle analysis confirms package/version 0.30 (22).
- JAR signature verification of the AAB passed; APK signature verification passed. Android release certificates are self-signed; the JDK reports trust-chain/timestamp warnings.
- App signing certificate SHA-256: `da454f94a59d15cb8b6119483d05cfb971a2c61c8695ba5f53f80b7c9fcaa555`.
- Packaged permission dump confirms no INTERNET permission. Bundle contains no native `.so` libraries.
- AAB contains the published privacy policy including the correct developer name and support email.
- Listing title 6/30, short description 68/80, full description 2464/4000 characters.
- Opaque 512 × 512 icon, opaque 1024 × 500 feature graphic, and five 585 × 1170 Play screenshot exports validated.
- `git diff --check`: passed.

## Device checks

Android 16 / API 36, dedicated Pixel 8 emulator, signed release APK:

- Installed and selected successfully as default home.
- Drawer opened and system Back closed it.
- System Back from Gestures & inputs returned to Settings home.
- Gesture-triggered accessibility disclosure showed the complete purpose, data handling, optional nature and explicit consent/cancel buttons.
- Cancel returned to a usable home screen; triggering the gesture again showed the disclosure again.
- Agree opened Android accessibility settings; service enabled through Android's own prompt.
- Configured swipe-up gesture opened Android Recent apps.
- Runtime service dump reports `capabilities=0`, consistent with no window-content retrieval.
- Service access revoked after testing. Settings → Permissions → Accessibility service then showed the same disclosure.
- Settings → About → Privacy policy displayed the bundled offline policy. That UI capture preceded the developer-name spelling correction; the final bundle was checked separately for the corrected policy text.
- Reviewer video recorded and parsed successfully, duration approximately 152 seconds. It shows cancellation, consent, enabling the service and the resulting system action. Local video still needs a reviewer-accessible hosted URL.

Android 11 / API 30, dedicated Pixel 4 emulator, debug build:

- All 13 multi-touch/gesture instrumentation checks passed.
- Test fixture now uses an empty saved layout, rebuilds after changing gesture settings, and restores the original layout/settings. This avoids first-run shortcut hits and stale drawer/Now callbacks. A failed launch no longer crashes its cleanup on an uninitialized activity.
- System Back closed the app drawer using legacy dispatch.

Both test emulators were stopped after verification. Android 8, physical-device coverage, tablet/large-screen coverage, and Google's Play pre-launch report remain to be completed before production release.

## Public policy and registration

Privacy URL: https://bizzaretor.com/vulcan-launcher/privacy.html

Published commit: `b16cf39a01fa8a0b33cadb2d83eaf33fede0e1e1`. Pages deployment succeeded; HTTP 200 and app/developer/contact content verified.

Public support email: `krishnanunnir97@gmail.com`.

Personal developer registration was opened and public developer name **Vulcan Launcher** entered. The form is at **Create or select payments profile**. No developer account has been created, terms accepted, registration fee paid, signing key transferred, app uploaded, tester invitations sent or production release submitted. Google registration/verification and the required 12-tester, 14-day closed test remain.

Other repository changes remain local and uncommitted. Existing unrelated work was retained. The public privacy HTML is the only file published to GitHub during this task. For GitHub/F-Droid releases, follow the clean tagged reproducibility process before publishing a release APK.

## Artifact checksums

- AAB: 502,777 bytes; SHA-256 `4a9947d44c1c9d404e7cc78335e2018d8334cb27b757668e55da6389c6fed3c6`.
- APK: 341,512 bytes; SHA-256 `def29f7c7b649dc0e9578576f580149d28a1296d5292afc6ec4c6b7394eea500`.
