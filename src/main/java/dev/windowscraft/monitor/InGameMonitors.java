package dev.windowscraft.monitor;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.CaptureSettings;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.client.TheaterMode;
import dev.windowscraft.config.ClientSettings;
import dev.windowscraft.display.Display;
import dev.windowscraft.display.DisplayManager;
import dev.windowscraft.display.Share;
import dev.windowscraft.display.WallRect;
import dev.windowscraft.monitor.MonitorBackend.Driver;
import dev.windowscraft.monitor.MonitorBackend.Mon;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * In-game monitors: U on a black concrete wall makes a new monitor that exists only inside
 * Windows and shows it on the wall; U again (or breaking the wall, leaving the world, closing
 * the game) removes it, and anything that was on it goes back to your desk. Sitting at one (the
 * theater key) hands it your real mouse and keyboard, so any app or game works on it.
 *
 * <p>Client thread, except where noted. The monitors are made by a small helper with a display
 * driver that drops every monitor by itself within a second if the helper stops (crash-safe).
 */
public final class InGameMonitors {
    public static final int WIDTH = 1920, HEIGHT = 1080, HZ = 60;
    /** The driver installer: Parsec's free, signed virtual display driver (from Parsec's own server), checked byte for byte. */
    static final String DRIVER_URL = "https://builds.parsec.app/vdd/parsec-vdd-0.41.0.0.exe";
    static final String DRIVER_SHA256 = "317977c5abd58f1c3f7b27ef7f96947efd07e6d35b27e415cea5daa6007629b1";

    private static MonitorBackend backend;
    private static boolean looked;
    /** Live monitors, by the id of the source showing them. Read from capture threads too. */
    private static final Map<String, Mon> LIVE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> NUMBER = new ConcurrentHashMap<>();
    private static final Set<UUID> STARTING = new HashSet<>();
    private static final Set<String> RESTARTING = new HashSet<>();
    private static final Map<UUID, List<Long>> REVIVED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> MISSES = new ConcurrentHashMap<>();
    private static volatile Driver driver;

    // ---- which backend --------------------------------------------------------------------------

    public static synchronized MonitorBackend backend() {
        if (looked) return backend;
        looked = true;
        if (Boolean.getBoolean("windowscraft.fakeMonitors") && FabricLoader.getInstance().isDevelopmentEnvironment()) {
            backend = new FakeMonitorBackend();
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            backend = new dev.windowscraft.capture.win.VDisplayHelper();
        }
        if (backend != null) backend.events(new SeatEvents());
        return backend;
    }

    /** Tests: the game window counts as focused (a headless test window may never be). */
    private static boolean testing;

    private static boolean focused(MinecraftClient mc) { return testing || mc.isWindowFocused(); }

    /** Tests: use this backend from now on. */
    public static synchronized void useForTesting(MonitorBackend b) {
        looked = true;
        backend = b;
        testing = b != null;
        if (b != null) b.events(new SeatEvents());
    }

    /** Can this PC make in-game monitors at all (Windows)? */
    public static boolean supported() { return backend() != null; }

    public static boolean isInGameMonitor(SourceInfo s) { return s != null && s.id().startsWith("vmon:"); }

    /** The monitor a source shows (any thread), or null. */
    public static Mon monitorOf(SourceInfo s) { return s == null ? null : LIVE.get(s.id()); }

    public static Mon monitorOf(Display d) { return d == null || d.share() == null ? null : monitorOf(d.share().source()); }

    /** Is this wall showing one of your in-game monitors? */
    public static boolean isOn(Display d) { return d != null && d.isLive() && isInGameMonitor(d.share().source()); }

    public static boolean starting(Display d) { return d != null && STARTING.contains(d.id); }

    /** Is any in-game monitor on its way on right now? */
    public static boolean anyStarting() { return !STARTING.isEmpty(); }

    /** Is that Windows display (\\.\DISPLAYn) one of ours? (Any thread: so pickers never offer it as a screen of yours.) */
    public static boolean ownsDevice(String device) {
        for (Mon m : LIVE.values()) if (m.device().equalsIgnoreCase(device)) return true;
        return false;
    }

    public static List<Mon> all() { return new ArrayList<>(LIVE.values()); }

    /** Which in-game monitor (its source id) that desktop spot is on, or null. */
    public static String sourceIdAt(int x, int y) {
        for (Map.Entry<String, Mon> e : LIVE.entrySet()) {
            Mon m = e.getValue();
            if (x >= m.left() && x < m.left() + m.width() && y >= m.top() && y < m.top() + m.height()) return e.getKey();
        }
        return null;
    }

    public static String name(SourceInfo s) {
        Integer n = s == null ? null : NUMBER.get(s.id());
        return "In-game monitor" + (n == null ? "" : " " + n);
    }

    /** The driver's state (asks the helper; cached for the screens). */
    public static CompletableFuture<Driver> driver() {
        MonitorBackend b = backend();
        if (b == null) return CompletableFuture.completedFuture(Driver.UNSUPPORTED);
        return b.status().exceptionally(t -> Driver.ERROR).thenApply(d -> driver = d);
    }

    /** Last known driver state (null: not asked yet). */
    public static Driver knownDriver() { return driver; }

    // ---- on and off -------------------------------------------------------------------------------

