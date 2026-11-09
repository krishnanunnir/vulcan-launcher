# Vulcan Google Play release

Prepared 7 November 2026. This is a submission kit, not evidence of Play approval or publication.

## Account and app setup

The signed-in Google account currently opens developer registration. The account owner must create the account, review the Developer Distribution Agreement, pay the one-time US$25 registration fee, and complete Google's identity, contact and device verification. Do not send identity documents, payment details, passwords or signing passwords through chat.

Create an **app**, default language **English (United States)**, name **Vulcan**, free, no ads. Package: `dev.vulcanlauncher`. Suggested category: **Personalization**. Public support email: `krishnanunnir97@gmail.com`. Website: `https://bizzaretor.com/vulcan-launcher/`.

The owner must confirm the intended audience and distribution countries in Console; those business declarations have not been submitted. Do not invent an IARC rating: complete the questionnaire against the actual app. Vulcan has no accounts, purchases, advertising, user-to-user messaging, or developer-hosted content.

## Signing — complete before the first upload

The existing git-ignored `keystore.properties` signs release builds. The AAB is signed with that existing key. Never commit keystores, passwords, PEPK exports or service-account credentials.

To preserve update signing compatibility with existing GitHub releases and reproducible F-Droid releases, enroll the **existing app signing key** in Play App Signing. In Console choose the option to export/upload an existing key, rather than accepting a different Google-generated app signing key. Android's other update rules still apply, including version-code ordering and installer/update-ownership restrictions.

Console provides the current PEPK tool, encryption parameters and instructions. The owner must explicitly authorize transferring the existing private signing key to Google at that step. No key export or transfer has been performed. After enrollment, compare Console's **app signing certificate SHA-256** with the existing signed APK. Configure a separate upload key later if desired; an upload key is distinct from the app signing key.

References: [Android signing documentation](https://developer.android.com/studio/publish/app-signing), [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

## Build and upload files

Version: **0.30**, version code **22**, minimum Android **8 / API 26**, target **Android 16 / API 36**.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest lintRelease bundleRelease assembleRelease
python3 tools/prepare-play-assets.py
```

- Upload bundle: `app/build/outputs/bundle/release/app-release.aab`.
- Companion APK for local testing: `app/build/outputs/apk/release/app-release.apk`.
- Title, short/full descriptions, contact and privacy URLs: `fastlane/metadata/android/en-US/`.
- Release notes: `fastlane/metadata/android/en-US/changelogs/22.txt`.
- Play icon: `fastlane/metadata/android/en-US/images/play-icon.png` (512 × 512, opaque).
- Feature graphic: `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024 × 500, opaque).
- Phone screenshots: `docs/play-store/phone-screenshots/`. These preserve existing genuine repository screenshots with neutral side margins to meet Play's 2:1 aspect limit. They are not fresh captures of version 0.30.

The asset script checks listing character limits and graphic dimensions. Do not upload the original 540 × 1170 Fastlane screenshots directly to Play; their aspect ratio exceeds 2:1. Use `play-icon.png` rather than the existing transparent icon export. If automating Fastlane later, prepare a separate Play metadata staging folder with the validated Play screenshots and opaque icon under Fastlane's conventional names.

This preparation build comes from an uncommitted working checkout. Follow `../BUILDING.md`'s clean tagged build and reproducibility process before publishing the corresponding GitHub/F-Droid release; do not represent this build as a reproducible tagged release.

## Privacy policy publication

Source: `app/src/main/res/raw/privacy_policy.txt`, available offline in **Settings → About → Privacy policy**. The asset script generates the matching `docs/privacy.html` page and the website footer links it.

Intended public URL: `https://bizzaretor.com/vulcan-launcher/privacy.html`. Deploy the page using the existing website's publishing process, then verify that exact URL is public, serves the policy without authentication, and has the correct contact email. A local HTML file or planned URL does not satisfy Console's live URL requirement. The policy page was published in commit `b16cf39a01fa8a0b33cadb2d83eaf33fede0e1e1`; its Pages deployment succeeded, and the public HTTPS URL returned HTTP 200 with the correct app and contact details on 7 November 2026. The local website footer link is prepared but not yet published.

## Data safety and app content answers

