import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader {
    private final String configPath;
    private String host = "127.0.0.1";
    private List<Integer> ports = new ArrayList<>();
    private long maxBodySize = 1048576;

    public ConfigLoader(String configPath) {
        this.configPath = configPath;
        ports.add(8080);
    }

    public void load() {
        try {
            List<String> lines = Files.readAllLines(Paths.get(configPath));
            String content = String.join("", lines);

            if (content.contains("\"host\"")) {
                int start = content.indexOf("\"host\"") + 7;
                int firstQuote = content.indexOf("\"", start);
                int secondQuote = content.indexOf("\"", firstQuote + 1);
                this.host = content.substring(firstQuote + 1, secondQuote);
            }

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

            if (content.contains("\"client_max_body_size\"")) {
                int sizeIndex = content.indexOf("\"client_max_body_size\"");
                int colonIndex = content.indexOf(":", sizeIndex);
                int commaIndex = content.indexOf(",", colonIndex);
                if (commaIndex == -1)
                    commaIndex = content.indexOf("}", colonIndex);
                String sizeStr = content.substring(colonIndex + 1, commaIndex).trim();
                this.maxBodySize = Long.parseLong(sizeStr);
            }

            System.out.println("⚙️ Configuration loaded successfully from " + configPath);
            System.out.println("   -> Host: " + this.host);
            System.out.println("   -> Ports: " + this.ports);
            System.out.println("   -> Max Body Size: " + this.maxBodySize + " bytes");

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
}