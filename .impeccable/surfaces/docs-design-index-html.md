---
version: 1
slug: "docs-design-index-html"
primary_target: "docs/design/index.html"
related_targets: []
---

# Vulcan designer (docs/design/index.html)

Scope: the browser designer page only. Visitor mode: Operate. The launcher app keeps its own world (DESIGN.md, "The Appliance"); this page does not follow it and does not change it.

Audience and job: someone moving to Vulcan, at a desk with the phone beside them, picks a starting look, tunes settings against a live phone preview, and carries the result to the phone as a link or backup file. Constraints: no build step, one HTML file, the preview renderer and the selectors used by tools/designer-test stay; the sunset logo keeps its fixed colours.

## Direction contract

THESIS: Choosing a look is choosing from a paint chart. The six looks are the page's colour, as one full-width band of named chips; the tool refuses the dark dashboard with one accent that it had before.

OWN-WORLD: White card stock, near-black ink, warm card grey for wells. No fixed brand accent: the accent is the chip of the look in use, with ink chosen for contrast on it. Flat fills, hairline rules, pills for tabs and actions, no shadows except under the phone. Figtree, heavy for names, medium for labels.

STORY: See six looks, tap one, watch the phone change, tune the group that matters, copy the link.

FIRST VIEWPORT: 60px white header (logo, name, Open backup, AI assistant, Share link in the accent). Below it the chart: six flat chips edge to edge, 176px tall, name in 20px heavy type at the bottom left, the current one underlined with an 8px ink bar. Below that two columns: a card-grey stage with the phone centred and the screen switch under it; the editor with pill tabs for the nine groups and ruled setting rows, values right-aligned.

FORM: Paint Chart, the model's pick (candidate 1 of 7); seed:c1a84cb2bb941d90. Signature move: the page re-inks itself in the chosen look's colour. Motion: the accent crossfades and the ink bar slides to the chosen chip.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Unresolved: this page's tokens live in its own CSS and this brief, not in DESIGN.md, which describes the launcher app.
