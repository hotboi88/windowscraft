"""Input benchmark: each click the viewer sent ("[bench viewer] click sent <nanoTime>" in its log)
matched with the bench window getting it ("pressed <nanoTime>"), both on the same PC (Windows'
System.nanoTime is one clock for every process). Prints median / P95 / max of click -> app.
  python tools/bench/clicks.py BOB_LOG WINDOW_LOG"""
import re
import sys

sent = [int(m.group(1)) for m in re.finditer(r'click sent (\d+)', open(sys.argv[1], encoding='utf-8', errors='replace').read())]
got = sorted(int(m.group(1)) for m in re.finditer(r'^pressed (\d+)', open(sys.argv[2], encoding='utf-8', errors='replace').read(), re.M))
ms = []
for t in sent:
    later = [g for g in got if t < g < t + 1_000_000_000]
    if later:
        ms.append((later[0] - t) / 1e6)
if not ms:
    print(f'clicks: {len(sent)} sent, none arrived at the window ({len(got)} presses there)')
    sys.exit(0)
ms.sort()


def pct(p):
    return ms[min(len(ms) - 1, int(p / 100 * len(ms)))]


print(f'clicks: {len(ms)} of {len(sent)} sent arrived; click -> app median {pct(50):.1f} ms, P95 {pct(95):.1f} ms, max {ms[-1]:.1f} ms')
