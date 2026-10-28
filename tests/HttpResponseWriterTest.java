import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class HttpResponseWriterTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] binary = new byte[20000];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        verify(new HttpResponseWriter(200, "OK", "application/octet-stream", binary),
                "200 OK", binary);
        byte[] unicode = "تم رفع الملف".getBytes(StandardCharsets.UTF_8);
        verify(new HttpResponseWriter(201, "Created", "text/plain; charset=UTF-8", unicode),
                "201 Created", unicode);
        verify(new HttpResponseWriter(200, "OK", "text/plain", new byte[0]), "200 OK", new byte[0]);

        error fallback = new error(new ConfigLoader("unused"));
        String[] reasons = {"400 Bad Request", "403 Forbidden", "404 Not Found", "405 Method Not Allowed",
                "413 Payload Too Large", "500 Internal Server Error", "501 Not Implemented", "503 Service Unavailable"};
        for (String reason : reasons) {
            int status = Integer.parseInt(reason.substring(0, 3));
            verify(fallback.createResponse(status), reason,
                    ("<html><body><h1>" + reason + "</h1></body></html>").getBytes(StandardCharsets.UTF_8));
        }
        Path page = Files.createTempFile("response-error-", ".html");
        try {
            Files.write(page, binary);
            error custom = new error(new ConfigLoader("unused") {
                @Override
                public String getErrorPagePath(int status) {
                    return page.toString();
                }
            });
            verify(custom.createResponse(404), "404 Not Found", binary);
            Files.delete(page);
            verify(custom.createResponse(404), "404 Not Found",
                    "<html><body><h1>404 Not Found</h1></body></html>".getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(page);
        }

        HttpResponseWriter failed = new HttpResponseWriter(200, "OK", "text/plain", binary);
        try {
            failed.writeTo(new SlowChannel() {
                @Override
                public int write(ByteBuffer source) throws IOException {
                    throw new IOException("disconnected");
                }
            });
            throw new AssertionError("Write failure was swallowed");
        } catch (IOException expected) {
            check(!failed.isComplete(), "failed response is not complete");
        }
        System.out.println("Passed " + checks + " response writer checks");
    }

    private static void verify(HttpResponseWriter writer, String status, byte[] body) throws Exception {
        SlowChannel channel = new SlowChannel();
        check(!writer.isComplete(), "headers must be sent even for empty bodies");
        check(writer.writeTo(channel) == 0 && !writer.isComplete(), "zero write keeps response pending");
        int attempts = 0;
        while (!writer.isComplete()) {
            int callsBefore = channel.calls;
            writer.writeTo(channel);
            if (++attempts > 100000 || channel.calls != callsBefore + 1) {
                throw new AssertionError("Writer must yield after each write attempt");
            }
        }
        byte[] response = channel.bytes.toByteArray();
        String text = new String(response, StandardCharsets.ISO_8859_1);
        int bodyStart = text.indexOf("\r\n\r\n") + 4;
        check(bodyStart >= 4, "header separator");
        String headers = text.substring(0, bodyStart);
        check(headers.startsWith("HTTP/1.1 " + status + "\r\n"), "status line");
        check(headers.contains("\r\nContent-Length: " + body.length + "\r\n"), "length counts bytes");
        check(headers.contains("\r\nConnection: close\r\n"), "explicit connection close");
        check(Arrays.equals(Arrays.copyOfRange(response, bodyStart, response.length), body),
                "complete body without dropped or repeated bytes");
        int calls = channel.calls;
        check(writer.writeTo(channel) == 0 && channel.calls == calls, "completed response is not sent twice");
        check(channel.isOpen(), "connection lifecycle belongs to the server");
    }

    private static class SlowChannel implements WritableByteChannel {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int calls;
        boolean open = true;

        @Override
        public int write(ByteBuffer source) throws IOException {
            int allowance = new int[] {0, 1, 7, 0, 31}[calls++ % 5];
            int count = Math.min(source.remaining(), allowance);
            for (int i = 0; i < count; i++) {
                bytes.write(source.get() & 0xff);
            }
            return count;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
