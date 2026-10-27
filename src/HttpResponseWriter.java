import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/** Keeps unsent bytes between writable events. Owns the supplied response body. */
public final class HttpResponseWriter {
    private final ByteBuffer headers;
    private final ByteBuffer body;

    public HttpResponseWriter(int status, String reason, String contentType, byte[] body) {
        this(status, reason, contentType, body, null);
    }

    private HttpResponseWriter(int status, String reason, String contentType, byte[] body, String location) {
        String text = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n";
        if (location != null) {
            text += "Location: " + location + "\r\n";
        }
        text += "Connection: close\r\n\r\n";
        this.headers = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
        this.body = ByteBuffer.wrap(body);
    }

    public static HttpResponseWriter redirect(int status, String location) {
        // ConfigLoader supplies an ASCII URI. Validate again at the header boundary.
        if (location == null || location.isEmpty() || location.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("Redirect Location must be a non-empty ASCII URI without whitespace");
        }
        String reason;
        switch (status) {
            case 301: reason = "Moved Permanently"; break;
            case 302: reason = "Found"; break;
            case 303: reason = "See Other"; break;
            case 307: reason = "Temporary Redirect"; break;
            case 308: reason = "Permanent Redirect"; break;
            default: throw new IllegalArgumentException("Unsupported redirect status: " + status);
        }
        return new HttpResponseWriter(status, reason, "text/plain; charset=UTF-8", new byte[0], location);
    }

    /** Makes at most one write attempt, including when the channel accepts zero bytes. */
    public int writeTo(WritableByteChannel channel) throws IOException {
        if (isComplete()) {
            return 0;
        }
        return channel.write(headers.hasRemaining() ? headers : body);
    }

    public boolean isComplete() {
        return !headers.hasRemaining() && !body.hasRemaining();
    }
}
