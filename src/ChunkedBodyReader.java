import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class ChunkedBodyReader {
    private enum State { SIZE, DATA, DATA_CR, DATA_LF, TRAILERS, DONE }
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final String QUOTED = "\"(?:[^\"\\\\]|\\\\.)*+\"";
    private static final Pattern EXTENSIONS = Pattern.compile(
            "(?:[ \\t]*;[ \\t]*" + TOKEN + "(?:[ \\t]*=[ \\t]*(?:" + TOKEN + "|" + QUOTED + "))?)*+");
    private static final Set<String> FORBIDDEN_TRAILERS = Set.of(
            "content-length", "transfer-encoding", "host", "connection", "trailer");

    private final long maxBodySize;
    private final ByteArrayOutputStream body;
    private final StringBuilder line = new StringBuilder();
    private State state = State.SIZE;
    private int remaining;
    private int metadataSize;
    private boolean carriageReturn;

    public ChunkedBodyReader(long maxBodySize, ByteArrayOutputStream body) {
        this.maxBodySize = maxBodySize;
        this.body = body;
    }

    public boolean read(ByteBuffer input) throws HttpRequestReader.RequestException {
        while (input.hasRemaining() && state != State.DONE) {
            if (state == State.DATA) {
                int count = Math.min(input.remaining(), remaining);
                byte[] chunk = new byte[count];
                input.get(chunk);
                body.writeBytes(chunk);
                remaining -= count;
                if (remaining == 0) state = State.DATA_CR;
                continue;
            }

            int next = input.get() & 0xff;
            if (state == State.DATA_CR || state == State.DATA_LF) {
                int expected = state == State.DATA_CR ? '\r' : '\n';
                if (next != expected) throw new HttpRequestReader.RequestException(400);
                state = state == State.DATA_CR ? State.DATA_LF : State.SIZE;
                continue;
            }

            String completed = readLine(next);
            if (completed != null) {
                if (state == State.SIZE) readSize(completed);
                else readTrailer(completed);
            }
        }
        return state == State.DONE;
    }

    // Keep a partial line between calls, including a CR waiting for its LF.
    private String readLine(int next) throws HttpRequestReader.RequestException {
        if (carriageReturn) {
            if (next != '\n') throw new HttpRequestReader.RequestException(400);
            carriageReturn = false;
            String completed = line.toString();
            line.setLength(0);
            return completed;
        }
        if (next == '\r') {
            carriageReturn = true;
        } else {
            if ((next < 32 && next != '\t') || next == 127 || line.length() >= 8192) {
                throw new HttpRequestReader.RequestException(400);
            }
            line.append((char) next);
        }
        return null;
    }

    private void readSize(String value) throws HttpRequestReader.RequestException {
        int end = 0;
        while (end < value.length() && "0123456789abcdefABCDEF".indexOf(value.charAt(end)) >= 0) end++;
        String extensions = value.substring(end);
        if (end == 0 || !EXTENSIONS.matcher(extensions).matches()) {
            throw new HttpRequestReader.RequestException(400);
        }
        addMetadata(extensions.length());
        long size;
        try {
            size = Long.parseLong(value.substring(0, end), 16);
        } catch (NumberFormatException e) {
            throw new HttpRequestReader.RequestException(413);
        }
        if (size > maxBodySize - body.size()) throw new HttpRequestReader.RequestException(413);
        remaining = (int) size;
        state = remaining == 0 ? State.TRAILERS : State.DATA;
    }

    private void readTrailer(String value) throws HttpRequestReader.RequestException {
        addMetadata(value.length() + 2);
        if (value.isEmpty()) {
            state = State.DONE;
            return;
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || !value.substring(0, colon).matches(TOKEN)
                || FORBIDDEN_TRAILERS.contains(value.substring(0, colon).toLowerCase(Locale.ROOT))) {
            throw new HttpRequestReader.RequestException(400);
        }
        // Valid trailers are consumed, but are not added to the body or headers.
    }

    private void addMetadata(int count) throws HttpRequestReader.RequestException {
        metadataSize += count;
        if (metadataSize > 16384) throw new HttpRequestReader.RequestException(400);
    }
}
