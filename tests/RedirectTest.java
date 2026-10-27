import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class RedirectTest {
    private static int checks;
    private static Path configFile;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("redirect-tests-");
        configFile = temp.resolve("config.json");
        try {
            int[] statuses = {301, 302, 303, 307, 308};
            String[] reasons = {"Moved Permanently", "Found", "See Other", "Temporary Redirect", "Permanent Redirect"};
            for (int i = 0; i < statuses.length; i++) {
                ConfigLoader config = load("{\"status\":" + statuses[i] + ",\"location\":\"/new?source=config#part\"}");
                Router router = new Router(config);
                for (String path : new String[] {"/old", "/old/", "/old/child?source=request"}) {
                    Router.RequestResult result = router.route("GET", path);
                    check(result.status == Router.RouteStatus.REDIRECT, "redirect route " + path);
                    check(result.filePath == null && !result.isDirectory, "no filesystem response");
                    check(result.redirect.status == statuses[i] && result.redirect.location.equals("/new?source=config#part"),
                            "configured status and exact destination");
                }
                verifyResponse(HttpResponseWriter.redirect(statuses[i], "/new?source=config#part"),
                        statuses[i] + " " + reasons[i], "/new?source=config#part");
                check(router.route("POST", "/old").status == Router.RouteStatus.METHOD_NOT_ALLOWED, "method check before redirect");
                check(router.route("GET", "/oldish").status == Router.RouteStatus.NOT_FOUND, "prefix boundary");
            }
            ConfigLoader defaults = load("{\"location\":\"https://example.test/new?q=1#part\"}");
            check(defaults.getRouteConfig("/old").redirect.status == 302, "default status");
            check(defaults.getRouteConfig("/old").redirect.location.equals("https://example.test/new?q=1#part"), "absolute URL");
            check(load("{\"location\":\"http://127.0.0.1:8081/\"}").getRouteConfig("/old").redirect != null, "HTTP URL with port");
            check(load("{\"location\":\"/caf\u00e9\"}").getRouteConfig("/old").redirect.location.equals("/caf%C3%A9"),
                    "Unicode destination encoded for ASCII header");
            check(load("{\"location\":\"/new%20page\"}").getRouteConfig("/old").redirect.location.equals("/new%20page"),
                    "existing percent escapes preserved");

            for (String bad : new String[] {"null", "\"/new\"", "[]", "{}", "{\"location\":null}", "{\"location\":1}",
                    "{\"location\":\"\"}", "{\"location\":\"/new\",\"extra\":true}"}) {
                reject(bad);
            }
            for (String status : new String[] {"200", "300", "304", "305", "306", "309", "301.5", "\"301\"", "null"}) {
                reject("{\"status\":" + status + ",\"location\":\"/new\"}");
            }
            for (String location : new String[] {"relative", "//example.test/new", "javascript:alert(1)", "file:///etc/passwd",
                    "https:///missing-host", "https://example.test:99999/", "https://example.test:0/", "/bad%ZZ", "/bad path",
                    "/new\r\nX-Injected: yes", "/new\t", "/new\u0000", "/new\\evil"}) {
                reject("{\"location\":" + quote(location) + "}");
            }
            for (String location : new String[] {"", "/new\r\nInjected: yes", "/space here", "/caf\u00e9"}) {
                try {
                    HttpResponseWriter.redirect(302, location);
                    throw new AssertionError("Unsafe Location accepted by writer");
                } catch (IllegalArgumentException expected) {
                    checks++;
                }
            }
            try {
                HttpResponseWriter.redirect(200, "/new");
                throw new AssertionError("Non-redirect status accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }

            Path root = Files.createDirectory(temp.resolve("data"));
            Path file = Files.writeString(root.resolve("keep.txt"), "keep me");
            String document = "{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"],\"root\":" + quote(root.toString()) + "},"
                    + "\"/old\":{\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"],\"root\":" + quote(root.toString())
                    + ",\"redirect\":{\"status\":307,\"location\":\"/new\"}},"
                    + "\"/old/static\":{\"accepted_methods\":[\"GET\"],\"root\":" + quote(root.toString()) + "}}}";
            Files.writeString(configFile, document);
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            Router router = new Router(config);
            check(router.route("POST", "/old/keep.txt").status == Router.RouteStatus.REDIRECT, "POST redirect");
            check(router.route("DELETE", "/old/keep.txt").status == Router.RouteStatus.REDIRECT, "DELETE redirect");
            check(Files.readString(file).equals("keep me"), "redirect does not delete or overwrite files");
            Router.RequestResult nested = router.route("GET", "/old/static/keep.txt");
            check(nested.status == Router.RouteStatus.OK && Path.of(nested.filePath).equals(file), "more specific static route wins");
            System.out.println("Passed " + checks + " redirect checks");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static ConfigLoader load(String redirect) throws Exception {
        Files.writeString(configFile, "{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"],\"root\":\"/nonexistent-redirect-root\"},"
                + "\"/old\":{\"accepted_methods\":[\"GET\"],\"root\":\"/nonexistent-redirect-root\",\"redirect\":" + redirect + "}}}");
        ConfigLoader config = new ConfigLoader(configFile.toString());
        config.load();
        return config;
    }

    private static void reject(String redirect) throws Exception {
        try {
            load(redirect);
            throw new AssertionError("Invalid redirect accepted");
        } catch (ConfigLoader.ConfigException expected) {
            check(expected.getMessage().contains("routes./old.redirect"), "field-specific redirect error");
        }
    }

    private static void verifyResponse(HttpResponseWriter writer, String status, String location) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel channel = new WritableByteChannel() {
            int calls;
            public int write(ByteBuffer source) {
                int count = calls++ % 2 == 0 ? 0 : Math.min(source.remaining(), 3);
                for (int i = 0; i < count; i++) {
                    bytes.write(source.get());
                }
                return count;
            }
            public boolean isOpen() { return true; }
            public void close() { throw new AssertionError("Writer must not close the channel"); }
        };
        int attempts = 0;
        while (!writer.isComplete()) {
            writer.writeTo(channel);
            if (++attempts > 10000) {
                throw new AssertionError("Redirect failed to complete");
            }
        }
        String response = bytes.toString(StandardCharsets.US_ASCII);
        String expected = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Length: 0\r\nLocation: " + location + "\r\nConnection: close\r\n\r\n";
        check(response.equals(expected), "exact redirect response with short/zero writes");
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\u0000", "\\u0000") + "\"";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
