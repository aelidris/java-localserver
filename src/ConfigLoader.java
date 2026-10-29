import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ConfigLoader {
    private final String configPath;
    private String host = "127.0.0.1";
    private List<Integer> ports = List.of(8080);
    private long maxBodySize = 1048576;
    private Map<Integer, String> errorPages = Map.of();
    private Map<String, RouteConfig> routes = Map.of("/", new RouteConfig());

    private List<ConfigLoader> servers = List.of();
    private List<String> serverNames = List.of();
    private boolean defaultServer;
    private long requestTimeoutMs = 60000;

    public static class RouteConfig {
        public List<String> acceptedMethods = List.of("GET");
        public String root = "./public";
        public String defaultFile = "index.html";
        public boolean directoryListing = false;
        public RedirectConfig redirect;
        public Map<String, String> cgi = Map.of(".py", "python3");
    }

    public record RedirectConfig(int status, String location) { }

    public ConfigLoader(String configPath) { this.configPath = configPath; }

    public void load() {
        try {
            // Bound the input before decoding or parsing it.
            byte[] bytes;
            try (var input = Files.newInputStream(Path.of(configPath))) {
                bytes = input.readNBytes(1048577);
            }
            if (bytes.length > 1048576) throw invalid("Config exceeds 1 MiB");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> config = object(JsonParser.parse(text), "config");
            if (!config.containsKey("servers")) {
                parseServer(config);
                servers = List.of(this);
                return;
            }
            keys(config, "servers");
            List<ConfigLoader> valid = new ArrayList<>();
            int index = 0;
            for (Object entry : array(config.get("servers"), "servers")) {
                index++;
                try {
                    ConfigLoader server = new ConfigLoader(configPath);
                    server.parseServer(object(entry, "server"));
                    for (ConfigLoader other : valid) {
                        if (!server.host.equals(other.host)
                                || server.ports.stream().noneMatch(other.ports::contains)) continue;
                        if ((server.defaultServer && other.defaultServer)
                                || (server.serverNames.isEmpty() && other.serverNames.isEmpty())
                                || server.serverNames.stream().anyMatch(other.serverNames::contains)) {
                            throw invalid("Conflicting server names/defaults on shared ports");
                        }
                    }
                    valid.add(server);
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("Skipping server " + index + ": " + e.getMessage());
                }
            }
            if (valid.isEmpty()) throw invalid("No valid server configuration");
            servers = List.copyOf(valid);
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException("Invalid config " + configPath + ": " + e.getMessage());
        }
    }

    private void parseServer(Map<String, Object> config) throws IOException {
        keys(config, "host", "ports", "client_max_body_size", "error_pages", "routes",
                "server_names", "default_server", "request_timeout_ms");

        String newHost = string(config.getOrDefault("host", "127.0.0.1"), "host");
        newHost = InetAddress.getByName(newHost).getHostAddress();
        List<Integer> newPorts = new ArrayList<>();
        for (Object value : array(config.getOrDefault("ports", List.of(new BigDecimal(8080))), "ports")) {
            int port = (int) integer(value, "port", 1, 65535);
            if (newPorts.contains(port)) throw invalid("Duplicate port: " + port);
            newPorts.add(port);
        }
        if (newPorts.isEmpty()) throw invalid("ports must not be empty");
        long newMaxBodySize = integer(config.getOrDefault("client_max_body_size", new BigDecimal(1048576)),
                "client_max_body_size", 0, Integer.MAX_VALUE - 8L);

        Map<Integer, String> newErrors = new HashMap<>();
        for (var entry : object(config.getOrDefault("error_pages", Map.of()), "error_pages").entrySet()) {
            if (!entry.getKey().matches("[45][0-9]{2}")) throw invalid("Invalid error status: " + entry.getKey());
            newErrors.put(Integer.parseInt(entry.getKey()), path(entry.getValue(), "error page"));
        }

        Map<String, RouteConfig> newRoutes = new HashMap<>();
        Map<String, Object> routeValues = object(config.getOrDefault("routes", Map.of("/", Map.of())), "routes");
        for (var entry : routeValues.entrySet()) {
            String name = routePath(entry.getKey());
            if (newRoutes.containsKey(name)) throw invalid("Duplicate route: " + name);
            newRoutes.put(name, route(name, object(entry.getValue(), "route " + name)));
        }
        if (!newRoutes.containsKey("/")) throw invalid("routes must include /");

        List<String> newNames = new ArrayList<>();
        for (Object value : array(config.getOrDefault("server_names", List.of()), "server_names")) {
            String name = string(value, "server name").toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?") || name.contains("..")
                    || newNames.contains(name)) throw invalid("Invalid or duplicate server name: " + name);
            newNames.add(name);
        }
        Object newDefault = config.getOrDefault("default_server", false);
        if (!(newDefault instanceof Boolean)) throw invalid("default_server must be true or false");
        long newTimeout = integer(config.getOrDefault("request_timeout_ms", new BigDecimal(60000)),
                "request_timeout_ms", 100, 3600000);

        // Publish only after the entire configuration has passed validation.
        serverNames = List.copyOf(newNames);
        defaultServer = (boolean) newDefault;
        requestTimeoutMs = newTimeout;
        host = newHost;
        ports = List.copyOf(newPorts);
        maxBodySize = newMaxBodySize;
        errorPages = Map.copyOf(newErrors);
        routes = Map.copyOf(newRoutes);
        System.out.println("Configuration loaded: " + host + " " + ports);
    }

    private String routePath(String name) {
        if (!name.startsWith("/") || name.contains("//") || name.contains("\\")
                || name.contains("?") || name.contains("#") || name.contains("%")
                || name.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw invalid("Invalid route path: " + name);
        }
        for (String segment : name.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw invalid("Invalid route path: " + name);
        }
        return name.length() > 1 && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    private RouteConfig route(String name, Map<String, Object> values) {
        keys(values, "accepted_methods", "root", "default_file", "directory_listing", "redirect", "cgi");
        RouteConfig route = new RouteConfig();
        List<String> methods = new ArrayList<>();
        for (Object value : array(values.getOrDefault("accepted_methods", List.of("GET")), name + " methods")) {
            String method = string(value, "method");
            if (!Set.of("GET", "POST", "DELETE").contains(method) || methods.contains(method)) {
                throw invalid("Invalid or duplicate method in " + name + ": " + method);
            }
            methods.add(method);
        }
        if (methods.isEmpty()) throw invalid("accepted_methods must not be empty in " + name);
        route.acceptedMethods = List.copyOf(methods);
        route.root = path(values.getOrDefault("root", name.equals("/uploads") ? "./uploads" : "./public"), name + " root");
        route.defaultFile = path(values.getOrDefault("default_file", "index.html"), name + " default_file");
        Path index = Path.of(route.defaultFile);
        if (index.isAbsolute() || index.normalize().startsWith("..") || index.normalize().toString().isEmpty()) {
            throw invalid("default_file must stay inside its root: " + name);
        }
        Object listing = values.getOrDefault("directory_listing", false);
        if (!(listing instanceof Boolean)) throw invalid("directory_listing must be true or false: " + name);
        route.directoryListing = (boolean) listing;
        if (values.containsKey("redirect")) route.redirect = redirect(object(values.get("redirect"), name + " redirect"));
        if (values.containsKey("cgi")) {
            Map<String, String> mappings = new HashMap<>();
            for (var entry : object(values.get("cgi"), name + " cgi").entrySet()) {
                if (!entry.getKey().matches("\\.[A-Za-z0-9]+")) throw invalid("Invalid CGI extension");
                mappings.put(entry.getKey(), path(entry.getValue(), "CGI interpreter"));
            }
            route.cgi = Map.copyOf(mappings);
        }
        return route;
    }

    private RedirectConfig redirect(Map<String, Object> values) {
        keys(values, "status", "location");
        int status = (int) integer(values.getOrDefault("status", new BigDecimal(302)), "redirect status", 300, 399);
        if (!Set.of(301, 302, 303, 307, 308).contains(status)) throw invalid("Unsupported redirect status: " + status);
        String location = string(values.get("location"), "redirect location");
        URI uri = URI.create(location);
        boolean local = location.startsWith("/") && !location.startsWith("//");
        boolean external = ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        if (!local && !external) throw invalid("redirect location must start with / or be an HTTP(S) URL");
        return new RedirectConfig(status, uri.toASCIIString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map)) throw invalid(name + " must be an object");
        return (Map<String, Object>) value;
    }

    private List<?> array(Object value, String name) {
        if (!(value instanceof List<?> list)) throw invalid(name + " must be an array");
        return list;
    }

    private String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()
                || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid(name + " must be a non-empty string without controls");
        return text;
    }

    private String path(Object value, String name) {
        String text = string(value, name);
        Path.of(text);
        return text;
    }

    private long integer(Object value, String name, long min, long max) {
        if (!(value instanceof BigDecimal number)) throw invalid(name + " must be a number");
        try {
            long result = number.longValueExact();
            if (result >= min && result <= max) return result;
        } catch (ArithmeticException ignored) { }
        throw invalid(name + " must be an integer between " + min + " and " + max);
    }

    private void keys(Map<String, Object> values, String... allowed) {
        for (String key : values.keySet()) {
            if (!Set.of(allowed).contains(key)) throw invalid("Unknown setting: " + key);
        }
    }

    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    public List<ConfigLoader> getServers() { return servers.isEmpty() ? List.of(this) : servers; }
    public List<String> getServerNames() { return serverNames; }
    public boolean isDefaultServer() { return defaultServer; }
    public long getRequestTimeoutMs() { return requestTimeoutMs; }

    public String getHost() { return host; }
    public List<Integer> getPorts() { return ports; }
    public long getMaxBodySize() { return maxBodySize; }
    public String getErrorPagePath(int code) { return errorPages.get(code); }
    public RouteConfig getRouteConfig(String path) { return routes.get(path); }
    public Set<String> getRoutePaths() { return routes.keySet(); }

    public static class ConfigException extends IllegalArgumentException {
        public ConfigException(String message) { super(message); }
    }
}
