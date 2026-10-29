import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// ByteBuffer remembers how much was sent, even when a write sends zero bytes.
public class HttpResponseWriter implements AutoCloseable {
    private ByteBuffer headers;
    private final ByteBuffer body;
    private FileChannel file;
    private long remaining;

    public HttpResponseWriter(int status, String reason, String contentType, byte[] body) {
        this(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8), body);
    }

    HttpResponseWriter(byte[] headers, byte[] body) {
        this.headers = ByteBuffer.wrap(headers);
        this.body = ByteBuffer.wrap(body);
    }

    private HttpResponseWriter(int status, String reason, String contentType, Path path) throws IOException {
        file = FileChannel.open(path, StandardOpenOption.READ);
        try {
            remaining = file.size();
            headers = ByteBuffer.wrap(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + remaining + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body = ByteBuffer.allocate(64 * 1024);
            body.limit(0);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    public static HttpResponseWriter file(int status, String reason, String contentType, Path path) throws IOException {
        return new HttpResponseWriter(status, reason, contentType, path);
    }

    public HttpResponseWriter addHeader(String name, String value) {
        if (!name.matches("[A-Za-z0-9-]+") || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid response header");
        }
        String text = StandardCharsets.ISO_8859_1.decode(headers.duplicate()).toString();
        headers = ByteBuffer.wrap((text.substring(0, text.length() - 2) + name + ": " + value + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        return this;
    }

    public int bufferedBytes() { return body.capacity(); }

    public static HttpResponseWriter raw(byte[] response) {
        return new HttpResponseWriter(new byte[0], response);
    }

    public static HttpResponseWriter redirect(int status, String location) {
        String reason = switch (status) {
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            default -> throw new IllegalArgumentException("Unsupported redirect status");
        };
        if (location.isEmpty() || location.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("Location must be an encoded URI without controls or spaces");
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nLocation: " + location
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        return new HttpResponseWriter(headers.getBytes(StandardCharsets.US_ASCII), new byte[0]);
    }

    // One write per writable event leaves time for the other connections.
    public int writeTo(WritableByteChannel channel) throws IOException {
        if (headers.hasRemaining()) return channel.write(headers);
        if (!body.hasRemaining() && remaining > 0) {
            body.clear();
            body.limit((int) Math.min(body.capacity(), remaining));
            int read = file.read(body);
            if (read < 0) throw new IOException("File truncated during response");
            remaining -= read;
            body.flip();
        }
        if (body.hasRemaining()) return channel.write(body);
        return 0;
    }

    public boolean isComplete() {
        return !headers.hasRemaining() && !body.hasRemaining() && remaining == 0;
    }

    public void close() {
        if (file != null) {
            try { file.close(); } catch (IOException ignored) { }
            file = null;
        }
    }
}