    /**
     * U on a wall: a new monitor on it (the wall made first if it's new). If the driver isn't there
     * yet, the setup screen explains and offers to install it.
     */
    public static void turnOn(MinecraftClient mc, String dimension, WallRect rect, Display existing) {
        MonitorBackend b = backend();
        if (b == null) {
            say(mc, "In-game monitors need Windows", Formatting.YELLOW);
            return;
        }
        if (existing != null && STARTING.contains(existing.id)) {
            hint(mc, "Turning on an in-game monitor… (still going)");
            return;
        }
        // (U always answers: if the helper takes a moment, you see that it's on its way)
        CompletableFuture<Driver> asked = driver();
        CompletableFuture.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (!asked.isDone()) mc.execute(() -> hint(mc, "Starting the in-game monitor helper…"));
        });
        asked.thenAccept(st -> mc.execute(() -> {
            if (st != Driver.OK) {
                mc.setScreen(new dev.windowscraft.ui.MonitorSetupScreen(dimension, rect, existing, st));
                return;
            }
            Display d = existing != null && DisplayManager.get().all().contains(existing) ? existing : DisplayManager.get().create(dimension, rect);
            if (STARTING.contains(d.id)) return;
            if (d.isLive()) DisplayManager.get().stop(d); // it was showing something else
            STARTING.add(d.id);
            hint(mc, "Turning on an in-game monitor…");
            b.add(d.monitorW > 0 ? d.monitorW : WIDTH, d.monitorW > 0 ? d.monitorH : HEIGHT, HZ).whenComplete((m, err) -> mc.execute(() -> {
                STARTING.remove(d.id);
                if (err != null) {
                    Throwable why = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
                    WindowsCraftClient.LOGGER.warn("In-game monitor couldn't be made", why);
                    say(mc, "Couldn't turn on the in-game monitor: " + why.getMessage(), Formatting.YELLOW);
                    // the driver got into trouble? then the setup screen offers to repair it
                    driver().thenAccept(now -> mc.execute(() -> {
                        if (now != Driver.OK && mc.currentScreen == null)
                            mc.setScreen(new dev.windowscraft.ui.MonitorSetupScreen(dimension, rect, existing, now));
                    }));
                    return;
                }
                if (!DisplayManager.get().all().contains(d) || d.isLive() || mc.world == null) {
                    b.remove(m); // the wall went away (or got something else) meanwhile
                    return;
                }
                int n = freeNumber();
                SourceInfo src = sourceFor(m, n);
                LIVE.put(src.id(), m);
                NUMBER.put(src.id(), n);
                DisplayManager.get().start(d, src, settingsFor(d));
                if (d.tablet) hint(mc, "Your tablet is on");
                else hint(mc, name(src) + " is on · sit down with " + TheaterMode.keyName() + " to use it · sneak + "
                        + WindowsCraftClient.keyName() + " for its menu");
                refreshRects(mc);
            }));
        }));
    }

    /** U on a live in-game monitor: off (its windows go back to your desk). */
    public static void turnOff(Display d) {
        DisplayManager.get().stop(d);
    }

    /** Turns off every in-game monitor. */
    public static void turnOffAll() {
        for (Share s : DisplayManager.get().liveShares()) if (isInGameMonitor(s.source())) DisplayManager.get().stopShare(s);
    }

    /** A share ended (DisplayManager): if it showed an in-game monitor, that monitor goes away. */
    public static void released(SourceInfo s) {
        if (!isInGameMonitor(s) || RESTARTING.contains(s.id())) return;
        Mon m = LIVE.remove(s.id());
        NUMBER.remove(s.id());
        if (m == null) return;
        if (seatedMon != null && seatedMon.index() == m.index()) unseat();
        MonitorBackend b = backend();
        if (b != null) b.remove(m).whenComplete((v, e) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> refreshRects(mc));
        });
    }

    /**
     * Game start: if the game closed unexpectedly while a monitor was on, Windows' taskbar setting
     * it changed is put back (the helper does that as soon as it starts).
     */
    public static void recoverAtStartup() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            if (com.sun.jna.platform.win32.Advapi32Util.registryKeyExists(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                    "Software\\WindowsCraft\\TaskbarBackup") && backend() != null) backend().status();
        } catch (Throwable t) {
            WindowsCraftClient.LOGGER.debug("taskbar recovery check", t);
        }
    }

    /** The game is closing: every monitor goes (the helper puts windows back first). Any thread. */
    public static void shutdown() {
        MonitorBackend b;
        synchronized (InGameMonitors.class) { b = backend; }
        LIVE.clear();
        restorePause();
        if (b != null) b.shutdown();
    }

    private static int freeNumber() {
        Set<Integer> used = new HashSet<>(NUMBER.values());
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    static SourceInfo sourceFor(Mon m, int n) {
        String id = "vmon:" + m.index() + ":" + m.device();
        String title = "In-game monitor " + n;
        String size = m.width() + "×" + m.height();
        if (backend() instanceof FakeMonitorBackend) {
            return new SourceInfo(SourceInfo.Kind.TEST, id, title, size, 0, m.left(), m.top(), m.width(), m.height(), "", false);
        }
        return new SourceInfo(SourceInfo.Kind.SCREEN, id, title, size, 0, m.left(), m.top(), m.width(), m.height(), "", false);
    }

    private static CaptureSettings settingsFor(Display d) {
        CaptureSettings s = d.lastSettings != null ? d.lastSettings : ClientSettings.get();
        // sound comes from the wall (the apps go quiet on your speakers), like an app share
        return new CaptureSettings(s.maxHeight(), s.fps(), false, s.audio(), s.fit(), s.hearInGame());
    }

    /**
     * Windows may move monitors around when another comes or goes: a wall whose monitor moved
     * restarts its capture on the new spot (the monitor itself, and its apps, stay as they are).
     */
    static void refreshRects(MinecraftClient mc) {
        MonitorBackend b = backend();
        if (b == null || LIVE.isEmpty()) return;
        b.rects().thenAccept(list -> mc.execute(() -> {
            for (Map.Entry<String, Mon> e : new ArrayList<>(LIVE.entrySet())) {
                Mon old = e.getValue();
                Mon fresh = list.stream().filter(x -> x.index() == old.index()).findFirst().orElse(null);
                boolean mirrored = fresh != null && fresh.width() == 0;
                int missed = fresh == null ? MISSES.merge(e.getKey(), 1, Integer::sum) : 0;
                if (fresh != null) MISSES.remove(e.getKey());
                if (mirrored || missed >= 2) {
                    // Windows is mirroring it onto one of your real screens (never shown), or it's gone
                    WindowsCraftClient.LOGGER.warn("In-game monitor {} isn't a monitor of its own any more ({}): turning it off", old,
                            mirrored ? "mirrored onto a real screen" : "missing");
                    MISSES.remove(e.getKey());
                    for (Share s : DisplayManager.get().liveShares()) if (s.source().id().equals(e.getKey())) DisplayManager.get().stopShare(s);
                    continue;
                }
                if (fresh == null) continue; // Windows may be rearranging: look again next time
                if (old.equals(fresh)) continue;
                WindowsCraftClient.LOGGER.info("In-game monitor {} moved: {} → {}", fresh.index(), old, fresh);
                LIVE.put(e.getKey(), fresh);
                if (seatedMon != null && seatedMon.index() == fresh.index()) seatedMon = fresh;
                restartShares(e.getKey(), sourceFor(fresh, NUMBER.getOrDefault(e.getKey(), 1)));
            }
        })).exceptionally(t -> null);
    }

    private static void restartShares(String id, SourceInfo fresh) {
        for (Share s : DisplayManager.get().liveShares()) {
            if (!s.source().id().equals(id)) continue;
            RESTARTING.add(id);
            try {
                for (Display d : new ArrayList<>(s.displays())) {
                    CaptureSettings settings = d.lastSettings != null ? d.lastSettings : ClientSettings.get();
                    DisplayManager.get().stop(d);
                    DisplayManager.get().start(d, fresh, settings);
                }
            } finally {
                RESTARTING.remove(id);
            }
        }
    }

    // ---- moving windows ------------------------------------------------------------------------------

    public static CompletableFuture<List<MonitorBackend.Win>> windowsOn(Display d) {
        Mon m = monitorOf(d);
        MonitorBackend b = backend();
        if (m == null || b == null) return CompletableFuture.completedFuture(List.of());
        return b.windowsOn(m).exceptionally(t -> List.of());
    }

    public static CompletableFuture<List<MonitorBackend.Win>> deskWindows() {
        MonitorBackend b = backend();
        if (b == null) return CompletableFuture.completedFuture(List.of());
        return b.deskWindows().exceptionally(t -> List.of());
    }

    public static void bring(long hwnd, Display d) {
        Mon m = monitorOf(d);
        if (m == null || backend() == null) return;
        guardFocus();
        backend().bring(hwnd, m);
    }

    private static int focusHolds;
    private static boolean focusSavedPause;

    /**
     * For a few seconds while an app starts or moves: nothing may take the keyboard from the game
     * (the game locks it), and if something still does, the game doesn't pause or drop your mouse.
     */
    private static void guardFocus() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (focusHolds++ == 0) {
            // your own setting (sitting or pointing may be holding it off already)
            focusSavedPause = savedPause != null ? savedPause : mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;
        }
        dev.windowscraft.capture.win.WinOps.lockForeground(true);
        CompletableFuture.delayedExecutor(6, java.util.concurrent.TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
            if (focusHolds > 0 && --focusHolds == 0) {
                dev.windowscraft.capture.win.WinOps.lockForeground(false);
                if (savedPause == null) mc.options.pauseOnLostFocus = focusSavedPause; // (unless you're sitting at a monitor)
                else savedPause = focusSavedPause;
            }
        }));
    }

    public static void close(long hwnd) {
        if (backend() != null) backend().close(hwnd);
    }

    /** Browsers that can run a separate copy of themselves (their own sign-ins, their own sound). */
    private static final java.util.Set<String> OWN_COPY = java.util.Set.of("chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "opera.exe", "firefox.exe");
    private static final Map<Long, String> EXE_OF = new java.util.concurrent.ConcurrentHashMap<>();

    /** The program file of that process ("" if unknown). */
    public static String exeOf(long pid) {
        if (pid <= 0) return "";
        return EXE_OF.computeIfAbsent(pid, p -> ProcessHandle.of(p).flatMap(h -> h.info().command()).orElse(""));
    }

    /** Can that app have a copy of its own on a monitor? Its name if so ("Brave"), else null. */
    public static String ownCopyName(long pid) {
        String exe = exeOf(pid);
        String file = exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).toLowerCase(java.util.Locale.ROOT);
        if (!OWN_COPY.contains(file)) return null;
        return switch (file) {
            case "chrome.exe" -> "Chrome";
            case "msedge.exe" -> "Edge";
            case "brave.exe" -> "Brave";
            case "vivaldi.exe" -> "Vivaldi";
            case "opera.exe" -> "Opera";
            default -> "Firefox";
        };
    }

    /**
     * Opens a separate copy of that browser on this monitor: a process of its own (so its sound
     * is this monitor's alone, and it closes with the monitor), with its own sign-ins and tabs,
     * kept for this monitor next time.
     */
    public static CompletableFuture<Boolean> openOwnCopy(Display d, long pid) {
        Mon m = monitorOf(d);
        String exe = exeOf(pid);
        if (m == null || backend() == null || exe.isEmpty() || ownCopyName(pid) == null) return CompletableFuture.completedFuture(false);
        String file = exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1).toLowerCase(java.util.Locale.ROOT);
        String base = System.getenv("LOCALAPPDATA");
        java.nio.file.Path dir = java.nio.file.Path.of(base != null ? base : System.getProperty("user.home"), "WindowsCraft", "profiles",
                file.replace(".exe", "") + "-" + (d.tablet ? "tablet" : d.id.toString().substring(0, 8)));
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            return CompletableFuture.completedFuture(false);
        }
        String params = file.equals("firefox.exe")
                ? "-no-remote -new-instance -profile \"" + dir + "\""
                : "--user-data-dir=\"" + dir + "\" --no-first-run --no-default-browser-check --new-window";
        guardFocus();
        return backend().launch(m, exe, params, file);
    }

    public static void sendHome(long hwnd) {
        if (backend() != null) backend().sendHome(hwnd);
    }

    // ---- sitting at one: your real mouse and keyboard work it -----------------------------------------

    private static volatile Mon seatedMon;
    /** Alt held at the monitor: looking around (the real pointer waits in the middle meanwhile). */
    private static volatile boolean altLooking;
    private static Display seatedOn;
    private static Boolean savedPause;
    private static long relockUntil;

    /** You're sitting at this in-game monitor (your real mouse and keyboard are on it). */
    public static Display seated() { return seatedMon != null ? seatedOn : null; }

    // ---- pointing at one: your real cursor is on it while you keep playing ---------------------------
    //
    // Alt-pointing at your own in-game monitor, or someone you allowed using it while you're not
    // sitting there: the real Windows cursor goes onto the monitor (so apps get real hovering,
    // clicks and scrolling, and the wall shows your real cursor), the game keeps the keyboard,
    // and your mouse keeps turning your view.

    private static volatile Mon pointMon;
    private static Display pointOn;
    /** You're the one pointing (Alt): your clicks reach the monitor. Otherwise a guest is. */
    private static volatile boolean pointMe;
    /** The helper confirmed: the cursor is there, your mouse turns your view through it. */
    private static volatile boolean pointReady;
    private static long unfocusedSince, pointRetryAt;
    private static double[] lastCursor = {0.5, 0.5};

    public static Display pointing() { return pointMon != null ? pointOn : null; }

    /** Are you Alt-pointing at one of your in-game monitors? */
    public static boolean ownerPointing() { return pointMon != null && pointMe; }

    /** Pointing is up and running (the helper turns your view from your mouse; Alt was let go for the apps). */
    public static boolean pointReady() { return pointMon != null && pointReady; }

    /** Is your real Windows cursor on that wall's monitor right now (sitting there, or pointing at it)? */
    public static boolean realCursorOn(Display d) { return d != null && (seated() == d || pointing() == d); }

    /** Starts (or keeps) the real cursor on that monitor. False: it can't be (then input goes the old way). */
    private static boolean ensurePoint(Display d, boolean me, double u, double v) {
        Mon m = monitorOf(d);
        MonitorBackend b = backend();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (m == null || b == null) return false;
        if (seatedMon != null) return seatedOn == d; // sitting: the cursor is yours there already (or elsewhere)
        if (pointOn == d && pointMon != null) {
            lastCursor = new double[]{u, v};
            if (me && !pointMe) {
                pointMe = true;
                if (pointReady) b.pointMe(true);
            }
            return true;
        }
        // you're pointing at another of your monitors yourself: theirs goes the old way meanwhile
        if (!me && pointMon != null && pointMe) return false;
        // never take the cursor while you're in a menu or another window has it; after a failure, wait a bit
        if (mc.currentScreen != null || !focused(mc) || System.nanoTime() < pointRetryAt) return false;
        if (pointMon != null) endPoint();
        lastCursor = new double[]{u, v};
        pointMon = m;
        pointOn = d;
        pointMe = me;
        pointReady = false;
        unfocusedSince = 0;
        holdPause(mc);
        b.point(m, dev.windowscraft.capture.win.WinGeom.gameWindow, u, v);
        return true;
    }

    /** The cursor goes back to the game. */
    public static void endPoint() {
        if (pointMon == null) return;
        boolean wasReady = pointReady;
        pointMon = null;
        pointOn = null;
        pointMe = false;
        pointReady = false;
        MonitorBackend b = backend();
        if (b != null) b.unpoint();
        if (seatedMon == null) restorePause();
        // the game takes the mouse again properly (its hold on the cursor was let go meanwhile)
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wasReady && mc.currentScreen == null && mc.isWindowFocused() && !testing) {
            mc.mouse.unlockCursor();
            mc.mouse.lockCursor();
        }
    }

    /** Alt-pointing at your own in-game monitor: the real cursor goes to (u, v) of it. */
    public static void ownerPoint(Display d, double u, double v) {
        if (!ensurePoint(d, true, u, v)) return;
        MonitorBackend b = backend();
        if (b != null) b.cursorTo(u, v);
    }

    /** You stopped Alt-pointing (looked away, or let go of Alt). */
    public static void ownerStopPointing() {
        if (pointMon == null || !pointMe) return;
        pointMe = false;
        MonitorBackend b = backend();
        if (b != null) b.pointMe(false);
        if (!dev.windowscraft.client.ScreenControl.hasGuests(pointOn)) endPoint();
    }

    /** Someone you allowed points at (u, v) of your monitor: your real cursor goes there (shared, like one mouse). */
    public static boolean guestCursor(Display d, double u, double v) {
        if (!ensurePoint(d, false, u, v)) return false;
        MonitorBackend b = backend();
        if (b != null) b.cursorTo(u, v);
        return true;
    }

    public static void guestClick(int button, boolean down) {
        MonitorBackend b = backend();
        if (b != null) b.click(button, down);
    }

    public static void guestWheel(double notches) {
        MonitorBackend b = backend();
        if (b != null) b.wheel(notches);
    }

    /** A guest's key while you sit at that monitor: typed as if on your keyboard. */
    public static void guestKey(int scancode, boolean down) {
        MonitorBackend b = backend();
        if (b != null) b.key(scancode, down);
    }

    /** Nobody uses it any more (your guests left and you aren't pointing): the cursor comes back. */
    public static void guestsGone(Display d) {
        guestRelease();
        if (pointOn == d && !pointMe) endPoint();
    }

    /** Lets go of every button and key a guest still holds on your monitors. */
    public static void guestRelease() {
        MonitorBackend b = backend();
        if (b != null) b.release();
    }

    private static void holdPause(MinecraftClient mc) {
        // the game keeps going when a click on the monitor briefly takes the keyboard
        if (savedPause == null) savedPause = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
    }

    /** Sat down at an in-game monitor: the pointer goes where the wall's pointer is. Returns false if it isn't one. */
    public static boolean seat(Display d, double u, double v) {
        Mon m = monitorOf(d);
        MonitorBackend b = backend();
        if (m == null || b == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        pointMon = null; // sitting takes over from pointing (the helper ends it)
        pointOn = null;
        pointMe = false;
        // the game keeps going while the monitor has the keyboard: no pause menu, and a
        // fullscreen game stays on screen instead of minimizing
        if (savedPause == null) savedPause = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        try {
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY, org.lwjgl.glfw.GLFW.GLFW_FALSE);
        } catch (Throwable ignored) {
        }
        seatedMon = m;
        seatedOn = d;
        altLooking = false;
        b.seat(m, u, v, dev.windowscraft.capture.win.WinGeom.gameWindow).whenComplete((r, e) -> mc.execute(() -> {
            if ("ok".equals(r) || seatedOn != d) return;
            String why = e != null ? e.getMessage() : r;
            WindowsCraftClient.LOGGER.warn("Couldn't hand the mouse and keyboard to the in-game monitor: {}", why);
            unseat();
            TheaterMode.leaveNow();
            dev.windowscraft.client.Tablet.lower();
            say(mc, "Couldn't hand your mouse and keyboard to the in-game monitor: " + why, Formatting.YELLOW);
        }));
        return true;
    }

    /** Got up: the mouse and keyboard come back to the game. */
    public static void unseat() {
        if (seatedMon == null) return;
        seatedMon = null;
        seatedOn = null;
        MonitorBackend b = backend();
        if (b != null) b.unseat();
        // the pause-when-you-leave setting comes back only once the game has your keyboard again
        // (getting up — Esc closing Start, say — mustn't count as you leaving the game)
        relockUntil = System.nanoTime() + 3_000_000_000L;
        gotUpAt = System.nanoTime();
    }

    private static long gotUpAt;

    /** You got up from a monitor (or tablet) a moment ago. */
    public static boolean justGotUp() { return gotUpAt != 0 && System.nanoTime() - gotUpAt < 700_000_000L; }

    private static void restorePause() {
        if (savedPause == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.pauseOnLostFocus = savedPause;
        savedPause = null;
        try {
            org.lwjgl.glfw.GLFW.glfwSetWindowAttrib(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_AUTO_ICONIFY, org.lwjgl.glfw.GLFW.GLFW_TRUE);
        } catch (Throwable ignored) {
        }
    }

    /** Where your real pointer is on the monitor you sit at (0..1), or null. */
    public static double[] pointerOnSeat() {
        Mon m = seatedMon;
        if (m == null || altLooking) return null;
        int[] p = dev.windowscraft.capture.win.WinGeom.cursorPos();
        if (p == null) return null;
        return new double[]{(p[0] - m.left() + 0.5) / m.width(), (p[1] - m.top() + 0.5) / m.height()};
    }

    /** Client tick. */
    private static int ticks;

    public static void tick(MinecraftClient mc) {
        MonitorBackend b = backend;
        if (b != null && (seatedMon != null || pointMon != null)) b.beat(); // "the game is still here" (a frozen game never keeps your mouse)
        if (pointMon != null) {
            // you opened a menu, or switched to another window for real: your cursor is yours again
            if (!focused(mc)) {
                if (unfocusedSince == 0) unfocusedSince = System.nanoTime();
            } else unfocusedSince = 0;
            boolean away = unfocusedSince != 0 && System.nanoTime() - unfocusedSince > 1_500_000_000L;
            if (mc.currentScreen != null || away || !isOn(pointOn)) endPoint();
        }
        if (b != null && !LIVE.isEmpty() && ++ticks % 40 == 0) refreshRects(mc); // Windows may have moved them
        if (b != null && ticks % 10 == 5) arrangeIfChanged(b);
        // back from the monitor: the game takes the mouse again (once Windows hands it the keyboard)
        if (relockUntil != 0) {
            boolean back = mc.isWindowFocused();
            if (back || System.nanoTime() > relockUntil) {
                if (back && mc.currentScreen == null) mc.mouse.lockCursor();
                relockUntil = 0;
                if (seatedMon == null && pointMon == null) restorePause();
            }
        }
    }

    private static String lastArrangement = "";

    /** Joined monitors in the world are joined on the Windows desktop too (and only those). */
    private static void arrangeIfChanged(MonitorBackend b) {
        String spec = arrangement();
        StringBuilder key = new StringBuilder(spec);
        for (Mon m : LIVE.values()) key.append(' ').append(m.index()).append('=').append(m.width()).append('x').append(m.height());
        // on any change at once; otherwise every few seconds anyway (Windows may have shuffled them)
        if (key.toString().equals(lastArrangement) && ticks % 100 != 5) return;
        boolean changed = !key.toString().equals(lastArrangement);
        lastArrangement = key.toString();
        if (spec.isEmpty()) return;
        if (changed) WindowsCraftClient.LOGGER.info("Laying out the in-game monitors like in the world: {}", spec);
        b.arrange(spec).thenRun(() -> MinecraftClient.getInstance().execute(() -> refreshRects(MinecraftClient.getInstance())))
                .exceptionally(t -> { lastArrangement = ""; return null; });
    }

    /** "index:parent:side ..." for every in-game monitor that's on (see {@link MonitorBackend#arrange}). */
    public static String arrangement() {
        record On(Mon m, Display d) {}
        List<On> on = new ArrayList<>();
        for (Display d : DisplayManager.get().all()) {
            Mon m = monitorOf(d);
            if (m != null && d.isLive()) on.add(new On(m, d));
        }
        on.sort(java.util.Comparator.comparingInt(o -> o.m().index()));
        Map<Integer, String> spec = new java.util.LinkedHashMap<>();
        java.util.Set<Integer> placed = new java.util.HashSet<>();
        for (On root : on) {
            if (placed.contains(root.m().index())) continue;
            placed.add(root.m().index());
            spec.put(root.m().index(), root.m().index() + ":-1:-");
            java.util.ArrayDeque<On> todo = new java.util.ArrayDeque<>(List.of(root));
            while (!todo.isEmpty()) {
                On p = todo.poll();
                for (On c : on) {
                    if (placed.contains(c.m().index())) continue;
                    char side = joinedSide(p.d(), c.d());
                    if (side == 0) continue;
                    placed.add(c.m().index());
                    spec.put(c.m().index(), c.m().index() + ":" + p.m().index() + ":" + side);
                    todo.add(c);
                }
            }
        }
        return String.join(" ", spec.values());
    }

    /** Where b's screen joins a's, edge to edge in the world (R, L, T, B of a), or 0 if they don't touch. */
    static char joinedSide(Display a, Display b) {
        if (a.tablet || b.tablet || !a.rect.isMonitor() || !b.rect.isMonitor() || !a.dimension.equals(b.dimension)) return 0;
        var pa = a.rect.pose() != null ? a.rect.pose() : dev.windowscraft.display.MonitorPose.standing(a.rect.turn());
        var pb = b.rect.pose() != null ? b.rect.pose() : dev.windowscraft.display.MonitorPose.standing(b.rect.turn());
        double w = dev.windowscraft.display.MonitorPose.HALF_W, h = dev.windowscraft.display.MonitorPose.HALF_H, f = dev.windowscraft.display.MonitorPose.FRONT;
        var ma = a.rect.min();
        var mb = b.rect.min();
        if (pa.at(ma, w, 0, f).distanceTo(pb.at(mb, -w, 0, f)) < 0.06 && pa.up().dotProduct(pb.up()) > 0.7) return 'R';
        if (pa.at(ma, -w, 0, f).distanceTo(pb.at(mb, w, 0, f)) < 0.06 && pa.up().dotProduct(pb.up()) > 0.7) return 'L';
        if (pa.at(ma, 0, h, f).distanceTo(pb.at(mb, 0, -h, f)) < 0.06 && pa.right().dotProduct(pb.right()) > 0.7) return 'T';
        if (pa.at(ma, 0, -h, f).distanceTo(pb.at(mb, 0, h, f)) < 0.06 && pa.right().dotProduct(pb.right()) > 0.7) return 'B';
        return 0;
    }

    /** The sizes that wall's in-game monitor can show ({w, h, hz}). */
    public static CompletableFuture<List<int[]>> modes(Display d) {
        Mon m = monitorOf(d);
        MonitorBackend b = backend();
        if (m == null || b == null) return CompletableFuture.completedFuture(List.of());
        return b.modes(m).exceptionally(t -> List.of());
    }

    /** Another size for that wall's in-game monitor (remembered for next time too). Completes with null, or a reason it couldn't. */
    public static CompletableFuture<String> setMode(Display d, int w, int h, int hz) {
        Mon m = monitorOf(d);
        MonitorBackend b = backend();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (m == null || b == null) return CompletableFuture.completedFuture("it isn't on");
        return b.setMode(m, w, h, hz).handle((fresh, err) -> {
            mc.execute(() -> {
                if (err == null) { // (what Windows really made it)
                    d.monitorW = fresh.width();
                    d.monitorH = fresh.height();
                    if (d.tablet) dev.windowscraft.config.ClientSettings.setTabletSize(d.monitorW, d.monitorH);
                    DisplayManager.get().save();
                }
                refreshRects(mc); // its picture follows the new size
            });
            if (err == null) return null;
            Throwable why = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
            return why.getMessage();
        });
    }

    /** Leaving the world: every monitor goes (DisplayManager stops their shares too). */
    public static void onDisconnect() {
        endPoint();
        unseat();
        turnOffAll();
    }

    private static final class SeatEvents implements MonitorBackend.Events {
        private static void onGame(Runnable r) { MinecraftClient.getInstance().execute(r); }

        @Override public void esc() { onGame(() -> { if (dev.windowscraft.client.Tablet.raised()) dev.windowscraft.client.Tablet.lower(); else TheaterMode.leaveNow(); }); }
        @Override public void alt(boolean down) { altLooking = down; }
        @Override public void seatMoved(int index) {
            onGame(() -> {
                if (seatedMon == null) return;
                for (Display d : DisplayManager.get().all()) {
                    Mon m = monitorOf(d);
                    if (m == null || m.index() != index || d == seatedOn) continue;
                    seatedMon = m;
                    seatedOn = d;
                    TheaterMode.glideTo(d); // (your view goes along to it)
                    return;
                }
            });
        }
        @Override public void adminApp(boolean refused) {
            onGame(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (refused) say(mc, "That app runs as administrator, so it stays on your desk (moving admin apps wasn't allowed).", Formatting.YELLOW);
                else say(mc, "That app (Task Manager?) runs as administrator: Windows asks once for permission to move admin apps onto your in-game monitors.", Formatting.GRAY);
            });
        }

        @Override public void monitorGone(int index) {
            onGame(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                for (Display d : List.copyOf(DisplayManager.get().all())) {
                    Mon m = monitorOf(d);
                    if (m == null || m.index() != index) continue;
                    WindowsCraftClient.LOGGER.warn("In-game monitor {} ({}) went away by itself; turning it on again", index, m.device());
                    if (seatedMon != null && seatedMon.index() == index) unseat();
                    if (pointMon != null && pointMon.index() == index) { pointMon = null; pointOn = null; pointMe = false; pointReady = false; }
                    SourceInfo src = d.share().source();
                    LIVE.remove(src.id()); // (first: it's gone already, nothing to remove)
                    NUMBER.remove(src.id());
                    DisplayManager.get().stop(d);
                    // the wall/monitor/tablet gets a fresh monitor of its own, as if you pressed U again
                    long now = System.currentTimeMillis();
                    List<Long> recent = REVIVED.computeIfAbsent(d.id, k -> new ArrayList<>());
                    recent.removeIf(t -> now - t > 120_000);
                    if (recent.size() >= 2) { // (it keeps happening: don't fight it)
                        say(mc, "An in-game monitor keeps getting dropped by Windows. Press U on it to try again.", Formatting.YELLOW);
                        continue;
                    }
                    recent.add(now);
                    if (mc.world != null && DisplayManager.get().all().contains(d)) {
                        hint(mc, "Windows dropped an in-game monitor - turning it back on…");
                        turnOn(mc, d.dimension, d.rect, d);
                    }
                }
            });
        }
        @Override public void recenter() { onGame(TheaterMode::recenter); }
        @Override public void zoom(double notches) { onGame(() -> { if (dev.windowscraft.client.Tablet.raised()) dev.windowscraft.client.Tablet.zoom(notches); else TheaterMode.scroll(notches); }); }

        @Override
        public void look(int dx, int dy) {
            onGame(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                // exactly like turning in the game: Minecraft's own sensitivity curve
                double s = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
                double f = s * s * s * 8.0;
                int invert = mc.options.getInvertYMouse().getValue() ? -1 : 1;
                if (seatedMon != null && dev.windowscraft.client.Tablet.raised() && mc.player != null) mc.player.changeLookDirection(dx * f, dy * f * invert); // holding a tablet up: Alt looks around
                else if (seatedMon != null) TheaterMode.look(dx * f, dy * f * invert);
                else if (pointMon != null && pointReady && mc.player != null) mc.player.changeLookDirection(dx * f, dy * f * invert);
            });
        }

        @Override
        public void mouseButton(int button, boolean down) {
            onGame(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                net.minecraft.client.util.InputUtil.Key k = net.minecraft.client.util.InputUtil.Type.MOUSE.createFromCode(button);
                net.minecraft.client.option.KeyBinding.setKeyPressed(k, down && mc.currentScreen == null);
                if (down && mc.currentScreen == null) net.minecraft.client.option.KeyBinding.onKeyPressed(k);
            });
        }

        @Override
        public void mouseWheel(double notches) {
            onGame(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null || mc.currentScreen != null || notches == 0) return;
                var inv = mc.player.getInventory();
                inv.selectedSlot = Math.floorMod(inv.selectedSlot - (int) Math.signum(notches), 9);
            });
        }

        @Override public void altUp() { onGame(InGameMonitors::ownerStopPointing); }

        @Override
        public void pointLost() {
            onGame(() -> {
                pointMon = null;
                pointOn = null;
                pointMe = false;
                pointReady = false;
                if (seatedMon == null) restorePause();
            });
        }

        @Override
        public void pointOk(boolean seated) {
            onGame(() -> {
                MonitorBackend b = backend();
                if (pointMon == null || b == null) return;
                pointReady = true;
                if (pointMe) b.pointMe(true);
                b.cursorTo(lastCursor[0], lastCursor[1]); // wherever it is by now
            });
        }

        @Override
        public void pointFail(String why) {
            onGame(() -> {
                WindowsCraftClient.LOGGER.warn("Couldn't put the cursor on the in-game monitor: {}", why);
                pointMon = null;
                pointOn = null;
                pointMe = false;
                pointReady = false;
                pointRetryAt = System.nanoTime() + 3_000_000_000L;
                if (seatedMon == null) restorePause();
            });
        }

        @Override
        public void lost() {
            onGame(() -> {
                pointMon = null;
                pointOn = null;
                pointMe = false;
                pointReady = false;
                if (LIVE.isEmpty() && seatedMon == null) return;
                // the monitors are gone with the helper: stop showing them (never capture anything else instead)
                boolean wasSeated = seatedMon != null;
                seatedMon = null;
                seatedOn = null;
                restorePause();
                if (backend instanceof dev.windowscraft.capture.win.VDisplayHelper) dev.windowscraft.capture.win.VDisplayHelper.unclip();
                if (wasSeated) TheaterMode.leaveNow();
                LIVE.clear(); // first: they're gone already, nothing to remove
                NUMBER.clear();
                for (Share s : DisplayManager.get().liveShares()) if (isInGameMonitor(s.source())) DisplayManager.get().stopShare(s);
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) say(mc, "The in-game monitors stopped (their helper closed).", Formatting.YELLOW);
            });
        }
    }

    // ---- installing the driver ------------------------------------------------------------------------

    /**
     * Downloads the driver installer (checked against the exact file we tested with), then runs it
     * as administrator — Windows asks you first. Progress goes to {@code progress}; completes with
     * the driver's state afterwards. Any thread.
     */
    /** Restarts the driver when Windows shows it with a problem. */
    public static CompletableFuture<Driver> repairDriver() {
        MonitorBackend b = backend();
        if (b == null) return CompletableFuture.completedFuture(Driver.UNSUPPORTED);
        return b.repair().thenApply(d -> driver = d);
    }

    public static CompletableFuture<Driver> installDriver(Consumer<String> progress) {
        MonitorBackend b = backend();
        if (b == null) return CompletableFuture.completedFuture(Driver.UNSUPPORTED);
        return CompletableFuture.supplyAsync(() -> {
            try {
                progress.accept("Downloading the driver…");
                Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("bin");
                Files.createDirectories(dir);
                Path file = dir.resolve("parsec-vdd-0.41.0.0.exe");
                byte[] bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                if (bytes == null || !sha256(bytes).equals(DRIVER_SHA256)) {
                    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(java.time.Duration.ofSeconds(15)).build();
                    HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(DRIVER_URL))
                            .timeout(java.time.Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() != 200) throw new IOException("download failed (HTTP " + r.statusCode() + ")");
                    bytes = r.body();
                    if (!sha256(bytes).equals(DRIVER_SHA256)) throw new IOException("the downloaded file isn't the expected driver");
                    Files.write(file, bytes);
                }
                return file;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).thenCompose(file -> {
            progress.accept("Windows will ask for permission — click Yes…");
            return b.install(file);
        }).thenCompose(result -> {
            if (result.equals("cancelled")) throw new java.util.concurrent.CompletionException(new IOException("the permission request was declined"));
            if (result.startsWith("failed")) throw new java.util.concurrent.CompletionException(new IOException("the installer " + result));
            progress.accept("Starting the driver…");
            return waitForDriver(b, 20);
        });
    }

    private static CompletableFuture<Driver> waitForDriver(MonitorBackend b, int triesLeft) {
        return b.status().thenCompose(st -> {
            if (st == Driver.OK || st == Driver.RESTART || triesLeft <= 0) return CompletableFuture.completedFuture(driver = st);
            return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .thenCompose(x -> waitForDriver(b, triesLeft - 1));
        });
    }

    /** Removes the driver (Settings): all in-game monitors go first. "ok", "cancelled", "missing" or "failed …". */
    public static CompletableFuture<String> removeDriver() {
        MonitorBackend b = backend();
        if (b == null) return CompletableFuture.completedFuture("missing");
        turnOffAll();
        return b.uninstall().whenComplete((r, e) -> driver = null);
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Tests: every message these show you. */
    public static volatile java.util.function.Consumer<String> testMessages;

    private static void hint(MinecraftClient mc, String msg) {
        var t = testMessages;
        if (t != null) t.accept(msg);
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg).formatted(Formatting.GRAY), true);
    }

    private static void say(MinecraftClient mc, String msg, Formatting f) {
        var t = testMessages;
        if (t != null) t.accept(msg);
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg).formatted(f), false);
    }

    private InGameMonitors() {}
}
