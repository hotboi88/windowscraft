package dev.windowscraft.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The relay tells the stream's owner a viewer can't keep up only when that's really so: a viewer
 * that reads everything never counts (not even right after it connects), one that reads far too
 * slowly does.
 */
class RelayBehindTest {
    private static final String STREAM = "0123456789abcdef0123456789abcdef";

    @Test
    void onlyRealSlownessIsReported() throws Exception {
        Path ts = Path.of(System.getProperty("java.io.tmpdir"), "wc-relaytest.ts");
        Process p;
        try {
            p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30",
                    "-t", "30", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "4000k", "-maxrate", "4000k", "-bufsize", "2000k",
                    "-g", "30", "-bf", "0", "-f", "mpegts", ts.toString()).inheritIO().start();
        } catch (Exception e) {
            Assumptions.abort("ffmpeg not installed");
            return;
        }
        Assumptions.assumeTrue(p.waitFor() == 0, "ffmpeg failed");
        byte[] data = Files.readAllBytes(ts);
        double seconds = 30;

        UUID owner = UUID.randomUUID();
        AtomicInteger behind = new AtomicInteger();
        try (RelayServer relay = new RelayServer(InetAddress.getLoopbackAddress(), 0, t -> owner, (pl, id) -> true)) {
            relay.onViewerBehind(id -> behind.incrementAndGet());
            Socket pub = connect(relay.port(), "PUB");
            AtomicInteger fastBehind = new AtomicInteger(-1);
            Thread fast = reader(relay.port(), 0);
            Thread publisher = new Thread(() -> {
                try (OutputStream out = pub.getOutputStream()) {
                    long start = System.nanoTime();
                    int step = 188 * 40;
                    for (int i = 0; i < data.length; i += step) {
                        // real time: the data is sent as fast as the video plays, not faster
                        long due = start + (long) (i / (double) data.length * seconds * 1e9);
                        long wait = due - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                        out.write(data, i, Math.min(step, data.length - i));
                        // the fast viewer alone, for the first 12 s
                        if (fastBehind.get() < 0 && System.nanoTime() - start > 12_000_000_000L) {
                            fastBehind.set(behind.get());
                            reader(relay.port(), 4_000); // then one that takes only ~4 KB/s
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            publisher.start();
            publisher.join();
            assertEquals(0, fastBehind.get(), "a viewer that keeps up is never reported");
            assertTrue(behind.get() >= 1, "a viewer far too slow is reported (" + behind.get() + ")");
            fast.interrupt();
            pub.close();
        }
    }

    private static Socket connect(int port, String role) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
        RelayProtocol.writeLine(s.getOutputStream(), String.join(" ", RelayProtocol.MAGIC, role, "token", STREAM));
        String reply = RelayProtocol.readLine(s.getInputStream(), 200);
        assertEquals("OK", reply);
        return s;
    }

    /** A viewer reading everything ({@code bytesPerSecond} 0) or only that much each second. */
    private static Thread reader(int port, int bytesPerSecond) {
        Thread t = new Thread(() -> {
            try (Socket s = connect(port, "SUB")) {
                if (bytesPerSecond > 0) s.setReceiveBufferSize(4096);
                InputStream in = s.getInputStream();
                byte[] buf = new byte[bytesPerSecond > 0 ? bytesPerSecond / 10 : 65536];
                while (in.read(buf) > 0) if (bytesPerSecond > 0) Thread.sleep(100);
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
