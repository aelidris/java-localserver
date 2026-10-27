import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

public class ChunkedRequestTest {
    private static final String HEADERS = "POST /uploads/chunked.bin HTTP/1.1\r\nHost: localhost\r\n"
            + "Transfer-Encoding: ChUnKeD\r\n\r\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        String chunks = "2;name=token\r\n\u0000\u00ff\r\n3 ; note = \"a;\\\"b\";flag\r\n\r\nX\r\n"
                + "00;done\r\nX-Checksum: ignored\r\n\r\n";
        byte[] body = new byte[] {0, (byte) 255, 13, 10, 'X'};
        byte[] wire = bytes(HEADERS + chunks);
        for (int split = 0; split < wire.length; split++) {
            HttpRequestReader reader = new HttpRequestReader(body.length);
            check(!reader.consume(ByteBuffer.wrap(wire, 0, split)), "incomplete split " + split);
            check(reader.consume(ByteBuffer.wrap(wire, split, wire.length - split)), "complete split " + split);
            check(Arrays.equals(reader.getBody(), body), "decoded binary body " + split);
        }
        HttpRequestReader bytewise = new HttpRequestReader(body.length);
        for (int i = 0; i < wire.length; i++) {
            check(bytewise.consume(ByteBuffer.wrap(wire, i, 1)) == (i == wire.length - 1),
                    "bytewise completion " + i);
        }
        check(Arrays.equals(bytewise.getBody(), body), "bytewise binary body");

        HttpRequestReader empty = new HttpRequestReader(0);
        check(empty.consume(ByteBuffer.wrap(bytes(HEADERS + "0\r\n\r\n"))), "empty chunked body");
        check(empty.getBody().length == 0, "empty decoded bytes");
        HttpRequestReader exact = new HttpRequestReader(1);
        ByteBuffer extra = ByteBuffer.wrap(bytes(HEADERS + "1\r\nx\r\n0\r\n\r\nEXTRA"));
        check(exact.consume(extra) && extra.remaining() == 5, "stop at final trailer terminator");
        check(Arrays.equals(exact.getBody(), bytes("x")), "exclude chunk framing");
        for (String incomplete : new String[] {"", "1\r", "1\r\n", "1\r\nx", "1\r\nx\r",
                "1\r\nx\r\n", "0\r\n", "0\r\nX-Trailer: value\r\n"}) {
            HttpRequestReader reader = new HttpRequestReader(5);
            check(!reader.consume(ByteBuffer.wrap(bytes(HEADERS + incomplete))), "incomplete chunk framing");
            try {
                reader.getBody();
                throw new AssertionError("Incomplete chunked body was exposed");
            } catch (IllegalStateException expected) {
                checks++;
            }
        }

        for (String invalid : new String[] {"Z\r\n", "-1\r\n", "+1\r\n", "0x1\r\n", "\r\n", " 1\r\n",
                "1\n", "1\rx", "1\r\nx!", "1\r\nx\r!", "1;\r\n", "1;=x\r\n", "1;name=\r\n",
                "1;name=\"unfinished\r\n", "1;name=\"x\u0000\"\r\n", "1;name=\"x\"garbage\r\n",
                "1;name=x y\r\n", "0\r\nBadTrailer\r\n", "0\r\nX: a\u0000b\r\n",
                "0\r\nContent-Length: 0\r\n", "0\r\ntransfer-encoding: chunked\r\n"}) {
            reject(HEADERS + invalid, 5, 400);
        }
        reject(HEADERS + "6\r\n", 5, 413);
        reject(HEADERS + "3\r\nabc\r\n3\r\n", 5, 413);
        reject(HEADERS + "FFFFFFFFFFFFFFFFFFFFFFFF\r\n", 5, 413);
        reject(HEADERS + "1\r\n", 0, 413);
        reject(HEADERS + "1;name=" + "x".repeat(8192) + "\r\n", 5, 400);
        reject(HEADERS + ("1;name=" + "x".repeat(6000) + "\r\nx\r\n").repeat(3), 5, 400);
        reject(HEADERS + "0\r\n" + ("X: " + "x".repeat(1000) + "\r\n").repeat(17), 5, 400);
        for (String headers : new String[] {"Transfer-Encoding: chunked\r\nContent-Length: 0",
                "Content-Length: 0\r\nTransfer-Encoding: chunked",
                "Transfer-Encoding: chunked\r\ntransfer-encoding: chunked",
                "Transfer-Encoding: chunked, chunked", "Transfer-Encoding: chunked, gzip",
                "Transfer-Encoding: gzip", "Transfer-Encoding: chunked;foo=bar",
                "Transfer-Encoding: ,chunked", "Transfer-Encoding:"}) {
            reject("POST / HTTP/1.1\r\n" + headers + "\r\n\r\n", 5, 400);
        }
        reject("POST / HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", 5, 501);
        randomizedReads();
        System.out.println("Passed " + checks + " chunked request checks");
    }

    private static void randomizedReads() throws Exception {
        Random random = new Random(42);
        byte[] body = new byte[65536];
        random.nextBytes(body);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(bytes(HEADERS));
        for (int offset = 0; offset < body.length;) {
            int count = Math.min(body.length - offset, 1 + random.nextInt(2048));
            String size = Integer.toHexString(count);
            encoded.write(bytes((offset % 2 == 0 ? size.toUpperCase(java.util.Locale.ROOT) : size) + "\r\n"));
            encoded.write(body, offset, count);
            encoded.write(bytes("\r\n"));
            offset += count;
        }
        encoded.write(bytes("0\r\n\r\n"));
        byte[] wire = encoded.toByteArray();
        for (int trial = 0; trial < 20; trial++) {
            HttpRequestReader reader = new HttpRequestReader(body.length);
            for (int offset = 0; offset < wire.length;) {
                int count = Math.min(wire.length - offset, 1 + random.nextInt(4096));
                check(reader.consume(ByteBuffer.wrap(wire, offset, count)) == (offset + count == wire.length),
                        "random fragmentation completion");
                offset += count;
            }
            check(Arrays.equals(reader.getBody(), body), "64 KiB random binary chunks");
        }
    }

    private static void reject(String wire, long limit, int status) throws Exception {
        HttpRequestReader reader = new HttpRequestReader(limit);
        try {
            reader.consume(ByteBuffer.wrap(bytes(wire)));
            throw new AssertionError("Invalid chunked request accepted");
        } catch (HttpRequestReader.RequestException expected) {
            check(expected.statusCode == status, "expected error " + status + ", got " + expected.statusCode);
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
