const assert = require("node:assert/strict");
const { readFile } = require("node:fs/promises");
const path = require("node:path");
const { test } = require("node:test");
const playwright = require("playwright-core");

const docs = path.resolve(__dirname, "../../docs");
const origin = "https://designer.test";
const mime = {
  ".html": "text/html",
  ".json": "application/json",
  ".jpg": "image/jpeg",
  ".png": "image/png",
};
const names = [
  "Mail", "Calendar and Reminders", "Notes", "VeryLongUnbrokenApplicationName".repeat(4),
  "الأخبار والطقس", "カレンダーとリマインダー", "🎧 Musique et balados",
];
const folderName = "Tools and Utilities";

function backup(settings = {}, savedSub = 1) {
  const app = (label, x, y, page) => ({ label, x, y, page });
  const children = Array.from({ length: 24 }, (_, i) => app(names[i % names.length], 0, 0, 0));
  const row = (y, page) => [
    app(names[0], 0, y, page),
    app(names[1], 1, y, page),
    { ...app(folderName, 2, y, page), folder: true, children },
  ];
  return {
    format: "vulcan-backup-2",
    settings: { labels: "on", dockLabels: "on", drawerLabels: "on", ...settings },
    home: { sub: savedSub, items: [...row(0, 0), ...row(1, 0), ...row(0, -1), app("Second page", 2, 0, 1)] },
    reference: { installedApps: names.map((label, i) => ({ label, pkg: "test.app" + i })) },
  };
}

async function withDesigner(run) {
  const engine = process.env.VULCAN_BROWSER || "chromium";
  const args = JSON.parse(process.env.VULCAN_BROWSER_ARGS || "[]");
  const browser = await playwright[engine].launch({ args });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: "reduce" });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    // Exercise the same files GitHub Pages serves, with no network or font dependency.
    await page.route("**/*", async route => {
      const url = new URL(route.request().url());
      if (url.origin !== origin) return route.abort();
      const file = path.join(docs, url.pathname === "/design/" ? "/design/index.html" : url.pathname);
      if (!mime[path.extname(file)]) return route.fulfill({ status: 404 });
      await route.fulfill({ body: await readFile(file), contentType: mime[path.extname(file)] });
    });
    await page.goto(origin + "/design/");
    await page.getByRole("button", { name: /Fresh install/ }).waitFor();
    await run(page);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
}

let imports = 0;
async function openBackup(page, data) {
  const name = "layout-" + ++imports + ".json";
  await page.locator("#open").setInputFiles({
    name, mimeType: "application/json", buffer: Buffer.from(JSON.stringify(data)),
  });
  await page.locator("#status b").getByText(name, { exact: true }).waitFor();
}

const button = (page, name) => page.getByRole("button", { name, exact: true });

async function previewGeometry(page, selector) {
  return page.locator(selector).evaluateAll(cells => {
    const rect = element => {
      const { x, y, width, height, right, bottom } = element.getBoundingClientRect();
      return { x, y, width, height, right, bottom };
    };
    return cells.map(cell => {
      const icon = cell.querySelector(".icw"), label = cell.querySelector(".lb");
      const lines = label && getComputedStyle(label).display !== "none" ? rect(label) : null;
      const track = cell.parentElement.parentElement.classList.contains("grid") ? cell.parentElement : cell;
      return { cell: rect(track), icon: rect(icon), label: lines, name: label?.textContent };
    });
  });
}

function near(a, b, context) {
  assert(Math.abs(a - b) <= 0.25, `${context}: ${a} vs ${b}`);
}

function checkCells(cells, context) {
  assert(cells.length > 0, context + ": no icons");
  const rows = new Map();
  for (const { cell, icon, label, name } of cells) {
    const at = context + " / " + (name || "unlabelled icon");
    assert(icon.width > 0 && icon.height > 0, at + ": zero-size icon");
    near(icon.x + icon.width / 2, cell.x + cell.width / 2, at + ": horizontal center");
    assert(icon.x >= cell.x - 0.25 && icon.right <= cell.right + 0.25, at + ": icon overflows its column");
    assert(icon.y >= cell.y - 0.25 && icon.bottom <= cell.bottom + 0.25, at + ": icon overflows its row");
    if (label) {
      assert(label.y >= icon.bottom - 0.25 && label.bottom <= cell.bottom + 0.25, at + ": label overflows its row");
      near(label.x + label.width / 2, cell.x + cell.width / 2, at + ": label center");
      assert(label.x >= cell.x - 0.25 && label.right <= cell.right + 0.25, at + ": label overflows its column");
    }
    // Cells of the same size and on the same row should share the icon top.
    const key = [cell.y, cell.width, cell.height].map(n => Math.round(n * 2)).join(",");
    if (rows.has(key)) near(icon.y, rows.get(key), at + ": icon top within row");
    else rows.set(key, icon.y);
  }
}

