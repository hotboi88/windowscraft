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
  - Without it: an app with a window on a monitor is heard only from the wall, also while used
    on your desk (SoundHome; the owner's ask, 16 Oct); on several monitors, where last used on one.
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
- One-file installer for friends (since 0.15.13): `sh tools/installer/build.sh <jar> <folder>` →
  "Install WindowsCraft <ver>.exe" with the jar inside (std-only Rust, built with the path
  remaps). Double-click: finds Cobbleverse game folders (the official launcher's custom game
  folders from launcher_profiles.json, Modrinth, CurseForge, Prism/MultiMC, ATLauncher, or a
  played folder near the user's files), takes older WindowsCraft jars out, waits while the game
  holds them, asks for the folder if none is found. Another Cobblemon pack, a backup or a server
  is left alone. `--list` shows what it would touch, changing nothing.
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

## 0.15.33 (16 Oct): monitors don't affect each other

His words, the same evening:
- **"i just added a monitor n it shut down like all the other monitors except 1 and then said on top
  of that that it failed to start the monitor i was trying to start and that 'screen share failed'"**
- **"overtime i drop frames"**
- **"i add or remove a monitor and i literally hear all the audio from the other monitors instantly
  go to my screen"**
- **"it keeps starting up with a window on it from probably another monitor … i dont want windows
  to ever go onto new screens … i dont want them effecting each other"**
- **"ONCE A MONITOR IS TURNED OFF ALL ITS APPS SHOULD BE ELIMINATED IMMEDIATELY"**
- **"i dont want the 'own brave' thing back again"**

What his log showed (21:28:49, a fourth monitor added):
- Each monitor coming or going makes Windows put back the layout it remembers for that exact set
  of monitors, or its own for a set it hasn't seen. Two other in-game monitors came back
  4096×2160 and one 1680×1050.
- The game put sizes back one at a time (`keepOwnSize`). The helper's `set_mode` moved "the ones
  to its right" along a row, but in-game monitors touch only at corners (`arrange` places each
  group off the previous one's bottom-right). After a resize the next monitor was left floating,
  and Windows rearranged everything its own way.
- Monitor 0 dropped out of Windows' list. `refreshRects` turned it off after two misses less than
  a second apart. Removing it changed the set again, and so on, until one monitor was left. Every
  "moved" also stopped and started its share (`restartShares`). That closed the app-sound capture
  (sound on his speakers until the restart), ended the stream ("screen share failed"), and
  restarted the captures mid-shuffle.
- At 17:24 the same shuffle made Windows Graphics Capture and GPU sharing of "In-game monitor 3"
  fail (`0x80070057`). Both were given up for good (`FallbackProducer.usingGdi`,
  `Share.gpuFailed`), so the wall ran GDI capture for four hours at ~35 pictures/s, 26.6 ms each:
  a processor core busy the whole time.

What changed:
- **Helper.**
  - Each monitor remembers the size it's meant to be (`Added.want`).
  - `arrange` lays every monitor out at that size and in one change puts back what Windows
    changed: another size, or switched off while still listed.
  - `settle` runs after every add and every remove, once the screens have been still for 700 ms.
  - `set_mode` sets `want` and lays everything out again, instead of shifting a row.
  - The watcher switches on again a monitor that's listed but off the desktop for about 3 s
    (internal `relayout`).
  - The first CI run showed what "switched off by Windows" really looks like. When Windows'
    remembered layout has one of ours off, the monitor drops out of `EnumDisplayDevices`
    altogether, and its display name can go to the next monitor that comes on (two entries
    `\\.\DISPLAY3`, then a false "went away by itself").
    - The driver still has it: `QueryDisplayConfig(QDC_ALL_PATHS)` lists its target, available
      and inactive (`switched_off`).
    - `switch_on` activates a path to it from a free source, then
      `SetDisplayConfig(… SDC_SAVE_TO_DATABASE)`. The fix is saved as Windows' layout for that set
      of monitors.
    - `arrange` looks each monitor's name up by its UID (`device_for_index`), not the cached one.
    - A new monitor that takes a cached name marks the old entry `?`.
    - The watcher calls a switched-off monitor gone only after about 30 s of failed tries.
    - The watcher now runs on a thread of its own: the display queries can wait while Windows
      changes its screens, and the driver's pings must never wait behind them.
  - A monitor really gone (`gone`) first gets an internal `lost`: its box closes, its own apps
    close wherever Windows put their windows (`close_own`: by `owned`/`placed`/box, not by area),
    and the driver lets it go. The game then turns its wall on again, empty.
  - `clear_monitor` on a monitor already off the desktop closes its apps the same way (before, it
    returned early and closed nothing).
  - A new monitor starts empty: `forget_monitor` clears `owned`/`placed`/`watch`/`LAUNCHES` for its
    number. Windows' "remember window locations" put an old monitor's windows back on a new one
    with the same number, and the stale entries kept them there.
  - `keep_windows_put` exempts a window brought from the desk only if it was on the desk just
    before. Before, a window brought to A long ago that Windows moved onto the new monitor stayed
    there.
- **Game.**
  - A moved monitor's share follows it (`DisplayManager.retarget` → `Share`/`CaptureSession`/
    `StreamPublisher.retarget`): the sound capture and the stream carry on.
  - `restartShares` is gone.
  - A monitor missing from the list is turned off only after 15 s (`MISSING_GRACE_NANOS`); the
    helper's `gone` is the real signal.
- **Captures.** These are retried instead of given up for good (`Backoff`):
  - GPU sharing: 5 s, then doubling to at most 5 min.
  - Graphics Capture under GDI: the same.
  - The stream's GPU capture: at the next start after 2 min, at most every 30 min.
  - A good minute starts the waits over.
- **"Own Brave" removed**: the helper command, the backend call and the button. The "one program,
  one sound" note stays.
- **Self-test 17.**
  - Windows' doing is reproduced by hand: one monitor at another size, then another switched off.
  - Each time, a monitor comes or goes; then a new size is set for one.
  - Checked: the sizes are back, it's switched on again, none goes, none overlaps, each touches
    another screen.

## 0.15.32 (16 Oct): apps run normally again (own copies off unless turned on)

- **"i can't open minecraft java or bedrock, roblox doesn't open at all anymore, steam doesn't open
  it just breaks now"**, then **"claude desktop app is just gray blank"**. His log and Sandboxie
  show what happened. The force rule, on while he sits at a monitor, put these into the box:
  - Steam: asked to sign in again, then "Steam Service Error".
  - The Epic launcher and Fortnite: Easy Anti-Cheat "Launch Error (30001)".
  - Roblox, started from a boxed Brave.
  - Store apps, everything in `C:\Program Files\WindowsApps`: the Minecraft Launcher, Bedrock, Sea
    of Thieves and Gaming Services ("msgamelaunch://notifyLaunch…" errors).
- Worse, Sandboxie's force rule applies to every program that starts while it's on, whoever starts
  it:
  - A process the Claude app (running on his desk) started at 18:44:57 was in the monitor's box
    (`SbieApi_QueryProcess`). The app's page process failed to start (Electron
    `render-process-gone`, `launch-failed`, exit code 18 = `SBOX_ERROR_CREATE_PROCESS`), so the
    window stayed gray.
  - Our own tools too: a Python run from Claude Code wrote its edits into a monitor's box.
