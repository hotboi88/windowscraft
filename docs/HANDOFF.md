# Handoff: where WindowsCraft stands (read this first in a new session)

The owner is not a programmer: explain things plainly, in terms of what he'll see and do.
He hosts; a friend joins remotely. The long-term goal is docs/VISION.md.

## Current state: 0.15.0 (all cloud tests green)

- Virtual in-game monitors (Parsec virtual display driver), capture, hardware encode, streaming
  to peers, input forwarding, spatial audio, sit-down view (flat and sharp, never under the
  taskbar), Alt-pointing, tablets, gaming-monitor blocks, joined monitors, self-update from the
  server.
- Sound per monitor: Windows only knows sound per program. Two layers:
  - "Own copies of apps" (Sandboxie-Plus v1.18.6, pinned URL + SHA-256 in
    src/main/java/dev/windowscraft/monitor/Sandbox.java, installed hidden). Each monitor has
    its own box (name "WC" + first 8 chars of the display id). While you sit at / point at a
    monitor with copies on, a force rule boxes newly started apps into that monitor's box.
    Rules are written straight into C:\Windows\Sandboxie.ini (our [WC…] sections only) and
    applied through SbieDll's SbieApi_ReloadConf, then confirmed with SbieApi_QueryConf
    (helper command "forcestate"). Never by starting Sandboxie's tools while a rule is on.
  - Without it: an app's sound plays where it was last used (SoundHome, incl. DESK).
