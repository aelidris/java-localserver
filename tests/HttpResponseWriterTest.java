import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class HttpResponseWriterTest {
    private static int checks;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    // Simulate a connection that alternates between short writes and no progress.
    private static class SlowChannel implements WritableByteChannel {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int calls;
        boolean open = true;

        public int write(ByteBuffer source) throws IOException {
            if (!open) throw new IOException("Disconnected");
            int allowance = new int[]{0, 1, 7, 0, 3}[calls++ % 5];
            int count = Math.min(allowance, source.remaining());
            for (int i = 0; i < count; i++) output.write(source.get());
            return count;
        }

        public boolean isOpen() { return open; }
        public void close() { open = false; }
    }

    private static void drain(HttpResponseWriter writer, byte[] expected) throws Exception {
        SlowChannel channel = new SlowChannel();
        int attempts = 0;
        while (!writer.isComplete()) {
            check(++attempts < 10000);
            int calls = channel.calls;
            int before = channel.output.size();
            int written = writer.writeTo(channel);
            check(channel.calls == calls + 1); // No busy loop on zero writes.
            check(channel.output.size() == before + written);
        }
        check(Arrays.equals(expected, channel.output.toByteArray()));
        int calls = channel.calls;
        check(writer.writeTo(channel) == 0);
        check(channel.calls == calls);
        check(channel.isOpen()); // Server owns the connection lifecycle.
    }

    public static void main(String[] args) throws Exception {
        for (byte[] body : new byte[][]{new byte[0], "مرحبا".getBytes(StandardCharsets.UTF_8),
                new byte[]{0, (byte) 255, 13, 10, 42}}) {
            String headers = "HTTP/1.1 201 Created\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.writeBytes(headers.getBytes(StandardCharsets.UTF_8));
            expected.writeBytes(body);
            drain(new HttpResponseWriter(201, "Created", "application/octet-stream", body), expected.toByteArray());
        }
        byte[] raw = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\n\nCGI result".getBytes(StandardCharsets.UTF_8);
        drain(HttpResponseWriter.raw(raw), raw);
        drain(HttpResponseWriter.raw(new byte[0]), new byte[0]);
        SlowChannel disconnected = new SlowChannel();
        disconnected.close();
        HttpResponseWriter writer = HttpResponseWriter.raw(new byte[]{1, 2});
        try {
            writer.writeTo(disconnected);
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
            check(!writer.isComplete());
        }
        System.out.println("PASS: " + checks + " response writer checks");
    }
}
