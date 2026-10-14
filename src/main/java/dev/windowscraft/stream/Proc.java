package dev.windowscraft.stream;

import dev.windowscraft.WindowsCraftClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Small helpers for running FFmpeg processes and local sockets. */
final class Proc {
    static Process start(List<String> cmd, String logTag) throws IOException {
        Process p = new ProcessBuilder(cmd).start();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) WindowsCraftClient.LOGGER.info("[{}] {}", logTag, line);
            } catch (IOException ignored) {
            }
        }, "WindowsCraft " + logTag + " log");
        err.setDaemon(true);
        err.start();
        return p;
    }

    /** Loopback-only listening socket on a free port (FFmpeg connects to it for audio). */
    static ServerSocket loopbackListener() throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        s.setSoTimeout(15_000);
        return s;
    }

    static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }

    static void kill(Process p) {
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
        }
    }

    static Socket connectRelay(String host, int port, String role, String token, String streamId) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        // publishing: keep the unsent backlog small, so a connection that's too slow is noticed (and
        // the stream turned down) within a second or two instead of seconds of video piling up
        if (role.startsWith("PUB")) s.setSendBufferSize(512 * 1024); // (PUB and PUB2)
        s.connect(new java.net.InetSocketAddress(host, port), 5000);
        s.setSoTimeout(15_000);
        dev.windowscraft.relay.RelayProtocol.writeLine(s.getOutputStream(), String.join(" ", dev.windowscraft.relay.RelayProtocol.MAGIC, role, token, streamId));
        String reply = dev.windowscraft.relay.RelayProtocol.readLine(s.getInputStream(), 200);
        if (!reply.equals("OK")) {
            s.close();
            throw new IOException("relay said: " + reply.replaceFirst("^ERR ", ""));
        }
        s.setSoTimeout(0);
        return s;
    }

    /** A UDP viewer's handshake: the control connection, its session and the UDP port to use, and whether the relay takes input on it. */
    record UdpRelay(Socket control, long session, int udpPort, boolean takesInput) {}

    static UdpRelay connectRelayUdp(String host, int port, String token, String streamId) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new java.net.InetSocketAddress(host, port), 5000);
        s.setSoTimeout(15_000);
        dev.windowscraft.relay.RelayProtocol.writeLine(s.getOutputStream(), String.join(" ", dev.windowscraft.relay.RelayProtocol.MAGIC, "SUBU", token, streamId));
        String[] reply = dev.windowscraft.relay.RelayProtocol.readLine(s.getInputStream(), 200).split(" ");
        if (reply.length < 3 || !reply[0].equals("OK")) {
            s.close();
            throw new IOException("relay said: " + String.join(" ", reply).replaceFirst("^ERR ", ""));
        }
        s.setSoTimeout(0);
        return new UdpRelay(s, Long.parseUnsignedLong(reply[1], 16), Integer.parseInt(reply[2]), reply.length >= 4 && reply[3].equals("C"));
    }

    private Proc() {}
}
