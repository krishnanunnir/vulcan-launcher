# Persona artwork provenance

The four `persona-*.png` cards were generated on the Android emulator by `PersonaCheck` from Vulcan's own Kotlin Canvas artwork. They contain fictional illustrated characters representing each person's contribution, not photographic likenesses or downloaded profile pictures.

- Character drawing, face crops and the profile scene: `app/src/main/java/dev/vulcanlauncher/PersonaArtwork.kt`.
- Fixed 1080 × 1350 card composition: `app/src/main/java/dev/vulcanlauncher/PersonaCard.kt`.
- Contributor names, roles and character selections: `app/src/main/java/dev/vulcanlauncher/Contributors.kt`; text is in `app/src/main/res/values/strings.xml`.
- Generation and PNG-sharing check: `app/src/androidTest/java/dev/vulcanlauncher/PersonaCheck.kt`, selected with `DeviceCheck -e check persona`.

The artwork uses the project's native primitives and palette. There are no external portrait assets, added runtime libraries, or network requests. List/profile screenshots used during refinement are emulator captures in the ignored `.impeccable/review/` directory; run logs are in ignored `build/persona-polish-2026-11-01/`.
