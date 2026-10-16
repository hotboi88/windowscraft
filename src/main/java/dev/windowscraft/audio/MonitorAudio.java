package dev.windowscraft.audio;

import dev.windowscraft.WindowsCraftClient;
import dev.windowscraft.capture.SourceInfo;
import dev.windowscraft.monitor.InGameMonitors;
import dev.windowscraft.monitor.MonitorBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sound of an in-game monitor: the apps that have a window on it, each captured on its own and
 * mixed into one stream — other apps (a call in Discord on your desk) aren't heard. An app counts
 * as a whole (Windows can't split one app's sound by window): it's heard from the wall even while
 * you use its window on your desk, and one on several monitors from where it was last used there
 * (see SoundHome). With
 * "hear it in the game", those apps go quiet on your speakers and play from the wall instead.
 */
final class MonitorAudio extends AudioCapture {
    private static final int CHUNK = 480 * 4;                       // 10 ms of 48 kHz stereo
    // each app keeps 60 ms queued (its packets arrive unevenly); more than 200 ms is cut back to 80
    private static final int PRIME = AudioCapture.BYTES_PER_SECOND * 60 / 1000 / 4 * 4, MAX = AudioCapture.BYTES_PER_SECOND / 5,
            TRIM_TO = AudioCapture.BYTES_PER_SECOND * 80 / 1000 / 4 * 4;

    private final SourceInfo source;
    private final boolean hearInGame;
    /** App → this wall's buffer of its sound: every app with a window on the monitor (filled while its sound plays here). */
    private final Map<Long, Feed> feeds = new HashMap<>();
    /** App → how many looks in a row it wasn't on the monitor (it's let go after GONE_AFTER). */
    private final Map<Long, Integer> missing = new HashMap<>();
    private static final int GONE_AFTER = 3; // (~2 s: a look while monitors are added and laid out can miss it)
    private final Thread mixer, watcher;
    private volatile boolean running = true;

    private static final class Feed {
        final PcmRing ring = new PcmRing(AudioCapture.BYTES_PER_SECOND / 2);
        boolean primed;
    }

    /**
     * One capture per app, shared by every monitor it has a window on. It lives as long as the app
     * is on any of them, so the app stays turned down on your desk the whole time; which wall hears
     * it is only where its sound is sent. (Moving Brave's sound to the monitor you clicked used to
     * close one capture — its volume came back on your speakers: "it just goes really loud on my
     * main desktop and then transfers back" — and start another up to 0.7 s later.)
     */
    private static final class App {
        final long pid;
        final boolean duck;
        WasapiHelper helper;
        /** The monitors it has a window on (guarded by APPS). */
        final Set<MonitorAudio> on = new HashSet<>();
        /** The one whose wall plays it now (null: none for the moment — still turned down). */
        volatile MonitorAudio wall;

        App(long pid, boolean duck) { this.pid = pid; this.duck = duck; }
    }

    private static final List<App> APPS = new java.util.ArrayList<>();

    MonitorAudio(SourceInfo source, boolean hearInGame, Sink sink) {
        super(sink);
        this.source = source;
        this.hearInGame = hearInGame;
        inGame = hearInGame;
        status = "apps on this monitor";
        watcher = new Thread(this::watch, "WindowsCraft monitor audio apps");
        watcher.setDaemon(true);
        watcher.start();
        mixer = new Thread(this::mix, "WindowsCraft monitor audio mix");
        mixer.setDaemon(true);
        mixer.setPriority(Thread.MAX_PRIORITY);
        mixer.start();
    }

