import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class RedirectTest {
    private static int checks;
    private static Path file;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    private static ConfigLoader load(String redirect) throws Exception {
        Files.writeString(file, "{\"routes\":{\"/\":{},\"/old\":{\"root\":\"/nonexistent-redirect-root\","
                + "\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"],\"redirect\":" + redirect + "},"
                + "\"/old/locked\":{\"accepted_methods\":[\"GET\"],\"redirect\":{\"location\":\"/locked\"}}}}");
        ConfigLoader config = new ConfigLoader(file.toString());
        config.load();
        return config;
    }

    private static String response(ConfigLoader.RedirectConfig redirect) throws Exception {
        HttpResponseWriter writer = HttpResponseWriter.redirect(redirect.status(), redirect.location());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel slow = new WritableByteChannel() {
            int calls;
            public int write(ByteBuffer source) {
                if (++calls % 2 == 1) return 0;
                int count = Math.min(3, source.remaining());
                for (int i = 0; i < count; i++) bytes.write(source.get());
                return count;
            }
            public boolean isOpen() { return true; }
            public void close() { }
        };
        int attempts = 0;
        while (!writer.isComplete()) {
            if (++attempts > 2000) throw new AssertionError("Writer made no progress");
            writer.writeTo(slow);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        file = Files.createTempFile("redirect-test-", ".json");
        try {
            for (int code : new int[]{301, 302, 303, 307, 308}) {
                ConfigLoader config = load("{\"status\":" + code + ",\"location\":\"/new?x=1#part\"}");
                Router router = new Router(config);
                for (String method : new String[]{"GET", "POST", "DELETE"}) {
                    Router.RequestResult result = router.route(method, "/old/missing.py?original=ignored");
                    check(result.status == Router.RouteStatus.REDIRECT);
                    check(result.filePath == null);
                    check(result.redirect.status() == code);
                    check(result.redirect.location().equals("/new?x=1#part"));
                }
                check(router.route("PATCH", "/old").status == Router.RouteStatus.METHOD_NOT_ALLOWED);
                check(router.route("POST", "/old/locked").status == Router.RouteStatus.METHOD_NOT_ALLOWED);
                check(router.route("GET", "/old/locked").redirect.location().equals("/locked"));
                check(router.route("GET", "/older").status != Router.RouteStatus.REDIRECT);
                String wire = response(config.getRouteConfig("/old").redirect);
                check(wire.startsWith("HTTP/1.1 " + code + " "));
                check(wire.contains("\r\nLocation: /new?x=1#part\r\n"));
                check(wire.endsWith("Content-Length: 0\r\nConnection: close\r\n\r\n"));
            }
            ConfigLoader.RedirectConfig defaultRedirect = load("{\"location\":\"/مرحبا\"}").getRouteConfig("/old").redirect;
            check(defaultRedirect.status() == 302);
            check(defaultRedirect.location().equals("/%D9%85%D8%B1%D8%AD%D8%A8%D8%A7"));
            check(load("{\"location\":\"https://example.com/path?q=1\"}").getRouteConfig("/old").redirect.location()
                    .equals("https://example.com/path?q=1"));
            for (String invalid : new String[]{"null", "[]", "{}", "{\"location\":null}",
                    "{\"location\":\"\"}", "{\"location\":\"relative\"}", "{\"location\":\"//example.com\"}",
                    "{\"location\":\"javascript:alert(1)\"}", "{\"location\":\"http:/missing-host\"}",
                    "{\"location\":\"/bad path\"}", "{\"location\":\"/bad%zz\"}",
                    "{\"location\":\"/ok\\r\\nInjected: yes\"}", "{\"location\":\"/ok\",\"status\":304}",
                    "{\"location\":\"/ok\",\"status\":200}", "{\"location\":\"/ok\",\"status\":301.5}",
                    "{\"location\":\"/ok\",\"status\":\"301\"}", "{\"location\":\"/ok\",\"typo\":true}"}) {
                try { load(invalid); throw new AssertionError("Accepted " + invalid); }
                catch (ConfigLoader.ConfigException expected) { check(true); }
            }
            try { HttpResponseWriter.redirect(301, "/ok\r\nInjected: yes"); throw new AssertionError("Header injection"); }
            catch (IllegalArgumentException expected) { check(true); }
            try { HttpResponseWriter.redirect(304, "/ok"); throw new AssertionError("Unsupported status"); }
            catch (IllegalArgumentException expected) { check(true); }
            System.out.println("PASS: " + checks + " redirect checks");
        } finally { Files.deleteIfExists(file); }
    }
}