Use these as source-reviewed form answers, adjusting to the actual Console questions and rechecking when functionality changes:

| Question | Prepared answer |
| --- | --- |
| Collect or share required user data types? | **No**, based on the current app: access and processing stay on-device; no INTERNET permission or telemetry SDKs. |
| Account creation? | **No account creation**. No account deletion URL applies. Local data deletion is explained in the policy. |
| Advertising? / Advertising ID? | **No / No**. No ads or AD_ID permission. |
| App access for reviewers? | All features accessible without login. Optional features require choosing Vulcan as the default home app and granting the relevant Android permission. |
| Privacy policy | Use the live URL above only after deployment and verification. |
| Data encryption in transit | No developer-operated data transmission. Follow Console's applicable branch; do not claim an encryption feature Vulcan does not provide. Exported JSON is not encrypted by Vulcan. |

On-device data includes installed app identities and profiles, layout, customizations, local app launch counts, optional contact/calendar data, saved approximate coordinates, active notification content and media metadata. A user-selected document provider can upload exported backups. External apps receive explicit user requests such as web searches, opening contacts and sharing cards. The privacy policy explains these flows; assess Console's user-initiated transfer exceptions against the actual form rather than treating absence of INTERNET as the only evidence.

Sources: [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).

## Accessibility declaration

Vulcan is **not an accessibility tool** and does not set `isAccessibilityTool=true`. Purpose: **App functionality**. Collection/sharing through AccessibilityService: **No**.

Suggested explanation:

> Vulcan is an Android home screen. Users can assign gestures or launcher shortcuts to specific Android system actions: screen lock, recent apps, notifications, quick settings and the power menu. The optional accessibility service invokes only the action the user requests using performGlobalAction. It does not retrieve window content, record interactions, or collect or share personal data. Before opening Android Accessibility settings, Vulcan shows a dedicated disclosure with Agree and open settings and Cancel buttons. The launcher remains usable without the service. Users can revoke access in Android settings.

The XML restricts event delivery to Vulcan, `onAccessibilityEvent` is empty, and `canRetrieveWindowContent=false`. Disclose these facts accurately; do not claim Vulcan helps users with disabilities as its primary purpose.

Google requires a reviewer-accessible demonstration video URL. A roughly 152-second device recording is prepared at `build/play-evidence/accessibility-review.mp4` (included in the submission kit). It shows cancellation, consent, enabling the service and a Recent apps gesture. It still needs a hosted reviewer-accessible URL; it has not been uploaded. The full demonstration checklist is:

1. Open Vulcan; show normal home and drawer operation without accessibility access.
2. In Settings → Gestures & inputs, assign a gesture to Recent apps (or use a preconfigured system-action shortcut).
3. Trigger the action and show the full disclosure. Tap Cancel and demonstrate the launcher still works.
4. Trigger it again; tap Agree and open settings. Enable **Vulcan actions** in Android's accessibility screen.
5. Return to Vulcan and trigger the configured gesture to open Recent apps.
6. Disable the service in Android settings and show that the launcher still works.

Also show Settings → Permissions → Accessibility service opening the same disclosure before enabling access. Add captions or narration explaining the action and the lack of screen-content access. Do not submit a script or local video path in place of a publicly reviewer-accessible video URL.

Source: [AccessibilityService policy and declaration](https://support.google.com/googleplay/android-developer/answer/10964491).

## Testing and submission sequence

1. Complete registration, app setup, signing enrollment, live privacy URL, listing, rating and policy declarations.
2. Upload the AAB to **Internal testing**. Inspect upload validation and pre-launch results. Verify the Play-installed certificate matches the intended existing signing certificate.
3. Test home selection, app launches, widgets, drawer/search, folders, Back from drawer and nested settings, backups, optional permissions, both accessibility disclosure entry points, cancellation and offline privacy access.
4. For a new personal account, run a **closed test with at least 12 testers opted in continuously for 14 days**, collect real feedback and fix failures. Internal testing does not count toward this requirement. The owner supplies tester email addresses or a tester group; no invitations have been sent.
5. Apply for production access with truthful testing evidence. After approval and policy review, submit a production release. Google decides approval and timing.

Source: [New personal account testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
