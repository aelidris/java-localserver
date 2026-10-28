import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Router {
    private final ConfigLoader configLoader;

    public Router(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public enum RouteStatus {
        OK,
        CGI,
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
        // Normalize path (e.g., "/" -> index.html)
        String pathWithoutQuery = uri.split("\\?")[0];

        // Check route config
        String routeKey = pathWithoutQuery.startsWith("/uploads") ? "/uploads" : "/";
        ConfigLoader.RouteConfig routeConfig = configLoader.getRouteConfig(routeKey);

        if (routeConfig == null) {
            // Fallback default rules if route not explicitly defined
            routeConfig = new ConfigLoader.RouteConfig();
        }

        // Check if HTTP Method is allowed -> 405 Method Not Allowed
        if (!routeConfig.acceptedMethods.contains(method) && !pathWithoutQuery.endsWith(".py")) {
            return new RequestResult(RouteStatus.METHOD_NOT_ALLOWED, null, false);
        }

        // Security Protection: Prevent POST/DELETE directly on root index.html to avoid overwriting content (Allowed for CGI .py files)
        if (method.equals("POST") && !pathWithoutQuery.startsWith("/uploads") && !pathWithoutQuery.endsWith(".py")) {
            return new RequestResult(RouteStatus.METHOD_NOT_ALLOWED, null, false);
        }

        // Resolve file path based on root and URI
        String relativePath = pathWithoutQuery.startsWith("/uploads")
                ? pathWithoutQuery.substring(8)
                : (pathWithoutQuery.equals("/") ? "/" + routeConfig.defaultFile : pathWithoutQuery);

        if (relativePath.isEmpty() || relativePath.equals("/")) {
            relativePath = "/" + (routeConfig.defaultFile != null ? routeConfig.defaultFile : "");
        }

        String targetPath = routeConfig.root + relativePath;
        Path fullPath = Paths.get(targetPath).normalize();

        // Security check: ensure path stays inside root directory (prevent path
        // traversal)
        Path rootPath = Paths.get(routeConfig.root).normalize();
        if (!fullPath.startsWith(rootPath)) {
            return new RequestResult(RouteStatus.FORBIDDEN, null, false);
        }

        // Check script CGI (end with .py)
        if (pathWithoutQuery.endsWith(".py")) {
            if (Files.exists(fullPath) && Files.isRegularFile(fullPath)) {
                return new RequestResult(RouteStatus.CGI, fullPath.toString(), false);
            } else {
                return new RequestResult(RouteStatus.NOT_FOUND, null, false);
            }
        }
        // Check if file/directory exists
        if (!Files.exists(fullPath)) {
            if (method.equals("POST")) {
                return new RequestResult(RouteStatus.OK, fullPath.toString(), false);
            }
            return new RequestResult(RouteStatus.NOT_FOUND, null, false);
        }

        // Check for Directory -> 403 Forbidden (if directory listing is false)
        if (Files.isDirectory(fullPath)) {
            if (!routeConfig.directoryListing) {
                return new RequestResult(RouteStatus.FORBIDDEN, fullPath.toString(), true);
            }
        }

        // Handle specific methods logic (GET, POST, DELETE)
        if (method.equals("POST")) {
            return new RequestResult(RouteStatus.OK, fullPath.toString(), false);
        } else if (method.equals("DELETE")) {
            return handleDeleteMapping(fullPath);
        }

        // Default GET OK
        return new RequestResult(RouteStatus.OK, fullPath.toString(), false);
    }

    private RequestResult handleDeleteMapping(Path path) {
        try {
            // Ensure we don't delete the root folder itself
            if (Files.isRegularFile(path)) {
                Files.delete(path);
                return new RequestResult(RouteStatus.OK, path.toString(), false);
            } else {
                return new RequestResult(RouteStatus.FORBIDDEN, null, false);
            }
        } catch (Exception e) {
            return new RequestResult(RouteStatus.INTERNAL_SERVER_ERROR, null, false);
        }
    }
}