import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.ArrayList;
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
    private final Map<SelectionKey, CGIHandler.Job> cgiJobs = new HashMap<>();
    private static final int MAX_CGI_JOBS = 16;

    public Server(ConfigLoader configLoader) {
        this.configLoader = configLoader;
        this.router = new Router(configLoader);
        this.errorHandler = new error(configLoader);
    }

    public void start() throws IOException {
        try {
            runServer();
        } finally {
            cgiJobs.values().forEach(CGIHandler.Job::close);
            cgiJobs.clear();
            if (selector != null) {
                for (SelectionKey key : selector.keys()) {
                    try { key.channel().close(); } catch (IOException ignored) { }
                }
                selector.close();
            }
            clientLastActiveTime.clear();
        }
    }

    private void runServer() throws IOException {
        selector = Selector.open();
        String host = configLoader.getHost();
        List<Integer> ports = configLoader.getPorts();

        for (int port : ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            try {
                serverChannel.configureBlocking(false);
                serverChannel.bind(new InetSocketAddress(host, port));
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            } catch (IOException | RuntimeException e) {
                serverChannel.close();
                throw e;
            }
            System.out.println("🚀 Server listening on " + host + ":" + port);
        }

        System.out.println("⚡ Single-thread NIO Event Loop started with 60s Timeout.");

        while (true) {
            // Check running scripts frequently, without waiting for socket activity.
            selector.select(cgiJobs.isEmpty() ? 1000 : 50);

            // Check and close inactive connections
            checkTimeouts();

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
                    System.err.println("Connection error: " + e.getMessage());
                    if (key.channel() instanceof SocketChannel client) {
                        if (e instanceof IOException || key.attachment() instanceof HttpResponseWriter) {
                            closeClient(client, key, "Client connection failed.");
                        } else {
                            sendError(client, key, 500);
                        }
                    }
                }
            }
            pollCgiJobs();
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
        if (cgiJobs.containsKey(key)) {
            // Detect a reset, but allow a client that half-closes its request to receive the response.
            if (client.read(ByteBuffer.allocate(1024)) == -1) key.interestOps(0);
            return;
        }
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
                } else if (result.isDirectory) {
                    sendDirectory(client, key, result);
                } else {
                    sendFile(client, key, result.filePath);
                }
                break;
            case CGI:
                try {
                    if (cgiJobs.size() >= MAX_CGI_JOBS) {
                        sendError(client, key, 503);
                        break;
                    }
                    int queryStart = uri.indexOf('?');
                    String queryString = queryStart < 0 ? "" : uri.substring(queryStart + 1);

                    Map<String, String> env = new HashMap<>();
                    env.put("REQUEST_METHOD", method);
                    env.put("QUERY_STRING", queryString);
                    env.put("REQUEST_URI", uri);
                    String contentType = parseHeaderValue(lines, "Content-Type:");
                    if (contentType != null) env.put("CONTENT_TYPE", contentType);
                    if (cookieHeader != null) {
                        env.put("HTTP_COOKIE", cookieHeader);
                    }

                    CGIHandler.Job job = CGIHandler.start(result.filePath, request.getBody(), env);
                    cgiJobs.put(key, job);
                    key.attach(job); // Release the accumulated request body.
                } catch (Exception e) {
                    sendError(client, key, 500);
                }
                break;
            case REDIRECT:
                queueResponse(key, HttpResponseWriter.redirect(result.redirect.status(), result.redirect.location()));
                break;
            case BAD_REQUEST:
                sendError(client, key, 400);
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
                    SelectionKey key = client.keyFor(selector);
                    if (key != null) {
                        cancelCgi(key);
                        key.attach(null);
                        key.cancel();
                    }
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
            cancelCgi(key);
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

    private void sendDirectory(SocketChannel client, SelectionKey key, Router.RequestResult result) {
        try {
            byte[] html = DirectoryListing.create(Path.of(result.filePath), result.directoryUrlPath);
            queueResponse(key, new HttpResponseWriter(200, "OK", "text/html; charset=UTF-8", html));
        } catch (java.nio.file.AccessDeniedException e) {
            sendError(client, key, 403);
        } catch (java.nio.file.NoSuchFileException e) {
            sendError(client, key, 404);
        } catch (IOException e) {
            sendError(client, key, 500);
        }
    }

    private void sendFile(SocketChannel client, SelectionKey key, String filePathStr) {
        try {
            Path filePath = Paths.get(filePathStr);
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                queueResponse(key, new HttpResponseWriter(200, "OK", "text/html; charset=UTF-8",
                        Files.readAllBytes(filePath)));
            } else {
                sendError(client, key, 404);
            }
        } catch (IOException e) {
            sendError(client, key, 500);
        }
    }

    private void sendResponse(SocketChannel client, SelectionKey key, int statusCode, String message) {
        queueResponse(key, new HttpResponseWriter(statusCode, statusCode == 201 ? "Created" : "OK",
                "text/plain; charset=UTF-8", message.getBytes(StandardCharsets.UTF_8)));
    }

    private void sendError(SocketChannel client, SelectionKey key, int statusCode) {
        cancelCgi(key);
        try {
            queueResponse(key, errorHandler.createResponse(statusCode));
        } catch (Exception e) {
            closeClient(client, key, "Could not prepare error response: " + e.getMessage());
        }
    }

    private void queueResponse(SelectionKey key, HttpResponseWriter response) {
        key.attach(response);
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void pollCgiJobs() {
        for (SelectionKey key : new ArrayList<>(cgiJobs.keySet())) {
            SocketChannel client = (SocketChannel) key.channel();
            if (!key.isValid()) {
                closeClient(client, key, "CGI client disconnected.");
                continue;
            }
            try {
                HttpResponseWriter response = cgiJobs.get(key).poll();
                if (response != null) {
                    cancelCgi(key);
                    queueResponse(key, response);
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("CGI failed: " + e.getMessage());
                sendError(client, key, 500);
            }
        }
    }

    private void cancelCgi(SelectionKey key) {
        CGIHandler.Job job = cgiJobs.remove(key);
        if (job != null) job.close();
    }

    private void writeData(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        HttpResponseWriter response = (HttpResponseWriter) key.attachment();
        if (response.writeTo(client) > 0) {
            clientLastActiveTime.put(client, System.currentTimeMillis());
        }
        if (response.isComplete()) closeClient(client, key, "📤 Response sent completely.");
    }
}
