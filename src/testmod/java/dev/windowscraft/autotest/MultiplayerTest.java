package dev.windowscraft.autotest;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.client.RemoteDisplays;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.WallDetector;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.stream.StreamPublisher;
import dev.windowscraft.stream.StreamSubscriber;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Dev-only multiplayer check. Run one server and two clients:
 * "sharer" (Alice) goes live with the test pattern + sync beeps; "viewer" (Bob) must see it on the
 * wall, receive audio, and measure the audio/video offset.
 */
public final class MultiplayerTest implements ClientModInitializer {
    private long[] lossStart;

    private String role;
    private int tick = -1;
    private final List<Double> syncOffsets = new ArrayList<>();
    private final List<Double> latencySamples = new ArrayList<>();
    private long lastFlashSeen, lastOnsetSeen;
    private boolean failed;

    @Override
    public void onInitializeClient() {
        role = System.getProperty("windowscraft.autotest.role");
        if (role == null) return;
        dev.windowscraft.client.BusyGuard.enabled = false; // (test machines draw slowly on purpose; the stream checks are about the network)
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String s) { WindowsCraftClient.LOGGER.info("[mp-test {}] {}", role, s); }

    private void check(MinecraftClient mc, boolean ok, String what) {
        if (ok) log("ok: " + what);
        else { log("CHECK FAILED: " + what); failed = true; }
    }

    private boolean lightened;

