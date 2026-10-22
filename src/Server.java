import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Server {
    private final String host;
    private final List<Integer> ports;
    private Selector selector;

    public Server(String host, List<Integer> ports) {
        this.host = host;
        this.ports = ports;
    }

    public void start() throws IOException {
        selector = Selector.open();

        for (int port : ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(host, port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("🚀 Server listening on " + host + ":" + port);
        }

        System.out.println("⚡ Single-thread NIO Event Loop started successfully.");

        while (true) {
            selector.select();
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                if (!key.isValid())
                    continue;

                if (key.isAcceptable()) {
                    acceptConnection(key);
                } else if (key.isReadable()) {
                    readData(key);
                }
            }
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        System.out.println("🔗 Accepted new connection from: " + client.getRemoteAddress());
    }

    private void readData(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            System.out.println("🔌 Client disconnected.");
            client.close();
            key.cancel();
            return;
        }

        String requestData = new String(buffer.array(), 0, bytesRead);
        System.out.println("📥 Received request:\n" + requestData);

        if (requestData.startsWith("GET")) {
            String[] lines = requestData.split("\r\n");
            String[] requestLineParts = lines[0].split(" ");
            String path = requestLineParts.length > 1 ? requestLineParts[1] : "/";

            String filePathStr = path.equals("/") ? "public/index.html" : "public" + path;
            java.nio.file.Path filePath = java.nio.file.Paths.get(filePathStr);

            String responseBody = "";
            String statusCode = "200 OK";

            if (java.nio.file.Files.exists(filePath) && !java.nio.file.Files.isDirectory(filePath)) {
                responseBody = new String(java.nio.file.Files.readAllBytes(filePath));
            } else {
                statusCode = "404 Not Found";
                java.nio.file.Path errorPath = java.nio.file.Paths.get("error_pages/404.html");
                if (java.nio.file.Files.exists(errorPath)) {
                    responseBody = new String(java.nio.file.Files.readAllBytes(errorPath));
                } else {
                    responseBody = "<h1>404 Not Found</h1>";
                }
            }

            String httpResponse = "HTTP/1.1 " + statusCode + "\r\n" +
                                  "Content-Type: text/html; charset=UTF-8\r\n" +
                                  "Content-Length: " + responseBody.getBytes().length + "\r\n" +
                                  "Connection: close\r\n\r\n" +
                                  responseBody;

            client.write(ByteBuffer.wrap(httpResponse.getBytes()));
            client.close();
            key.cancel();
            System.out.println("📤 Handled request for path: " + path + " -> Status: " + statusCode);
        }
    }
}