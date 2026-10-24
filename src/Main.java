public class Main {
    public static void main(String[] args) {
        try {
            // Load configuration from config.json
            ConfigLoader config = new ConfigLoader("config.json");
            config.load();

            // Pass the config instance directly to Server
            Server server = new Server(config);
            server.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}