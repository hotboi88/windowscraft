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
 * as a whole, so one that also has a window on your desk (the same browser on both) keeps its
 * sound on your desk: Windows can't split one app's sound by window. With
 * "hear it in the game", those apps go quiet on your speakers and play from the wall instead.
 */
final class MonitorAudio extends AudioCapture {
    private static final int CHUNK = 480 * 4;                       // 10 ms of 48 kHz stereo
    // each app keeps 60 ms queued (its packets arrive unevenly); more than 200 ms is cut back to 80
    private static final int PRIME = AudioCapture.BYTES_PER_SECOND * 60 / 1000 / 4 * 4, MAX = AudioCapture.BYTES_PER_SECOND / 5,
            TRIM_TO = AudioCapture.BYTES_PER_SECOND * 80 / 1000 / 4 * 4;

    /** Apps already told about (their sound stays on your desk). */
    private static final Set<Long> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final SourceInfo source;
    private final boolean hearInGame;
    private final Map<Long, Feed> feeds = new HashMap<>();
    private final Thread mixer, watcher;
    private volatile boolean running = true;

    private static final class Feed {
        final PcmRing ring = new PcmRing(AudioCapture.BYTES_PER_SECOND / 2);
        WasapiHelper helper;
        boolean primed;
    }

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

    /** Every couple of seconds: which apps have a window on the monitor? Each gets a capture. */
    private void watch() {
        long me = ProcessHandle.current().pid();
        while (running) {
            MonitorBackend.Mon m = InGameMonitors.monitorOf(source);
            MonitorBackend b = InGameMonitors.backend();
            if (m != null && b != null) {
                try {
                    Set<Long> pids = new HashSet<>();
                    Map<Long, String> titles = new HashMap<>();
                    for (MonitorBackend.Win w : b.windowsOn(m).get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        if (w.pid() > 0 && w.pid() != me) {
                            pids.add(w.pid());
                            titles.putIfAbsent(w.pid(), w.title());
                        }
                    }
                    // an app that also has a window on your desk (your browser open on both): its
                    // sound can't be split by window, so it stays where you are — on your desk —
                    // never pulled away from you onto the monitor
                    Set<Long> onDesk = new HashSet<>();
                    for (MonitorBackend.Win w : b.deskWindows().get(5, java.util.concurrent.TimeUnit.SECONDS)) onDesk.add(w.pid());
                    for (Long pid : pids) {
                        if (onDesk.contains(pid) && TOLD.add(pid)) InGameMonitors.sharedAppSound(titles.getOrDefault(pid, "That app"));
                    }
                    pids.removeAll(onDesk);
                    // a program on another monitor too (Brave on two of them): heard from just one
                    SoundHome.seen(source.id(), pids);
                    pids.removeIf(pid -> !SoundHome.playsHere(source.id(), pid));
                    update(pids);
                } catch (Exception e) {
                    WindowsCraftClient.LOGGER.debug("monitor audio: couldn't list apps", e);
                }
            }
            try {
                Thread.sleep(700); // (often: when you switch to Brave on another monitor, its sound follows quickly)
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void update(Set<Long> pids) {
        synchronized (feeds) {
            for (Long pid : new HashSet<>(feeds.keySet())) {
                if (!pids.contains(pid)) feeds.remove(pid).helper.close(); // gone from the monitor: its volume comes back
            }
            for (Long pid : pids) {
                if (feeds.containsKey(pid) || !running) continue;
                Feed f = new Feed();
                try {
                    List<String> args = new java.util.ArrayList<>(List.of("--include-pid", Long.toString(pid),
                            "--parent-pid", Long.toString(ProcessHandle.current().pid())));
                    Path state = null;
                    if (hearInGame) {
                        state = newDuckStateFile();
                        Files.createDirectories(state.getParent());
                        args.addAll(List.of("--duck", state.toString()));
                    }
                    f.helper = new WasapiHelper(args, "app audio", f.ring::write, state);
                    feeds.put(pid, f);
                } catch (Exception e) {
                    WindowsCraftClient.LOGGER.warn("monitor audio: couldn't capture app {}", pid, e);
                }
            }
            int n = feeds.size();
            status = n == 0 ? "apps on this monitor (none playing)" : "apps on this monitor (" + n + ")";
        }
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
        synchronized (feeds) {
            for (Feed f : feeds.values()) {
                f.helper.close();
                all.add(f.helper.stopped());
            }
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
