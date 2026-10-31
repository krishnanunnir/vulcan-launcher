---
name: Vulcan
description: An offline Android launcher built like a well-made appliance; warm-grey housing, printed legends, one signal orange.
colors:
  housing: "#E9E7E2"
  housing-night: "#2A2A28"
  raised: "#F3F2EE"
  raised-night: "#363632"
  well: "#DEDBD5"
  well-night: "#222220"
  key: "#D1CEC7"
  key-night: "#3E3E3A"
  legend-ink: "#2A2A28"
  legend-ink-night: "#E9E7E2"
  legend-secondary: "#595752"
  legend-secondary-night: "#A9A69F"
  outline: "#8C8A84"
  hairline: "#C9C6BF"
  hairline-night: "#4A4945"
  signal: "#E8721C"
  signal-ink: "#B85410"
  on-green: "#4E8F3A"
  on-green-night: "#7DB862"
typography:
  display:
    fontFamily: "sans-serif-light (system sans, Roboto on stock Android)"
    fontSize: "64sp"
    fontWeight: 300
    lineHeight: 1
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "sans-serif (system sans)"
    fontSize: "22sp"
    fontWeight: 400
  title:
    fontFamily: "sans-serif (system sans)"
    fontSize: "16sp"
    fontWeight: 400
  body:
    fontFamily: "sans-serif (system sans)"
    fontSize: "14sp"
    fontWeight: 400
  label:
    fontFamily: "sans-serif-medium (system sans)"
    fontSize: "11sp"
    fontWeight: 500
    letterSpacing: "0.1em"
  legend-glance:
    fontFamily: "sans-serif-medium (system sans)"
    fontSize: "13sp"
    fontWeight: 500
    letterSpacing: "0.12em"
rounded:
  grip: "3dp"
  key: "16dp"
  sheet: "22dp"
  card: "24dp"
  dialog: "28dp"
  pill: "1000dp"
spacing:
  hair: "4dp"
  xs: "6dp"
  sm: "8dp"
  md: "12dp"
  dock-inset: "14dp"
  lg: "16dp"
  xl: "24dp"
components:
  brand-logo-settings:
    backgroundColor: "{colors.housing-night}"
    textColor: "{colors.legend-ink-night}"
    size: "64dp"
  app-icon-home:
    size: "64dp"
  app-icon-drawer:
    size: "60dp"
  dock-housing-optional:
    backgroundColor: "{colors.housing}"
    rounded: "{rounded.pill}"
    padding: "5dp"
  search-well:
    backgroundColor: "{colors.well}"
    textColor: "{colors.legend-ink}"
    typography: "{typography.title}"
    rounded: "{rounded.pill}"
    height: "48dp"
    padding: "0 4dp 0 16dp"
  chip-key-selected:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.legend-ink}"
    rounded: "{rounded.key}"
    height: "32dp"
    padding: "0 16dp 0 12dp"
  chip-legend:
    textColor: "{colors.legend-secondary}"
    rounded: "{rounded.key}"
    height: "32dp"
    padding: "0 16dp"
  list-item:
    textColor: "{colors.legend-ink}"
    typography: "{typography.title}"
    padding: "8dp 24dp 8dp 16dp"
    height: "72dp"
  list-item-well-icon:
    backgroundColor: "{colors.well}"
    size: "40dp"
    rounded: "{rounded.pill}"
  menu-raised:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.card}"
    width: "264dp"
    padding: "6dp 0"
  drawer-sheet:
    backgroundColor: "{colors.housing}"
    rounded: "{rounded.sheet}"
  toggle-on:
    backgroundColor: "{colors.on-green}"
    textColor: "{colors.housing}"
    width: "52dp"
    height: "32dp"
    rounded: "{rounded.pill}"
  toggle-off:
    backgroundColor: "{colors.key}"
    textColor: "{colors.outline}"
    width: "52dp"
    height: "32dp"
    rounded: "{rounded.pill}"
---

# Design System: Vulcan

## Overview

**Creative North Star: "The Appliance"**

The launcher is a well-made household appliance in the Braun line (SK4, ET66, T3): a warm-grey moulded housing, legends printed in dark ink, and one signal orange reserved for the single live control on screen, with the fixed sunset logo as the approved brand exception. Every panel is either a raised housing face or a well pressed into one; nothing floats as glass, nothing is tinted by the wallpaper, and the home screen leaves the user's wallpaper untouched: the clock, its legend, the page grille and the dock icons sit directly on it, with no dock body by default. App icons keep their own designs under the system mask; the launcher does not force a shape on them.

