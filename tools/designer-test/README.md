# Designer preview tests

From this directory, install the locked dependencies and run the Chromium suite:

```sh
npm ci
npx playwright-core install chromium
npm test
```

Use `VULCAN_BROWSER=firefox npm test` or `VULCAN_BROWSER=webkit npm test` after installing that browser with Playwright.

The suite opens synthetic Vulcan backups in the Designer and measures the rendered icon and label positions. It covers mixed label lengths, dense grids, half cells, and the Home, Dock, Drawer, Folder, and Now previews at phone, landscape, and desktop sizes.
