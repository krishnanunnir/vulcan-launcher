#!/usr/bin/env python3
"""Parse the LOOKS list from the app source into settings.json for the UI sweep.

Mirrors the helper constructors in Looks.kt (onOff, sizes, percent, color/range/listOf).
"""
import json
import os
import re
import sys

# This file lives in <repo>/tools/ui-test: the sources are two levels up.
ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(os.path.dirname(ROOT)), "app/src/main/java/dev/vulcanlauncher")

GROUPS = {
    "HOME": "Home screen",
    "DOCK_GROUP": "Dock",
    "DRAWER": "App drawer",
    "FOLDERS": "Folders",
    "LOOK": "Look & feel",
    "BADGES": "Notification badges",
    "GESTURES": "Gestures & inputs",
    "NOW": "Now page",
}


def read(f):
    with open(f"{SRC}/{f}") as fh:
        return fh.read()


def list_values(expr):
    """Turn a Kotlin list-of-strings expression into Python values."""
    expr = expr.strip()
    m = re.fullmatch(r"listOf\((.*)\)", expr, re.S)
    if m:
        out, i, body = [], 0, m.group(1)
        # split on top-level commas, strings may contain commas
        for part in re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)', body):
            part = part.strip()
            if not part:
                continue
            sm = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', part)
            if not sm:
                return None
            out.append(sm.group(1).replace('\\"', '"'))
        return out
    return None


def range_values(expr):
    m = re.fullmatch(r"range\((\d+),\s*(\d+)(?:,\s*(\d+))?\)", expr.strip())
    if not m:
        return None
    a, b, step = int(m.group(1)), int(m.group(2)), int(m.group(3) or 1)
    return [str(i) for i in range(a, b + 1, step)]


def split_items(block):
    """Split 'A, B(1,2), C' at top level commas."""
    items, depth, cur, instr = [], 0, [], False
    i = 0
    while i < len(block):
        c = block[i]
        if instr:
            cur.append(c)
            if c == "\\":
                i += 1
                if i < len(block):
                    cur.append(block[i])
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
                cur.append(c)
            elif c in "([":
                depth += 1
                cur.append(c)
            elif c in ")]":
                depth -= 1
                cur.append(c)
            elif c == "," and depth == 0:
                items.append("".join(cur).strip())
                cur = []
            else:
                cur.append(c)
        i += 1
    if "".join(cur).strip():
        items.append("".join(cur).strip())
    return [x for x in items if x]


def split_args(s):
    """Split an item like Name(a, b(c,d), "x") into [a, b(c,d), "x"]."""
    m = re.fullmatch(r"(\w+)\((.*)\)", s, re.S)
    if not m:
        raise ValueError(f"cannot parse item: {s[:80]}")
    name, body = m.group(1), m.group(2)
    args, depth, cur, instr = [], 0, [], False
    i = 0
    while i < len(body):
        c = body[i]
        if instr:
            cur.append(c)
            if c == "\\":
                i += 1
                if i < len(body):
                    cur.append(body[i])
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
                cur.append(c)
            elif c in "([":
                depth += 1
                cur.append(c)
            elif c in ")]":
                depth -= 1
                cur.append(c)
            elif c == "," and depth == 0:
                args.append("".join(cur).strip())
                cur = []
            else:
                cur.append(c)
        i += 1
    if "".join(cur).strip():
        args.append("".join(cur).strip())
    return name, args


def unquote(s):
    return re.fullmatch(r'"((?:[^"\\]|\\.)*)"', s).group(1).replace('\\"', '"')


def balanced_call(expr, name):
    """The balanced `name(...)` call at the start of [expr], or None."""
    if not expr.startswith(name + "("):
        return None
    depth, instr = 0, False
    i = len(name)
    while i < len(expr):
        c = expr[i]
        if instr:
            if c == "\\":
                i += 1
            elif c == '"':
                instr = False
        elif c == '"':
            instr = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return expr[: i + 1]
        i += 1
    return None


