"""Safety checks for wc-audio --duck ("You hear it from the wall too"), on test players only:
- the shared app's sound comes from a child process: it is turned down and comes through amplified;
- another app playing at the same time is left alone;
- the shared app turned back up in the mixer (ten times) never comes through loud: re-ducked first;
- volumes come back on exit, and after a crash with --restore.
Usage: safety.py <wc-audio.exe> <sounds dir (sounds.sh)>"""
import array, os, subprocess, sys, threading, time

exe, D = sys.argv[1], sys.argv[2]
H = os.path.dirname(os.path.abspath(__file__))
PLAY, PS = os.path.join(H, 'play.py'), os.path.join(H, 'sessions.ps1')
STATE = os.path.join(D, 'safety-state.txt')
QUIET, FAINT = os.path.join(D, 'tone.wav'), os.path.join(D, 'faint.wav')
LOUD = 0.1  # the amplified test tone peaks near 0.0001: anything over 0.1 got through un-ducked
fails = []


def check(ok, what):
    print(('ok   ' if ok else 'FAIL ') + what, flush=True)
    if not ok:
        fails.append(what)


def volumes(pid, set_to=None):
    args = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', PS, '-TargetPid', str(pid)]
    if set_to is not None:
        args += ['-Set', str(set_to)]
    out = subprocess.run(args, capture_output=True, text=True).stdout
    return [float(l.rsplit(' ', 1)[1]) for l in out.splitlines() if ' volume ' in l]


def player(wav):
    p = subprocess.Popen([sys.executable, '-I', PLAY, wav, '40', '1.0'], stdout=subprocess.PIPE)
    return p, int(p.stdout.readline())


class Helper:
    def __init__(self, root):
        self.p = subprocess.Popen([exe, '--include-pid', str(root), '--parent-pid', str(os.getpid()), '--duck', STATE],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        self.peaks, self.lines, self.bytes = [], [], 0
        threading.Thread(target=self._out, daemon=True).start()
        threading.Thread(target=self._err, daemon=True).start()

    def _out(self):
        while True:
            b = self.p.stdout.read(3840)
            if not b:
                return
            self.bytes += len(b)
            a = array.array('h', b[:len(b) // 2 * 2])
            self.peaks.append((time.perf_counter(), max((abs(x) for x in a), default=0) / 32768))

    def _err(self):
        for l in self.p.stderr:
            self.lines.append((time.perf_counter(), l.decode(errors='replace').strip()))

    def stop(self):
        self.p.stdin.close()
        return self.p.wait(10)


if os.path.exists(STATE):
    os.remove(STATE)
# the shared app: a parent whose child plays (as a browser plays from a child process)
parent = subprocess.Popen([sys.executable, '-I', '-c',
                           'import subprocess, sys; c = subprocess.Popen([sys.executable, "-I", sys.argv[1], sys.argv[2], "40", "1.0"], stdout=subprocess.PIPE); '
                           'print(c.stdout.readline().decode().strip(), flush=True); c.wait()', PLAY, QUIET], stdout=subprocess.PIPE)
child = int(parent.stdout.readline())
other, other_pid = player(FAINT)
h = Helper(parent.pid)
try:
    time.sleep(5)
    v = volumes(child)
    check(v and all(abs(x - 0.001) < 1e-4 for x in v), f'the child process is turned down: {v}')
    v = volumes(other_pid)
    check(v and all(abs(x - 1.0) < 1e-3 for x in v), f'the other app is left alone: {v}')
    got = [pk for t, pk in h.peaks[-100:]]
    check(max(got, default=0) > 0.00005, f'its sound comes through amplified (peak {max(got, default=0):.4f})')
    # someone turns it back up in the mixer, three times
    ups = []
    for _ in range(10):
        before = time.perf_counter()
        volumes(child, 1.0)
        ups.append((before, time.perf_counter()))
        time.sleep(0.7)
        v = volumes(child)
        check(v and all(abs(x - 0.001) < 1e-4 for x in v), f'turned up in the mixer: turned down again: {v}')
    loud = [(t, pk) for t, pk in h.peaks if pk > LOUD]
    check(not loud, f'never through loud (over {LOUD}): {len(loud)} reads, peak {max((pk for _, pk in loud), default=0):.3f}')
    check(any('volume was changed in the mixer' in l for _, l in h.lines), 'says it noticed')
    rate = h.bytes / 192000
    code = h.stop()
    time.sleep(0.3)
    v = volumes(child)
    check(code == 0 and v and all(abs(x - 1.0) < 1e-3 for x in v), f'on exit ({code}) its volume comes back: {v}')
    check(not os.path.exists(STATE), 'and the state file is gone')
    # a crash: killed while the app is turned down; --restore puts it back
    h = Helper(parent.pid)
    time.sleep(4)
    v = volumes(child)
    check(v and all(abs(x - 0.001) < 1e-4 for x in v), f'(again turned down: {v})')
    check(os.path.exists(STATE), 'the state file is there while it is turned down')
    subprocess.run(['taskkill', '/F', '/PID', str(h.p.pid)], capture_output=True)
    time.sleep(0.5)
    v = volumes(child)
    check(os.path.exists(STATE) and v and all(abs(x - 0.001) < 1e-4 for x in v), f'killed: still down, the state file kept: {v}')
    r = subprocess.run([exe, '--restore', STATE], capture_output=True, text=True)
    v = volumes(child)
    check(r.returncode == 0 and v and all(abs(x - 1.0) < 1e-3 for x in v), f'--restore puts it back: {v} {r.stderr.strip()}')
    check(not os.path.exists(STATE), 'and removes the state file')
    print('stderr:', ' | '.join(l for _, l in h.lines))
finally:
    for p in (parent, other):
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(p.pid)], capture_output=True)
print('PASS' if not fails else f'FAILED {len(fails)}')