- A force rule can't be kept to one monitor's apps, so it breaks "WindowsCraft must only affect
  its own monitors".
- His call: **"lets go back to normal for apps. the only reason for sandboxing AT ALL, is just so
  that we can get multiple instances of the same app to not mix sound with each other across
  monitors. if there's a way to get it done without sandboxing i prefer it actually"**.
- `Display.ownCopies` is now off by default, and every monitor goes back to off. (Before, it was
  saved only when off; now it's saved only when on.) The toggle stays, and its text says what
  turning it on does.
- Unchanged: each app's sound plays only from the monitor it has a window on (`SoundHome`). With
  windows on several monitors, it plays from the one where it was last used.
- Two ways to get a second copy with its own sound:
  - "Own Brave": a separate browser with its own profile, no Sandboxie (removed in 0.15.33, at his
    word).
  - "Open another copy here": that one app in the monitor's box, on purpose. Nothing else is
    boxed.

## 0.15.31 (16 Oct): your desk's windows stay yours when monitors come and go

- **"my apps all disappear from my main desktop sometimes when i close the game"**, then **"now
  they are all 'gone' but when i alt+tab they are there"**. Windows 11's "Remember window locations
  based on monitor connection" and "Minimize windows when a monitor is disconnected" (both on by
  default; his: defaults) — the in-game monitors come back as the same monitors every session, so
  Windows puts desk windows back onto them as they come on (out of sight: they're virtual), and
  minimizes them when they go. Worse, a monitor turning off closed every window on it, and ended
  their apps 3 s later if started that session.
- A monitor turning off now closes only what's its own (`evacuate`: opened or placed there
  (`placed`), started for it (`owned`), its own copies (`box_of`)); any other window on it goes back
  to the desk first ("… was on in-game monitor N without being its own: back to your desk").
- `keep_windows_put`: for ~8 s after a monitor comes on or monitors go, any window that changed
  monitor without WindowsCraft moving it goes back where it was (on the desk, or on its in-game
  monitor at the same spot, wherever that monitor is now). His Windows settings aren't touched.
- **"get rid of all ingame monitors simultaneous instead of making my screen go black 10 separate
  times"**: not possible with the Parsec driver, measured (self-test step 16, WM_DISPLAYCHANGE
  counted by a hidden window): one layout change per monitor whichever way — one after another 3
  for 3; taken off the desktop together first (one `ChangeDisplaySettingsEx` batch) 4 for 3 (at every
  unplug Windows reapplies the layout it saved for the monitors that are left); keep-alive stopped
  so the driver lets them go: it dropped one in 4 s, the rest still one by one. The driver removes
  its monitors one at a time ~3 s apart. Kept: one after another (no waits between the asks), and
  a check that leaving never costs more than one change per monitor. (A driver that creates and
  removes all its monitors at once — e.g. one configured with a monitor count and restarted —
  would be the way; not done.)
- Self-test 10b: a window moved onto a monitor as it comes on goes back; a desk window moved onto a
  monitor comes back to the desk, not closed, when monitors go.

## 0.15.30 (16 Oct): a boxed browser opens your desk's sign-ins

- **"sign-ins aren't carrying over."** Browsers built on Chromium (Chrome, Edge, Brave, Vivaldi,
  Opera, …) lock the sign-ins they save with a key only their own elevation service can open
  (app-bound encryption: `os_crypt.app_bound_encrypted_key` in Local State; his Brave has one). In a
  box that service ran inside it as the box's user and couldn't open the key: the box saw the
  desk's sign-ins and could read none of them. ("Logins follow my desk" never carried a browser's
  over — the probe had said so all along, as a note: "box 1 starts with your desk login: no".)
- Every box now runs each such service as SYSTEM inside it (`chromium_elevation_services`: every
  service whose program is `elevation_service.exe`, whichever browser installed it, in
  [Template_WCShared]: `SandboxService` + `RunServiceAsSystem`, `OpenSamEndpoint=y`,
  `UseChromeSecurePreferencesHack=n` — what Sandboxie's own `Template_Chromium_Elevation` does for
  the browsers it names).
