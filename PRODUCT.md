# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
People leaving Nova Launcher. They depend on its depth (grids, gestures, icon packs, drawer groups, backups) but no longer want ads, trackers or an activity logger running on their home screen. They want a launcher they can install and trust, and import their Nova setup into, without losing the features they rely on.

## Product Purpose
Vulcan is an Android home-screen launcher written from scratch. It covers what people use Nova for, with no network access at all. It succeeds when a Nova user can switch over, import their backup, and not miss anything, while knowing that nothing the launcher does can leave the phone.

## Positioning
It has no `INTERNET` permission, and the README's Permissions table justifies every permission it does request. Privacy is a property of the manifest, not a policy promise. It matches Nova's feature depth and comes with its own calm default look, not a Nova clone or an iOS clone.

## Operating Context
- Used hundreds of times a day as the phone's home screen: one-handed, quick glances, launching apps, gestures, drag and drop.
- Users arrive with a Nova backup and expect it to import cleanly.
- Distributed through F-Droid: open source, no proprietary dependencies, reproducible builds.
- Runs on stock Android and OEM skins. Samsung One UI is the primary test device, and some platform features (cross-window blur) behave differently there.

## Capabilities and Constraints
- The hard rules (no network, justified permissions, no runtime dependencies, no Nova code) are in `CLAUDE.md`. Architecture is in `docs/ARCHITECTURE.md`; what's out of scope is in the README.
- Optional permissions stay off until the user turns on the feature that needs them.

## Brand Commitments
- Name: Vulcan (never "Vulcan Launcher"). Package `dev.vulcanlauncher`.
- Logo: the user-approved two-shape sunset, an orange half-sun above a separate warm-white horizon on charcoal. Use it everywhere Vulcan's identity appears, in its fixed brand colours in both themes.
- The native foreground and background vectors in `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_background.xml` are the logo source. `docs/icon.svg` and the other public assets are exports regenerated together by `python3 tools/export-logo.py`; see `docs/BUILDING.md`.
- Voice: plain and factual, especially about permissions and privacy. It says what the launcher does and doesn't do, without marketing.
- The Vulcan look is the default. Every Nova-style option stays available in settings.

## Evidence on Hand
- The "no INTERNET permission" claim can be checked with `aapt2 dump permissions` on any release APK.
- Don't invent user counts, testimonials or press.

## Product Principles
1. **Calm by default, power on demand.** Opinionated defaults that work out of the box. Nova-level depth is in settings, never in the way.
2. **Nothing leaves the phone.** Every feature works offline. If it needs the network, it doesn't ship.
3. **Earn every permission.** Ask only when the user turns on the feature that needs it, and say why in plain words.
4. **Don't strand a Nova user.** Parity and a clean import come before novelty.
5. **Platform first.** Use what Android already provides before adding code or dependencies.

## Accessibility & Inclusion
No product-specific requirement established yet. Follow Android platform accessibility norms (touch targets, TalkBack labels, font scaling).
