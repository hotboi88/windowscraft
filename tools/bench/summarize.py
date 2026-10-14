"""Step 0: one table from the measurement reports (measure-*.json) in a folder.

    python tools/bench/summarize.py build/bench [name-filter]

Rows are metrics, columns are reports; each cell is "median / P95 / P99" (counts: per second).
"""
import json
import os
import sys

KEYS = [
    "frame.interval", "frame.wc_walls", "frame.wc_pointing", "frame.wc_hud", "tick.wc_client",
    "wall.poll", "wall.copy", "wall.show_age", "gpu.wall_upload", "gpu.walls_frame",
    "gpu_share.announce_age", "gpu_share.show_age", "gpu.share_copy",
    "capture.grab", "capture.interval", "capture.helper_age", "capture.copy_out",
    "enc.out_age", "enc.cpu_latency", "enc.out_interval", "enc.relay_write", "enc.cpu_feed_write",
    "relay.in_age", "relay.queue_wait", "relay.queue_kb",
    "view.arrival_age", "view.arrival_age_synced", "view.net_delay", "view.round_trip", "view.arrival_interval",
    "view.queue_wait", "view.decode", "view.decoded_interval", "view.show_age", "view.e2e_testpattern", "view.first_picture",
    "input.server_wait", "input.host_wait", "input.host_apply",
    "sys.process_cpu", "sys.main_thread_cpu", "sys.wc_threads_cpu", "sys.helpers_cpu", "sys.helpers_ram",
    "sys.alloc_rate", "sys.wc_alloc_rate", "sys.gc_ms_per_s", "sys.heap_used",
    "gpu.util", "gpu.encoder", "gpu.decoder", "gpu.vram_used",
]


def cell(m):
    return f"{m['p50']:.2f} / {m['p95']:.2f} / {m['p99']:.2f}"


def main():
    folder = sys.argv[1]
    filt = sys.argv[2] if len(sys.argv) > 2 else ""
    reports = []
    for f in sorted(os.listdir(folder)):
        if f.startswith("measure-") and f.endswith(".json") and filt in f:
            with open(os.path.join(folder, f), encoding="utf-8") as fh:
                reports.append((f[len("measure-"):-len(".json")], json.load(fh)))
    if not reports:
        print("no reports")
        return
    for name, r in reports:
        print(f"## {name}  ({r['seconds']:.0f} s)")
        ms = r.get("metrics", {})
        for k in KEYS:
            if k in ms:
                m = ms[k]
                extra = ""
                if k == "frame.interval" and m["mean"] > 0:
                    extra = f"   {1000 / m['mean']:.1f} fps avg, 1% low {1000 / m['top1mean']:.1f} fps"
                print(f"  {k:26s} {cell(m):>28s} {m['unit']:>5s}  n={m['n']}{extra}")
        secs = max(1.0, r["seconds"])
        cs = r.get("counters", {})
        if cs:
            print("  counts/s: " + ", ".join(f"{k}={v / secs:.1f}" for k, v in sorted(cs.items())))
        for k, v in sorted(r.get("notes", {}).items()):
            print(f"  [{k}] {v}")
        o = r.get("overhead", {})
        print(f"  measuring itself: {o.get('msPerSecond', 0):.3f} ms/s + sampler {o.get('samplerMsPerSecond', 0):.2f} ms/s")
        print()


if __name__ == "__main__":
    main()