- Probe section 11 (CI): desk Chrome and Edge signed in and closed; in a plain box `wc[none]`, with
  the services run as SYSTEM `wc[wclogin=desk]` — both browsers. Self-test: every elevation service
  on the PC is in the boxes' template.

## 0.15.29 (16 Oct): own copies box again; no open corners on the monitor's lip

- **"it didn't work. sound went straight from my desktop to the monitor once i opened brave."**
  The helper's box work had frozen for good. When the game sends its exceptions again (it does
  whenever it lays the monitors out, and when its anti-cheat scan is done) every box made so far is
  written again — in a `for` over `BOXES_MADE.lock().unwrap().clone()`, whose lock lives for the
  whole loop, and `ensure_box` locks it again: that thread waited on itself, and every thread that
  touched a box after it waited behind it, the one moving the rule with your seat included. So
  nothing you started was a monitor's own copy: the Brave you opened on a monitor was your desk's
  Brave, and the monitor took its sound. Found on the live helper: its threads' stacks, read
  without stopping it (each thread suspended for a moment), named with a build of the same code
  that kept its symbols (`-C strip=none` in RUSTFLAGS leaves the code byte-identical; the profile's
  `strip` changes the layout) — ten threads in `Mutex<Vec<String>>::lock`, under `ensure_box` and
  the force thread.
- Fix: `note_box` / `boxes_made`, the list never locked while anything else runs. Two more locks no
  longer held across calls: the worked-out folders (`force_folders`) and the driver handle when a
  monitor is removed again.
- The watchdog now says when the thread that moves the rule with you hasn't gone round for 10 s
  ("own copies: the rule that boxes what you start hasn't followed you…"), and when it does again.
