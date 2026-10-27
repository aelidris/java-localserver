import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class RouterTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("router-tests-");
        try {
            Path fallback = Files.createDirectory(temp.resolve("public"));
            Path assets = Files.createDirectory(temp.resolve("assets"));
            Path privateRoot = Files.createDirectory(temp.resolve("private"));
            Path empty = Files.createDirectory(temp.resolve("empty"));
            Path storage = temp.resolve("new/storage");
            Path health = Files.writeString(temp.resolve("health.txt"), "healthy");
            Path index = Files.writeString(fallback.resolve("index.html"), "home");
            Path fallbackFile = Files.writeString(fallback.resolve("assets-old.txt"), "fallback");
            Path prefixCollision = Files.writeString(fallback.resolve("assets2"), "not an asset");
            Path assetIndex = Files.writeString(assets.resolve("home.html"), "asset home");
            Path logo = Files.writeString(assets.resolve("logo.txt"), "logo");
            Path nestedIndex = Files.writeString(Files.createDirectory(assets.resolve("nested")).resolve("home.html"), "nested home");
            Path childCollision = Files.writeString(assets.resolve("private-other.txt"), "parent route");
            Path privateFile = Files.writeString(privateRoot.resolve("secret.txt"), "private route");
            Path configFile = temp.resolve("config.json");
            String json = "{\"routes\":{"
                    + route("/", fallback, "GET,POST,DELETE", "index.html", false) + ","
                    + route("/assets", assets, "GET,POST,DELETE", "home.html", false) + ","
                    + route("/assets/private/", privateRoot, "GET", "index.html", false) + ","
                    + route("/storage", storage, "GET,POST,DELETE", "index.html", false) + ","
                    + route("/readonly", assets, "GET", "home.html", false) + ","
                    + route("/health", health, "GET", "index.html", false) + ","
                    + route("/listing", empty, "GET", "index.html", true) + "}}";
            Files.writeString(configFile, json);
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            Router router = new Router(config);

            file(router, "/", index);
            file(router, "/?next=/assets/private", index);
            file(router, "/assets/logo.txt", logo);
            file(router, "/assets/logo.txt?download=true", logo);
            file(router, "/assets", assetIndex);
            file(router, "/assets/", assetIndex);
            file(router, "/assets/nested", nestedIndex);
            file(router, "/assets/nested/", nestedIndex);
            file(router, "/assets/private/secret.txt", privateFile);
            file(router, "/assets/private-other.txt", childCollision);
            file(router, "/assets-old.txt", fallbackFile);
            file(router, "/assets2", prefixCollision);
            file(router, "/health", health);
            file(router, "/health/", health);
            status(router, "GET", "/health/child", Router.RouteStatus.NOT_FOUND);
            status(router, "GET", "/assets/missing", Router.RouteStatus.NOT_FOUND);
            status(router, "POST", "/assets/private/secret.txt", Router.RouteStatus.METHOD_NOT_ALLOWED);
            status(router, "DELETE", "/readonly/logo.txt", Router.RouteStatus.METHOD_NOT_ALLOWED);
            check(Files.exists(logo), "denied delete preserves file");
            status(router, "POST", "/assets", Router.RouteStatus.FORBIDDEN);
            status(router, "POST", "/assets/nested", Router.RouteStatus.FORBIDDEN);
            status(router, "DELETE", "/assets/nested", Router.RouteStatus.FORBIDDEN);
            status(router, "DELETE", "/assets", Router.RouteStatus.FORBIDDEN);
            check(Files.exists(assetIndex) && Files.exists(nestedIndex), "directory requests preserve index files");
            status(router, "GET", "/storage/missing", Router.RouteStatus.NOT_FOUND);
            Router.RequestResult upload = router.route("POST", "/storage/sub/new.bin");
            check(upload.status == Router.RouteStatus.OK && Path.of(upload.filePath).equals(storage.resolve("sub/new.bin")),
                    "new custom upload root and stripped prefix");
            check(!Files.exists(storage), "routing alone does not create upload directories");
            Files.createDirectories(Path.of(upload.filePath).getParent());
            Files.write(Path.of(upload.filePath), new byte[] {0, (byte) 255});
            file(router, "/storage/sub/new.bin", Path.of(upload.filePath));
            status(router, "DELETE", "/storage/sub/new.bin", Router.RouteStatus.OK);
            check(!Files.exists(Path.of(upload.filePath)), "delete uses custom route root");
            status(router, "GET", "/storage/sub/new.bin", Router.RouteStatus.NOT_FOUND);
            Router.RequestResult rootPost = router.route("POST", "/new.txt");
            check(rootPost.status == Router.RouteStatus.OK && Path.of(rootPost.filePath).equals(fallback.resolve("new.txt")),
                    "POST permission follows configuration even on root route");

            for (String path : new String[] {"/../secret", "/assets/../secret", "/assets/private/../../secret", "/assets/./logo.txt"}) {
                status(router, "GET", path, Router.RouteStatus.FORBIDDEN);
            }
            for (String path : new String[] {"", "?x", "relative", "//assets/logo.txt", "/assets//logo.txt",
                    "/assets/a\\b", "/assets/x#fragment", "/assets/invalid\u0000"}) {
                status(router, "GET", path, Router.RouteStatus.BAD_REQUEST);
            }
            Path outside = Files.writeString(temp.resolve("outside.txt"), "do not touch");
            Files.createSymbolicLink(assets.resolve("escape"), outside);
            Files.createSymbolicLink(assets.resolve("escape-dir"), privateRoot);
            for (String method : new String[] {"GET", "POST", "DELETE"}) {
                status(router, method, "/assets/escape", Router.RouteStatus.FORBIDDEN);
            }
            status(router, "POST", "/assets/escape-dir/new.txt", Router.RouteStatus.FORBIDDEN);
            check(Files.readString(outside).equals("do not touch"), "external symlink target preserved");
            Files.createSymbolicLink(assets.resolve("internal-link"), logo);
            file(router, "/assets/internal-link", assets.resolve("internal-link"));
            Path linkedIndex = Files.createDirectory(assets.resolve("linked-index"));
            Files.createSymbolicLink(linkedIndex.resolve("home.html"), outside);
            status(router, "GET", "/assets/linked-index", Router.RouteStatus.FORBIDDEN);
            Files.createSymbolicLink(assets.resolve("dangling"), temp.resolve("not-created"));
            status(router, "POST", "/assets/dangling", Router.RouteStatus.NOT_FOUND);
            Router.RequestResult listing = router.route("GET", "/listing");
            check(listing.status == Router.RouteStatus.OK && listing.isDirectory, "listing flag preserved for response handling");

            Files.writeString(configFile, "{\"routes\":{" + route("/", Path.of("."), "GET", "index.html", false) + "}}");
            config.load();
            file(router, "/README.md", Path.of("README.md").toAbsolutePath());
            System.out.println("Passed " + checks + " routing checks");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static String route(String prefix, Path root, String methods, String index, boolean listing) {
        return quote(prefix) + ":{\"root\":" + quote(root.toString()) + ",\"accepted_methods\":[\""
                + methods.replace(",", "\",\"") + "\"],\"default_file\":" + quote(index)
                + ",\"directory_listing\":" + listing + "}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void file(Router router, String uri, Path path) {
        Router.RequestResult result = router.route("GET", uri);
        check(result.status == Router.RouteStatus.OK && !result.isDirectory && Path.of(result.filePath).equals(path),
                "GET " + uri + " maps to " + path + ", got " + result.status + " " + result.filePath);
    }

    private static void status(Router router, String method, String uri, Router.RouteStatus status) {
        Router.RequestResult result = router.route(method, uri);
        check(result.status == status, method + " " + uri + " expected " + status + ", got " + result.status);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
