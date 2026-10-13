package dev.windowscraft.monitor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Tests only (development runs with -Dwindowscraft.fakeMonitors=true): pretend in-game monitors,
 * so the whole flow around them — U, walls breaking, leaving the world, sitting down — can be
 * tested on any system. The wall shows the test pattern.
 */
public final class FakeMonitorBackend implements MonitorBackend {
    private final Map<Integer, Mon> live = new TreeMap<>();
    private int next;
    public volatile Driver driver = Driver.OK;
    public final List<String> calls = new ArrayList<>();
    private Events events;

    @Override public synchronized CompletableFuture<Driver> status() { return CompletableFuture.completedFuture(driver); }

    @Override
    public synchronized CompletableFuture<Mon> add(int width, int height, int hz) {
        calls.add("add " + width + "x" + height + "@" + hz);
        if (driver != Driver.OK) return CompletableFuture.failedFuture(new IllegalStateException("the in-game monitor driver isn't ready"));
        int i = next++;
        Mon m = new Mon(i, "\\\\.\\FAKE" + i, 100_000 + i * width, 0, width, height);
        live.put(i, m);
        return CompletableFuture.completedFuture(m);
    }

    @Override
    public synchronized CompletableFuture<Void> remove(Mon m) {
        calls.add("remove " + m.index());
        live.remove(m.index());
        return CompletableFuture.completedFuture(null);
    }

    public synchronized int liveCount() { return live.size(); }

    @Override public synchronized CompletableFuture<List<Mon>> rects() { return CompletableFuture.completedFuture(new ArrayList<>(live.values())); }
    // a few pretend windows, so the monitor menu has something to show
    @Override public CompletableFuture<List<Win>> windowsOn(Mon m) {
        return CompletableFuture.completedFuture(List.of(new Win(1000 + m.index(), 1, true, "Movie night - Brave")));
    }
    @Override public CompletableFuture<List<Win>> deskWindows() {
        return CompletableFuture.completedFuture(List.of(new Win(2001, 2, false, "Discord"), new Win(2002, 3, false, "Notes.txt - Notepad")));
    }
    @Override public synchronized CompletableFuture<Void> bring(long hwnd, Mon m) { calls.add("bring " + hwnd); return CompletableFuture.completedFuture(null); }
    @Override public synchronized CompletableFuture<Boolean> launch(Mon m, String file, String params, String exe) {
        calls.add("launch " + m.index() + " " + file);
        return CompletableFuture.completedFuture(true);
    }
    @Override public synchronized CompletableFuture<Void> close(long hwnd) { calls.add("close " + hwnd); return CompletableFuture.completedFuture(null); }
    @Override public synchronized CompletableFuture<Void> sendHome(long hwnd) { calls.add("home " + hwnd); return CompletableFuture.completedFuture(null); }
    @Override public synchronized CompletableFuture<String> seat(Mon m, double u, double v, long gameWindow) {
        calls.add("seat " + m.index());
        return CompletableFuture.completedFuture("ok");
    }
    public volatile int beats;
    @Override public void beat() { beats++; }
    @Override public synchronized void unseat() { calls.add("unseat"); }
    @Override public synchronized void point(Mon m, long gameWindow, double u, double v) {
        calls.add("point " + m.index());
        lastCursor = new double[]{u, v};
        if (events != null) events.pointOk(false);
    }
    @Override public synchronized void release() { calls.add("release"); }
    @Override public synchronized CompletableFuture<Void> arrange(String spec) { calls.add("arrange " + spec); return CompletableFuture.completedFuture(null); }
    @Override public CompletableFuture<List<int[]>> modes(Mon m) {
        return CompletableFuture.completedFuture(List.of(new int[]{1280, 720, 60}, new int[]{1600, 1200, 60}, new int[]{1920, 1080, 60}, new int[]{2560, 1440, 60}));
    }
    @Override public synchronized CompletableFuture<Mon> setMode(Mon m, int w, int h, int hz) {
        calls.add("setmode " + w + "x" + h);
        Mon n = new Mon(m.index(), m.device(), m.left(), m.top(), w, h);
        live.put(m.index(), n);
        return CompletableFuture.completedFuture(n);
    }
    @Override public synchronized void unpoint() { calls.add("unpoint"); }
    @Override public synchronized void pointMe(boolean me) { calls.add("pointme " + me); }
    @Override public synchronized void cursorTo(double u, double v) { lastCursor = new double[]{u, v}; }
    @Override public synchronized void click(int button, boolean down) { calls.add("click " + button + " " + down); }
    @Override public synchronized void wheel(double notches) { calls.add("wheel"); }
    @Override public synchronized void key(int scancode, boolean down) { calls.add("key " + scancode + " " + down); }
    public volatile double[] lastCursor;
    @Override public CompletableFuture<String> install(Path installer) { driver = Driver.OK; return CompletableFuture.completedFuture("ok"); }
    @Override public CompletableFuture<String> uninstall() { driver = Driver.MISSING; return CompletableFuture.completedFuture("ok"); }
    @Override public void events(Events e) { events = e; }
    public Events events() { return events; }
    @Override public synchronized void shutdown() { calls.add("shutdown"); live.clear(); }
}
