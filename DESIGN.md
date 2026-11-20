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
  surface-low: "#E4E2DC"
  surface-low-night: "#2E2E2B"
  surface-container: "#DEDBD5"
  surface-container-night: "#33332F"
  surface-high: "#D8D5CE"
  surface-high-night: "#383834"
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
  error: "#B3261E"
  error-night: "#F2B8B5"
typography:
  display:
    fontFamily: "sans-serif-light (system sans, Roboto on stock Android)"
    fontSize: "20–200sp, auto-sized to the clock item"
    fontWeight: 300
    lineHeight: 1
    letterSpacing: "-0.02em"
  nameplate:
    fontFamily: "sans-serif-light (system sans)"
    fontSize: "34sp"
    fontWeight: 300
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
  chip: "8dp"
  key: "16dp"
  segment: "20dp"
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
  nameplate:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.legend-ink}"
    typography: "{typography.nameplate}"
    rounded: "{rounded.card}"
    padding: "20dp"
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
  search-bar-home:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.legend-ink}"
    rounded: "{rounded.pill}"
    height: "52dp"
    padding: "0 6dp 0 18dp"
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
  tab-chip:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.legend-ink}"
    rounded: "{rounded.chip}"
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
  menu-raised-rows:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.card}"
    width: "216dp"
    padding: "6dp 0"
  drawer-sheet:
    backgroundColor: "{colors.housing}"
    rounded: "{rounded.sheet}"
  drawer-panel:
    backgroundColor: "{colors.housing}"
    rounded: "0"
  now-card:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.legend-ink}"
    rounded: "{rounded.card}"
    padding: "20dp"
  theme-card:
    backgroundColor: "{colors.housing}"
    rounded: "18dp"
    padding: "12dp"
  color-sheet:
    backgroundColor: "{colors.surface-high}"
    rounded: "{rounded.dialog}"
  segmented:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.legend-ink}"
    rounded: "{rounded.segment}"
    height: "40dp"
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
- Raised faces (menus, folder windows, settings panels, the optional housing dock) with a 1dp machined rim: light along the top, shade along the bottom.
- Legends in small tracked capitals; one large light clock.
- The signal dot: one orange point that marks what is live.
- One colour sheet for every colour the user can set, so a colour is always picked the same way.

## Colors

Near-neutral warm greys (chroma under 0.01 in OKLCH) carry everything; one saturated orange and one switch green are the only hues.

### Primary
- **Signal Orange** (signal): the live control: the active letter in the drawer index, or the search glyph while the field has focus. When search has focus, the index gives up its dot, so the drawer never shows two signals. It is drawn as a dot or a glyph, never as text and never as a fill behind text. The static sunset logo also uses this color as its fixed brand sun.
- **Signal Ink** (signal-ink): the signal drawn on the light housing or in a well (index dot, search glyph), darkened to #B85410 so it holds 3:1 there. At night, Signal Orange itself clears 3:1 on the dark housing.

### Secondary
- **Switch Green** (on-green, on-green-night): "on": the track of a toggle that is on, and checked radios and boxes in dialogs. Nothing else.

### Neutral
- **Housing Grey** (housing / housing-night): the body. The drawer panel, settings pages, dialogs' surroundings, the optional housing dock's face.
- **Raised Key** (raised / raised-night): a key standing proud of the housing. Menus, folder windows, settings panels, the nameplate, the selected drawer tab.
- **Well Grey** (well / well-night): a recess pressed into the housing. Search slot, settings icon circles, the drawer grip, the optional housing dock's channel (the dock colour mixed 6% toward black in light, 30% in night).
- **Key Grey** (key / key-night): the off-state toggle track and the container tone where the platform asks for one.
- **Surface Low / Container / High** (surface-low, surface-container, surface-high): the three steps between the housing and a raised key, for surfaces that sit on the housing rather than stand off it: Now page cards, the colour sheet, a full-screen folder window.
- **Legend Ink** (legend-ink / legend-ink-night): primary text, glyphs, dialog buttons, the text caret.
- **Secondary Legend** (legend-secondary / legend-secondary-night): section headers, summaries, menu tile legends, inactive index letters, hint text.
- **Outline Grey** (outline): the off-toggle stroke, the segmented control's border, the thumb.
- **Hairline** (hairline / hairline-night): list dividers.
- **Error** (error / error-night): the Remove and Uninstall glyphs in an app's menu, and nothing else.

