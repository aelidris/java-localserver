import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Locale;
import java.util.function.ToLongFunction;

// One reader per connection: collect headers, then a fixed-length or chunked body.
public class HttpRequestReader {
    private static final int MAX_HEADERS = 16 * 1024;
    private long maxBodySize;
    private final ToLongFunction<String> selectHost;
    private final ByteArrayOutputStream headers = new ByteArrayOutputStream();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private String[] lines;
    private int lastFourBytes;
    private int contentLength;
    private ChunkedBodyReader chunkedBody;
    private boolean continuePending;

    public HttpRequestReader(long maxBodySize) {
        this(maxBodySize, null);
    }

    public HttpRequestReader(long maxBodySize, ToLongFunction<String> selectHost) {
        this.maxBodySize = Math.min(maxBodySize, Integer.MAX_VALUE - 8L);
        this.selectHost = selectHost;
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
        if (chunkedBody != null) return chunkedBody.read(input);
        int count = Math.min(input.remaining(), contentLength - body.size());
        byte[] chunk = new byte[count];
        input.get(chunk);
        body.writeBytes(chunk);
        return body.size() == contentLength;
    }

    private void parseHeaders() throws RequestException {
        lines = headers.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[0].matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                || !first[1].startsWith("/") || first[1].contains("#")
                || first[1].chars().anyMatch(c -> c <= 32 || c == 127)
                || !first[2].equals("HTTP/1.1")) throw new RequestException(400);
        boolean hasLength = false;
        String transferEncoding = null;
        String expectation = null;
        String host = null;
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
            if (name.equalsIgnoreCase("Transfer-Encoding")) {
                transferEncoding = transferEncoding == null ? value : transferEncoding + "," + value;
            }
            if (name.equalsIgnoreCase("Host")) {
                if (host != null) throw new RequestException(400);
                host = hostName(value);
            }
            if (name.equalsIgnoreCase("Expect")) {
                expectation = expectation == null ? value : expectation + "," + value;
            }
        }

        if (host == null) throw new RequestException(400);
        if (selectHost != null) maxBodySize = selectHost.applyAsLong(host);

        if (transferEncoding != null) {
            if (hasLength) throw new RequestException(400);
            String[] codings = transferEncoding.split(",", -1);
            for (int i = 0; i < codings.length; i++) {
                String coding = codings[i].trim();
                if (coding.isEmpty() || (coding.equalsIgnoreCase("chunked") && i != codings.length - 1)) {
                    throw new RequestException(400);
                }
            }
            if (!codings[codings.length - 1].trim().equalsIgnoreCase("chunked")) throw new RequestException(400);
            if (codings.length > 1) throw new RequestException(501);
            chunkedBody = new ChunkedBodyReader(maxBodySize, body);
        }
        if (length > maxBodySize) throw new RequestException(413);
        contentLength = (int) length;
        if (expectation != null) {
            if (!expectation.equalsIgnoreCase("100-continue")) throw new RequestException(417);
            continuePending = true;
        }
    }

    public boolean hasData() {
        return headers.size() > 0;
    }

    public int bodySize() { return body.size(); }

    public boolean takeContinue() {
        if (lines == null || !continuePending) return false;
        continuePending = false;
        return true;
    }

    private static String hostName(String value) throws RequestException {
        try {
            URI uri = URI.create("http://" + value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || value.endsWith(":") || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        } catch (IllegalArgumentException e) {
            throw new RequestException(400);
        }
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
