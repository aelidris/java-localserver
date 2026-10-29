import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Server {
    private static final int MAX_CLIENTS = 256;
    private static final int MAX_CGI_JOBS = 16;
    // Leave heap space for parser copies, sessions and the JVM itself.
    private static final long BUFFER_LIMIT = Math.min(32 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 8);
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US);
    private final ConfigLoader config;
    private final Map<SocketChannel, Client> clients = new HashMap<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(4096);
    private Selector selector;
    private int runningCgi;

    private static class Client {
        final SelectionKey key;
        final long started = System.nanoTime();
        long active = started;
        ConfigLoader config;
        HttpRequestReader request;
        HttpResponseWriter response;
        ByteBuffer interim;
        CGIHandler.Job cgi;
        String sessionId;

        Client(SelectionKey key, List<ConfigLoader> sites) {
            this.key = key;
            config = sites.stream().filter(ConfigLoader::isDefaultServer).findFirst().orElse(sites.get(0));
            request = new HttpRequestReader(config.getMaxBodySize(), host -> {
                for (ConfigLoader site : sites) {
                    if (site.getServerNames().contains(host)) { config = site; break; }
                }
                return config.getMaxBodySize();
            });
        }
    }

    public Server(ConfigLoader config) { this.config = config; }

    public void start() throws IOException {
        try {
            selector = Selector.open();
            listen();
            while (true) {
                selector.select(runningCgi == 0 ? 100 : 25);
                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    SelectionKey key = ready.next();
                    ready.remove();
                    if (!key.isValid()) continue;
                    try {
                        if (key.isAcceptable()) accept(key);
                        else if (key.isReadable()) read((Client) key.attachment());
                        else if (key.isWritable()) write((Client) key.attachment());
                    } catch (IOException | RuntimeException e) {
                        if (key.attachment() instanceof Client client) close(client);
                        else System.err.println("Accept failed: " + e.getMessage());
                    }
                }
                maintainClients();
            }
        } finally {
            for (Client client : new ArrayList<>(clients.values())) close(client);
            if (selector != null) {
                for (SelectionKey key : selector.keys()) key.channel().close();
                selector.close();
            }
        }
    }

    private void listen() throws IOException {
        Map<InetSocketAddress, List<ConfigLoader>> bindings = new LinkedHashMap<>();
        for (ConfigLoader site : config.getServers()) {
            for (int port : site.getPorts()) {
                InetSocketAddress address = new InetSocketAddress(site.getHost(), port);
                bindings.computeIfAbsent(address, ignored -> new ArrayList<>()).add(site);
            }
        }
        int listening = 0;
        for (var binding : bindings.entrySet()) {
            ServerSocketChannel socket = ServerSocketChannel.open();
            try {
                socket.configureBlocking(false);
                socket.bind(binding.getKey());
                socket.register(selector, SelectionKey.OP_ACCEPT, binding.getValue());
                listening++;
                System.out.println("Listening on " + binding.getKey());
            } catch (IOException | RuntimeException e) {
                socket.close();
                System.err.println("Skipping listener " + binding.getKey() + ": " + e.getMessage());
            }
        }
        if (listening == 0) throw new IOException("No available listening port");
    }

    @SuppressWarnings("unchecked")
    private void accept(SelectionKey key) throws IOException {
        SocketChannel socket = ((ServerSocketChannel) key.channel()).accept();
        if (socket == null) return;
        try {
            if (clients.size() >= MAX_CLIENTS) { socket.close(); return; }
            socket.configureBlocking(false);
            SelectionKey clientKey = socket.register(selector, SelectionKey.OP_READ);
            Client client = new Client(clientKey, (List<ConfigLoader>) key.attachment());
            clientKey.attach(client);
            clients.put(socket, client);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private void read(Client client) throws IOException {
        if (bufferedBytes() + readBuffer.capacity() > BUFFER_LIMIT) { sendError(client, 503); return; }
        readBuffer.clear();
        int count = ((SocketChannel) client.key.channel()).read(readBuffer);
        if (count == -1) {
            if (client.cgi != null) client.key.interestOps(0); // Half-close still permits a response.
            else if (client.request.hasData()) sendError(client, 400);
            else close(client);
            return;
        }
        if (count == 0) return;
        client.active = System.nanoTime();
        if (client.cgi != null) return;
        readBuffer.flip();
        try {
            if (client.request.read(readBuffer)) handle(client);
            else if (client.request.takeContinue()) {
                client.interim = ByteBuffer.wrap("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.key.interestOps(SelectionKey.OP_WRITE);
            }
        } catch (HttpRequestReader.RequestException e) {
            sendError(client, e.status);
        } catch (java.nio.file.AccessDeniedException e) {
            sendError(client, 403);
        } catch (java.nio.file.NoSuchFileException e) {
            sendError(client, 404);
        } catch (IOException e) {
            sendError(client, 500);
        } catch (RuntimeException e) {
            sendError(client, 500);
        }
    }

    private static String header(String[] lines, String name) {
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (lines[i].substring(0, colon).equalsIgnoreCase(name)) return lines[i].substring(colon + 1).trim();
        }
        return null;
    }

    private void handle(Client client) throws IOException {
        HttpRequestReader request = client.request;
        String[] lines = request.getHeaderLines();
        String[] first = lines[0].split(" ");
        String method = first[0], uri = first[1];
        String cookie = header(lines, "Cookie");
        client.sessionId = Session.resolve(cookie, client.config);
        Router.RequestResult result = new Router(client.config).route(method, uri);
        switch (result.status) {
            case OK -> {
                Path path = Path.of(result.filePath);
                if (method.equals("POST")) {
                    Files.createDirectories(path.getParent());
                    Files.write(path, request.getBody());
                    queue(client, text(201, "Created", "File uploaded successfully."));
                } else if (method.equals("DELETE")) {
                    queue(client, text(200, "OK", "File deleted successfully."));
                } else if (result.isDirectory) {
                    queue(client, new HttpResponseWriter(200, "OK", "text/html; charset=UTF-8",
                            DirectoryListing.create(path, result.directoryUrlPath)));
                } else {
                    queue(client, HttpResponseWriter.file(200, "OK", MimeTypes.forPath(result.filePath), path));
                }
            }
            case CGI -> {
                if (runningCgi >= MAX_CGI_JOBS) { sendError(client, 503); return; }
                int query = uri.indexOf('?');
                Map<String, String> env = new HashMap<>();
                env.put("REQUEST_METHOD", method);
                env.put("QUERY_STRING", query < 0 ? "" : uri.substring(query + 1));
                env.put("REQUEST_URI", uri);
                env.put("SESSION_ID", client.sessionId);
                env.put("SESSION_VISITS", Session.getSession(client.sessionId).get("visits").toString());
                String type = header(lines, "Content-Type");
                if (type != null) env.put("CONTENT_TYPE", type);
                if (cookie != null) env.put("HTTP_COOKIE", cookie);
                try {
                    client.cgi = CGIHandler.start(result.interpreter, result.filePath, request.getBody(), env);
                    runningCgi++;
                    client.request = null;
                } catch (IOException | RuntimeException e) { sendError(client, 500); }
            }
            case REDIRECT -> queue(client, HttpResponseWriter.redirect(result.redirect.status(), result.redirect.location()));
            case METHOD_NOT_ALLOWED -> queue(client, new error(client.config).createResponse(405)
                    .addHeader("Allow", result.allowedMethods));
            case BAD_REQUEST -> sendError(client, 400);
            case FORBIDDEN -> sendError(client, 403);
            case NOT_FOUND -> sendError(client, 404);
            case PAYLOAD_TOO_LARGE -> sendError(client, 413);
            default -> sendError(client, 500);
        }
    }

    private static HttpResponseWriter text(int status, String reason, String message) {
        return new HttpResponseWriter(status, reason, "text/plain; charset=UTF-8", message.getBytes(StandardCharsets.UTF_8));
    }

    private long bufferedBytes() {
        long bytes = 0;
        for (Client client : clients.values()) {
            if (client.request != null) bytes += client.request.bodySize();
            if (client.response != null) bytes += client.response.bufferedBytes();
        }
        return bytes;
    }

    private void queue(Client client, HttpResponseWriter response) {
        client.request = null;
        if (bufferedBytes() + response.bufferedBytes() > BUFFER_LIMIT) {
            response.close();
            response = text(503, "Service Unavailable", "Server buffer limit reached.");
        }
        response.addHeader("Date", HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC)));
        if (client.sessionId != null) {
            response.addHeader("Set-Cookie", "SESSION_ID=" + client.sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                    + Session.MAX_AGE_SECONDS);
        }
        client.response = response;
        client.key.interestOps(SelectionKey.OP_WRITE);
    }

    private void sendError(Client client, int status) {
        cancelCgi(client);
        try { queue(client, new error(client.config).createResponse(status)); }
        catch (RuntimeException e) { close(client); }
    }

    private void write(Client client) throws IOException {
        if (client.interim != null) {
            if (((SocketChannel) client.key.channel()).write(client.interim) > 0) client.active = System.nanoTime();
            if (!client.interim.hasRemaining()) {
                client.interim = null;
                client.key.interestOps(SelectionKey.OP_READ);
            }
            return;
        }
        if (client.response.writeTo((SocketChannel) client.key.channel()) > 0) client.active = System.nanoTime();
        if (client.response.isComplete()) close(client);
    }

    private void maintainClients() {
        long now = System.nanoTime();
        for (Client client : new ArrayList<>(clients.values())) {
            long timeout = client.config.getRequestTimeoutMs() * 1_000_000L;
            // An absolute deadline prevents a slow trickle from holding a request forever.
            if (now - client.active >= timeout || (client.request != null && now - client.started >= timeout)) {
                close(client);
            } else if (client.cgi != null) {
                try {
                    HttpResponseWriter response = client.cgi.poll();
                    if (response != null) { cancelCgi(client); queue(client, response); }
                } catch (IOException | RuntimeException e) { sendError(client, 500); }
            }
        }
        Session.expire();
    }

    private void cancelCgi(Client client) {
        if (client.cgi != null) {
            client.cgi.close();
            client.cgi = null;
            runningCgi--;
        }
    }

    private void close(Client client) {
        clients.remove((SocketChannel) client.key.channel());
        cancelCgi(client);
        if (client.response != null) client.response.close();
        client.key.cancel();
        client.key.attach(null);
        try { client.key.channel().close(); } catch (IOException ignored) { }
    }
}
