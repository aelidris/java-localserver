import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ConfigLoaderTest {
    private static int checks;
    private static Path file;

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks + " failed");
    }

    private static ConfigLoader load(String json) throws Exception {
        Files.writeString(file, json);
        ConfigLoader config = new ConfigLoader(file.toString());
        config.load();
        return config;
    }

    private static void rejected(String json) throws Exception {
        try {
            load(json);
            throw new AssertionError("Accepted invalid config: " + json);
        } catch (ConfigLoader.ConfigException e) {
            check(e.getMessage().contains("Invalid config"));
        }
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("config-test-");
        file = work.resolve("config.json");
        try {
            ConfigLoader defaults = load("{}");
            check(defaults.getHost().equals("127.0.0.1"));
            check(defaults.getPorts().equals(List.of(8080)));
            check(defaults.getMaxBodySize() == 1048576);
            check(defaults.getRouteConfig("/").acceptedMethods.equals(List.of("GET")));
            ConfigLoader config = load("""
                {
                  "routes": {
                    "/uploads": {"root":"./files", "accepted_methods":["POST","DELETE"], "directory_listing":false},
                    "/": {"root":"./pages, test", "default_file":"welcome.html", "directory_listing":true}
                  },
                  "error_pages":{"404":"errors/not,found:page.html"},
                  "client_max_body_size":0, "ports":[9000,9001], "host":"127.0.0.1"
                }
                """);
            check(config.getPorts().equals(List.of(9000, 9001)));
            check(config.getMaxBodySize() == 0);
            check(config.getErrorPagePath(404).equals("errors/not,found:page.html"));
            check(config.getRouteConfig("/").root.equals("./pages, test"));
            check(config.getRouteConfig("/").defaultFile.equals("welcome.html"));
            check(config.getRouteConfig("/").directoryListing);
            check(config.getRouteConfig("/uploads").root.equals("./files"));
            check(!config.getRouteConfig("/uploads").directoryListing);
            check(config.getRouteConfig("/uploads").acceptedMethods.equals(List.of("POST", "DELETE")));

            Files.writeString(file, "{\"host\":\"0.0.0.0\",\"ports\":[1234],\"routes\":{}}");
            try { config.load(); throw new AssertionError("Expected failure"); }
            catch (ConfigLoader.ConfigException expected) { check(config.getPorts().equals(List.of(9000, 9001))); }
            check(config.getHost().equals("127.0.0.1"));
            check(config.getRouteConfig("/").root.equals("./pages, test"));

            for (String invalid : List.of("", "[]", "null", "{", "{}garbage", "{\"ports\":[8080,]}",
                    "{\"host\":\"x\",\"host\":\"y\"}", "{\"ports\":null}", "{\"ports\":[]}",
                    "{\"ports\":[8080,8080]}", "{\"ports\":[0]}", "{\"ports\":[65536]}",
                    "{\"ports\":[1.5]}", "{\"ports\":[\"8080\"]}", "{\"host\":\"\"}",
                    "{\"host\":false}", "{\"host\":null}", "{\"client_max_body_size\":-1}",
                    "{\"client_max_body_size\":2147483648}", "{\"client_max_body_size\":1.1}",
                    "{\"unknown\":1}", "{\"error_pages\":[]}", "{\"error_pages\":{\"200\":\"x\"}}",
                    "{\"error_pages\":{\"404\":null}}", "{\"routes\":{}}", "{\"routes\":null}",
                    "{\"routes\":{\"/\":{},\"other\":{}}}")) rejected(invalid);
            ConfigLoader custom = load("{\"routes\":{\"/\":{},\"/images/\":{},\"/images/icons\":{}}}");
            check(custom.getRoutePaths().contains("/images"));
            check(custom.getRoutePaths().contains("/images/icons"));
            check(!custom.getRoutePaths().contains("/images/"));
            for (String path : List.of("//images", "/images//icons", "/../images", "/images/./icons",
                    "/images?x", "/images#x", "/im ages", "/im%61ges")) {
                rejected("{\"routes\":{\"/\":{},\"" + path + "\":{}}}");
            }
            rejected("{\"routes\":{\"/\":{},\"/images\":{},\"/images/\":{}}}");
            for (String route : List.of("null", "[]", "{\"accepted_methods\":[]}",
                    "{\"accepted_methods\":[\"PUT\"]}", "{\"accepted_methods\":[\"GET\",\"GET\"]}",
                    "{\"root\":null}", "{\"root\":\"bad\\u0000path\"}", "{\"default_file\":\"../x\"}",
                    "{\"default_file\":\"/x\"}", "{\"default_file\":\".\"}", "{\"directory_listing\":\"false\"}",
                    "{\"directory_listing\":null}", "{\"rooot\":\"x\"}")) {
                rejected("{\"routes\":{\"/\":" + route + "}}");
            }
            for (String invalidJson : List.of("{\"x\":null,\"x\":1}", "[01]", "[+1]", "[1.]", "[1e]",
                    "[NaN]", "[true false]", "{\"x\":\"bad\\q\"}", "\"raw\nnewline\"",
                    "\"\\u12\"", "\"\\uZZZZ\"", "[".repeat(40) + "0" + "]".repeat(40))) {
                try { JsonParser.parse(invalidJson); throw new AssertionError("Accepted: " + invalidJson); }
                catch (IllegalArgumentException expected) { check(true); }
            }
            Map<?, ?> parsed = (Map<?, ?>) JsonParser.parse("{\"x\":[true,false,null,-1.25e2],\"text\":\"a\\\"b\\\\c\\n\\u0645\"}");
            List<?> values = (List<?>) parsed.get("x");
            check(values.get(0).equals(true) && values.get(1).equals(false) && values.get(2) == null);
            check(((BigDecimal) values.get(3)).compareTo(new BigDecimal(-125)) == 0);
            check(parsed.get("text").equals("a\"b\\c\nم"));
            check(load("{\"ports\":[65535],\"client_max_body_size\":2147483639}").getMaxBodySize() == 2147483639);

            Files.write(file, new byte[]{(byte) 0xff});
            try { new ConfigLoader(file.toString()).load(); throw new AssertionError("Accepted invalid UTF-8"); }
            catch (ConfigLoader.ConfigException expected) { check(true); }
            rejected(" ".repeat(1048577));

            for (String invalid : List.of("{\"servers\":[]}", "{\"servers\":null}",
                    "{\"servers\":[{\"ports\":[0]}]}", "{\"servers\":[{}],\"host\":\"127.0.0.1\"}",
                    "{\"default_server\":\"true\"}", "{\"request_timeout_ms\":0}",
                    "{\"server_names\":[\"bad name\"]}", "{\"server_names\":[\"a.test\",\"A.TEST\"]}",
                    "{\"routes\":{\"/\":{\"cgi\":{\"py\":\"python3\"}}}}",
                    "{\"routes\":{\"/\":{\"cgi\":{\".py\":null}}}}")) rejected(invalid);
            ConfigLoader multiple = load("""
                {"servers":[
                  {"ports":[9000],"server_names":["a.test"]},
                  {"ports":[9000],"server_names":["b.test"],"default_server":true},
                  {"ports":[0]},
                  {"ports":[9000],"server_names":["a.test"]},
                  {"ports":[9000],"server_names":["c.test"],"default_server":true}
                ]}
                """);
            check(multiple.getServers().size() == 2);
            check(multiple.getServers().get(1).isDefaultServer());
            check(multiple.getServers().get(1).getServerNames().equals(List.of("b.test")));
            ConfigLoader cgi = load("{\"routes\":{\"/\":{\"cgi\":{\".cgi\":\"python3\"}}}}");
            check(cgi.getRouteConfig("/").cgi.equals(Map.of(".cgi", "python3")));
            Files.delete(file);
            try { new ConfigLoader(file.toString()).load(); throw new AssertionError("Accepted missing file"); }
            catch (ConfigLoader.ConfigException expected) { check(true); }

            // A bad startup config must fail before opening a listening socket.
            Files.writeString(file, "{\"ports\":[0]}");
            Path log = work.resolve("startup.log");
            Process process = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"), "Main")
                    .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                check(process.waitFor(5, TimeUnit.SECONDS));
                check(process.exitValue() == 1);
                check(Files.readString(log).contains("Configuration error:"));
            } finally { process.destroyForcibly(); }
            Files.delete(log);
            System.out.println("PASS: " + checks + " config and JSON checks");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(work);
        }
    }
}
