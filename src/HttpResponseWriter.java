import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/** Keeps unsent bytes between writable events. Owns the supplied response body. */
public final class HttpResponseWriter {
    private final ByteBuffer headers;
    private final ByteBuffer body;

    private HttpResponseWriter(byte[] headers, byte[] body) {
        this.headers = ByteBuffer.wrap(headers);
        this.body = ByteBuffer.wrap(body);
    }

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

    public static HttpResponseWriter fromCgi(CGIHandler.Response response) {
        byte[] body = response.getBody();
        String reason;
        switch (response.statusCode) {
            case 200: reason = "OK"; break;
            case 201: reason = "Created"; break;
            case 202: reason = "Accepted"; break;
            case 204: reason = "No Content"; break;
            case 301: reason = "Moved Permanently"; break;
            case 302: reason = "Found"; break;
            case 303: reason = "See Other"; break;
            case 304: reason = "Not Modified"; break;
            case 307: reason = "Temporary Redirect"; break;
            case 308: reason = "Permanent Redirect"; break;
            default: reason = ""; // HTTP permits an empty reason phrase; the status code is authoritative.
        }
        StringBuilder headers = new StringBuilder("HTTP/1.1 " + response.statusCode + " " + reason + "\r\n");
        response.headers.forEach((name, values) -> values.forEach(value -> headers.append(name).append(": ")
                .append(value).append("\r\n")));
        if (response.statusCode != 204 && response.statusCode != 304) {
            headers.append("Content-Length: ").append(body.length).append("\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        return new HttpResponseWriter(headers.toString().getBytes(StandardCharsets.ISO_8859_1), body);
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
