# WindowsCraft: long-term vision and project goals

I want to clarify the long-term vision for WindowsCraft as we continue development.

This isn't a request to restart our work, change direction, or interrupt what we're currently implementing. I'm happy with the progress we've made, including our ongoing Sandboxie integration and the custom sandbox management options we're adding to the in-game monitor management UI.

I just want to make sure we're building toward the same ultimate goal.

## The long-term vision

I want WindowsCraft to compete with the extremely smooth, native-feeling experience demonstrated by WaylandCraft and other Linux compositor mods, but on Windows, with a major additional advantage: **multiplayer screen sharing, shared audio, and remote interaction.**

I want players to be able to build actual computer setups inside Minecraft, with multiple monitors, applications, games, and virtual environments that feel genuinely usable rather than like delayed screen-sharing surfaces.

The ideal experience should be excellent for gaming, watching movies together, browsing, working, and general desktop use.

Importantly, I want this experience to be just as impressive for remote peers as it is for the host.

## Our existing foundation

We've already implemented a substantial amount of functionality, including virtual displays, native Windows integration, capture, hardware encoding, remote streaming, input forwarding, spatial audio, shared viewing, and Minecraft-integrated screen rendering.

We're also actively developing Sandboxie integration to improve application isolation, support multiple application instances, and give users more control over the environments attached to their virtual monitors.

I want to keep improving and extending these existing systems, not replace them unnecessarily.

## Long-term technical priorities

**1. WaylandCraft-level local responsiveness**

I want the host's in-game monitors to feel as close to native Windows applications as technically possible.

Investigate opportunities to minimize capture overhead, unnecessary compression, decoding, memory transfers, buffering, and GPU synchronization delays.

If our existing native infrastructure can provide a more direct GPU presentation path for local screens, I'd like us to explore that without compromising multiplayer streaming.

**2. Exceptional peer experience**

Remote players should receive the sharpest, smoothest, most responsive experience their hardware and network can reasonably support.

Prioritize low-latency streaming, crisp text, high-resolution video, smooth frame pacing, efficient hardware decoding, and immediate input forwarding.

I want peer interaction to be genuinely practical for everyday desktop use and as responsive as possible for gaming.

**3. Gaming-grade input without sacrificing desktop usability**

Support accurate keyboard input, simultaneous key presses, reliable key-state handling, relative mouse movement, and low-latency controls.

Input should not be unnecessarily delayed by Minecraft ticks, frame capture, video decoding, or rendering.

These improvements should also benefit regular typing, clicking, dragging, and productivity applications.

**4. Continue building on Sandboxie integration**

Sandboxie is now an important part of our approach to application isolation and managing multiple application instances.

Continue the integration and testing we're already doing, including the custom Sandboxie options within the monitor management interface.

Long-term, I'd like users to have flexible control over which applications and sandboxes are associated with virtual monitors, whether environments persist, and how multiple instances are managed.

Keep audio isolation, application compatibility, and host input-focus behavior in mind as separate technical concerns that need verification. Sandboxie shouldn't be assumed to solve all of them automatically.

**5. Minimize Minecraft performance overhead**

I want WindowsCraft to have as little impact as possible on ordinary Minecraft performance.

That includes Minecraft FPS, 1% lows, frame times, JVM memory, CPU utilization, and GPU overhead.

Continue exploring ways to offload appropriate work into our existing native infrastructure, reduce unnecessary memory copies, reuse buffers, optimize texture updates, and eliminate idle processing.

Ideally, when the virtual monitors aren't being used, WindowsCraft should have almost no measurable impact on Minecraft performance.

**6. Preserve the full Minecraft experience**

Performance optimizations must not sacrifice the features that make WindowsCraft immersive.

Preserve:
- Proper world-space monitor rendering and occlusion by Minecraft blocks, entities, and geometry.
- Spatial audio originating from the actual in-game monitors.
- Distance-based sound attenuation and multiplayer audio synchronization.
- Optional shader effects on screens and compatibility with supported shader packs.
- Existing lighting, brightness, emissive, and rendering options.
- Seated interaction, fullscreen viewing, tablets, and other existing display features.
- Shared viewing, multiplayer synchronization, and remote-control permissions.

The heavy processing can happen outside Minecraft, but the monitors must continue to behave like real objects inside the Minecraft world.

**7. Maximum performance for hosts and peers**

Avoid improving peer performance by unnecessarily sacrificing host performance, or vice versa.

Investigate efficient multi-viewer streaming, hardware utilization, adaptive quality, GPU memory management, frame scheduling, and resource budgeting.

I want WindowsCraft to intelligently balance performance without silently sacrificing image quality, functionality, or responsiveness.

**8. General application compatibility**

WindowsCraft should work with as many ordinary Windows applications as possible.

Avoid making app-specific solutions the foundation of the system when a more general approach is possible.

Support browsers, games, media players, productivity software, and multiple application instances through reusable infrastructure.

## How I want us to continue developing

Please treat all of this as long-term guidance for our existing project.

- Continue the current Sandboxie integration and testing rather than interrupting it for a new roadmap.
- Inspect and reuse existing systems before adding anything.
- Avoid unnecessary rewrites, duplicate pipelines, and redundant dependencies.
- Prefer small, measurable, reversible improvements.
- Benchmark actual bottlenecks rather than assuming an optimization will help.
- Protect existing functionality and compatibility during refactoring.
- Preserve our established workflows, UI, monitor behavior, and multiplayer functionality.
- Be realistic about Windows limitations and avoid promising universal compatibility where it cannot be guaranteed.
- Prioritize improvements that benefit the entire WindowsCraft experience rather than only one application or use case.

## The ultimate goal

I want WindowsCraft to feel less like a mod that streams Windows desktops onto Minecraft blocks and more like **Windows applications and shared computers genuinely exist inside the Minecraft world.**

Locally, I want the responsiveness and visual quality people associate with WaylandCraft.

Remotely, I want friends to be able to see, hear, and control those same environments with exceptional clarity and minimal latency.

And I want the underlying implementation to remain efficient enough that Minecraft itself continues performing extremely well.

We don't need to accomplish all of this immediately. I just want these goals to guide the decisions we're already making.

**For now, continue our current Sandboxie work exactly where we left off. Don't switch tasks or start a new implementation because of this message. Just keep this overall direction in mind as we make future architectural and performance decisions.**

## Added performance goals (13 Oct 2026)

- Peer viewing and control will always cost something; keep that overhead as small as technically possible.
- Several peers watching the same virtual monitor share one capture and one encode — never a separate expensive pipeline per viewer.
- Remote control adds almost no overhead beyond viewing.
- Monitors use rendering and streaming resources only when they're actually needed.
- As viewer counts grow, watch host FPS, frame-time stability, 1% lows, memory and GPU use: small, predictable overhead is better than occasional stutters.
- Measure before optimizing: median, P95 and P99, host and peer separately; diagnostics off by default.
