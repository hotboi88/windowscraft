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
   Also: pin a tested FFmpeg build (done on `next-0.15.2`: BtbN's FFmpeg 9.0.2 release build,
   SHA-256 in `Ffmpeg.java`; same viewer latency and bit-identical decoding as the owner's
   nightly N-127259; an older download is replaced in the background). With real sound packets the muxer's 100 ms interleave wait
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
   A likely way the check misreads (found 14 Oct, by code): it read 25 spots back with whatever
   pack state the last mod left — a pixel-pack buffer left bound (Xaero's maps use them) catches
   the read and the untouched memory reads as black. On `next-0.15.2`: pack state set plainly
   for the read, a read that didn't land is no verdict, and the normal capture of the same screen
   (it keeps running at 1/s) is asked first — black too, or nothing yet, means look again later.
   Test key v4: a "black" verdict is tested again once.
   The owner's actual case — a 4K in-game monitor on the CPU route, his settings: ~21 pictures/s
   on the wall from a 60 fps window (24 ms to copy each 33 MB picture out of the helper, 16 ms
   into the GPU buffer), captured → wall well over 100 ms, ~2.3 cores in the game + 1 in the
   helper, 1 % lows 47 fps (≈95 idle). The fixes branch barely changes it (24/s): copying is the
   bottleneck at 4K. (`monitor.sh` with `BW_W/BW_H` and `-Dwindowscraft.bench.monitorSize=WxH`.)
4. **Lip sync**, measurable now that sound flows: sound 256 ms after the picture (115 beeps,
   140–340 ms). Roughly: ~80 ms sender cushion + ~45 ms AAC + 120–170 ms playback reserve and
   device; sound passes the viewer's FFmpeg in < 1 ms (measured standalone, AAC frame in →
   samples out). With the viewer decode fix (5) the picture is ~40 ms sooner and sound isn't:
   ~290–300 ms (multiplayer test median 291); with 40/60 ms reserves ~190 ms. Tried and dropped:
   sender `-pes_payload_size 0` (sound packets out 18–22 ms sooner, but the viewer's adaptive
   reserve grew: 301 → 356 ms). Proposal: shrink the reserves (listen for crackles) or hold
   pictures back on viewers when a screen plays a movie.
   On `next-0.15.2` (lip sync): the viewer holds the *compressed* video packets back by the
   measured gap while the screen has sound and nobody there is working it (`stream.AvSync`: per
   stream arrival − timestamp, median; + sound still queued to the card − ~28 ms picture way;
   straight there when you start watching, then 20 ms/s up, at once down). Sound is never
   delayed. To check on the owner's PC: multiplayer A/V offset (beeps) → ~0, `view.lip_hold`.
