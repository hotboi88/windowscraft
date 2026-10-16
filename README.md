# WindowsCraft

Share your Windows screen or any app window onto a black concrete wall in Minecraft. Everyone on
the server who has the mod watches it live, with sound coming from the wall.

Look at the wall, press **U**, pick what to share, then hit **Go Live**.

On Windows a wall (or a **gaming monitor** block, or a **tablet** in your hand) can also be an
**in-game monitor**: a real extra screen for your PC, with its own taskbar and Start menu, that
you sit down at and use with your real mouse and keyboard.

**U anywhere** opens the WindowsCraft menu: all your screens wherever they are — what's open on
them, who else can use them, their size and picture — and the settings.

Built for **Minecraft 1.21.1 + Fabric**, and checked against the **COBBLEVERSE 1.7.42** mod list.

## Install

The same jar goes in three places:

| Where | Why |
|---|---|
| Your Cobbleverse `mods` folder | To share and watch |
| Her Cobbleverse `mods` folder | To watch (and share her own screens) |
| Your **server's** `mods` folder | So you can see each other's screens. Without it, the mod still works, but only for yourself. |

How to find the client `mods` folder:
- **Modrinth App:** instance → ⋮ → *Open folder* → `mods`.
- **CurseForge:** right-click the modpack → *Open Folder* → `mods`.

For an **"Open to LAN"** world instead of a dedicated server, nothing extra is needed: the host's
client acts as the server.

**Everyone needs the same version, the server too** — WindowsCraft adds blocks and items (gaming
monitors and tablets), and Minecraft only lets you join a server whose blocks your game knows.
After that, a newer WindowsCraft on the server is offered to you in chat when you join, and once
you say yes, it updates itself (it's put in place when you close the game).

> **Updating from 0.13 or older:** this one update has to be installed by hand on every PC (and the
> server): the old version can't join a server that has the new blocks, so it can't be offered the
> update either. From then on, updates come by themselves.

Cobbleverse already has Fabric API, the only dependency.

### First time you go live (or first time you watch)

- **FFmpeg download.** The mod downloads FFmpeg once, about 200 MB, into `.minecraft/windowscraft/ffmpeg/`.
  The share dialog shows progress. It then picks your GPU's video encoder automatically: NVIDIA,
  then AMD, then Intel, then a software fallback.
- **Windows Firewall prompt.** It may appear on the **server** PC when the first screen goes live,
  because the server opens TCP port **25585** for the video relay. Click **Allow**.

## Tailscale

She connects to the same address she already uses for Minecraft (your Tailscale IP or MagicDNS
name). The video goes to that same address on port 25585, inside your private Tailscale
network, with no router setup.

If her wall says **"Connecting…"** forever, Windows Firewall on the server PC is blocking 25585.
Allow Java through the firewall, or add an inbound rule for TCP 25585.

## Use it

1. **Build the wall.** Use black concrete, up to 32 × 24 blocks. With **Picture: Auto** (the
   default) the stream covers the whole front of the wall when the shapes nearly match (a 16:9
   screen on 7 × 4 or 16 × 9). When the shapes really differ, it keeps its proportions with black
   bars. **Fill** always stretches edge to edge; **Fit** always keeps proportions.
2. **Press U.** Look at the side of the wall, from up to 48 blocks away, and press **U**.
3. **Choose what to share:**
   - **Applications**: single windows, with live previews.
   - **Screens**: whole monitors, plus a *Test pattern*.
   - **Resolution**: 720p / 1080p / Source.
   - **Frame rate**: 15 / 30 / 60.
   - **Picture**: Auto / Fill / Fit. The mouse pointer is always shared.
   - **Audio**: for an app window, **Game / App / Off**. A window share always sends **only that
     app's sound**.
     - **Game** (default): the app goes quiet on your PC and you hear it from the wall in
       Minecraft, in 3D, the same way everyone else does. Your own volume (🔈, 0–500 %) is on
       the share dialog and in the U menu. Its volume is put back as soon as you
       stop sharing, even if the game crashes.
     - **App**: you hear it straight from the app, as usual.
     - A screen share offers **On / Off** and sends everything except Minecraft, so the other
       person's stream never echoes back.
4. **Go Live** (or double-click a card).

Other controls:
- **Press U on your own live wall** to switch source, change quality, **Stop sharing** or
  **Remove display**. The bottom line of the dialog shows the stream status: encoder, bitrate,
  audio mode, and whether sound is actually coming through.
- **The WindowsCraft menu.** Press **U** anywhere that isn't a wall (or **sneak + U** at an
  in-game monitor). On the left, every screen of yours in this world (a dot shows which are on) and
  other people's. For the one you pick:
  - **Windows** (in-game monitors): what's open on it — **Move** any of it to your desk or another
    in-game monitor, or **Close** it — and **Bring here** for windows on your desk or other monitors.
  - **Who can use it**: **Only me**, **People I pick** (a switch per player) or **Everyone
    except…** (a switch to block someone), plus **Same for all my screens**. Everyone can always
    watch.
  - **Picture**: an in-game monitor's size (**Match the wall** picks the size shaped most like the
    wall, so it fills it without black bars; or any size Windows offers it), and for everyone
    watching: sharpness, pictures a second, shape on the wall, sound.
  - **Turn on / Turn off**, **Share…**, **Stop** — from wherever you are.
  - **⚙ Settings**: pointer speed, performance, shaders, the display driver, updates, controls.
