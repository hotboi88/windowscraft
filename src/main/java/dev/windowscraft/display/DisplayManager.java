package dev.windowscraft.display;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.ClientNet;
import dev.windowscraft.stream.StreamPublisher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Owns the displays (walls) for the world you're in and the {@link Share}s feeding them. A share
 * is one capture + one stream; several walls showing the same source share it (mirroring).
 * Everything here runs on the client thread; capture and streaming have their own threads.
 */
public final class DisplayManager {
    /** Shares whose walls are all farther than this pause capture (unless they're streaming to others). */
    public static final double ACTIVE_DISTANCE = 96;
    private static final DisplayManager INSTANCE = new DisplayManager();

    private final List<Display> displays = new ArrayList<>();
    private final List<Share> shares = new ArrayList<>();
    private final List<Share> pendingRelease = new ArrayList<>();
    private String worldKey;
    private int tickCounter;

    public static DisplayManager get() { return INSTANCE; }

    public List<Display> all() { return Collections.unmodifiableList(displays); }

    /** What you're sharing right now (each one may be on several walls). */
    public List<Share> liveShares() {
        List<Share> out = new ArrayList<>();
        for (Share s : shares) if (!s.session.isEnded()) out.add(s);
        return out;
    }

    /** Stops a share everywhere it's shown (the walls stay, ready for next time). */
    public void stopShare(Share s) {
        for (Display d : new ArrayList<>(s.displays)) stop(d);
    }

    // ---- lifecycle -------------------------------------------------------------------------

    public void onJoin(MinecraftClient mc) {
        stopAll();
        displays.clear();
        worldKey = computeWorldKey(mc);
        displays.addAll(DisplayStore.load(worldKey));
        for (Display d : displays) if (d.access == Display.ACCESS_ALL_BUT) applyAccess(d); // (who's here now)
        WindowsCraftClient.LOGGER.info("Loaded {} display(s) for {}", displays.size(), worldKey);
    }

    public void onDisconnect() {
        if (worldKey != null) DisplayStore.save(worldKey, displays);
        stopAll();
        displays.clear();
        worldKey = null;
    }

