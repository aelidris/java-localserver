import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Incremental chunk framing decoder; only decoded data enters the request body. */
final class ChunkedBodyReader {
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_METADATA_BYTES = 16384;
    private enum State { SIZE, DATA, DATA_CR, DATA_LF, TRAILERS, DONE }

    private final long maxBodySize;
    private final ByteArrayOutputStream body;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private State state = State.SIZE;
    private boolean lineCR;
    private int remaining;
    private int trailerBytes;
    private int extensionBytes;

    ChunkedBodyReader(long maxBodySize, ByteArrayOutputStream body) {
        this.maxBodySize = Math.min(maxBodySize, Integer.MAX_VALUE - 8L);
        this.body = body;
    }

    void consume(ByteBuffer input) throws HttpRequestReader.RequestException {
        while (input.hasRemaining() && state != State.DONE) {
            switch (state) {
                case SIZE:
                case TRAILERS:
                    String value = readLine(input);
                    if (value == null) {
                        return;
                    }
                    if (state == State.SIZE) {
                        readSize(value);
                    } else if (value.isEmpty()) {
                        state = State.DONE;
                    } else {
                        int colon = HttpRequestReader.validateFieldLine(value);
                        String name = value.substring(0, colon);
                        if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding")) {
                            fail(400);
                        }
                        // Trailer metadata is deliberately discarded, never merged with headers.
                    }
                    break;
                case DATA:
                    int count = Math.min(input.remaining(), remaining);
                    byte[] data = new byte[count];
                    input.get(data);
                    body.write(data, 0, data.length);
                    remaining -= count;
                    if (remaining == 0) {
                        state = State.DATA_CR;
                    }
                    break;
                case DATA_CR:
                    if (input.get() != '\r') {
                        fail(400);
                    }
                    state = State.DATA_LF;
                    break;
                case DATA_LF:
                    if (input.get() != '\n') {
                        fail(400);
                    }
                    state = State.SIZE;
                    break;
                default:
                    throw new IllegalStateException("Unexpected chunk state");
            }
        }
    }

    private String readLine(ByteBuffer input) throws HttpRequestReader.RequestException {
        while (input.hasRemaining()) {
            int value = input.get() & 0xff;
            if (state == State.TRAILERS && ++trailerBytes > MAX_METADATA_BYTES) {
                fail(400);
            }
            if (lineCR) {
                if (value != '\n') {
                    fail(400);
                }
                lineCR = false;
                String result = new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
                line.reset();
                return result;
            }
            if (value == '\r') {
                lineCR = true;
            } else {
                if (value == '\n' || line.size() >= MAX_LINE_BYTES) {
                    fail(400);
                }
                line.write(value);
            }
        }
        return null;
    }

    private void readSize(String value) throws HttpRequestReader.RequestException {
        int end = 0;
        while (end < value.length() && isHex(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            fail(400);
        }
        extensionBytes += value.length() - end;
        if (extensionBytes > MAX_METADATA_BYTES) {
            fail(400);
        }
        validateExtensions(value, end);
        long size;
        try {
            size = Long.parseLong(value.substring(0, end), 16);
        } catch (NumberFormatException e) {
            fail(413);
            return;
        }
        if (size > maxBodySize - body.size()) {
            fail(413);
        }
        remaining = (int) size;
        state = remaining == 0 ? State.TRAILERS : State.DATA;
    }

    private static void validateExtensions(String value, int index) throws HttpRequestReader.RequestException {
        while (index < value.length()) {
            index = skipWhitespace(value, index);
            if (index == value.length() || value.charAt(index++) != ';') {
                fail(400);
            }
            index = skipWhitespace(value, index);
            int start = index;
            while (index < value.length() && isTokenChar(value.charAt(index))) {
                index++;
            }
            if (index == start) {
                fail(400);
            }
            int afterName = skipWhitespace(value, index);
            if (afterName < value.length() && value.charAt(afterName) == '=') {
                index = skipWhitespace(value, afterName + 1);
                if (index < value.length() && value.charAt(index) == '"') {
                    index = quotedValueEnd(value, index + 1);
                } else {
                    start = index;
                    while (index < value.length() && isTokenChar(value.charAt(index))) {
                        index++;
                    }
                    if (index == start) {
                        fail(400);
                    }
                }
            }
        }
    }

    private static int quotedValueEnd(String value, int index) throws HttpRequestReader.RequestException {
        while (index < value.length()) {
            char c = value.charAt(index++);
            if (c == '"') {
                return index;
            }
            if (c == '\\') {
                if (index == value.length()) {
                    fail(400);
                }
                c = value.charAt(index++);
            }
            if ((c < 32 && c != '\t') || c == 127) {
                fail(400);
            }
        }
        fail(400);
        return index;
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && (value.charAt(index) == ' ' || value.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isTokenChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static void fail(int status) throws HttpRequestReader.RequestException {
        throw new HttpRequestReader.RequestException(status);
    }

    boolean isComplete() {
        return state == State.DONE;
    }
}
