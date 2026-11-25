#!/usr/bin/env python3
# Frame stats for every Vulcan screen and gesture: home, drawer (scroll, tabs, search), folder, menus, Now page,
# app launch and return, Settings open time and every Settings page (opened, scrolled, closed).
# Usage: ANDROID_SERIAL=<serial> tools/perf/screens.py <label>     -> .local/perf/<label>-screens.txt
# Changes nothing: taps only page titles in Settings (checked against the page's title) and leaves with Back.
# Not covered, because they would move the user's layout: icon and widget drags, widget resize.
# Release build as the default home, phone unlocked, portrait, ~1080x2340; the home coordinates fit the S23 layout.
import os, re, subprocess, sys, time

P = "dev.vulcanlauncher"
if not os.environ.get("ANDROID_SERIAL"): sys.exit("set ANDROID_SERIAL (adb devices -l): with no serial adb may pick an emulator")
LABEL = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: screens.py <label>")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".local", "perf")
os.makedirs(OUT, exist_ok=True)

def sh(cmd): return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, timeout=90).stdout
def key(k, wait=0.8): sh(f"input keyevent KEYCODE_{k}"); time.sleep(wait)
def tap(x, y, wait=1.0): sh(f"input tap {x} {y}"); time.sleep(wait)
def swipe(x1, y1, x2, y2, ms=300, wait=0.8): sh(f"input swipe {x1} {y1} {x2} {y2} {ms}"); time.sleep(wait)
def hold(x, y, wait=1.0): swipe(x, y, x, y, 900, wait)

def nodes():
    for _ in range(3):  # uiautomator returns nothing while something animates
        x = subprocess.run(["adb", "exec-out", "uiautomator dump /dev/tty"], capture_output=True, text=True, timeout=60).stdout
        found = []
        for n in re.findall(r"<node [^>]*>", x):
            b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n)[1])))
            t = re.search(r'text="([^"]*)"', n)[1].replace("&amp;", "&")
            d = re.search(r'content-desc="([^"]*)"', n)[1].replace("&amp;", "&")
            found.append((t, d, ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)))
        if found: return found
        time.sleep(1)
    return []

def find(label, scroll=False, below=0):
    for i in range(14 if scroll else 1):
        hit = [c for t, d, c in nodes() if label in (t, d) and c[1] > below]
        if hit: return hit[0]
        if scroll: swipe(100, 1700, 100, 1100, 300)  # short steps: a long fling skips past a row
    return None

def title(): return next((t for t, d, c in nodes() if t and 140 < c[1] < 250), "")

rows = []
def measure(name, action):
    sh(f"dumpsys gfxinfo {P} reset")
    note = action() or ""
    g = sh(f"dumpsys gfxinfo {P}")
    f = lambda pat: (re.search(pat, g) or [None, "?"])[1]
    hist = [int(ms) for ms, n in re.findall(r"(\d+)ms=(\d+)", g.split("HISTOGRAM:")[1].split("\n")[0]) if n != "0"] if "HISTOGRAM:" in g else []
    r = (name, f(r"Total frames rendered: (\d+)"), f(r"Janky frames: \d+ \(([\d.]+%)\)"),
         *[f(rf"\n *{p}th percentile: (\d+)ms") for p in (50, 90, 99)], max(hist) if hist else "?", note)
    if r[1] == "0": r = (name, "0", "-", "-", "-", "-", "-", note or "nothing redrawn")
    rows.append(r); print("| " + " | ".join(map(str, r)) + " |", flush=True)

def home(): key("HOME", 1.5)
def open_drawer(): swipe(540, 1500, 540, 400, 200, 1.5)

# --- home screen and drawer ---------------------------------------------------------------------------------------
home()
def x3(f):
    def run():
        for _ in range(3): f()
    return run
measure("home idle 3 s", lambda: time.sleep(3))
measure("drawer open + close x3", x3(lambda: (open_drawer(), key("BACK", 1.2))))
def drawer_scroll():
    open_drawer()
    for _ in range(4): swipe(540, 1700, 540, 800, 300, 1); swipe(540, 1000, 540, 1600, 300, 1)
    key("BACK", 1.2)