### Themes
The theme is four cards, not a list of names. Each card shows a small picture of the scheme it produces, and the chosen one carries a check and a filled container.

- **Appliance** (default): the housing above, with signal orange.
- **Wallpaper**: the wallpaper's Material You colours on Android 12+, otherwise a seed taken from the wallpaper.
- **AMOLED**: the wallpaper's accents on pure black, always dark. Panels sit a few tones above the black; the drawer's own light/dark choice is fixed and shown greyed.
- **Your colour**: one colour of the user's, expanded into a tonal palette.

### Custom roles
Seven roles can be set by the user, each stored as `c_<role>` and laid over the theme's palette. Removing one brings the theme's colour back.

| Role | What it paints |
| --- | --- |
| Accent | The signal, and the theme's primary |
| Background | The housing |
| Text | Legend ink |
| Secondary text | Secondary legend |
| Raised | Raised keys and panels |
| Well | Recessed wells |
| Divider | Hairlines |

A custom colour is checked as it is set: text on the backgrounds it is drawn on needs 4.5:1, the accent 3:1. A colour that falls short is reported in the sheet, and a dialog button that would become unreadable falls back to legend ink so the way back is never hidden.

### Named Rules
**The One Signal Rule.** Orange marks the live control on a surface. If two controls on screen claim it, one of them is not live. The static sunset logo is the approved brand exception; it does not indicate state or add another UI accent. With a custom accent, that colour takes the signal's place and the rule is unchanged.

**The Ink Not Accent Rule.** Where Material would colour a control with the accent (dialog buttons, section headers, the caret), the Appliance prints it in legend ink. The colour sheet's Cancel and Done are ink for the same reason: the accent is what is being picked.

**The One Sheet Rule.** Every colour setting opens the same sheet: a live sample of the part, a "from the theme" (or "default") chip, a hex field, wallpaper and fixed swatches, and HSV sliders folded under "Exact color". A new colour setting reuses it; it does not grow its own picker.

## Typography

**Display Font:** system sans, light (sans-serif-light)
**Body Font:** system sans (sans-serif)
**Label Font:** system sans, medium (sans-serif-medium), uppercase and tracked

**Character:** One family, as on a printed control panel: a thin, large numeral for the clock and small, wide-spaced capitals for every legend. The platform face is deliberate in a native launcher; it matches the status bar and the apps it launches.

### Hierarchy
- **Display** (300, auto-sized 20–200sp, line-height 1, -0.02em): the home clock only, in the label colour over the wallpaper. It grows with its item, like a clock widget, so the size is a range rather than a value.
- **Nameplate** (300, 34sp, -0.02em): the launcher's name at the top of settings, over the version legend.
- **Headline** (400, 22sp): sheet and settings page titles; 24sp for a picker's title beside the app it lists.
- **Title** (400, 16sp): list item headlines; 14sp in dense 48dp menu rows; 15sp in the drawer search field.
- **Body** (400, 14sp): list summaries, secondary legend colour, up to three lines.
- **Label** (500, 11sp, 0.1em, uppercase): printed legends. 12sp for settings section headers, 10sp under menu action tiles, 11sp for the drawer letter index (not tracked).
- **Glance legend** (500, 13sp, 0.12em, uppercase): the single line under the clock, date and status joined by a middle dot ("MON 19 OCT · CHARGED").

