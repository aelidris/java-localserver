import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigLoader {
    private final String configPath;
    private String host = "127.0.0.1";
    private List<Integer> ports = new ArrayList<>();
    private long maxBodySize = 1048576;
    private Map<Integer, String> errorPages = new HashMap<>();
    private Map<String, RouteConfig> routes = new HashMap<>();

    public static class RouteConfig {
        public List<String> acceptedMethods = new ArrayList<>();
        public String root = "./public";
        public String defaultFile = "index.html";
        public boolean directoryListing = false;
    }

    public ConfigLoader(String configPath) {
        this.configPath = configPath;
        ports.add(8080);
    }

    public void load() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(configPath));
            String content = String.join("", lines);

            // Parse Host
            if (content.contains("\"host\"")) {
                int start = content.indexOf("\"host\"") + 7;
                int firstQuote = content.indexOf("\"", start);
                int secondQuote = content.indexOf("\"", firstQuote + 1);
                this.host = content.substring(firstQuote + 1, secondQuote);
            }

            // Parse Ports
            if (content.contains("\"ports\"")) {
                int portsIndex = content.indexOf("\"ports\"");
                int bracketStart = content.indexOf("[", portsIndex);
                int bracketEnd = content.indexOf("]", bracketStart);
                String portsStr = content.substring(bracketStart + 1, bracketEnd).trim();

                if (!portsStr.isEmpty()) {
                    ports.clear();
                    String[] portArray = portsStr.split(",");
                    for (String p : portArray) {
                        ports.add(Integer.parseInt(p.trim()));
                    }
                }
            }

            // Parse Max Body Size
            if (content.contains("\"client_max_body_size\"")) {
                int sizeIndex = content.indexOf("\"client_max_body_size\"");
                int colonIndex = content.indexOf(":", sizeIndex);
                int commaIndex = content.indexOf(",", colonIndex);
                if (commaIndex == -1 || commaIndex > content.indexOf("}", colonIndex)) {
                    commaIndex = content.indexOf("}", colonIndex);
                }
                String sizeStr = content.substring(colonIndex + 1, commaIndex).trim();
                this.maxBodySize = Long.parseLong(sizeStr);
            }

            // Parse Error Pages
            if (content.contains("\"error_pages\"")) {
                int epIndex = content.indexOf("\"error_pages\"");
                int objStart = content.indexOf("{", epIndex);
                int objEnd = content.indexOf("}", objStart);
                String epSub = content.substring(objStart + 1, objEnd);
                String[] pairs = epSub.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split(":");
                    if (keyValue.length == 2) {
                        String codeStr = keyValue[0].replace("\"", "").trim();
                        String pathStr = keyValue[1].replace("\"", "").trim();
                        try {
                            int code = Integer.parseInt(codeStr);
                            errorPages.put(code, pathStr);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            // Parse Routes (Basic extraction for root `/`)
            if (content.contains("\"routes\"")) {
                // Parse Routes (/ and /uploads)
                if (content.contains("\"routes\"")) {
                    // Parse Root Route "/"
                    RouteConfig defaultRoute = new RouteConfig();
                    if (content.contains("\"/\"")) {
                        int rootIndex = content.indexOf("\"/\"");
                        int amIndex = content.indexOf("\"accepted_methods\"", rootIndex);
                        if (amIndex != -1) {
                            int bracketStart = content.indexOf("[", amIndex);
                            int bracketEnd = content.indexOf("]", bracketStart);
                            String methodsStr = content.substring(bracketStart + 1, bracketEnd);
                            String[] methods = methodsStr.split(",");
                            defaultRoute.acceptedMethods.clear();
                            for (String m : methods) {
                                defaultRoute.acceptedMethods.add(m.replace("\"", "").trim());
                            }
                        }
                    }
                    routes.put("/", defaultRoute);

                    // Parse Uploads Route "/uploads"
                    if (content.contains("\"/uploads\"")) {
                        RouteConfig uploadsRoute = new RouteConfig();
                        uploadsRoute.root = "./uploads"; // Explicit root for uploads
                        uploadsRoute.directoryListing = true;

                        int uploadsIndex = content.indexOf("\"/uploads\"");
                        int amIndex = content.indexOf("\"accepted_methods\"", uploadsIndex);
                        if (amIndex != -1) {
                            int bracketStart = content.indexOf("[", amIndex);
                            int bracketEnd = content.indexOf("]", bracketStart);
                            String methodsStr = content.substring(bracketStart + 1, bracketEnd);
                            String[] methods = methodsStr.split(",");
                            uploadsRoute.acceptedMethods.clear();
                            for (String m : methods) {
                                uploadsRoute.acceptedMethods.add(m.replace("\"", "").trim());
                            }
                        }
                        routes.put("/uploads", uploadsRoute);
                    }
                }
            }

            System.out.println("⚙️ Configuration loaded successfully from " + configPath);
            System.out.println("   -> Host: " + this.host);
            System.out.println("   -> Ports: " + this.ports);
            System.out.println("   -> Max Body Size: " + this.maxBodySize + " bytes");
            System.out.println("   -> Error Pages Loaded: " + this.errorPages.keySet());

        } catch (IOException e) {
            System.out.println("⚠️ Could not read config file, using default values. Error: " + e.getMessage());
        }
    }

    public String getHost() {
        return host;
    }

    public List<Integer> getPorts() {
        return ports;
    }

    public long getMaxBodySize() {
        return maxBodySize;
    }

    public String getErrorPagePath(int errorCode) {
        return errorPages.get(errorCode);
    }

    public RouteConfig getRouteConfig(String path) {
        return routes.get(path);
    }
}