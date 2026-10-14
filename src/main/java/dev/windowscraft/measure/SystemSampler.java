package dev.windowscraft.measure;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Once a second while measuring: this game's CPU, memory, garbage collection and allocations
 * (all of it, and WindowsCraft's own threads), the helper programs it started (FFmpeg, wc-*),
 * and — on NVIDIA cards — the graphics card's load, video memory and video encoder/decoder use.
 */
final class SystemSampler {
    private final String side;
    private volatile boolean running = true;
    private Thread thread;
    private Process smi;
    private final int cores = Runtime.getRuntime().availableProcessors();
    private long ownCpuNanos, ownStartNanos = System.nanoTime();

    private static final Measure.Metric PROC_CPU = Measure.value("sys.process_cpu", "cores", 100, "the whole game's CPU use (1.00 = one core busy)");
    private static final Measure.Metric MACHINE_CPU = Measure.value("sys.machine_cpu", "%", 1, "the whole PC's CPU use");
    private static final Measure.Metric HEAP = Measure.value("sys.heap_used", "MB", 1, "Java memory in use (sampled)");
    private static final Measure.Metric ALLOC = Measure.value("sys.alloc_rate", "MB/s", 1, "Java allocations, the whole game");
    private static final Measure.Metric WC_ALLOC = Measure.value("sys.wc_alloc_rate", "MB/s", 1000, "Java allocations by WindowsCraft's own threads");
    private static final Measure.Metric WC_CPU = Measure.value("sys.wc_threads_cpu", "cores", 1000, "CPU of WindowsCraft's own Java threads (1.00 = one core)");
    private static final Measure.Metric GAME_THREAD_CPU = Measure.value("sys.main_thread_cpu", "cores", 1000, "CPU of the game's main thread (Render thread / Server thread)");
    private static final Measure.Metric GC_TIME = Measure.value("sys.gc_ms_per_s", "ms/s", 1, "time spent in garbage collection each second");
    private static final Measure.Counter GC_COUNT = Measure.counter("sys.gc_count", "garbage collections");
    private static final Measure.Metric HELPERS_CPU = Measure.value("sys.helpers_cpu", "cores", 1000, "CPU of all helper programs (FFmpeg, wc-*) together");
    private static final Measure.Metric HELPERS_RAM = Measure.value("sys.helpers_ram", "MB", 1, "memory of all helper programs together");
    private static final Measure.Metric GPU = Measure.value("gpu.util", "%", 1, "graphics card busy (whole PC, NVIDIA)");
    private static final Measure.Metric GPU_ENC = Measure.value("gpu.encoder", "%", 1, "video encoder busy (whole PC, NVIDIA)");
    private static final Measure.Metric GPU_DEC = Measure.value("gpu.decoder", "%", 1, "video decoder busy (whole PC, NVIDIA)");
    private static final Measure.Metric VRAM = Measure.value("gpu.vram_used", "MB", 1, "video memory in use (whole PC, NVIDIA)");
    private static final Measure.Metric ENC_SESSIONS = Measure.value("gpu.encode_sessions", "", 1, "video encodes running (whole PC, NVIDIA)");
    private static final Measure.Metric ENC_LATENCY = Measure.value("gpu.encode_latency", "ms", 1000, "the driver's average encode time (whole PC, NVIDIA)");

    SystemSampler(String side) { this.side = side; }

    void start() {
        thread = new Thread(this::run, "WindowsCraft measure");
        thread.setDaemon(true);
        thread.start();
        startSmi();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        Process p = smi;
        if (p != null) p.destroy();
    }

    void resetBaseline() {
        ownStartNanos = System.nanoTime();
        Thread t = thread;
        ownCpuNanos = t == null ? 0 : cpuOf(t.threadId());
    }

    /** The sampler's own CPU per second of measuring (part of the measuring's overhead). */
    double ownCpuMsPerSecond() {
        Thread t = thread;
        if (t == null) return 0;
        long cpu = cpuOf(t.threadId()) - ownCpuNanos;
        double secs = Math.max(1e-3, (System.nanoTime() - ownStartNanos) / 1e9);
        return cpu / 1e6 / secs;
    }

    private static long cpuOf(long tid) {
        if (tid < 0) return 0;
        try {
            long v = ManagementFactory.getThreadMXBean().getThreadCpuTime(tid);
            return Math.max(0, v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void run() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var tm = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Map<Long, long[]> prev = new HashMap<>(); // thread id → {cpu ns, allocated bytes}
        Map<Long, Long> helperPrev = new HashMap<>();
        long prevGcCount = gcCount(), prevGcTime = gcTime();
        long prevTotalAlloc = totalAlloc(tm);
        long prevWall = System.nanoTime(), prevProcCpu = os.getProcessCpuTime();
        boolean firstRound = true;
        // (getProcessCpuLoad() costs ~95 ms a call on Windows and listing child processes ~40 ms:
        // CPU time deltas instead, and the helpers' list refreshed only every 15 s)
        java.util.List<ProcessHandle> helpers = new java.util.ArrayList<>();
        long helpersListed = 0;
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (!Measure.ON) continue;
            long now = System.nanoTime();
            double dt = (now - prevWall) / 1e9;
            prevWall = now;
            try {
                long pcpu = os.getProcessCpuTime();
                if (pcpu >= 0 && prevProcCpu >= 0) PROC_CPU.record(Math.round((pcpu - prevProcCpu) / 1e7 / dt)); // (cores × 100)
                prevProcCpu = pcpu;
                double mc = os.getCpuLoad();
                if (mc >= 0) MACHINE_CPU.record(Math.round(mc * 100));
                var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                HEAP.record(heap.getUsed() >> 20);
                long gc = gcCount(), gt = gcTime();
                GC_COUNT.add(gc - prevGcCount);
                GC_TIME.record(Math.round((gt - prevGcTime) / dt));
                prevGcCount = gc;
                prevGcTime = gt;
                long ta = totalAlloc(tm);
                if (ta > 0 && prevTotalAlloc > 0) ALLOC.record(Math.round((ta - prevTotalAlloc) / dt / (1 << 20)));
                prevTotalAlloc = ta;

                // per thread: WindowsCraft's own, and the game's main thread
                long[] ids = tm.getAllThreadIds();
                ThreadInfo[] infos = tm.getThreadInfo(ids, 0);
                long wcCpu = 0, wcAlloc = 0, mainCpu = 0;
                Map<Long, long[]> next = new HashMap<>();
                for (ThreadInfo ti : infos) {
                    if (ti == null) continue;
                    String n = ti.getThreadName();
                    boolean wc = n.startsWith("WindowsCraft") && !n.startsWith("WindowsCraft measure"); // (the measuring itself is reported apart)
                    boolean main = n.equals("Render thread") || n.equals("Server thread");
                    if (!wc && !main) continue;
                    long id = ti.getThreadId();
                    long cpu = Math.max(0, tm.getThreadCpuTime(id)), al = Math.max(0, tm.getThreadAllocatedBytes(id));
                    long[] p = prev.get(id);
                    next.put(id, new long[]{cpu, al});
                    if (p == null) continue;
                    if (wc) {
                        wcCpu += cpu - p[0];
                        wcAlloc += al - p[1];
                    } else mainCpu += cpu - p[0];
                }
                prev = next;
                if (!firstRound) {
                    WC_CPU.record(Math.round(wcCpu / 1e6 / dt)); // (ms of CPU per s → shown /1000 = cores)
                    WC_ALLOC.record(Math.round(wcAlloc / dt / 1e3)); // KB/s → shown /1000 = MB/s
                    GAME_THREAD_CPU.record(Math.round(mainCpu / 1e6 / dt));
                }

                // helper programs this game started
                long helperCpu = 0, helperRam = 0;
                Map<Long, Long> hp = new HashMap<>();
                if (now - helpersListed > 15_000_000_000L) {
                    helpersListed = now;
                    helpers.clear();
                    for (ProcessHandle ph : (Iterable<ProcessHandle>) ProcessHandle.current().descendants()::iterator) helpers.add(ph);
                }
                for (ProcessHandle ph : helpers) {
                    if (!ph.isAlive()) continue;
                    ProcessHandle.Info info = ph.info();
                    String cmd = info.command().orElse("");
                    String exe = cmd.replace('\\', '/');
                    exe = exe.substring(exe.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                    if (!exe.startsWith("ffmpeg") && !exe.startsWith("wc-")) continue;
                    long c = info.totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);
                    Long was = helperPrev.get(ph.pid());
                    hp.put(ph.pid(), c);
                    if (was != null) helperCpu += c - was;
                    helperRam += WinMemory.workingSetMb(ph.pid());
                    Measure.note("helper " + exe.replace(".exe", ""), "running");
                }
                helperPrev = hp;
                if (!firstRound) HELPERS_CPU.record(Math.round(helperCpu / 1e6 / dt));
                HELPERS_RAM.record(helperRam);
                firstRound = false;
            } catch (Throwable t) {
                // sampling must never break the game
            }
        }
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionCount());
        return n;
    }

    private static long gcTime() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionTime());
        return n;
    }

    private static long totalAlloc(com.sun.management.ThreadMXBean tm) {
        try {
            return tm.getTotalThreadAllocatedBytes();
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** NVIDIA's own tool, once, printing a line a second while we measure (nothing on other cards). */
    private void startSmi() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=utilization.gpu,utilization.encoder,utilization.decoder,memory.used,encoder.stats.sessionCount,encoder.stats.averageLatency,name",
                    "--format=csv,noheader,nounits", "-lms", "1000").redirectErrorStream(true).start();
            smi = p;
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = r.readLine()) != null) {
                        String[] f = line.split(",\\s*");
                        if (f.length < 7) continue;
                        try {
                            GPU.record(Long.parseLong(f[0].trim()));
                            GPU_ENC.record(Long.parseLong(f[1].trim()));
                            GPU_DEC.record(Long.parseLong(f[2].trim()));
                            VRAM.record(Long.parseLong(f[3].trim()));
                            ENC_SESSIONS.record(Long.parseLong(f[4].trim()));
                            ENC_LATENCY.record(Long.parseLong(f[5].trim()));
                            Measure.note("gpu", f[6].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "WindowsCraft measure gpu");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            smi = null; // not an NVIDIA card (or no nvidia-smi): no GPU numbers
        }
    }
}
