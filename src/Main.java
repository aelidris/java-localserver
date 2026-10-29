public class Main {
    public static void main(String[] args) {
        try {
            // Load configuration from config.json
            ConfigLoader config = new ConfigLoader(args.length == 0 ? "config.json" : args[0]);
            config.load();

            // Pass the config instance directly to Server
            Server server = new Server(config);
            server.start();

        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Server failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
