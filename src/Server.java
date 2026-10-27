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
                    } else if (key.isWritable()) {
                        writeData(key);
                    }
                } catch (IOException | RuntimeException e) {
                    if (key.channel() instanceof SocketChannel) {
                        closeClient((SocketChannel) key.channel(), key,
                                "⚠️ Client request failed: " + e.getClass().getSimpleName());
                    } else {
                        System.err.println("⚠️ Could not accept connection: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) {
            return;
        }
        try {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ,
                    new HttpRequestReader(configLoader.getMaxBodySize()));

            // Record connection time
            clientLastActiveTime.put(client, System.currentTimeMillis());
            System.out.println("🔗 Accepted new connection from: " + client.getRemoteAddress());
        } catch (IOException | RuntimeException e) {
            closeClient(client, client.keyFor(selector), "⚠️ Client setup failed.");
            throw e;
        }
    }

    private void readData(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();

        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int bytesRead = client.read(buffer);

        if (bytesRead == -1) {
            closeClient(client, key, "🔌 Client disconnected.");
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        clientLastActiveTime.put(client, System.currentTimeMillis());
        HttpRequestReader request = (HttpRequestReader) key.attachment();
        buffer.flip();
        try {
            if (!request.consume(buffer)) {
                return;
            }
        } catch (HttpRequestReader.RequestException e) {
            sendError(key, e.statusCode);
            return;
        }

        String method = request.getMethod();
        String uri = request.getUri();
        System.out.println("📥 Received complete request: " + method + " " + uri);

        // Route the request via Router
        Router.RequestResult result = router.route(method, uri);

        // Handle routing status results
        switch (result.status) {
            case OK:
                if (method.equals("DELETE")) {
                    sendResponse(key, 200, "File deleted successfully.");
                } else if (method.equals("POST")) {
                    try {
                        Path filePath = Paths.get(result.filePath);

                        if (filePath.getParent() != null) {
                            Files.createDirectories(filePath.getParent());
                        }

                        Files.write(filePath, request.getBody());

                        sendResponse(key, 201, "File uploaded successfully.");
                    } catch (IOException e) {
                        sendError(key, 500);
                    }
                } else if (result.isDirectory) {
                    sendDirectory(key, result);
                } else {
                    sendFile(key, result.filePath);
                }
                break;
            case REDIRECT:
                queueResponse(key, HttpResponseWriter.redirect(result.redirect.status, result.redirect.location));
                break;
            case BAD_REQUEST:
                sendError(key, 400);
                break;
            case METHOD_NOT_ALLOWED:
                sendError(key, 405);
                break;
            case FORBIDDEN:
                sendError(key, 403);
                break;
            case NOT_FOUND:
                sendError(key, 404);
                break;
            case PAYLOAD_TOO_LARGE:
                sendError(key, 413);
                break;
            case INTERNAL_SERVER_ERROR:
            default:
                sendError(key, 500);
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
                SelectionKey key = client.keyFor(selector);
                if (key != null) {
                    key.attach(null);
                    key.cancel();
                }
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
        System.out.println(logMessage);
        clientLastActiveTime.remove(client);
        if (key != null) {
            key.attach(null);
            key.cancel();
        }
        try {
            client.close();
        } catch (IOException e) {
            System.out.println("⚠️ Error closing client: " + e.getMessage());
        }
    }

    private void sendFile(SelectionKey key, String filePathStr) {
        try {
            Path filePath = Paths.get(filePathStr);
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                byte[] responseBody = Files.readAllBytes(filePath);

                queueResponse(key, new HttpResponseWriter(200, "OK",
                        "text/html; charset=UTF-8", responseBody));
            } else {
                sendError(key, 404);
            }
        } catch (IOException e) {
            sendError(key, 500);
        }
    }

    private void sendDirectory(SelectionKey key, Router.RequestResult result) {
        try {
            byte[] body = DirectoryListing.render(Paths.get(result.filePath), result.directoryUrlPath);
            queueResponse(key, new HttpResponseWriter(200, "OK", "text/html; charset=UTF-8", body));
        } catch (java.nio.file.AccessDeniedException e) {
            sendError(key, 403);
        } catch (java.nio.file.NoSuchFileException | java.nio.file.NotDirectoryException e) {
            sendError(key, 404);
        } catch (IOException e) {
            sendError(key, 500);
        }
    }

    private void sendResponse(SelectionKey key, int statusCode, String message) {
        queueResponse(key, new HttpResponseWriter(statusCode, statusCode == 201 ? "Created" : "OK",
                "text/plain; charset=UTF-8", message.getBytes(StandardCharsets.UTF_8)));
    }

    private void sendError(SelectionKey key, int statusCode) {
        queueResponse(key, errorHandler.createResponse(statusCode));
    }

    private void queueResponse(SelectionKey key, HttpResponseWriter response) {
        // Release the request buffer and resume only when the socket can accept output.
        key.attach(response);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void writeData(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        HttpResponseWriter response = (HttpResponseWriter) key.attachment();
        if (response.writeTo(client) > 0) {
            clientLastActiveTime.put(client, System.currentTimeMillis());
        }
        if (response.isComplete()) {
            closeClient(client, key, "📤 Response sent completely.");
        }
    }
}
