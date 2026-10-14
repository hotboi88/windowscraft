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
   Also: pin a tested FFmpeg build. With real sound packets the muxer's 100 ms interleave wait
   now holds the CPU path's pictures (capture → encoded 17.6/55/61 ms); `-max_interleave_delta`
   of 1 µs brings it to 3.3/6.6/12.5 ms (GPU path: 4/22/26 already) and lip sync doesn't change
   (256 vs 265 ms) — on the branch as the default.
2. **New viewers drop to light playback (30 fps) ~2 s after starting** and ask the sharer for a
   lighter stream: the decoder reads ~0.5 s before its first picture, the queue looks "behind".
   Fix (branch): catch-ups in the first 5 s after the first picture don't count.
3. **Direct GPU sharing is off on the owner's PC** ("the shared pictures came out black in the
   game", saved per driver), so walls take the CPU route (three copies per picture). Same card and
   driver passed and worked in every test (window and in-game monitor). The verdict came on
   12 Oct 18:54, 9 s after it started, with Iris + the Solas shader pack and Xaero's minimap/world
   map loaded. Ruled out: a new monitor showing only the desktop for 12 s; a pixel-pack buffer
   left bound. Proposal: re-test in the real game with logging (copy vs blit path, scissor/pack
   state at the read-back); call it black only if the CPU capture of the same source isn't.
   In-game monitor on the fast route: captured → wall 0/7/10 ms (CPU route at 720p30: 13/17/18 ms).
4. **Lip sync**, measurable now that sound flows: sound 256 ms after the picture (115 beeps,
   140–340 ms). Roughly: ~80 ms sender cushion + ~45 ms AAC + ~60 ms viewer decoder + 120–170 ms
   playback reserve and device, against ~145 ms for the picture. Proposal: shrink the reserves
   (listen for crackles) or hold pictures back on viewers.
5. **Viewer decode**: after the fixes a 1080p60 picture arrives ~5 ms after capture but leaves the
   viewer's FFmpeg ~130 ms later; CPU NV12→BGRA conversion costs 1–1.5 cores at 1080p60. The
   decoder type doesn't matter (software 125, d3d11va 134, cuda 138 ms, auto 123 ms in the game).
   Standalone on a game-like 1080p60 stream fed in real time, the same command holds each
   picture 64/68/72 ms (half-size output or NV12: ~58 ms; one filter thread: much worse). Going
   well below ~60 ms needs decoding in our own helper rather than the FFmpeg CLI.
6. **Extra render frame** (copy in frame N, show in N+1): same-frame test switch 13.0 → 5.6 ms
   (own wall), 12.2 → 4.0 ms (viewer). Prototype on the branch: copy on arrival into a slot the
   render thread sets aside (pump thread) — own wall 13.0/16.7/17.6 → 6.2/10.6/11.7 ms, viewer
   12.2/16.4/17.0 → 7.4/13.5/15.1 ms, the render thread never waits.
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

Hidden screens are still decoded (measured): a viewer with its back to a 1080p60 screen decoded
30 pictures a second and threw all of them away — 1.1–1.5 cores of FFmpeg plus 0.3 of
WindowsCraft's threads for a screen nobody looks at. Prototype on the branch: 2 s after none of
its walls is drawn (off-screen pause on), only the sound is fed to the decoder; seen again, the
picture resumes at the next keyframe — FFmpeg ~1.3 → ~0.05 cores while hidden, no stalls.

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