5. **Viewer decode holds pictures, counted in pictures**: after the fixes a 1080p60 picture arrives
   ~5 ms after capture but leaves the viewer's FFmpeg ~130 ms later. The decoder type doesn't
   matter (software 125, d3d11va 134, cuda 138 ms, auto 123 ms in the game; `-threads 1` the
   same). Standalone (`declat`-style: a TS fed in real time, picture-in → BGRA-out), the delay
   scales with the picture interval — 64 ms at 60/s, 114 at 30/s, 315 at 10/s, ≈3 pictures — and
   the GPU path only sends a picture when the screen changes. The SPS is fine (POC type 2,
   max_num_reorder_frames 0). The holds: the TS reader waits for the next PES start (video PES
   length 0 by default) and FFmpeg's H.264 parser waits for the next access unit; each is one
   picture (30/s: 113 → 80 → 46 ms). Branch: sender `-omit_video_pes_length 0` (pictures ≤ 64 KB;
   bigger ones — keyframes, or 10/s at 10 Mb/s — still wait), viewer
   `-fflags nobuffer+noparse+nofillin` (decoded pictures bit-identical; switch
   `windowscraft.promptDecode=false`). `-flush_packets 1` on the picture output, the sound path,
   `-avioflags direct`, threads and hwaccel make no difference. In the game, captured → decoded on
   the viewer: 60/s 136/166/174 → 98/125/136 ms, ~17/s 306/398/407 → 195/245/266 ms. Left: one
   picture inside FFmpeg (60/s 29 ms, 30/s 46 ms standalone), and CPU NV12→BGRA conversion
   (1–1.5 cores at 1080p60; half-size or NV12 output ~6 ms less).
   **Both gone on `next-0.15.2`.** The last picture was the CLI's raw-picture *writer*, not the
   decoder: rawvideo encodes on frame threads and hands picture k out when k+1 comes in (FFmpeg's
   `-debug_ts` latency report: 150 of 155 ms in "encode" with pictures fed 150 ms apart; FFmpeg's
   libraries driven directly, via PyAV, hold nothing). `-threads:v 1` on the output: 0 held.
   Pictures over 64 KB (no PES length: every keyframe, big changes, all pictures from older
   senders) still waited for the next picture's start — with change-driven capture, the last
   change before the screen goes still appeared only with the next change. The viewer now puts a
   tiny sized PES ("Q264" + filler NAL, which FFmpeg's H.264 decoder drops silently) right after
   such a picture where its last TS packet is padded out, renumbering the video CCs
   (`relay.PictureEnds`). Measured by counting, not timing (one picture at a time, 150 ms apart):
   60/s and 30/s 1 → 0 held, 10/s and old-style streams 2 → 0; decoded pictures bit-identical.
   Colour: the viewer's FFmpeg now outputs NV12 and `render.Nv12Converter` colours it into the
   wall's RGBA texture on the GPU (BT.601 limited, colour per 2×2 block as FFmpeg did: within
   3/255 of its BGRA; 1.5 bytes a pixel instead of 4 through socket, Java and upload). CI: movie
   test (Linux, llvmpipe) identical colour bars and results. Still to measure on the RTX 4060:
   viewer CPU and captured → decoded in the game.
   Counting beats timing here: with the owner's game running, the same timed run read 29 ms
   once and 58 ms the next (`avtest/hold.py`-style counting is in the commit message). Measuring note: `view.decode` (arrivals
   and decoded pictures matched in order) is unreliable — FFmpeg drops the pictures it reads while
   sizing up the stream (`-fflags nobuffer`), which shifts the match (it read ~1.2 s at 17/s); use
   `view.e2e_testpattern` (timing strip) minus `view.arrival_age`. Standalone tools:
   `tools/bench/declat.py` (pictures) and `audlat.py` (sound), fed in real time, matched from the end.
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
   viewer kept its first decode size across quality steps (branch: a step up past it restarts the
   decoder — 720p → 1080p is now decoded at 1920×1080, was stuck at 1280×720); sitting down once froze the
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

## A friend's game controller (15 Oct, 0.15.9, ViGEmBus 1.21.442, one PC)

| | median | P95 | max |
|---|---|---|---|
| wc-input "pad" → XInput (what games read) | 1.0 ms | | 1.9 ms |
| friend's game sends → XInput on the owner's PC (70 presses) | 2.0 ms | 3.3 ms | 4.5 ms |
| friend's controller pressed → XInput on the owner's PC (via GLFW each frame, 39 presses) | 7.9 ms | | 354 ms (first) |

A controller of their own appears 37 ms after their first message; let go of after 3 s without a
message; unplugged when they go. Over the internet: plus their one-way delay.

## Two people watching (15 Oct, 0.15.8, dev bench `VIEWERS=2`, 1080p60 window, one PC)

| | one viewer | two viewers |
|---|---|---|
| sharer: game fps (average / 1 % low) | 119.2 / 87.2 | 118.9 / 80.3 |
| sharer: helpers (capture + encode) | 0.23 cores, encoder 12 % | 0.23 cores, encoder 11 % (one encode) |
| server relay: wait before sending on / CPU | 0.03 ms / 0.09 cores | 0.04 ms / 0.11 cores |
| viewers: pictures decoded | 53.6/s | 53/s and 54/s |
| viewers: captured → decoded (median / P95) | 51.9 / 65.5 ms | 58.2 / 80.7 and 60.3 / 82.8 ms |

