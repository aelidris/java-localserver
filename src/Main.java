public class Main {
    public static void main(String[] args) {
        try {
            ConfigLoader config = new ConfigLoader("config.json");
            config.load();

            Server server = new Server(config.getHost(), config.getPorts());
            server.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}