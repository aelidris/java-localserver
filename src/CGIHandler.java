import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class CGIHandler {
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_OUTPUT = 16 * 1024 * 1024;

    public static Job start(String scriptPath, byte[] body, Map<String, String> environment) throws IOException {
        Path script = Path.of(scriptPath).toRealPath();
        Job job = new Job(Files.createTempDirectory("localserver-cgi-"));
        try {
            Files.write(job.input, body);
            String interpreter = script.toString().endsWith(".py") ? "python3" : "perl";
            ProcessBuilder builder = new ProcessBuilder(interpreter, script.toString());
            builder.directory(script.getParent().toFile());
            builder.environment().putAll(environment);
            builder.environment().put("CONTENT_LENGTH", String.valueOf(body.length));
            builder.environment().put("SCRIPT_FILENAME", script.toString());
            builder.environment().put("PATH_INFO", script.toString());
            builder.environment().put("GATEWAY_INTERFACE", "CGI/1.1");
            builder.environment().put("SERVER_PROTOCOL", "HTTP/1.1");
            // Files avoid blocking on process pipes; an empty input file provides EOF too.
            builder.redirectInput(job.input.toFile());
            builder.redirectOutput(job.output.toFile());
            builder.redirectError(job.errors.toFile());
            job.process = builder.start();
            return job;
        } catch (IOException | RuntimeException e) {
            job.close();
            throw e;
        }
    }

    public static class Job implements AutoCloseable {
        private final Path directory, input, output, errors;
        private final long started = System.nanoTime();
        private Process process;
        private boolean closed;

        private Job(Path directory) {
            this.directory = directory;
            input = directory.resolve("stdin");
            output = directory.resolve("stdout");
            errors = directory.resolve("stderr");
        }

        // Called by the event loop. null means the script is still running.
        public HttpResponseWriter poll() throws IOException {
            if (closed) throw new IOException("CGI job is closed");
            try {
                if (System.nanoTime() - started >= TIMEOUT_NANOS) throw new IOException("CGI timed out");
                if (Files.size(output) + Files.size(errors) > MAX_OUTPUT) throw new IOException("CGI output is too large");
                if (process.isAlive()) return null;
                if (process.exitValue() != 0) throw new IOException("CGI exited with an error");
                byte[] bytes;
                try (var stream = Files.newInputStream(output)) {
                    bytes = stream.readNBytes(MAX_OUTPUT + 1);
                }
                if (bytes.length > MAX_OUTPUT) throw new IOException("CGI output is too large");
                return CGIResponse.parse(bytes);
            } finally {
                if (process != null && !process.isAlive()) close();
            }
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (process != null) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
            }
            for (Path path : new Path[]{input, output, errors, directory}) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { System.err.println("CGI cleanup failed: " + e.getMessage()); }
            }
        }
    }
}
