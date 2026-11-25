#!/usr/bin/env python3
"""UI sweep harness for Vulcan on a real device.

For every setting in settings.json and every one of its values: apply the value
(plus a per-setting context and layout variant), cold-start the launcher, run a
probe (home / drawer / folder / popup / ...), screenshot, and record crashes and
logcat warnings. Resumable: states already in results.jsonl are skipped.

Usage:
  python3 harness.py --phase main          # the big sweep
  python3 harness.py --phase special       # fresh install, settings pages, dialogs, groups, hidden
  python3 harness.py --phase gestures      # gesture actions + gesture keys + home button
  python3 harness.py --only cols           # one setting
"""
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape as xesc

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = f"{ROOT}/out/sweep1"
PKG = "dev.vulcanlauncher"
ACT = "dev.vulcanlauncher/.MainActivity"
W, H = 1080, 2340

# Before the first adb call below: with a phone and an emulator attached, every adb command would fail.
if not os.environ.get("ANDROID_SERIAL") and len([l for l in subprocess.run(
        ["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip()]) > 1:
    sys.exit("More than one device attached: set ANDROID_SERIAL (adb devices -l)")

# ---------------------------------------------------------------- testbed layout

def item(page, x, y, w=1, h=1, **kw):
    d = dict(page=page, x=x, y=y, w=w, h=h, pkg="", cls="", widgetId=-1, user=0,
             shortcutId="", action="", folder=False, children=[], label="", icon="", swipe="")
    d.update(kw)
    return d


if subprocess.run(["adb", "shell", "pm path com.sec.android.app.clockpackage"], capture_output=True).returncode == 0:
    # Galaxy S23
    CLOCK = ("com.sec.android.app.clockpackage", "com.sec.android.app.clockpackage.ClockPackage")
    CAMERA = ("com.sec.android.app.camera", "com.sec.android.app.camera.Camera")
    GALLERY = ("com.sec.android.gallery3d", "com.samsung.android.gallery.app.activity.GalleryActivity")
    SETTINGS = ("com.android.settings", "com.android.settings.Settings")
    MESSAGES = ("com.samsung.android.messaging", "com.android.mms.ui.ConversationComposer")
    DIALER = ("com.samsung.android.dialer", "com.samsung.android.dialer.DialtactsActivity")
    FIREFOX = ("org.mozilla.firefox", "org.mozilla.firefox.App")
    CONTACTS = ("com.samsung.android.app.contacts", "com.samsung.android.contacts.contactslist.PeopleActivity")
else:
    # Google APIs emulator (tools/emulators.sh); Chrome stands in for Firefox
    CLOCK = ("com.google.android.deskclock", "com.android.deskclock.DeskClock")
    CAMERA = ("com.android.camera2", "com.android.camera.CameraLauncher")
    GALLERY = ("com.google.android.apps.photos", "com.google.android.apps.photos.home.HomeActivity")
    SETTINGS = ("com.android.settings", "com.android.settings.Settings")
    MESSAGES = ("com.google.android.apps.messaging", "com.google.android.apps.messaging.ui.ConversationListActivity")
    DIALER = ("com.google.android.dialer", "com.google.android.dialer.extensions.GoogleDialtactsActivity")
    FIREFOX = ("com.android.chrome", "com.google.android.apps.chrome.Main")
    CONTACTS = ("com.google.android.contacts", "com.android.contacts.activities.PeopleActivity")


def app_item(page, x, y, app, **kw):
    return item(page, x, y, pkg=app[0], cls=app[1], **kw)


def child(app):
    return item(0, 0, 0, pkg=app[0], cls=app[1])


def base_layout():
    return [
        app_item(0, 0, 1, CLOCK),
        item(0, 1, 1, folder=True, label="Tools",
             children=[child(SETTINGS), child(CAMERA), child(GALLERY)]),
        app_item(0, 2, 1, FIREFOX),
        app_item(0, 3, 1, CONTACTS, label="A very long app label that wraps"),
        app_item(1, 1, 1, MESSAGES),
    ] + [app_item(-1, i, 0, a) for i, a in enumerate([DIALER, MESSAGES, FIREFOX, CAMERA])]


def dock9_layout():
    apps = [DIALER, MESSAGES, FIREFOX, CAMERA, SETTINGS, GALLERY, CLOCK, CONTACTS, DIALER]
    return base_layout()[:5] + [app_item(-1, i, 0, a) for i, a in enumerate(apps)]


def dock3p_layout():
    items = base_layout()[:5]
    items += [app_item(-1, i, 0, a) for i, a in enumerate([DIALER, MESSAGES, FIREFOX])]
    items += [app_item(-2, i, 0, a) for i, a in enumerate([CAMERA, SETTINGS])]
    items += [app_item(-3, 0, 0, CLOCK)]
    return items


def subgrid_layout():
    """The real subgrid state: the user enabled it, so all items sit in half-cell units."""
    items = base_layout()
    for it in items:
        if it["page"] >= 0:
            it.update(x=it["x"] * 2, y=it["y"] * 2, w=it["w"] * 2, h=it["h"] * 2)
    return items


LAYOUTS = {"base": base_layout, "dock9": dock9_layout, "dock3p": dock3p_layout, "subgridScaled": subgrid_layout}

# ---------------------------------------------------------------- probe helpers


class Miss(Exception):
    """A text-based step could not find its target."""


class Runner:
    def __init__(self):
        self.done = self._load_done()
        self.note = ""

    @staticmethod
    def _load_done():
        done = set()
        f = f"{OUT}/results.jsonl"
        if os.path.exists(f):
            for line in open(f):
                try:
                    e = json.loads(line)
                except Exception:
                    continue
                if e.get("error") or e.get("crash"):
                    continue  # re-run failed or errored states
                done.add(e["sid"])
        return done

    def sh(self, args, timeout=30, binary=False, stdin=None):
        cmd = ["adb"] + args
        try:
            p = subprocess.run(cmd, capture_output=True, timeout=timeout, input=stdin)
        except subprocess.TimeoutExpired:
            return 1, b"" if binary else ""
        out = p.stdout
        return p.returncode, out if binary else out.decode("utf-8", "replace")

    def shell(self, cmd, timeout=30):
        return self.sh(["shell", cmd], timeout=timeout)[1]

    def sleep(self, s):
        time.sleep(s)

    # ---- device state ----
    def force_stop(self):
        self.shell(f"am force-stop {PKG}")

    def write_prefs(self, prefs, fresh=False):
        """prefs: {'layout'|'looks'|'data': dict[str,str]}"""
        self.force_stop()
        # The system restarts the home app at once and its onCreate saves the old layout (async apply):
        # let that write land before ours, or it clobbers the new state.
        self.sleep(1.2)
        if fresh:
            self.shell(f"run-as {PKG} sh -c 'rm -f /data/data/{PKG}/shared_prefs/*.xml'")
            return
        for name, d in prefs.items():
            body = "".join(f'    <string name="{k}">{xesc(v, {chr(34): "&quot;"})}</string>\n' for k, v in sorted(d.items()))
            xml = f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n{body}</map>\n"
            # Atomic: the home app restarts right after force-stop and may read the file mid-write
            # (no "items" -> it saves a default dock over the testbed).
            f = f"/data/data/{PKG}/shared_prefs/{name}.xml"
            cmd = ["shell", f"run-as {PKG} sh -c 'cat > {f}.tmp && mv {f}.tmp {f}'"]
            p = subprocess.run(["adb"] + cmd, input=xml.encode(), capture_output=True, timeout=20)
            if p.returncode != 0:
                raise RuntimeError(f"prefs write failed: {p.stderr.decode()}")
        # The auto-restarted process cached the old prefs in memory; kill it so the next one reads these.
        self.force_stop()

    def start(self):
        # Undo leftovers of the previous state's probe: an open notification shade, a forced rotation.
        self.shell("cmd statusbar collapse; settings put system accelerometer_rotation 0; settings put system user_rotation 0")
        self.shell("logcat -c")
        self.shell(f"am start -W -n {ACT} >/dev/null 2>&1")
        self.sleep(1.15)

    def pid_alive(self):
        return bool(self.shell(f"pidof {PKG}").strip())

    def logcat_greps(self):
        pid = self.shell(f"pidof {PKG}").strip().split()
        hits = []
        if pid:
            txt = self.shell(f"logcat -d -v brief --pid {pid[0]}")
            hits = [l.strip() for l in txt.splitlines() if re.search(r" E | W |FATAL", l)]
        alllog = self.shell("logcat -d -v brief -e 'FATAL EXCEPTION' -e 'ANR in dev.vulcanlauncher'")
        hits += [l.strip() for l in alllog.splitlines() if ("FATAL" in l or "ANR in" in l) and "adbd" not in l]
        return hits[:12]

    # ---- screenshots ----
    def shot(self, state_dir, suffix):
        os.makedirs(state_dir, exist_ok=True)
        path = f"{state_dir}/{suffix}.png"
        rc, data = self.sh(["exec-out", "screencap", "-p"], binary=True, timeout=30)
        if rc != 0 or not data.startswith(b"\x89PNG"):
            raise RuntimeError(f"screencap failed rc={rc}")
        with open(path, "wb") as fh:
            fh.write(data)
        return path

    # ---- input ----
    def tap(self, x, y):
        self.shell(f"input tap {int(x)} {int(y)}")

    def key(self, code):
        self.shell(f"input keyevent {code}")

    def swipe(self, x1, y1, x2, y2, ms):
        self.shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

    def longpress(self, x, y, ms=800):
        self.shell(f"input motionevent DOWN {int(x)} {int(y)}")
        self.sleep(ms / 1000)
        self.shell(f"input motionevent UP {int(x)} {int(y)}")

    def drag(self, x1, y1, x2, y2, steps=10, hold=0.8):
        self.shell(f"input motionevent DOWN {int(x1)} {int(y1)}")
        self.sleep(hold)
        for i in range(1, steps + 1):
            x = x1 + (x2 - x1) * i / steps
            y = y1 + (y2 - y1) * i / steps
            self.shell(f"input motionevent MOVE {int(x)} {int(y)}")
            self.sleep(0.03)
        self.sleep(0.15)
        self.shell(f"input motionevent UP {int(x2)} {int(y2)}")

    def shell_seq(self, cmds):
        """Run several input commands in one device-side shell (for tight timing)."""
        self.shell("; ".join(cmds))

    # ---- ui dump ----
    def dump(self):
        for _ in range(4):
            self.shell("rm -f /sdcard/_ui.xml")
            self.shell("uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1")
            rc, data = self.sh(["exec-out", "cat /sdcard/_ui.xml"], binary=True, timeout=20)
            if rc == 0 and data.startswith(b"<?xml"):
                return data.decode("utf-8", "replace")
            self.sleep(0.7)
        raise Miss("dump failed after retries")

    def find(self, text, xml=None, index=0):
        xml = xml or self.dump()
        root = ET.fromstring(xml)
        found = []
        for node in root.iter("node"):
            if (node.get("text") or "").lower() == text.lower():
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if m:
                    a, b, c, d = map(int, m.groups())
                    found.append(((a + c) / 2, (b + d) / 2))
        if index >= len(found):
            raise Miss(f"text {text!r} not found")
        return found[index]

    def tap_text(self, text, index=0):
        x, y = self.find(text, index=index)
        self.tap(x, y)
        return x, y

    def longpress_text(self, text, ms=800, index=0):
        x, y = self.find(text, index=index)
        self.longpress(x, y, ms)
        return x, y

    def swipe_text_up(self, text, dy=280, ms=200):
        x, y = self.find(text)
        self.swipe(x, y, x, y - dy, ms)
        return x, y


# ---------------------------------------------------------------- probes

def p_home(r, d):
    return [("0", r.shot(d, "0"))]


def p_drawer(r, d):
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(0.9)
    return [("0", r.shot(d, "0"))]


def p_menu(r, d):
    r.longpress(540, 1650, 800)
    r.sleep(0.7)
    return [("0", r.shot(d, "0"))]


def p_settings(r, d):
    shots = []
    r.longpress(540, 1650, 800)
    r.sleep(0.8)
    try:
        r.tap_text("Settings")
    except Miss:
        return [("0_menu", r.shot(d, "0_menu")), ("MISS", r.shot(d, "MISS"))]
    r.sleep(1.0)
    shots.append(("0", r.shot(d, "0")))
    return shots


def p_folder(r, d):
    r.tap_text("Tools")
    r.sleep(0.9)
    return [("0", r.shot(d, "0"))]


def p_folder_swipe_open(r, d):
    """Swipe up on the folder icon: opens it when folderSwipe is on."""
    shots = []
    r.swipe_text_up("Tools", dy=300, ms=180)
    r.sleep(1.0)
    shots.append(("0_swipe", r.shot(d, "0_swipe")))
    return shots


def p_folder_swipe_tap(r, d):
    """Tap the folder: launches the first child when folderSwipe is on."""
    shots = []
    r.tap_text("Tools")
    r.sleep(1.8)
    shots.append(("0_tap", r.shot(d, "0_tap")))
    r.key("HOME")
    r.sleep(0.7)
    shots.append(("1_back", r.shot(d, "1_back")))
    return shots


def p_popup(r, d):
    shots = []
    try:
        r.longpress_text("Clock", 800)
    except Miss:
        return [("MISS", r.shot(d, "MISS"))]
    r.sleep(0.8)
    shots.append(("0", r.shot(d, "0")))
    return shots


def p_now(r, d):
    r.swipe(200, 1700, 950, 1700, 240)
    r.sleep(1.0)
    return [("0", r.shot(d, "0"))]


def p_midswipe(r, d):
    shots = []
    r.shell("input motionevent DOWN 900 1700")
    for x in range(850, 380, -40):
        r.shell(f"input motionevent MOVE {x} 1700")
        r.sleep(0.02)
    r.sleep(0.15)
    shots.append(("0_mid", r.shot(d, "0_mid")))
    r.shell("input motionevent UP 380 1700")
    r.sleep(0.9)
    shots.append(("1_after", r.shot(d, "1_after")))
    return shots


def p_page2(r, d):
    r.swipe(950, 1700, 250, 1700, 240)
    r.sleep(0.9)
    return [("0", r.shot(d, "0"))]


def p_infinite(r, d):
    shots = []
    r.swipe(950, 1700, 250, 1700, 240)
    r.sleep(0.5)
    r.swipe(950, 1700, 250, 1700, 240)
    r.sleep(0.7)
    shots.append(("0_last", r.shot(d, "0_last")))
    r.swipe(950, 1700, 250, 1700, 240)
    r.sleep(0.9)
    shots.append(("1_wrap", r.shot(d, "1_wrap")))
    return shots


def p_search(r, d, query="clock"):
    shots = []
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(0.9)
    try:
        for t in ("Search", "Search apps", "Search for apps"):
            try:
                r.tap_text(t)
                break
            except Miss:
                continue
        else:
            raise Miss("search slot")
    except Miss:
        shots.append(("MISS", r.shot(d, "MISS")))
        return shots
    r.sleep(0.5)
    r.shell(f'input text "{query}"')
    r.sleep(1.3)
    shots.append(("0", r.shot(d, "0")))
    return shots


def p_pullsearch(r, d):
    shots = []
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(0.9)
    r.swipe(540, 1400, 540, 2050, 260)
    r.sleep(0.9)
    shots.append(("0", r.shot(d, "0")))
    return shots


def p_remember(r, d):
    shots = []
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(0.9)
    r.swipe(540, 1900, 540, 1200, 300)
    r.sleep(0.6)
    shots.append(("0_scrolled", r.shot(d, "0_scrolled")))
    r.key("BACK")
    r.sleep(0.6)
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(1.0)
    shots.append(("1_reopened", r.shot(d, "1_reopened")))
    return shots


def p_launch(r, d):
    shots = []
    r.swipe(540, 2120, 540, 1100, 240)
    r.sleep(0.9)
    try:
        r.tap_text("Clock")
    except Miss:
        shots.append(("MISS", r.shot(d, "MISS")))
        return shots
    r.sleep(1.8)
    shots.append(("0_launched", r.shot(d, "0_launched")))
    r.key("HOME")
    r.sleep(0.9)
    shots.append(("1_back", r.shot(d, "1_back")))
    return shots


def p_homekey(r, d):
    shots = []
    r.swipe(950, 1700, 250, 1700, 240)
    r.sleep(0.7)
    shots.append(("0_page2", r.shot(d, "0_page2")))
    r.key("HOME")
    r.sleep(0.9)
    shots.append(("1_after_home", r.shot(d, "1_after_home")))
    return shots


def p_drag(r, d):
    """Long-press-drag the Clock icon with `input draganddrop`; records where it lands (subgrid)."""
    shots = []
    x, y = r.find("Clock")
    tx, ty = 475, 1650  # half a cell right (odd subcell), lower empty row
    r.shell(f"input draganddrop {int(x)} {int(y)} {int(tx)} {int(ty)} 900")
    r.sleep(1.0)
    shots.append(("0_dropped", r.shot(d, "0_dropped")))
    try:
        nx, ny = r.find("Clock")
        r.note = f"drop from({int(x)},{int(y)}) to({int(nx)},{int(ny)})"
    except Miss:
        r.note = "Clock gone after drag (merged)"
    return shots


def p_dragwiggle(r, d):
    """A slow drag held mid-way: icons wiggle while the drop bar shows."""
    shots = []
    x, y = r.find("Clock")
    p = subprocess.Popen(["adb", "shell", f"input draganddrop {int(x)} {int(y)} {int(x + 80)} {int(y)} 3000"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    r.sleep(1.5)
    shots.append(("0_dragging", r.shot(d, "0_dragging")))
    p.wait(timeout=15)
    r.sleep(0.9)
    shots.append(("1_end", r.shot(d, "1_end")))
    return shots


PROBES = {
    "home": p_home,
    "drawer": p_drawer,
    "menu": p_menu,
    "settings": p_settings,
    "folder": p_folder,
    "folderSwipeOpen": p_folder_swipe_open,
    "folderSwipeTap": p_folder_swipe_tap,
    "popup": p_popup,
    "now": p_now,
    "midswipe": p_midswipe,
    "page2": p_page2,
    "infinite": p_infinite,
    "search": p_search,
    "pullsearch": p_pullsearch,
    "remember": p_remember,
    "launch": p_launch,
    "homekey": p_homekey,
    "drag": p_drag,
    "dragwiggle": p_dragwiggle,
}

# ---------------------------------------------------------------- sweep config

# extra looks for a setting's states (to make the value visible / meaningful)
CONTEXT = {
    "defaultPage": {"pages": "9"},
    "searchStyle": {"searchBar": "dockBelow"},
    "searchColor": {"searchBar": "dockBelow"},
    "dockCols": {},
    "dockPages": {"dockCols": "3"},
    "dockColor": {"dockBg": "rounded"},
    "dockAlpha": {"dockBg": "rounded"},
    "dockShadow": {"dockBg": "rounded"},
    "labelSize": {},
    "badgePos": {"badges": "numeric"},
    "badgeSize": {"badges": "numeric"},
    "badgeColor": {"badges": "numeric"},
    "nightStart": {"nightMode": "schedule", "nightEnd": "13"},
    "nightEnd": {"nightMode": "schedule", "nightStart": "12"},
    "webSearch": {},
    "contactSearch": {},
}

# layout variant per setting, or per (setting, value)
LAYOUT_FOR = {
    "dockCols": "dock9",
    "dockPages": "dock3p",
}
LAYOUT_BY_VALUE = {
    ("subgrid", "on"): "subgridScaled",
}

# probes per setting (first is primary)
PROBE_FOR = {
    "searchBar": ["home"],
    "searchStyle": ["home"],
    "searchColor": ["home"],
    "scrollEffect": ["midswipe"],
    "infiniteScroll": ["infinite"],
    "wallpaperScroll": ["page2"],
    "subgrid": ["drag"],
    "lockLayout": ["popup", "drag"],
    "widgetOverlap": ["home"],
    "closeAfterLaunch": ["launch"],
    "pullToSearch": ["pullsearch"],
    "rememberPosition": ["remember"],
    "fastScroll": ["drawer"],
    "webSearch": ["search"],
    "searchEngine": ["search"],
    "contactSearch": ["search"],
    "frequent": ["drawer"],
    "foldersFirst": ["drawer"],
    "keepInAll": ["drawer"],
    "workTab": ["drawer"],
    "drawerStyle": ["drawer", "settings"],
    "drawerTheme": ["drawer", "settings"],
    "drawerAlpha": ["drawer"],
    "drawerCols": ["drawer"],
    "drawerRows": ["drawer"],
    "drawerIconSize": ["drawer"],
    "drawerLabels": ["drawer"],
    "drawerSort": ["drawer"],
    "showKeyboard": ["drawer"],
    "accent": ["drawer", "settings", "menu"],
    "blur": ["drawer", "settings"],
    "launchAnim": ["launch"],
    "wiggle": ["dragwiggle"],
    "shape": ["home", "drawer"],
    "reshape": ["home", "drawer"],
    "autogen": ["home"],
    "normalize": ["home"],
    "iconShadow": ["home"],
    "folderPreview": ["folder"],
    "folderBg": ["folder"],
    "folderColor": ["folder"],
    "folderClip": ["folder"],
    "folderFirstBig": ["folder"],
    "folderWindow": ["folder"],
    "folderNames": ["home", "folder"],
    "folderSwipe": ["folderSwipeOpen", "folderSwipeTap"],
    "badges": ["home", "popup"],
    "badgePos": ["home", "popup"],
    "badgeSize": ["home", "popup"],
    "badgeColor": ["home", "popup"],
    "nightMode": ["home", "drawer", "settings"],
    "nightStart": ["home", "drawer"],
    "nightEnd": ["home", "drawer"],
    "nowClock": ["now"],
    "nowMedia": ["now"],
    "nowCalendar": ["now"],
    "nowContacts": ["now"],
    "nowApps": ["now"],
    "glance": ["home"],
    "statusBar": ["home"],
    "hideClock": ["home"],
    "darkStatusIcons": ["home"],
    "orientation": ["home"],
    "indicator": ["home"],
    "popupShortcuts": ["popup"],
    "popupNotifications": ["popup"],
    "closeApp": ["popup"],
    "autoAdd": ["home"],
}

def slug(v):
    s = re.sub(r"[^0-9a-zA-Z]+", "_", v).strip("_")
    return s[:40] if s else "x"


def state_prefs(setting, value, ctx=None, layout="base"):
    looks = dict(ctx or {})
    looks[setting] = value
    layout_items = LAYOUTS[layout]()
    return {
        "looks": looks,
        "layout": {"items": json.dumps(layout_items, separators=(",", ":"))},
        "data": {},
    }


def run_state(r, sid, setting, value, probe, prefs, fresh=False, extra_steps=None):
    if sid in r.done:
        return True
    d = f"{OUT}/{sid}"
    t0 = time.time()
    r.note = ""
    entry = dict(sid=sid, setting=setting, value=value, probe=probe, shots=[], crash=False,
                 pidok=True, logs=[], ms=0, miss=False, note="")
    try:
        r.write_prefs(prefs, fresh=fresh)
        r.start()
        pidok = r.pid_alive()
        entry["pidok"] = pidok
        if not pidok:
            entry["crash"] = True
        if extra_steps:
            extra_steps(r, d)
        else:
            shots = PROBES[probe](r, d)
            entry["shots"] = [s[1] for s in shots]
            entry["miss"] = any(s[0] == "MISS" for s in shots)
        entry["note"] = r.note
        entry["logs"] = r.logcat_greps()
    except Exception as e:
        entry["error"] = f"{type(e).__name__}: {e}"
        entry["crash"] = True
    entry["ms"] = int((time.time() - t0) * 1000)
    with open(f"{OUT}/results.jsonl", "a") as fh:
        fh.write(json.dumps(entry) + "\n")
    r.done.add(sid)
    flag = "CRASH" if entry.get("crash") else ("miss" if entry.get("miss") else "ok")
    print(f"{flag:5} {sid} ({entry['ms']}ms)", flush=True)
    return not entry.get("crash")


# ---------------------------------------------------------------- the owner's setup

# Outside any --out run directory, so a later run with another --out still finds what an aborted one left.
BEFORE = f"{ROOT}/out/before"
ROTATION = ("accelerometer_rotation", "user_rotation")  # Runner.start sets both


def save_setup(r):
    """Copies the launcher's shared_prefs and the rotation settings to BEFORE, ahead of the sweep overwriting them."""
    serial = r.sh(["get-serialno"])[1].strip()
    if os.path.isdir(f"{BEFORE}/shared_prefs"):
        # A run that was killed or unplugged couldn't restore: this copy is the real setup, the device holds a testbed.
        with open(f"{BEFORE}/device.json") as fh:
            if json.load(fh)["serial"] != serial:
                sys.exit(f"{BEFORE} holds another device's setup: put it back there with restore.sh first")
        print(f"Keeping the setup an earlier run saved in {BEFORE}; it is put back at the end.", flush=True)
        return
    rc, names = r.sh(["shell", f"run-as {PKG} ls /data/data/{PKG}/shared_prefs"])
    if rc != 0:
        sys.exit(f"Can't read the launcher's settings, so nothing was changed (needs the debuggable build, see README): {names.strip()}")
    tmp = f"{BEFORE}.tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(f"{tmp}/shared_prefs")
    for n in names.split():
        if n.endswith(".xml"):
            rc, data = r.sh(["exec-out", f"run-as {PKG} cat /data/data/{PKG}/shared_prefs/{n}"], binary=True)
            if rc != 0 or not data:
                sys.exit(f"Couldn't copy {n}, so nothing was changed")
            with open(f"{tmp}/shared_prefs/{n}", "wb") as fh:
                fh.write(data)
    with open(f"{tmp}/device.json", "w") as fh:
        json.dump({"serial": serial, "rotation": {k: r.shell(f"settings get system {k}").strip() for k in ROTATION}}, fh)
    os.rename(tmp, BEFORE)  # only a complete copy counts as saved


def restore_setup(r):
    with open(f"{BEFORE}/device.json") as fh:
        for k, v in json.load(fh)["rotation"].items():
            r.shell(f"settings delete system {k}" if v == "null" else f"settings put system {k} {v}")
    subprocess.run(["sh", f"{ROOT}/restore.sh", BEFORE], check=True)
    shutil.rmtree(BEFORE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", default="main")
    ap.add_argument("--only", default=None)
    ap.add_argument("--pick", default=None, help="JSON file {setting: [values]}: run only these")
    ap.add_argument("--out", default=None, help="run directory (default out/sweep1)")
    args = ap.parse_args()
    global OUT
    OUT = os.path.abspath(args.out) if args.out else OUT
    pick = json.load(open(args.pick)) if args.pick else None
    os.makedirs(OUT, exist_ok=True)
    settings = json.load(open(f"{ROOT}/settings.json"))
    if args.phase != "main":
        sys.exit("phase not implemented yet")
    r = Runner()
    save_setup(r)
    # Ctrl-C raises KeyboardInterrupt; a closed terminal or kill sends these: all end in the finally below.
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda *_: sys.exit(1))
    try:
        for s in settings:
            if args.only and s["key"] != args.only or pick is not None and s["key"] not in pick:
                continue
            key = s["key"]
            probes = PROBE_FOR.get(key, ["home"])
            ctx = CONTEXT.get(key, {})
            for vi, v in enumerate(s["values"]):
                if pick is not None and v not in pick[key]:
                    continue
                layout = LAYOUT_BY_VALUE.get((key, v), LAYOUT_FOR.get(key, "base"))
                sid = f"{key}__{vi:02d}_{slug(v)}"
                prefs = state_prefs(key, v, ctx, layout)
                for pi, probe in enumerate(probes):
                    rsid = sid if pi == 0 else f"{sid}__p{pi}"
                    run_state(r, rsid, key, v, probe, prefs)
    finally:
        try:
            restore_setup(r)
        except Exception as e:
            sys.exit(f"Putting the setup back failed ({e}): run tools/ui-test/restore.sh {BEFORE}, then delete {BEFORE}")


if __name__ == "__main__":
    main()