Density is that of a control panel used hundreds of times a day, one-handed: few elements, each with a clear legend, nothing hidden behind a mystery gesture. The look refuses Material You tonal pills and iOS frosted glass. Those looks (wallpaper colour, glass dock, glass drawer, blur) remain available as non-default settings, but they are user options, not this system.

**Key Characteristics:**
- Opaque warm-grey housing in light and night variants; no blur by default.
- Recessed wells (search slot, dock channel, icon circles, sheet grip) shaded dark under the top edge and catching light on the lower lip.
- Raised faces (menus, folder windows, drawer sheet, and the optional housing dock) with a 1dp machined rim: light along the top, shade along the bottom.
- Legends in small tracked capitals; one large light clock.
- The signal dot: one orange point that marks what is live.

## Colors

Near-neutral warm greys (chroma under 0.01 in OKLCH) carry everything; one saturated orange and one switch green are the only hues.

### Primary
- **Signal Orange** (signal): the live control: the active letter in the drawer index, or the search glyph while the field has focus. When search has focus, the index gives up its dot, so the drawer never shows two signals. It is drawn as a dot or a glyph, never as text and never as a fill behind text. The static sunset logo also uses this color as its fixed brand sun.
- **Signal Ink** (signal-ink): the signal drawn on the light housing or in a well (index dot, search glyph), darkened to #B85410 so it holds 3:1 there. At night, Signal Orange itself clears 3:1 on the dark housing.

### Secondary
- **Switch Green** (on-green, on-green-night): "on": the track of a toggle that is on, and checked radios and boxes in dialogs. Nothing else.

### Neutral
- **Housing Grey** (housing / housing-night): the body. Drawer sheet, settings pages, dialogs' surroundings, the optional housing dock's face.
- **Raised Key** (raised / raised-night): a key standing proud of the housing. Menus, folder windows, the selected drawer tab.
- **Well Grey** (well / well-night): a recess pressed into the housing. Search slot, settings icon circles, the drawer grip, the optional housing dock's channel (the dock colour mixed 6% toward black in light, 30% in night).
- **Key Grey** (key / key-night): the off-state toggle track and the container tone where the platform asks for one.
- **Legend Ink** (legend-ink / legend-ink-night): primary text, glyphs, dialog buttons, the text caret.
- **Secondary Legend** (legend-secondary / legend-secondary-night): section headers, summaries, menu tile legends, inactive index letters, hint text.
- **Outline Grey** (outline): the off-toggle stroke and thumb.
- **Hairline** (hairline / hairline-night): list dividers.

### Named Rules
**The One Signal Rule.** Orange marks the live control on a surface. If two controls on screen claim it, one of them is not live. The static sunset logo is the approved brand exception; it does not indicate state or add another UI accent.

**The Ink Not Accent Rule.** Where Material would colour a control with the accent (dialog buttons, section headers, the caret), the Appliance prints it in legend ink.

## Typography

**Display Font:** system sans, light (sans-serif-light)
**Body Font:** system sans (sans-serif)
**Label Font:** system sans, medium (sans-serif-medium), uppercase and tracked

**Character:** One family, as on a printed control panel: a thin, large numeral for the clock and small, wide-spaced capitals for every legend. The platform face is deliberate in a native launcher; it matches the status bar and the apps it launches.

### Hierarchy
- **Display** (300, 64sp, line-height 1, -0.02em): the home clock only, in the label colour over the wallpaper.
- **Headline** (400, 22sp): sheet and settings page titles.
- **Title** (400, 16sp): list item headlines; 14sp in dense 48dp menu rows; 15sp in the drawer search field.
- **Body** (400, 14sp): list summaries, secondary legend colour, up to three lines.
- **Label** (500, 11sp, 0.1em, uppercase): printed legends. 12sp for settings section headers, 10sp under menu action tiles, 11sp for the drawer letter index (not tracked).
- **Glance legend** (500, 13sp, 0.12em, uppercase): the single line under the clock, date and status joined by a middle dot ("MON 19 OCT · CHARGED").

### Named Rules
**The Printed Legend Rule.** A label that names a part of the appliance (section, tile, glance line) is set uppercase, medium weight, tracked 0.1em or wider. Content (app names, settings titles, summaries) stays in sentence case.

## Layout

Home is the wallpaper with a glance strip at the top-left (24dp side padding, 20dp top), the page grid, a 14dp-tall page indicator row, and the dock row: by default a bare row of icons on the wallpaper, full width and drawn behind the navigation bar. When the housing dock is chosen it floats instead, inset 14dp from each side edge, 4dp above and 10dp below. The drawer is a full-width housing sheet whose top edge sits 12dp below the status bar, with a 28dp grip area, a 4-column grid, a 28dp-wide letter index on the right edge, tab chips at the top and the search well at the bottom under the thumb (48dp tall, 16dp side margins). Settings and sheets are single-column lists capped at 640dp wide.

