package dev.windowscraft.monitor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Makes and removes in-game monitors: monitors that exist only inside Windows (a virtual display
 * driver), which a wall shows and the real mouse and keyboard can work.
 */
public interface MonitorBackend {
    enum Driver { OK, MISSING, DISABLED, RESTART, ERROR, UNSUPPORTED }

    /** One in-game monitor: the driver's index, Windows' name for it, where it is on the desktop (physical pixels). */
    record Mon(int index, String device, int left, int top, int width, int height) {
        public int right() { return left + width; }
        public int bottom() { return top + height; }
        public String area() { return left + " " + top + " " + right() + " " + bottom(); }
    }

    /** A top-level app window; {@code ours}: it was started on the monitor (it closes when the monitor turns off). */
    record Win(long hwnd, long pid, boolean ours, String title) {}

    /** While you sit at an in-game monitor, the helper reports the keys the game keeps for itself. */
    interface Events {
        void esc();
        void alt(boolean down);
        void look(int dx, int dy);
        void zoom(double notches);
        void recenter();
        /** The helper stopped: every monitor it made is gone. */
        void lost();
        /** That one monitor went away by itself (Windows or the driver dropped it). */
        default void monitorGone(int index) {}
        /** An app running as administrator should go onto a monitor, but Windows won't let it be moved. */
        default void adminApp() {}
        /** Windows moved or renamed a monitor: look where they all are now. */
        default void monitorsMoved() {}
        /** An app that only runs once came forward where it already is (not here): it stays there. */
        default void singleInstance(String title) {}
        /** Pointing: your own click (or release) while someone else points — it belongs to the game. */
        default void mouseButton(int button, boolean down) {}
        /** Pointing: your own wheel while someone else points — it belongs to the game. */
        default void mouseWheel(double notches) {}
        /** You let go of Alt while pointing. */
        default void altUp() {}
        /** Pointing ended by itself (the game stopped answering). */
        default void pointLost() {}
        /** The cursor is on the monitor now ({@code seated}: you sit there, it was already). */
        default void pointOk(boolean seated) {}
        /** The cursor couldn't go onto the monitor. */
        default void pointFail(String why) {}
        /** Sitting: your cursor went on to a monitor joined to yours — you sit at that one now. */
        default void seatMoved(int index) {}
        /** Pointing, with an app on the monitor in front (you clicked it): your key, for the game ({@code vk} Windows' key code). */
        default void gameKey(int vk, int scancode, boolean down) {}
    }

    CompletableFuture<Driver> status();

    /** A new monitor; completes exceptionally with a message you can show if it can't be made. */
    CompletableFuture<Mon> add(int width, int height, int hz);

    /** Its windows go back to your desk, then the monitor goes away. */
    CompletableFuture<Void> remove(Mon m);

    /**
     * Where each monitor is now (Windows can move them when others come and go). A monitor
     * Windows is mirroring onto one of your real screens comes back with width 0; one that's
     * missing isn't listed.
     */
    CompletableFuture<List<Mon>> rects();

    CompletableFuture<List<Win>> windowsOn(Mon m);

    /** Windows on your real monitors. */
    CompletableFuture<List<Win>> deskWindows();

    CompletableFuture<Void> bring(long hwnd, Mon m);

    CompletableFuture<Void> sendHome(long hwnd);

    /** Closes a window like its × does (the app may ask to save first). */
    CompletableFuture<Void> close(long hwnd);

    /**
     * Starts an app straight onto that monitor: {@code file} with {@code params}; {@code exe} is
     * the program's file name (so a program that was already running is recognised by the new
     * window it opens). Its windows never show up on your real screens.
     */
    CompletableFuture<Boolean> launch(Mon m, String file, String params, String exe);

    /**
     * Your real mouse and keyboard go to that monitor (pointer at u, v of it). Completes with "ok"
     * or a reason it couldn't. While seated, {@link #beat()} must come every few seconds, or the
     * helper gives everything back by itself (a frozen game never keeps your mouse).
     */
    CompletableFuture<String> seat(Mon m, double u, double v, long gameWindow);

