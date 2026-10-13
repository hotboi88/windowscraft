package dev.windowscraft.capture.win;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.monitor.MonitorBackend;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

/**
 * In-game monitors on Windows, through wc-vdisplay.exe (Parsec Virtual Display Driver). The
 * helper answers commands one at a time, in order; while you sit at a monitor it also reports
 * the keys the game keeps (Esc, Alt…). If the helper stops, the driver drops its monitors by
 * itself within a second — nothing can be left behind.
 */
public final class VDisplayHelper implements MonitorBackend {
    private static final Set<String> EVENTS = Set.of("esc", "alt", "look", "zoom", "recenter", "mbtn", "mwheel", "altup", "pointlost", "pointok", "pointfail", "seatmove", "gone", "adminapp");

    private record Pending(Set<String> ends, List<String> lines, CompletableFuture<List<String>> done) {}

    private Process process;
    private OutputStream stdin;
    private final Deque<Pending> pending = new ArrayDeque<>();
    private volatile Events events;
    private boolean everStarted;

    @Override public void events(Events e) { events = e; }

    private synchronized CompletableFuture<List<String>> call(String command, String... ends) {
        CompletableFuture<List<String>> f = new CompletableFuture<>();
        try {
            ensure();
            Pending p = new Pending(Set.of(ends), new ArrayList<>(), f);
            pending.add(p);
            stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            // never wait forever: a helper that stops answering is restarted (everything waiting
            // on it gets an error to show, instead of U silently doing nothing)
            Process proc = process;
            long secs = command.startsWith("install") || command.startsWith("uninstall") || command.startsWith("repair") ? 600 : command.startsWith("add") ? 90
                    : command.startsWith("setmode") || command.startsWith("seat") || command.startsWith("remove") ? 40 : 20;
            CompletableFuture.delayedExecutor(secs, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                if (f.isDone()) return;
                WindowsCraftClient.LOGGER.warn("The in-game monitor helper didn't answer \"{}\" in {} s: restarting it", command.split("[ \t]")[0], secs);
                f.completeExceptionally(new IOException("the in-game monitor helper didn't answer (it was restarted: try again)"));
                synchronized (VDisplayHelper.this) {
                    pending.remove(p);
                    if (process == proc && proc != null) proc.destroyForcibly(); // (the reader then fails the rest; the next call starts a fresh one)
                }
            });
        } catch (IOException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    /** A command with no answer ("beat"). */
    private synchronized void send(String command) {
        try {
            if (process == null || !process.isAlive()) return; // nothing seated in a helper that isn't running
            stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ignored) {
        }
    }

    private void ensure() throws IOException {
        if (process != null && process.isAlive()) return;
        // whatever the old helper still owed will never come
        for (Pending x : pending) x.done.completeExceptionally(new IOException("the in-game monitor helper stopped"));
        pending.clear();
        Path exe = extract();
        process = new ProcessBuilder(exe.toString(), "--parent-pid", Long.toString(ProcessHandle.current().pid())).start();
        stdin = process.getOutputStream();
        Process p = process;
        Thread out = new Thread(() -> read(p), "WindowsCraft in-game monitors");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) WindowsCraftClient.LOGGER.info("[wc-vdisplay] {}", line);
            } catch (IOException ignored) {
            }
        }, "WindowsCraft in-game monitors log");
        err.setDaemon(true);
        err.start();
        everStarted = true;
    }

    private void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String first = line.split(" ", 2)[0];
                if (EVENTS.contains(first)) {
                    event(line);
                    continue;
                }
                Pending done = null;
                synchronized (this) {
                    Pending head = pending.peek();
                    if (head == null) continue;
                    head.lines.add(line);
                    if (head.ends.contains(first)) done = pending.poll();
                }
                if (done != null) done.done.complete(done.lines);
            }
        } catch (IOException ignored) {
        }
        List<Pending> left;
        synchronized (this) {
            if (process != p) return; // an older helper: a new one is running already
            left = new ArrayList<>(pending);
            pending.clear();
            process = null;
        }
        for (Pending x : left) x.done.completeExceptionally(new IOException("the in-game monitor helper stopped"));
        Events e = events;
        if (e != null) e.lost();
    }

    private void event(String line) {
        Events e = events;
        if (e == null) return;
        String[] p = line.split(" ");
        try {
            switch (p[0]) {
                case "esc" -> e.esc();
                case "alt" -> e.alt(p.length > 1 && p[1].equals("1"));
                case "look" -> e.look(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                case "zoom" -> e.zoom(Double.parseDouble(p[1]));
                case "recenter" -> e.recenter();
                case "mbtn" -> e.mouseButton(Integer.parseInt(p[1]), p[2].equals("1"));
                case "mwheel" -> e.mouseWheel(Double.parseDouble(p[1]));
                case "altup" -> e.altUp();
                case "pointlost" -> e.pointLost();
                case "pointok" -> e.pointOk(p.length > 1 && p[1].equals("seated"));
                case "pointfail" -> e.pointFail(line.length() > 10 ? line.substring(10) : "?");
                case "seatmove" -> e.seatMoved(Integer.parseInt(p[1]));
                case "gone" -> e.monitorGone(Integer.parseInt(p[1]));
                case "adminapp" -> e.adminApp();
                default -> {}
            }
        } catch (RuntimeException ex) {
            WindowsCraftClient.LOGGER.debug("bad event {}", line, ex);
        }
    }

    private static String last(List<String> lines) { return lines.isEmpty() ? "" : lines.get(lines.size() - 1); }

    @Override
    public CompletableFuture<Driver> status() {
        return call("status", "status").thenApply(l -> {
            String[] p = last(l).split(" ");
            return switch (p.length > 1 ? p[1] : "") {
                case "ok" -> Driver.OK;
                case "missing" -> Driver.MISSING;
                case "disabled" -> Driver.DISABLED;
                case "restart" -> Driver.RESTART;
                default -> Driver.ERROR;
            };
        });
    }

    @Override
    public CompletableFuture<Mon> add(int width, int height, int hz) {
        return call("add " + width + " " + height + " " + hz, "added", "fail").thenApply(l -> {
            String s = last(l);
            if (s.startsWith("fail")) throw new IllegalStateException(s.length() > 5 ? s.substring(5) : "the monitor couldn't be made");
            String[] p = s.split(" ");
            return new Mon(Integer.parseInt(p[1]), p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        });
    }

    @Override
    public CompletableFuture<Void> remove(Mon m) {
        return call("remove " + m.index(), "removed").thenApply(l -> null); // the helper puts its windows back first
    }

    @Override
    public CompletableFuture<List<Mon>> rects() {
        return call("rects", "rects-end").thenApply(l -> {
            List<Mon> out = new ArrayList<>();
            for (String s : l) {
                String[] p = s.split(" ");
                if (p[0].equals("rect") && p.length >= 7) out.add(new Mon(Integer.parseInt(p[1]), p[2], Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6])));
                // shown on top of one of your real screens: a zero-size entry says "stop showing it now"
                else if (p[0].equals("mirrored") && p.length >= 2) out.add(new Mon(Integer.parseInt(p[1]), "", 0, 0, 0, 0));
            }
            return out;
        });
    }

    private static List<Win> wins(List<String> l) {
        List<Win> out = new ArrayList<>();
        for (String s : l) {
            String[] p = s.split(" ", 5);
            if (!p[0].equals("win") || p.length < 4) continue;
            try {
                out.add(new Win(Long.parseUnsignedLong(p[1]), Long.parseLong(p[2]), p[3].equals("1"), p.length > 4 ? p[4] : ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    @Override public CompletableFuture<List<Win>> windowsOn(Mon m) { return call("windows " + m.index(), "windows-end").thenApply(VDisplayHelper::wins); }

    @Override public CompletableFuture<List<Win>> deskWindows() { return call("deskwindows", "windows-end").thenApply(VDisplayHelper::wins); }

    @Override
    public CompletableFuture<Void> bring(long hwnd, Mon m) {
        return call("bring " + Long.toUnsignedString(hwnd) + " " + m.index(), "brought").thenApply(l -> null);
    }

    @Override
    public CompletableFuture<Boolean> launch(Mon m, String file, String params, String exe) {
        String clean = (file + "\t" + params + "\t" + exe).replace('\n', ' ').replace('\r', ' ');
        return call("launch\t" + m.index() + "\t" + clean, "launched").thenApply(l -> last(l).endsWith("ok"));
    }

    @Override
    public CompletableFuture<Void> close(long hwnd) {
        return call("close " + Long.toUnsignedString(hwnd), "closed").thenApply(l -> null);
    }

    @Override
    public CompletableFuture<Void> sendHome(long hwnd) {
        return call("home " + Long.toUnsignedString(hwnd), "homed").thenApply(l -> null);
    }

    @Override
    public CompletableFuture<String> seat(Mon m, double u, double v, long gameWindow) {
        try {
            Win32.User32.I.AllowSetForegroundWindow(-1); // the game has the keyboard: it lets the helper pass it on
        } catch (Throwable ignored) {
        }
        return call(String.format(java.util.Locale.ROOT, "seat %d %.5f %.5f %s", m.index(), u, v, Long.toUnsignedString(gameWindow)), "seated", "fail")
                .thenApply(l -> { String s = last(l); return s.startsWith("fail") ? (s.length() > 5 ? s.substring(5) : "failed") : "ok"; });
    }

    @Override public void beat() { send("beat"); }

    @Override
    public void point(Mon m, long gameWindow, double u, double v) {
        send(String.format(java.util.Locale.ROOT, "point %d %s %.5f %.5f", m.index(), Long.toUnsignedString(gameWindow), u, v));
    }

    @Override public void unpoint() { send("unpoint"); }
    @Override public void release() { send("release"); }

    @Override
    public CompletableFuture<Driver> repair() {
        return call("repair", "repair").thenApply(l -> {
            String[] p = last(l).split(" ");
            return p.length > 1 && p[1].equals("ok") ? Driver.OK : p.length > 2 && p[2].equals("restart") ? Driver.RESTART : Driver.ERROR;
        });
    }

    @Override
    public CompletableFuture<Void> arrange(String spec) {
        return call("arrange " + spec, "arranged").thenApply(l -> null);
    }

    @Override
    public CompletableFuture<List<int[]>> modes(Mon m) {
        return call("modes " + m.index(), "modes-end").thenApply(l -> {
            List<int[]> out = new ArrayList<>();
            for (String s : l) {
                String[] p = s.split(" ");
                if (p[0].equals("mode") && p.length >= 4) out.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])});
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Mon> setMode(Mon m, int w, int h, int hz) {
        return call("setmode " + m.index() + " " + w + " " + h + " " + hz, "modeset").thenApply(l -> {
            String[] p = last(l).split(" ");
            if (p.length < 6 || !p[1].equals("ok")) throw new IllegalStateException(last(l).replaceFirst("^modeset fail ", ""));
            return new Mon(m.index(), m.device(), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
        });
    }
    @Override public void pointMe(boolean me) { send("pointme " + (me ? 1 : 0)); }
    @Override public void cursorTo(double u, double v) { send(String.format(java.util.Locale.ROOT, "ptr %.5f %.5f", u, v)); }
    @Override public void click(int button, boolean down) { send("btn " + button + (down ? " down" : " up")); }
    @Override public void wheel(double notches) { send(String.format(java.util.Locale.ROOT, "wheel %.3f", notches)); }
    @Override public void key(int scancode, boolean down) { send("key " + scancode + (down ? " down" : " up")); }

    @Override
    public void unseat() {
        call("unseat", "unseated");
    }

    /** Lets go of the mouse clip from the game's side too (the helper may be gone). */
    public static void unclip() {
        try {
            Win32.User32.I.ClipCursor(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public CompletableFuture<String> install(Path installer) {
        return call("install " + installer.toAbsolutePath(), "install").thenApply(l -> {
            String[] p = last(l).split(" ");
            return p.length > 1 && !p[1].equals("failed") ? p[1] : "failed " + (p.length > 2 ? p[2] : "?");
        });
    }

    @Override
    public CompletableFuture<String> uninstall() {
        return call("uninstall", "uninstall").thenApply(l -> {
            String[] p = last(l).split(" ");
            return p.length > 1 && !p[1].equals("failed") ? p[1] : "failed " + (p.length > 2 ? p[2] : "?");
        });
    }

    @Override
    public synchronized void shutdown() {
        if (process == null) return;
        try {
            stdin.close(); // the helper puts windows back and removes its monitors, then exits
        } catch (IOException ignored) {
        }
        Process p = process;
        try {
            if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
        } catch (InterruptedException e) {
            p.destroy();
        }
        process = null;
    }

    static synchronized Path extract() throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("windowscraft").resolve("bin");
        Path exe = dir.resolve("wc-vdisplay.exe");
        try (InputStream in = VDisplayHelper.class.getResourceAsStream("/windowscraft/natives/wc-vdisplay.exe")) {
            if (in == null) throw new IOException("wc-vdisplay.exe missing from the mod jar");
            byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(exe) || Files.size(exe) != bytes.length || !java.util.Arrays.equals(Files.readAllBytes(exe), bytes)) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve("wc-vdisplay.exe.tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return exe;
    }
}
