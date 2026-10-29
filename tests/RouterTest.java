import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class RouterTest {
    private static int checks;
    private static Router router;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    private static void status(String method, String uri, Router.RouteStatus expected) {
        Router.RequestResult result = router.route(method, uri);
        check(result.status == expected);
    }

    private static void file(String uri, Path expected) {
        Router.RequestResult result = router.route("GET", uri);
        check(result.status == Router.RouteStatus.OK);
        check(Path.of(result.filePath).equals(expected));
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("routing-test-");
        try {
            Path site = Files.createDirectory(work.resolve("site"));
            Path images = Files.createDirectory(work.resolve("images"));
            Path icons = Files.createDirectory(work.resolve("icons"));
            Path uploads = Files.createDirectory(work.resolve("uploads"));
            Files.writeString(site.resolve("home.html"), "home");
            Files.writeString(site.resolve("images-old"), "fallback");
            Files.writeString(images.resolve("home.html"), "images");
            Files.writeString(images.resolve("logo.png"), "image");
            Files.writeString(images.resolve("run.py"), "print('test')");
            Files.createDirectories(images.resolve("nested"));
            Files.writeString(images.resolve("nested/home.html"), "nested index");
            Files.writeString(icons.resolve("logo.png"), "specific image");
            Files.writeString(uploads.resolve("remove.txt"), "delete me");
            Path single = Files.writeString(work.resolve("single.html"), "single file");
            Path configFile = work.resolve("config.json");
            Files.writeString(configFile, """
                {"routes": {
                  "/": {"root":"%s/site", "default_file":"home.html", "accepted_methods":["GET","POST"]},
                  "/images/": {"root":"%s/images", "default_file":"home.html", "accepted_methods":["GET"]},
                  "/images/icons": {"root":"%s/icons", "accepted_methods":["GET"]},
                  "/files": {"root":"%s/uploads", "accepted_methods":["POST","DELETE"]},
                  "/single": {"root":"%s/single.html"}
                }}
                """.formatted(work, work, work, work, work));
            ConfigLoader config = new ConfigLoader(configFile.toString());
            config.load();
            router = new Router(config);
            file("/", site.resolve("home.html"));
            file("/images", images.resolve("home.html"));
            file("/images/", images.resolve("home.html"));
            file("/images/logo.png?version=1", images.resolve("logo.png"));
            file("/images/icons/logo.png", icons.resolve("logo.png"));
            file("/images/nested/", images.resolve("nested/home.html"));
            file("/images-old", site.resolve("images-old"));
            file("/single", single);
            file("/single/", single);
            status("GET", "/single/child", Router.RouteStatus.NOT_FOUND);
            status("GET", "/images/icons/missing", Router.RouteStatus.NOT_FOUND);
            status("GET", "/files/remove.txt", Router.RouteStatus.METHOD_NOT_ALLOWED);
            status("POST", "/images/logo.png", Router.RouteStatus.METHOD_NOT_ALLOWED);
            status("DELETE", "/images/logo.png", Router.RouteStatus.METHOD_NOT_ALLOWED);
            status("POST", "/images/run.py", Router.RouteStatus.METHOD_NOT_ALLOWED);
            status("GET", "/images/run.py", Router.RouteStatus.CGI);
            status("POST", "/new.txt", Router.RouteStatus.OK);
            Router.RequestResult upload = router.route("POST", "/files/new/nested.bin");
            check(upload.status == Router.RouteStatus.OK);
            check(Path.of(upload.filePath).equals(uploads.resolve("new/nested.bin")));
            check(!Files.exists(uploads.resolve("new"))); // Routing alone must not write uploads.
            status("DELETE", "/files/remove.txt", Router.RouteStatus.OK);
            check(!Files.exists(uploads.resolve("remove.txt")));
            status("DELETE", "/files", Router.RouteStatus.FORBIDDEN);
            status("POST", "/files", Router.RouteStatus.FORBIDDEN);
            status("GET", "/images/../home.html", Router.RouteStatus.FORBIDDEN);
            status("GET", "/images//logo.png", Router.RouteStatus.BAD_REQUEST);
            status("GET", "/images\\logo.png", Router.RouteStatus.BAD_REQUEST);
            status("GET", "/bad\u0000path", Router.RouteStatus.BAD_REQUEST);

            // Existing symlinks and missing upload targets must both stay inside the root.
            Files.createSymbolicLink(images.resolve("outside"), site);
            Files.createSymbolicLink(uploads.resolve("outside"), site);
            status("GET", "/images/outside/home.html", Router.RouteStatus.FORBIDDEN);
            status("POST", "/files/outside/new.txt", Router.RouteStatus.FORBIDDEN);
            status("DELETE", "/files/outside/home.html", Router.RouteStatus.FORBIDDEN);
            check(Files.exists(site.resolve("home.html")));
            Files.createDirectory(images.resolve("empty"));
            status("GET", "/images/empty", Router.RouteStatus.FORBIDDEN);
            config.getRouteConfig("/images").directoryListing = true;
            Router.RequestResult directory = router.route("GET", "/images/empty");
            check(directory.status == Router.RouteStatus.OK && directory.isDirectory);
            System.out.println("PASS: " + checks + " routing checks");
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
