import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CGIHandlerTest {
    private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path script = Files.createTempFile(TEMP, "fixture with spaces-", ".sh");
        try {
            Files.writeString(script, "printf '%s|%s|%s|%s|' \"$REQUEST_METHOD\" "
                    + "\"$QUERY_STRING\" \"$PATH_INFO\" \"$CONTENT_LENGTH\"\ncat\n");
            byte[] body = new byte[] { 0, 1, (byte) 255, 10 };
            try (var job = CGIHandler.start(script, List.of("/bin/sh"),
                    Map.of("REQUEST_METHOD", "POST", "QUERY_STRING", "x=1", "PATH_INFO", "/extra",
                            "CONTENT_LENGTH", "999"), body)) {
                byte[] prefix = "POST|x=1|/extra|4|".getBytes(StandardCharsets.UTF_8);
                byte[] expected = Arrays.copyOf(prefix, prefix.length + body.length);
                System.arraycopy(body, 0, expected, prefix.length, body.length);
                check(Arrays.equals(await(job), expected), "environment and binary stdin/stdout");
                try {
                    job.pollOutput();
                    throw new AssertionError("Finished job was reused");
                } catch (IllegalStateException expectedError) {
                    checks++;
                }
            }
            checkClean();

            expectFailure(script, "exit 7\n", "exited with code 7");
            expectFailure(script, "head -c 2097153 /dev/zero\n", "output limit");
            expectFailure(script, "head -c 2097153 /dev/zero >&2\n", "output limit");
            expectFailure(script, "exec sleep 30\n", "timed out");

            Files.writeString(script, "exec sleep 30\n");
            try (var job = start(script)) {
                long before = System.nanoTime();
                check(job.pollOutput() == null, "running job returns null");
                check(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "poll does not wait");
            }
            checkClean();
            try {
                CGIHandler.start(script, List.of("/no-such-cgi-interpreter"), Map.of(), new byte[0]);
                throw new AssertionError("Invalid interpreter was accepted");
            } catch (IOException expected) {
                checks++;
            }
            checkClean();
            testResponses(script);
            System.out.println("Passed " + checks + " CGI checks");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private static void testResponses(Path script) throws Exception {
        byte[] header = ("Status: 201 Created\r\nContent-Type: application/octet-stream\r\n"
                + "Set-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 999\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = Arrays.copyOf(header, header.length + 3);
        raw[header.length] = (byte) 255;
        raw[header.length + 1] = 0;
        raw[header.length + 2] = 10;
        var response = CGIHandler.parseResponse(raw);
        check(response.statusCode == 201, "explicit CGI status");
        check(response.headers.get("set-cookie").equals(List.of("a=1", "b=2")), "repeated cookies");
        check(!response.headers.containsKey("Content-Length"), "discard script framing");
        check(Arrays.equals(response.getBody(), new byte[] { (byte) 255, 0, 10 }), "binary response");
        check(parse("Content-Type: text/plain\n\nhello").statusCode == 200, "LF default status");
        check(parse("Location: https://example.com/\n\n").statusCode == 302, "redirect status");
        check(parse("Status: 204 No Content\n\n").getBody().length == 0, "empty response");
        for (String invalid : List.of("", "Content-Type: text/plain", "Broken\n\n",
                "Status: 999 Nope\n\n", "Status: 200 OK\nStatus: 201 Created\n\n",
                "Content-Type: text/plain\nTransfer-Encoding: chunked\n\n",
                "Content-Type: text/plain\nConnection: close\n\n",
                "Content-Type: text/plain\nX-Test: bad\rvalue\n\n",
                "Content-Type: text/plain\nX-Test: bad\u0000\n\n",
                "Content-Type: text/plain\nContent-Type: text/html\n\n",
                "X-Test: value\n\nbody", "Status: 204 No Content\n\nbody",
                "Content-Type: text/plain\nX-Test: " + "x".repeat(16384) + "\n\n")) {
            try {
                parse(invalid);
                throw new AssertionError("Invalid response accepted");
            } catch (IOException expected) {
                checks++;
            }
        }
        Files.writeString(script, "printf 'Content-Type: text/plain\\n\\nhello'\n");
        try (var job = start(script)) {
            CGIHandler.Response result = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (result == null && System.nanoTime() < deadline) {
                result = job.poll();
                if (result == null) {
                    Thread.sleep(10);
                }
            }
            check(result != null && Arrays.equals(result.getBody(), "hello".getBytes(StandardCharsets.UTF_8)),
                    "script to parsed response");
        }
        checkClean();
    }

    private static CGIHandler.Response parse(String value) throws IOException {
        return CGIHandler.parseResponse(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static CGIHandler.Job start(Path script) throws IOException {
        return CGIHandler.start(script, List.of("/bin/sh"), Map.of(), new byte[0]);
    }

    private static byte[] await(CGIHandler.Job job) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            byte[] output = job.pollOutput();
            if (output != null) {
                return output;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Job did not finish within test deadline");
    }

    private static void expectFailure(Path script, String source, String message) throws Exception {
        Files.writeString(script, source);
        try (var job = start(script)) {
            try {
                await(job);
                throw new AssertionError("Expected " + message);
            } catch (IOException e) {
                check(e.getMessage().contains(message), e.getMessage());
            }
        }
        checkClean();
    }

    private static void checkClean() throws IOException {
        try (var paths = Files.list(TEMP)) {
            check(paths.noneMatch(path -> path.getFileName().toString().startsWith("cgi-")),
                    "temporary files are removed");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