    private void onTick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tick++;
        // the movie test measures the stream, not the game: the test players draw few frames so a
        // small test machine has room for the encoder and decoder
        if (!lightened && new java.io.File(mc.runDirectory.getParentFile(), "run-bob/movie.txt").exists()) {
            lightened = true;
            mc.options.getMaxFps().setValue(10);
            mc.options.getViewDistance().setValue(2);
            dev.windowscraft.client.BusyGuard.enabled = false; // (the low frame rate here is on purpose)
            log("movie test: game drawing kept light (10 fps, view distance 2)");
        }
        if (role.equals("sharer")) sharer(mc); else viewer(mc);
    }

    private void sharer(MinecraftClient mc) {
        if (tick == 60) {
            check(mc, ClientNet.serverSupported(), "server announced WindowsCraft (relay " + ClientNet.relayHost() + ":" + ClientNet.relayPort() + ")");
            String dim = mc.world.getRegistryKey().getValue().toString();
            int g = mc.player.getBlockPos().getY() - 1;
            WallRect rect = WallDetector.detect(mc.world, new BlockPos(0, g + 2, -8), Direction.SOUTH, p -> false);
            check(mc, rect != null && rect.widthBlocks() == 7 && rect.heightBlocks() == 4, "found the 7×4 wall from the sharer's client");
            Display d = DisplayManager.get().create(dim, rect);
            if (new java.io.File(mc.runDirectory.getParentFile(), "run-bob/movie.txt").exists()) {
                dev.windowscraft.capture.TestPatternProducer.MOVIE = true; // (a movie-like picture: the stream at its full data rate)
                log("movie mode");
            }
            DisplayManager.get().start(d, SourceInfo.testPattern(), new CaptureSettings(720, 30, true, true));
            log("went live with the test pattern");
            WallRect mirrorRect = WallDetector.detect(mc.world, new BlockPos(6, g + 2, -8), Direction.SOUTH, p -> false);
            check(mc, mirrorRect != null && mirrorRect.widthBlocks() == 4 && mirrorRect.heightBlocks() == 3, "found the 4×3 mirror wall");
            Display m = DisplayManager.get().create(dim, mirrorRect);
            DisplayManager.get().start(m, SourceInfo.testPattern(), new CaptureSettings(720, 30, true, true));
            check(mc, m.share() == d.share(), "the second wall mirrors the first (one capture, one stream)");
            log("mirrored the test pattern onto a second wall");
            var mon = mc.world.getBlockState(new BlockPos(-5, g + 1, -5));
            check(mc, mon.getBlock() instanceof dev.windowscraft.block.MonitorBlock, "the server's gaming monitor block arrived: " + mon);
            if (mon.getBlock() instanceof dev.windowscraft.block.MonitorBlock) {
                Display gm = DisplayManager.get().create(dim, WallRect.monitor(new BlockPos(-5, g + 1, -5), mon.get(dev.windowscraft.block.MonitorBlock.TURN)));
                DisplayManager.get().start(gm, SourceInfo.testPattern(), new CaptureSettings(720, 30, true, true));
                log("and onto the gaming monitor block");
            }
            // two joined gaming monitors: both must reach everyone (the joined one hangs at an angle)
            for (BlockPos jp : List.of(new BlockPos(-9, g + 1, -5), new BlockPos(-8, g + 1, -5))) {
                if (!(mc.world.getBlockState(jp).getBlock() instanceof dev.windowscraft.block.MonitorBlock)) {
                    check(mc, false, "joined monitor block arrived at " + jp);
                    continue;
                }
                WallRect jr = dev.windowscraft.block.MonitorBlock.rectAt(mc.world, jp);
                Display jd = DisplayManager.get().create(dim, jr);
                DisplayManager.get().start(jd, SourceInfo.testPattern(), new CaptureSettings(720, 30, true, true));
                log("went live on the gaming monitor at " + jp + " (" + jr.faceString() + ")");
            }
            dev.windowscraft.apps.InputBridge.testSink = sink::add;
            dev.windowscraft.client.ScreenControl.testControlPattern = true;
        }
        // let Bob use the first wall (only that one) once he asks
        java.io.File wants = new java.io.File(mc.runDirectory.getParentFile(), "run-bob/viewer-wants-control");
        if (!approved && wants.exists() && mc.getNetworkHandler() != null) {
            for (var e : mc.getNetworkHandler().getPlayerList()) {
                if (e.getProfile().getId().equals(mc.player.getUuid())) continue;
                check(mc, sink.stream().noneMatch(l -> l.startsWith("button m")), "before he's allowed, Bob's clicks never arrive: " + sink);
                DisplayManager.get().setController(DisplayManager.get().all().get(0), e.getProfile().getId(), e.getProfile().getName(), true);
                log("let " + e.getProfile().getName() + " use the first wall");
                approved = true;
            }
        }
        java.io.File did = new java.io.File(mc.runDirectory.getParentFile(), "run-bob/viewer-controlled");
        if (approved && !controlChecked && did.exists()) {
            controlChecked = true;
            List<String> got = new ArrayList<>(sink);
            log("input from Bob: " + got);
            check(mc, got.contains("focus"), "Bob sitting down gives the app the keyboard");
            check(mc, got.stream().anyMatch(l -> l.startsWith("button l down")) && got.stream().anyMatch(l -> l.startsWith("button l up")), "Bob's clicks reach the app");
            check(mc, got.contains("key down 17") && got.contains("key up 17"), "Bob's keys reach the app");
            check(mc, got.stream().anyMatch(l -> l.startsWith("move ")), "Bob's pointer moves");
            check(mc, got.stream().noneMatch(l -> l.startsWith("button m")), "the wall Bob isn't allowed on ignores him");
            check(mc, got.contains("blur"), "Bob getting up lets the app go");
        }
        if (tick > 60 && tick % 100 == 0) {
            Display d = DisplayManager.get().all().get(0);
            StreamPublisher p = d.publisher();
            CaptureSession s = d.session();
            log("publisher: " + (p == null ? "none" : p.status() + " | audio: " + p.audioStatus()) + " | capture " + String.format("%.1f fps", s.measuredFps()));
        }
        if (tick == 1500) {
            Display d = DisplayManager.get().all().get(0);
            check(mc, d.publisher() != null && d.publisher().status().startsWith("Live"), "publisher is live");
        }
        // stay online until the viewer has finished (it drops a marker file), at most ~6 minutes
        java.io.File marker = new java.io.File(mc.runDirectory.getParentFile(), "run-bob/viewer-done");
        if (tick > 1500 && (marker.exists() || tick > 7200)) {
            StreamPublisher p0 = DisplayManager.get().all().get(0).publisher();
            check(mc, p0 != null && p0.level() > 0 && p0.levelWhy().contains("viewer"),
                    "a viewer falling behind turned the stream down (step " + (p0 == null ? -1 : p0.level()) + ": " + (p0 == null ? "" : p0.levelWhy()) + ")");
            log(failed ? "SHARER DONE WITH FAILURES" : "SHARER DONE");
            mc.scheduleStop();
            tick = Integer.MIN_VALUE / 2;
        }
    }

    private long directStartBytes;

    private final List<String> sink = java.util.Collections.synchronizedList(new ArrayList<>());
    private boolean approved, controlChecked;
    private int ctrlTick = -1;
    private RemoteDisplays.Remote ctrlR;

    /** Bob: asks to use Alice's screen, then uses it from the chair. */
    private void viewerControl(MinecraftClient mc) {
        if (tick == 1220) {
            for (RemoteDisplays.Remote w : RemoteDisplays.all()) {
                check(mc, !w.controllableByMe(), "Alice's walls aren't Bob's to use yet");
                // a forged click without permission: the server must drop it
                ClientNet.control(w.id, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0.5, 0.5, 2, 0);
                ClientNet.control(w.id, dev.windowscraft.net.Payloads.OP_BUTTON_UP, 0.5, 0.5, 2, 0);
            }
        }
        if (tick == 1240) {
            try { new java.io.File(mc.runDirectory, "viewer-wants-control").createNewFile(); } catch (java.io.IOException ignored) {}
        }
        if (tick > 1240 && ctrlTick < 0) {
            ctrlR = RemoteDisplays.all().stream().filter(RemoteDisplays.Remote::controllableByMe).findFirst().orElse(null);
            if (ctrlR != null) { ctrlTick = tick; log("Alice lets me use '" + ctrlR.info.title() + "'"); }
        }
        if (ctrlTick < 0) return;
        int t = tick - ctrlTick;
        long h = mc.getWindow().getHandle();
        if (t == 20) dev.windowscraft.client.TheaterMode.watch(mc, ctrlR);
        if (t == 50) {
            check(mc, dev.windowscraft.client.TheaterMode.controlledRemote() == ctrlR, "theater mode at Alice's wall: Bob uses it");
            check(mc, dev.windowscraft.client.ScreenControl.pointerVisible(ctrlR), "Bob sees the pointer while sitting");
            double before = dev.windowscraft.client.ScreenControl.pointer(ctrlR)[0];
            dev.windowscraft.client.ScreenControl.onLook(mc, 120, 40);
            check(mc, dev.windowscraft.client.ScreenControl.pointer(ctrlR)[0] > before, "Bob's mouse moves the pointer");
            dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, org.lwjgl.glfw.GLFW.GLFW_PRESS);
            dev.windowscraft.client.ScreenControl.onMouseButton(mc, 0, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
            mc.keyboard.onKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_W, 17, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
            check(mc, !mc.options.forwardKey.isPressed(), "Bob's keys don't walk him while sitting");
            mc.keyboard.onKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_W, 17, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
            // the mirror wall isn't his to use
            RemoteDisplays.all().stream().filter(w -> !w.controllableByMe()).forEach(w -> {
                ClientNet.control(w.id, dev.windowscraft.net.Payloads.OP_BUTTON_DOWN, 0.5, 0.5, 2, 0);
                ClientNet.control(w.id, dev.windowscraft.net.Payloads.OP_BUTTON_UP, 0.5, 0.5, 2, 0);
            });
        }
        if (t == 70) {
            check(mc, RemoteDisplays.pointerShown(ctrlR), "Alice's game tells everyone the pointer is showing");
            shot(mc, "mp_viewer_controlling");
        }
        if (t == 80) dev.windowscraft.client.TheaterMode.leaveNow();
        if (t == 140) {
            check(mc, !dev.windowscraft.client.TheaterMode.active(), "Esc gets Bob up");
            try { new java.io.File(mc.runDirectory, "viewer-controlled").createNewFile(); } catch (java.io.IOException ignored) {}
        }
        if (t == 220) check(mc, !RemoteDisplays.pointerShown(ctrlR) && !dev.windowscraft.client.ScreenControl.pointerVisible(ctrlR),
                "once nobody uses it, the pointer is gone for everyone");
    }

    // ---- "watching a movie over a home internet link" (run-bob/movie.txt: kbps queueMs delayMs jitterMs loss freeze)
    private long movieStart, movieMeasure;
    private long movieSkipped0, movieFrames0;

    private void movieViewer(MinecraftClient mc, java.io.File cfg) {
        RemoteDisplays.Remote r = RemoteDisplays.all().stream().findFirst().orElse(null);
        StreamSubscriber sub = r == null ? null : r.subscriber();
        if (tick % 200 == 0) log("movie: " + (r == null ? "no screen known yet" : sub == null ? "screen known, not watching it" : sub.status() + ", pictures " + sub.framesDecoded()));
        if (tick > 20 * 240 && movieMeasure == 0) { // (4 minutes and still nothing: say so and stop)
            log("MOVIE RESULT: none — no picture arrived (" + (r == null ? "no screen known" : sub == null ? "not watching" : sub.status()) + ")");
            try { new java.io.File(mc.runDirectory, "viewer-done").createNewFile(); } catch (java.io.IOException ignored) {}
            mc.scheduleStop();
            movieMeasure = Long.MAX_VALUE / 4;
            return;
        }
        if (sub == null || sub.framesDecoded() < 30) return;
        long now = System.nanoTime();
        if (movieStart == 0) {
            movieStart = now;
            log("movie: watching, settling in");
        }
        if (movieMeasure == 0 && now - movieStart > 10_000_000_000L) {
            try {
                String[] v = java.nio.file.Files.readString(cfg.toPath()).trim().split("\\s+");
                StreamSubscriber.FREEZE_ON_LOSS = Boolean.parseBoolean(v[5]);
                dev.windowscraft.relay.UdpReceiver.testLink = new dev.windowscraft.relay.UdpReceiver.TestLink(
                        Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3]), Double.parseDouble(v[4]));
                log("movie: link " + dev.windowscraft.relay.UdpReceiver.testLink + ", freeze on loss " + StreamSubscriber.FREEZE_ON_LOSS);
            } catch (Exception e) {
                log("movie: bad movie.txt " + e);
            }
            sub.resetStallStats();
            movieSkipped0 = sub.skippedPieces();
            movieFrames0 = sub.framesDecoded();
            movieMeasure = now;
        }
        if (movieMeasure != 0 && now - movieMeasure > 60_000_000_000L) {
            double secs = (now - movieMeasure) / 1e9;
            log(String.format(java.util.Locale.ROOT, "MOVIE RESULT: %.0f s · pictures %.1f/s · pauses over 1/4 s: %d (%.1f s in all, longest %d ms) · damaged pictures %d · pieces lost for good %d · catch-ups %d · %s · stream %dx%d",
                    secs, (sub.framesDecoded() - movieFrames0) / secs, sub.stalls, sub.stallMs / 1000.0, sub.longestStallMs, sub.damagedFrames,
                    sub.skippedPieces() - movieSkipped0, sub.catchUps(), sub.transport(), r.info.width(), r.info.height()));
            shot(mc, "mp_viewer_movie");
            dev.windowscraft.relay.UdpReceiver.testLink = null;
            try { new java.io.File(mc.runDirectory, "viewer-done").createNewFile(); } catch (java.io.IOException ignored) {}
            mc.scheduleStop();
            movieMeasure = Long.MAX_VALUE / 4;
        }
    }

    private void viewer(MinecraftClient mc) {
        java.io.File movieCfg = new java.io.File(mc.runDirectory, "movie.txt");
        if (movieCfg.exists()) {
            movieViewer(mc, movieCfg);
            return;
        }
        viewerControl(mc);
        RemoteDisplays.Remote r = RemoteDisplays.all().stream().findFirst().orElse(null);
        StreamSubscriber sub = r == null ? null : r.subscriber();
        if (sub != null) {
            long flash = sub.lastFlashNanos(), onset = sub.audio().lastOnsetNanos();
            if (flash != lastFlashSeen && onset != lastOnsetSeen && flash != 0 && onset != 0) {
                double off = (onset - flash) / 1e6; // + means sound is heard after the picture shows
                if (Math.abs(off) < 500) syncOffsets.add(off);
                lastFlashSeen = flash;
                lastOnsetSeen = onset;
            }
        }
        if (sub != null && sub.latencyMs() >= 0 && tick % 20 == 0) latencySamples.add(sub.latencyMs());
        if (tick > 0 && tick % 100 == 0) {
            if (sub != null && sub.latencyMs() >= 0) log(String.format("glass-to-glass delay %.0f ms (min %d, max %d), catch-ups %d", sub.latencyMs(), sub.latencyMin(), sub.latencyMax(), sub.catchUps()));
            log(r == null ? "no remote screen yet" : String.format("remote '%s' by %s %dx%d | %s | frames %d | audio %d KB, rms %.3f, device %s",
                    r.info.title(), r.ownerName, r.info.width(), r.info.height(), sub == null ? "not watching" : sub.status(),
                    sub == null ? 0 : sub.framesDecoded(), sub == null ? 0 : sub.audio().bytesPlayed() / 1024,
                    sub == null ? 0 : sub.audio().lastRms(), sub == null ? "?" : (sub.audio().deviceMissing() ? "none (headless)" : "ok")));
        }
        if (tick == 700 && sub != null) {
            check(mc, ClientNet.troubleSupported(), "the server passes \"can't keep up\" on");
            check(mc, ClientNet.relayUdpPort() > 0 && sub.transport().startsWith("UDP"), "watching over UDP (live-video style): " + sub.transport());
            sub.testFallBehind(); // as if this PC couldn't play it fast enough: Alice's stream should send less
            log("pretended to fall behind");
        }
        if (tick == 600) {
            // the server offered a newer WindowsCraft: it was downloaded, whole, next to the mods
            java.nio.file.Path mods = mc.runDirectory.toPath().resolve("mods");
            java.util.List<java.nio.file.Path> got = new java.util.ArrayList<>();
            try (var st = java.nio.file.Files.newDirectoryStream(mods, "windowscraft*.wcupdate")) { st.forEach(got::add); } catch (Exception ignored) {}
            long size = got.isEmpty() ? -1 : got.get(0).toFile().length();
            String jar = System.getProperty("windowscraft.update.testJar", "");
            long want = new java.io.File(jar.isEmpty() ? "?" : jar).length();
            check(mc, got.size() == 1 && size > 0 && size == want, "an update from the server was downloaded whole (" + got + ", " + size + " of " + want + " bytes)");
            for (java.nio.file.Path p : got) p.toFile().delete(); // (a test: nothing to put in place)
        }
        // a bad connection: pieces get lost on the way (more than resending can make up for).
        // The picture must keep going (the last good one held, never smeared), and Alice's
        // stream should be asked to send less
        if (tick == 1600 && sub != null) {
            lossStart = new long[]{sub.framesDecoded(), sub.skippedPieces(), sub.recoveredPieces()};
            dev.windowscraft.relay.UdpReceiver.testLoss = 0.35;
            log("losing 35 % of the picture packets on the way (resends too)");
        }
        if (tick == 1660) shot(mc, "mp_viewer_lossy");
        if (tick == 1700 && sub != null && lossStart != null) {
            dev.windowscraft.relay.UdpReceiver.testLoss = 0;
            long frames = sub.framesDecoded() - lossStart[0], lost = sub.skippedPieces() - lossStart[1];
            long resent = sub.recoveredPieces() - lossStart[2];
            check(mc, lost > 0 || resent > 50, "the lossy connection really lost pieces: " + resent + " sent again, " + lost + " given up on (" + sub.transport() + ")");
            check(mc, frames > 20, "the picture kept going through it: " + frames + " pictures in 5 s");
            check(mc, sub.lossTroubleSent(), "the owner was asked for a lighter stream");
        }
        if (tick == 900) shot(mc, "mp_viewer_front");
        if (tick == 910 && r != null) mc.setScreen(new dev.windowscraft.ui.RemoteOptionsScreen(r));
        if (tick == 950) shot(mc, "mp_viewer_options");
        if (tick == 955 && r != null) {
            mc.setScreen(null);
            r.prefs().direct = true; // "Direct": crisp stereo, no 3D chain
            directStartBytes = sub == null ? 0 : sub.audio().bytesPlayed();
        }
        if (tick == 1100) { // look at Alice's gaming monitor block
            int g = mc.player.getBlockPos().getY() - 1;
            net.minecraft.util.math.Vec3d to = new net.minecraft.util.math.Vec3d(-4.5, g + 1.6, -4.5).subtract(mc.player.getEyePos());
            mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        }
        if (tick == 1130) { // and at the joined pair
            int g = mc.player.getBlockPos().getY() - 1;
            net.minecraft.util.math.Vec3d to = new net.minecraft.util.math.Vec3d(-8, g + 1.6, -4.5).subtract(mc.player.getEyePos());
            mc.player.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            mc.player.setPitch((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        }
        if (tick == 1140) {
            shot(mc, "mp_viewer_joined_monitors");
            mc.player.setYaw(180);
            mc.player.setPitch(0);
        }
        if (tick == 1110) {
            shot(mc, "mp_viewer_monitor_block");
            mc.player.setYaw(180);
            mc.player.setPitch(0);
        }
        if (tick == 1150) check(mc, sub != null && sub.audio().bytesPlayed() - directStartBytes > 48000 * 4 * 3, "direct sound keeps playing");
        if (tick == 1160 && r != null) {
            r.prefs().direct = false;
            dev.windowscraft.client.TheaterMode.watch(mc, r);
            check(mc, dev.windowscraft.client.TheaterMode.controlledRemote() == null, "watching someone's screen without permission doesn't use it");
        }
        if (tick == 1200) {
            check(mc, dev.windowscraft.client.TheaterMode.active(), "viewer in theater mode");
            shot(mc, "mp_viewer_theater");
        }
        if (tick == 1210) dev.windowscraft.client.TheaterMode.toggle(mc);
        if (tick == 2400) {
            List<Double> sorted = new ArrayList<>(latencySamples);
            java.util.Collections.sort(sorted);
            if (!sorted.isEmpty()) log(String.format("delay over %d one-second samples: median %.0f ms, 90th pct %.0f ms, worst %.0f ms",
                    sorted.size(), sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.9)), sorted.get(sorted.size() - 1)));
            log("delay series (ms, 1/s): " + latencySamples.stream().map(d -> Long.toString(Math.round(d))).toList());
            check(mc, r != null, "viewer learned about Alice's screen from the server");
            List<RemoteDisplays.Remote> walls = new ArrayList<>(RemoteDisplays.all());
            check(mc, walls.size() == 5 && walls.stream().allMatch(w -> w.info.streamId().equals(walls.get(0).info.streamId())),
                    "viewer sees all five of Alice's screens carrying one stream (" + walls.size() + ")");
            check(mc, walls.stream().filter(w -> w.rect.isMonitor() && w.rect.turn() == 0).count() == 2
                            && walls.stream().anyMatch(w -> w.rect.faceString().contains("~")),
                    "including both joined gaming monitors, the hanging one too: " + walls.stream().map(w -> w.rect.faceString()).toList());
            check(mc, walls.stream().anyMatch(w -> w.rect.isMonitor() && w.rect.turn() == 1),
                    "including the diagonal gaming monitor block: " + walls.stream().map(w -> w.rect.faceString()).toList());
            check(mc, RemoteDisplays.watchedStreams() == 1, "viewer downloads the mirrored stream once (" + RemoteDisplays.watchedStreams() + ")");
            check(mc, sub != null && sub.framesDecoded() > 200, "viewer decoded > 200 frames (" + (sub == null ? 0 : sub.framesDecoded()) + ")");
            check(mc, sub != null && sub.audio().bytesPlayed() > 48000 * 4 * 5, "viewer received > 5 s of audio");
            double avg = syncOffsets.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            // (the median too: a beep matched to the wrong flash, ±0.5–1 s, drags the average)
            double median = syncOffsets.stream().mapToDouble(Double::doubleValue).sorted()
                    .skip(Math.max(0, (syncOffsets.size() - 1) / 2)).findFirst().orElse(Double.NaN);
            log(String.format("A/V offset over %d beeps: avg %.0f ms, median %.0f ms (positive = sound after picture), samples %s", syncOffsets.size(), avg,
                    median, syncOffsets.subList(0, Math.min(12, syncOffsets.size()))));
            check(mc, syncOffsets.size() >= 5 && Math.abs(avg) < 250, "audio and video within 250 ms of each other");
            check(mc, ctrlTick > 0, "Bob was allowed to use Alice's wall");
            shot(mc, "mp_viewer_end");
            log(failed ? "VIEWER DONE WITH FAILURES" : "VIEWER DONE");
            try { new java.io.File(mc.runDirectory, "viewer-done").createNewFile(); } catch (java.io.IOException ignored) {}
        }
        if (tick == 2450) mc.scheduleStop();
    }

    private void shot(MinecraftClient mc, String name) {
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, name + ".png", mc.getFramebuffer(), msg -> log(msg.getString()));
    }
}
