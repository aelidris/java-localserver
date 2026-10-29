import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// One reader per connection: first collect headers, then Content-Length bytes.
public class HttpRequestReader {
    private static final int MAX_HEADERS = 16 * 1024;
    private final long maxBodySize;
    private final ByteArrayOutputStream headers = new ByteArrayOutputStream();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private String[] lines;
    private int lastFourBytes;
    private int contentLength;

    public HttpRequestReader(long maxBodySize) {
        this.maxBodySize = Math.min(maxBodySize, Integer.MAX_VALUE - 8L);
    }

    public boolean read(ByteBuffer input) throws RequestException {
        while (lines == null && input.hasRemaining()) {
            if (headers.size() == MAX_HEADERS) throw new RequestException(400);
            int next = input.get() & 0xff;
            headers.write(next);
            lastFourBytes = (lastFourBytes << 8) | next;
            if (lastFourBytes == 0x0d0a0d0a) parseHeaders();
        }

        if (lines == null) return false;
        int count = Math.min(input.remaining(), contentLength - body.size());
        byte[] chunk = new byte[count];
        input.get(chunk);
        body.writeBytes(chunk);
        return body.size() == contentLength;
    }

    private void parseHeaders() throws RequestException {
        lines = headers.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        boolean hasLength = false;
        boolean hasTransferEncoding = false;
        long length = 0;

        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new RequestException(400);
            String name = lines[i].substring(0, colon);
            String value = lines[i].substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || lines[i].chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                throw new RequestException(400);
            }
            if (name.equalsIgnoreCase("Content-Length")) {
                if (hasLength || !value.matches("[0-9]+")) throw new RequestException(400);
                hasLength = true;
                try {
                    length = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new RequestException(400);
                }
            }
            if (name.equalsIgnoreCase("Transfer-Encoding")) hasTransferEncoding = true;
        }

        // Chunked decoding is the next step; never save its framing as file data.
        if (hasTransferEncoding) throw new RequestException(hasLength ? 400 : 501);
        if (length > maxBodySize) throw new RequestException(413);
        contentLength = (int) length;
    }

    public boolean hasData() {
        return headers.size() > 0;
    }

    public String[] getHeaderLines() {
        return lines;
    }

    public byte[] getBody() {
        return body.toByteArray();
    }

    public static class RequestException extends Exception {
        public final int status;

        public RequestException(int status) {
            this.status = status;
        }
    }
}
