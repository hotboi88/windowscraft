package dev.windowscraft.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Relay handshake. A client sends one line, {@code WCR1 PUB|SUB <token> <streamId>\n}, and gets
 * {@code OK\n} or {@code ERR <reason>\n}. After that the socket carries raw MPEG-TS bytes:
 * publisher → relay → every subscriber of that stream.
 */
public final class RelayProtocol {
    public static final String MAGIC = "WCR1";
    public static final int DEFAULT_PORT = 25585;

    public static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads one short ASCII line (no buffering past the newline, so the stream stays aligned). */
    public static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < max) {
            int c = in.read();
            if (c < 0) throw new IOException("connection closed during handshake");
            if (c == '\n') return sb.toString();
            sb.append((char) c);
        }
        throw new IOException("handshake line too long");
    }

    private RelayProtocol() {}
}