- Self-test, step 4b: the exceptions (and a box's options) sent again once both boxes exist; the
  rule still follows you to A, and on to B. (The old helper froze right there.)
- **"the monitor blocks have these weird corner holes"**: the lip's top and bottom bars (0.15.22)
  had no end faces, an open notch at each corner. They have them now, in all three models.

## 0.15.28 (16 Oct): own copies set up per monitor; joined monitors share one box

- **"a set of advanced settings … per monitor … do you even want files to save"** and **"when a
  conjoined monitor gets this i would want it to not function as a conjoined monitor anymore"**.
  Per monitor (with own copies on): "What its apps change" — forgotten when they close
  (`followDesk`, default) / kept here; "Files you save there" — your own folders (default) / this
  monitor only (`Display.filesHere`). Quick picks: Like your desk (forgotten + your folders), Its
  own PC (kept + here), Leaves no trace (forgotten + here: files go with the box). Helper:
  `boxopts <idx> <follow> <deskFiles>` (replaces `follow`); a box gets the `OpenFilePath` lines for
  your folders only when its monitor sends files there (`desk_files_settings`, no longer in the
  shared template).
- "Starts with nothing" isn't offered: Sandboxie's `UsePrivacyMode` runs nothing without a
  supporter certificate (probe: in a privacy box not even cmd.exe ran; a normal box saw the file
  and ran an app from %LOCALAPPDATA%).
- Joined monitors = one PC: two monitors are joined only when their own copies are set up alike
  (`InGameMonitors.sameBox` in `joinedSide`), and a group's apps go in one box — the group's
  first screen's (`groupKey` → `profilekey`). Changing a joined monitor asks: all of them, or just
  this one (it stops being joined: its cursor and windows no longer cross over). The layout
  follows (`arrangeIfChanged` pushes the boxes again on a change). A monitor turning off closes and
  empties its box only when no other monitor still uses it (`box_shared`).
- WinGameTest: joined monitors are one group; one set up differently stops being joined (its own
  root in the layout); alike again, joined again.

## 0.15.27 (16 Oct): screens have names; remembered by name and settings only; the menu has its own size

- **"make it so that monitors are renameable, and remembered by their name and settings. not the
  apps that were on them … if they are destroyed or no longer exist they are not remembered"**:
  `Display.name` (null: the usual "In-game monitor 2" / "Wall 3"), saved in displays.json with its
  settings; "Rename" next to its title in the menu (type, Enter keeps it, Esc doesn't, empty →
  the usual name; at most 32 characters, Ctrl+V pastes). A named in-game monitor's stream is
  "In-game monitor: <name>" — friends' games know an in-game monitor by that start
  (`DisplayRenderer`/`DeskView` `startsWith("In-game monitor")`). What was last shown on a screen
  (`lastSource`, the share picker's preselection) is no longer saved. Forgetting destroyed screens
  was already there: `DisplayManager.tick` removes a screen whose wall/monitor block is gone,
  judged only where its chunks are loaded and well inside your view distance.
- **"allow the monitor manager UI to have a gui scale bar"**: Settings → Menu size (60–200 %, on top
  of Minecraft's GUI scale; `ClientSettings.hubScale`). The hub lays out in its own units
  (`layout()` from `width / k`), draws under a `scale(k)`, takes the mouse divided by k, and scales
  its scissor rects itself (1.21.1's `enableScissor` ignores the pose matrix — checked with javap).
  The slider applies when you let go (the menu doesn't resize under your mouse).
- Own copies, the first switch: written through Sandboxie's service setting by setting, the first force switch took 6.7 s on CI once 0.15.26 added 12 shared folders (the sandbox probe's "sitting at monitor A" checks failed: the rule wasn't there yet). `isolate <idx> 1` now writes the templates and that monitor's box in the background at once; sitting down is one change. Sandboxie settings changes over 1 s are logged.
- WinGameTest: a screen renamed "Movies" is in displays.json, no "last" there; screenshots of the
  menu (named), at 150 %, and the settings page at 150 %.

## 0.15.26 (16 Oct): "Logins follow my desk"; your own folders shared with every monitor

- **"is there any way at all to preserve the sign ins while keeping sound separate on each one"**,
  and then "if it's generalized or 'just for brave' which is a theme i really want to avoid":
  sound needs a separate process per monitor (Windows splits sound by process), sign-ins are
  files. A box is copy-on-write, so it already starts with the desk's sign-ins; it then keeps its
  own changes. New per monitor, on by default: **"Logins follow my desk"** (`Display.followDesk`,
  `follow <idx> 0|1`). The helper looks every ~1 s (with `quiet_sbie_ui`, one process snapshot,
  `box_of` per process): a following monitor's box with nothing running in it for 3 looks in a row
  is emptied (`Start.exe /box:X delete_sandbox_silent`); also when the monitor turns off. Whole-box,
  so any app — nothing knows where an app keeps its sign-ins. Off: the box keeps its own accounts.
- **Files saved on a monitor went into its box**, not your folders (and would now be emptied with
  it): `[Template_WCShared]` gets `OpenFilePath` for Desktop, Documents, Downloads, Pictures,
  Music, Videos (`SHGetKnownFolderPath`, wherever they are on that PC): saved straight to them.
- The probe's privacy-mode check (be5a0f6) is the other direction (every copy signed out): kept
  only as information, not used.

## 0.15.25 (16 Oct): Sandboxie's own window never pops up

- Own copies worked on his PC for the first time with 0.15.24 (13:37: "new programs now start in
  box WCcc591e8a (1038 ms)", no "didn't take it"; `C:\Windows\Sandboxie.ini` made by its service)
  — and with the first boxed program, SbieSvc started Sandboxie Control (parent SbieSvc.exe): its
  "Software Compatibility" question, then "Messages from Sandboxie" (SBIE2112, a boxed app
  denied a named pipe `\Device\NamedPipe\uv\…` of an unboxed one). Now `SbieCtrl_EnableAutoStart=n`
  in [GlobalSettings] and every [UserSettings_…] (`quiet_sbie_settings`, with the templates), and
  the force thread closes SbieCtrl.exe / SandMan.exe started by SbieSvc.exe (`quiet_sbie_ui`,
  ~1 s; one you start yourself has Explorer as its parent and stays).
- **"signed into my browser"** in its own copy: Sandboxie boxes are copy-on-write — a boxed app
  reads your real files (its profile, its logins) and only its changes stay in the box. So a box
  starts as a copy of your desk. To start fresh, the box must hide your data: Sandboxie's
  `UsePrivacyMode` (in his SbieDrv.sys) — to be tried in the sandbox probe (does a boxed app still
  see a file in %APPDATA%; is it free without a supporter certificate; do apps still run).

## 0.15.24 (16 Oct): own copies for real — Sandboxie's settings through its own service

- **"the own copies don't work at all they still have the same logins n everything theres no
  sandboxing happening"**: on his PC there was no `C:\Windows\Sandboxie.ini` at all. The helper
  wrote it straight (`write_ini`: a temp file next to it, then rename) — the Windows folder is
  read-only for normal users, so every write failed silently since own copies came in: no box was
  ever defined, no force rule ever existed (every "new programs now start in box …" said
  "Sandboxie didn't take it"), "Open another copy here" started programs in undefined boxes. CI
  never saw it: its runners are administrators. Now every change goes through Sandboxie's own
  service, `SbieDll_UpdateConf` (as SbieIni.exe does; normal users may unless `EditAdminOnly`),
  only what differs from the file (`section_of` → 's'/'a' per setting; the file is readable),
  then `SbieApi_ReloadConf`. The breakouts live in `[Template_WCShared]`, the force folders in
  `[Template_WCForce]`; a box has `Template=WCShared`, and `Template=WCForce` while you sit at it —
  moving the force rule is one change, not ~170. Without `SbieDll_UpdateConf` (old Sandboxie) the
  file is written straight as before. The "took it" check also accepts the template name; the
  self-test's rule reader counts `Template=WCForce` in a [WC…] section.

## 0.15.23 (16 Oct): a guest's held button shows as held; a new monitor doesn't take an app's sound; 720p30 kept

- **Guests' clicks, the safe upgrade** (he chose it over pen/touch injection, which takes the
  game's focus): wc-input sets the button in the app's own shared key state while the guest
  holds it (`button_state`: GetKeyboardState/SetKeyboardState while attached — never while
  you're in that app), so apps that ask `GetKeyState` before taking a click or a drag see it.
  Self-test: "…and to an app that asks Windows, the guest's button shows as held". Still to try
  ("another reliable way for real input without pausing my focus"): pen injection with the
  target made `WS_EX_NOACTIVATE` for the moment, or UI Automation Invoke at the point.
- **"i'm not even using brave … on a new monitor i created but i can hear it from" there**: an app
  on several monitors, not yet used on any, played from the first monitor by source id. Now from
  the one it was on first (`SoundHome.FIRST`), until it's used on another.
- Streams: decode trouble no longer lowers a stream that's already ≤ 30 fps at ≤ 720 lines
  (`lightAlready`): those steps only cut bits — a blurrier picture, not a lighter one.
- He was still on 0.15.18 when "starting the second monitor on joint monitors doesn't work" and
  "all the monitors shut off" (12:20): the stuck helper 0.15.21 fixed; 0.15.22 installed 12:36.

## 0.15.22 (16 Oct): every app its own copy by default; real app names; the screen behind the lip

- **"make it so all apps run sandboxed by default"**: own copies were already on for every new
  monitor (`Display.ownCopies = true`), but the helper let Discord, WhatsApp, Java, Steam, Epic,
  Battle.net, Riot and the Minecraft launcher out of the box by name (`ALWAYS_NORMAL`, 26). Now
  only what can't run in a box: Explorer, Task Manager, Settings, msiexec and the anti-cheat
  programs (10). The game you play in is let out by its folder (game dir, java.home), anti-cheat
  games by theirs; "Always run normally" lets any other out.
- **Real app names** (asked earlier: "Whatsapp.root", "Lghub", "Steelseriesggclient"…):
  `ui.AppNames` reads the program's version info — ProductName, else FileDescription (Windows'
  own say "… Operating System"), a dotted one-word name cut at the dot ("PowerToys.QuickAccess"
  → "PowerToys") — else the tidied file name. Store apps (an AppxManifest.xml in the exe's folder
  or up to 3 above: WindowsApps, XboxGames) aren't offered as copies: Sandboxie can't run them.
  On his PC that's WhatsApp, Claude, Notepad, Windows Terminal, the Xbox Minecraft launcher.
- **"the monitor block screen doesn't yet go behind the lip"**: there was no lip — the panel was one
  cuboid z 7..9 with the bezel painted on its flat front, and the picture sat on that face
  (`M_FRONT = 7/16 − 1/1024`), pulled toward the eye. Now the templates (block, diagonal, panel)
  have the panel 0.5 px thinner and four lip bars (top, bottom 1.125, sides 1) 0.5 px proud
  around the screen opening, front UVs mapped to the same texels (u flipped on north faces); the
  picture sits `WallRect.RECESS` (0.5/16) back (`M_FRONT = 7.5/16 − 1/1024`; panels
  `FRONT − RECESS`). `DisplayRenderer.towardCamera` caps the pull on monitors so its depth part
  stays under the recess (`(RECESS − 0.004)/facing`), and `drawInFront` drops the slope bias for
  them (`recessed`, set per wall in `quad`/`drawPointer`). Walls are unchanged.

## 0.15.21 (16 Oct): the helper can't hang on Sandboxie; monitors go off together and take their apps

- **"i just had all the monitors shut off for no reason"** (11:31:53, 0.15.18): he turned on a third
  monitor; Windows applied a remembered three-monitor layout (monitor 0 4096×2160 → 1800×1200,
  monitor 1 1920×1080 → 4096×2160), `keepOwnSize` asked for both back, and the helper never
  answered again: the first question waiting (`windows`, sent right after `arrange` answered)
  had 0 lines after 80 s, so 0.15.18's grace ran out and it was restarted. Not slowness — stuck.
  Just before, `Sandbox.pushAll()` (from `refreshRects`) had sent `isoexclude`, whose handler on
  the command thread rewrote every box section and called `SbieApi_ReloadConf` (and Sandboxie
  had been "didn't take it" on every force change all day). Now: the Sandboxie part of
  `isoexclude` runs on its own thread (logs if the reload takes over 2 s); one writer of
  Sandboxie.ini at a time (`INI_LOCK`: the force thread and the command thread both wrote it);
  and a watchdog names the command (and its last `step`: writing Sandboxie.ini, asking Sandboxie
  to reload, Windows applying a layout, listing windows) every 5 s once one runs over 10 s. If it
  happens again, the log says where.
- **"stop them from all shutting off one at a time … 5 monitors i have to wait like a whole
  minute"**: leaving the world sends one `remove` per monitor; each waited up to 8 s for its own
  copies to close, then up to 3 s for the display to go. The helper now reads ahead 150 ms for
  more `remove`s and does them together (`remove_many`): every box's apps close at the same time,
  then the monitors go right to left (as `shutdown_monitors` did). Answers stay in order.
- **"monitors should destroy all their processes / apps along with them"**: a monitor's own
  windows were moved to your desk and then sent WM_CLOSE; anything asking "save changes?" stayed
  there, and only windowless leftovers were ended (after 6 s). Now its windows are closed where
  they are (only ones brought from your desk go back), and 3 s later every program started while
  it was on and still running is ended, unless it has a window off the monitor (your desk Brave).
  Its box gets 3 s (was 8) before `/terminate`. At the helper's start, every WindowsCraft box is
  emptied (`end_box_leftovers`): a monitor's own copies exist only while it's on.
- Asked next: every app its own copy by default (new monitors with own copies on; Discord,
  WhatsApp, launchers as copies unless added to "Always run normally"); the monitor block's
  picture behind the frame's lip; fewer display changes per monitor start (Discord's whole-screen
  share pauses on each).

## 0.15.20 (16 Oct): an app's sound moves between walls without ever reaching your desk

- **"it's not really a stutter it just goes really loud on my main desktop and then transfers
  back if i click on brave on a separate ingame monitor"**: one Brave (one process) with windows
  on two monitors. `SoundHome` sent its sound to the monitor last used, and `MonitorAudio` did it
  by closing that wall's capture at once (wc-audio gives the app its volume back: full volume on
  his speakers) while the other wall started its own up to 0.7 s later (`--duck` again); the
  losing wall's mixer then ran dry, so its player kept growing its delay (120 → 270 ms). Now one
  capture per app (`MonitorAudio.APPS`), shared by every monitor it has a window on: it lives
  while the app is on any monitor (plus 2 s, `endSoon`: a monitor's picture restarting closes and
  reopens its sound), and only `App.wall` — which wall's buffer its sound goes to — changes:
  `SoundHome.usedOn` moves it at once (the follower looks every 200 ms), the walls' looks confirm.
  Each wall keeps a buffer for every app on its monitor, so it writes silence rather than running
  dry. Captures start outside the mixer's lock (they start a process), and wc-audio.exe is
  checked against the jar once per run instead of per capture. wc-audio.exe unchanged.
- **"make the settings icon an actual settings icon pixel art"**: the hub's "⚙  Settings" used a
  character Minecraft's font doesn't draw as a gear; now `UiKit.gear`, an 11 × 11 pixel gear.
- Discord "screen" share pausing whenever a monitor is turned on: he shares the whole screen;
  adding/changing a display resets Windows' screen duplication. Not looked at yet.

## 0.15.19 (16 Oct): in-game monitors at your screen's refresh rate; a taskbar at its monitor's scale; Ctrl+Alt to look around

- **"my apps feel bogged down on the ingame monitor. i thought it should have no input delay and
  no blur"**: his screen is 1920×1080 at 165 Hz; every in-game monitor was made at 60 Hz (`HZ`)
  and its wall fed at most 60 pictures a second (`tickGpuSharing`: `max(60, fps)`). The Parsec
  virtual display offers 24/30/60/144/240 Hz. Now `InGameMonitors.screenHz` (GLFW: the game's
  monitor, else the primary) is asked for on `add`, `setMode` and `keepOwnSize` (Windows picks
  the nearest: 144), and the wall's GPU sharing runs at that rate. Monitors already on keep 60
  until they're turned on again (next game start). The pointer was already drawn live on the wall.
  Blur: his monitor 0 is 4096×2160 at 200 %, shown ~1000 px tall while seated (a 1080p screen): a
  picture shrunk to half blurs fine text — 1920×1080 is the sharp (and cheaper) size for him.
- **"the taskbar cut in half completely. icons cut in half too"**: after `keepOwnSize` put monitor 0
  back from 1600×1200 (100 %) to 4096×2160 (200 %), its taskbar stayed 48 px tall drawing 96 px;
  `fix_misplaced_tray` only checked edge and position, and its fallback kept the current height.
  Now a taskbar whose thickness isn't your own taskbar's at that monitor's DPI (`tray_thickness`:
  `Shell_TrayWnd` thickness → 96 dpi → monitor's effective DPI, ±2 px) is out of place too, and is
  put in place at that thickness. At most 6 tries a minute per taskbar (Windows insisting).
- `tell_taskbar` (WM_SETTINGCHANGE "TraySettings" to HWND_BROADCAST, up to 1 s per window) now runs
  on its own thread, one at a time: it ran on the hook thread (tidy_taskbars from the timer and
  win_event), holding up the low-level mouse hook while every window answered. Logs if it takes
  over 2 s. The command loop logs any command over 2 s (`SlowCommand`) — for the 20 s+ stall.
- **"instead of just alt+scroll or alt+spacebar make it ctrl+alt+scroll and ctrl+alt+spacebar …
  sometimes i need to use alt-holding for image editing apps"**: holding left Alt while seated
  also started looking around (cursor parked, mouse turning the view) and every Alt+key was kept
  for the game — so it's Ctrl + left Alt now for all three: look around (+ mouse), zoom (+ scroll),
  straight again (+ Space). Helper: `LALT_DOWN`/`CTRL_DOWN`, `ALT` = the chord; only Space is kept
  (with its release); every other key with Ctrl+Alt held is the app's (Ctrl+Alt+Z). Plain
  Alt+Tab still switches between that monitor's windows. Game side (theater on a shared window
  or someone else's screen): `ScreenControl.lookHeld`; Alt and Ctrl now reach the app. Pointing
  while walking around stays plain Alt. Self-test: "Ctrl + Alt + Space … never reaches the app",
  new "plain Alt while seated is the app's: Alt + A reaches it and doesn't start looking around"
  (the test window now logs WM_SYSKEYDOWN/UP). wc-selftest.exe rebuilt (MSVC + crt-static,
  `target-static-remap`).
- wc-vdisplay.exe rebuilt (GNU, `--target-dir target-remap`, path prefixes remapped; new import:
  api-ms-win-shcore-scaling for GetDpiForMonitor). wc-audio.exe unchanged (0.4.5).

## 0.15.18 (16 Oct): a slow helper no longer turns every monitor off; a monitor keeps the size you picked

- **"i just changed the resolution on one monitor and they both shut down (joint monitors)"**
  (10:26, 10:34, 10:40, all on 0.15.13): each time 20 s after a size change on monitor 0 the
  next `windows`/`rects` went unanswered — no box command near, so not the 0.15.17 mix-up: the
  helper's command thread was busy or blocked for 20 s+ (cause not yet known; `fix_misplaced_tray`
  → `tell_taskbar` broadcasts WM_SETTINGCHANGE at the same moment each time). The game then killed
  it: the driver drops every monitor, Windows moves their windows to the desk, and its "remember
  window locations" puts them back on whichever monitor has that UID ("it moved all my windows
  from the other monitor … onto it"). Now a question that takes too long is given up (the caller
  gets "busy, try again") but keeps its place (`HelperAnswers.giveUp`: its late answer is dropped,
  never handed to the next question); the helper is restarted only if it's still silent 60 s
  later. Both log what's still waiting and, when it does come, how long the answer took — next
  time the log says how long the stall really is.
- **Monitor 0 went 4096×2160 → 1920×1080 by itself** the moment monitor 1 joined (Windows'
  remembered layout for that set of monitors; `UID256+index` is the same "monitor" every time).
  `keepOwnSize`: when a live monitor's size changes and it isn't ours (`SIZING`), it's set back to
  the wall's saved `monitorW/H`, at most once a minute per monitor.
- Still open (helper, needs a rebuild): log any command that takes over 2 s; a taskbar sized for
  the wrong scale (48 px on a 200 % monitor: "the taskbar cut off in half") counts as misplaced;
  windows Windows moves onto a newly added monitor go back. Brave's sound stutters on every click
  between two monitors (one process, windows on both: `SoundHome` hands it over by closing one
  capture and starting another; MonitorAudio builds it inside `synchronized (feeds)`) — next.
- The mouse "trapped" after dragging a window to another monitor: fixed in 0.15.14 (e3d3457);
  he was still on 0.15.13.

## 0.15.17 (16 Oct): opening a copy no longer shuts the in-game monitors down

- **"Couldn't hand your mouse and keyboard to the in-game monitor: … the in-game monitor helper
  stopped" / "The in-game monitors stopped (their helper closed)"**, each time right after "Open
  another copy here" (Discord, the Xbox Minecraft launcher) or "Reset this monitor's apps". The
  helper answers `boxlaunch`, `boxreset` and `ownbrowser` from their own thread whenever they're
  done; `VDisplayHelper` matched answers strictly in order, so one finishing after the menu's next
  `windows`/`modes` swallowed that question's end line, the question after it never completed, and
  20 s later the helper was killed (his log: "didn't answer "modes" in 20 s", "… "windows" …").
  Now `HelperAnswers` routes those three one-line answers to their own question and everything else
  to the oldest in-order one; each helper run has its own (a killed helper's last lines can't reach
  the next). Unit test `HelperAnswersTest` replays the sequence. Java only: the helper is unchanged.
- The guest's choppy sound / blur (0.15.16 below): her laptop was at 7.2 of 8 GB while playing.
  His log agrees: every lowering from 03:29 was "a viewer's PC can't play it fast enough", never
  network; her viewer is on TCP (UDP: 0 packets). A stall from paging → behind → skip to the newest
  keyframe, which drops the sound too.
- Next (asked): every app's "own copy on each monitor" optional — Discord, WhatsApp, launchers as
  removable defaults in "Always run normally"; only anti-cheat games, Store apps and the game
  itself locked, each with its reason. Plus real app names (version info, not file names), current
  WhatsApp (`WhatsApp.Root.exe`), no "+"/"another copy" for what already runs normally, and decode
  trouble not lowering at all when the source is already 720p30.

## 0.15.16 (16 Oct): a guest sitting still sends your apps nothing; a slow viewer no longer blurs the stream

- **"whenever i leave a show / movie running the time bar keeps popping up … it only happens in
  fullscreen"** and **"hovering … just flashes and flashes the hover ui"** (while he sat at the
  monitor). His guest may use all four of his monitors (`displays.json` controllers). Sitting at one,
  her game sends a heartbeat every second (`flushRemote`: `OP_POINT`, "still here"), and
  `guestOnMonitor` passed each one to the app as a move (`slot N move x y` → a posted
  WM_MOUSEMOVE at her pointer). A video in full screen shows its controls on any move; under his
  own mouse the hover went to her spot and back once a second (seen: Brave's link bubble hiding and
  showing every ~1.02 s with his cursor still). In a window her pointer is usually off the video,
  hence "only in fullscreen". Now a heartbeat is passed on only if the pointer moved since the
  last move sent (`Guest.movedX/movedY`). Windows game test 1745: three heartbeats at one spot →
  no move; a real move → a move. (Likely also the lichess drags that "lag": the piece was pulled
  to her spot once a second.)
- **The guest's laptop: "youtube at 720p60 is blurry"; Spotify choppy.** His log: 10 of 24 stream restarts were
  "a viewer's PC can't play it fast enough", walking the stream down to step 4 (1080×720@30,
  1200 kb/s) and back up every 5 min — each a restart (a gap in the sound). Fewer bits barely
  lighten a decoder's work (per picture), they only blur; so decode trouble now lowers no further
  than step 2 (720p, 30 fps), the viewer's own light playback does the rest. Network trouble still
  goes all the way down. Her own log is still needed to see why her PC falls behind at 720p30
  (`[pause]`, "was behind; skipped", "can't keep up" lines): 8 GB with a nearly full C: means
  paging is the first suspect.
- Passive window/cursor recorder used for this (WinEvent hooks + polling, changes nothing):
  `scratchpad/watch/fgwatch3.ps1` (not in the repo).

## 0.15.15 (16 Oct): a monitor's sound never leaves it; viewers' cost capped; shaders on screens for everyone

- **"the audio still goes straight to my main desktop when i create a new monitor. i dont want a
  monitor's audio to ever leave that monitor."** (His game was still 0.15.13: he restarted it
  inside the old watcher's 15 s look, so 0.15.14's SoundHome fix never went in; the PC watcher now
  looks every second: `scratchpad/client-swap-fast.ps1`, jar from `scratchpad/ready`.) Two more
  ways found:
  - Store apps (Media Player, Netflix…): the window is ApplicationFrameHost's, the sound the app's
    own process. `windows`/`deskwindows` reported the frame host's pid, so wc-audio captured (and
    turned down) nothing and the film played on the desk. `sound_pid` (wc-vdisplay) and
    `WinGdiBackend.soundPid` (window shares, SoundHome) take the pid of the window inside the
    frame (Windows.UI.Core.CoreWindow). Seen live: wc-audio on ApplicationFrameHost.exe while
    Microsoft.Media.Player.exe played.
  - `MonitorAudio` closed an app's capture the first time a look at the monitor came back without
    it (monitors being added and laid out again) — its volume came back for a moment. Now only
    after 3 looks in a row (~2 s); handing it to another monitor is still at once.
