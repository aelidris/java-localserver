import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Server {
    private final ConfigLoader configLoader;
    private final Router router;
    private Selector selector;

    public Server(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.router = new Router(configLoader);
    }

    public void start() throws IOException {
        selector = Selector.open();
        String host = configLoader.getHost();
        List<Integer> ports = configLoader.getPorts();

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

        String[] lines = requestData.split("\r\n");
        if (lines.length == 0) {
            sendError(client, key, 400);
            return;
        }

        String[] requestLineParts = lines[0].split(" ");
        if (requestLineParts.length < 2) {
            sendError(client, key, 400);
            return;
        }

        String method = requestLineParts[0];
        String uri = requestLineParts[1];

        // Check Content-Length for Payload Too Large (413) on POST requests
        if (method.equals("POST")) {
            long contentLength = parseContentLength(lines);
            if (contentLength > configLoader.getMaxBodySize()) {
                sendError(client, key, 413);
                return;
            }
        }

        // Route the request via Router
        Router.RequestResult result = router.route(method, uri);

        // Handle routing status results
        switch (result.status) {
            case OK:
                if (method.equals("DELETE")) {
                    sendResponse(client, key, 200, "File deleted successfully.");
                } else if (method.equals("POST")) {
                    try {
                        String body = extractRequestBody(requestData);
                        Path filePath = Paths.get(result.filePath);

                        if (filePath.getParent() != null) {
                            Files.createDirectories(filePath.getParent());
                        }

                        Files.writeString(filePath, body);

                        sendResponse(client, key, 201, "File uploaded successfully.");
                    } catch (IOException e) {
                        sendError(client, key, 500);
                    }
                } else {
                    sendFile(client, key, result.filePath);
                }
                break;
            case METHOD_NOT_ALLOWED:
                sendError(client, key, 405);
                break;
            case FORBIDDEN:
                sendError(client, key, 403);
                break;
            case NOT_FOUND:
                sendError(client, key, 404);
                break;
            case PAYLOAD_TOO_LARGE:
                sendError(client, key, 413);
                break;
            case INTERNAL_SERVER_ERROR:
            default:
                sendError(client, key, 500);
                break;
        }
    }

    private String extractRequestBody(String requestData) {
        int bodyIndex = requestData.indexOf("\r\n\r\n");
        if (bodyIndex != -1 && bodyIndex + 4 < requestData.length()) {
            return requestData.substring(bodyIndex + 4);
        }
        return "";
    }

    private long parseContentLength(String[] lines) {
        for (String line : lines) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Long.parseLong(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private void sendFile(SocketChannel client, SelectionKey key, String filePathStr) {
        try {
            Path filePath = Paths.get(filePathStr);
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                byte[] responseBody = Files.readAllBytes(filePath);

                String httpResponse = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: " + responseBody.length + "\r\n" +
                        "Connection: close\r\n\r\n";

                ByteBuffer buffer = ByteBuffer.allocate(httpResponse.getBytes().length + responseBody.length);
                buffer.put(httpResponse.getBytes());
                buffer.put(responseBody);
                buffer.flip();

                client.write(buffer);
                client.close();
                key.cancel();
                System.out.println("📤 Handled request -> Status: 200 OK");
            } else {
                sendError(client, key, 404);
            }
        } catch (IOException e) {
            sendError(client, key, 500);
        }
    }

    private void sendResponse(SocketChannel client, SelectionKey key, int statusCode, String message) {
        try {
            String httpResponse = "HTTP/1.1 " + statusCode + " OK\r\n" +
                    "Content-Type: text/plain; charset=UTF-8\r\n" +
                    "Content-Length: " + message.getBytes().length + "\r\n" +
                    "Connection: close\r\n\r\n" +
                    message;

            client.write(ByteBuffer.wrap(httpResponse.getBytes()));
            client.close();
            key.cancel();
            System.out.println("📤 Sent response: " + statusCode);
        } catch (IOException e) {
            System.out.println("⚠️ Error sending response: " + e.getMessage());
        }
    }

    private void sendError(SocketChannel client, SelectionKey key, int statusCode) {
        try {
            String statusText = "";
            switch (statusCode) {
                case 400:
                    statusText = "400 Bad Request";
                    break;
                case 403:
                    statusText = "403 Forbidden";
                    break;
                case 404:
                    statusText = "404 Not Found";
                    break;
                case 405:
                    statusText = "405 Method Not Allowed";
                    break;
                case 413:
                    statusText = "413 Payload Too Large";
                    break;
                case 500:
                    statusText = "500 Internal Server Error";
                    break;
                default:
                    statusText = "404 Not Found";
                    statusCode = 404;
                    break;
            }

            // Get error page path dynamically from ConfigLoader
            String errorFilePath = configLoader.getErrorPagePath(statusCode);
            if (errorFilePath == null) {
                errorFilePath = "error_pages/" + statusCode + ".html";
            }

            Path errorPath = Paths.get(errorFilePath);
            String responseBody = "";

            if (Files.exists(errorPath)) {
                responseBody = new String(Files.readAllBytes(errorPath));
            } else {
                responseBody = "<h1>" + statusText + "</h1>";
            }

            String httpResponse = "HTTP/1.1 " + statusText + "\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + responseBody.getBytes().length + "\r\n" +
                    "Connection: close\r\n\r\n" +
                    responseBody;

            client.write(ByteBuffer.wrap(httpResponse.getBytes()));
            client.close();
            key.cancel();
            System.out.println("❌ Sent error response: " + statusText);

        } catch (IOException e) {
            System.out.println("⚠️ Error sending error response: " + e.getMessage());
        }
    }
}