- Things learned the hard way (keep them):
  - Never force a whole drive (it boxes Sandboxie's own tools; the rule can't be undone).
  - Sandboxie has NO exceptions inside a forced folder (driver source: process_force.c).
    Breakout* only applies to programs a boxed app starts. So the helper "carves" the app
    folders around exceptions (wc-vdisplay.rs: carve / force_folders / refresh_carve; quick
    pass then deep pass, background, low priority).
  - Don't put Sandboxie's folder or Windows' folder in BreakoutFolder: Start.exe has to run
    inside the box to launch there ("open another copy" never arrived).
  - BoxNameTitle=- removes the [#] title marker (n only hides the box name).
  - Don't AttachThreadInput to a boxed app's thread: it can hang for good. Keyboard
    hand-over (foreground) runs on its own thread with a time limit.
  - Read other apps' window titles with InternalGetWindowText, never GetWindowText /
    MainWindowTitle (they ask the window and hang on a stuck app).
  - In tests, never kill processes by window-title match: it once killed the CI runner itself.
    Kill only by exact process name/pid of our own test apps.
  - Always-normal (never a copy): Discord, WhatsApp, Explorer, Java, launchers (Steam, Epic,
    Battle.net, Riot), anti-cheat, Task Manager, msiexec, Settings, plus the user's own list.
    "Open another copy here" can still box one of these on purpose (LAUNCH_ALLOW).

## Owner's rules (non-negotiable)

- No mixed audio between monitors/desk, ever; all tabs/windows on one monitor audible;
  monitors play different audio at once.
- No repeated sign-ins, no browser restarts, no profile cloning / restart-and-copy, no VMs,
  no paying for anything, no app-specific foundations.
- No admin/UAC prompts for Task Manager. wc-audio.exe is the 0.4.5 binary: changed once, with the
  owner's OK (15 Oct, so nothing is lost while the app is turned down); never replace it without
  asking him.
- Don't copy WaylandCraft code. Keep Cobblemon key compatibility. No fake UI.
- Diagnostics/measurement off by default. Small, measurable, reversible changes; no rewrites.
- His PC is also his work machine: ask before anything that takes over the screen, mouse or
  keyboard (self-tests do), or installs/changes anything system-wide. Only touch connected
  folders. Never send his email address anywhere.

## Building and testing

- Natives: native/wc-audio (Rust, target x86_64-pc-windows-gnu from Linux; on Windows the
  normal toolchain works). After rebuilding, copy wc-vdisplay.exe / wc-selftest.exe /
  wc-input.exe / wc-capture.exe into src/main/resources/windowscraft/natives (NOT wc-audio.exe:
  only with the owner's OK, built `cargo build --release --target x86_64-pc-windows-gnu --bin
  wc-audio` like 0.4.4 and 0.4.5, and tested with tools/duck first).
  Build anything that ships with `RUSTFLAGS="--remap-path-prefix=<home>\.cargo=cargo
  --remap-path-prefix=<repo>=windowscraft"` (and a separate `--target-dir`): otherwise the
  binary carries the build PC's user folder in its panic paths (wc-audio 0.4.5 is built that way;
  wc-capture.exe and wc-vdisplay.exe from before still carry it).
  On Windows with the MSVC toolchain, build with `RUSTFLAGS="-C target-feature=+crt-static"`
  (else the helper needs VCRUNTIME140.dll, which not every PC has); wc-capture.exe since 0.15.3
  is built that way (`--target-dir target-static`).
- Mod: `gradle build` → build/libs/windowscraft-<ver>+mc1.21.1.jar (version in gradle.properties).
- Package: zip of dist/installer/*, the jar, and a "Windows self-test" folder (wc-selftest.exe,
  wc-input.exe, wc-vdisplay.exe, wc-capture.exe, dist/selftest/Run self-test.bat) →
  dist/WindowsCraft-<ver>.zip (forward slashes in the zip: made with Python's zipfile).
- CI (GitHub Actions, Windows runners, no real GPU): windows-selftest, windows-game-test,
  sandbox-probe (Sandboxie probe + `wc-selftest --sandbox` end-to-end, 23 checks), movie-test,
  windows-stress.

## Step 0 — measurement: instrumentation done, first findings in docs/STEP0.md (14 Oct 2026)

Measured on the owner's RTX 4060 PC (one-PC benchmark, tools/bench). The measuring + benchmark
(off by default) and the measured fixes (0.15.1, approved by the owner and merged on 14 Oct;
on his server since 14 Oct 10:07, his PC and friends through the in-game update) are on main. Top findings: stream sound
almost entirely dropped on Windows (sound input stamped per packet with the wall clock; fix on the
branch); every new viewer falsely drops to 30 fps and turns the sharer down (fix on the branch);
direct GPU sharing switched off on the owner's PC by a likely false "black" verdict; lip sync
256 ms (sound late); the viewer's FFmpeg held each picture ~3 pictures (fix on the branch:
captured → decoded 136 → 98 ms at 60/s, 306 → 195 ms at ~17/s; sound then ~290 ms late). The Windows self-test ran on the real PC:
64/70 passed (see STEP0.md). Still to measure: a real Tailscale peer, Cobbleverse frame rate,
a 4K monitor, sound by ear.

Original Step 0 brief (kept for reference) — no optimizations yet. Add instrumentation, off by
default, and measure host and peer separately:
- median / P95 / P99 latency and frame times;
- input timing incl. server-thread and render-thread delays;
- capture, encode, transport, decode and present timings;
- decoded resolution, bitrate, drops, A/V sync;
- CPU, Java allocations, GC, RAM, VRAM, FPS and 1% lows;
- the instrumentation's own overhead.
Keep CPU-path (cloud) results apart from GPU findings that need the real PC. Then deliver a
ranked list of confirmed bugs, measured bottlenecks and fixes. After that: the optimization
phases, then the Minecraft-overhead phase (profile no mod / idle / streaming; idle impact ≈ 0).

Suspects from code review (unmeasured — verify before acting): PUB2 send-buffer bug; missing
-forced-idr; stale --ext slot drop; viewer decode size frozen; fps=30 light filter; 150 ms
lookUpNow stall; rate limit drops key-ups; helper priority / 15.6 ms timer resolution; viewer
CPU BGRA conversion + 4 copies; hidden walls still decoded; mipmaps every frame (deliberate,
"rewind" issue); extra render frame; A/V sync off 100–350 ms; 10 Mb/s cap and periodic IDR
pulses; no keyframe cache; input tied to render frames / server thread; duplicate WGC captures;
full FFmpeg restart on quality change; no guest relative mouse.

## 0.15.13 (15 Oct): wc-audio 0.4.5 — nothing lost while the app is turned down

The owner wants a film shared from Edge heard from the wall by both of them ("but I wanted us to
both hear it from the wall": "You hear it from the wall too" stays on), and OK'd changing
wc-audio for this one fix (his rule had it at 0.4.4). 0.4.4 looked for the app's sessions (every
device, every 250 ms) and read the list of every process (every 1 s, and whenever sound started
after silence) on the thread that reads the sound. With his game running one look took ~35 ms
(process list 24 ms median, up to 45; sessions ~10 ms), and application loopback lost sound while
held up — in spite of the 500 ms buffer asked for.
- **Looks run on a thread of their own** ("app checks", `duck.rs` `Checks`). The sound thread
  still reads the found sessions' state and volume (~0.05 ms) right before each decision
  (`Watch`, shared between the two MTA threads): a first try without that let a session turned
  back up in the mixer come through amplified (23 reads, up to 0.15 of full scale), which 0.4.4
  never did.
- **Sound after silence (or too loud) waits, undecided,** until a look begun since has finished
  (~10 ms; after 200 ms it goes on silenced), as when the look ran in line. The process list is
  read only when a session never seen before appears, so each new one is told apart by the app's
  process tree as it is right then.
- Measured with his game running (`tools/duck/compare.sh`, a windowless player, 24 s each):
  | helper | steady sound | sound with dead-silent gaps |
  |---|---|---|
  | 0.4.4 | 988–995 ms per s, 23–25 hold-ups over 30 ms (up to 71) | 988–991 ms per s, 24 hold-ups (up to 113) |
  | 0.4.5 | 999–1000 ms per s, none | 1000 ms per s, 5–13 waits for a look (up to 31) |
- Safety (`tools/duck/safety.py`, test players only): a child process's session is turned down
  and comes through amplified; another app playing meanwhile is left alone; turned back up in the
  mixer 10 times, never through loud (a steady −63 dB tone, under the "too loud" guard, so any
  slip would come out near full scale); its volume back on exit; killed, `--restore` puts it back.
  0.4.4 passes the same.
- `LostSound` stays, as a safety net. Not measured: under the bench's heavy load (three game
  instances), as his game was running; 0.4.4 lost ~2 % there, and with the looks off the sound
  thread it should behave like a plain capture, which lost nothing under that load.
- CI: sandbox-probe failed once on "…and opens on monitor A" (the sandboxed app's window at
  the desktop's default spot, [104,104]) and passed on the re-run: a second flaky check there
  (nothing in 0.15.13 touches window placement).

## 0.15.12 (15 Oct): lip sync for real — the sound with its picture, also far away

The owner: "so there is proof via the tests that this is movie-watching tier". Measured with the
movie test (`tools/movie/windows.sh [netsim]`: the test pattern's flash and beep, both test players
on one PC, measurement off as in normal play) and a real film in a browser (`flashbeep.mp4` — a
white flash and a 1 kHz beep at the start of every second — shared from Edge with
`-Dwindowscraft.bench.windowTitle=… -Dwindowscraft.bench.audio=true`; the stream dumped with
`-Dwindowscraft.exp.dumpTs`, beep minus flash by the stream's own timestamps). Five things, found
in this order:
- **The sharer stamped its sound ~150 ms late.** Its clock started at FFmpeg's `RTCSTART` — when
  the sound filter is set up, after the encoder and the rest have started. Now it starts at a
  moment fixed in Java just before FFmpeg starts (`asetpts=N/SR/TB+<start>/TB`); `feedAudio` counts
  samples from then and writes silence for the time FFmpeg takes to start. The real film, beep
  minus flash in the stream: median 166 ms → −2.5 ms.
- **The cushion is held steady** (it's part of the timestamps): `PcmRing.oldestAgeNanos` (what's
  queued plus the time since the newest arrived: steady through uneven bursts) is kept at
  `CUSHION_MS` by playing a hair faster or slower (`PcmStretch`: 4-point cubic, at most 2 frames in
  480 = 0.4 %), and it jumps when 50 ms off. Before, it could sit anywhere from 40 to 160 ms.
- **Sound the capture lost is put back as silence where it went missing** (`LostSound`, in
  `AudioHub`): wc-audio 0.4.4 looks for the app's sessions on the thread that reads the sound
  while it turns the app down ("hear it in the game"): on a busy PC, a ~25–40 ms stall once a
  second, 5–20 ms of sound lost. Read by a fast reader under the bench's load: turned down 981–995
  ms of sound per second, not turned down 1000 (no gaps over 15 ms). Closed up, everything after a
  hole played early, and the cushion ran dry (2–8 times per 10 s, each an 80 ms gap); now 0–1.
  A film with exact digital silence between sounds is far worse (the helper re-checks the sessions
  whenever sound starts after silence: ~25 % lost under load) — the test film has a faint hiss now.
  The real fix belongs in wc-audio (sessions on their own thread), which stays 0.4.4 (owner's rule).
- **FFmpeg held the pictures back for the sound.** It reads its inputs in step by their
  timestamps, holding back whichever gets 100 ms ahead (`SCHEDULE_TOLERANCE`); the sound, stamped
  when it was heard, is ~130 ms behind (cushion + AAC). Pictures came in bursts every ~90 ms
  (GPU path) or half were dropped (CPU path: 13.8/s). The sound now goes in `AUDIO_SCHEDULE_MS`
  later and `-bsf:a setts` takes that back after FFmpeg's scheduling.
- **Viewers never waited for the sound in normal play** (since lip sync came in, 8f883bc):
  `AvSync` timed arrivals with `Chunk.madeNanos`, which is only set while measuring — 0 everywhere
  else, so the gap between sound and picture came out as nonsense (~−500 ms) and pictures were
  never held for the sound's longer way. Now the time the data was read. Bench runs (measuring)
  never showed it. And `AvSync.PICTURE_AFTER_MS` 28 → 150, measured end to end (28: sound 90 ms
  before its picture, 70: 83, 150: 5; `-Dwindowscraft.exp.pictureAfterMs`).
Also: `-Dwindowscraft.test.silentSpeakers=true` (test players time their sound as always, but
nothing comes out of the speakers; the test options.txt files have Minecraft's own sounds at 0),
dev logs `[audio in]` / `[audio out]` while measuring, `-Dwindowscraft.exp.dumpTsMB`.
Not fixed: the test pattern's own pictures are stamped ~45 ms late (CPU path: stamped when FFmpeg
reads them), so real pictures land a little the other way (sound a hair after the picture); Edge
itself shows the flash ±40 ms unevenly when the PC is loaded with three game instances.

## 0.15.11 (15 Oct): a friend 100 ms away (the owner's girlfriend: ~100 ms that goes up and down); screens flush

New dev tool: `relay.NetSim` — a bad line between the relay and its viewers, both ways, on the
server: `SERVER_EXTRA="-Dwindowscraft.exp.netsim=rtt=100,jitter=60,loss=0.5,spike=250,every=12,len=2"
tools/bench/run.sh …` (FIFO queues with a wandering delay, spikes, loss; input lines over TCP are
late, never lost; unit-tested). Found and fixed with it (the owner, watching Bob's window: "some
kind of extreme distortion on the bottom half"):
- **Smeared pictures.** A piece given up on ("lost for good") — or a catch-up skip — handed the
  decoder a picture with a hole; FFmpeg concealed it and the picture smeared until the next
  keyframe ("PES packet size mismatch", "corrupt input packet" in the viewer's FFmpeg log, also
  without NetSim whenever the viewer had to catch up). `StreamSubscriber.FREEZE_ON_LOSS` is now on
  by default: video held (last good picture) until the next keyframe, sound goes on
  (`-Dwindowscraft.freezeOnLoss=false`: as before).
- **Giving up too soon.** `UdpReceiver.latencyMs`: 2.5 round trips + 70 ms → 3 + 120 (min 160, max
  1200): on a 100 ms line that swings, resent pieces were still on their way. Spiky line
  (rtt=100,jitter=30,loss=1,spike=250), same run, old rule vs new: damaged pictures shown 35 → 0,
  pieces given up 2 → 0, pictures/s 28 → 43–45, captured → decoded median 245 → 182–195 ms
  (`-Dwindowscraft.exp.oldGiveUp=true` compares).
- **Shared windows froze while anyone used them.** `ScreenControl.ensureParked` moved a shared
  window 300 px below the monitor while it was used (Chrome's hover/scroll); a window off every
  monitor isn't drawn: its capture got 0 pictures/s (measured: 49/s on screen, 0 parked, 48 back),
  so the wall and the stream froze — the viewer's FFmpeg then found no data ("could not find codec
  parameters"), restarted every 15 s ("Accept timed out"). And it moved a window of the owner's
  desk. Off now (`-Dwindowscraft.parkSharedWindows=true`: on). A friend sitting at a shared window
  over a steady 100 ms line: 40 pictures/s, clicks 70/70 at 67 ms median, nothing damaged.
  (Whether Chromium apps still scroll for a friend while Minecraft covers them: not shown — a
  quick test with posted wheel messages didn't scroll even uncovered.)
Screens sit flush (the owner: "remove the gap from the block to the screen … without the block
tearing into it", "the full monitor screen to cover the indentation and not pop outward"):
- `DisplayRenderer.towardCamera`: the picture is pulled toward your eye by 1.5 of your screen's
  pixels as they span along the surface from where you are (`pixelAngle` from the FOV and the
  framebuffer's height each frame), at most 0.25 + 0.02·d — ~0.007 of a block at 3 blocks straight
  on (was ~0.08: 0.004 + 0.012·d + (1/32)/facing). Measured in his modpack with Solas (testmod
  `ScreenTour`, `-Dwindowscraft.tour=NAME`: a wall and a gaming monitor block sharing the bench
  window, 10 spots, the game's own screenshots): with only depth precision ("small") or no pull
  the picture vanished entirely seen steeply from 26 blocks (Solas's TAA jitter); the pixel-sized
  pull keeps it there and is a hair everywhere else. `-Dwindowscraft.exp.pull=full|small|off|pixel`.
- Gaming monitor blocks: the screen area is 16:9 now — 128 px fronts (made from the 16 px ones:
  screen 112 × 63 texels = 14 × 7.875 model px, the bezel under it thinner, its mark kept); the
  picture's area `WallRect.M_BOTTOM` 6 → 5.125 / 16, `MonitorPose.SCREEN_B` −3 → −3.875 / 16. Before,
  a 16:9 picture on the 2:1 area left dark bars beside it.
Measured over the simulated line before the fixes (in-game monitor, the stream at the bench's
30 fps default): steady 100 ms: captured → decoded 129 / 149 / 179 ms (median / P95 / max), clicks
on the monitor 59 / 68 / 70 ms; swinging (±30 ms each way, 0.5 % loss): 140 / 273 / 359 ms, longest
pause 253 ms, clicks 62 / 82 / 89; spikes (+250 ms for 2 s every ~12 s, 1 % loss): 145 / 440 / 648
ms, longest pause 610 ms, clicks 61 / 163 / 199. In-game monitors stream at the owner's own
setting (60 on his PC); the bench sharer has no settings file (30).

## 0.15.10 (15 Oct): nothing outside WindowsCraft's own monitors (the owner's rule)

The owner: "everything WindowsCraft does should only apply to WindowsCraft monitors — not my main
desktop, not a real monitor of my physical setup". Swept for Windows-wide changes; what changed:
- Task Manager's "Run as invoker" layer is no longer set while monitors are on (it changed Task
  Manager on the desk too; Windows 11 ignores it for administrators anyway). One left by an older
  version is still put back (`taskmgr_normal_rights` only restores). Self-test: "…how Windows
  starts Task Manager is left alone".
- Friends' game controllers only at your in-game monitors (`handleGuest`: `!monitor` → ignored),
  not at a shared window of your desk; their input to monitors stays on the guest lane.
- SDL's background-events hint (`SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1`) is set in wc-vdisplay's
  own environment, so only what WindowsCraft starts on an in-game monitor (`launch`, Sandboxie's
  launcher) inherits it. Measured (tools/bench/padmonitor.py: the same SDL stand-in, a front
  window in front): started on a WindowsCraft monitor 8/8 controller presses, on the desk 0/8.
  Games started from a monitor's own Start menu or from Steam come from Explorer/Steam: not covered.
Still Windows-wide, on purpose, and only while it matters:
- the taskbar settings while in-game monitors exist (a taskbar per display, each with its own
  windows — Windows has no per-display switch; it's what keeps monitor apps off your own taskbar;
  it would put a taskbar on a second physical screen too);
- your pointer speed / acceleration neutral only while you look around with Alt at a monitor;
- a friend's virtual controller is visible to every game on the PC while they sit at a monitor
  (Windows has no per-window controllers); it exists only then.
- Sandboxie force rules (Own copies of apps): while you sit at / point at a monitor, apps started
  then are boxed even if started elsewhere — to tighten when the owner sets Sandboxie up (not
  installed on his PC).
Controller bench at an in-game monitor (`BENCH_INPUT=1 VIEWER_EXTRA=-Dwindowscraft.bench.pad=true
tools/bench/run.sh monitor 1080 60 40 NAME` — a shared window no longer takes controllers):
70/70, 2.7 / 8.1 / 36.8 ms (median / P95 / max).
Seen: an app started on a monitor through a launcher stub (the venv's python.exe → python.exe)
wasn't closed when the test monitor was removed (its window is the child's; `owned()` has the
stub's pid). Games that start through a launcher could stay open after their monitor goes.

## 0.15.9 (15 Oct): friends' game controllers (ViGEmBus)

Someone sitting at one of your screens (allowed to use it) with a game controller gets a
controller of their own on your PC: a virtual Xbox controller through ViGEmBus (Nefarius; the
owner has 1.21.442 installed — the last release, archived Nov 2023, still works; WindowsCraft
doesn't install it), for any game that reads controllers, also one that isn't in front — so they
play while you play Minecraft. Without ViGEmBus nothing changes (wc-input logs it once).
- Friend's side: `client.GuestPad`, each frame while seated at someone's screen
  (`TheaterMode.controlledRemote()`): GLFW's first gamepad → XInput's buttons and values (GLFW's
  stick Y is down-positive: flipped); sent on change (≤ every 8 ms, a hair of stick noise stays
  home) and once a second; neutral when you get up or unplug it.
- One message: `OP_PAD` (10) — left stick in x/y, buttons + triggers in a, right stick in f (12
  bits each: `Payloads.GamePad`, unit-tested over the relay's text). Older servers' relays pass it
  (the relay doesn't check ops); the game-server path needs `OP_LAST` 10 (0.15.9 servers).
- Your side: `ScreenControl.handleGuest` → that guest's wc-input slot, `pad <buttons> <lt> <rt>
  <lx> <ly> <rx> <ry>`; only once they sit (like keys), and on the guest lane for every wall (a
  controller is the PC's, not a window's). wc-input: one `vigem-client` (MIT, talks to the bus
  driver itself) Xbox 360 target per slot, made with the first `pad`, unplugged at `slot n end`;
  nothing for 3 s → let go of (still plugged in).
- Measured (one PC): helper → XInput 1.0 ms median (1.9 max); controller plugged in 37 ms after
  the first message; the friend's game → XInput on yours 2.0 / 3.3 / 4.5 ms (median / P95 / max,
  70 of 70 presses, `BENCH_INPUT=1 VIEWER_EXTRA=-Dwindowscraft.bench.pad=true` (a shared window then; since
  0.15.10 run it with `monitor`), timed by
  `tools/bench/padcheck.py watch` + `match`). Through GLFW with a controller plugged in on the
  viewer's side (`padcheck.py loop` plugs one in through wc-input's slot 99, `-Dwindowscraft.bench.pad=glfw`
  reads it with GLFW each frame): 39 of 39 presses, physical press → XInput on the owner's side 7.9 ms
  median (one 354 ms at the start); stick directions right.
- Seated heartbeat: a seated guest's once-a-second `OP_POINT` now has a = 1 ("still sitting"); a
  guest the owner had let go (nothing for 3 s: a hitch on their side) is sat down again by it.
  Before, after such a hitch their keys (and now controller) went nowhere until they got up and
  sat down again (found by the controller bench: the viewer stalled 6 s while loading). Older
  owners ignore a.
- Which games, while another window is in front (tools/bench/padapis.py with stand-in games:
  tools/bench/padgame, sdlgame.py): **XInput** (most PC games) 8/8, 1.0 ms; **Windows.Gaming.Input**
  0/8 (Windows gives it to the app in front only — no way around); **SDL 2 as shipped** 0/8 (its
  default); **SDL with SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1** 8/8, 1.6 ms — WindowsCraft could set
  that for what it starts on a monitor, or (the owner's OK: every app) in the user's environment.
  In front, each stand-in got 3/3. DirectInput and GameInput not tried.
- Limits: Windows shows every controller to every game — two friends in two different controller
  games at once can both reach a game that listens to all controllers (idea: Sandboxie's
  ClosedFilePath per monitor box for the other controllers' devices — untried). Keyboard-and-mouse
  games stay as before: window messages per person and window (most 3D mouse-look games read the
  real devices and only answer the real mouse and keyboard). Not done: keyboard + mouse mapped to
  a controller for friends without one.

## 0.15.8 (15 Oct): the self-test on the owner's PC, and what it found

Run on his PC (Windows 11 Home 25H2, build 26200; CI is Server 2025, 26100) three times:
61/65 → 64/67 → 65/67. Fixed:
- **Sitting down: the cursor where you pointed, and held on the monitor.** The game lets go of
  its hold on the cursor when it stops being in front (drops the clip, puts the cursor back where
  it took it from); wc-vdisplay put it back only at 40/90/180 ms after `seat`. On his busier PC
  the game let go later: the cursor ended on the monitor's top edge (2048,1080 for a middle at
  2880,1620) and, until the seated hook took the clip back, could leave the monitor. Now for 3 s a
  dropped clip (seen by `seat`'s watcher, or taken back by the seated hook: `CLIP_RETAKEN`) is
  taken back and the cursor goes back to where it last was (not onto the clip's edge; for 40 ms
  after, unless your own mouse moves: `SEAT_MOVES`). Nothing else ever moves the cursor.
- The self-test's full-screen Edge opens InPrivate: on a PC signed in with a Microsoft account a
  new profile signs itself in and its "now syncing" dialog took the F11 (seen in a screenshot).
- `Run self-test.bat` writes its report to the desktop Windows shows (OneDrive's, often), not
  `%USERPROFILE%\Desktop`. The zip's self-test folder now has wc-capture.exe (check 16 ran "SKIP").
- wc-vdisplay logs what comes to the front while you sit, and why an app that came forward
  elsewhere stays there (for reports).
Still failing on his PC only: "an app that only runs once … comes over to the monitor" (and its
"…back to your desk", which follows). The click on the monitor's taskbar is seen (the shell, on
this monitor), but the desk window coming forward never reaches the helper while seated. Likely
the test itself: his PC has the usual foreground lock (ForegroundLockTimeout 200000), so the
self-test's own SetForegroundWindow may not count as an app coming forward — a real app started
from the taskbar is allowed to. The check now says whether Windows took the test's bring-forward.
Real check for the owner: seated at a monitor, open Discord (already open on the desk) from the
monitor's taskbar or Start → it should come over.
wc-vdisplay.exe is still built with the GNU target (`cargo build --release --bin wc-vdisplay
--target x86_64-pc-windows-gnu`, as before); wc-selftest/wc-capture with MSVC + crt-static.

## 0.15.7 (15 Oct): direct GPU sharing for shared windows too; less work while nobody watches

- Shared windows (not in-game monitors) on GPU sharing in a no-error context: a window that
  drew before the game had checked the new shared pictures wrote over the helper's opaque fill
  (a window's own off-screen pixels are all zeros), so every memory size "read back empty" and
  the share fell back to the CPU. wc-capture `--ext` now keeps new pictures to itself — held, its
  opaque fill only — until the game has checked them: `GpuShareTexture.update` frees all three
  right after `register`. `GpuShare.delivering()` counts announced pictures (waiting for a first
  picture as well would wait for ever). Seen in his modpack (Alice's copy, 1080p60 bench window):
  "wall via direct GPU sharing", 43–50 pictures/s.
- Resized shares on GPU sharing: "free <slot> <n>" names which pictures (the n-th "ntslots"; the
  game's `GpuShare.Slots.version`) — a "free" still on its way for the pictures before a resize
  could hand the game's new, unchecked picture to the helper (the very race above). And the wall
  keeps the last picture until the first one at the new size is copied (`GpuShareTexture`
  `allocPending`: its texture is resized in that frame); before, each resize showed the resting
  CPU capture's picture (up to 1 s old) for a frame or two, uploaded at 8–10 ms on the render
  thread. Tested in his modpack: the bench window resized every 4 s (`BW_RESIZE=4`; ~10 resizes in the 40 s measured): GPU-shared pictures 54/s throughout, never
  "read back empty"; CPU uploads at a resize 11 (7.5–9.8 ms each) → 0; game 175 / 60 → 196 / 68 fps
  (average / 1 % low; partly run-to-run noise).
- The self-test waits 25 s (was 10) for Task Manager to open: its first start on a fresh CI
  runner took longer (the windows-selftest flake: "Task Manager … it didn't open", first round only).
- A shared screen that's live but paused (nobody watching for 8 s): its CPU capture rests and its
  wall copy may be smaller (`StreamPublisher.paused()` in `DisplayManager.tickGpuSharing`).
  Before, a paused stream still counted as "a CPU stream needs frames": full size, full rate, on
  top of GPU sharing (seen: 43–50 CPU captures/s while the wall was fed by GPU sharing; now 1/s).
  Checked both ways with the viewer arriving after the pause (`BOB_DELAY=90 tools/bench/run.sh
  window 1080 60 40 NAME`, and with `SHARER_EXTRA=-Dwindowscraft.gpuCapture=false` for the CPU
  stream): 1920×1080 decoded at 52/s and 48/s after the resume.
- Measuring: nvidia-smi (`-lms 1000`) now ends with the game however it ends (a Windows job,
  KILL_ON_JOB_CLOSE, `SystemSampler.GameJob`); a test game ended from outside left it running
  for ever (52 found on the owner's PC, one per test run).
- The relay listens on the server's `server-ip` when it has one (else all addresses, as before):
  the test servers (server-ip 127.0.0.1) no longer ask Windows Firewall for a port.
- Tools: `tools/prodclient/server.sh SERVER_DIR` (a copy of a modded server: its mods, never its
  world; offline, flat, 25567 / relay 25587) and `run.sh` `PLAYER=` / `SERVER=HOST:PORT`; the
  bench's sharer retries going live (a slow modpack world has no wall yet at first);
  `windowscraft.bench.viewerDir`. Three modpack copies (server, Alice, Bob) need ~20 GB free.

## 0.15.6 (14 Oct): direct GPU sharing works with Sodium (the owner's "black" verdict, solved)

Found with his modpack in a launcher-style client (`tools/prodclient/run.sh INSTANCE`: a copy of
the instance — never its saves — offline, the world opened by the test mod's ProdStart once
loading is done; `BW=1`, `SODIUM_NO_ERROR=0`, `GPU_SHARING=off`). Cause: Sodium's "no error"
OpenGL context (`use_no_error_g_l_context`, on by default) — glGetError is always 0, so the first
memory size tried for the imported D3D11 picture (too small on NVIDIA: it pads rows; the driver
says "Memory object too small") was kept, and the picture read back all zeros. Fix: wc-capture
fills each new shared picture with opaque black; in a no-error context (`GL_CONTEXT_FLAGS` &
NO_ERROR) `GpuShareTexture` reads each imported picture's middle and last pixel (all zeros = wrong
size: next size) and always copies by blit. Verdict key v5 (a "black" verdict is tested again).
The black check's log now also prints the shared pictures' middles as OpenGL sees them.
Measured in his modpack (309 mods, Iris + Solas, 1600×900 game, standing 9 blocks away), 4K
in-game monitor: CPU route 37 fps avg / 15.5 1 % low, 22 pictures/s, captured→wall ~106 ms,
game 4.0 + helper 1.0 cores → GPU sharing 89 / 28.5, 52/s, 0/7/11 ms, 1.8 + 0.2 cores (GPU now
92–100 % busy: Solas + the 4K bench window + DWM). 1080p: 2.3 → 1.6 cores, 97/40 → 107/43 fps.
Other glGetError uses (StreamingTexture's persistent map, Nv12Converter's first check) only pick a
fallback; in a no-error context they never fall back (fine on his card). Still to see in his game:
watching a friend's screen with Iris (the NV12 colour pass).

## 0.15.5 (14 Oct): guests' input on in-game monitors handled the moment it arrives

`ScreenControl` "guest lane" (one thread, "WindowsCraft guest input"): input over the relay to one
of your in-game monitors is handled there at once — not queued for the game thread's next frame,
and the window look-up under a click (`lookUpNow`, up to 150 ms) no longer stalls your game.
Each guest has their own input slot in wc-input, so nothing touches your own mouse/keyboard.
What the lane needs from the game (which walls are in-game monitors, who may use them) is copied
each tick (`refreshRoutes`; an unknown wall is looked up on the game thread, at most 10×/s). A
monitor's guest list only changes on the lane (`dropGuest` forwards; the tick's idle check is
re-checked there). Shared windows (one input for you and them) stay on the game thread. The
server's way of passing input (no relay) goes through the lane too, for monitors.
`-Dwindowscraft.guestLane=false` = before. Measured (two-player bench, in-game monitor, the
sharer's game at 30 fps): click → app 20.7 / 33.1 / 37.1 ms (median / P95 / max) → 2.7 / 6.1 /
16.6 ms. CI doesn't cover it (no in-game monitors in the two-player test; the Windows game test
has no guests): `BENCH_INPUT=1 SHARER_EXTRA="-Dwindowscraft.bench.gameFps=30" tools/bench/run.sh
monitor 1080 60 40 NAME`.

Also in 0.15.5: the sound reserve on a screen you hear (`audio.SoundReserve`, used by
`SpatialAudioPlayer`) gives back a 50 ms step after a calm spell (2 min without sound arriving
late; doubled, up to 30 min, when a step back was followed by late sound within 5 min), never below
where it started (120 ms). Before, it only grew (to 320 ms) and stayed — with lip sync the picture
waits for the sound, so one hiccup kept every later picture that much behind. The extra sound is
taken out 10 ms at a time (crossfaded, ≤ 5/s). Unit-tested; not seen in a real run yet (the
one-PC bench never makes sound arrive late, even starting at 30 ms). Dev switches:
`-Dwindowscraft.exp.audioReserve=MS`, `-Dwindowscraft.exp.audioReserveCalm=S`. The self-test's
main-taskbar check now names what covers the taskbar.

## 0.15.4 (14 Oct): fix for 0.15.3's stream restarts

0.15.3's smaller wall copies reached `StreamPublisher.onFrame`, which takes the CPU capture's
picture size as the window's size: on the GPU path (NVENC, the owner's PC) every shrink/grow
read as "window resized" and restarted the stream (two-player bench: 14–16 restarts in 40 s,
viewer captured→decoded 315 ms median, > 2 s P95). Now onFrame takes the window's size from the
session's full size and never hands a smaller copy to the encoder, and any stream not on the GPU
path keeps the copies full size. Same bench after: 0 restarts, 54–63 ms. CI can't see this (no
hardware encoder: CI streams always take the CPU path) — the local two-player bench does
(`BENCH_INPUT=1 tools/bench/run.sh window|monitor 1080 60 40 NAME`; "window resized" in
alice-NAME.log). Also: the bench's input mode never ran (`BENCH_INPUT=1` reached Java as "1");
`clicks.py` now times each click from the viewer's send to the bench window getting it; the
self-test skips Task Manager where Windows starts it as administrator (`taskmgr_elevates`).

## 0.15.3 (14 Oct, branch `next-0.15.3`): Automatic / Advanced settings, copies as sharp as they look

docs/SETTINGS.md "Built (0.15.3)" has the model (`ClientSettings.Auto`, `on(Auto)`,
`on(Auto, Display)`, `Display.overrides`), the UI and the old-file migration. First new Automatic
decision: `display.CopySize` + wc-capture `shrink <n>` (CPU route only; GPU mipmaps before the
read-back). Measured (docs/STEP0.md, "0.15.3"): a 4K in-game monitor across the room 21 → 30
pictures/s, 1 % lows 40 → 55–81 fps (two runs), game CPU 2.2 → 0.5–0.7 cores. The owner's own settings file had
off-screen pause and the distance limit off: he opens in Advanced with those two Off. Bench:
`-Dwindowscraft.bench.far=N` (a phase N blocks away), `-Dwindowscraft.bench.noSit=true`.
Next for the CPU route at full size (sitting at a 4K monitor): the game copies each picture twice
(helper memory → frame, frame → GPU buffer, 16–27 ms each under load); a frame that borrows the
helper's memory would save one, but needs the mapping kept until every borrowed frame is back
(an unmapped read crashes the game) — only worth it if direct GPU sharing stays unavailable on
his PC (its v4 re-test runs when he plays 0.15.2+).

## 0.15.2 (14 Oct; on main, on the owner's server since 13:04, his PC when his game closes)

Measured on his RTX 4060 (docs/STEP0.md, "0.15.2 on the RTX 4060") and CI-tested: FFmpeg pinned to 9.0.2; viewer pictures as NV12,
coloured on the GPU (`render.Nv12Converter`, off for good if it fails; `windowscraft.gpuConvert`);
no picture held in the viewer's FFmpeg at all (`-threads:v 1` on the raw output +
`relay.PictureEnds` for pictures over 64 KB; `windowscraft.pictureEnds`); the GPU-sharing black
check made hard to fool (key v4); lip sync on viewers while just watching (`stream.AvSync`,
`windowscraft.lipSync=off`). Plans: docs/SETTINGS.md (Automatic / Advanced / per screen).
Guests' input over the relay (VISION 3: not between the server's ticks): UDP viewers' control line
carries it up (relay "OK … C"), the relay checks it with ServerHub's rules and writes it down the
owner's PUB3 connection (else the server's way); `windowscraft.relayInput=false`; CI two-player
test checks it. Next: the owner still handles it on the render thread (waits for the next
frame) — measure `input.host_wait_relay` in a real session before moving the in-game-monitor
path off it. CI now also runs the two-player test (`tools/mptest/run.sh`, mp-test.yml). Needs the owner's PC (with nothing else running): viewer CPU
and in-game latency with the bench, Iris + his shader pack with a viewer (the colour pass puts
GL state back — check), the 40/60 sound reserves (CI runners have no sound device), the
Task Manager / self-test items (his UAC is the Windows default: Task Manager auto-elevates).

## 0.15.1 checks with the owner (installed on his server 14 Oct; PC and friends via the in-game update)

1. Same jar on server and PC (exactly one windowscraft jar per mods
folder). 2. Share a monitor playing a video: the friend hears it from the wall. 3. The friend starts
watching: stays smooth, no immediate "a viewer's PC can't play it fast enough". 4. A minute in front
of an in-game monitor with his usual shader pack: Direct GPU sharing on in ⚙ Settings, or send the
"Direct GPU sharing looked black" log line. 5. Friend looks away and back: picture back within a
second, sound never stopped. 6. Lip sync feel (≈0.3 s late expected; next fix: the 40/60 ms
reserve switches, ≈0.19 s, need listening). 7. Both run
`/windowscraft measure start 60` in a session and send the reports.

## Also waiting on the owner (Windows test list for 0.15.0)

1. Install on PC and server. 2. U on a monitor → Own copies of apps → Set up (no Sandboxie window
ever). 3. Brave + video on monitor 1 and on monitor 2: each only from its own wall; desk Brave
untouched. 4. Sign in on monitor 1's Brave, close, reopen: still signed in. 5. Discord open on
desk, opened from a monitor: comes over. 6. Get up, Brave from own taskbar: the normal one.
7. Quit Minecraft while seated, open an app on desk: normal. 8. Anti-cheat game from a monitor:
runs normally.
