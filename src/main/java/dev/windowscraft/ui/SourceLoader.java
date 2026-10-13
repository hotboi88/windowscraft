package dev.windowscraft.ui;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureBackend;
import dev.windowscraft.capture.CaptureBackends;
import dev.windowscraft.capture.Frame;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.render.CaptureTexture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * Background worker for the share dialog: lists windows and screens and grabs small live
 * previews every couple of seconds, entirely off the game thread. (PrintWindow can block on a
 * hung app, which is exactly why this must never run on the render thread.)
 */
final class SourceLoader {
    static final int THUMB_W = 384, THUMB_H = 216;
    private static final long REFRESH_NANOS = 2_000_000_000L;
    private static int textureSeq;

    /** One pickable source plus its preview textures (textures are touched on the render thread only). */
    static final class Entry {
        volatile SourceInfo info;
        volatile Frame pendingThumb, pendingIcon;
        private CaptureTexture thumb, icon;
        private Identifier thumbId, iconId;
        boolean iconRequested;

        Entry(SourceInfo info) { this.info = info; }

        /** Render thread: upload any freshly captured previews. */
        void sync() {
            Frame t = pendingThumb;
            if (t != null) {
                pendingThumb = null;
                if (thumb == null) {
                    thumb = new CaptureTexture(false);
                    thumbId = Identifier.of("windowscraft", "thumb/" + (textureSeq++));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(thumbId, thumb);
                }
                thumb.upload(t);
            }
            Frame i = pendingIcon;
            if (i != null) {
                pendingIcon = null;
                if (icon == null) {
                    icon = new CaptureTexture(false);
                    iconId = Identifier.of("windowscraft", "icon/" + (textureSeq++));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(iconId, icon);
                }
                icon.upload(i);
            }
        }

        Identifier thumbId() { return thumb != null && thumb.hasContent() ? thumbId : null; }
        int thumbW() { return thumb == null ? 0 : thumb.width(); }
        int thumbH() { return thumb == null ? 0 : thumb.height(); }
        Identifier iconId() { return icon != null && icon.hasContent() ? iconId : null; }

        void release() {
            var tm = MinecraftClient.getInstance().getTextureManager();
            if (thumbId != null) tm.destroyTexture(thumbId);
            if (iconId != null) tm.destroyTexture(iconId);
            thumb = icon = null;
            thumbId = iconId = null;
        }
    }

    final CaptureBackend backend = CaptureBackends.get();
    volatile List<Entry> windows = List.of();
    volatile List<Entry> screens = List.of();
    volatile boolean loadedOnce;
    private final List<Entry> retired = new ArrayList<>();
    private volatile boolean running = true;
    private volatile boolean preferScreens;
    private final Thread thread;

    SourceLoader() {
        thread = new Thread(this::run, "WindowsCraft source list");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void setPreferScreens(boolean screensTab) { preferScreens = screensTab; LockSupport.unpark(thread); }

    void close() {
        running = false;
        LockSupport.unpark(thread);
    }

    /** Render thread: upload previews and free textures of sources that went away. */
    void syncTextures(boolean all) {
        for (Entry e : windows) e.sync();
        for (Entry e : screens) e.sync();
        synchronized (retired) {
            for (Entry e : retired) e.release();
            retired.clear();
        }
        if (all) {
            for (Entry e : windows) e.release();
            for (Entry e : screens) e.release();
        }
    }

    private void run() {
        while (running) {
            long start = System.nanoTime();
            try {
                refreshLists();
                loadedOnce = true;
                List<Entry> first = preferScreens ? screens : windows, second = preferScreens ? windows : screens;
                captureThumbs(first);
                captureThumbs(second);
            } catch (Throwable t) {
                WindowsCraftClient.LOGGER.warn("Refreshing share sources failed", t);
                loadedOnce = true;
            }
            long wait = REFRESH_NANOS - (System.nanoTime() - start);
            if (running && wait > 0) LockSupport.parkNanos(wait);
        }
    }

    private void refreshLists() {
        List<SourceInfo> ws = backend.isSupported() ? backend.listWindows() : List.of();
        List<SourceInfo> ss = new ArrayList<>(backend.isSupported() ? backend.listScreens() : List.of());
        ss.add(SourceInfo.testPattern());
        windows = merge(windows, ws);
        screens = merge(screens, ss);
    }

    private List<Entry> merge(List<Entry> old, List<SourceInfo> fresh) {
        Map<String, Entry> byId = new HashMap<>();
        for (Entry e : old) byId.put(e.info.id(), e);
        List<Entry> out = new ArrayList<>(fresh.size());
        for (SourceInfo s : fresh) {
            Entry e = byId.remove(s.id());
            if (e == null) e = new Entry(s); else e.info = s;
            out.add(e);
        }
        synchronized (retired) { retired.addAll(byId.values()); }
        return out;
    }

    private void captureThumbs(List<Entry> list) {
        for (Entry e : list) {
            if (!running) return;
            try {
                Frame f = CaptureBackends.thumbnail(e.info, THUMB_W, THUMB_H);
                if (f != null) e.pendingThumb = f;
                if (!e.iconRequested && e.info.kind() == SourceInfo.Kind.WINDOW) {
                    e.iconRequested = true;
                    Frame icon = backend.icon(e.info);
                    if (icon != null) e.pendingIcon = icon;
                }
            } catch (Throwable t) {
                // one uncooperative window shouldn't stop the others
            }
        }
    }
}
