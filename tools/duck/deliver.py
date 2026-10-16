"""Runs wc-audio on one app for N seconds and reports how its sound arrives (bytes per second, gaps)."""
import os, subprocess, sys, threading, time

exe, pid, secs = sys.argv[1], sys.argv[2], float(sys.argv[3])
bufsize = int(sys.argv[4]) if len(sys.argv) > 4 else 4096
extra = ['--duck', os.environ['DUCK']] if os.environ.get('DUCK') else []
p = subprocess.Popen([exe, '--include-pid', pid, '--parent-pid', str(os.getpid())] + extra,
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
threading.Thread(target=lambda: [print('[wc-audio]', l.decode(errors='replace').rstrip(), flush=True) for l in p.stderr], daemon=True).start()
reads = []
def pump():
    while True:
        b = p.stdout.read(bufsize)
        if not b:
            return
        reads.append((time.perf_counter(), len(b)))
threading.Thread(target=pump, daemon=True).start()
time.sleep(secs)
p.stdin.close()
time.sleep(1)
p.kill()
reads = [r for r in reads if r[0] > reads[0][0] + 3]  # (skip its start)
span = reads[-1][0] - reads[0][0]
total = sum(n for _, n in reads[1:])
gaps = [b[0] - a[0] for a, b in zip(reads, reads[1:])]
print(f'{total / 192000 / span * 1000:.0f} ms of sound per s, {len(reads) / span:.0f} reads/s, '
      f'gaps over 30 ms: {sum(g > 0.03 for g in gaps)}, longest {max(gaps) * 1000:.0f} ms, over {span:.0f} s')
# the shortfall (sound expected by now minus sound come) at each batch's end: steps up when sound is lost
t0 = reads[0][0]; got = 0; short = []; prev_t = None
for i, (t, n) in enumerate(reads):
    if prev_t is not None and t - prev_t > 0.003 and i > 1:
        short.append((round((t - t0) * 1000), round(prev_short, 1)))
    got += n; prev_short = (t - t0) * 1000 - got / 192.0; prev_t = t
floor = min(s for _, s in short)
print('shortfall over its lowest, ms, at batch ends (t ms: excess) — steps only:')
last = None; steps = []
for t, s in short:
    e = s - floor
    if last is not None and abs(e - last) > 6: steps.append(f'{t}:{e - last:+.0f}')
    last = e
print(' '.join(steps[:80]))
print('final excess', round(short[-1][1] - floor), 'ms; lowest-to-highest excess', round(max(s for _, s in short) - floor))