test("issue #81: short apps, wrapped apps and folders line up on the same row", async () => {
  await withDesigner(async page => {
    const home = "#screen .grid .cell";
    await openBackup(page, backup({ cols: "5", iconSize: "48", labelLines: "1" }));
    await page.locator('[data-key="cols"] input').press("Home");
    await page.locator('[data-key="iconSize"] input').press("Home");
    checkCells(await previewGeometry(page, home), "minimum columns and icon size");

    await openBackup(page, backup({ cols: "5", iconSize: "48", labelLines: "2" }));
    const cells = await previewGeometry(page, home);
    assert.deepEqual(cells.slice(0, 3).map(entry => entry.name), [names[0], names[1], folderName]);
    assert(cells.slice(0, 3).every(entry => entry.label), "fixture should keep its labels visible");
    const visibleTextLines = await page.locator(home + " .lb").nth(1).evaluate(label => {
      const range = document.createRange();
      range.selectNodeContents(label);
      const bottom = label.getBoundingClientRect().bottom;
      return new Set(Array.from(range.getClientRects())
        .filter(rect => rect.height > 0 && rect.bottom <= bottom + 0.25)
        .map(rect => Math.round(rect.top * 2))).size;
    });
    assert.equal(visibleTextLines, 2, "fixture should wrap the long app name");
    checkCells(cells, "two-line labels");
    near(cells[0].icon.y, cells[1].icon.y, "wrapped app");
    near(cells[0].icon.y, cells[2].icon.y, "wrapped folder");
    near(cells[0].label.height, cells[1].label.height, "reserved label height");

    await page.locator('[data-key="labelLines"]').getByRole("button", { name: "Single line" }).click();
    const oneLine = await previewGeometry(page, home);
    checkCells(oneLine, "one-line labels");
    assert(oneLine[0].label.height < cells[0].label.height);
    await page.locator('[data-key="labels"]').getByRole("switch").click();
    assert.equal(await page.locator(home + " .lb").count(), 0);
    checkCells(await previewGeometry(page, home), "labels off");
  });
});

test("preview fits icons and labels across grid sizes, preview screens and viewports", async () => {
  await withDesigner(async page => {
    const layouts = [
      { cols: "3", rows: "3", dockCols: "3", drawerCols: "3", drawerRows: "3", folderCols: "2" },
      { cols: "5", rows: "6", dockCols: "5", drawerCols: "4", drawerRows: "6", folderCols: "auto" },
      { cols: "12", rows: "12", dockCols: "9", drawerCols: "8", drawerRows: "9", folderCols: "6" },
      { cols: "12", rows: "12", dockCols: "9", drawerCols: "8", drawerRows: "9", folderCols: "6", subgrid: "on" },
    ];
    const labelModes = [
      { labels: "off", dockLabels: "off", drawerLabels: "off" },
      { labelSize: "8", labelLines: "1" },
      { labelSize: "12", labelLines: "2", labelFont: "monospace" },
      { labelSize: "18", labelLines: "2", labelFont: "sans-serif-condensed" },
      { labelSize: "24", labelLines: "2", labelFont: "serif" },
    ];
    for (const [width, height] of [[320, 640], [844, 390], [1440, 900]]) {
      await page.setViewportSize({ width, height });
      for (const layout of layouts) for (const size of ["32", "52", "72"]) for (const labels of labelModes) {
        const settings = {
          ...layout, ...labels, iconSize: size, dockIconSize: size, drawerIconSize: size,
          dockBg: "housing", drawerStyle: "grid", folderRows: "6", nowApps: "on",
        };
        const context = `${width}×${height} / ${layout.cols}×${layout.rows} / ${layout.subgrid || "off"} / ${size} dp / ${JSON.stringify(labels)}`;
        await openBackup(page, backup(settings, layout.subgrid === "on" ? 2 : 1));
        await button(page, "Home").click();
        checkCells(await previewGeometry(page, "#screen .grid .cell"), context + " Home");
        checkCells(await previewGeometry(page, "#screen .dock .cell"), context + " Dock");
        await button(page, "App drawer").click();
        checkCells(await previewGeometry(page, "#screen > .col .cell"), context + " Drawer");
        await button(page, "Folder").click();
        checkCells(await previewGeometry(page, "#screen > :last-child .cell"), context + " Folder");
        await button(page, "Now page").click();
        checkCells(await previewGeometry(page, "#screen > .col .cell"), context + " Now");
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), width, context);
      }
    }
  });
});

test("alternate Drawer and Folder styles keep long names within their previews", async () => {
  await withDesigner(async page => {
    const settings = { labelLines: "2", labelSize: "24", drawerCols: "8", drawerRows: "9",
      drawerIconSize: "72", folderCols: "2", folderRows: "6", iconSize: "72" };
    for (const drawerStyle of ["grid", "pages", "glass", "housing", "list"]) {
      await openBackup(page, backup({ ...settings, drawerStyle }));
      await button(page, "App drawer").click();
      if (drawerStyle === "list") {
        const labels = await page.locator(".drawer-list-label").evaluateAll(elements => elements.map(el => ({
          width: el.clientWidth, textWidth: el.scrollWidth, ellipsis: getComputedStyle(el).textOverflow,
        })));
        assert(labels.some(label => label.width > 0 && label.textWidth > label.width && label.ellipsis === "ellipsis"));
      } else checkCells(await previewGeometry(page, "#screen > .col .cell"), drawerStyle);
    }
    for (const folderWindow of ["popup", "fullscreen"]) {
      await openBackup(page, backup({ ...settings, folderWindow }));
      await button(page, "Folder").click();
      checkCells(await previewGeometry(page, "#screen > :last-child .cell"), folderWindow);
      assert(await page.locator("#screen > :last-child").evaluate(panel =>
        panel.getBoundingClientRect().bottom <= panel.parentElement.getBoundingClientRect().bottom + 0.25
      ), folderWindow + " extends off screen");
    }
  });
});
