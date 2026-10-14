# Step 0 — measurements and findings (14 Oct 2026)

Measured on the owner's PC: RTX 4060 (driver 616.92), i9-10900KF, Windows 11 Home. Times are
median / P95 / P99. Both test players ran on one PC (one graphics card shared), so latencies are
reliable and frame rates slightly pessimistic. Not measured yet: a real remote peer (Tailscale),
Cobbleverse itself, a 4K in-game monitor, sound by ear.

## How to measure

- In game: `/windowscraft measure start [seconds]` | `stop` | `status` → `windowscraft/measurements/`
  (text + JSON). Dedicated server: `windowscraft-server measure start [seconds]`. Automated runs:
  `-Dwindowscraft.measure=SECONDS[@DELAY]` (a report every SECONDS). Off by default; while off each
  measuring point is one volatile read. Measuring itself ≈ 0.02–0.03 of a core while on.
- One-PC benchmark (`tools/bench`, Git Bash on Windows): `run.sh pattern|window|idle HEIGHT FPS
  SECONDS [NAME]` — test server on 25566/25586 (a real server on 25565/25585 keeps running), sharer
  and viewer measuring; `window` streams `BenchWindow.java` (GPU path; timing strip → exact
  capture → decoded). `monitor.sh` — an in-game monitor (real helper + driver), standing and
  seated. `summarize.py DIR [filter]` prints the table. Dev-only experiment switches (off):
  `windowscraft.exp.interleave`, `.noLight`, `.sameFrame`, `.hires`, `.hwaccel`, `.dumpTs`.
- Don't edit a bench script while it runs (bash reads it as it goes), and don't edit sources while
  a suite runs (each launch recompiles).

## Ranked findings

1. **Stream sound is almost entirely dropped (Windows, current FFmpeg).** The sound input was
   stamped per packet with the wall clock; loopback hands it over in bursts, the stamps run
   backwards and the AAC encoder drops nearly everything: 12–20 audio packets per 5 s instead of
   ~800; a test viewer decodes 0 KB (also on untouched 0.15.0). A real session log had 110,622
   "Non-monotonic DTS" + 35,795 "Queue input is backward in time" warnings (34 MB log). CI didn't
   see it: Linux, older FFmpeg; Windows downloads the newest nightly (unpinned).
   Fix (branch `step0-fix-prototypes`): sound clock = sample count from the wall clock at start
   (`asetpts=N/SR/TB+RTCSTART/(TB*1000000)`), CPU path `-copyts`. Viewer sound 0 → 21.8 MB; and
   capture → encoded 21/61/67 → 4/22/26 ms (the muxer had held pictures in ~80 ms clumps).
   Also: pin a tested FFmpeg build.
2. **New viewers drop to light playback (30 fps) ~2 s after starting** and ask the sharer for a
   lighter stream: the decoder reads ~0.5 s before its first picture, the queue looks "behind".
   Fix (branch): catch-ups in the first 5 s after the first picture don't count.
3. **Direct GPU sharing is off on the owner's PC** ("the shared pictures came out black in the
   game", saved per driver), so walls take the CPU route (three copies per picture). Same card and
   driver passed and worked in tests (window and in-game monitor). Likely a false "black" (new or
   unchanging monitor). Proposal: re-test; call it black only if the CPU capture of the same
   source isn't. In-game monitor on the fast route: captured → wall 0/7/10 ms (CPU route at 720p30:
   13/17/18 ms).
4. **Lip sync**, measurable now that sound flows: sound 256 ms after the picture (115 beeps,
   140–340 ms). Proposal: hold pictures back by the measured audio delay on viewers or shrink the
   reserves.
5. **Viewer decode**: after the fixes a 1080p60 picture arrives ~5 ms after capture but leaves the
   viewer's FFmpeg ~130 ms later; CPU NV12→BGRA conversion costs 1–1.5 cores at 1080p60.
6. **Extra render frame** (copy in frame N, show in N+1): same-frame test switch 13.0 → 5.6 ms
   (own wall), 12.2 → 4.0 ms (viewer). Waiting isn't acceptable and starting the copy at frame
   start didn't finish in time in a light world; proposal: copy on arrival into a slot reserved
   by the render thread.
7. **wc-capture 15.6 ms timer**: its 2 ms waits last up to 15.6 ms. `timeBeginPeriod(1)` (branch):
   captured → wall P99 9.8 → 1.4 ms (in-game monitor).
8. **Guest input waits for the host's next frame**: 3.3/7.8/8.5 ms at 120 fps; idle server
   0.07/0.29/0.63 ms (a busy server: up to a tick — measure); hand-over 0.01 ms; no rate-limit drops
   at 62/s (the limiter would drop releases too, by code).
9. Smaller: viewer drops ~1.5 pictures/s at 60 fps (rawvideo timestamp collisions; branch:
   `-enc_time_base:v demux`); PUB2 never got the 512 KB send buffer (branch: `startsWith("PUB")`);
   viewer keeps its first decode size across quality steps (code); sitting down once froze the
   GPU-shared picture (helper sent nothing after a 2 → 60 fps change; not reproduced); a second
   capture of the same source keeps running at 1/s while GPU sharing feeds the wall; a guest's
   button press on a shared window can block the host's render thread up to 150 ms (code; 0.15 ms
   measured).

Windows self-test on the real PC (first time off CI): 64/70 passed. Failed: Task Manager from a
monitor opened elevated on the desk (the no-admin switch didn't take effect; the test then couldn't
close it, and a second run was spoiled by it — the test should close what it opens); single-instance
bring-over both ways; full-screen browser lost full screen after Esc; main-taskbar check; a guest's
held key mixing into the owner's typing. A Windows Firewall prompt was open during the run (possible
focus interference).

Idle cost (mod loaded, nothing on): walls 0.01/0.02/0.04 ms a frame, client tick 0.02 ms, no
WindowsCraft thread CPU or allocations; the idle wc-vdisplay helper holds ~8 MB.

## 1080p60 GPU path, friend's screen (one PC)

| Step | 0.15.0 | with the branch's fixes |
|---|---|---|
| captured → encoded | 21 / 61 / 67 ms | 4 / 22 / 26 ms |
| captured → arrived at viewer | 23 / 61 / 67 ms | 5 / 23 / 27 ms |
| waiting in the relay | 0.2 / 15 / 22 ms | 0.1 / 0.2 / 1.0 ms |
| captured → decoded (timing strip) | 199 / 237 / 254 ms | 133 / 161 / 178 ms |
| decoded → handed to the GPU | 12 / 16 / 17 ms | 12 / 16 / 19 ms |
| viewer playback | 30 fps (false light mode) | full rate |
