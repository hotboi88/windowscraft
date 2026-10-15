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
- No admin/UAC prompts for Task Manager; wc-audio.exe stays the 0.4.4 binary.
- Don't copy WaylandCraft code. Keep Cobblemon key compatibility. No fake UI.
- Diagnostics/measurement off by default. Small, measurable, reversible changes; no rewrites.
- His PC is also his work machine: ask before anything that takes over the screen, mouse or
  keyboard (self-tests do), or installs/changes anything system-wide. Only touch connected
  folders. Never send his email address anywhere.

## Building and testing

- Natives: native/wc-audio (Rust, target x86_64-pc-windows-gnu from Linux; on Windows the
  normal toolchain works). After rebuilding, copy wc-vdisplay.exe / wc-selftest.exe /
  wc-input.exe / wc-capture.exe into src/main/resources/windowscraft/natives (NOT wc-audio.exe).
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
  70 of 70 presses, `BENCH_INPUT=1 VIEWER_EXTRA=-Dwindowscraft.bench.pad=true`, timed by
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
