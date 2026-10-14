# Notices

WindowsCraft is licensed under the GNU Lesser General Public License v3.0 or later
(`COPYING.LESSER`, which builds on the GNU GPL v3 in `COPYING`).

## Third-party code

- **Dream Displays** — https://github.com/arnodoelinger/dreamdisplays
  Copyright (c) Arno Dölinger and contributors, LGPL-3.0.
  The 3D audio engine (`src/main/java/dev/windowscraft/audio/dsp/*`, `AudioRenderChain`,
  `AcousticsEngine`, `AcousticModel`, `AcousticsProbe`) is a Java port of Dream Displays'
  `media/audio` module and `VoxelAcousticsProbe`. Its material table comes from Sound Physics
  Remastered's defaults, as in the original.

## Tools used at runtime (not bundled)

- **FFmpeg** (https://ffmpeg.org) runs as a separate program. On Windows the mod downloads
  the official BtbN build (GPL) of FFmpeg 9.0.2 on first use and checks it against its
  SHA-256 (if that build is ever gone: the newest 9.0 build, checked against BtbN's list).
- **JNA** ships with Minecraft itself.

## Bundled helper

- `wc-audio.exe` is built from `native/wc-audio` (this repository), using the `wasapi`
  Rust crate (MIT) and Microsoft's `windows` crates (MIT/Apache-2.0).
