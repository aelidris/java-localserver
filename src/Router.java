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
        REDIRECT,
        CGI,
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
        public ConfigLoader.RedirectConfig redirect;
        public String directoryUrlPath;
        public CgiTarget cgi;

        public RequestResult(RouteStatus status, String filePath, boolean isDirectory) {
            this.status = status;
            this.filePath = filePath;
            this.isDirectory = isDirectory;
        }
    }

    public static final class CgiTarget {
        public final Path script;
        public final String interpreter;
        public final String scriptName;
        public final String pathInfo;
        public final String pathTranslated;

        private CgiTarget(Path script, String interpreter, String scriptName, String pathInfo, String pathTranslated) {
            this.script = script;
            this.interpreter = interpreter;
            this.scriptName = scriptName;
            this.pathInfo = pathInfo;
            this.pathTranslated = pathTranslated;
        }
    }

    public RequestResult route(String method, String uri) {
        int query = uri.indexOf('?');
        String rawPath = query >= 0 ? uri.substring(0, query) : uri;
        if (rawPath.contains("#") || rawPath.chars().anyMatch(c -> c <= 32 || c == 127)) {
            return failure(RouteStatus.BAD_REQUEST);
        }
        String path;
        try {
            path = UrlPath.decode(rawPath);
        } catch (IllegalArgumentException e) {
            return failure(RouteStatus.BAD_REQUEST);
        }
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)) {
            return failure(RouteStatus.BAD_REQUEST);
        }
        // Reject traversal before matching so it cannot cross into another route.
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return failure(RouteStatus.FORBIDDEN);
            }
        }

        String routeKey = matchRoute(path);
        if (routeKey == null) {
            return failure(RouteStatus.NOT_FOUND);
        }
        ConfigLoader.RouteConfig config = configLoader.getRouteConfig(routeKey);
        if (!config.acceptedMethods.contains(method)) {
            return failure(RouteStatus.METHOD_NOT_ALLOWED);
        }
        if (config.redirect != null) {
            RequestResult result = new RequestResult(RouteStatus.REDIRECT, null, false);
            result.redirect = config.redirect;
            return result;
        }

        String relative = path.substring(routeKey.equals("/") ? 1 : routeKey.length());
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        try {
            Path root = Paths.get(config.root).toAbsolutePath().normalize();
            if (!config.cgi.isEmpty()) {
                RequestResult cgi = findCgi(config, root, relative, routeKey, path);
                if (cgi != null) {
                    return cgi;
                }
                if (!method.equals("GET")) {
                    return failure(RouteStatus.METHOD_NOT_ALLOWED);
                }
            }
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

            if ((method.equals("POST") || method.equals("DELETE")) && isCgiRoot(target)) {
                return failure(RouteStatus.FORBIDDEN);
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
                RequestResult result = new RequestResult(RouteStatus.OK, target.toString(), true);
                result.directoryUrlPath = path;
                return result;
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

    private String matchRoute(String path) {
        String best = null;
        for (String candidate : configLoader.getRoutePaths()) {
            if ((candidate.equals("/") || path.equals(candidate) || path.startsWith(candidate + "/"))
                    && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    private RequestResult findCgi(ConfigLoader.RouteConfig config, Path root, String relative,
            String routeKey, String path) throws IOException {
        if (Files.isRegularFile(root)) {
            String info = routeKey.equals("/") ? (path.equals("/") ? "" : path) : path.substring(routeKey.length());
            return cgiResult(config, root, root, routeKey, info);
        }
        Path candidate = root;
        String scriptName = routeKey.equals("/") ? "" : routeKey;
        for (String segment : relative.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            candidate = candidate.resolve(segment);
            scriptName += "/" + segment;
            if (Files.isRegularFile(candidate)) {
                return cgiResult(config, root, candidate, scriptName, path.substring(scriptName.length()));
            }
        }
        if (Files.isDirectory(candidate)) {
            Path index = candidate.resolve(config.defaultFile).normalize();
            if (Files.isRegularFile(index)) {
                return cgiResult(config, root, index, (path.endsWith("/") ? path : path + "/") + config.defaultFile, "");
            }
        }
        return null;
    }

    private RequestResult cgiResult(ConfigLoader.RouteConfig config, Path root, Path script,
            String scriptName, String pathInfo) throws IOException {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String interpreter = dot >= 0 ? config.cgi.get(name.substring(dot)) : null;
        if (interpreter == null) {
            return null;
        }
        Path realScript = script.toRealPath();
        if (!realScript.startsWith(canonicalPath(root)) || !Files.isReadable(realScript)) {
            return failure(RouteStatus.FORBIDDEN);
        }
        String translated = "";
        if (!pathInfo.isEmpty()) {
            String routeKey = matchRoute(pathInfo);
            if (routeKey != null) {
                Path translationRoot = Paths.get(configLoader.getRouteConfig(routeKey).root).toAbsolutePath().normalize();
                String suffix = pathInfo.substring(routeKey.equals("/") ? 1 : routeKey.length());
                if (suffix.startsWith("/")) {
                    suffix = suffix.substring(1);
                }
                Path target = translationRoot.resolve(suffix).normalize();
                if (!canonicalPath(target).startsWith(canonicalPath(translationRoot))) {
                    return failure(RouteStatus.FORBIDDEN);
                }
                translated = target.toString();
            }
        }
        RequestResult result = new RequestResult(RouteStatus.CGI, null, false);
        result.cgi = new CgiTarget(realScript, interpreter, UrlPath.encode(scriptName), pathInfo, translated);
        return result;
    }

    private boolean isCgiRoot(Path target) throws IOException {
        Path resolved = canonicalPath(target);
        for (String routePath : configLoader.getRoutePaths()) {
            ConfigLoader.RouteConfig config = configLoader.getRouteConfig(routePath);
            if (!config.cgi.isEmpty() && resolved.startsWith(canonicalPath(Paths.get(config.root).toAbsolutePath().normalize()))) {
                return true;
            }
        }
        return false;
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