def main():
    looks_kt = read("Looks.kt")
    actions_kt = read("Actions.kt")
    effects_kt = read("Effects.kt")
    icons_kt = read("Icons.kt")
    folders_kt = read("Folders.kt")

    # symbol tables
    sym = {}
    for src in (looks_kt, actions_kt, effects_kt, icons_kt, folders_kt):
        for m in re.finditer(r"val (\w+) = listOf\((.*?)\)\n", src, re.S):
            vals = list_values(f"listOf({m.group(2)})")
            if vals is not None:
                sym[m.group(1)] = vals

    for name in ("COLOR_VALUES", "COLOR_NAMES", "EFFECTS", "EFFECT_NAMES", "SHAPES", "SHAPE_NAMES",
                 "PREVIEWS", "PREVIEW_NAMES", "FOLDER_BGS", "FOLDER_BG_NAMES", "ACTIONS", "ACTION_NAMES",
                 "GESTURE_KEYS", "GESTURE_NAMES", "GESTURE_DEFAULTS", "NIGHT_MODES", "NIGHT_MODE_NAMES",
                 "SEARCH_ENGINES", "SEARCH_ENGINE_NAMES", "SORTS", "SORT_NAMES"):
        if name not in sym:
            sys.exit(f"missing symbol table {name}")

    # the LOOKS list body up to the gesture map
    body = re.search(r"val LOOKS = listOf\((.*?)\n\) \+ GESTURE_KEYS\.indices\.map", looks_kt, re.S).group(1)

    def resolve_values(expr, default, key):
        v = list_values(expr)
        if v is not None:
            return v
        v = range_values(expr)
        if v is not None:
            return v
        if expr in sym:
            return sym[expr]
        if expr.startswith("listOf(") and "+" in expr:  # listOf("system") + COLOR_VALUES
            head = list_values(balanced_call(expr, "listOf"))
            tail = re.search(r"\+\s*(\w+)", expr).group(1)
            return head + sym[tail]
        if expr.startswith('range(' ):
            # range(3, 12) with names like "$it dp" handled by helper names below
            return None
        raise ValueError(f"{key}: cannot resolve values expr {expr[:60]}")

    entries = []
    for item in split_items(body):
        name, args = split_args(item)
        if name == "onOff":
            g = GROUPS[args[0]]
            key, title = unquote(args[1]), unquote(args[2])
            default = "on" if args[3].strip() == "true" else "off"
            entries.append(dict(group=g, key=key, title=title, default=default,
                                values=["on", "off"], names=["On", "Off"], action=False))
        elif name in ("sizes", "percent"):
            g = GROUPS[args[0]]
            key, title = unquote(args[1]), unquote(args[2])
            default = unquote(args[3]) if len(args) > 3 else "48"
            if name == "sizes":
                vs = [str(i) for i in range(32, 73, 4)]
                names = [f"{v} dp" for v in vs]
            else:
                vs = [str(i) for i in range(0, 101, 10)]
                names = [f"{v}%" for v in vs]
            entries.append(dict(group=g, key=key, title=title, default=default,
                                values=vs, names=names, action=False))
        elif name == "color":
            g = GROUPS[args[0]]
            key, title = unquote(args[1]), unquote(args[2])
            default = unquote(args[3])
            themed = len(args) > 4 and "themed = true" in " ".join(args[4:])
            vs = (["system"] if themed else []) + sym["COLOR_VALUES"]
            names = (["Wallpaper colors"] if themed else []) + sym["COLOR_NAMES"]
            entries.append(dict(group=g, key=key, title=title, default=default,
                                values=vs, names=names, action=False))
        elif name == "Choice":
            g = GROUPS[args[0]]
            key, title, default = unquote(args[1]), unquote(args[2]), unquote(args[3])
            vs = resolve_values(args[4], default, key)
            ns = None
            if len(args) > 5:
                ns = resolve_values(args[5], None, key) if not args[5].startswith("$") else None
            action = any("action = true" in a for a in args[5:])
            if ns is None:
                ns = vs
            if len(vs) != len(ns) and not action:
                sys.exit(f"{key}: values {len(vs)} names {len(ns)}")
            entries.append(dict(group=g, key=key, title=title, default=default,
                                values=vs, names=ns, action=action))
        else:
            sys.exit(f"unknown constructor {name}")

    # gestures: GESTURE_KEYS x ACTIONS, action=true
    if len(sym["GESTURE_KEYS"]) != len(sym["GESTURE_DEFAULTS"]):
        sys.exit("gesture key/default length mismatch")
    for i, key in enumerate(sym["GESTURE_KEYS"]):
        entries.append(dict(group="Gestures & inputs", key=key, title=sym["GESTURE_NAMES"][i],
                            default=sym["GESTURE_DEFAULTS"][i], values=sym["ACTIONS"],
                            names=sym["ACTION_NAMES"], action=True))

    # homeButton
    m = re.search(r'Choice\(GESTURES, "homeButton", "Home button", "default", listOf\((.*?)\), listOf\((.*?)\)\)', looks_kt, re.S)
    entries.append(dict(group="Gestures & inputs", key="homeButton", title="Home button", default="default",
                        values=list_values(f"listOf({m.group(1)})"), names=list_values(f"listOf({m.group(2)})"),
                        action=False))

    if len(entries) != 105:
        have = {(e["group"], e["key"]) for e in entries}
        src_keys = re.findall(r'(?:Choice|onOff|sizes|percent|color)\([A-Z_]+, "([A-Za-z_]+)"', looks_kt)
        missing = [k for k in src_keys if not any(k == e["key"] for e in entries)]
        sys.exit(f"expected 105 settings, parsed {len(entries)}; missing: {missing}")
    with open(os.path.join(ROOT, "settings.json"), "w") as fh:
        json.dump(entries, fh, indent=1)

    total = sum(len(e["values"]) for e in entries)
    print(f"settings: {len(entries)}  total values: {total}")
    for e in entries:
        print(f"  {e['group']:18} {e['key']:16} default={e['default']:8} values={len(e['values'])}")


if __name__ == "__main__":
    main()
