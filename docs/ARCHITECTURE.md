# WindowsCraft architecture (v0.3)

## 1. Overview

```
 SHARER'S PC                                 SERVER (dedicated or "Open to LAN")          VIEWER'S PC
 ───────────                                 ──────────────────────────────────          ───────────
 capture (GDI)  ──frames──▶ wall texture     ServerHub: who is live where        DisplayState
     │                                         (Minecraft custom packets)  ─────────────▶ RemoteDisplays
     ├──tap──▶ StreamPublisher                                                              │ (≤96 blocks, 6 nearest)
     │          raw BGRA ─loopback TCP─▶ FFmpeg ──MPEG-TS──▶ RelayServer :25585 ──────────▶ StreamSubscriber
 wc-audio.exe ─PCM─▶ (real-time clock)   (NVENC/AMF/QSV/x264,   (fan-out, no re-encode,        │ queue ▶ FFmpeg decode
 (WASAPI app loopback)                    AAC, wall-clock PTS)   skips slow viewers to          │   ├─BGRA─▶ wall texture
                                                                 the next keyframe)             │   └─PCM──▶ 3D audio chain ▶ speakers
```

There are three planes:
- **Control.** Tiny Fabric custom payloads over the Minecraft connection say who is live where,
  and hand out relay tokens.
- **Media.** One TCP stream per screen goes from its owner to the relay, and one from the relay to
  each viewer. Video never touches the Minecraft connection.
- **Local.** Capture, textures, UI and spatial audio stay on each client.

## 2. Components