    /** "The game is still here" while seated or pointing. */
    void beat();

    // ---- own copies of apps (Sandboxie boxes, one per monitor) ----
    /** "Own copies" for that monitor on or off. */
    default void isolate(Mon m, boolean on) {}
    /** Programs (file names) and folders that never go in a box, and extra folders apps are installed in (game libraries). */
    default void isolationExceptions(List<String> programs, List<String> folders, List<String> appFolders) {}
    /** "ok", "missing" or "stopped". */
    default CompletableFuture<String> sandboxStatus() { return CompletableFuture.completedFuture("missing"); }
    /** Installs Sandboxie-Plus quietly (one permission prompt). Completes with the status after. */
    default CompletableFuture<String> installSandbox(Path installer) { return CompletableFuture.completedFuture("missing"); }
    /** Starts a program in that monitor's box (another copy of it). */
    default CompletableFuture<Boolean> boxLaunch(Mon m, String file, String params) { return CompletableFuture.completedFuture(false); }
    /** Closes everything in that monitor's box and wipes it (its logins and data). */
    default CompletableFuture<Void> boxReset(Mon m) { return CompletableFuture.completedFuture(null); }
    /** The windows of programs running in that monitor's box. */
    default CompletableFuture<List<Win>> boxApps(Mon m) { return CompletableFuture.completedFuture(List.of()); }

    /** Which wall this monitor is (its own browser copies are kept per wall). */
    default void profileKey(Mon m, String key) {}

    /** The monitor's own copy of that browser (its own profile, kept for this wall) opens on it. */
    default CompletableFuture<Boolean> ownBrowser(Mon m, String exePath) { return CompletableFuture.completedFuture(false); }

    /**
     * Your real cursor goes onto that monitor while the game keeps the keyboard (Alt-pointing at
     * it, or someone you allowed using it), at (u, v) of it; your mouse keeps turning your view,
     * as look events. Answered by {@link Events#pointOk} or {@link Events#pointFail}.
     */
    default void point(Mon m, long gameWindow, double u, double v) {}

    default void unpoint() {}

    /** Lets go of every button and key pressed for someone you allowed. */
    default void release() {}

    /** The sizes that monitor can show: {width, height, refresh}, smallest first. */
    default CompletableFuture<List<int[]>> modes(Mon m) { return CompletableFuture.completedFuture(List.of()); }

    /** Another size for it (where it is); completes with where it is now, or exceptionally with a reason. */
    default CompletableFuture<Mon> setMode(Mon m, int w, int h, int hz) { return CompletableFuture.failedFuture(new IllegalStateException("not supported")); }

    /**
     * Lays your in-game monitors out on the Windows desktop like they stand in the world: each
     * entry "index:parent:side" (side R, L, T or B of the parent; parent -1 for one standing on
     * its own). Joined ones touch edge to edge (a window drags across), the rest never touch
     * each other or your own screens (nothing drags onto them by accident).
     */
    default CompletableFuture<Void> arrange(String spec) { return CompletableFuture.completedFuture(null); }

    /** The driver got into trouble: restart it (Windows asks for permission). Completes with its state after. */
    default CompletableFuture<Driver> repair() { return CompletableFuture.completedFuture(Driver.ERROR); }

    /** You're the one pointing (your clicks reach the monitor), or not (they're the game's). */
    default void pointMe(boolean me) {}

    /** The real cursor to (u, v) of the monitor you point at or sit at. */
    default void cursorTo(double u, double v) {}

    /** A click there by someone you allowed (GLFW button numbers). */
    default void click(int button, boolean down) {}

    default void wheel(double notches) {}

    /** A key as if typed on your keyboard (only while you sit at the monitor). */
    default void key(int scancode, boolean down) {}

    /** …and back to the game. */
    void unseat();

    /** Runs the driver installer as administrator: "ok", "cancelled" or "failed <code>". */
    CompletableFuture<String> install(Path installer);

    /** "ok", "cancelled", "missing" or "failed <code>". */
    CompletableFuture<String> uninstall();

    void events(Events e);

    /** Removes every monitor and stops (game closing). */
    void shutdown();
}
