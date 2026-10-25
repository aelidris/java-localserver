import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CGIHandler {

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final long MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    public static class Job {
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
    }
}