The extra ~7 ms is already there when the picture reaches the relay (in_age 19.1 → 22.8 ms median),
before it's copied to anyone: three games and a server on one PC compete for it. On separate PCs
a second viewer costs the sharer nothing measurable; the relay's fan-out is negligible.

## Windows self-test on the owner's PC again (15 Oct, 0.15.7 → 0.15.8)

| | 13 Oct | 15 Oct, 0.15.7 | after the fixes (0.15.8) |
|---|---|---|---|
| passed | 64 / 70 | 61 / 65 (Task Manager skipped: elevated here) | 65 / 67 |
| main taskbar; a guest's held key in your typing | fail | pass | pass |
| sitting down: cursor where you pointed | pass | **fail** (2048,1080 for 2880,1620: the game let go late) | pass |
| full-screen Edge after Esc | fail | fail (a "now syncing" sign-in dialog took the F11) | pass (InPrivate) |
| a monitor's picture is captured | — | skipped (no wc-capture.exe in the zip) | pass |
| an app that only runs once comes over (and back) | fail | fail | fail — the test's own bring-forward never reaches the helper here (foreground lock?); to check with Discord |

## 0.15.7: shared windows on GPU sharing; a paused stream lets the capture rest (RTX 4060, 15 Oct)

Shared window (the 1080p60 bench window on a wall, not an in-game monitor), sharer's CPU captures
for its own wall — the "capture" figure in the share's perf line:

| | 0.15.6 | 0.15.7 |
|---|---|---|
| shared window in his modpack (Sodium) | CPU route ("read back empty at every memory size") | **direct GPU sharing, 43–50 pictures/s** |
| live, nobody watching (paused), GPU sharing feeding the wall | 43–50 CPU captures/s, full size | **1/s** |
| live and watched, GPU capture+encode | 1/s | 1/s |
| live and watched, CPU stream (`-Dwindowscraft.gpuCapture=false`) | full size, full rate | full size, 42–48/s (back from 1/s as the viewer arrives) |

Resizing a shared window (his modpack, the bench window ¾ as big and back every 4 s, ~10 resizes
in the 40 s measured): direct GPU sharing all along, 54 pictures/s on the wall; the wall's CPU
uploads at a resize 11 × 7.5–9.8 ms → none (it keeps the last picture until one at the new size
is there); game 174.8 / 59.6 → 196.0 / 67.9 fps (average / 1 % low), longest frame 47.9 → 39.7 ms.

Dev bench (tools/bench, `BOB_DELAY=90`: the viewer arrives after the stream has paused): the viewer
decoded 1920×1080 at 52/s (GPU stream) and 48/s (CPU stream) after the resume; the sharer's game
119 fps average / 86 1 % low, all helpers 0.23 cores.

## 0.15.6: direct GPU sharing in the owner's modpack (Cobbleverse, Iris + Solas), RTX 4060

`tools/prodclient/run.sh` (a copy of his instance, launcher-style), in-game monitor bench standing
9 blocks away, game window 1600 × 900, the bench window on the monitor at 60/s:

| 4K in-game monitor | CPU route (GPU sharing off: what he had) | GPU sharing (0.15.6) |
|---|---|---|
| game fps, average / 1 % low | 37.1 / 15.5 | **89.1 / 28.5** |
| pictures on the wall | 22/s | **52/s** |
| captured → on the wall | ~58 (helper) + 48 (wall) ms median | **0 / 7 / 11 ms** |
| game CPU / WindowsCraft threads / helper | 3.96 / 1.33 / 1.02 cores | **1.81 / 0.08 / 0.20** |
| GPU busy | 67 % | 93 % (now the limit: Solas + the 4K bench window + DWM) |

1080p: game 2.33 → 1.61 cores, helper 0.37 → 0.11, 96.8 / 40.1 → 106.7 / 43.3 fps. Before 0.15.6
GPU sharing was "black" in this modpack: Sodium's "no error" OpenGL context hid the driver refusing
a memory size (HANDOFF, 0.15.6).

