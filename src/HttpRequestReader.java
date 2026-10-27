import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Accumulates one Content-Length or chunked request across socket reads. */
public final class HttpRequestReader {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private final long maxBodySize;
    private final ByteArrayOutputStream headers = new ByteArrayOutputStream();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private int headerSuffix;
    private boolean headersComplete;
    private int contentLength;
    private ChunkedBodyReader chunkedBody;
    private String method;
    private String uri;
    private final Map<String, String> requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static final class RequestException extends Exception {
        public final int statusCode;

        RequestException(int statusCode) {
            this.statusCode = statusCode;
        }
    }

    public HttpRequestReader(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    /** Leaves any bytes after this request unread; the server closes after its response. */
    public boolean consume(ByteBuffer input) throws RequestException {
        while (!headersComplete && input.hasRemaining()) {
            int value = input.get() & 0xff;
            headers.write(value);
            headerSuffix = (headerSuffix << 8) | value;
            if (headers.size() >= 4 && headerSuffix == 0x0d0a0d0a) {
                parseHeaders();
                headersComplete = true;
                headers.reset();
            } else if (headers.size() >= MAX_HEADER_BYTES) {
                throw new RequestException(400);
            }
        }
        if (headersComplete && chunkedBody != null) {
            chunkedBody.consume(input);
        } else if (headersComplete) {
            int count = Math.min(input.remaining(), contentLength - body.size());
            byte[] chunk = new byte[count];
            input.get(chunk);
            body.write(chunk, 0, chunk.length);
        }
        return isComplete();
    }

    private void parseHeaders() throws RequestException {
        String text = new String(headers.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = text.substring(0, text.length() - 4).split("\r\n", -1);
        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || !isToken(requestLine[0])
                || !requestLine[1].startsWith("/")
                || requestLine[1].chars().anyMatch(c -> c <= 32 || c == 127)
                || !requestLine[2].equals("HTTP/1.1")) {
            throw new RequestException(400);
        }
        method = requestLine[0];
        uri = requestLine[1];
        String lengthValue = null;
        String transferEncoding = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = validateFieldLine(lines[i]);
            String name = lines[i].substring(0, colon);
            String value = lines[i].substring(colon + 1).trim();
            requestHeaders.merge(name, value, (first, next) -> first + (name.equalsIgnoreCase("Cookie") ? "; " : ", ") + next);
            if (name.equalsIgnoreCase("Transfer-Encoding")) {
                if (transferEncoding != null) {
                    throw new RequestException(400);
                }
                transferEncoding = value;
            }
            if (name.equalsIgnoreCase("Content-Length")) {
                if (lengthValue != null || !value.matches("[0-9]+")) {
                    throw new RequestException(400);
                }
                lengthValue = value;
            }
        }
        if (transferEncoding != null) {
            if (lengthValue != null) {
                throw new RequestException(400);
            }
            String[] codings = transferEncoding.split(",", -1);
            for (int i = 0; i < codings.length; i++) {
                String coding = codings[i].trim();
                if (!isToken(coding) || (coding.equalsIgnoreCase("chunked") && i != codings.length - 1)) {
                    throw new RequestException(400);
                }
            }
            if (!codings[codings.length - 1].trim().equalsIgnoreCase("chunked")) {
                throw new RequestException(400);
            }
            if (codings.length != 1) {
                throw new RequestException(501);
            }
            chunkedBody = new ChunkedBodyReader(maxBodySize, body);
        }
        if (lengthValue != null) {
            long length;
            try {
                length = Long.parseLong(lengthValue);
            } catch (NumberFormatException e) {
                throw new RequestException(413);
            }
            if (length > maxBodySize || length > Integer.MAX_VALUE - 8) {
                throw new RequestException(413);
            }
            contentLength = (int) length;
        }
    }

    static int validateFieldLine(String line) throws RequestException {
        int colon = line.indexOf(':');
        if (colon <= 0 || !isToken(line.substring(0, colon))
                || line.substring(colon + 1).chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
            throw new RequestException(400);
        }
        return colon;
    }

    private static boolean isToken(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    }

    public boolean isComplete() {
        return headersComplete && (chunkedBody != null ? chunkedBody.isComplete() : body.size() == contentLength);
    }

    public String getMethod() {
        return method;
    }

    public String getUri() {
        return uri;
    }

    public String getHeader(String name) {
        return requestHeaders.getOrDefault(name, "");
    }

    public byte[] getBody() {
        if (!isComplete()) {
            throw new IllegalStateException("Request is incomplete");
        }
        return body.toByteArray();
    }
}
