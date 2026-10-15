"""Game controllers for people using your screens, timed (dev only, Windows).

  python tools/bench/padcheck.py watch OUT SECONDS   records when XInput (what games read) shows
                                                    button A going down or up, on any controller
  python tools/bench/padcheck.py match BOBLOG OUT    pairs those with the viewer's
                                                    "[bench viewer] pad sent A=1 <nanoTime>" lines
  python tools/bench/padcheck.py loop WCINPUT SECONDS
                                                    plugs in a "viewer's own" controller (wc-input's
                                                    slot 99) for -Dwindowscraft.bench.pad=glfw to read,
                                                    presses A and pushes the left stick up each
                                                    second, and times them arriving on the owner's
                                                    controller (the next one plugged in)

Both sides use the PC's performance counter (Java's System.nanoTime, Python's perf_counter_ns),
so on one PC the difference is the whole way: viewer -> relay -> your game -> wc-input -> ViGEmBus.
"""
import ctypes, re, sys, time


def watch(out, seconds):
    class GP(ctypes.Structure):
        _fields_ = [("buttons", ctypes.c_ushort), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                    ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]

    class ST(ctypes.Structure):
        _fields_ = [("packet", ctypes.c_uint), ("gp", GP)]

    x = ctypes.WinDLL("xinput1_4")
    s = ST()
    held = [False] * 4
    end = time.perf_counter() + seconds
    with open(out, "w") as f:
        while time.perf_counter() < end:
            for i in range(4):
                a = x.XInputGetState(i, ctypes.byref(s)) == 0 and bool(s.gp.buttons & 0x1000)
                if a != held[i]:
                    held[i] = a
                    f.write("A %s %d user %d\n" % ("down" if a else "up", time.perf_counter_ns(), i))
                    f.flush()
            time.sleep(0.0005)


def match(boblog, out):
    sent = [int(m.group(1)) for m in re.finditer(r"pad sent A=1 (\d+)", open(boblog, encoding="utf-8", errors="replace").read())]
    downs = [int(l.split()[2]) for l in open(out) if l.startswith("A down")]
    lat = []
    for t in sent:
        later = [d for d in downs if t <= d < t + 2_000_000_000]
        if later:
            lat.append((later[0] - t) / 1e6)
    if not lat:
        print("no presses matched (%d sent, %d seen)" % (len(sent), len(downs)))
        return
    lat.sort()
    p = lambda q: lat[min(len(lat) - 1, int(q * len(lat)))]
    print("A pressed on the viewer -> seen by XInput on the owner's PC: %d of %d, median %.1f ms, P95 %.1f ms, max %.1f ms"
          % (len(lat), len(sent), p(0.5), p(0.95), lat[-1]))


def loop(wcinput, seconds):
    import subprocess

    class GP(ctypes.Structure):
        _fields_ = [("buttons", ctypes.c_ushort), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                    ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]

    class ST(ctypes.Structure):
        _fields_ = [("packet", ctypes.c_uint), ("gp", GP)]

    x = ctypes.WinDLL("xinput1_4")

    def pads():
        out = {}
        for i in range(4):
            s = ST()
            if x.XInputGetState(i, ctypes.byref(s)) == 0:
                out[i] = s.gp
        return out

    p = subprocess.Popen([wcinput], stdin=subprocess.PIPE, text=True)

    def send(l):
        p.stdin.write(l + chr(10))
        p.stdin.flush()

    before = set(pads())
    send("slot 99 pad 0 0 0 0 0 0 0")
    t0 = time.perf_counter()
    mine = None
    while mine is None and time.perf_counter() - t0 < 5:
        new = set(pads()) - before
        mine = min(new) if new else None
    print("the viewer's own controller: XInput user %s" % mine, flush=True)
    end = time.perf_counter() + seconds
    owner, lat, wrong = None, [], []
    k = 0
    while time.perf_counter() < end:
        k += 1
        press = k % 2 == 1
        t = time.perf_counter_ns()
        send("slot 99 pad %d 0 0 0 %d 0 0" % (0x1000 if press else 0, 20000 if press else 0))
        deadline = time.perf_counter() + 0.9
        while time.perf_counter() < deadline:
            now = pads()
            if owner is None:
                others = set(now) - before - {mine}
                owner = min(others) if others else None
            g = now.get(owner) if owner is not None else None
            if g is not None and bool(g.buttons & 0x1000) == press:
                if press:
                    lat.append((time.perf_counter_ns() - t) / 1e6)
                    if not 15000 < g.ly < 25000:
                        wrong.append("left stick up read as ly=%d" % g.ly)
                break
            time.sleep(0.0005)
        time.sleep(max(0, deadline - time.perf_counter()))
    send("slot 99 end")
    p.stdin.close()
    p.wait(5)
    lat.sort()
    if lat:
        print("viewer's controller -> owner's controller (through GLFW, the relay, the owner's game): %d presses, median %.1f ms, max %.1f ms"
              % (len(lat), lat[len(lat) // 2], lat[-1]))
    else:
        print("nothing arrived on the owner's controller (owner's XInput user: %s)" % owner)
    print("stick direction: " + ("ok" if not wrong else "; ".join(sorted(set(wrong)))))


if __name__ == "__main__":
    if sys.argv[1] == "loop":
        loop(sys.argv[2], float(sys.argv[3]))
    elif sys.argv[1] == "watch":
        watch(sys.argv[2], float(sys.argv[3]))
    else:
        match(sys.argv[2], sys.argv[3])
