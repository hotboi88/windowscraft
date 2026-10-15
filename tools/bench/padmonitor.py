"""An SDL game started on a WindowsCraft monitor (by wc-vdisplay, as WindowsCraft does) gets a
friend's controller from behind another window; the same game started on your desk doesn't.

  python tools/bench/padmonitor.py WC-VDISPLAY.EXE WC-INPUT.EXE PADGAME.EXE SDL-PYTHON.EXE tools/bench/sdlgame.py OUTDIR

Adds an in-game monitor for the test (your screens blink), clicks a front window (moves the mouse).
"""
import ctypes, os, queue, subprocess, sys, threading, time
VD, WCI, PADGAME, VPY, SDLGAME, OUT = sys.argv[1:7]
u = ctypes.windll.user32
helper = subprocess.Popen([VD, "--parent-pid", str(os.getpid())], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
answers = queue.Queue()
threading.Thread(target=lambda: [answers.put(l.strip()) for l in helper.stdout], daemon=True).start()

def call(line, want, secs=30):
    helper.stdin.write(line + chr(10)); helper.stdin.flush()
    end = time.time() + secs
    while time.time() < end:
        try:
            l = answers.get(timeout=0.5)
        except queue.Empty:
            continue
        if l.startswith(want) or l.startswith("fail"):
            return l
    return None

added = call("add 1920 1080 60", "added")
print("monitor:", added, flush=True)
idx = added.split()[1]
wc = subprocess.Popen([WCI], stdin=subprocess.PIPE, text=True)
def pad(a):
    wc.stdin.write("slot 1 pad %d 0 0 0 0 0 0%s" % (0x1000 if a else 0, chr(10))); wc.stdin.flush()
pad(False); time.sleep(1)
mon_log, desk_log = os.path.join(OUT, "sdl-monitor.txt"), os.path.join(OUT, "sdl-desk.txt")
for f in (mon_log, desk_log):
    if os.path.exists(f): os.remove(f)
print(call("launch\t%s\t%s\t-I \"%s\" \"SDL game on a WindowsCraft monitor\" 100 100 inherit \"%s\"\tpython.exe" % (idx, VPY, SDLGAME, mon_log), "launched"), flush=True)
desk_env = {k: v for k, v in os.environ.items() if k != "SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS"}
desk = subprocess.Popen([VPY, "-I", SDLGAME, "SDL game on your desk", "40", "300", "inherit", desk_log], env=desk_env)
front = subprocess.Popen([PADGAME, "front", "Front window (stand-in Minecraft)", "500", "60"], stdout=subprocess.PIPE, text=True)
fh = int(front.stdout.readline().split("hwnd")[1])
time.sleep(5)

class R(ctypes.Structure): _fields_ = [("l", ctypes.c_long), ("t", ctypes.c_long), ("r", ctypes.c_long), ("b", ctypes.c_long)]
r = R(); u.GetWindowRect(fh, ctypes.byref(r))
u.SetCursorPos((r.l + r.r) // 2, r.t + 12); time.sleep(0.15)
u.mouse_event(2, 0, 0, 0, 0); time.sleep(0.05); u.mouse_event(4, 0, 0, 0, 0); time.sleep(0.6)
print("front window in front:", u.GetForegroundWindow() == fh, flush=True)
t0 = time.perf_counter_ns()
for _ in range(8):
    pad(True); time.sleep(0.3); pad(False); time.sleep(0.4)
t1 = time.perf_counter_ns()
wc.stdin.write("slot 1 end" + chr(10)); wc.stdin.flush()

def count(path):
    if not os.path.exists(path):
        return "no log", ""
    lines = open(path).read().splitlines()
    ready = next((l for l in lines if l.startswith("ready")), "")
    downs = [l.split() for l in lines if l.startswith("A down")]
    return "%d of 8 while not in front" % len([d for d in downs if t0 <= int(d[2]) <= t1 and d[3] == "fg=0"]), ready

print("on the WindowsCraft monitor: %s  (%s)" % count(mon_log))
print("on your desk:                %s  (%s)" % count(desk_log))
front.kill(); desk.kill()
call("remove " + idx, "removed", 15)
helper.stdin.close(); wc.stdin.close()
