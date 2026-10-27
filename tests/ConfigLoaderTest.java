import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ConfigLoaderTest {
    private static int checks;
    private static Path file;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("config-tests-");
        file = directory.resolve("config.json");
        try {
            ConfigLoader sample = new ConfigLoader("config.json");
            sample.load();
            check(sample.getPorts().equals(List.of(8080, 8081)), "repository ports");
            check(sample.getMaxBodySize() == 1048576, "repository body size");
            check(sample.getRouteConfig("/").acceptedMethods.equals(List.of("GET", "POST", "DELETE")), "repository methods");
            check(sample.getErrorPagePath(404).equals("error_pages/404.html"), "repository error pages");

            ConfigLoader defaults = load("{}");
            check(defaults.getHost().equals("127.0.0.1") && defaults.getPorts().equals(List.of(8080)), "documented defaults");
            check(defaults.getRouteConfig("/").acceptedMethods.equals(List.of("GET")), "default read-only route");
            ConfigLoader custom = load("{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"],"
                    + "\"root\":\"./web,files:{x}\",\"default_file\":\"home\\u002ehtml\",\"directory_listing\":true}},"
                    + "\"error_pages\":{\"404\":\"error: pages/missing.html\"},\"ports\":[9000,9001],"
                    + "\"client_max_body_size\":0,\"host\":\"127.0.0.1\"}");
            check(custom.getRouteConfig("/").root.equals("./web,files:{x}"), "punctuation inside strings");
            check(custom.getRouteConfig("/").defaultFile.equals("home.html"), "unicode escape");
            check(custom.getRouteConfig("/").directoryListing, "boolean config applied");
            check(custom.getErrorPagePath(404).equals("error: pages/missing.html"), "path containing colon");
            check(custom.getMaxBodySize() == 0, "zero body limit is valid");
            try {
                custom.getPorts().add(9002);
                throw new AssertionError("Ports can be changed outside validation");
            } catch (UnsupportedOperationException expected) {
                checks++;
            }
            Files.writeString(file, "{\"ports\":[9002],\"client_max_body_size\":-1}");
            rejectLoad(custom, "client_max_body_size");
            check(custom.getPorts().equals(List.of(9000, 9001)) && custom.getMaxBodySize() == 0,
                    "failed reload leaves previous config intact");
            Files.writeString(file, "{}");
            custom.load();
            check(custom.getPorts().equals(List.of(8080)) && custom.getErrorPagePath(404) == null,
                    "reload resets removed fields");
            check(load("{\"ports\":[1,65535],\"client_max_body_size\":2147483639}").getPorts().size() == 2,
                    "numeric upper and lower boundaries");
            check(load("{\"ports\":[8e3]}").getPorts().equals(List.of(8000)), "JSON exponent notation");
            ConfigLoader customRoutes = load("{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"]},"
                    + "\"/assets/icons/\":{\"accepted_methods\":[\"GET\"],\"root\":\"./icons\"}}}");
            check(customRoutes.getRouteConfig("/assets/icons").root.equals("./icons"), "custom route and trailing slash normalization");
            check(customRoutes.getRoutePaths().contains("/assets/icons"), "route prefixes exposed for matching");
            try {
                customRoutes.getRoutePaths().clear();
                throw new AssertionError("Route paths can be changed outside validation");
            } catch (UnsupportedOperationException expected) {
                checks++;
            }
            for (String name : new String[] {"api", "", "//api", "/api//v1", "/api/../x", "/api/./x",
                    "/api?x", "/api#x", "/api%2fx", "/api x"}) {
                reject("{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"]},\"" + name
                        + "\":{\"accepted_methods\":[\"GET\"]}}}", "routes.");
            }
            reject("{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"]},"
                    + "\"/api\":{\"accepted_methods\":[\"GET\"]},\"/api/\":{\"accepted_methods\":[\"GET\"]}}}", "duplicates route");

            for (String value : new String[] {"[]", "null", "\"8080\"", "[0]", "[-1]", "[65536]", "[1.5]",
                    "[\"8080\"]", "[true]", "[null]", "[8080,8080]", "[9999999999999999999999]"}) {
                reject("{\"ports\":" + value + "}", "ports");
            }
            for (String value : new String[] {"-1", "1.5", "true", "null", "\"100\"", "2147483640", "1e100000"}) {
                reject("{\"client_max_body_size\":" + value + "}", "client_max_body_size");
            }
            for (String value : new String[] {"null", "5", "\"\"", "\" \"", "\"bad host\"", "\"http://localhost\"", "\"x\\n\""}) {
                reject("{\"host\":" + value + "}", "host");
            }
            reject("{\"port\":8080}", "config.port");
            reject("{\"error_pages\":[]}", "error_pages");
            reject("{\"error_pages\":{\"200\":\"x\"}}", "error_pages.200");
            reject("{\"error_pages\":{\"404\":null}}", "error_pages.404");
            reject("{\"error_pages\":{\"404\":\"bad\\u0000path\"}}", "error_pages.404");
            reject("{\"routes\":{}}", "routes");
            reject("{\"routes\":{\"/\":[]}}", "routes./");
            for (String fields : new String[] {"", "\"accepted_methods\":[]", "\"accepted_methods\":[\"PATCH\"]",
                    "\"accepted_methods\":[\"GET\",\"GET\"]", "\"accepted_methods\":[null]",
                    "\"accepted_methods\":[\"GET\"],\"directory_listing\":\"false\"",
                    "\"accepted_methods\":[\"GET\"],\"root\":null",
                    "\"accepted_methods\":[\"GET\"],\"default_file\":\"../secret\"",
                    "\"accepted_methods\":[\"GET\"],\"default_file\":\"/absolute\"",
                    "\"accepted_methods\":[\"GET\"],\"redirect\":\"/elsewhere\""}) {
                reject("{\"routes\":{\"/\":{" + fields + "}}}", "routes./");
            }
            reject("{\"routes\":{\"/\":{\"accepted_methods\":[\"GET\"]},\"/api\":{}}}", "routes./api");
            for (String json : new String[] {"", "{", "{\"ports\":[8080,]}", "{\"ports\":[8080] garbage}",
                    "{\"host\":\"unterminated}", "{} false", "{\"host\":\"x\n\"}", "{\"ports\":[01]}",
                    "{\"ports\":[+1]}", "{\"ports\":[1.]}", "{\"ports\":[NaN]}", "{\"x\":true,}",
                    "{\"x\":\"\\q\"}", "{\"x\":\"\\uXYZ1\"}", "{\"host\":null,\"host\":\"127.0.0.1\"}",
                    "{\"host\":\"127.0.0.1\",\"ho\\u0073t\":\"localhost\"}"}) {
                reject(json, "Invalid JSON");
            }
            reject("[]", "config must be a JSON object");
            reject("null", "config must be a JSON object");
            reject("[".repeat(40) + "0" + "]".repeat(40), "nesting");
            reject(" ".repeat(1024 * 1024 + 1), "1 MiB");
            Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
            rejectLoad(new ConfigLoader(file.toString()), "Invalid configuration");
            Files.delete(file);
            rejectLoad(new ConfigLoader(file.toString()), "Invalid configuration");

            Object decoded = JsonParser.parse("{\"x\":[true,false,null,\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"]}");
            List<?> values = (List<?>) ((Map<?, ?>) decoded).get("x");
            check(values.get(0).equals(true) && values.get(1).equals(false) && values.get(2) == null, "JSON literals");
            check(values.get(3).equals("\"\\/\b\f\n\r\t"), "JSON string escapes");
            startupFailure(directory);
            System.out.println("Passed " + checks + " configuration checks");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory.resolve("startup.log"));
            Files.deleteIfExists(directory);
        }
    }

    private static void startupFailure(Path directory) throws Exception {
        Files.writeString(file, "{\"ports\":[0]}");
        Path classes = Path.of(ConfigLoaderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path log = directory.resolve("startup.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), "Main").directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            check(process.waitFor(5, TimeUnit.SECONDS), "invalid config exits promptly");
            check(process.exitValue() == 1, "invalid config exit status");
            String output = Files.readString(log);
            check(output.contains("Configuration error:") && output.contains("ports[0]"), "clear startup error");
            check(!output.contains("listening") && !output.contains("at ConfigLoader"), "no startup or stack trace");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }

    private static ConfigLoader load(String json) throws Exception {
        Files.writeString(file, json);
        ConfigLoader loader = new ConfigLoader(file.toString());
        loader.load();
        return loader;
    }

    private static void reject(String json, String message) throws Exception {
        Files.writeString(file, json);
        rejectLoad(new ConfigLoader(file.toString()), message);
    }

    private static void rejectLoad(ConfigLoader loader, String message) {
        try {
            loader.load();
            throw new AssertionError("Invalid config accepted");
        } catch (ConfigLoader.ConfigException expected) {
            check(expected.getMessage().contains(message), "error should contain '" + message + "': " + expected.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}
