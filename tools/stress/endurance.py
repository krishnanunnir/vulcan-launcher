#!/usr/bin/env python3
# Endurance run for a leak or a slow-down that builds up: rounds of drawer, Now page, hold-home overview, folder and
# app launch-and-return, with memory and frame stats sampled after each round.
# Usage: ANDROID_SERIAL=<serial> tools/stress/endurance.py <label> [rounds=12] [folder label]
#        -> .local/stress/<label>-endurance.txt, exit 1 on FAIL
# FAIL: the launcher's process changed (a crash or a kill), a FATAL EXCEPTION or ANR of it in logcat, or views or
# heap (Java + native) more than 25% over round 1 at the end.
# Changes nothing: every step ends with Back or Home. Vulcan as the default home, unlocked, portrait. Clears logcat.
# Each round takes about 100 s. The folder loop runs only with a folder label that is on the page home opens on.
import os, re, subprocess, sys, time

P = "dev.vulcanlauncher"
if not os.environ.get("ANDROID_SERIAL"): sys.exit("set ANDROID_SERIAL (adb devices -l): with no serial adb may pick an emulator")
if len(sys.argv) < 2: sys.exit("usage: endurance.py <label> [rounds=12] [folder label]")
LABEL, ROUNDS, FOLDER = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 12, sys.argv[3] if len(sys.argv) > 3 else None
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".local", "stress")
os.makedirs(OUT, exist_ok=True)

def sh(cmd): return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, timeout=90).stdout
def key(k, wait=0.7): sh(f"input keyevent KEYCODE_{k}"); time.sleep(wait)

W, H = map(int, re.search(r"(\d+)x(\d+)", sh("wm size").strip().splitlines()[-1]).groups())
def at(fx, fy): return f"{int(W * fx)} {int(H * fy)}"  # a point as fractions of the screen
def swipe(a, b, ms, wait): sh(f"input swipe {a} {b} {ms}"); time.sleep(wait)

def find(label):
    for _ in range(3):  # uiautomator returns nothing while something animates
        x = subprocess.run(["adb", "exec-out", "uiautomator dump /dev/tty"], capture_output=True, text=True, timeout=60).stdout
        for n in re.findall(r"<node [^>]*>", x):
            if label in (re.search(r'text="([^"]*)"', n)[1], re.search(r'content-desc="([^"]*)"', n)[1]):
                b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n)[1])))
                return f"{(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}"
        if x: return None
        time.sleep(1)

def sample():
    m, g = sh(f"dumpsys meminfo {P}"), sh(f"dumpsys gfxinfo {P}")
    sh(f"dumpsys gfxinfo {P} reset")
    f = lambda pat, s: (re.search(pat, s) or [None, "0"])[1]
    return {"pid": sh(f"pidof {P}").strip(), "pss": int(f(r"TOTAL PSS:\s+(\d+)", m)) // 1024,
            "heap": (int(f(r"Java Heap:\s+(\d+)", m)) + int(f(r"Native Heap:\s+(\d+)", m))) // 1024,
            "graphics": int(f(r"Graphics:\s+(\d+)", m)) // 1024, "views": int(f(r"Views:\s+(\d+)", m)),
            "frames": f(r"Total frames rendered: (\d+)", g), "janky": f(r"Janky frames: \d+ \(([\d.]+%)\)", g),
            "p99": f(r"\n *99th percentile: (\d+)ms", g)}

def one_round(folder):
    for _ in range(20): swipe(at(.5, .64), at(.5, .17), 180, 0.9); key("BACK")               # drawer
    for _ in range(6): swipe(f"3 {int(H * .5)}", at(.65, .5), 200, 1.0); key("BACK", 0.8)     # Now page, from the left edge
    for _ in range(5): swipe(at(.5, .38), at(.5, .38), 800, 1.2); key("BACK", 0.8)            # hold home: the overview
    if folder:
        for _ in range(10): sh(f"input tap {folder}"); time.sleep(0.9); key("BACK")
    for _ in range(3): sh("am start -a android.settings.SETTINGS"); time.sleep(2); key("HOME", 1.5)

lines = []
def say(s): print(s, flush=True); lines.append(s)

sh("logcat -c")
key("HOME", 1); key("HOME", 2)
folder = find(FOLDER) if FOLDER else None
say(f"label: {LABEL}  date: {time.strftime('%F %T')}  serial: {os.environ['ANDROID_SERIAL']}  screen: {W}x{H}  rounds: {ROUNDS}")
say(sh(f"dumpsys package {P}").split("versionName=")[1].split()[0] if "versionName=" in sh(f"dumpsys package {P}") else "version ?")
say("folder loop: " + (f"'{FOLDER}' at {folder}" if folder else "skipped" + (f" ('{FOLDER}' not on screen)" if FOLDER else " (no folder label given)")))
say("| round | pid | PSS MB | heap MB | graphics MB | views | frames | janky | p99 ms |")
rows = []
for r in range(ROUNDS + 1):
    if r: one_round(folder)
    s = sample(); rows.append(s)
    say(f"| {r} | {s['pid']} | {s['pss']} | {s['heap']} | {s['graphics']} | {s['views']} | {s['frames']} | {s['janky']} | {s['p99']} |")

key("HOME", 1)
log = subprocess.run(["adb", "logcat", "-d"], capture_output=True, text=True, timeout=90).stdout
bad = [l for l in log.splitlines() if "ANR in " + P in l] + re.findall(r".*FATAL EXCEPTION.*\n.*Process: " + re.escape(P) + ".*", log)
first, last = rows[min(1, ROUNDS)], rows[-1]  # round 1 is the baseline: the drawer and panels are built by then
fails = []
if len({s["pid"] for s in rows}) > 1 or not last["pid"]: fails.append("the launcher's process changed: " + " -> ".join(dict.fromkeys(s["pid"] or "gone" for s in rows)))
if bad: fails.append(f"{len(bad)} crash or ANR line(s) in logcat, first: {bad[0].strip()[:160]}")
for k in ("views", "heap"):
    if last[k] > first[k] * 1.25: fails.append(f"{k} grew from {first[k]} (round 1) to {last[k]}")
say("RESULT: " + ("PASS" if not fails else "FAIL: " + "; ".join(fails)))
path = os.path.join(OUT, f"{LABEL}-endurance.txt")
open(path, "w").write("\n".join(lines) + "\n")
print("saved", os.path.normpath(path))
sys.exit(1 if fails else 0)
