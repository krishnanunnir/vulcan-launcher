#!/usr/bin/env python3
# Broken copies of a real backup, for Settings > Backup & restore > Restore from a file: none may crash the launcher
# or lose the layout it had. Made from your own backup so the apps in them are installed.
# Usage: tools/stress/hostile-backups.py <backup.json> [out dir=.local/stress/hostile]
#        then: adb push <out dir>/. /sdcard/Download/ , restore each, and delete them afterwards.
# The first six must be refused with nothing changed. The rest may be accepted, clamped or repaired: an accepted
# restore replaces the layout and rotates the automatic backups, so run those on an emulator or after saving a backup.
import copy, json, os, sys

if len(sys.argv) < 2: sys.exit("usage: hostile-backups.py <backup.json> [out dir]")
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".local", "stress", "hostile")
os.makedirs(out, exist_ok=True)
raw = open(sys.argv[1]).read()
base = json.loads(raw)
apps = [i for i in base["home"]["items"] if "pkg" in i and "widgetId" not in i]
if not apps: sys.exit("the backup has no app icon on home to work with")

files = {}
def changed(name, edit):
    d = copy.deepcopy(base); edit(d); files[name] = json.dumps(d)
def items(d): return d["home"]["items"]
def app(d, n): a = [i for i in items(d) if "pkg" in i and "widgetId" not in i]; return a[n % len(a)]

# Refused: not a backup at all.
files["refuse-1-truncated"] = raw[:len(raw) // 2]
files["refuse-2-empty-object"] = "{}"
files["refuse-3-array"] = "[1,2,3]"
files["refuse-4-binary"] = None
files["refuse-5-newer-format"] = json.dumps(dict(base, format="vulcan-backup-99"))
changed("refuse-6-home-wrong-shape", lambda d: d.update(home={"items": "nope"}))

# Read, and put right.
def wrong_types(d):
    d["home"].update(pages="abc", defaultPage=[1], sub=7)
    d["settings"].update(cols="banana", iconSize=99999, labelColor="#zzz", labels=True)
changed("repair-1-wrong-types", wrong_types)
def huge_counts(d):
    d["home"].update(pages=100000, defaultPage=-5); d["settings"]["rows"] = "2147483647"
changed("repair-2-huge-counts", huge_counts)
def off_grid(d):
    app(d, 0).update(x=2147483647, y=-3); app(d, 1).update(w=0, h=-1); app(d, 2).update(page=500)
    app(d, 3).update(page=-999999); app(d, 4).update(x="zz")
changed("repair-3-off-grid", off_grid)
def duplicates(d):
    items(d).extend(copy.deepcopy(app(d, 0)) for _ in range(5)); d["home"].update(pages=9, defaultPage=8)
changed("repair-4-duplicates", duplicates)
def odd_items(d):
    deep = {"page": 0, "x": 0, "y": 0, "folder": True, "label": "deep", "children": [copy.deepcopy(app(d, 0))]}
    for _ in range(50): deep = {"page": 0, "x": 0, "y": 0, "folder": True, "label": "deep", "children": [deep]}
    items(d).extend([
        dict(deep, x=1, y=3),
        {"page": 0, "x": 2, "y": 3, "folder": True, "label": "empty", "children": []},
        dict(copy.deepcopy(app(d, 0)), page=0, x=3, y=3, label="L" * 200000),
        {"page": 0, "x": 0, "y": 3, "pkg": "no.such.pkg", "cls": "X"},
        {"page": 0, "x": 0, "y": 4, "action": "bogus_action", "label": "bogus"},
        dict(copy.deepcopy(app(d, 0)), page=0, x=1, y=4, icon="pack:no.pack:x", swipe="page:99")])
changed("repair-5-odd-items", odd_items)
def many(d):
    a = app(d, 0)
    items(d).extend(dict(copy.deepcopy(a), page=n % 9, x=n % 5, y=(n // 5) % 6) for n in range(3000)); d["home"]["pages"] = 9
changed("repair-6-3000-items", many)

for name, text in files.items():
    p = os.path.join(out, f"stress-{name}.json")
    if text is None: open(p, "wb").write(os.urandom(4096))
    else: open(p, "w").write(text)
    print(f"{os.path.getsize(p):>9}  {os.path.normpath(p)}")
