package dev.windowscraft.display;

import dev.windowscraft.capture.CaptureSession;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceDescriptor;
import dev.windowscraft.stream.StreamPublisher;
import java.util.UUID;
import net.minecraft.util.Identifier;

/** One screen on a wall: its geometry (persisted) plus the share feeding it, if live (not persisted). */
public final class Display {
    public final UUID id;
    /** (A tablet's follows you; a wall's never changes.) */
    public volatile String dimension;
    /**
     * Your tablet's screen: in your hand, not on a wall. Its rect follows you around (so its sound
     * comes from you); it's never drawn in the world, never sent to the server and never saved.
     */
    public boolean tablet;
    /** Where it is; follows the wall if its blocks change (see DisplayManager#tick). */
    public volatile WallRect rect;
    public SourceDescriptor lastSource;
    public CaptureSettings lastSettings;
    /**
     * Players who can use this screen with their mouse and keyboard right now (id → name), worked
     * out from {@link #access} — what the server is told. Nobody else can.
     */
    public final java.util.Map<UUID, String> controllers = new java.util.LinkedHashMap<>();

    /** Who can use it: only you, the people you picked, or everyone except the people you blocked. */
    public static final int ACCESS_ME = 0, ACCESS_PICKED = 1, ACCESS_ALL_BUT = 2;
    public int access = ACCESS_PICKED;
    /** The people you picked (for {@link #ACCESS_PICKED}) and the ones you blocked (for {@link #ACCESS_ALL_BUT}). */
    public final java.util.Map<UUID, String> picked = new java.util.LinkedHashMap<>(), blocked = new java.util.LinkedHashMap<>();
    /** The size you chose for its in-game monitor (0: the usual 1920 × 1080). */
    public int monitorW, monitorH;
    /** In-game monitor: apps started at it run as its own copies (its own Sandboxie box). */
    /** Its name, as you named it (null: the usual "In-game monitor 2", "Wall 3"). Remembered with its settings. */
    public volatile String name;
    public boolean ownCopies = true;
    /** With own copies: its apps start from your desk's sign-ins each time (its box emptied once they've all closed). */
    public boolean followDesk = true;
    /**
     * This screen's own performance picks, where it isn't like all screens (setting key →
     * "true"/"false"; see ClientSettings.on(Auto, Display)). Absent: like all screens.
     */
    public final java.util.Map<String, String> overrides = new java.util.TreeMap<>();

    // runtime only
    Share share;
    String endedMessage;
    boolean endNotified;

    public Display(UUID id, String dimension, WallRect rect) {
        this.id = id;
        this.dimension = dimension;
        this.rect = rect;
    }

    public CaptureSession session() { return share == null ? null : share.session; }
    public StreamPublisher publisher() { return share == null ? null : share.publisher; }
    public Share share() { return share; }
    public boolean isLive() { return share != null && !share.session.isEnded(); }
    public String endedMessage() { return endedMessage; }

    /** How this wall shows its picture (Auto / Fill / Fit); each mirrored wall can differ. */
    public int fit() { return lastSettings == null ? CaptureSettings.FIT_AUTO : lastSettings.fit(); }

    /** Render thread: the texture to draw, or null if there's no picture yet. */
    public Identifier pollTexture(long renderFrame) { return share == null ? null : share.pollTexture(renderFrame); }

    /** Render thread: as above, or (upload false) the last picture again without any work. */
    public Identifier pollTexture(long renderFrame, boolean upload) { return share == null ? null : share.pollTexture(renderFrame, upload); }

    /** Render thread: this wall was (not) on screen this frame, this many pixels tall. */
    public void noteView(boolean seen, double px) { if (share != null) share.noteView(seen, px); }

    public int textureWidth() { return share == null ? 0 : share.textureWidth(); }
    public int textureHeight() { return share == null ? 0 : share.textureHeight(); }

    StreamPublisher.Target target() { return new StreamPublisher.Target(id, dimension, rect, fit(), java.util.List.copyOf(controllers.keySet())); }
}
