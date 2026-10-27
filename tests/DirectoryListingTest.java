import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DirectoryListingTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("directory-tests-");
        try {
            Path root = Files.createDirectory(temp.resolve("files"));
            Path folder = Files.createDirectory(root.resolve("a folder"));
            Path empty = Files.createDirectory(root.resolve("empty"));
            Files.writeString(folder.resolve("child.txt"), "child");
            for (String name : new String[] {"z.txt", "a b.txt", "a+b.txt", "100%.txt", "question?.txt",
                    "hash#.txt", "ملف.txt", "<img onerror='x'>&\".txt", "%2e%2e"}) {
                Files.writeString(root.resolve(name), name);
            }
            Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
            Files.createSymbolicLink(root.resolve("external-link"), outside);
            Files.createSymbolicLink(root.resolve("internal-link"), root.resolve("z.txt"));
            Files.writeString(root.resolve("control\nname"), "skip");
            Files.writeString(root.resolve("back\\slash"), "skip");
            Path configFile = temp.resolve("config.json");
            Files.writeString(configFile, "{\"routes\":{"
                    + "\"/\":{\"root\":" + quote(temp.toString()) + ",\"accepted_methods\":[\"GET\"]},"
                    + "\"/files\":{\"root\":" + quote(root.toString()) + ",\"accepted_methods\":[\"GET\"],\"directory_listing\":true},"
                    + "\"/disabled\":{\"root\":" + quote(root.toString()) + ",\"accepted_methods\":[\"GET\"],\"directory_listing\":false}}}");
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            Router router = new Router(config);
            Router.RequestResult result = router.route("GET", "/files?ignore=query");
            check(result.status == Router.RouteStatus.OK && result.isDirectory, "listing selected");
            check(result.directoryUrlPath.equals("/files"), "query excluded from listing URL");
            String html = new String(DirectoryListing.render(Path.of(result.filePath), result.directoryUrlPath), StandardCharsets.UTF_8);
            check(html.contains("<h1>Index of /files/</h1>"), "URL title");
            check(!html.contains(temp.toString()), "no filesystem paths exposed");
            check(html.indexOf("a folder/") < html.indexOf("z.txt"), "directories before files");
            check(html.indexOf("100%.txt") < html.indexOf("z.txt"), "stable filename order");
            check(html.contains("&lt;img onerror=&#39;x&#39;&gt;&amp;&quot;.txt"), "HTML escaped filename");
            check(!html.contains("<img"), "no filename HTML injection");
            check(!html.contains("external-link") && !html.contains("internal-link"), "symlinks omitted");
            check(!html.contains("control\nname") && !html.contains("back\\slash"), "unsupported names omitted");

            Matcher links = Pattern.compile("href=\"([^\"]+)\"").matcher(html);
            int count = 0;
            while (links.find()) {
                String href = links.group(1);
                check(href.startsWith("/files/") && href.chars().allMatch(c -> c > 32 && c < 127), "absolute ASCII link");
                Router.RequestResult linked = router.route("GET", href);
                check(linked.status == Router.RouteStatus.OK, "generated link resolves: " + href);
                Path linkedPath = Path.of(linked.filePath);
                check(linkedPath.startsWith(root) && Files.exists(linkedPath), "link stays in mapped root");
                count++;
            }
            check(count == 11, "all ordinary files and directories listed");
            check(html.contains("/files/a%2Bb.txt") && html.contains("/files/a%20b.txt"), "plus distinct from space");
            check(html.contains("/files/100%25.txt") && html.contains("/files/%252e%252e"), "percent encoded once");
            check(html.contains("/files/question%3F.txt") && html.contains("/files/hash%23.txt"), "query and fragment names encoded");
            check(new String(DirectoryListing.render(root, "/files/"), StandardCharsets.UTF_8).equals(html),
                    "trailing slash does not change generated links");
            String nested = new String(DirectoryListing.render(folder, "/files/a folder"), StandardCharsets.UTF_8);
            check(nested.contains("/files/a%20folder/child.txt"), "nested link uses full encoded path");
            check(new String(DirectoryListing.render(empty, "/files/empty"), StandardCharsets.UTF_8)
                    .contains("This directory is empty."), "empty directory response");
            String title = new String(DirectoryListing.render(empty, "/files/<script>"), StandardCharsets.UTF_8);
            check(title.contains("&lt;script&gt;") && !title.contains("<script>"), "escaped directory title");
            check(router.route("GET", "/disabled").status == Router.RouteStatus.FORBIDDEN, "listing disabled returns 403");
            Files.writeString(root.resolve("index.html"), "default file");
            for (String uri : new String[] {"/files", "/disabled"}) {
                Router.RequestResult index = router.route("GET", uri);
                check(index.status == Router.RouteStatus.OK && !index.isDirectory
                        && Path.of(index.filePath).equals(root.resolve("index.html")), "index takes precedence: " + uri);
            }
            for (String uri : new String[] {"/files/%2e%2e/outside.txt", "/files/.%2E/outside.txt", "/files/%2E/z.txt"}) {
                check(router.route("GET", uri).status == Router.RouteStatus.FORBIDDEN, "encoded traversal rejected");
            }
            for (String uri : new String[] {"/files/%", "/files/%2", "/files/%ZZ", "/files/%C3%28", "/files/%FF",
                    "/files/%00", "/files/%0A", "/files/%5C", "/files/%2Fz.txt"}) {
                check(router.route("GET", uri).status == Router.RouteStatus.BAD_REQUEST, "invalid decoded path rejected: " + uri);
            }
            check(Path.of(router.route("GET", "/files%2Fa%2Bb.txt").filePath).equals(root.resolve("a+b.txt")),
                    "decode before choosing route");
            check(UrlPath.decode(UrlPath.encode("/ملفات/a+b?#%.txt")).equals("/ملفات/a+b?#%.txt"), "UTF-8 path roundtrip");
            try {
                DirectoryListing.render(root.resolve("missing"), "/files/missing");
                throw new AssertionError("Missing directory rendered");
            } catch (java.nio.file.NoSuchFileException expected) {
                checks++;
            }
            testLimits(temp);
            System.out.println("Passed " + checks + " directory listing checks");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void testLimits(Path temp) throws Exception {
        Path many = Files.createDirectory(temp.resolve("many"));
        for (int i = 0; i < 10001; i++) {
            Files.createFile(many.resolve("f" + i));
        }
        expectLimit(many, "entry limit");
        Path large = Files.createDirectory(temp.resolve("large"));
        for (int i = 0; i < 3000; i++) {
            Files.createFile(large.resolve("&".repeat(230) + i));
        }
        expectLimit(large, "response size limit");
    }

    private static void expectLimit(Path directory, String message) throws Exception {
        try {
            DirectoryListing.render(directory, "/files");
            throw new AssertionError("Oversized listing accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains(message), "bounded listing: " + message);
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