| Package | Responsibility |
|---|---|
| `capture`, `capture.win` | GDI capture via JNA (monitors via BitBlt; windows via PrintWindow with a 3-method fallback chain); test pattern (with A/V-sync flash + machine-readable timing strip). |
| `display`, `render`, `ui` | Wall detection, displays, persistence, mipmapped BGRA textures, the share dialog. |
| `net` | `Payloads` (Hello, Announce, AnnounceAck, Remove, DisplayState, DisplayGone), `DisplayInfo`, `ServerHub` (server-side registry, limits, relay lifecycle). |
| `relay` | `RelayServer` (tokens, owner-only publish, connection limits, per-viewer queues with keyframe skipping), `TsSplitter` (MPEG-TS packet/PAT/PMT/keyframe parsing). |
| `client` | `ClientNet` (protocol state), `RemoteDisplays` (other players' screens; watch-by-distance; feeds the acoustics engine). |
| `stream` | `Ffmpeg` (locate/download/verify, encoder probe), `StreamPublisher`, `StreamSubscriber`. |
| `audio` | Java port of the Dream Displays acoustics engine (`dsp/*`, `AudioRenderChain`, `AcousticsEngine`, `AcousticsProbe`); `AudioCapture` (test tone / `wc-audio.exe`); `SpatialAudioPlayer`. |
| `native/wc-audio` | Rust helper: WASAPI application loopback (include one app's process tree, or exclude Minecraft's), falling back to system loopback. |

## 3. Protocol

1. **Join.** On join, the server sends `Hello(protocol, relayPort, token, streaming)`. `streaming`
   is false for a singleplayer world nobody else can join.
2. **Go Live.** The owner's client sends `Announce(DisplayInfo)`, where `DisplayInfo` carries:
   - the display id, dimension, face and wall bounds
   - the title
   - a random 128-bit stream id
   - the encoded size and fps

   It is re-sent every 10 s as a heartbeat. The server drops screens it hasn't heard about for 30 s.
3. **Server checks.** The server validates the announce:
   - flat wall, at most 32 × 24, sizes ≤ 7680 × 4320, fps 1–120
   - at most 8 screens per player
   - a stream id may only be used by one player (one player may put it on several walls; see §3a)

   It registers the screen, replies `AnnounceAck(relayOpen)` and broadcasts `DisplayState` to
   everyone else who has the mod.
4. **Relay handshake.** `WCR1 PUB|SUB <token> <streamId>\n` is answered with `OK` or
   `ERR …`. PUB is only accepted from the screen's owner. SUB is accepted from any player on the
   server.
5. **Stop or leave.** `Remove`, a disconnect or a missed heartbeat sends `DisplayGone`, and the
   relay closes that stream. When a player leaves, all of their relay connections are closed.

## 3a. Mirroring and encoder fallback

- **Sharer.** A `Share` is one capture, one wall texture and one `StreamPublisher`. Choosing a
  source that is already live on another wall attaches the new wall to the existing share. The
  publisher announces one `DisplayInfo` per wall (its `Target`s), all with the same stream id;
  each wall keeps its own picture-fit setting. The capture ends when the share's last wall stops.
- **Viewer.** `RemoteDisplays` groups walls by stream id into a `Watch`: one subscriber, one
  decoder, one texture (uploaded once per frame) and one sound source. Every tick the sound
  source is placed on the member wall nearest the listener. Distance limits and the 6-stream cap
  count streams, not walls. Mute is per stream.
- **Encoder fallback.** If a hardware encoder produces no output at all (typically because the
  GPU's limit on concurrent encode sessions is used up), that stream restarts on libx264.

## 3b. Capture and upload paths (v0.3)

**GPU path.** Used when streaming on Windows with a hardware encoder. There's no `-f lavfi`
input, because lavfi can't carry hardware frames. Instead the capture is a filter-graph source:

```
gfxcapture=hwnd|hmonitor=…:width=W:height=H:resize_mode=scale_aspect   (Windows Graphics Capture, D3D11 BGRA)
  → setpts=RTCTIME/(TB*1000000)        wall-clock stamps, matching the audio input (-copyts)
  → fps=N → split ─ scale_d3d11=format=nv12 [→ hwmap qsv] → h264_nvenc / h264_amf / h264_qsv
                  └ hwdownload,format=bgra → rawvideo over loopback TCP → your own wall
```

- **CPU capture while on the GPU path.** It drops to 1 frame/s, only to notice a window being
  closed or resized.
- **Falling back.** The share falls back to the CPU path for its lifetime if any of these happen:
  - FFmpeg exits before connecting;
  - the pipeline fails before its first output byte;
  - no frame arrives within 10 s.
- **Still windows.** Windows Graphics Capture sends nothing while a window doesn't change.
  Viewers therefore treat a stream whose data, such as audio, keeps arriving as alive.

**CPU path.** GDI capture (BitBlt / PrintWindow) → raw BGRA over loopback TCP → FFmpeg scale,
then any encoder.

**Direct GPU sharing (`GpuShare`, `GpuShareTexture`, `wc-capture.exe`).**
- **Helper.** It creates a D3D11 device on the graphics card Minecraft uses (matched by GL
  vendor, then by most video memory; the LUID is passed in). It captures with Windows Graphics
  Capture, copies each frame into one of three `D3D11_RESOURCE_MISC_SHARED` textures, and prints
  `slots w h h0 h1 h2` and `frame k`.
- **Minecraft side.** Each slot is opened on our own D3D11 device (JNA COM calls) and registered
  with `wglDXRegisterObjectNV`. Per frame: lock, `glCopyImageSubData` into the wall's mipmapped
  texture, unlock, generate mipmaps.
- **Effect on the CPU capture and the stream.** While this feeds the wall, the CPU capture rests
  (1 frame/s, only to notice a closed or resized window), and FFmpeg's GPU pipeline drops its
  preview output.
- **Fallback.** Any failure (no extension, different graphics card, helper exit) falls back to
  FFmpeg's preview or the CPU path, and the stream pipeline restarts with the preview.

**Wall textures (`StreamingTexture`).**
- Three slots in a persistently mapped buffer (GL 4.4 `ARB_buffer_storage`), guarded by fences.
- A copier thread writes each frame into a free slot. The render thread starts an asynchronous
  PBO→texture transfer into the back texture, then swaps.
- Without buffer storage, it uses plain `glTexSubImage2D`, decided per screen.

**Covered windows (`KeepRendering`).** Chromium stops painting windows that are completely
covered, unless a `TaskListThumbnailWnd` (the taskbar preview) is visible. While an app window is
shared, an invisible, click-through, off-screen 1×1 window of that class is shown, and
re-announced every 2 s.

## 3c. "Hear it in the game" (`wc-audio --duck`)

**What happens.** Windows applies an app's volume-mixer level *before* the application-loopback
tap, and the audio engine works in float. So the helper:
1. turns every playing session of the app's process tree, on every output device, down to
   0.001 (−60 dB);
2. captures in float32 and multiplies by 1000.

The sharer then hears the app only from the wall (`LocalSound`, the same 3D chain as viewers).
The stream is bit-for-bit as loud as before.

**Safety.** The gain is 1000×, so it's only applied once every playing session reads back at
0.001 and a 200 ms hold has passed, and it fades in over 50 ms. A block louder than a turned-down
app could produce is silenced and triggers an immediate re-scan. If that keeps happening for 2 s,
the share falls back to "plays from the app".

**Restoring volumes.**
- **When:** a session gets its volume back when it stops playing, when the helper exits (stdin
  closed, or the game process dies, watched via its handle), and on expiry.
- **Records:** originals are written to `windowscraft/volumes/ducked-<id>.txt` *before* ducking.
  A record is cleared only after the restored level reads back correctly.
- **Leftovers:** files left by a crash are replayed with `wc-audio --restore` at startup and
  every minute. An app that is turned down again also recovers its recorded original.

## 4. Latency design (measured: median 0.3 s)

Every buffer is either bounded or skippable:

- **Sharer, video in.** Frames go to FFmpeg at a steady frame rate, repeating the last frame if
  capture is slower. If FFmpeg falls behind by more than 1 s, frames are skipped rather than
  burst.
- **Sharer, audio in.** Audio is fed on a real-time clock, with silence filling any gaps.
- **Sharer, timestamps.** Both inputs are stamped with the wall clock
  (`-use_wallclock_as_timestamps`), so audio and video share one timeline.
- **Sharer, muxer.** `-max_interleave_delta 0.1s` stops the muxer from holding one stream back
  for the other. FFmpeg's default allows up to 10 s.
- **Encoder.** Low-latency presets throughout:
  - NVENC: `p1` + `ull`
  - AMF: `ultralowlatency`
  - QSV: `veryfast`, no lookahead
  - x264: `zerolatency`

  CBR, no B-frames, and a 1 s keyframe interval.
- **Relay.** Each viewer has a queue (≤ 12 MB). A viewer that is a whole keyframe interval
  behind when the next keyframe arrives is cut forward to it. New viewers start on a keyframe,
  together with the PAT/PMT tables that precede it.
- **Viewer, before the decoder.** Received data waits in an app-level queue. If more than about
  1 s of it is waiting, the queue is trimmed to the newest keyframe. The decoder isn't restarted;
  catching up costs one skipped moment.
- **Viewer, audio out.** Audio is never paced by sleeping. The decoder already delivers in real
  time, and reading slower than that would stall FFmpeg and freeze the picture. That bug was
  found and fixed through measurement; see §6. The output line holds at most 125 ms; anything
  more is dropped.
- **Watchdog.** No picture for 6 s rebuilds the viewer pipeline.

Raw frames travel over loopback TCP, not process pipes. Windows anonymous pipes can't carry
1080p60 BGRA (≈ 500 MB/s).

## 5. Spatial audio (ported from Dream Displays)

Each screen is a planar area source with two emitters, carrying the left and right channels. The
chain:
- **distance roll-off:** inverse-distance, clamped to the screen's own size
- **directivity:** quieter behind the screen
- **parametric binaural:** Woodworth ITD, ILD and a head-shadow low-pass
- **occlusion:** a low-pass plus gain, from voxel raytraces through the blocks between you and the
  screen, using per-material weights
- **air absorption** over distance
- **room reverb:** Freeverb-style, from 24 rays sprayed around the screen, using material
  reflectivity
- **loudness normalisation** (EBU R128-style) and a limiter

The probe runs 5×/s per watched screen. Unit tests cover:
- the plain-gain bypass
- range safety
- normalisation
- left/right placement
- distance roll-off and wall muffling

## 6. Measurements (this sandbox: 2 CPU cores, software rendering, software x264)

**Setup.** Real dedicated server + sharer client + viewer client, 720p30 test pattern, 2 minutes.

| | First run | After fixes |
|---|---|---|
| Delay, median | 0.65 s, drifting to 15 s | **0.30–0.37 s** |
| Delay, 90th percentile | 3.5 s | **0.5–0.6 s** |
| Delay, worst | 15 s | **0.7–1.0 s** |
| A/V offset (avg of ~100 beeps) | — | **−70 to −90 ms** (sound slightly early) |

**How the drift was found.** An independent probe viewer, a script reading the timing strip
straight from the relay, stayed at 0.3 s while the in-game viewer drifted. That proved the backlog
was inside the viewer. The cause: the no-audio-device fallback paced playback with `sleep()`,
which runs slow under load.

**What these numbers include.**
- The decode count and audio include the full in-game texture and DSP path.
- Real PCs with GPU rendering and hardware encode/decode should do at least as well.
- **Not measured here:** real Windows timings, NVENC/AMF/QSV, `-hwaccel auto` decoding, and WASAPI
  capture.

## 7. Known limitations

- When not streaming (singleplayer, or a server without the mod), capture still uses GDI on the CPU.
- Some DirectX games and DRM video capture as black; share the screen instead.
- Per-app audio is officially Windows 11 / 10 build 20348+. Older systems fall back to all system
  audio, including Minecraft's own.
- Pausing and resuming the shared app in "Game" mode can leak about 20–50 ms of direct sound before
  it's turned down again: idle sessions get their volume back so that nothing stays quiet.
- Viewers still decode on the CPU (FFmpeg) and upload with `StreamingTexture`. Zero-copy decode
  would need D3D11 hardware decoding plus the same interop.
- The relay listens on all interfaces (port 25585). Over Tailscale that's private. On a public
  server, tokens and owner-only publishing protect it, but the port is reachable.
