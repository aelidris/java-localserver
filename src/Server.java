import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Server {
    private final ConfigLoader configLoader;
    private final Router router;
    private final error errorHandler;
    private Selector selector;

    // Map to track last activity time for each client connection (for timeout
    // handling)
    private final Map<SocketChannel, Long> clientLastActiveTime = new HashMap<>();
    private static final long TIMEOUT_MS = 60000; // 60 seconds timeout

    public Server(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.router = new Router(configLoader);
        this.errorHandler = new error(configLoader);
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

        System.out.println("⚡ Single-thread NIO Event Loop started with 60s Timeout.");

        while (true) {
            // Wake up every 1 second to check for timed-out connections
            int readyChannels = selector.select(1000);

            // Check and close inactive connections
            checkTimeouts();

            if (readyChannels == 0) {
                continue;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();

            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();

                if (!key.isValid())
                    continue;

                try {
                    if (key.isAcceptable()) {
                        acceptConnection(key);
                    } else if (key.isReadable()) {
                        readData(key);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("Connection error: " + e.getMessage());
                    if (key.channel() instanceof SocketChannel client) {
                        if (e instanceof IOException) {
                            closeClient(client, key, "Client connection failed.");
                        } else {
                            sendError(client, key, 500);
                        }
                    }
                }
            }
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) return;

        try {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ,
                    new HttpRequestReader(configLoader.getMaxBodySize()));
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }

        // Record connection time
        clientLastActiveTime.put(client, System.currentTimeMillis());
        System.out.println("🔗 Accepted new connection from: " + client.getRemoteAddress());
    }

    private String parseHeaderValue(String[] lines, String headerName) {
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase())) {
                return line.substring(headerName.length()).trim();
            }
        }
        return null;
    }

    private void readData(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        HttpRequestReader request = (HttpRequestReader) key.attachment();

        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            if (request.hasData()) sendError(client, key, 400);
            else closeClient(client, key, "🔌 Client disconnected.");
            return;
        }
        if (bytesRead == 0) return;
        clientLastActiveTime.put(client, System.currentTimeMillis());

        buffer.flip();
        try {
            if (!request.read(buffer)) return; // Wait for the next readable event.
        } catch (HttpRequestReader.RequestException e) {
            sendError(client, key, e.status);
            return;
        }
        handleRequest(client, key, request);
    }

    private void handleRequest(SocketChannel client, SelectionKey key, HttpRequestReader request) {
        String[] lines = request.getHeaderLines();
        if (lines.length == 0) {
            sendError(client, key, 400);
            return;
        }

        String[] requestLineParts = lines[0].split(" ", -1);
        if (requestLineParts.length != 3 || requestLineParts[0].isEmpty()
                || !requestLineParts[2].equals("HTTP/1.1")) {
            sendError(client, key, 400);
            return;
        }

        String method = requestLineParts[0];
        String uri = requestLineParts[1];
        if (!uri.startsWith("/") || uri.contains("#")
                || uri.chars().anyMatch(c -> c <= 32 || c == 127)) {
            sendError(client, key, 400);
            return;
        }

        System.out.println("📥 " + method + " " + uri);
        String cookieHeader = parseHeaderValue(lines, "Cookie:");

        // Route the request via Router
        Router.RequestResult result = router.route(method, uri);

        // Handle routing status results
        switch (result.status) {
            case OK:
                if (method.equals("DELETE")) {
                    sendResponse(client, key, 200, "File deleted successfully.");
                } else if (method.equals("POST")) {
                    try {
                        Path filePath = Paths.get(result.filePath);

                        if (filePath.getParent() != null) {
                            Files.createDirectories(filePath.getParent());
                        }

                        Files.write(filePath, request.getBody());

                        sendResponse(client, key, 201, "File uploaded successfully.");
                    } catch (IOException e) {
                        sendError(client, key, 500);
                    }
                } else {
                    sendFile(client, key, result.filePath);
                }
                break;
            case CGI:
                try {
                    int queryStart = uri.indexOf('?');
                    String queryString = queryStart < 0 ? "" : uri.substring(queryStart + 1);

                    Map<String, String> env = new HashMap<>();
                    if (cookieHeader != null) {
                        env.put("HTTP_COOKIE", cookieHeader);
                    }

                    // Execute CGI script via CGIHandler
                    String cgiRawResponse = CGIHandler.executeCGI(result.filePath, method, queryString, request.getBody(),
                            env);

                    String finalResponse;
                    
                    if (cgiRawResponse.startsWith("HTTP/1.1")) {
                        finalResponse = cgiRawResponse;
                    } else {
                        finalResponse = "HTTP/1.1 200 OK\r\n" + cgiRawResponse;
                    }

                    client.write(ByteBuffer.wrap(finalResponse.getBytes(StandardCharsets.UTF_8)));
                    closeClient(client, key, "📤 Handled CGI request successfully.");
                } catch (Exception e) {
                    sendError(client, key, 500);
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

    private void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<SocketChannel, Long>> iterator = clientLastActiveTime.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<SocketChannel, Long> entry = iterator.next();
            SocketChannel client = entry.getKey();
            long lastActive = entry.getValue();

            if (currentTime - lastActive > TIMEOUT_MS) {
                try {
                    System.out.println(
                            "⏰ Connection timeout reached. Closing inactive client: " + client.getRemoteAddress());
                } catch (IOException e) {
                    System.out.println("⏰ Connection timeout reached. Closing inactive client.");
                }

                try {
                    client.close();
                } catch (IOException ignored) {
                }

                iterator.remove();
            }
        }
    }

    private void closeClient(SocketChannel client, SelectionKey key, String logMessage) {
        clientLastActiveTime.remove(client);
        if (key != null) {
            key.attach(null);
            key.cancel();
        }
        try {
            System.out.println(logMessage);
            client.close();
        } catch (IOException e) {
            System.out.println("⚠️ Error closing client: " + e.getMessage());
        }
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
                closeClient(client, key, "📤 Handled request -> Status: 200 OK");
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
        } catch (Exception e) {
            System.out.println("⚠️ Error sending response: " + e.getMessage());
        } finally {
            closeClient(client, key, "📤 Finished response: " + statusCode);
        }
    }

    private void sendError(SocketChannel client, SelectionKey key, int statusCode) {
        try {
            errorHandler.sendError(client, key, statusCode);
        } catch (Exception e) {
            System.out.println("⚠️ Error sending error response: " + e.getMessage());
        } finally {
            closeClient(client, key, "❌ Finished error response: " + statusCode);
        }
    }
}
