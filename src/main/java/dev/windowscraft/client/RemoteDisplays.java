package dev.windowscraft.client;

import dev.windowscraft.audio.AcousticModel;
import dev.windowscraft.audio.AcousticsEngine;
import dev.windowscraft.audio.AcousticsProbe;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.net.DisplayInfo;
import dev.windowscraft.net.Payloads;
import dev.windowscraft.render.DisplayTexture;
import dev.windowscraft.stream.StreamSubscriber;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Other players' live screens. Walls showing the same stream (a mirrored window) share one
 * download, one decoder and one sound: the audio plays from whichever of those walls is nearest
 * to you, so mirrors never echo. A stream is only downloaded while one of its walls is near
 * (same dimension, within 96 blocks), and at most the few nearest streams. Client thread only.
 */
public final class RemoteDisplays {
    public static final double WATCH_DISTANCE = 96;
    public static final int MAX_WATCHED = 6;

    /** One remote wall. */
    public static final class Remote {
        public final UUID id;
        public DisplayInfo info;
        public UUID owner;
        public String ownerName;
        public WallRect rect;

        Remote(UUID id) { this.id = id; }

        Watch watch() { return watches.get(info.streamId()); }
        public StreamSubscriber subscriber() { Watch w = watch(); return w == null ? null : w.subscriber; }
        /** Listening options are per stream: they apply to every wall of a mirrored window. */
        public Prefs prefs() { return prefs.computeIfAbsent(info.streamId(), k -> new Prefs()); }
        public boolean muted() { return prefs().muted; }
        public void toggleMuted() { prefs().muted = !prefs().muted; }
        public Identifier pollTexture(long renderFrame) { Watch w = watch(); return w == null ? null : w.poll(renderFrame); }
        /** Render thread: upload false = the last picture again, without any work. */
        public Identifier pollTexture(long renderFrame, boolean upload) {
            Watch w = watch();
            return w == null ? null : upload ? w.poll(renderFrame) : w.lastPolled;
        }
        public int textureWidth() { Watch w = watch(); return w == null ? 0 : w.texture.width(); }
        public int textureHeight() { Watch w = watch(); return w == null ? 0 : w.texture.height(); }
        public String dimension() { return info.dimension(); }
        public int fit() { return info.fit(); }
        /** Where the app's client area is in this stream's picture. */
        public Box box() {
            Box b = new Box(info.boxX0(), info.boxY0(), info.boxX1(), info.boxY1());
            return b.sane() ? b : Box.FULL;
        }
        /** Did its owner let you use it (mouse and keyboard)? */
        public boolean controllableByMe() {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc.player != null && info.controllableBy(mc.player.getUuid());
        }

        /** Its wall is intact here (checked twice a second; until its owner's game catches up, a broken wall shows nothing). */
        public volatile boolean wallOk = true;

        // the owner's pointer, as everyone sees it
        public boolean pointerShown;
        public double pointerX = 0.5, pointerY = 0.5;
        long pointerAt;
    }

    /** One stream being watched, shown on every wall that carries it. */
    static final class Watch {
        final String streamId;
        final StreamSubscriber subscriber;
        final DisplayTexture texture;
        final UUID audioId = UUID.randomUUID();
        AcousticModel.Environment environment = AcousticModel.Environment.OPEN_AIR;
        UUID audibleWall;
        private long lastPollFrame = -1;
        private Identifier lastPolled;

        Watch(Remote first) {
            streamId = first.info.streamId();
            subscriber = new StreamSubscriber(audioId, streamId, first.info.width(), first.info.height(), first.info.fps());
            subscriber.reportAs(first.info.displayId());
            texture = new DisplayTexture("remote/" + streamId);
        }

        Identifier poll(long renderFrame) { // upload once per frame however many walls show it
            if (renderFrame != lastPollFrame) {
                lastPollFrame = renderFrame;
                lastPolled = texture.poll(subscriber);
            }
            return lastPolled;
        }
    }

