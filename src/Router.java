import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class Router {
    private final ConfigLoader configLoader;

    public Router(ConfigLoader configLoader) { this.configLoader = configLoader; }

    public enum RouteStatus {
        OK, CGI, REDIRECT, BAD_REQUEST, NOT_FOUND, FORBIDDEN, METHOD_NOT_ALLOWED,
        PAYLOAD_TOO_LARGE, INTERNAL_SERVER_ERROR
    }

    public static class RequestResult {
        public RouteStatus status;
        public String filePath;
        public boolean isDirectory;
        public String directoryUrlPath;
        public ConfigLoader.RedirectConfig redirect;

        public RequestResult(RouteStatus status, String filePath, boolean isDirectory) {
            this.status = status;
            this.filePath = filePath;
            this.isDirectory = isDirectory;
        }
    }

    public RequestResult route(String method, String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        if (path.contains("#") || path.chars().anyMatch(c -> c <= 32 || c == 127)) return error(RouteStatus.BAD_REQUEST);
        try {
            path = UrlPath.decode(path);
        } catch (IllegalArgumentException e) {
            return error(RouteStatus.BAD_REQUEST);
        }
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\")
                || path.chars().anyMatch(c -> c < 32 || c == 127)) return error(RouteStatus.BAD_REQUEST);
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return error(RouteStatus.FORBIDDEN);
        }

        String routePath = findRoute(path);
        ConfigLoader.RouteConfig config = configLoader.getRouteConfig(routePath);
        if (!config.acceptedMethods.contains(method)) return error(RouteStatus.METHOD_NOT_ALLOWED);
        if (config.redirect != null) {
            RequestResult result = new RequestResult(RouteStatus.REDIRECT, null, false);
            result.redirect = config.redirect;
            return result;
        }

        // Strip the matched prefix: /images/logo.png -> <images root>/logo.png.
        String relative = path.substring(routePath.equals("/") ? 1 : routePath.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        try {
            Path root = Path.of(config.root).toAbsolutePath().normalize();
            boolean fileRoot = Files.isRegularFile(root);
            if (fileRoot && !relative.isEmpty()) return error(RouteStatus.NOT_FOUND);
            Path target = fileRoot ? root : root.resolve(relative).normalize();
            if (!insideRoot(root, target)) return error(RouteStatus.FORBIDDEN);

            if (method.equals("GET") && Files.isDirectory(target)) {
                Path index = target.resolve(config.defaultFile).normalize();
                if (!insideRoot(root, index)) return error(RouteStatus.FORBIDDEN);
                if (Files.isRegularFile(index)) target = index;
                else if (!config.directoryListing) return error(RouteStatus.FORBIDDEN);
                else {
                    RequestResult result = new RequestResult(RouteStatus.OK, target.toString(), true);
                    result.directoryUrlPath = path;
                    return result;
                }
            }

            // CGI still uses .py for now, but must obey the route's allowed methods.
            if (target.toString().endsWith(".py")) {
                return Files.isRegularFile(target)
                        ? new RequestResult(RouteStatus.CGI, target.toString(), false)
                        : error(RouteStatus.NOT_FOUND);
            }
            if (method.equals("POST")) {
                if ((!fileRoot && relative.isEmpty()) || Files.isDirectory(target)) return error(RouteStatus.FORBIDDEN);
                if (Files.exists(target) && !Files.isRegularFile(target)) return error(RouteStatus.FORBIDDEN);
                return new RequestResult(RouteStatus.OK, target.toString(), false);
            }
            if (!Files.exists(target)) return error(RouteStatus.NOT_FOUND);
            if (!Files.isRegularFile(target)) return error(RouteStatus.FORBIDDEN);
            if (method.equals("DELETE")) Files.delete(target);
            return new RequestResult(RouteStatus.OK, target.toString(), false);
        } catch (InvalidPathException e) {
            return error(RouteStatus.BAD_REQUEST);
        } catch (NoSuchFileException e) {
            return error(RouteStatus.NOT_FOUND);
        } catch (AccessDeniedException e) {
            return error(RouteStatus.FORBIDDEN);
        } catch (IOException e) {
            return error(RouteStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String findRoute(String path) {
        String best = "/";
        for (String candidate : configLoader.getRoutePaths()) {
            if ((path.equals(candidate) || path.startsWith(candidate + "/")) && candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean insideRoot(Path root, Path target) throws IOException {
        return target.startsWith(root) && realPath(target).startsWith(realPath(root));
    }

    // Resolve existing parents too: an upload's destination may not exist yet.
    private Path realPath(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    private RequestResult error(RouteStatus status) {
        return new RequestResult(status, null, false);
    }
}
