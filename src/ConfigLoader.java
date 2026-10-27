import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigLoader {
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private final String configPath;
    private String host = "127.0.0.1";
    private List<Integer> ports = List.of(8080);
    private long maxBodySize = 1048576;
    private Map<Integer, String> errorPages = new HashMap<>();
    private Map<String, RouteConfig> routes = new HashMap<>();

    public static class RouteConfig {
        public List<String> acceptedMethods = new ArrayList<>();
        public String root = "./public";
        public String defaultFile = "index.html";
        public boolean directoryListing = false;
    }

    public static class ConfigException extends IllegalArgumentException {
        ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ConfigLoader(String configPath) {
        this.configPath = configPath;
    }

    public void load() {
        try {
            byte[] bytes;
            try (var stream = Files.newInputStream(Paths.get(configPath))) {
                bytes = stream.readNBytes(MAX_CONFIG_BYTES + 1);
            }
            if (bytes.length > MAX_CONFIG_BYTES) {
                throw invalid("file", "must not exceed 1 MiB");
            }
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> config = object(JsonParser.parse(content), "config");
            knownKeys(config, Set.of("host", "ports", "client_max_body_size", "error_pages", "routes"), "config");

            String newHost = string(config.getOrDefault("host", "127.0.0.1"), "host");
            if (newHost.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                    || newHost.contains("/")) {
                throw invalid("host", "must be an IP address or hostname without whitespace or a URL scheme");
            }
            try {
                InetAddress.getByName(newHost);
            } catch (IOException e) {
                throw invalid("host", "cannot resolve '" + newHost + "'");
            }
            List<?> portValues = array(config.getOrDefault("ports", List.of(BigDecimal.valueOf(8080))), "ports");
            if (portValues.isEmpty()) {
                throw invalid("ports", "must contain at least one port");
            }
            List<Integer> newPorts = new ArrayList<>();
            Set<Integer> seenPorts = new HashSet<>();
            for (int i = 0; i < portValues.size(); i++) {
                int port = (int) integer(portValues.get(i), "ports[" + i + "]", 1, 65535);
                if (!seenPorts.add(port)) {
                    throw invalid("ports", "contains duplicate port " + port);
                }
                newPorts.add(port);
            }
            long newMaxBodySize = integer(config.getOrDefault("client_max_body_size", BigDecimal.valueOf(1048576)),
                    "client_max_body_size", 0, Integer.MAX_VALUE - 8L);
            Map<Integer, String> newErrorPages = new HashMap<>();
            Map<String, Object> pages = object(config.getOrDefault("error_pages", Map.of()), "error_pages");
            for (Map.Entry<String, Object> entry : pages.entrySet()) {
                String field = "error_pages." + entry.getKey();
                if (!entry.getKey().matches("[45][0-9]{2}")) {
                    throw invalid(field, "must use an HTTP error code from 400 to 599");
                }
                newErrorPages.put(Integer.parseInt(entry.getKey()), path(entry.getValue(), field));
            }
            Map<String, Object> routeValues = object(config.getOrDefault("routes",
                    Map.of("/", Map.of("accepted_methods", List.of("GET")))), "routes");
            if (!routeValues.containsKey("/")) {
                throw invalid("routes", "must include the '/' route");
            }
            Map<String, RouteConfig> newRoutes = new HashMap<>();
            for (Map.Entry<String, Object> entry : routeValues.entrySet()) {
                String routeName = entry.getKey();
                if (!routeName.equals("/") && !routeName.equals("/uploads")) {
                    throw invalid("routes." + routeName, "is not supported yet; supported routes are '/' and '/uploads'");
                }
                newRoutes.put(routeName, route(entry.getValue(), routeName));
            }

            // Publish the new configuration only after every field has passed validation.
            host = newHost;
            ports = List.copyOf(newPorts);
            maxBodySize = newMaxBodySize;
            errorPages = newErrorPages;
            routes = newRoutes;
            System.out.println("⚙️ Configuration loaded successfully from " + configPath);
        } catch (CharacterCodingException e) {
            throw new ConfigException("Invalid configuration '" + configPath + "': file must contain valid UTF-8", e);
        } catch (IOException e) {
            throw new ConfigException("Invalid configuration '" + configPath + "': cannot read file: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid configuration '" + configPath + "': " + e.getMessage(), e);
        }
    }

    private static RouteConfig route(Object value, String name) {
        String field = "routes." + name;
        Map<String, Object> fields = object(value, field);
        knownKeys(fields, Set.of("accepted_methods", "root", "default_file", "directory_listing"), field);
        RouteConfig route = new RouteConfig();
        List<?> methods = array(fields.get("accepted_methods"), field + ".accepted_methods");
        if (methods.isEmpty()) {
            throw invalid(field + ".accepted_methods", "must not be empty");
        }
        for (Object methodValue : methods) {
            String method = string(methodValue, field + ".accepted_methods");
            if (!Set.of("GET", "POST", "DELETE").contains(method) || route.acceptedMethods.contains(method)) {
                throw invalid(field + ".accepted_methods", "must contain distinct GET, POST, or DELETE values");
            }
            route.acceptedMethods.add(method);
        }
        route.root = path(fields.getOrDefault("root", name.equals("/uploads") ? "./uploads" : "./public"), field + ".root");
        route.defaultFile = path(fields.getOrDefault("default_file", "index.html"), field + ".default_file");
        if (Paths.get(route.defaultFile).isAbsolute() || Paths.get(route.defaultFile).normalize().startsWith("..")) {
            throw invalid(field + ".default_file", "must be a relative path inside the route root");
        }
        Object listing = fields.getOrDefault("directory_listing", name.equals("/uploads"));
        if (!(listing instanceof Boolean)) {
            throw invalid(field + ".directory_listing", "must be a boolean");
        }
        route.directoryListing = (Boolean) listing;
        return route;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map)) {
            throw invalid(field, "must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value, String field) {
        if (!(value instanceof List)) {
            throw invalid(field, "must be an array");
        }
        return (List<?>) value;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw invalid(field, "must be a non-empty string");
        }
        return (String) value;
    }

    private static String path(Object value, String field) {
        String text = string(value, field);
        try {
            Paths.get(text);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "must be a valid filesystem path");
        }
        return text;
    }

    private static long integer(Object value, String field, long min, long max) {
        if (value instanceof BigDecimal) {
            try {
                long number = ((BigDecimal) value).longValueExact();
                if (number >= min && number <= max) {
                    return number;
                }
            } catch (ArithmeticException ignored) {
            }
        }
        throw invalid(field, "must be an integer between " + min + " and " + max);
    }

    private static void knownKeys(Map<String, Object> object, Set<String> allowed, String field) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(field + "." + key, "is unknown or not supported yet");
            }
        }
    }

    private static IllegalArgumentException invalid(String field, String message) {
        return new IllegalArgumentException(field + " " + message);
    }

    public String getHost() { return host; }
    public List<Integer> getPorts() { return ports; }
    public long getMaxBodySize() { return maxBodySize; }
    public String getErrorPagePath(int errorCode) { return errorPages.get(errorCode); }
    public RouteConfig getRouteConfig(String path) { return routes.get(path); }
}