- **Own copies (Sandboxie) per monitor** are on by default already (`Display.ownCopies = true`),
  but Sandboxie-Plus was never set up on his PC (not installed; no "new programs now start in
  box" in his log). That needs his one click (U → Own copies of apps → Set up; Windows asks once).
- **His guest at 15 fps "just by me having my screens up".** His log: streams of 3840×2024@60 (two)
  and 1800×1200@60 to her laptop at once; her PC reported it couldn't keep up, and quality came
  down one step, one stream at a time, minutes later. Each 4K picture is ~12 MB through her game
  (NV12 after the decoder) — ~1.2 GB/s for three. Now: streams at most 1080 lines
  (`StreamPublisher` STREAM_MAX_H, `-Dwindowscraft.streamMaxHeight`; his own walls stay full size,
  they don't use the stream), and a viewer plays only its nearest screen full: the others it sees
  play light (30 pictures a second, at most 720 lines; `StreamSubscriber.playLight`, held 3 s
  before changing). Screens not drawn for 2 s already decoded their sound only (as he asked:
  "if someone doesn't have a monitor on their screen … it just needs to stream audio").
- **"the music was laggy skipping for her."** 18 stream restarts in ~20 min; 9 were "upload shared
  differently" — the viewer count blinked 1 → 2 → 1 as a viewer reconnected, each blink re-split
  the upload and restarted the stream (a moment without sound for everyone watching). A new share
  now has to hold for 30 s first.
- **Shader pack on screens: one setting for everyone, on by default.** `Payloads.ScreensShaded`
  (both ways); the server keeps it (`windowscraft-server.json` "screensShaded", default true),
  sends it on join and to everyone when anyone flips it. Off a WindowsCraft server it's your own
  (now shaded by default). Each side checks the other has the payload first.

## 0.15.14 (16 Oct): sound stays on the wall, Roblox and Steam stay on the monitor, joined monitors after a drag

Four things the owner hit while playing (his game open; nothing tested on his screen):
- **"it keeps playing audio on my main desktop whenever i click on brave on my desktop … if music is
  being streamed on the wall or any audio then it should only go there."** SoundHome made your desk
  a place too: using Brave's desk window moved Brave's sound to your speakers (and away from the
  wall and its viewers). Now a program with a window on an in-game monitor is heard only from the
  wall, also while you use its window on your desk; on several monitors, from where it was last
  used on one. Off every monitor it's normal again. (`SoundHome`, `SoundHomeTest`; the desk-window
  listing every 0.7 s is gone from `MonitorAudio`.) This is the owner's own exception to "only its
  own monitors": the desk Brave's sound goes to the wall, by his ask.
- **"roblox doesn't pop up on the ingame monitor it pops up on my main desktop … this should never
  happen."** Log: Roblox opened on monitor 0 (00:48:50, right), he got up (00:49:04), and 3 s later
  `on_desk_activated` "brought it over to your desk": a window on a monitor coming forward within
  8 s of the desk's shell being in front counted as "you started it from your desk". Roblox brings
  itself forward once loaded, right after its launcher closes and Windows puts the desktop in front.
  Now only a window that came over *from* your desk (it has a HOMES entry: bring_over / Bring here)
  goes back; one that opened on a monitor stays ("…stays (it opened there)" in the log). Self-test
  6b checks it (born on the monitor, own taskbar clicked, it comes forward: stays).
- **"multi-monitor setups don't allow me to move my mouse over to the next monitor unless i'm
  dragging a window" / "that happens only after i've already dragged a window".** Moving to a
  joined monitor holds the cursor on it for SWITCH_REST (1.2 s). During a drag the hold isn't set
  (it makes the window jitter), so the timer's take-back set it as you let go — without NARROWED,
  so when the rest ended the single-monitor clip looked like "an app's own tighter clip" and stayed
  for good. Now that take-back marks NARROWED too, and the group opens again after the rest (not
  mid-drag). Self-test: "after dragging a window across, the cursor still goes on to the joined
  monitor without dragging".
- **"apps like steam don't pop up on the ingame monitor they pop up on my main desktop."** Log:
  sitting at monitor 2, Steam (already running in the tray) showed its window on monitor 0, where
  it was last, and the last fallback in `on_window_shown` ("opened on an in-game monitor by
  itself: moving it to your desk") sent it to the desk: rule 2 (cursor on a monitor → there) had
  passed it over (too small when shown, or open when he sat down). Now, while you sit at a
  monitor, that fallback puts it on your monitor instead; and a window rule 2 passed over only
  for being small is looked at again 0.3 s and 1.2 s later (Steam sizes its window after showing
  it) and comes to your monitor once it has its size. Self-test 6c.
- Server Distant Horizons throttled live from the console (the owner's "distant horizons … still
  makes me lag"): `dh config threading.numberOfThreads 2`, `threading.threadRunTimeRatio 0.5`,
  `generation.maxRequestDistance 320` (were 10 / 1.0 / 4096; saved to its config). It was building
  far terrain on 4.3 cores of the same PC; 1.8 after.
- CI: sandbox-probe flaked twice on timing ("…and opens on monitor A": the move landed after the
  check looked — the check now waits up to 3 s; "sitting at monitor B: the rule moved to B's box":
  read before the rule switched), green on the third run. 4/4 green.
- wc-vdisplay.exe (GNU) and wc-selftest.exe (MSVC, static CRT) rebuilt with the path remaps: neither
  carries the build PC's user folder any more.

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
