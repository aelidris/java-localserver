import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ChunkedRequestTest {
    private static final String HEADER = "POST /uploads/test HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n";
    private static int checks;

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    private static void valid(String body, String expected) throws Exception {
        byte[] wire = bytes(HEADER + body);
        for (int split = 0; split < wire.length; split++) {
            HttpRequestReader reader = new HttpRequestReader(1024);
            check(!reader.read(ByteBuffer.wrap(wire, 0, split)));
            check(reader.read(ByteBuffer.wrap(wire, split, wire.length - split)));
            check(Arrays.equals(reader.getBody(), bytes(expected)));
        }
        HttpRequestReader reader = new HttpRequestReader(1024);
        for (int i = 0; i < wire.length; i++) {
            check(reader.read(ByteBuffer.wrap(wire, i, 1)) == (i == wire.length - 1));
        }
        check(Arrays.equals(reader.getBody(), bytes(expected)));
    }

    private static void rejected(String wire, int limit, int status) throws Exception {
        try {
            new HttpRequestReader(limit).read(ByteBuffer.wrap(bytes(wire)));
            throw new AssertionError("Expected status " + status);
        } catch (HttpRequestReader.RequestException e) {
            check(e.status == status);
        }
    }

    public static void main(String[] args) throws Exception {
        valid("0\r\n\r\n", "");
        valid("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n", "Wikipedia");
        valid("5\r\n\u0000\u00ff\r\nA\r\n0\r\n\r\n", "\u0000\u00ff\r\nA");
        valid("A;flag;name=token;note=\"a;\\\"b\"\r\n0123456789\r\n00;last=yes\r\nX-Checksum: ignored\r\n\r\n", "0123456789");
        valid("1 \t; flag = \"ok\"\r\nx\r\n0\r\n\r\n", "x");
        for (String bad : new String[]{"G\r\n", "-1\r\n", "+1\r\n", " 1\r\n", "1 \r\n",
                "1;\r\n", "1;x=\r\n", "1;x=\"open\r\n", "1\n", "1\rX", "1\r\naX",
                "1\r\na\rX", "0\r\nBad trailer\r\n", "0\r\nContent-Length: 4\r\n",
                "0\r\nTransfer-Encoding: chunked\r\n", "0\r\nHost: other\r\n",
                "0\r\nX: bad\u0000value\r\n"}) {
            rejected(HEADER + bad, 1024, 400);
        }
        rejected(HEADER + "401\r\n", 1024, 413);
        rejected(HEADER + "2\r\nab\r\n2\r\n", 3, 413);
        rejected(HEADER + "FFFFFFFFFFFFFFFF\r\n", 1024, 413);
        rejected(HEADER + "1;x=" + "a".repeat(8192), 1024, 400);
        rejected(HEADER + "0\r\n" + ("X: " + "a".repeat(6000) + "\r\n").repeat(3), 1024, 400);
        rejected(HEADER + ("1;x=" + "a".repeat(6000) + "\r\nz\r\n").repeat(3), 1024, 400);
        for (String coding : new String[]{"", "gzip", "chunked, gzip", "chunked, chunked", "chunked,"}) {
            rejected(HEADER.replace("chunked", coding), 1024, 400);
        }
        rejected(HEADER.replace("chunked", "gzip, chunked"), 1024, 501);
        rejected(HEADER.replace("Host: localhost", "Content-Length: 0"), 1024, 400);
        HttpRequestReader exact = new HttpRequestReader(1);
        check(exact.read(ByteBuffer.wrap(bytes(HEADER + "1\r\nx\r\n0\r\n\r\n"))));
        HttpRequestReader zero = new HttpRequestReader(0);
        check(zero.read(ByteBuffer.wrap(bytes(HEADER + "0\r\n\r\n"))));
        System.out.println("PASS: " + checks + " chunked request checks");
    }
}
