"""Which kinds of games see a friend's controller (wc-input + ViGEmBus) while another window is in front?

  python tools/bench/padapis.py WC-INPUT.EXE PADGAME.EXE SDL-PYTHON.EXE tools/bench/sdlgame.py

PADGAME.EXE: tools/bench/padgame (cargo build --release; XInput / Windows.Gaming.Input stand-ins);
SDL-PYTHON.EXE: a Python with pygame (SDL 2), e.g. a venv. Opens the stand-ins and a front window,
clicks the front window (moves the mouse), presses A eight times, then each stand-in in front.
"""
import ctypes, subprocess, sys, threading, time
WCINPUT, PADGAME, VPY, SDLGAME = sys.argv[1:5]
u = ctypes.windll.user32
games = [("XInput game", [PADGAME, "xinput", "XInput game", "40", "40"]),
         ("Windows.Gaming.Input game", [PADGAME, "wgi", "Windows.Gaming.Input game", "420", "40"]),
         ("SDL game (as shipped)", [VPY, "-I", SDLGAME, "SDL game (as shipped)", "40", "300", "0"]),
         ("SDL game (background allowed)", [VPY, "-I", SDLGAME, "SDL game (background allowed)", "420", "300", "1"])]
logs, hwnds, procs = {}, {}, []

def reader(name, p):
    for line in p.stdout:
        line = line.strip()
        if line.startswith("ready"):
            hwnds[name] = int(line.split("hwnd")[1])
        elif line.startswith("A "):
            logs.setdefault(name, []).append(line)

wc = subprocess.Popen([WCINPUT], stdin=subprocess.PIPE, text=True)
def pad(a):
    wc.stdin.write("slot 1 pad %d 0 0 0 0 0 0%s" % (0x1000 if a else 0, chr(10))); wc.stdin.flush()
pad(False); time.sleep(1.0)
for name, cmd in games:
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)
    procs.append(p); threading.Thread(target=reader, args=(name, p), daemon=True).start()
front = subprocess.Popen([PADGAME, "front", "Front window (stand-in Minecraft)", "800", "40"], stdout=subprocess.PIPE, text=True)
procs.append(front); threading.Thread(target=reader, args=("front", front), daemon=True).start()
time.sleep(4)

def click_title(h):
    class R(ctypes.Structure): _fields_ = [("l", ctypes.c_long), ("t", ctypes.c_long), ("r", ctypes.c_long), ("b", ctypes.c_long)]
    r = R(); u.GetWindowRect(h, ctypes.byref(r))
    u.SetCursorPos((r.l + r.r) // 2, r.t + 12); time.sleep(0.15)
    u.mouse_event(2, 0, 0, 0, 0); time.sleep(0.05); u.mouse_event(4, 0, 0, 0, 0); time.sleep(0.5)
    return u.GetForegroundWindow() == h

def presses(n):
    ts = []
    for _ in range(n):
        ts.append(time.perf_counter_ns()); pad(True); time.sleep(0.3); pad(False); time.sleep(0.4)
    return ts

print("front window in front:", click_title(hwnds["front"]))
bg_start = time.perf_counter_ns(); bg = presses(8); bg_end = time.perf_counter_ns()
fg = {}
for name, _ in games:
    ok = click_title(hwnds[name]) if name in hwnds else False
    s = time.perf_counter_ns(); presses(3); fg[name] = (ok, s, time.perf_counter_ns())
wc.stdin.write("slot 1 end" + chr(10)); wc.stdin.flush(); time.sleep(0.5)
for p in procs: p.kill()
wc.stdin.close()

def seen(name, a, b, want_fg):
    downs = [l.split() for l in logs.get(name, []) if l.startswith("A down")]
    return [(int(d[2]), d[3]) for d in downs if a <= int(d[2]) <= b and d[3] == "fg=%d" % want_fg]

print()
for name, _ in games:
    got = seen(name, bg_start, bg_end, 0)
    lat = sorted((t - max(x for x in bg if x <= t)) / 1e6 for t, _ in got) if got else []
    ok, a, b = fg[name]
    fgot = seen(name, a, b, 1)
    print("%-32s not in front: %d of 8%s   in front: %d of 3%s" % (name, len(got), " (median %.1f ms)" % lat[len(lat) // 2] if lat else "",
          len(fgot), "" if ok else " (couldn't bring it to the front)"))