    private static String computeWorldKey(MinecraftClient mc) {
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return "sp:" + mc.getServer().getSaveProperties().getLevelName();
        }
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry != null) return (entry.isRealm() ? "realms:" : "mp:") + entry.address.toLowerCase(java.util.Locale.ROOT);
        return "unknown";
    }

    // ---- queries ---------------------------------------------------------------------------

    public Display findAt(String dimension, BlockPos pos, Direction face) {
        for (Display d : displays) {
            if (!d.tablet && d.dimension.equals(dimension) && (d.rect.isMonitor() || d.rect.face() == face) && d.rect.contains(pos)) return d;
        }
        return null;
    }

    public boolean isTaken(String dimension, Direction face, BlockPos pos) {
        return findAt(dimension, pos, face) != null;
    }

    /** Which source ids are currently live (for the LIVE badges in the picker). */
    public List<String> liveSourceIds() {
        List<String> ids = new ArrayList<>();
        for (Share s : shares) if (!s.session.isEnded()) ids.add(s.source.id());
        return ids;
    }

    private Share shareFor(String sourceId) {
        for (Share s : shares) if (s.source.id().equals(sourceId) && !s.session.isEnded()) return s;
        return null;
    }

    // ---- mutations -------------------------------------------------------------------------

    /** Your tablet's screen (made the first time it's asked for in this world). */
    public Display tablet(MinecraftClient mc) {
        for (Display d : displays) if (d.tablet) return d;
        if (mc.player == null || mc.world == null) return null;
        Display d = new Display(UUID.randomUUID(), mc.world.getRegistryKey().getValue().toString(), WallRect.monitor(mc.player.getBlockPos().up(), 0));
        d.tablet = true;
        d.access = Display.ACCESS_ME;
        int[] size = dev.windowscraft.config.ClientSettings.tabletSize(); // tablet-shaped, or what you picked last
        d.monitorW = size[0];
        d.monitorH = size[1];
        displays.add(d);
        return d;
    }

    public Display create(String dimension, WallRect rect) {
        for (Display d : displays) {
            if (d.tablet) continue;
            if (d.dimension.equals(dimension) && d.rect.overlaps(rect)) {
                if (!d.rect.equals(rect)) resize(d, rect); // the wall was rebuilt: take its new shape
                return d;
            }
        }
        Display d = new Display(UUID.randomUUID(), dimension, rect);
        Display like = null; // who can use it: as your other screens (your latest one)
        for (Display o : displays) if (!o.tablet) like = o;
        if (like != null) {
            d.access = like.access;
            d.picked.putAll(like.picked);
            d.blocked.putAll(like.blocked);
        }
        displays.add(d);
        applyAccess(d);
        save();
        return d;
    }

    /**
     * Shows {@code source} on {@code d}. If that source is already live on another wall, this wall
     * joins it (mirror: same capture, same stream); otherwise a new capture starts.
     */
    public void start(Display d, SourceInfo source, CaptureSettings settings) {
        settings = noCursor(settings);
        detach(d);
        Share share = shareFor(source.id());
        if (share == null) {
            share = new Share(source, new CaptureSession(source, settings), "share/" + UUID.randomUUID());
            share.audio.configure(settings.audio(), settings.hearInGame());
            shares.add(share);
        } else {
            applyStreamSettings(share, settings); // the newest choice of quality wins for every wall of it
        }
        share.displays.add(d);
        d.share = share;
        d.endedMessage = null;
        d.endNotified = false;
        d.lastSource = source.descriptor();
        d.lastSettings = settings;
        if (share.publisher != null) share.publisher.setTargets(targets(share));
        else if (ClientNet.serverSupported() && !targets(share).isEmpty()) share.publisher = new StreamPublisher(targets(share), source, share.session, share.audio, settings);
        save();
    }

    public void updateSettings(Display d, CaptureSettings settings) {
        settings = noCursor(settings);
        d.lastSettings = settings;
        if (d.share != null) {
            applyStreamSettings(d.share, settings);
            if (d.share.publisher != null) d.share.publisher.setTargets(targets(d.share)); // e.g. Picture: Fill/Fit
        }
        save();
    }

    /** Windows' own cursor is never in the picture: the screen draws its pointer itself, only while someone's using it. */
    private static CaptureSettings noCursor(CaptureSettings s) {
        return new CaptureSettings(s.maxHeight(), s.fps(), false, s.audio(), s.fit(), s.hearInGame());
    }

    /** Lets a player use this screen with their mouse and keyboard, or takes that away. */
    /** Picks (or un-picks) someone who may use that screen (switching it to "people I pick" if it was "only me"). */
    public void setController(Display d, UUID player, String name, boolean allowed) {
        if (d.access == Display.ACCESS_ALL_BUT) { // "everyone except…": allowing someone un-blocks them
            if (allowed) d.blocked.remove(player);
            else d.blocked.put(player, name);
        } else if (allowed) {
            if (!d.picked.containsKey(player) && d.picked.size() >= dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS) return; // (full)
            d.picked.put(player, name);
            d.access = Display.ACCESS_PICKED;
        } else d.picked.remove(player);
        applyAccess(d);
        save();
    }

    /** Blocks (or un-blocks) someone for "everyone except…". */
    public void setBlocked(Display d, UUID player, String name, boolean block) {
        if (block) d.blocked.put(player, name);
        else d.blocked.remove(player);
        applyAccess(d);
        save();
    }

    /** Who may use it: only you, the people you pick, or everyone except the ones you block. */
    public void setAccess(Display d, int access) {
        d.access = Math.max(Display.ACCESS_ME, Math.min(Display.ACCESS_ALL_BUT, access));
        applyAccess(d);
        save();
    }

    /**
     * Works out who can use the screen now and tells the server if that changed (for "everyone
     * except…", whoever is on the server right now — again when people join or leave). Anyone who
     * can't any more lets go of everything.
     */
    public void applyAccess(Display d) {
        if (d.tablet) return; // only yours
        java.util.Map<UUID, String> want = new java.util.LinkedHashMap<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (d.access == Display.ACCESS_PICKED) want.putAll(d.picked);
        else if (d.access == Display.ACCESS_ALL_BUT && mc.getNetworkHandler() != null) {
            UUID me = mc.player == null ? null : mc.player.getUuid();
            for (var e : mc.getNetworkHandler().getPlayerList()) {
                UUID id = e.getProfile().getId();
                if (!id.equals(me) && !d.blocked.containsKey(id)) want.put(id, e.getProfile().getName());
            }
        }
        if (want.size() > dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS) {
            // too many: whoever can use it already keeps it, newcomers wait
            java.util.Map<UUID, String> keep = new java.util.LinkedHashMap<>();
            for (var e : want.entrySet()) if (d.controllers.containsKey(e.getKey())) keep.put(e.getKey(), e.getValue());
            for (var e : want.entrySet()) if (keep.size() < dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS) keep.putIfAbsent(e.getKey(), e.getValue());
            while (keep.size() > dev.windowscraft.net.DisplayInfo.MAX_CONTROLLERS) keep.remove(keep.keySet().iterator().next());
            want = keep;
        }
        if (want.equals(d.controllers)) return;
        java.util.Set<UUID> gone = new java.util.HashSet<>(d.controllers.keySet());
        gone.removeAll(want.keySet());
        d.controllers.clear();
        d.controllers.putAll(want);
        if (d.share != null && d.share.publisher != null) d.share.publisher.setTargets(targets(d.share)); // tell the server now
        for (UUID p : gone) dev.windowscraft.client.ScreenControl.dropGuest(d, p);
    }

    /** The screen takes a new shape (its wall changed); everyone watching is told straight away. */
    void resize(Display d, WallRect r) {
        d.rect = r;
        if (d.share != null && d.share.publisher != null) d.share.publisher.setTargets(targets(d.share));
        save();
    }

    /**
     * The wall around this screen as it is now: found again from the screen's middle block, or
     * from whichever of its blocks still stands (nearest the middle first). Null if none does.
     */
    private WallRect refit(ClientWorld world, Display d) {
        WallRect old = d.rect;
        if (old.isMonitor()) return WallDetector.stillValid(world, old) ? old : null; // a monitor block: there, or not
        java.util.function.Predicate<BlockPos> taken = p -> {
            for (Display o : displays) if (o != d && o.dimension.equals(d.dimension) && o.rect.face() == old.face() && o.rect.contains(p)) return true;
            return false;
        };
        BlockPos mid = BlockPos.ofFloored(old.center().subtract(Vec3d.of(old.face().getVector()).multiply(0.5)));
        List<BlockPos> tries = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(old.min(), old.max())) tries.add(p.toImmutable());
        tries.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(mid)));
        for (BlockPos p : tries) {
            if (taken.test(p) || !WallDetector.isScreenBlock(world, p, old.face())) continue;
            WallRect r = WallDetector.detect(world, p, old.face(), taken);
            if (r != null) return r;
        }
        return null;
    }

    /** Checks in a row (half a second apart) that found the wall broken. */
    private final java.util.Map<UUID, Integer> brokenSince = new java.util.HashMap<>();

    /** Is every chunk the wall (and the space just in front of it) is in loaded? */
    private static boolean wallLoaded(ClientWorld world, WallRect r) {
        BlockPos a = r.min().offset(r.face()), b = r.max().offset(r.face());
        int x0 = Math.min(Math.min(r.min().getX(), a.getX()), Math.min(r.max().getX(), b.getX())) >> 4;
        int x1 = Math.max(Math.max(r.min().getX(), a.getX()), Math.max(r.max().getX(), b.getX())) >> 4;
        int z0 = Math.min(Math.min(r.min().getZ(), a.getZ()), Math.min(r.max().getZ(), b.getZ())) >> 4;
        int z1 = Math.max(Math.max(r.min().getZ(), a.getZ()), Math.max(r.max().getZ(), b.getZ())) >> 4;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                if (!world.getChunkManager().isChunkLoaded(x, z)) return false;
        return true;
    }

    /** Is the wall at least two chunks inside the area your game keeps loaded? */
    private static boolean wellInside(MinecraftClient mc, WallRect r) {
        int view = mc.options.getClampedViewDistance();
        Vec3d c = r.center();
        int dx = Math.abs(((int) Math.floor(c.x) >> 4) - mc.player.getChunkPos().x);
        int dz = Math.abs(((int) Math.floor(c.z) >> 4) - mc.player.getChunkPos().z);
        return Math.max(dx, dz) <= Math.max(1, view - 2);
    }

    public Display byId(UUID id) {
        for (Display d : displays) if (d.id.equals(id)) return d;
        return null;
    }

    private static void applyStreamSettings(Share share, CaptureSettings settings) {
        share.session.updateSettings(settings);
        share.audio.configure(settings.audio(), settings.hearInGame());
        if (share.publisher != null) share.publisher.updateSettings(settings);
    }

    private static List<StreamPublisher.Target> targets(Share share) {
        List<StreamPublisher.Target> t = new ArrayList<>();
        for (Display d : share.displays) if (!d.tablet) t.add(d.target()); // (a tablet is only yours to see)
        return t;
    }

    public void stop(Display d) {
        detach(d);
        d.endedMessage = null;
    }

    public void remove(Display d) {
        detach(d);
        displays.remove(d);
        save();
    }

    /** Takes a wall off its share; the share (capture + stream) ends when its last wall leaves. */
    private void detach(Display d) {
        Share s = d.share;
        if (s == null) return;
        d.share = null;
        s.displays.remove(d);
        if (s.publisher != null) ClientNet.remove(d.id);
        if (s.displays.isEmpty()) {
            endShare(s);
        } else if (s.publisher != null) {
            s.publisher.setTargets(targets(s));
        }
    }

    private void endShare(Share s) {
        if (s.publisher != null) {
            s.publisher.stop();
            for (Display d : s.displays) ClientNet.remove(d.id);
            s.publisher = null;
        }
        s.session.stop();
        if (s.localSound != null) {
            s.localSound.stop();
            s.localSound = null;
        }
        s.audio.close();
        if (s.gpu != null) s.gpu.stop();
        shares.remove(s);
        pendingRelease.add(s);
        dev.windowscraft.monitor.InGameMonitors.released(s.source); // an in-game monitor goes with its last wall
    }

    private void stopAll() {
        for (Display d : new ArrayList<>(displays)) detach(d);
        for (Share s : new ArrayList<>(shares)) endShare(s);
        dev.windowscraft.capture.win.KeepRendering.setActive(false);
    }

    public void save() {
        if (worldKey != null) DisplayStore.save(worldKey, displays);
    }

    // ---- per tick --------------------------------------------------------------------------

    public void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;
        tickLocalSound(mc);
        if (tickCounter % 1200 == 0) dev.windowscraft.audio.AudioCapture.restoreLeftovers(); // once a minute
        if (++tickCounter % 10 != 0) return; // twice a second is plenty
        String dim = world.getRegistryKey().getValue().toString();
        Vec3d eye = mc.player.getEyePos();

        // "everyone except…": people come and go
        if (tickCounter % 40 == 0) for (Display d : displays) if (d.access == Display.ACCESS_ALL_BUT) applyAccess(d);

        // walls change: a screen always follows its wall — smaller when blocks are taken away,
        // bigger when the wall is built out around it, gone only when no screen block is left
        for (Display d : new ArrayList<>(displays)) {
            if (d.tablet) continue; // (in your hand: no wall to check)
            // Only judge a wall your game really has in front of it: every chunk it touches (and
            // the ones just in front of it) loaded, and well inside your view distance — as you
            // walk away, the world unloads piece by piece, and a half-unloaded wall must never
            // count as broken (that would turn an in-game monitor off and close its apps).
            if (!d.dimension.equals(dim) || !wallLoaded(world, d.rect) || !wellInside(mc, d.rect)) {
                brokenSince.remove(d.id);
                continue;
            }
            boolean broken = !WallDetector.stillValid(world, d.rect);
            // and it has to stay broken for a moment (blocks arriving from the server mid-load)
            if (broken) {
                int n = brokenSince.merge(d.id, 1, Integer::sum);
                if (n < 2) continue;
            } else brokenSince.remove(d.id);
            if (!broken && tickCounter % 40 != 0) continue; // growing is checked every 2 s
            WallRect now = refit(world, d);
            if (now == null) {
                if (broken) {
                    remove(d);
                    mc.player.sendMessage(Text.literal("A screen was removed: its wall is gone.").formatted(Formatting.GRAY), true);
                }
            } else if (!now.equals(d.rect) && (broken || now.contains(d.rect))) {
                resize(d, now);
                mc.player.sendMessage(Text.literal("Screen resized to its wall: " + now.widthBlocks() + " × " + now.heightBlocks() + " blocks")
                        .formatted(Formatting.GRAY), true);
            }
        }

        // covered browsers/Discord stop drawing (and freeze on the wall) unless we keep them going
        dev.windowscraft.capture.win.KeepRendering.setActive(shares.stream()
                .anyMatch(s -> s.source.kind() == SourceInfo.Kind.WINDOW && !s.session.isEnded()));
        for (Share s : new ArrayList<>(shares)) {
            CaptureSession session = s.session;
            if (session.isEnded()) {
                for (Display d : s.displays) {
                    d.endedMessage = session.message();
                    d.share = null;
                }
                if (s.displays.stream().anyMatch(d -> !d.endNotified)) {
                    mc.player.sendMessage(Text.literal("Screen share ended: " + session.message()).formatted(Formatting.YELLOW), true);
                }
                for (Display d : s.displays) d.endNotified = true;
                endShare(s);
                s.displays.clear();
                continue;
            }
            // the server may say it supports WindowsCraft a moment after you went live
            if (s.publisher == null && ClientNet.serverSupported() && !targets(s).isEmpty()) {
                s.publisher = new StreamPublisher(targets(s), s.source, session, s.audio, session.settings());
            }
            if (s.publisher != null && tickCounter % 200 == 0) s.publisher.announceAll(); // heartbeat
            tickGpuSharing(s);
            boolean anyNear = s.displays.stream().anyMatch(d -> d.dimension.equals(dim)
                    && d.rect.center().squaredDistanceTo(eye) < ACTIVE_DISTANCE * ACTIVE_DISTANCE);
            // keep capturing while streaming, even when you walk away from your own screens
            session.setPaused(!anyNear && s.publisher == null);
        }
    }

    /**
     * "Hear it in the game": while a share's app is turned down, play its sound from whichever of
     * its walls is nearest to you, like everyone else hears it.
     */
    private void tickLocalSound(MinecraftClient mc) {
        String dim = mc.world.getRegistryKey().getValue().toString();
        Vec3d ear = mc.gameRenderer.getCamera().getPos();
        double master = mc.options.getSoundVolume(net.minecraft.sound.SoundCategory.MASTER);
        for (Share s : shares) {
            boolean want = s.audio.playsInGame() && !s.session.isEnded();
            if (want && s.localSound == null) s.localSound = new LocalSound(s.audio);
            if (!want && s.localSound != null) {
                s.localSound.stop();
                s.localSound = null;
            }
            if (s.localSound == null) continue;
            Display nearest = null;
            for (Display d : s.displays) {
                if (!d.dimension.equals(dim)) continue;
                if (nearest == null || d.rect.center().squaredDistanceTo(ear) < nearest.rect.center().squaredDistanceTo(ear)) nearest = d;
            }
            if (nearest == null) { // all its walls are in another dimension: nothing to hear here
                s.localSound.setVolume(0);
                dev.windowscraft.audio.AcousticsEngine.get().update(s.localSound.id, new dev.windowscraft.audio.AcousticModel.SourceState(
                        dev.windowscraft.client.RemoteDisplays.plane(s.displays.get(0).rect), 0f, true, s.environment));
                continue;
            }
            if (nearest != s.audibleWall) {
                s.audibleWall = nearest;
                s.environment = dev.windowscraft.audio.AcousticModel.Environment.OPEN_AIR;
            }
            var plane = dev.windowscraft.client.RemoteDisplays.plane(nearest.rect);
            if (tickCounter % 4 == 0) s.environment = dev.windowscraft.audio.AcousticsProbe.probe(plane, ear);
            double vol = master * s.localVolume;
            dev.windowscraft.audio.AcousticsEngine.get().update(s.localSound.id,
                    new dev.windowscraft.audio.AcousticModel.SourceState(plane, (float) vol, vol <= 0, s.environment));
            s.localSound.setVolume(vol);
        }
    }

    /**
     * Direct GPU sharing (Windows): start the capture helper for this share when it's wanted and
     * possible; stop it when switched off; let the CPU capture rest while it feeds the wall.
     */
    private void tickGpuSharing(Share s) {
        boolean want = dev.windowscraft.render.GpuInterop.wanted() && dev.windowscraft.capture.GpuShare.supported(s.source) && !s.gpuFailed;
        int fps = s.session.settings().fps();
        if (dev.windowscraft.monitor.InGameMonitors.isInGameMonitor(s.source)) fps = Math.max(60, fps); // (your own screen: every picture)
        if (s.gpu != null && (!want || fps != s.gpuFps)) {
            s.gpu.stop();
            s.gpu = null;
            s.texture().dropGpu();
        }
        if (want && s.gpu == null && dev.windowscraft.render.GpuInterop.ensure()) {
            s.gpu = new dev.windowscraft.capture.GpuShare(s.source);
            s.gpuFps = fps;
            s.gpuSentFps = fps;
            s.gpu.start(fps, false, dev.windowscraft.render.GpuInterop.luid()); // (an in-game monitor's pointer is drawn live on your wall)
        }
        if (s.publisher != null) {
            // your wall reads the window itself (direct GPU sharing or Graphics Capture): FFmpeg
            // needn't send a copy back (that copy arrives late)
            s.publisher.setWallFedElsewhere(() -> s.gpuFeeding() || s.session.wgcActive());
            s.publisher.setWallStarting(() -> s.gpu != null && !s.gpuFailed && !s.gpuFeeding() && s.gpu.failure() == null);
        }
        // where the app's content is in the stream: the GPU stream shows the whole window, fitted in
        if (s.publisher != null) {
            boolean whole = s.source.kind() == SourceInfo.Kind.WINDOW && s.publisher.gpuActive();
            s.publisher.setBox(whole ? dev.windowscraft.client.Box.frameFit(s.publisher.streamWidth(), s.publisher.streamHeight(),
                    dev.windowscraft.capture.win.WinGeom.geometry(s.source.handle())) : dev.windowscraft.client.Box.FULL);
        }
        // performance settings: nothing on screen → (almost) no captures for your wall; small on
        // screen → at most 15 a second. The stream to other players is never slowed down.
        boolean cpuStreamNeedsFrames = s.publisher != null && s.publisher.streaming() && !s.publisher.gpuActive();
        boolean hidden = dev.windowscraft.config.ClientSettings.offscreenPause() && !s.seenRecently();
        boolean far = dev.windowscraft.config.ClientSettings.distanceFpsLimit() && s.viewPx() < dev.windowscraft.render.DisplayRenderer.FAR_PX;
        int wallFps = hidden ? 2 : far ? Math.min(fps, 15) : fps;
        if (s.gpu != null && wallFps != s.gpuSentFps) {
            s.gpu.setFps(wallFps);
            s.gpuSentFps = wallFps;
        }
        // the CPU capture can rest unless a CPU stream pipeline needs its frames
        s.session.setGpuRest((s.gpuFeeding() || hidden) && !cpuStreamNeedsFrames);
        s.session.setFpsCap(far && !cpuStreamNeedsFrames ? 15 : 0);
    }

    /** Render thread: free textures of shares that ended since last frame. */
    public void releasePendingTextures() {
        if (pendingRelease.isEmpty()) return;
        for (Share s : pendingRelease) s.releaseTexture();
        pendingRelease.clear();
    }
}
