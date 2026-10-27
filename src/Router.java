import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Router {
    private final ConfigLoader configLoader;

    public Router(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public enum RouteStatus {
        OK,
        BAD_REQUEST,
        NOT_FOUND,
        FORBIDDEN,
        METHOD_NOT_ALLOWED,
        PAYLOAD_TOO_LARGE,
        INTERNAL_SERVER_ERROR
    }

    public static class RequestResult {
        public RouteStatus status;
        public String filePath;
        public boolean isDirectory;

        public RequestResult(RouteStatus status, String filePath, boolean isDirectory) {
            this.status = status;
            this.filePath = filePath;
            this.isDirectory = isDirectory;
        }
    }

    public RequestResult route(String method, String uri) {
        int query = uri.indexOf('?');
        String path = query >= 0 ? uri.substring(0, query) : uri;
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\") || path.contains("#")
                || path.chars().anyMatch(c -> c <= 32 || c == 127)) {
            return failure(RouteStatus.BAD_REQUEST);
        }
        // Reject traversal before matching so it cannot cross into another route.
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return failure(RouteStatus.FORBIDDEN);
            }
        }

        String routeKey = null;
        for (String candidate : configLoader.getRoutePaths()) {
            boolean matches = candidate.equals("/") || path.equals(candidate) || path.startsWith(candidate + "/");
            if (matches && (routeKey == null || candidate.length() > routeKey.length())) {
                routeKey = candidate;
            }
        }
        if (routeKey == null) {
            return failure(RouteStatus.NOT_FOUND);
        }
        ConfigLoader.RouteConfig config = configLoader.getRouteConfig(routeKey);
        if (!config.acceptedMethods.contains(method)) {
            return failure(RouteStatus.METHOD_NOT_ALLOWED);
        }

        String relative = path.substring(routeKey.equals("/") ? 1 : routeKey.length());
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        try {
            Path root = Paths.get(config.root).toAbsolutePath().normalize();
            Path target;
            if (Files.isRegularFile(root)) {
                // A file root serves only the mount itself, not descendants.
                if (!relative.isEmpty()) {
                    return failure(RouteStatus.NOT_FOUND);
                }
                target = root;
            } else {
                target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || !canonicalPath(target).startsWith(canonicalPath(root))) {
                    return failure(RouteStatus.FORBIDDEN);
                }
                if (relative.isEmpty() && !method.equals("GET")) {
                    return failure(RouteStatus.FORBIDDEN);
                }
            }

            if (method.equals("POST")) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target)) {
                    return failure(RouteStatus.FORBIDDEN);
                }
                return new RequestResult(RouteStatus.OK, target.toString(), false);
            }
            if (!Files.exists(target)) {
                return failure(RouteStatus.NOT_FOUND);
            }
            if (method.equals("DELETE")) {
                return deleteFile(target);
            }
            if (Files.isDirectory(target)) {
                Path index = target.resolve(config.defaultFile).normalize();
                if (!index.startsWith(root) || !canonicalPath(index).startsWith(canonicalPath(root))) {
                    return failure(RouteStatus.FORBIDDEN);
                }
                if (Files.isRegularFile(index)) {
                    return new RequestResult(RouteStatus.OK, index.toString(), false);
                }
                if (!config.directoryListing) {
                    return failure(RouteStatus.FORBIDDEN);
                }
                // Directory response generation is handled separately from route selection.
                return new RequestResult(RouteStatus.OK, target.toString(), true);
            }
            if (!Files.isRegularFile(target)) {
                return failure(RouteStatus.FORBIDDEN);
            }
            return new RequestResult(RouteStatus.OK, target.toString(), false);
        } catch (InvalidPathException e) {
            return failure(RouteStatus.BAD_REQUEST);
        } catch (NoSuchFileException e) {
            return failure(RouteStatus.NOT_FOUND);
        } catch (AccessDeniedException e) {
            return failure(RouteStatus.FORBIDDEN);
        } catch (IOException e) {
            return failure(RouteStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Resolves existing symlinks, including parents of a not-yet-created upload. */
    private static Path canonicalPath(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new NoSuchFileException(path.toString());
            }
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    private RequestResult deleteFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return failure(RouteStatus.FORBIDDEN);
        }
        Files.delete(path);
        return new RequestResult(RouteStatus.OK, path.toString(), false);
    }

    private static RequestResult failure(RouteStatus status) {
        return new RequestResult(status, null, false);
    }
}