    private static final Map<UUID, Remote> remotes = new LinkedHashMap<>();
    private static final Map<String, Watch> watches = new LinkedHashMap<>();
    private static final List<DisplayTexture> releaseQueue = new ArrayList<>();
    private static final Map<String, Prefs> prefs = new LinkedHashMap<>();

    /** How you listen to one stream. Volume is on top of Minecraft's master volume. */
    public static final class Prefs {
        public boolean muted;
        public boolean direct = dev.windowscraft.config.ClientSettings.remoteDirect();
        public double volume = 1.0;
    }
    private static int tick;

    public static Collection<Remote> all() { return remotes.values(); }

    static void upsert(Payloads.DisplayState p) {
        DisplayInfo info = p.info();
        WallRect rect = WallRect.parse(info.face(), info.min(), info.max());
        if (rect == null) return;
        Remote r = remotes.computeIfAbsent(info.displayId(), Remote::new);
        DisplayInfo old = r.info;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && info.controllableBy(mc.player.getUuid()) && (old == null || !old.controllableBy(mc.player.getUuid()))) {
            mc.player.sendMessage(net.minecraft.text.Text.literal(p.ownerName() + " lets you use their screen: hold Alt and look at it, or press "
                    + TheaterMode.keyName() + " at it to sit down").formatted(net.minecraft.util.Formatting.GRAY), false);
        }
        r.info = info;
        r.owner = p.owner();
        r.ownerName = p.ownerName();
        r.rect = rect;
        // the stream was rebuilt in another shape (a window resized): its decoder must restart.
        // A step up or down in quality (same shape, other size or rate) doesn't: the decoder
        // keeps scaling to the size it started with, and the picture just carries on.
        if (old != null && old.streamId().equals(info.streamId()) && old.height() > 0 && info.height() > 0
                && Math.abs(old.width() / (double) old.height() - info.width() / (double) info.height()) > 0.01) {
            stopWatch(info.streamId());
        }
        dropUnusedWatches();
    }

    /** The owner's pointer moved or hid. */
    static void pointer(Payloads.Pointer p) {
        Remote r = remotes.get(p.displayId());
        if (r == null) return;
        r.pointerShown = p.visible();
        r.pointerX = Math.max(0, Math.min(1, p.x()));
        r.pointerY = Math.max(0, Math.min(1, p.y()));
        r.pointerAt = System.nanoTime();
    }

    /** Is the owner's pointer showing on that wall? (Quietly hides if the owner stops telling us.) */
    public static boolean pointerShown(Remote r) {
        return r.pointerShown && System.nanoTime() - r.pointerAt < 3_000_000_000L;
    }

    static void remove(UUID id) {
        if (remotes.remove(id) != null) dropUnusedWatches();
    }

    public static void clear() {
        remotes.clear();
        prefs.clear();
        for (String sid : new ArrayList<>(watches.keySet())) stopWatch(sid);
    }

    public static Remote findAt(String dimension, BlockPos pos, Direction face) {
        for (Remote r : remotes.values()) {
            if (r.info.dimension().equals(dimension) && (r.rect.isMonitor() || r.rect.face() == face) && r.rect.contains(pos)) return r;
        }
        return null;
    }

    private static void stopWatch(String streamId) {
        Watch w = watches.remove(streamId);
        if (w != null) {
            w.subscriber.stop();
            AcousticsEngine.get().unregister(w.audioId);
            releaseQueue.add(w.texture); // don't leave a frozen last frame on the wall
        }
    }

    private static void dropUnusedWatches() {
        Set<String> used = new HashSet<>();
        for (Remote r : remotes.values()) used.add(r.info.streamId());
        for (String sid : new ArrayList<>(watches.keySet())) if (!used.contains(sid)) stopWatch(sid);
        prefs.keySet().retainAll(used);
    }

    /** Client tick: start/stop downloads by distance, and feed the acoustics engine. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        tick++;
        String dim = mc.world.getRegistryKey().getValue().toString();
        Vec3d eye = mc.gameRenderer.getCamera().getPos();
        // the camera's own forward/up (a fixed world-up breaks down when looking straight up or down)
        org.joml.Vector3f f = mc.gameRenderer.getCamera().getHorizontalPlane(), up = mc.gameRenderer.getCamera().getVerticalPlane();
        AcousticsEngine.get().setListener(new AcousticModel.ListenerPose(eye.x, eye.y, eye.z, f.x(), f.y(), f.z(), up.x(), up.y(), up.z()));
        double master = mc.options.getSoundVolume(SoundCategory.MASTER);

        if (tick % 10 == 0) {
            for (Remote r : remotes.values()) {
                r.wallOk = !r.info.dimension().equals(dim) || !mc.world.isChunkLoaded(r.rect.min()) || !mc.world.isChunkLoaded(r.rect.max())
                        || dev.windowscraft.display.WallDetector.stillValid(mc.world, r.rect);
            }
        }
        // nearest wall of each stream, among walls in range
        Map<String, Remote> nearestWall = new LinkedHashMap<>();
        for (Remote r : remotes.values()) {
            if (!r.info.dimension().equals(dim)) continue;
            double d2 = r.rect.center().squaredDistanceTo(eye);
            if (d2 >= WATCH_DISTANCE * WATCH_DISTANCE) continue;
            Remote best = nearestWall.get(r.info.streamId());
            if (best == null || d2 < best.rect.center().squaredDistanceTo(eye)) nearestWall.put(r.info.streamId(), r);
        }
        // watch at most the few nearest streams (each one is a decoder and a download)
        List<Remote> chosen = nearestWall.values().stream()
                .sorted(Comparator.comparingDouble(r -> r.rect.center().squaredDistanceTo(eye)))
                .limit(MAX_WATCHED).toList();
        Set<String> wanted = new HashSet<>();
        for (Remote r : chosen) wanted.add(r.info.streamId());
        for (String sid : new ArrayList<>(watches.keySet())) if (!wanted.contains(sid)) stopWatch(sid);

        for (Remote wall : chosen) {
            Watch w = watches.computeIfAbsent(wall.info.streamId(), k -> new Watch(wall));
            // the sound comes from the nearest wall of this stream
            if (!wall.id.equals(w.audibleWall)) {
                w.audibleWall = wall.id;
                w.environment = AcousticModel.Environment.OPEN_AIR;
            }
            AcousticModel.SourcePlane plane = plane(wall.rect);
            if (tick % 4 == 0) w.environment = AcousticsProbe.probe(plane, eye); // raytrace 5×/s, not every frame
            Prefs pr = wall.prefs();
            double vol = master * pr.volume;
            AcousticsEngine.get().update(w.audioId, new AcousticModel.SourceState(plane, (float) vol, pr.muted, w.environment));
            w.subscriber.audio().setDirect(pr.direct);
            w.subscriber.audio().setVolume(pr.muted ? 0 : vol);
        }
    }

    /** The screen as a sound source: centre, outward normal, viewer-right (u) and up (v) axes. */
    public static AcousticModel.SourcePlane plane(WallRect rect) {
        Vec3d[] c = rect.corners(1, 1); // TL, TR, BR, BL
        Vec3d center = c[0].add(c[2]).multiply(0.5);
        Vec3d n = rect.normal(), u = rect.rightVector(), v = rect.upVector();
        return new AcousticModel.SourcePlane(center.x, center.y, center.z, n.x, n.y, n.z,
                u.x, u.y, u.z, v.x, v.y, v.z, rect.width(), rect.height());
    }

    /** How many streams are being downloaded right now (for tests/diagnostics). */
    public static int watchedStreams() { return watches.size(); }

    /** Render thread: free textures of streams that went away. */
    public static void releaseTextures() {
        for (DisplayTexture t : releaseQueue) t.release();
        releaseQueue.clear();
    }

    private RemoteDisplays() {}
}