### Named Rules
**The Printed Legend Rule.** A label that names a part of the appliance (section, tile, glance line) is set uppercase, medium weight, tracked 0.1em or wider. Content (app names, settings titles, summaries) stays in sentence case.

## Layout

Home is the wallpaper with the page grid, a 14dp-tall page indicator row, and the dock row: by default a bare row of icons on the wallpaper, full width and drawn behind the navigation bar. When the housing dock is chosen it floats instead, inset 14dp from each side edge, 4dp above and 10dp below.

The home search bar sits below the dock by default; it can also go above the dock, above the pages, below them, or be turned off. The swipe indicator is an optional 20dp row holding one ︿ arrow that opens the drawer, and it passes its touches through so a swipe can start on it. Held sideways, the dock becomes a column down the right edge and its search bar moves under the pages; portrait is the default, so landscape is opt-in.

The clock is an item on the grid, not a strip fixed above it: one row across the page when it is added, resizable from two cells wide, and it grows its type to fill whatever it is given. Its legend and status line sit under the clock, left-aligned.

The drawer has five styles: grid (default), glass, housing, list and pages. Grid is a flat housing panel at the user's Background opacity (100%), full screen, with no corners and no grip. The housing sheet is a full-width face whose top edge sits 12dp below the status bar, with a 28dp grip area, 22dp top corners, a 20dp shadow cast upward and a 40% black scrim over home. Every style keeps the same 4-column grid by default, tab chips at the top, and the search well under the thumb (48dp tall, 16dp side margins) for glass and housing, or at the top (56dp) otherwise. Every style but pages carries a 28dp-wide letter index down the right edge; pages has none, since there is nothing to jump to.

Settings and sheets are single-column lists capped at 640dp wide. Settings opens on a nameplate, then three panels: Settings (Home screen, App drawer, Folders, Search, Gestures & inputs, Now page, Badges), Style (Look & feel, Night mode, Share style) and Launcher (Backup & restore, Permissions, Default home, Restart, Contributors, About).

App icons are 64dp on home and in the dock and 60dp in the drawer, the One UI scale, so the launcher's icons match the system's around them. Each is a user setting; these are the defaults.

Spacing runs on a 4dp base: 4, 6, 8, 12, 16 and 24dp, with 14dp reserved for the dock inset. List rows are 56dp (one line) or 72dp (with summary), 48dp in menus.

## Elevation & Depth

Depth is physical and two-way: faces stand up, wells press in. A raised face gets a 1dp rim (a top-to-bottom gradient from the face colour lightened toward white, to the face, to the face darkened toward black) plus an 8dp platform elevation shadow, soft and ambient. A well gets the inverse rim (dark at the top, light at the lower lip) and an inner top shade (black at 14% light / 35% night, fading out by a third of its height). The housing drawer sheet casts a soft 20dp shadow upward onto home, which is dimmed with a 40% black scrim.

Settings panels and the nameplate sit at 2dp rather than 8dp: they are part of the page, not floating over it.

