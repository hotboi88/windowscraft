# WindowsCraft — full spec for whoever continues this (read before touching anything)

Read in this order: this file → docs/VISION.md (the owner's own words, the long-term goal) →
docs/HANDOFF.md (state at hand-off) → docs/ARCHITECTURE.md (older but still the backbone) →
README.md (what users are told; keep it true).

---

## 0. Getting started on the owner's PC

- Repo: https://github.com/hotboi88/windowscraft (public — never commit anything personal, no
  emails, no names, no logs from his PC). Clone it into the folder he connected.
- Toolchain (ask before installing anything system-wide; per-user installs preferred):
  - JDK 21 and Gradle (the build uses Fabric Loom; `gradle build`).
  - Rust (rustup). On Windows, build natives with the normal MSVC or GNU toolchain:
    `cd native/wc-audio && cargo build --release`.
  - Git.
- The mod runs inside the **Cobbleverse** modpack (Fabric, Minecraft 1.21.1). His instance's
  `mods` folder is where the jar goes; ask him where the instance lives (launcher-dependent).
- **His PC is also his work computer.** Before anything that takes over the screen, mouse or
  keyboard (wc-selftest does: it adds virtual monitors, moves the mouse, types, blinks screens),
  or installs/changes anything system-wide (display driver, Sandboxie, registry), ask and let
  him pick the time. Reading, building and measuring in the background are fine. Never touch
  anything outside the connected folders without asking.

## 1. Who you're working for and how to talk to him

- He is not a programmer. Explain in plain words, in terms of what he'll see and do. No jargon
  unless he uses it. Short answers; lists only when they help.
- He tests on Windows and reports what he sees (often with screenshots). Treat his reports as
  ground truth; don't argue with what he saw — find out why.
- Be honest about limits ("Windows doesn't allow X"), never promise universal compatibility,
  and never present something as done that isn't tested.
- He gets a packaged zip (or, now, an install into his mods folder) plus a **short Windows test
  list** after every meaningful change.
- He hosts the server; a friend joins remotely over Tailscale. The friend's (peer's) experience
  must be just as good as his.

## 2. What WindowsCraft is

A Fabric mod that puts real, usable Windows screens inside Minecraft and shares them with other
players:

- **In-game monitors**: virtual displays (Parsec virtual display driver) shown on walls (black
  concrete) or on gaming-monitor blocks; also tablets. Windows treats them as real monitors —
  each has its own taskbar and Start; apps opened from it open on it.
- **Sitting down (O)**: your real mouse and keyboard drive that monitor; it's shown flat and
  sharp on your screen like a real monitor. Esc gets up. **Alt + look** points and clicks
  without sitting.