measure("drawer scroll x8", drawer_scroll)
def drawer_tabs():
    open_drawer(); miss = []
    for _ in range(2):
        for t in ("Media", "Shopping", "All apps"):
            c = find(t)
            if c: tap(*c, wait=1.0)
            else: miss.append(t)
    key("BACK", 1.2)
    return "missing tabs: " + ", ".join(sorted(set(miss))) if miss else ""
measure("drawer tab switch x6", drawer_tabs)
def drawer_search():
    open_drawer(); c = find("Search apps")
    if not c: key("BACK"); return "no search field"
    tap(*c, wait=1.0); sh("input text camera"); time.sleep(1.5)
    for _ in range(6): key("DEL", 0.3)
    key("BACK", 1.0); key("BACK", 1.0); home()
measure("drawer search typing", drawer_search)
measure("folder open + close x3", x3(lambda: (tap(972, 1665, 1.2), key("BACK", 1.0))))
measure("home long-press menu x3", x3(lambda: (hold(540, 800, 1.2), key("BACK", 1.0))))
measure("app icon long-press x3", x3(lambda: (hold(108, 1947, 1.2), key("BACK", 1.0))))
measure("Now page in + out x3", x3(lambda: (swipe(200, 1200, 950, 1200, 240, 1.2), swipe(950, 1200, 200, 1200, 240, 1.2))))
measure("page swipe left x3 (edge)", x3(lambda: swipe(950, 1200, 200, 1200, 240, 1.2)))
measure("app launch + return x3", x3(lambda: (tap(108, 1947, 2.5), key("HOME", 2.0))))
home()

# --- Settings -----------------------------------------------------------------------------------------------------
PAGES = [("Home screen", ["Dock"]), ("App drawer", ["Tabs", "Folders"]), ("Folders", []), ("Search", []),
         ("Gestures & inputs", []), ("Now page", []), ("Notification badges", []), ("Look & feel", ["Custom colors"]),
         ("Backup & restore", []), ("Permissions", []), ("Contributors", [])]
sh("logcat -c")
hold(540, 800, 1.2)
c = find("Settings")
if not c: sys.exit("long-press menu has no Settings")
tap(*c, wait=2.0)
opened = re.findall(r"Displayed dev\.vulcanlauncher/\.SettingsActivity.*\+(\d+)ms", sh("logcat -d"))
print("Settings open:", opened[0] + " ms" if opened else "no Displayed line")

def browse():  # scroll the open page down and back up
    for _ in range(3): swipe(100, 1900, 100, 800, 300, 0.7)
    for _ in range(3): swipe(100, 800, 100, 1900, 300, 0.7)

def visit(name, path):
    def run():
        for i, p in enumerate(path):
            c = find(p, scroll=True, below=300)  # a row, not the title bar
            if not c:
                for _ in range(i): key("BACK", 1.0)  # back to Settings' top level for the next page
                return f"row '{p}' not found"
            tap(*c, wait=1.5)
        if title() != path[-1]: t = title(); key("BACK"); return f"landed on '{t}', backed out"
        browse()
        for _ in path: key("BACK", 1.0)
    measure(name, run)
    for _ in range(4): swipe(100, 900, 100, 1900, 200, 0.3)  # back to the top of Settings

for top, subs in PAGES:
    visit(f"Settings › {top}", [top])
    for s in subs: visit(f"Settings › {top} › {s}", [top, s])
def settings_search():
    c = find("Search settings")
    if not c: return "no search button"
    tap(*c, wait=1.0); sh("input text icon"); time.sleep(1.5); browse(); key("BACK", 0.8); key("BACK", 0.8)
measure("Settings search 'icon'", settings_search)
key("BACK", 1.0); home()

with open(os.path.join(OUT, f"{LABEL}-screens.txt"), "w") as o:
    o.write(f"label: {LABEL}  date: {time.strftime('%F %T')}  Settings open: {opened[0] + ' ms' if opened else '?'}\n")
    o.write("| scenario | frames | janky | p50 | p90 | p99 | worst ms | note |\n|---|---|---|---|---|---|---|---|\n")
    for r in rows: o.write("| " + " | ".join(map(str, r)) + " |\n")
print("saved", os.path.join(OUT, f"{LABEL}-screens.txt"))