### Shadow Vocabulary
- **Housing lift** (platform elevation 8dp, outline clipped to the face's radius): menus, folder windows, the optional housing dock.
- **Panel rest** (platform elevation 2dp): settings panels and the nameplate.
- **Sheet cast** (linear gradient from transparent to black 25% over the 20dp above the sheet's top edge): the housing drawer sheet over home.
- **Legend lift** (text shadow 4dp blur, 1dp down, black 40%; on by default, a setting): the clock, its legend and home labels over the wallpaper.

### Named Rules
**The Raised or Recessed Rule.** Every surface is a face or a well. Rims and well shading are the only gradients; there is no decorative gradient, no glow, no blur in the default look.

## Shapes

Round wells and pill slots. Anything that holds a single row (search slot, toggle, grip, the optional housing dock) is a full pill. App icons are not reshaped: the default icon shape is the system mask, so each app keeps its own silhouette (squircle and other masks are options, not the system). Panels take a moderate corner: 22dp for the drawer sheet's top edge, 24dp for menus, folder windows, settings panels and Now cards, 28dp for dialogs and settings sheets, 20dp for segmented buttons, 18dp for theme cards, 16dp for chip keys and menu tile press states, 8dp for drawer tab chips. Icon circles in settings are 40dp wells. Dots are the recurring small form: 6dp dots in the page grille on a 14dp pitch, a 5dp dot beside the active index letter, and a 6dp dot in the page indicator's Dots style.

## Components

Exact sizes live in the front matter and in `Theme.kt`; this is what each part is.

- **Sunset logo:** a half-sun in signal orange above a warm-white horizon on charcoal, in fixed colours in both themes. The native vectors in `res/drawable/ic_launcher_*.xml` are the source; every other copy is an export (see `docs/BUILDING.md`).
- **Dock:** by default no body at all, just icons on the wallpaper. The optional housing dock is a raised pill with a well channel the icons sit in. Toggling it off as an overlay hides it without giving its space back to the grid, so home icons stay where they are.
- **Search well:** a pill well at the bottom of the drawer, under the thumb. On focus its glyph takes the signal and the letter index gives up its dot.
- **Home search bar:** a raised housing pill, 52dp tall, with no orange, since nothing on it is live. Its shape follows the Search style setting (square, 14dp rounded, or pill) and its mic sits in a 40dp well at the end.
- **Drawer tabs:** the selected tab is a raised key; the others are plain legends. No dot, because the signal belongs to the index or search. The bar is one scrolling row or wraps over as many rows as it needs (Wrap is the default, capped at a third of the drawer's height). A tab can carry a colour of its own, set in its editor through the shared colour sheet; it is off by default, and it is the one place a fill is allowed because the user asked for it.
- **Settings lists:** rows grouped on raised faces under printed legends, icons in round wells, opening with a nameplate beside the logo. Rows are divided by hairlines that start past the icon well.
- **Segmented buttons:** one choice of a few, in a 20dp pill outline; the chosen segment is filled with the container tone and grows to fit a wrapped label rather than clipping it.
- **Colour sheet:** the one sheet for every colour. A live sample of the part at the top (a small home screen for the theme and its roles, the part itself for folders, dock, search, labels and badges), then the "from the theme" chip beside a hex field, then swatches six to a row (the wallpaper's tones, six fixed hues, a grey ramp), then hue, saturation and brightness under "Exact color". The picked swatch gets an ink ring and a check in its own readable ink; swatches near the sheet's tone keep a hairline so dark ones still show at night.
- **Theme cards:** four cards in two rows, each a small picture of the scheme it produces (a raised key, a legend bar, a well and a signal block), the chosen one checked and filled.
- **Now page:** cards on the surface-container tone, 24dp corners, a 14sp legend title in the primary ink (legend ink under the Appliance look), and a body of live content. The date and time lead above them. Cards of the user's own hold a row of apps they picked, or a widget, and are removed from a menu rather than by dragging.
- **Badges:** a dot, a count, or the app's own small icon, at one of four corners, in three sizes, in the user's colour. A count prints in black on a light badge and white on a dark one, so it stays readable whatever colour is chosen. Ongoing notifications count; a channel's "Show dot" switch, group summaries and empty notifications do not.
- **Contributor personas:** a 56dp illustrated face in a recessed round well leads each row, followed by the 16sp name, 14sp persona and 13sp contribution. Profiles use scalable native text and the same character drawing, with a native Back action and a persistent 56dp Share persona button. At 600dp of content width, biography and artwork sit beside each other and align to the top. The exported 1080 × 1350 card keeps its fixed print composition; artwork is generated locally from `PersonaArtwork.kt`, without downloaded portraits.
- **Menus:** raised faces at the finger, above it when there is room, lined up with its left edge (its right near the screen edge), with an arrow in the face's own colour and edge pointing at it. Rows for content (shortcuts with a grip, a notification), then the actions. An app's actions are always icon tiles in one order (Edit, Remove, App info, then Widgets and Uninstall when they apply), so every app's menu has the same shape; tiles need 264dp, a rows-only menu 216dp. Other menus (folders, widgets, home) with four actions or fewer are labelled rows. Remove and Uninstall take the error colour on their icon. Holding empty space shrinks the page and shows Wallpaper, Widgets, Settings and More under it instead of a menu.
- **Folders:** a preview of the apps inside (grid, stack, line, fan or radial) on one of five backgrounds (none, circle, disc, platform or square), with the name under it. Opening one gives a pop-up window at the folder's own radius, clear of the dock, or a full-screen page; its Background opacity lets the wallpaper through, and its colour paints the window as well as the preview. The folder's own ⋮ menu sits at the end of its name.
- **First run:** the one surface that is not housing. Charcoal, with the sunset rising over its horizon in signal orange (2.2s, once), then a 460dp column of steps: hello, set as home, setup, Nova import review, widgets. Primary keys are warm white on the charcoal.
- **Undo bar:** a snackbar on the inverse surface, 8dp corners, 6dp elevation, a 14sp message and Undo in the inverse primary. Capped at 560dp, 16dp above the navigation bar, sliding up 24dp as it fades in.
- **Toggle:** a key-grey track when off, switch green when on.
- **Drop targets:** while something is dragged, Remove and Uninstall appear as outlined 48dp keys in a bar at the top of the screen, in the label colour with a glyph and a word. The one under the finger fills with the container tone.
- **Page indicator:** a speaker-grille row of equal dots; the current one darkens without changing size. No orange on home by default. The Signal style lights the current dot in the accent, the Dots style stretches it into a pill, and the Line style slides a 24dp bar; None removes the row.
- **Drawer letter index:** secondary-ink letters down the right edge; the one in view gets legend ink and a signal dot.
- **Clock:** one large light clock over one tracked legend line, shadowed to read over any wallpaper. The legend joins date and status with a middle dot. Without the Appliance look it becomes the date over a status line, and the Clock or Date style picks either alone.
- **Dialogs and sheets:** housing-tone surfaces over a dim; buttons in legend ink, checked controls in switch green. Pickers and forms slide up from the bottom edge, in thumb reach; confirmations and warnings stay centred.

## Do's and Don'ts

### Do:
- **Do** build every panel as a raised face or a well, with the 1dp rim on faces and the inner top shade on wells.
- **Do** mark the live thing with a signal dot (5-6dp) or a signal-tinted glyph, and keep it to one per surface.
- **Do** print part names as uppercase legends, 0.1em tracking or wider, medium weight.
- **Do** leave the default dock bare on the wallpaper; when the housing dock is chosen, keep it 14dp in from the screen edges.
- **Do** keep the housing drawer sheet 12dp below the status bar, and the default drawer panel flat and full screen.
- **Do** show app icons in their own system-masked shapes by default.
- **Do** use switch green only for a toggle that is on.
- **Do** open the one colour sheet for any new colour setting, and check the contrast of what it saves.
- **Do** let the clock grow with its item instead of fixing its size.
- **Do** keep the first run on charcoal under the sunrise; it is the one surface that is not housing.

### Don't:
- **Don't** set text in signal orange or fill a container with it. The undo bar's Undo is the one exception: a transient snackbar on the inverse surface, where the accent is the only thing that reads as an action.
- **Don't** colour controls with an accent where legend ink will do: dialog buttons, section headers and the caret stay ink.
- **Don't** add gradients beyond rim and well shading, or blur, to the default look; glass stays an opt-in setting.
- **Don't** use Material You tonal pill containers for selection; a selected item is a raised key. The segmented control is the platform's own component and keeps its filled segment.
- **Don't** grow a second colour picker, a second theme list, or a second way to name a colour.
