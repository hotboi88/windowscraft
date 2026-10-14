package dev.windowscraft.audio;

import dev.windowscraft.monitor.InGameMonitors;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows only knows sound per program, not per window: Brave on two in-game monitors (or on a
 * monitor and your desk) is one Brave. So when a program has windows in several places, its sound
 * plays from just one of them — where you (or someone you let use it) last used it: whichever has
 * its window in front, or was clicked last. Your desk counts as a place too: use it there and its
 * sound is on your speakers, click into it on a monitor and the sound moves to that wall. Any app.
 */
public final class SoundHome {
    /** Your real desk, as a place a program's sound can belong to. */
    static final String DESK = "desk";
    /** program → the in-game monitor (source id) its sound belongs to, or DESK. */
    private static final Map<Long, String> HOME = new ConcurrentHashMap<>();
    /** in-game monitor (source id) → the programs with a window on it (as its sound watcher last saw). */
    private static final Map<String, Set<Long>> ON = new ConcurrentHashMap<>();
    private static Thread follower;

    /** Its watcher saw these programs on that monitor. */
    /** The programs with a window on your real desk. */
    static void seenOnDesk(Set<Long> pids) { ON.put(DESK, Set.copyOf(pids)); }

    static void seen(String monitor, Set<Long> pids) {
        ON.put(monitor, Set.copyOf(pids));
        ensureFollower();
    }

    static void gone(String monitor) {
        ON.remove(monitor);
        HOME.values().removeIf(monitor::equals);
    }

    /** Should that monitor play this program's sound? */
    static boolean playsHere(String monitor, long pid) {
        String home = HOME.get(pid);
        boolean elsewhere = false;
        for (var e : ON.entrySet()) if (!e.getKey().equals(monitor) && e.getValue().contains(pid)) elsewhere = true;
        if (!elsewhere) return true; // only here: of course
        if (home != null && ON.getOrDefault(home, Set.of()).contains(pid)) return home.equals(monitor);
        // never used yet, and also open on your desk: your desk (it was yours before it was the monitor's)
        if (ON.getOrDefault(DESK, Set.of()).contains(pid)) return false;
        // never used yet: the first monitor it's on (by name), so exactly one plays it
        String first = null;
        for (var e : ON.entrySet()) if (e.getValue().contains(pid) && (first == null || e.getKey().compareTo(first) < 0)) first = e.getKey();
        return monitor.equals(first);
    }

    /** Someone clicked (or brought forward) this window: its program's sound comes from the monitor it's on. */
    public static void used(long hwnd) {
        if (hwnd == 0 || !isWindows()) return;
        try {
            var h = new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(hwnd));
            com.sun.jna.ptr.IntByReference p = new com.sun.jna.ptr.IntByReference();
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(h, p);
            com.sun.jna.platform.win32.WinDef.RECT r = new com.sun.jna.platform.win32.WinDef.RECT();
            if (p.getValue() <= 0 || !com.sun.jna.platform.win32.User32.INSTANCE.GetWindowRect(h, r)) return;
            String m = InGameMonitors.sourceIdAt((r.left + r.right) / 2, (r.top + r.bottom) / 2);
            // (on a monitor: that monitor; on your desk — not the game itself — your desk)
            if (m == null && com.sun.jna.Pointer.nativeValue(h.getPointer()) == dev.windowscraft.capture.win.WinGeom.gameWindow) return;
            HOME.put((long) p.getValue(), m != null ? m : DESK);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void ensureFollower() {
        if (follower != null || !isWindows()) return;
        follower = new Thread(() -> {
            long last = 0;
            while (true) {
                try {
                    Thread.sleep(200);
                    var fg = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
                    long h = fg == null ? 0 : com.sun.jna.Pointer.nativeValue(fg.getPointer());
                    if (h != 0 && h != last) used(h);
                    last = h;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "WindowsCraft sound home");
        follower.setDaemon(true);
        follower.start();
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"); }

    private SoundHome() {}
}
