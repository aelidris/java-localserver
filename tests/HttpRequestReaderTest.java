import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class HttpRequestReaderTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] body = new byte[] {0, (byte) 255, 13, 10, (byte) 128};
        byte[] headers = bytes("POST /uploads/test.bin HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 5\r\n\r\n");
        byte[] wire = Arrays.copyOf(headers, headers.length + body.length);
        System.arraycopy(body, 0, wire, headers.length, body.length);
        for (int split = 0; split < wire.length; split++) {
            HttpRequestReader reader = new HttpRequestReader(5);
            check(!reader.consume(ByteBuffer.wrap(wire, 0, split)), "incomplete prefix " + split);
            check(reader.consume(ByteBuffer.wrap(wire, split, wire.length - split)), "complete suffix " + split);
            check(Arrays.equals(reader.getBody(), body), "binary body " + split);
            check(reader.getMethod().equals("POST") && reader.getUri().equals("/uploads/test.bin"),
                    "request line " + split);
        }

        HttpRequestReader bytewise = new HttpRequestReader(5);
        for (int i = 0; i < wire.length; i++) {
            check(bytewise.consume(ByteBuffer.wrap(new byte[] {wire[i]})) == (i == wire.length - 1),
                    "one byte at a time " + i);
        }
        byte[] largeBody = new byte[12000];
        for (int i = 0; i < largeBody.length; i++) {
            largeBody[i] = (byte) i;
        }
        HttpRequestReader large = new HttpRequestReader(largeBody.length);
        check(!large.consume(ByteBuffer.wrap(bytes("POST /uploads/large HTTP/1.1\r\n"
                + "content-length:\t12000\r\n\r\n"))), "headers alone do not complete upload");
        for (int offset = 0; offset < largeBody.length; offset += 4096) {
            int size = Math.min(4096, largeBody.length - offset);
            check(large.consume(ByteBuffer.wrap(largeBody, offset, size)) == (offset + size == largeBody.length),
                    "large upload progress");
        }
        check(Arrays.equals(largeBody, large.getBody()), "large upload preserved");

        for (String method : new String[] {"GET", "POST", "DELETE"}) {
            HttpRequestReader empty = new HttpRequestReader(0);
            check(empty.consume(ByteBuffer.wrap(bytes(method + " / HTTP/1.1\r\nHost: localhost\r\n\r\n"))),
                    "no body for " + method);
            check(empty.getBody().length == 0, "empty body");
        }
        HttpRequestReader exact = new HttpRequestReader(3);
        ByteBuffer extra = ByteBuffer.wrap(bytes("POST /uploads/a HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcEXTRA"));
        check(exact.consume(extra), "complete first request");
        check(extra.remaining() == 5 && Arrays.equals(exact.getBody(), bytes("abc")), "respect declared length");

        for (String invalid : new String[] {"", "-1", "+1", "abc", "1, 1"}) {
            reject("Content-Length: " + invalid + "\r\n", 400);
        }
        reject("Content-Length: 1\r\nContent-Length: 1\r\n", 400);
        reject("Content-Length: 1\r\ncontent-length: 2\r\n", 400);
        reject("Content-Length: 6\r\n", 413);
        reject("Content-Length: 999999999999999999999999\r\n", 413);
        reject("Transfer-Encoding: chunked\r\n", 400);
        reject("Content-Length: 1\r\nTransfer-Encoding: chunked\r\n", 400);
        reject("Content-Length : 1\r\n", 400);
        reject("BrokenHeader\r\n", 400);
        reject("X-Invalid: a\u0000b\r\n", 400);
        reject("X-Large: " + "a".repeat(16384) + "\r\n", 400);
        HttpRequestReader incomplete = new HttpRequestReader(5);
        incomplete.consume(ByteBuffer.wrap(headers));
        try {
            incomplete.getBody();
            throw new AssertionError("Incomplete body was exposed");
        } catch (IllegalStateException expected) {
            checks++;
        }
        System.out.println("Passed " + checks + " request reader checks");
    }

    private static void reject(String headers, int status) throws Exception {
        HttpRequestReader reader = new HttpRequestReader(5);
        try {
            reader.consume(ByteBuffer.wrap(bytes("POST /uploads/a HTTP/1.1\r\n" + headers + "\r\n")));
            throw new AssertionError("Invalid request was accepted");
        } catch (HttpRequestReader.RequestException expected) {
            check(expected.statusCode == status, "error status " + status);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