App icons are 64dp on home and in the dock and 60dp in the drawer, the One UI scale, so the launcher's icons match the system's around them. Each is a user setting; these are the defaults.

Spacing runs on a 4dp base: 4, 6, 8, 12, 16 and 24dp, with 14dp reserved for the dock inset. List rows are 56dp (one line) or 72dp (with summary), 48dp in menus.

## Elevation & Depth

Depth is physical and two-way: faces stand up, wells press in. A raised face gets a 1dp rim (a top-to-bottom gradient from the face colour lightened toward white, to the face, to the face darkened toward black) plus an 8dp platform elevation shadow, soft and ambient. A well gets the inverse rim (dark at the top, light at the lower lip) and an inner top shade (black at 14% light / 35% night, fading out by a third of its height). The drawer sheet casts a soft 20dp shadow upward onto home, which is dimmed with a 40% black scrim.

### Shadow Vocabulary
- **Housing lift** (platform elevation 8dp, outline clipped to the face's radius): menus, folder windows, the optional housing dock.
- **Sheet cast** (linear gradient from transparent to black 25% over the 20dp above the sheet's top edge): the drawer sheet over home.
- **Legend lift** (text shadow 4dp blur, 1dp down, black 40%; on by default, a setting): glance and home labels over the wallpaper.

### Named Rules
**The Raised or Recessed Rule.** Every surface is a face or a well. Rims and well shading are the only gradients; there is no decorative gradient, no glow, no blur in the default look.

## Shapes

Round wells and pill slots. Anything that holds a single row (search slot, toggle, grip, the optional housing dock) is a full pill. App icons are not reshaped: the default icon shape is the system mask, so each app keeps its own silhouette (squircle and other masks are options, not the system). Panels take a moderate corner: 22dp for the drawer sheet's top edge, 24dp for menus and folder windows, 28dp for dialogs and settings sheets, 16dp for chip keys and menu tile press states. Icon circles in settings are 40dp wells. Dots are the recurring small form: 6dp dots in the page grille on a 14dp pitch, a 5dp dot beside the active index letter, a 6dp dot in the selected tab.

## Components

Exact sizes live in the front matter and in `Theme.kt`; this is what each part is.

- **Sunset logo:** a half-sun in signal orange above a warm-white horizon on charcoal, in fixed colours in both themes. The native vectors in `res/drawable/ic_launcher_*.xml` are the source; every other copy is an export (see `docs/BUILDING.md`).
- **Dock:** by default no body at all, just icons on the wallpaper. The optional housing dock is a raised pill with a well channel the icons sit in.
- **Search well:** a pill well at the bottom of the drawer, under the thumb. On focus its glyph takes the signal and the letter index gives up its dot.
- **Home search bar:** a raised housing pill with no orange, since nothing on it is live.
- **Drawer tabs:** the selected tab is a raised key; the others are plain legends. No dot, because the signal belongs to the index or search.
- **Settings lists:** rows grouped on raised faces under printed legends, icons in round wells, opening with a nameplate beside the logo.
- **Menus:** raised faces at the finger, with rows for content and icon tiles for actions.
- **Toggle:** a key-grey track when off, switch green when on.
- **Page indicator:** a speaker-grille row of equal dots; the current one darkens without changing size. No orange on home by default.
- **Drawer letter index:** secondary-ink letters down the right edge; the one in view gets legend ink and a signal dot.
- **Glance:** one large light clock over one tracked legend line, shadowed to read over any wallpaper.
- **Dialogs and sheets:** housing-tone surfaces over a dim; buttons in legend ink, checked controls in switch green.

## Do's and Don'ts

### Do:
- **Do** build every panel as a raised face or a well, with the 1dp rim on faces and the inner top shade on wells.
- **Do** mark the live thing with a signal dot (5-6dp) or a signal-tinted glyph, and keep it to one per surface.
- **Do** print part names as uppercase legends, 0.1em tracking or wider, medium weight.
- **Do** leave the default dock bare on the wallpaper; when the housing dock is chosen, keep it 14dp in from the screen edges.
- **Do** keep the drawer sheet 12dp below the status bar.
- **Do** show app icons in their own system-masked shapes by default.
- **Do** use switch green only for a toggle that is on.

### Don't:
- **Don't** set text in signal orange or fill a container with it.
- **Don't** colour controls with an accent where legend ink will do: dialog buttons, section headers and the caret stay ink.
- **Don't** add gradients beyond rim and well shading, or blur, to the default look; glass stays an opt-in setting.
- **Don't** use Material You tonal pill containers for selection; a selected item is a raised key with a dot.
