---
version: 1
slug: "docs-index-html"
primary_target: "docs/index.html"
related_targets: ["docs/privacy.html"]
---

# Vulcan landing page (docs/index.html)

Scope: the public landing page, and the privacy policy's styling. Visitor mode: Persuade (privacy.html: Read). The launcher app keeps its own world (DESIGN.md, "The Appliance"); this page follows the designer page's world instead, at the user's request, so the site reads as one thing.

Audience and job: someone weighing a launcher, often a Nova user who has heard about the ads and trackers, decides whether to install. Constraints: one static HTML file, no script, factual copy unchanged, the sunset logo keeps its fixed colours, real app screenshots stay real.

## Direction contract

THESIS: The same paint chart as the designer, used to sell: the six looks stand as six phones on six colour chips, edge to edge, directly under the headline.

OWN-WORLD: White card stock, near-black ink, warm card grey for wells. Colour comes only from the six look chips. The page's accent is the Fresh install chip (#2B50E8), the look a new install starts with, used for the primary download button, the Vulcan size bar and the closing band; accent fills on white carry a 1.5px ink keyline. Flat fills, hairline rules, pills, no shadow except under a phone. Figtree, heavy for headings.

STORY: Half a megabyte, six ways it can look, three numbers, the size chart, what it cannot send, what Nova sends, how to move over, what it does, download.

FIRST VIEWPORT: 68px header; headline left with the pitch and three pills right; then the chart, six chips each holding a phone with the look's name under it, all visible at 1440x900.

FORM: Paint Chart, carried over from docs/design/index.html (seed:c1a84cb2bb941d90). Signature move: the chart as hero. Motion: the six phones settle onto their chips left to right on load; a chip's phone lifts and an 8px ink bar rises on hover.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review and the verdict.

Provenance: screenshots/look-*.webp are captures of the designer's own preview (docs/design/index.html), one per look; the page says so under the chart. screenshots/drawer.jpg and settings.jpg are screenshots of the app.

Unresolved: tokens live in this page's CSS and this brief, not in DESIGN.md, which describes the launcher app.
