import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

/** Keeps unsent bytes between writable events. Owns the supplied response body. */
public final class HttpResponseWriter {
    private final ByteBuffer headers;
    private final ByteBuffer body;

    public HttpResponseWriter(int status, String reason, String contentType, byte[] body) {
        String text = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        this.headers = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
        this.body = ByteBuffer.wrap(body);
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