## 0.15.3: "Copies as sharp as they look" on the RTX 4060 (14 Oct, his game closed, his server running)

A 3840 × 2160 in-game monitor on the CPU route (direct GPU sharing off, the owner's other
performance settings: off-screen pause and distance limit off), the bench window at 60/s on it,
game window 1600 × 900, 30 s per phase (`tools/bench/monitor.sh` with `BW_W=3840 BW_H=2160`,
`-Dwindowscraft.bench.monitorSize=3840x2160 -Dwindowscraft.bench.far=24 -Dwindowscraft.bench.noSit=true`):

| standing 24 blocks away (wall 250 px tall on screen) | setting off | on (copy 960 × 540) |
|---|---|---|
| pictures on the wall | 21/s | **30/s** (all the helper sends) |
| copy out of the helper / into the GPU buffer, median | 26 / 16 ms | **0.4 / 0.3 ms** |
| captured by Windows → copied out, median / P95 | 72 / 100 ms | **20 / 41 ms** |
| copied out → on the wall, median | 41 ms | **5 ms** |
| game fps, average / 1 % low | 114 / 39.8 | **119 / 80.9** (a second run, with the shipped helper: 117 / 55.0) |
| game CPU / helper CPU | 2.21 / 0.94 cores | **0.53 / 0.16** (second run 0.69 game) |
| GPU busy | 54 % | **42 %** |

Standing 9 blocks away (wall 680 px tall) it stays at full size in both (1080 isn't twice 680):
20–31 pictures/s, 16–27 ms per copy (two runs of the same thing; the copies compete for memory —
a single 4K copy alone takes ~5 ms on this PC, 7 GB/s). Sitting at it (full size by design) is
unchanged: the fix for that is direct GPU sharing working (no copies through the CPU).

## 0.15.2 on the RTX 4060 (14 Oct, the owner's game open in the background)

One-PC bench, a shared window at 1080p, viewer lip sync off (latency only). Captured → decoded
(timing strip), median / P95 / P99:

| | 0.15.1 | 0.15.2, colour by FFmpeg (BGRA) | 0.15.2 (NV12, GPU colour) |
|---|---|---|---|
| 60/s | 97.5 / 122.7 / 136.3 ms | 63.4 / 101.7 / 203.4 ms | **43.5 / 64.5 / 70.3 ms** |
| 30/s | — | 62.4 / 84.9 / 97.5 ms | **48.8 / 82.8 / 93.3 ms** |
| viewer game, CPU (60/s) | — | 1.50 cores | **0.79** |
| viewer FFmpeg, CPU (60/s) | — | 1.42 cores | **0.47** |
| GPU per picture (upload + colour + mipmaps) | — | 2.20 ms | **1.39 ms** |

Lip sync (multiplayer test with sound, beeps vs flashes): without, sound 370–410 ms after the
picture; with, the last 30 beeps median **36 ms** (10th–90th pct 0..70), pictures held 390–450 ms
(the viewer's sound reserve had grown to ~220 ms: the PC ran three test games and the owner's).
Not tested: Iris/Sodium with a viewer — the owner's jars can't load in the dev environment
(Sodium's mixins need a production client; tools/prodclient isn't in this tree).

## 1080p60 GPU path, friend's screen (one PC)

| Step | 0.15.0 | with the branch's fixes |
|---|---|---|
| captured → encoded | 21 / 61 / 67 ms | 4 / 22 / 26 ms |
| captured → arrived at viewer | 23 / 61 / 67 ms | 5 / 23 / 27 ms |
| waiting in the relay | 0.2 / 15 / 22 ms | 0.1 / 0.2 / 1.0 ms |
| captured → decoded (timing strip) | 199 / 237 / 254 ms | 98 / 125 / 136 ms (133 / 161 / 178 before the decode fix) |
| decoded → handed to the GPU | 12 / 16 / 17 ms | 12 / 16 / 19 ms |
| viewer playback | 30 fps (false light mode) | full rate |