    /** Every 0.7 s: which apps have a window on the monitor, and which of them this wall plays. */
    private void watch() {
        long me = ProcessHandle.current().pid();
        while (running) {
            MonitorBackend.Mon m = InGameMonitors.monitorOf(source);
            MonitorBackend b = InGameMonitors.backend();
            if (m != null && b != null) {
                try {
                    Set<Long> pids = new HashSet<>();
                    for (MonitorBackend.Win w : b.windowsOn(m).get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        if (w.pid() > 0 && w.pid() != me) pids.add(w.pid());
                    }
                    // an app on several monitors is heard where it was last used on one (Windows
                    // can't split one program's sound by window); your desk never takes it
                    SoundHome.seen(source.id(), pids);
                    Set<Long> plays = new HashSet<>(pids);
                    plays.removeIf(pid -> !SoundHome.playsHere(source.id(), pid));
                    update(plays, pids);
                } catch (Exception e) {
                    WindowsCraftClient.LOGGER.debug("monitor audio: couldn't list apps", e);
                }
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void update(Set<Long> plays, Set<Long> onIt) {
        List<Long> leaving = new java.util.ArrayList<>();
        synchronized (feeds) {
            for (Long pid : new HashSet<>(feeds.keySet())) {
                if (onIt.contains(pid)) {
                    missing.remove(pid);
                    continue;
                }
                // gone from this monitor: only after a few looks in a row — one look while monitors
                // are added and laid out again can come back without it
                if (missing.merge(pid, 1, Integer::sum) >= GONE_AFTER) {
                    missing.remove(pid);
                    feeds.remove(pid);
                    leaving.add(pid);
                }
            }
            if (running) for (Long pid : onIt) feeds.computeIfAbsent(pid, p -> new Feed());
            int n = feeds.size();
            status = n == 0 ? "apps on this monitor (none playing)" : "apps on this monitor (" + n + ")";
        }
        // (never while holding the mixer's lock: starting a capture starts a process)
        for (Long pid : leaving) letGo(pid);
        if (!running) return;
        for (Long pid : onIt) hold(pid, plays.contains(pid));
    }

    /** That app is on this monitor: its capture (started if it's the first monitor it's on); with {@code plays}, its sound comes here. */
    private void hold(long pid, boolean plays) {
        synchronized (APPS) {
            App a = null;
            for (App x : APPS) if (x.pid == pid && x.duck == hearInGame) a = x;
            if (a != null && a.helper.stopped().isDone()) { // (its helper ended by itself: a fresh one)
                APPS.remove(a);
                a = null;
            }
            if (a == null) {
                App fresh = new App(pid, hearInGame);
                try {
                    List<String> args = new java.util.ArrayList<>(List.of("--include-pid", Long.toString(pid),
                            "--parent-pid", Long.toString(ProcessHandle.current().pid())));
                    Path state = null;
                    if (hearInGame) {
                        state = newDuckStateFile();
                        Files.createDirectories(state.getParent());
                        args.addAll(List.of("--duck", state.toString()));
                    }
                    fresh.helper = new WasapiHelper(args, "app audio", (pcm, off, len) -> {
                        MonitorAudio w = fresh.wall;
                        if (w != null) w.receive(pid, pcm, off, len);
                    }, state);
                } catch (Exception e) {
                    WindowsCraftClient.LOGGER.warn("monitor audio: couldn't capture app {}", pid, e);
                    return;
                }
                APPS.add(fresh);
                a = fresh;
            }
            a.on.add(this);
            if (plays || a.wall == null) a.wall = this;
        }
    }

    /** That app left this monitor: its sound stops coming here; its capture ends once it's on no monitor (its volume comes back). */
    private void letGo(long pid) {
        synchronized (APPS) {
            for (App a : APPS) {
                if (a.pid != pid || a.duck != hearInGame || !a.on.remove(this)) continue;
                if (a.wall == this) a.wall = nextWall(a);
                if (a.on.isEmpty()) endSoon(a);
            }
        }
    }

    /**
     * An app on no monitor any more: its capture ends a moment later, unless a monitor takes it up
     * again meanwhile — a monitor's picture restarting (a new resolution) closes its sound and
     * opens it again, and the app's volume came back on your desk in between. Done once it's ended
     * (or taken up again).
     */
    private static java.util.concurrent.CompletableFuture<?> endSoon(App a) {
        java.util.concurrent.CompletableFuture<Object> done = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            boolean end;
            synchronized (APPS) {
                end = a.on.isEmpty() && APPS.remove(a);
            }
            if (!end) {
                done.complete(null);
                return;
            }
            a.helper.close();
            a.helper.stopped().whenComplete((r, e) -> done.complete(null));
        });
        return done;
    }

    /** Where an app's sound goes once the wall that played it lets go: the monitor it belongs to now, if it's on one. */
    private static MonitorAudio nextWall(App a) {
        for (MonitorAudio o : a.on) if (SoundHome.playsHere(o.source.id(), a.pid)) return o;
        return null;
    }

    /** That app was just used on that monitor: its sound goes to that wall now (not at the wall's next look). */
    static void usedOn(long pid, String monitor) {
        synchronized (APPS) {
            for (App a : APPS) {
                if (a.pid != pid) continue;
                for (MonitorAudio m : a.on) if (m.source.id().equals(monitor)) a.wall = m;
            }
        }
    }

    /** Sound of one of the apps, sent to this wall. */
    private void receive(long pid, byte[] pcm, int off, int len) {
        Feed f;
        synchronized (feeds) {
            f = feeds.get(pid);
        }
        if (f != null) f.ring.write(pcm, off, len);
    }

    /** Every 10 ms: a little from each app, added together. */
    private void mix() {
        byte[] chunk = new byte[CHUNK], out = new byte[CHUNK];
        int[] sum = new int[CHUNK / 2];
        long start = System.nanoTime(), written = 0;
        while (running) {
            long due = (System.nanoTime() - start) * AudioCapture.RATE / 1_000_000_000L;
            while (written + 480 <= due) {
                java.util.Arrays.fill(sum, 0);
                boolean any = false;
                synchronized (feeds) {
                    for (Feed f : feeds.values()) {
                        int avail = f.ring.available();
                        boolean fadeIn = false;
                        if (!f.primed) {
                            if (avail < PRIME) continue; // a little buffer first, so its packets never run dry
                            f.primed = true;
                            fadeIn = true;
                        }
                        if (avail > MAX) { f.ring.trimTo(TRIM_TO); fadeIn = true; } // never let the delay grow
                        int got = f.ring.read(chunk, 0, CHUNK);
                        // starting, jumping or running dry is a soft 3 ms fade, never a click
                        if (fadeIn) PcmFades.fadeIn(chunk, got - got % 4);
                        if (got < CHUNK) {
                            PcmFades.fadeOut(chunk, got - got % 4);
                            f.primed = false; // ran dry: buffer up again
                        }
                        for (int i = 0; i + 1 < got; i += 2) sum[i / 2] += (short) ((chunk[i + 1] << 8) | (chunk[i] & 0xFF));
                        any |= got > 0;
                    }
                }
                for (int i = 0; i < sum.length; i++) {
                    int v = Math.max(-32768, Math.min(32767, sum[i]));
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                }
                if (any || !feeds.isEmpty()) sink.write(out, 0, CHUNK);
                written += 480;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private volatile java.util.concurrent.CompletableFuture<?> stoppedAll;

    @Override
    public void close() {
        SoundHome.gone(source.id());
        running = false;
        watcher.interrupt();
        mixer.interrupt();
        java.util.List<java.util.concurrent.CompletableFuture<?>> all = new java.util.ArrayList<>();
        synchronized (APPS) {
            for (App a : APPS) {
                if (!a.on.remove(this)) continue;
                if (a.wall == this) a.wall = nextWall(a);
                if (a.on.isEmpty()) all.add(endSoon(a));
            }
        }
        synchronized (feeds) {
            feeds.clear();
        }
        stoppedAll = java.util.concurrent.CompletableFuture.allOf(all.toArray(new java.util.concurrent.CompletableFuture[0]));
    }

    /** Done once every app has its own volume back. */
    @Override
    public java.util.concurrent.CompletableFuture<?> stopped() {
        java.util.concurrent.CompletableFuture<?> f = stoppedAll;
        return f != null ? f : java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
