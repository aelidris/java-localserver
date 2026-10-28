import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public class CGIRoutingTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("cgi-routing-");
        try {
            Path scripts = Files.createDirectory(temp.resolve("scripts"));
            Path script = Files.writeString(scripts.resolve("echo.py"), "print('Content-Type: text/plain\\n\\nhello')");
            Path outside = Files.writeString(temp.resolve("outside.py"), "outside");
            Files.writeString(scripts.resolve("data.txt"), "data");
            Files.createSymbolicLink(scripts.resolve("escape.py"), outside);
            Path configFile = temp.resolve("config.json");
            String document = "{\"routes\":{\"/\":{\"root\":" + quote(temp.toString())
                    + ",\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"]},\"/cgi\":{\"root\":" + quote(scripts.toString())
                    + ",\"default_file\":\"./echo.py\",\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"],\"cgi\":{\".py\":\"python3\"}},"
                    + "\"/single\":{\"root\":" + quote(script.toString())
                    + ",\"accepted_methods\":[\"GET\"],\"cgi\":{\".py\":\"python3\"}}}}";
            Files.writeString(configFile, document);
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            Router router = new Router(config);
            for (String method : new String[] {"GET", "POST", "DELETE"}) {
                Router.RequestResult result = router.route(method, "/cgi/echo.py/extra?x=1");
                check(result.status == Router.RouteStatus.CGI, "CGI dispatch before file mutation");
                check(result.cgi.script.equals(script.toRealPath()) && result.cgi.interpreter.equals("python3"), "authorized script and interpreter");
                check(result.cgi.scriptName.equals("/cgi/echo.py") && result.cgi.pathInfo.equals("/extra"), "script and path info split");
                check(result.cgi.pathTranslated.equals(temp.resolve("extra").toString()), "PATH_INFO translated through routes");
            }
            check(Files.exists(script), "DELETE dispatched without deleting source");
            check(router.route("GET", "/cgi").cgi.scriptName.equals("/cgi/echo.py"), "CGI directory index");
            check(router.route("POST", "/cgi/").status == Router.RouteStatus.CGI, "POST to CGI index");
            check(router.route("GET", "/cgi/echo.py/").cgi.pathInfo.equals("/"), "trailing path info retained");
            check(router.route("GET", "/single/extra").cgi.scriptName.equals("/single"), "file root CGI");
            check(router.route("POST", "/single").status == Router.RouteStatus.METHOD_NOT_ALLOWED, "method checked before CGI");
            check(router.route("GET", "/cgi/data.txt").status == Router.RouteStatus.OK, "unmapped extension remains static");
            check(router.route("GET", "/cgi/missing.py").status == Router.RouteStatus.NOT_FOUND, "missing script");
            check(router.route("POST", "/cgi/new.py").status == Router.RouteStatus.METHOD_NOT_ALLOWED, "no script upload on CGI route");
            check(router.route("DELETE", "/cgi/data.txt").status == Router.RouteStatus.METHOD_NOT_ALLOWED, "no file deletion on CGI route");
            check(router.route("GET", "/cgi/escape.py").status == Router.RouteStatus.FORBIDDEN, "script symlink escape denied");
            for (String method : new String[] {"GET", "POST", "DELETE"}) {
                check(router.route(method, "/scripts/echo.py").status == Router.RouteStatus.FORBIDDEN, "CGI source protected through alias");
            }
            check(router.route("POST", "/scripts/new.py").status == Router.RouteStatus.FORBIDDEN, "CGI root protected from new uploads");

            for (String mapping : new String[] {"null", "[]", "{}", "{\"py\":\"python3\"}", "{\".p/y\":\"python3\"}",
                    "{\".py\":[]}", "{\".py\":\"\"}", "{\".py\":\"python3 -u\"}", "{\".py\":\"python3\\n\"}"}) {
                Files.writeString(configFile, "{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"],\"cgi\":" + mapping + "}}}");
                try {
                    new ConfigLoader(configFile.toString()).load();
                    throw new AssertionError("Invalid CGI mapping accepted");
                } catch (ConfigLoader.ConfigException expected) {
                    check(expected.getMessage().contains(".cgi"), "CGI config field error");
                }
            }
            HttpRequestReader request = new HttpRequestReader(0);
            request.consume(ByteBuffer.wrap("GET / HTTP/1.1\r\ncontent-type: text/plain\r\nCookie: a=1\r\nCookie: b=2\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII)));
            check(request.getHeader("Content-Type").equals("text/plain") && request.getHeader("cookie").equals("a=1; b=2"),
                    "CGI request headers and repeated cookies");

            byte[] body = new byte[] {0, (byte) 255, 13, 10};
            byte[] header = "Status: 201 Created\r\nContent-Type: application/octet-stream\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 999\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
            byte[] output = Arrays.copyOf(header, header.length + body.length);
            System.arraycopy(body, 0, output, header.length, body.length);
            byte[] response = write(CGIHandler.parseResponse(output));
            String text = new String(response, StandardCharsets.ISO_8859_1);
            check(text.startsWith("HTTP/1.1 201 Created\r\n"), "CGI HTTP status");
            check(text.contains("Set-Cookie: a=1\r\nSet-Cookie: b=2\r\n"), "repeated response cookies");
            check(text.contains("Content-Length: 4\r\n") && !text.contains("999"), "server owns body framing");
            check(Arrays.equals(Arrays.copyOfRange(response, text.indexOf("\r\n\r\n") + 4, response.length), body), "binary CGI response");
            for (int status : new int[] {204, 304}) {
                text = new String(write(CGIHandler.parseResponse(("Status: " + status + "\n\n").getBytes(StandardCharsets.US_ASCII))), StandardCharsets.US_ASCII);
                check(!text.contains("Content-Length:") && text.endsWith("\r\n\r\n"), "bodyless status framing " + status);
            }
            text = new String(write(CGIHandler.parseResponse("Location: /new\n\n".getBytes(StandardCharsets.US_ASCII))), StandardCharsets.US_ASCII);
            check(text.startsWith("HTTP/1.1 302 Found") && text.contains("Location: /new\r\n"), "CGI redirect");
            System.out.println("Passed " + checks + " CGI routing/response checks");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static byte[] write(CGIHandler.Response parsed) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpResponseWriter writer = HttpResponseWriter.fromCgi(parsed);
        WritableByteChannel channel = new WritableByteChannel() {
            int calls;
            public int write(ByteBuffer buffer) {
                int count = calls++ % 3 == 0 ? 0 : Math.min(buffer.remaining(), 7);
                for (int i = 0; i < count; i++) { output.write(buffer.get()); }
                return count;
            }
            public boolean isOpen() { return true; }
            public void close() { }
        };
        for (int i = 0; !writer.isComplete() && i < 10000; i++) { writer.writeTo(channel); }
        check(writer.isComplete(), "CGI short/zero writes complete");
        return output.toByteArray();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
        checks++;
    }
}