- **Press U on someone else's wall** for listening options, which only affect you:
  - **Sound: 3D, from the wall** (in the world: distance, direction, room echo) or **Direct**
    (the stream's own stereo at full quality, no effects).
  - **Volume**, from 0 to 500 % (above 100 % it's boosted through a limiter, so it gets louder
    without crackling), and **Mute**.
  - **Theater mode**.
- **Theater mode (O).** Look at a live screen and press **O**. The camera floats out of your
  body to the front of the screen, so it fills most of your view with the blocks around it
  still visible. **W/S** or the **mouse wheel** move closer or further, within a range. The
  **mouse** looks around from that spot, and **Space** or **middle-click** turns the view back to
  the screen; a hint at the bottom reminds you whenever you're looking away. Press **O** again
  to fly back. While watching, your character stands still: no walking, jumping, turning,
  breaking or clicking.

### Several screens

- **Different windows on different walls:** just make more walls and go live on each. Each one is
  its own stream, up to 8 per player.
- **The same window on several walls (mirroring):** pick a window that's already live and the
  button says **Mirror**. Every wall shows it, but it is still captured, encoded and downloaded
  once. Its sound plays only from the wall nearest to each listener, so there's no echo. Muting
  one mirrored wall mutes all of them.

The sound is 3D:
- louder when you're close
- left/right as you turn your head
- muffled through walls
- echoing in stone rooms and damped by wool

## In-game monitors (Windows)

Look at a black concrete wall (or a gaming monitor block) and press **U**: a new **in-game
monitor** turns on — a monitor that exists only inside Windows, shown on that wall (and to
everyone on the server, with sound). It's just another monitor: your wallpaper, and Windows' own
taskbar and Start menu. Press **U** again to turn it off. **Sneak + U** opens the WindowsCraft menu
on it (what's open on it, who can use it, its size). Sneak + U on an idle wall shares a window or
one of your real screens instead, as before.

- **Sit-down view, pixel for pixel**: sit down at your own in-game monitor (**O**) and, once
  you're seated, it's drawn flat on your screen like a real monitor — no 3D wall, shaders or
  compression in between, about one frame of delay — right where the monitor is, with the room
  around it (no black bars). Fully zoomed in, its edges touch the edges of what you can see of
  the game window, never further: nothing is cropped, and it never goes under your real Windows
  taskbar (a fullscreen game stays above the taskbar while you sit). When it fits 1:1 it's exactly
  pixel for pixel. **Ctrl+Alt + scroll** out and it stays flat and sharp, just smaller; look around with
  Ctrl+Alt to see the room (a hand resting on the mouse doesn't turn the view); **Esc** gets you up.
  The next time you sit down (or watch a screen in theater mode) you're as close as you were when
  you left. Someone else's screen you sit at or watch in theater mode is shown flat and sharp the
  same way, so it looks and feels the same for everyone. Joined monitors: moving onto the next one switches
  the view to it. Turn it off in the menu (In-game monitors → "Sit-down view"). Standing in the
  room, the monitor you're looking at gets its full-resolution picture too (one at a time).
- **Apps that only run once** (Discord, WhatsApp, Calculator… any app that won't open a second
  window): already open on your desk or another monitor, opening it from a monitor brings its
  window over — in the middle of the monitor, at a modest size, in one move. And the other way:
  open it from your own Start menu or taskbar while it's on a monitor, and it comes back to your
  desk the same way.
- **Store apps** (Calculator, Settings…) open on the monitor you open them from, like any app.
- **Each monitor's sound is its own — never mixed**: a monitor plays only what's on it, never
  another monitor's sound or your desk's. Windows only knows sound per program, so:
  - **Own copies of apps** (U → the monitor → "Own copies of apps"; a one-time setup of the free
    Sandboxie-Plus, which WindowsCraft downloads, checks and installs hidden — Windows asks for
    permission once, and it never shows itself): every app you start while sitting at a monitor
    (or pointing at it) runs as that monitor's own copy — its own program, so its own sound, and
    all its windows and tabs play from that monitor, all at once. Its logins and data are its own
    and kept (sign in to a site once on that monitor, it stays), and apps that only run once can
    run there too ("Open another copy here"). The apps on your desk are never touched. Discord,
    WhatsApp, game launchers, games with anti-cheat, Java and Minecraft, and anything you add to
    "Always run normally" are never copies (Discord and WhatsApp come over instead). Getting up,
    turning it off, closing the game or the game crashing leave nothing behind that would affect
    your desk's apps. "Reset this monitor's apps" wipes its logins and data.
  - Without it, an app open in several places plays from wherever you last used it (never from two
    places), and a browser open both on your desk and on a monitor can get a separate one there
    ("Own Brave" in the monitor manager — its own profile, so you sign in there once).
- **File Explorer and Task Manager** open on the monitor you open them from, like any app.
  (Task Manager normally runs as administrator, which Windows doesn't let other programs move;
  while in-game monitors are on it starts with your normal rights instead — no permission
  prompt — and goes back to normal when they turn off.)
- **Its own taskbar**: while an in-game monitor is on, Windows' own setting "show taskbar apps on
  the taskbar where the window is open" is on, so its taskbar only knows the apps on it (Brave
  open on your desk doesn't show as open there, and hovering shows no previews of it) and your
  main taskbar doesn't list what's on the monitor. Your own setting comes back when the last
  monitor turns off (after a crash: the next time the game starts).
- **Apps open where you start them**: a new window opens where your real cursor was when you
  opened it. Sitting at a monitor (or pointing at it), anything you open goes onto it (an app from
  its Start menu, a new Brave window) at its own normal size, like on your desk. From your own
  taskbar, Start menu or desktop it opens on your desk — even an app that also has windows on a
  monitor, or one that remembers it was last on one (that one is moved back to your desk). While
  you play, a window opened by something on a monitor (a person using it clicking a link) stays
  with that monitor. An app that only ever runs once (Discord, Spotify, WhatsApp…) and is
  open on your desk already brings its window over instead (it can't open a second one); it goes
  back to its spot on your desk when the monitor turns off, and isn't closed. (Windows 11 keeps pinned taskbar icons on your main
  taskbar in this mode, so start apps from the monitor's Start button.)
- **Turning it off closes what lives on it** (on your desk first, so a "save changes?" question is
  where you can answer it); windows you brought over from your desk just go back. A program started
  while the monitor was on that keeps running with no window at all (hidden in the system tray)
  is ended a few seconds later — never one you had running before.
- **Alt+Tab while seated** only switches between the windows on that monitor, and the **Windows
  key** opens that monitor's own Start menu (Windows key + another key: Windows' shortcuts as usual).
- **Games and full-screen video**: a game that picks its own display (most have a "Display" or
  "Monitor" option in their video settings) needs that set to the in-game monitor once. A
  full-screen video or game on the monitor keeps the whole screen when you press Esc to get up:
  the monitor's taskbar is hidden the moment Windows tries to show it over it. **Ctrl+Alt + Space**
  while seated only looks straight again — the app never sees it. Plain Alt is the app's.
- **Sit down (O) and it's your PC**: your real mouse and keyboard work whatever is on the monitor —
  any app, any game — and the wall shows your real pointer in its real shape (text cursor, hand
  on links…), drawn live from your mouse so it never lags. **Esc** gets up; **Ctrl + left Alt** + mouse looks around, Ctrl+Alt + scroll zooms,
  Ctrl+Alt + Space looks straight again (plain Alt goes to the app: image editors hold it). If the game ever stops answering, or you switch to something
  else (Task Manager, Ctrl+Alt+Del), your mouse and keyboard come back by themselves. A game's own
  hold on the cursor (dragging Roblox's camera) is left alone.
- **Alt-pointing works like sitting**: hold Alt and look at your monitor, and your real cursor is
  there — real hovering, clicks and scrolling, your real cursor on the wall — while you keep
  walking and looking around. The app you click comes to the front, as on a real desktop, and
  your keys keep going to the game (walking, sneaking) until you let go of Alt; the game's title
  bar doesn't flicker, and your mouse moving never jiggles the cursor off what you're hovering.
- **Other players you allow each get their own pointer** (drawn as a Windows arrow): their
  pointing, clicks, scrolling and typing go straight to the window under their pointer, as the
  messages Windows itself would send it — never through your real cursor or keyboard. Each person
  has their own input (their own window, held buttons and keys), so several people at once never
  let go of each other's clicks or type into each other's window, and the moment you use that app
  yourself, nothing of theirs (a held Shift) mixes into your typing. You keep playing normally, and
  you can always sit down at your own monitor. Anything they hold is let go when they stop. (A few
  programs only listen to the real mouse and keyboard — most games, apps running as
  administrator — those only you can use.)
- **Its size**: 1920 × 1080 to start with; the menu's **Picture** tab changes it while it's on
  (its apps stay open) and it's remembered for that wall. Windows' **Scale** follows the size
  (1440p 125 %, 4K 200 %…), so text and the cursor stay the same size — a higher resolution is
  sharper, never tinier. On your own PC an in-game monitor is drawn at its full resolution and up
  to 60 pictures a second, whatever the stream to others is set to.
- **Each monitor is a station of its own**: monitors that aren't joined never touch each other or
  your own screens on the Windows desktop (only at a corner), so a window or the cursor can't
  slip onto one by accident. **Joined** gaming monitors (see below) sit edge to edge: drag a
  window from one to the next, and while you sit at one the cursor goes on to the ones joined to
  it (your view glides along).
- **Monitors come and go freely**: a new one is always new (its own size, scale and spot — never
  an old one's), left-overs from a crash are removed first, and if the driver is still busy from
  the last one it waits a moment and tries again.
- **Nothing is left behind**: turning it off, breaking its whole wall, leaving the world or closing
  the game removes the monitor, and everything that was on it goes back to your desk. If the game
  crashes, the driver removes the monitors within a second by itself.
- **Sound**: each app is heard from the monitor it's on. Windows gives a program one sound, not
  each window: the same browser on two monitors plays from the one you used it on last. For a
  browser of its own on a monitor (its own sound, its own sign-ins, closed with the monitor), use
  **Own Brave / Own Chrome / Own Edge…** next to it on the monitor's Windows tab.
- **Setup, once**: the first U offers to install the free, signed *Parsec Virtual Display Driver*
  (downloaded from Parsec, checked byte for byte); Windows asks for permission. Settings shows its
  state and has **Remove driver**. Adding or removing a monitor may make your screens blink for a
  moment, like plugging in a monitor.



## Gaming monitors and tablets

**Gaming monitors** are blocks in 32 colours (creative tab *WindowsCraft*). Place one and it turns
to face you, in any of 8 directions — straight or diagonal. Look at it and press **U**: it's an
in-game monitor, like a wall (or **sneak + U** to share a window on it). Everything works the same:
sit down with **O**, Alt-point at it, let others use it, everyone sees it. Breaking it turns it off.

**Multi-monitor setups**: place a monitor against the side, top or bottom of another one and it
**joins** it — no stand, hanging edge to edge off the other one, turned to face where you stand
as you place it (a side one angles in toward you, one above tilts down). **Sneak** while placing
to keep it flat, in line. Join as many as you like, in any direction. Joined monitors are joined
in Windows too: windows drag across them, the cursor moves on from one to the next.

**Tablets** (32 colours too) are your own hand-held in-game monitor:
- **Holding one** turns your tablet on (one per person, whichever tablet you hold).
  **Right-click** holds it up, filling your screen with its frame around it: your real
  mouse and keyboard work it, like sitting at a monitor. **Esc** puts it down; **hold Ctrl+Alt** to look
  around while holding it up; **Ctrl+Alt + scroll** makes it smaller or bigger. Its picture has the
  shape of its size (the menu's Picture tab), drawn sharp, with your real cursor.
- Holding your tablet, the screens around you aren't yours to point at: your clicks and Alt are
  the tablet's, and nobody's screen thinks you're using it.
- Put down, it keeps going **in your hand** — a video keeps playing, its sound comes from you.
  You hold it like a map: in one hand, or in both when the other hand is free (look down to bring
  it up closer). Other players see it like any small tool in your hand, screen up, its back below.
- **Sneak + right-click** turns it off (its apps close, like any in-game monitor); it stays off
  until you right-click again.
- Only you see what's on it. It's in the U menu as *Your tablet* (its windows and size).

Crafting (survival):
- Black gaming monitor: iron ingot, black stained glass pane, iron ingot / iron ingot, redstone,
  iron ingot / _, iron ingot, _.
- Black tablet: iron ingot, black stained glass pane, iron ingot / iron ingot, redstone, iron ingot.
- Any colour: a monitor (or tablet) + a dye — or two dyes for the in-between shades (e.g. orange +
  yellow = amber).

## Sharing a window or screen

Look at a black concrete wall and press **sneak + U** (just **U** when in-game monitors aren't
available): pick a window (**Applications**) or a whole monitor (**Screens**), choose quality,
**Go Live**. Everyone on the server with WindowsCraft sees it. U on your wall again changes or
stops it.

- **Pointing while walking around**: hold **Alt** and the screen's pointer follows your view;
  Alt + click / Alt + scroll use the shared window. A plain click on the wall does nothing (it
  never mines it); **sneak** to mine or build.
- **Theater mode (O)** is the computer chair: you sit where the screen fills your view, the mouse
  moves the pointer exactly like your PC's cursor, every key goes to the app (none to Minecraft or
  other mods), **Ctrl+Alt + mouse** looks around, **Ctrl+Alt + scroll** zooms, **Ctrl+Alt + Space** recenters,
  **Esc** gets up (Shift+Esc sends Esc to the app).
- **The pointer only shows** while someone points at the screen with Alt or sits at it — so a
  video never has a cursor wandering over it. Everyone watching sees it then. Sitting, it hides
  after a couple of seconds without moving the mouse, and comes back as soon as you move it.
- **Who can use it — per screen.** In the U menu → **Who can use it**: **Only me**, **People I
  pick** or **Everyone except…**. People who can use it get the very same controls on that screen
  (Alt to point, O to sit down). Everyone else can only watch. The server only passes input on from players you
  allowed, and your game checks again. Take it back any time: anything they were holding is let go.
  You see "… is using your screen" when someone starts, and the list says who's using it now.
  The choice is saved with the wall.
- **While you use a window, it steps just off your monitor**: Chrome-based apps (Brave, Chrome,
  Discord…) ignore hover, scrolling and some clicks on their pages wherever another window
  (Minecraft) covers them. So only while you're using it (Alt-pointing, sitting at it, or a player
  you allowed is), the shared window sits just past the edge of your monitor at its own size. It
  stays on your taskbar, and goes back exactly where it was 2.5 seconds after you stop, when you
  Alt+Tab, or when you stop sharing (also after a crash, next time the game starts).
- **Screens follow their wall**: take blocks away and the screen shrinks to the rectangle that's
  left (still live); build the wall out around it and it grows; it's only removed when none of
  its blocks are left. Walking away never counts: a wall is only judged while every chunk it's in is
  loaded and well inside your view distance, and only when it stays broken for a moment. Other players' games never draw a screen over blocks that are gone.

Input reaches apps as the window messages Windows would send them (`wc-input.exe`), so this suits
ordinary apps — browsers, Discord, Spotify, editors. Apps built on WinUI (like the new WhatsApp),
games that read the mouse and keyboard straight from the hardware (like Roblox), and apps running
as administrator can be shared and watched, but not controlled.

## Shaders

With a shader pack (Iris), screens are always drawn after the shader pack's final pass, so they
are sharp and their colours are exactly what was shared: no tinting, anti-aliasing smear, bloom,
fog or motion blur. Blocks and players in front of a screen still cover it. A screen sits 1/32 of a
block off its wall (like a thin TV panel), so the concrete behind can't flicker through it at
steep angles — shader packs with anti-aliasing (TAA) shift the world by a fraction of a pixel every
frame, which used to be enough to let it through. Tested with
Cobbleverse's Sodium 0.8.12 + Iris 1.8.14 and Complementary Reimagined.

## Performance

- **Direct GPU sharing (on by default, Windows).** Your own wall reads the shared window straight
  from graphics memory: Windows Graphics Capture → Direct3D textures → imported into OpenGL as
  external memory (`GL_EXT_memory_object_win32`). No copies through the CPU. Before it's used in
  the game, the exact steps run once in a throwaway helper (`wc-capture --selftest`); if that
  fails, or the game ever closes while it's starting, it stays off and the wall uses the normal
  route. U menu → ⚙ Settings shows its state (and can switch it off). After this update it tests
  again once (it now also tries the memory sizes some drivers expect).
- **The stream to other players** is captured, scaled and encoded on the graphics card
  (Windows Graphics Capture → GPU scaling → NVENC/AMF/Quick Sync).
- **Live-video delivery to viewers (UDP)**: the server sends each viewer the stream the way
  broadcast links do (the idea behind SRT): numbered packets at a steady pace; a lost one is asked
  for again at once and resent while it can still be used, and one that arrives too late is skipped
  instead of holding everything behind it up. Over a long, slightly lossy path (a Tailscale link
  across the country) TCP slows to a crawl after every loss and the video backs up; tested over a
  simulated 100 ms link, this kept full 1080p60 with nothing skipped and ~0.1–0.4 s delay even at
  3 % packet loss (TCP fell apart at 0.05 %). If UDP can't get through (a firewall), the viewer
  falls back to TCP by itself. Needs 0.13+ on the server and the viewer's game.
- **It fits everyone's connection**: the stream steps down by itself — 30 fps, less data, then
  720p — instead of backing up (which made the picture stutter and the sound fall further and
  further behind) when the connection to the server can't take it, when this PC can't encode it in
  time, or when someone watching can't keep up: the server notices a viewer falling behind (their
  internet, or the way from the server to them — e.g. a long Tailscale link) and tells your game,
  and a viewer whose PC can't play it in time says so too (and plays every other picture itself).
  After five calm minutes it tries one step better again. The report shows the step and why.
  Hearing about viewers needs WindowsCraft 0.11.5+ on the server and on the viewer's game.
- **Uploading frames to walls** (the normal route, and other players' screens): frames are copied
  into a GPU buffer on a background thread, and the game's render thread only starts a transfer.
- **Performance settings** (U menu → ⚙ Settings, all on by default):
  - *Off-screen pause* — skip the per-frame work whenever the wall isn't actually on your screen.
  - *Distance frame-rate limit* — slow the picture down when you're far away (at most 15 a second).
  None of them slows down the stream other players watch.
- **No see-through specks**: whatever transparency an app leaves in its pixels is ignored; screens
  are always opaque.

### When your PC or the connection is busy

- **Screens nobody is watching** don't send anything (no encoding, no upload); they start again
  within a second or two when someone looks at them. Other players only download the screens in
  front of them or close by.
- **Your upload is shared** among the screens people are watching (10 Mbit/s in all by default,
  `uploadKbps` in `config/windowscraft/settings.json`), so several monitors at once don't flood
  your connection — and Minecraft itself stays smooth for everyone.
- **Busy guard**: if the game world starts lagging (the server can't keep 20 ticks a second) or
  your frame rate drops low while your screens are being sent, they send lighter pictures by
  themselves, and go back up once things are calm.
- **A lost piece of the picture** costs a short glitch that clears within a second, not a frozen
  picture. Tested over imitated home connections: on a good or typical connection a film plays
  without pauses; on a connection barely faster than the stream, the stream steps down.

## Good to know

- **Sound:** playback keeps about 120 ms in reserve (growing to at most 320 ms if sound keeps
  arriving late, e.g. in a heavy modpack), so game hitches and network bursts can't empty it.
  Samples pass through untouched; clock drift is corrected rarely with a 4 ms crossfade. Boosts
  above 100 % go through a 2 ms lookahead limiter, and 3D sound glides smoothly as you turn, so
  neither adds clicks. Any gap in the stream fades out and in instead of cutting.
- **Walls and distance:** every block between you and a screen damps its sound by what it's made
  of (stone about 9 dB, glass and leaves very little); a thick wall or a hillside silences it, and
  screens fade out completely between 48 and 64 blocks away, whatever the volume boost. ("Direct"
  sound, from the U menu on someone else's screen, skips all of this on purpose.)
- **Something wrong?** Type **`/windowscraft report`** in chat. It saves one zip on your Desktop
  with the recent game logs, crash files, a system summary and — if the mod heard any clicks in
  screen sound — short sound samples of them. Send that file along with what happened.
- **Measuring (off unless you start it):** **`/windowscraft measure start 60`** measures for a
  minute what your screens cost and how long each step takes (capture, encoding, the relay, the
  network, decoding, drawing; your frame rate and 1 % lows; memory, CPU and graphics card), then
  saves a report in `windowscraft/measurements/` in the game folder (`stop` ends it early,
  `status` says if it's on). On a dedicated server, `windowscraft-server measure start 60` in the
  server console does the same for the relay. Reports hold numbers only — no window titles or names.
- **Delay:** usually about **0.3 s** behind the real screen. If a PC is momentarily too busy,
  the viewer skips ahead rather than falling behind.
- **Bandwidth:** each screen is encoded once on the sharer's PC, about 3 Mbit/s at 720p30 and up
  to 10 Mbit/s at 1080p60. Viewers only download screens within 96 blocks, at most the 6 nearest.
- **Windows keep updating** behind Minecraft, even when they're completely covered (by Minecraft
  in fullscreen or any maximized window). Browsers, Discord and other Chromium/Electron apps
  normally stop drawing covered windows; the mod keeps them drawing while you share. Minimized
  windows pause.
- **Black windows:** some games and DRM-protected video capture as black. Share the screen
  instead.
- **Don't share the monitor Minecraft is on.** You'd see Minecraft inside Minecraft.
- **Privacy:** walls are remembered per server, but sharing never restarts by itself when you
  rejoin.

## Server settings

`config/windowscraft-server.json` (created on first start):

```json
{"relayPort": 25585, "relayEnabled": true}
```

The mod adds the gaming monitor blocks and tablets (no commands), so the server and every player
need the same WindowsCraft.
Limits:
- 8 live screens per player.
- Only a screen's owner can stream to it.
- Only players currently on the server can use the relay.

The relay uses the port over **TCP and UDP** (25585 by default): if your server is reached through
a router's port forward, forward both (with Tailscale there's nothing to do). Viewers fall back to
TCP when UDP doesn't get through.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Only you can see this — the server doesn't have WindowsCraft" | Put the jar in the server's `mods` folder too. |
| Her wall: "Connecting…" forever | Firewall on the server PC: allow TCP 25585. |
| "Not streamed — nobody else can join this world" | It's a singleplayer world. Use "Open to LAN" or a server. |
| Status says "software (x264)" | No usable GPU encoder was found, or the GPU was already running as many video encodes as it allows (consumer NVIDIA cards allow a handful at once, shared with OBS, Discord and others). Streaming still works, using more CPU. Updating the graphics driver can help. |
| "system audio (incl. Minecraft — older Windows)" | Per-app audio capture is officially for Windows 11 / Windows 10 build 20348+ (it often works on older Windows 10 too). Where it doesn't, the mod falls back to all system audio, which includes Minecraft's own sound, so wear headphones or mute remote screens to avoid echo. |
| A window shows black | That app blocks background capture. Share its screen instead. |
| An app is very quiet after a crash | The mod puts the volume back by itself the next time the game runs while that app is open. You can also fix it by hand in Windows *Settings → Sound → Volume mixer*. |
| Someone else's screen freezes for a moment now and then | Pieces of the picture got lost on the way (Wi-Fi, a busy connection). Their screen holds the last good picture until the next clean one (well under a second) instead of smearing, and if it keeps happening their game asks yours to send a lighter stream. `/windowscraft report` on their side shows how many pieces were lost. |
| U on a wall does nothing / in-game monitors won't turn on | The display driver got stuck (it can happen after monitors are turned on and off many times quickly). WindowsCraft notices it and offers **Repair driver**, which restarts the driver (Windows asks for permission). Restarting the PC fixes it too. |
| "Windows dropped an in-game monitor - turning it back on…" | Windows let one of the monitors go by itself; it comes back on its own right away. If it keeps happening you get a message instead, and U on it tries again. |
| Something about in-game monitors misbehaves on your PC | Close Minecraft and run `Windows self-test\Run self-test.bat` from the WindowsCraft zip: in a couple of minutes it checks every part of in-game monitors on your own PC (your screens blink and the mouse moves meanwhile) and puts a report on your desktop. |
| A yellow border appears around a shared window (Windows 10) | Windows 10 draws it during GPU capture, and it can't be turned off there. Windows 11 doesn't show it. |

Logs are in `logs/latest.log`, in lines tagged `WindowsCraft`, `[ffmpeg-enc]`, `[ffmpeg-dec]` and `[wc-audio]`.

## Build from source

Needs JDK 21. Building the audio helper needs Rust with the `x86_64-pc-windows-gnu` target and MinGW.

```
gradle build                  # → build/libs/windowscraft-<version>.jar (includes wc-audio.exe)
gradle test                   # unit tests: geometry, 3D audio chain, MPEG-TS keyframe splitter
(cd native/wc-audio && cargo build --release --target x86_64-pc-windows-gnu)
cp native/wc-audio/target/x86_64-pc-windows-gnu/release/wc-audio.exe src/main/resources/windowscraft/natives/
```

### Windows self-test

`native/wc-audio/src/bin/wc-selftest.rs` drives the real helpers with real virtual monitors (the
Parsec driver) and checks what Windows actually does — monitor layout, where windows open, the
cursor, clicks and typing, several guests at once, Esc and Start, full-screen apps, driver
add/remove cycles, crash recovery, scale per resolution. `.github/workflows/windows-selftest.yml`
runs it (three rounds) on a GitHub Windows machine on every push; the report and screenshots are
attached to the run. On a PC: `wc-selftest.exe --rounds 1 --report report.txt --shots shots`
(Minecraft closed).

### Automated in-game tests

- `gradle runAutotest`: single player. It builds walls, opens the dialog with the key, clicks
  cards, goes live, breaks a wall and takes screenshots. Needs `run/saves/autotest`, which you can
  generate with `gradle runWorldgen`.
- `gradle runMpServer`, then `runMpSharer` and `runMpViewer`: a real dedicated server and two
  clients.
  - "Alice" streams the test pattern.
  - "Bob" must see it on the wall, receive the audio, and measure the delay (from a timestamp strip
    in the picture) and the A/V offset (from a once-a-second flash plus beep).

For headless machines, add `-Pxdisplay=:99` (Xvfb).

`tools/wincapture-probe` runs the Windows capture code outside the game.

## License

LGPL-3.0-or-later. The 3D audio engine is ported from **Dream Displays** by Arno Dölinger
(LGPL-3.0). See `NOTICE.md`.
