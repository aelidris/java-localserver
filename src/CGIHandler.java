import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class CGIHandler {

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

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
