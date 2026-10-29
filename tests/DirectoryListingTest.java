import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class DirectoryListingTest {
    private static int checks;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    private static String html(Path path, String url) throws Exception {
        return new String(DirectoryListing.create(path, url), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("listing-test-");
        try {
            Path files = Files.createDirectory(work.resolve("files"));
            Path folder = Files.createDirectory(files.resolve("folder name"));
            check(html(folder, "/browse/folder name").contains("<ul>\n</ul>"));
            for (String name : new String[]{"a.txt", "z.txt", "with space.txt", "مرحبا.txt", "a+b.txt",
                    "100%.txt", "hash#.txt", "query?.txt", "<img>&\"'.txt", "%2e%2e"}) {
                Files.writeString(files.resolve(name), "content");
                check(UrlPath.decode(UrlPath.encode("/browse/" + name)).equals("/browse/" + name));
            }
            Files.createSymbolicLink(files.resolve("outside-link"), work);
            Files.writeString(files.resolve("hidden\ncontrol"), "skip");
            Files.writeString(files.resolve("hidden\\backslash"), "skip");
            String listing = html(files, "/browse");
            check(listing.contains("href=\"/browse/with%20space.txt\""));
            check(listing.contains("href=\"/browse/a%2Bb.txt\""));
            check(listing.contains("href=\"/browse/hash%23.txt\""));
            check(listing.contains("href=\"/browse/query%3F.txt\""));
            check(listing.contains("href=\"/browse/100%25.txt\""));
            check(listing.contains("&lt;img&gt;&amp;&quot;&#39;.txt"));
            check(!listing.contains("<img>"));
            check(!listing.contains("outside-link") && !listing.contains("hidden"));
            check(listing.indexOf(">folder name/</a>") < listing.indexOf(">a.txt</a>"));
            check(listing.indexOf(">a.txt</a>") < listing.indexOf(">z.txt</a>"));
            check(listing.equals(html(files, "/browse/")));
            check(html(files, "/<script>").contains("Index of /&lt;script&gt;/"));
            check(UrlPath.decode("/a+b").equals("/a+b"));
            check(UrlPath.decode("/%252e%252e").equals("/%2e%2e"));
            for (String bad : new String[]{"/%", "/%0", "/%zz", "/%ff", "/%c0%af", "/%ed%a0%80", "/%e2%82"}) {
                try { UrlPath.decode(bad); throw new AssertionError("Accepted bad URL " + bad); }
                catch (IllegalArgumentException expected) { check(true); }
            }

            Path configFile = work.resolve("config.json");
            Files.writeString(configFile, "{\"routes\":{\"/\":{},\"/browse\":{\"root\":\"" + files
                    + "\",\"directory_listing\":true}}}");
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            Router router = new Router(config);
            Router.RequestResult directory = router.route("GET", "/browse/folder%20name?ignored=1");
            check(directory.isDirectory && directory.status == Router.RouteStatus.OK);
            check(directory.directoryUrlPath.equals("/browse/folder name"));
            for (String name : new String[]{"with space.txt", "مرحبا.txt", "a+b.txt", "100%.txt", "hash#.txt", "query?.txt", "%2e%2e"}) {
                Router.RequestResult result = router.route("GET", UrlPath.encode("/browse/" + name));
                check(result.status == Router.RouteStatus.OK);
                check(Path.of(result.filePath).equals(files.resolve(name)));
            }
            for (String bad : new String[]{"/browse/%00", "/browse/%5cfile", "/browse/%ff", "/browse/%"}) {
                check(router.route("GET", bad).status == Router.RouteStatus.BAD_REQUEST);
            }
            check(router.route("GET", "/browse/%2e%2e/config.json").status == Router.RouteStatus.FORBIDDEN);
            check(router.route("GET", "/browse/outside-link/config.json").status == Router.RouteStatus.FORBIDDEN);
            check(router.route("POST", "/browse/a.txt").status == Router.RouteStatus.METHOD_NOT_ALLOWED);
            Files.writeString(folder.resolve("index.html"), "Default page wins");
            check(!router.route("GET", "/browse/folder%20name").isDirectory);
            config.getRouteConfig("/browse").directoryListing = false;
            check(router.route("GET", "/browse").status == Router.RouteStatus.FORBIDDEN);
            check(router.route("GET", "/browse/folder%20name").status == Router.RouteStatus.OK);

            Path many = Files.createDirectory(work.resolve("many"));
            for (int i = 0; i < 10001; i++) Files.createFile(many.resolve("file" + i));
            try { DirectoryListing.create(many, "/many"); throw new AssertionError("Missing entry limit"); }
            catch (IOException expected) { check(expected.getMessage().contains("too many entries")); }
            Path large = Files.createDirectory(work.resolve("large"));
            for (int i = 0; i < 1300; i++) Files.createFile(large.resolve("&".repeat(230) + i));
            try { DirectoryListing.create(large, "/large"); throw new AssertionError("Missing output limit"); }
            catch (IOException expected) { check(expected.getMessage().contains("exceeds 2 MiB")); }
            System.out.println("PASS: " + checks + " listing and URL checks");
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
