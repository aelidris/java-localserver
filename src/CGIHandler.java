import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public class CGIHandler {

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    /** Parsed CGI response. The HTTP writer must supply its own framing headers. */
    public static class Response {
        public final int statusCode;
        public final Map<String, List<String>> headers;
        private final byte[] body;

        private Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
            this.statusCode = statusCode;
            Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            this.headers = Collections.unmodifiableMap(copy);
            this.body = body;
        }

        public byte[] getBody() {
            return body.clone();
        }
    }

    /** Parses CRLF or LF headers while preserving binary bodies and repeated cookies. */
    public static Response parseResponse(byte[] output) throws IOException {
        Objects.requireNonNull(output, "output");
        if (output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("CGI output limit exceeded");
        }
        int headerEnd = -1;
        int bodyStart = -1;
        for (int i = 0; i < output.length && i <= MAX_HEADER_BYTES; i++) {
            if (i + 3 < output.length && output[i] == '\r' && output[i + 1] == '\n'
                    && output[i + 2] == '\r' && output[i + 3] == '\n') {
                headerEnd = i;
                bodyStart = i + 4;
                break;
            }
            if (i + 1 < output.length && output[i] == '\n' && output[i + 1] == '\n') {
                headerEnd = i;
                bodyStart = i + 2;
                break;
            }
        }
        if (headerEnd < 0) {
            throw new IOException("Missing or oversized CGI response headers");
        }
        String headerText = new String(output, 0, headerEnd, StandardCharsets.ISO_8859_1);
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int status = 200;
        boolean hasStatus = false;
        for (String line : headerText.split("\r?\n", -1)) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("Malformed CGI response header");
            }
            String name = line.substring(0, colon);
            String rawValue = line.substring(colon + 1);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || rawValue.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                throw new IOException("Invalid CGI response header");
            }
            String value = rawValue.trim();
            if (name.equalsIgnoreCase("Status")) {
                if (hasStatus || !value.matches("[2-5][0-9]{2}( [\\x20-\\x7e]*)?")) {
                    throw new IOException("Invalid CGI status");
                }
                status = Integer.parseInt(value.substring(0, 3));
                hasStatus = true;
            } else if (name.equalsIgnoreCase("Content-Length")) {
                // The HTTP writer calculates the actual byte length from getBody().
                continue;
            } else if (List.of("connection", "transfer-encoding", "keep-alive", "upgrade",
                    "trailer", "te", "proxy-authenticate", "proxy-authorization")
                    .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new IOException("CGI response contains a hop-by-hop header");
            } else {
                if ((name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Location"))
                        && (value.isEmpty() || headers.containsKey(name))) {
                    throw new IOException("Invalid or duplicate CGI " + name);
                }
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        if (!hasStatus && headers.containsKey("Location")) {
            status = 302;
        }
        byte[] body = Arrays.copyOfRange(output, bodyStart, output.length);
        if (status == 204 || status == 304) {
            if (body.length != 0) {
                throw new IOException("CGI status does not permit a response body");
            }
        } else if (!headers.containsKey("Content-Type") && !headers.containsKey("Location")) {
            throw new IOException("CGI response is missing Content-Type");
        }
        return new Response(status, headers, body);
    }

    /** A running CGI process. Close it when its client disconnects. */
    public static class Job implements AutoCloseable {
        private final Process process;
        private final Path inputFile;
        private final Path outputFile;
        private final Path errorFile;
        private final long startedAt;
        private boolean finished;

        private Job(Process process, Path inputFile,
                Path outputFile, Path errorFile) {
            this.process = process;
            this.inputFile = inputFile;
            this.outputFile = outputFile;
            this.errorFile = errorFile;
            this.startedAt = System.nanoTime();
        }

        /**
         * Returns null while running, then returns stdout once. Call regularly from
         * the event loop to enforce the five-second and combined 2 MiB output limits.
         * These are polling limits, not OS-enforced disk quotas.
         */
        public byte[] pollOutput() throws IOException {
            if (finished) {
                throw new IllegalStateException("CGI job is already finished");
            }
            try {
                if (System.nanoTime() - startedAt >= TIMEOUT_NANOS) {
                    throw new IOException("CGI execution timed out");
                }
                long errorBytes = Files.size(errorFile);
                if (Files.size(outputFile) > MAX_OUTPUT_BYTES - errorBytes) {
                    throw new IOException("CGI output limit exceeded");
                }
                if (process.isAlive()) {
                    return null;
                }
                if (process.exitValue() != 0) {
                    throw new IOException("CGI process exited with code " + process.exitValue());
                }
                byte[] output;
                try (var stream = Files.newInputStream(outputFile)) {
                    output = stream.readNBytes((int) MAX_OUTPUT_BYTES + 1);
                }
                if (output.length > MAX_OUTPUT_BYTES - Files.size(errorFile)) {
                    throw new IOException("CGI output limit exceeded");
                }
                close();
                return output;
            } catch (IOException | RuntimeException failure) {
                try {
                    close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        /** Returns null while running, otherwise a parsed response, exactly once. */
        public Response poll() throws IOException {
            byte[] output = pollOutput();
            return output == null ? null : parseResponse(output);
        }

        @Override
        public void close() throws IOException {
            finished = true;
            // Stop children before the parent so they cannot keep writing to our files.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            deleteFiles(inputFile, outputFile, errorFile);
        }
    }

    /**
     * Starts a trusted script without a shell or worker thread. The caller supplies
     * CGI request variables (REQUEST_METHOD, QUERY_STRING, PATH_INFO, CONTENT_TYPE,
     * SERVER_NAME, SERVER_PORT, etc.) and must authorize the script before calling.
     * Use an empty interpreter list for an executable script, or e.g. ["python3"].
     */
    public static Job start(Path script, List<String> interpreter,
            Map<String, String> environment, byte[] body) throws IOException {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(interpreter, "interpreter");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(body, "body");
        Path scriptPath = script.toRealPath();
        if (!Files.isRegularFile(scriptPath) || !Files.isReadable(scriptPath)) {
            throw new IOException("CGI script must be a readable regular file");
        }

        List<String> command = new ArrayList<>(interpreter);
        command.add(scriptPath.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scriptPath.getParent().toFile());
        Map<String, String> env = builder.environment();
        env.putAll(environment);
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SCRIPT_FILENAME", scriptPath.toString());
        env.put("CONTENT_LENGTH", Integer.toString(body.length));
        env.putIfAbsent("SERVER_PROTOCOL", "HTTP/1.1");
        env.putIfAbsent("REQUEST_METHOD", "GET");
        env.putIfAbsent("QUERY_STRING", "");
        env.putIfAbsent("PATH_INFO", "");

        Path input = null;
        Path output = null;
        Path errors = null;
        try {
            input = Files.createTempFile("cgi-input-", ".tmp");
            output = Files.createTempFile("cgi-output-", ".tmp");
            errors = Files.createTempFile("cgi-error-", ".tmp");
            Files.write(input, body);
            builder.redirectInput(input.toFile());
            builder.redirectOutput(output.toFile());
            builder.redirectError(errors.toFile());
            return new Job(builder.start(), input, output, errors);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteFiles(input, output, errors);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void deleteFiles(Path... paths) throws IOException {
        IOException failure = null;
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
