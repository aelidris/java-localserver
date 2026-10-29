import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

// ByteBuffer remembers how much was sent, even when a write sends zero bytes.
public class HttpResponseWriter {
    private final ByteBuffer headers;
    private final ByteBuffer body;

    public HttpResponseWriter(int status, String reason, String contentType, byte[] body) {
        this(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8), body);
    }

    private HttpResponseWriter(byte[] headers, byte[] body) {
        this.headers = ByteBuffer.wrap(headers);
        this.body = ByteBuffer.wrap(body);
    }

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
        if (body.hasRemaining()) return channel.write(body);
        return 0;
    }

    public boolean isComplete() {
        return !headers.hasRemaining() && !body.hasRemaining();
    }
}
