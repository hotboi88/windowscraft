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
- Mod: `gradle build` → build/libs/windowscraft-<ver>+mc1.21.1.jar (version in gradle.properties).
- Package: zip of dist/installer/*, the jar, and a "Windows self-test" folder (wc-selftest.exe,
  wc-input.exe, wc-vdisplay.exe, dist/selftest/Run self-test.bat) → dist/WindowsCraft-<ver>.zip.
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