- **Sharing ("Go Live")**: a window, your screen, or an in-game monitor streams to everyone
  nearby: capture → hardware encode (NVENC/AMF/QSV, x264 fallback) → MPEG-TS over the server's
  relay (port 25585, not the Minecraft connection) → decode on each viewer → wall texture.
  Remote control with permissions. Theater mode (O on someone else's screen).
- **Spatial audio**: the screen's sound comes from the wall in 3D (distance, occlusion), synced
  for everyone; or "direct" sound; per-screen volume 0–500 %.
- **Natives** (Rust, native/wc-audio/src/bin): `wc-vdisplay` (virtual monitors, seating,
  pointing, window placement, taskbars, input hooks, Sandboxie control — the big one),
  `wc-capture` (capture), `wc-audio` (WASAPI per-process loopback / muting — **keep the 0.4.4
  binary**, don't replace it), `wc-input` (input injection), `wc-apps`, `wc-selftest` (Windows
  end-to-end tests). Java side: `src/main/java/dev/windowscraft/*` (monitor, capture.win, client,
  render, stream, relay, net, audio, ui, block).

## 3. The desired experience (what "right" looks like)

### On the host's own PC
- **Feels native**: an in-game monitor should feel like a real Windows monitor — WaylandCraft-
  level responsiveness, no smear, no lag, crisp text, smooth cursor.
- **Sit-down view**: always the sharp flat view (not just seated — also when watching), sized so
  the monitor's picture touches the edge of what you can see of the game and no more: never
  cropped at the top, never under the real Windows taskbar (windowed: stops above the taskbar;
  fullscreen game: full height). Zoom is remembered. Cursor must look clean at every zoom (no
  fringe, no stray black pixels).
- **Full-screen video stays full screen**: no taskbar over a full-screen video, and pressing Esc
  (getting up) must never bring the taskbar back over it.
- **Alt-pointing**: no flicker of the Minecraft title bar, no hover flicker, every click lands.
- **Keys**: Alt+Space and other Alt combos work for the monitor's apps; game keys still work
  where intended.
- **Apps open where you start them**: from a monitor's Start/taskbar → on that monitor, at their
  normal size. That includes Store apps (Calculator, Settings), File Explorer and Task Manager
  (Task Manager without any admin prompt).
- **Single-instance apps** (Discord, WhatsApp, any app that insists on one window): already open
  elsewhere → opening it from a monitor brings it over cleanly — one move, no glitching or
  flicker, centered, modest size. Opening it from the real desk brings it back the same way.
- **Taskbars**: each monitor's taskbar shows only its own apps; never cut in half, never on the
  wrong screen, always back in place after joins/resolution changes.
- **Joined monitors** (blocks placed side by side) are joined in Windows too: windows drag across.

### Sound (non-negotiable — see §4)
- Each monitor plays only its own sound. Never from another monitor, never from the desk.
- Every window and tab of an app on one monitor is audible; different monitors play different
  things at the same time.
- Implemented as: "Own copies of apps" via Sandboxie-Plus (each monitor its own box → separate
  processes → separate sound, logins kept per monitor); otherwise sound follows where an app was
  last used.

### For peers (just as good)
- Sharpest, smoothest, lowest-latency stream their hardware and network allow: crisp text,
  high resolution, smooth frame pacing, hardware decode, movies watchable together (A/V in sync).
- Remote control that feels immediate, adds almost no overhead beyond viewing.
- Looks and feels the same as for the host (same flat sharp view in theater/seated).

### Minecraft itself
- WindowsCraft must cost almost nothing when monitors aren't in use, and little when they are:
  FPS, 1 % lows, frame times, RAM, CPU, GPU.
- All in-world features stay: world-space screens with proper occlusion, spatial audio,
  shaders (optional on screens), lighting/brightness/emissive options, seated/fullscreen,
  tablets, shared viewing, permissions.
- Small, predictable overhead beats occasional stutters.

## 4. Non-negotiables

- **Sound**: never mixed between monitors / desk / speakers; all same-monitor tabs and windows
  audible; monitors simultaneous.
- **No repeated sign-ins**; **no browser restarts**; **no profile cloning / restart-and-copy**
  (he explicitly rejected it); **no VMs** (they can't game); **no paying for anything**; **no
  app-specific foundations** (general mechanisms first).
- Sandboxie-Plus bundling is approved (he likes multiple instances of apps and games); its
  settings live in the monitor manager UI. It must never show its own UI.
- No admin/UAC prompt for Task Manager. `wc-audio.exe` stays the 0.4.4 binary.
- Don't copy WaylandCraft code. Keep Cobblemon key compatibility. **No fake UI** (nothing in
  the UI that doesn't really work).
- Diagnostics/measurement **off by default**.
- Small, measurable, reversible changes. Reuse existing systems; no rewrites, no duplicate
  pipelines, no redundant dependencies. Benchmark before optimizing.
- Never expose the owner's personal info (email etc.) — the repo is public.

## 5. Where things stand (0.15.0)

All cloud tests green (Windows self-test, in-game game test, Sandboxie probe + 23-check end-to-end
own-copies test). Details and the hard-won lessons: docs/HANDOFF.md. Highlights:

- Own copies of apps per monitor (Sandboxie-Plus 1.18.6, pinned + SHA-256-checked, silent install,
  hidden). Force rule follows where you sit/point; carved around exceptions (Sandboxie can't make
  exceptions inside a forced folder); confirmed with Sandboxie before it counts; cleared on
  get-up / off / helper stop / helper crash / game gone. Discord, WhatsApp, launchers,
  anti-cheat games, Java/Minecraft never become copies; "Open another copy here" can box one on
  purpose. No [#] title marker.
- Monitor manager (U): own copies toggle, set-up with progress, "running as its own", open
  another copy, "always run normally" list, two-click reset of a monitor's apps.
- Sit-down view, fullscreen/windowed fit, taskbar fixes, bring-over, sound-follows-last-use,
  Store apps, Alt-point fixes — see README.

**Untested on real hardware yet** (cloud runners have no GPU): actual sound separation by ear,
games/video inside copies, all GPU-path performance. His 0.15.0 test list is in HANDOFF.md —
ask him for results before building on assumptions.

## 6. What's next (in this order)

### Step 0 — measurement only (approved, not started)
Add instrumentation, **off by default**, then measure. No optimizations in this step.
- Host and peer separately.
- Median / P95 / P99 end-to-end latency and frame times.
- Input timing, including Minecraft server-thread and render-thread delays.
- Per stage: capture, encode, transport, decode, present.
- Decoded resolution, bitrate, drops, A/V sync.
- CPU, Java allocations, GC, RAM, VRAM, FPS and 1 % lows.
- The instrumentation's own overhead (on vs off).
- Keep CPU-path results (reproducible in CI) apart from GPU findings (only on his PC — now
  possible).
- Deliverable: a ranked list — confirmed bugs, measured bottlenecks, proposed fixes with
  expected gain — shown to him before implementing.

Code-review suspects to verify (not measured; don't fix blind): PUB2 send-buffer bug; missing
`-forced-idr`; stale `--ext` slot drop; viewer decode size frozen; fps=30 light filter; 150 ms
lookUpNow stall; rate limit drops key-ups; helper thread priority / 15.6 ms timer resolution;
viewer CPU BGRA conversion + 4 copies; hidden walls still decoded; mipmaps every frame
(deliberate — the "rewind" issue); an extra render frame; A/V sync off by 100–350 ms; 10 Mb/s cap
and periodic IDR pulses; no keyframe cache for new viewers; input tied to render frames / the
server thread; duplicate WGC captures; full FFmpeg restart on quality change; no guest relative
mouse (one cursor per Windows session — a Windows limit).

### Then
1. Optimization phases, one measured change at a time (local responsiveness incl. a more direct
   GPU path for local screens if feasible without hurting streaming; peer quality and latency;
   gaming-grade input; shared capture/encode for several viewers of the same screen; resources
   only when needed).
2. Minecraft-overhead phase: profile three scenarios — no mod, mod idle, streaming — and drive the
   idle cost to ~zero. No extra processes, copies or synchronization unless a benchmark shows a
   net win.
3. Keep extending Sandboxie integration (per-monitor control of which apps/boxes, persistence,
   instances) — verifying audio isolation, app compatibility and input focus as separate concerns.

## 7. How to work

- Build: natives (`cargo build --release` in native/wc-audio) → copy wc-vdisplay.exe,
  wc-selftest.exe, wc-input.exe, wc-capture.exe into src/main/resources/windowscraft/natives
  (**not** wc-audio.exe) → `gradle build` → build/libs/windowscraft-<ver>+mc1.21.1.jar.
- Version in gradle.properties (`mod_version`); bump it and the first line of
  dist/installer/How to install.txt for each release. The server needs the same jar; clients on
  ≥ 0.14.2 are offered updates from the server in chat.
- Package: dist/installer/* + the jar + "Windows self-test" folder (wc-selftest.exe,
  wc-input.exe, wc-vdisplay.exe, dist/selftest/Run self-test.bat) → dist/WindowsCraft-<ver>.zip.
- Tests: CI workflows in .github/workflows (windows-selftest, windows-game-test, sandbox-probe,
  movie-test, windows-stress) run on push. Keep them green; add a test for every fix. On his PC,
  wc-selftest only with his OK (`wc-selftest.exe --rounds 1 --report report.txt --shots shots`,
  Minecraft closed).
- Test hygiene learned the hard way: never kill processes by window title (it killed a CI runner);
  never use GetWindowText / MainWindowTitle on other apps' windows (hangs on a stuck app — use
  InternalGetWindowText); never force a whole drive into a Sandboxie box.
- Keep README.md and How to install.txt truthful whenever behavior changes.
- Commit small, descriptive commits; push to main (CI runs on push).
