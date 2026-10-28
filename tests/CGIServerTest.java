import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real localhost HTTP/CGI integration; requires python3 and permission to open sockets. */
public class CGIServerTest {
    private static int port;
    private static int checks;
    private static Path work;
    private static Path scripts;
    private static Path cgiTemp;

    public static void main(String[] args) throws Exception {
        work = Files.createTempDirectory("cgi-server-test-");
        Process server = null;
        try {
            scripts = Files.createDirectory(work.resolve("scripts"));
            cgiTemp = Files.createDirectory(work.resolve("cgi-temp"));
            Files.createDirectory(work.resolve("public"));
            Files.writeString(work.resolve("public/index.html"), "static-alive");
            Files.writeString(scripts.resolve("fixture.txt"), "relative-file");
            Files.writeString(scripts.resolve("echo.py"), String.join("\n",
                    "import json, os, pathlib, sys",
                    "pathlib.Path('echo-ran').write_text('yes')",
                    "body = sys.stdin.buffer.read()",
                    "keys = ['REQUEST_METHOD','SCRIPT_NAME','SCRIPT_FILENAME','PATH_INFO','PATH_TRANSLATED','QUERY_STRING','CONTENT_LENGTH','CONTENT_TYPE','SERVER_NAME','SERVER_PORT','SERVER_PROTOCOL','REMOTE_ADDR','HTTP_COOKIE']",
                    "result = {key: os.environ.get(key, '') for key in keys}",
                    "result.update(cwd=os.getcwd(), argv=sys.argv, relative=pathlib.Path('fixture.txt').read_text(), body=body.hex())",
                    "sys.stdout.buffer.write(b'Status: 201 Created\\r\\nContent-Type: application/json\\r\\nSet-Cookie: a=1\\r\\nSet-Cookie: b=2\\r\\nContent-Length: 999\\r\\n\\r\\n' + json.dumps(result).encode())", ""));
            Files.writeString(scripts.resolve("mode.py"), String.join("\n",
                    "import os, pathlib, sys, time",
                    "mode = os.environ.get('QUERY_STRING', '')",
                    "pathlib.Path(mode + '.pid').write_text(str(os.getpid()))",
                    "if mode in ('timeout','reset') or mode.startswith('busy'): time.sleep(30)",
                    "if mode == 'slow': time.sleep(1.5)",
                    "if mode == 'half': time.sleep(0.2)",
                    "if mode == 'exit': sys.exit(7)",
                    "if mode == 'bad': sys.stdout.write('invalid output'); sys.exit(0)",
                    "if mode == 'limit': sys.stdout.buffer.write(b'x' * 2097153); sys.exit(0)",
                    "if mode == 'stderr': sys.stderr.buffer.write(b'x' * 2097153)",
                    "if mode == 'redirect': sys.stdout.write('Location: /\\n\\n'); sys.exit(0)",
                    "if mode == 'empty': sys.stdout.write('Status: 204 No Content\\n\\n'); sys.exit(0)",
                    "body = bytes(range(256)) * 4096 if mode == 'large' else sys.stdin.buffer.read() or b'done'",
                    "sys.stdout.buffer.write(b'Content-Type: application/octet-stream\\r\\n\\r\\n' + body)", ""));
            try (ServerSocket reservation = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                port = reservation.getLocalPort();
            }
            Files.writeString(work.resolve("config.json"), "{\"host\":\"127.0.0.1\",\"ports\":[" + port + "],\"client_max_body_size\":65536,\"routes\":{"
                    + "\"/\":{\"root\":\"./public\",\"accepted_methods\":[\"GET\"]},"
                    + "\"/cgi\":{\"root\":\"./scripts\",\"default_file\":\"echo.py\",\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"],\"cgi\":{\".py\":\"python3\"}},"
                    + "\"/source\":{\"root\":\"./scripts\",\"accepted_methods\":[\"GET\",\"POST\",\"DELETE\"]},"
                    + "\"/bad\":{\"root\":\"./scripts\",\"accepted_methods\":[\"GET\"],\"cgi\":{\".py\":\"/no-such-interpreter\"}},"
                    + "\"/uploads\":{\"root\":\"./uploads\",\"accepted_methods\":[\"GET\",\"POST\"]}}}");
            Path classes = Path.of(CGIServerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.io.tmpdir=" + cgiTemp, "-cp", classes.toString(), "Main")
                    .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("server.log").toFile()).start();
            boolean started = false;
            for (int i = 0; i < 100; i++) {
                if (!server.isAlive()) { throw new AssertionError(Files.readString(work.resolve("server.log"))); }
                try {
                    check(request("GET", "/", "", new byte[0]).status == 200, "server starts");
                    started = true;
                    break;
                } catch (java.net.ConnectException e) { Thread.sleep(50); }
            }
            check(started, "server ready before deadline");
            exerciseServer();
            System.out.println("Passed " + checks + " HTTP/CGI integration checks");
        } finally {
            if (server != null) {
                List<ProcessHandle> children = server.descendants().collect(java.util.stream.Collectors.toList());
                children.forEach(ProcessHandle::destroyForcibly);
                server.destroy();
                if (!server.waitFor(3, TimeUnit.SECONDS)) { server.destroyForcibly().waitFor(); }
                for (ProcessHandle child : children) {
                    if (child.isAlive()) { child.destroyForcibly(); }
                }
            }
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) { Files.deleteIfExists(path); }
            }
        }
    }

    private static void exerciseServer() throws Exception {
        byte[] binary = {0, (byte) 255, 13, 10};
        Response response = request("POST", "/cgi/echo.py/extra?x=a%20b+z",
                "Content-Type: application/octet-stream\r\nCookie: a=1\r\nCookie: b=2\r\n", binary);
        check(response.status == 201 && response.headers.contains("Set-Cookie: a=1\r\nSet-Cookie: b=2"), "CGI status and repeated cookies");
        Map<?, ?> env = (Map<?, ?>) JsonParser.parse(new String(response.body, StandardCharsets.UTF_8));
        check(env.get("REQUEST_METHOD").equals("POST") && env.get("SCRIPT_NAME").equals("/cgi/echo.py"), "CGI method and script URI");
        check(env.get("PATH_INFO").equals("/extra") && env.get("PATH_TRANSLATED").equals(work.resolve("public/extra").toString()), "path info and physical translation");
        check(env.get("QUERY_STRING").equals("x=a%20b+z"), "raw query string");
        check(env.get("CONTENT_LENGTH").equals("4") && env.get("CONTENT_TYPE").equals("application/octet-stream"), "CGI content metadata");
        check(env.get("body").equals("00ff0d0a") && env.get("HTTP_COOKIE").equals("a=1; b=2"), "binary stdin and cookies");
        check(env.get("SCRIPT_FILENAME").equals(scripts.resolve("echo.py").toString())
                && env.get("argv").equals(List.of(scripts.resolve("echo.py").toString())), "absolute script is first interpreter argument");
        check(env.get("cwd").equals(scripts.toString()) && env.get("relative").equals("relative-file"), "relative file handling");
        check(env.get("SERVER_PORT").equals(Integer.toString(port)) && env.get("SERVER_NAME").equals("127.0.0.1")
                && env.get("REMOTE_ADDR").equals("127.0.0.1") && env.get("SERVER_PROTOCOL").equals("HTTP/1.1"), "network metadata");
        check(request("GET", "/cgi", "", new byte[0]).status == 201, "CGI directory index");
        check(request("DELETE", "/cgi/echo.py", "", new byte[0]).status == 201 && Files.exists(scripts.resolve("echo.py")), "DELETE dispatched to CGI");

        Files.delete(scripts.resolve("echo-ran"));
        try (Socket client = connect()) {
            client.getOutputStream().write("POST /cgi/echo.py HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().write(binary, 0, 2);
            Thread.sleep(150);
            check(!Files.exists(scripts.resolve("echo-ran")), "incomplete body does not launch script");
            client.getOutputStream().write(binary, 2, 2);
            check(read(client).status == 201, "fragmented body launches once complete");
        }
        byte[] chunked = new byte[] {'4',13,10,0,(byte)255,13,10,13,10,'0',13,10,13,10};
        check(Arrays.equals(request("POST", "/cgi/mode.py?binary", "Transfer-Encoding: chunked\r\n", chunked).body, binary), "decoded chunked stdin and binary stdout");
        check(request("GET", "/cgi/mode.py?redirect", "", new byte[0]).headers.contains("Location: /"), "CGI redirect forwarded");
        response = request("GET", "/cgi/mode.py?empty", "", new byte[0]);
        check(response.status == 204 && response.body.length == 0 && !response.headers.contains("Content-Length:"), "204 omits body framing");
        response = request("GET", "/cgi/mode.py?large", "", new byte[0]);
        check(response.body.length == 1048576, "large CGI response complete");
        for (int i = 0; i < response.body.length; i++) {
            if (response.body[i] != (byte) i) { throw new AssertionError("Large CGI body corrupted"); }
        }
        clean();

        try (Socket slow = open("GET", "/cgi/mode.py?slow", "", new byte[0])) {
            await(() -> Files.exists(scripts.resolve("slow.pid")), 3000, "slow script started");
            long start = System.nanoTime();
            check(request("GET", "/", "", new byte[0]).status == 200, "static request during CGI");
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "CGI does not block event loop");
            check(read(slow).status == 200, "slow CGI completes");
        }
        try (Socket half = open("GET", "/cgi/mode.py?half", "", new byte[0])) {
            half.shutdownOutput();
            check(read(half).status == 200, "half-close retains CGI response");
        }
        for (String mode : new String[] {"exit", "bad", "limit", "stderr", "timeout"}) {
            check(request("GET", "/cgi/mode.py?" + mode, "", new byte[0]).status == 500, "CGI failure becomes 500: " + mode);
            clean();
            check(request("GET", "/", "", new byte[0]).status == 200, "server survives CGI failure");
        }
        long timeoutPid = Long.parseLong(Files.readString(scripts.resolve("timeout.pid")));
        await(() -> !ProcessHandle.of(timeoutPid).map(ProcessHandle::isAlive).orElse(false), 3000, "timed out process terminated");
        try (Socket reset = open("GET", "/cgi/mode.py?reset", "", new byte[0])) {
            await(() -> Files.exists(scripts.resolve("reset.pid")), 3000, "reset test process started");
            reset.setSoLinger(true, 0);
        }
        long resetPid = Long.parseLong(Files.readString(scripts.resolve("reset.pid")));
        await(() -> !ProcessHandle.of(resetPid).map(ProcessHandle::isAlive).orElse(false), 3000, "reset cancels CGI promptly");
        awaitClean();

        check(request("GET", "/bad/echo.py", "", new byte[0]).status == 500, "missing interpreter");
        check(request("GET", "/cgi/missing.py", "", new byte[0]).status == 404, "missing CGI script");
        for (String method : new String[] {"GET", "POST", "DELETE"}) {
            check(request(method, "/source/echo.py", "", binary).status == 403, "CGI source alias protection");
        }
        check(request("POST", "/cgi/new.py", "", binary).status == 405 && !Files.exists(scripts.resolve("new.py")), "CGI root is not an upload directory");
        byte[] source = "print('must not execute')".getBytes(StandardCharsets.US_ASCII);
        check(request("POST", "/uploads/user.py", "", source).status == 201, "normal upload remains supported");
        check(Arrays.equals(request("GET", "/uploads/user.py", "", new byte[0]).body, source), "uploaded extension does not enable CGI");
        Files.delete(scripts.resolve("echo-ran"));
        try (Socket oversized = connect()) {
            oversized.getOutputStream().write("POST /cgi/echo.py HTTP/1.1\r\nHost: localhost\r\nContent-Length: 65537\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            check(read(oversized).status == 413 && !Files.exists(scripts.resolve("echo-ran")), "body limit before execution");
        }
        List<Socket> busy = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) { busy.add(open("GET", "/cgi/mode.py?busy" + i, "", new byte[0])); }
            await(() -> java.util.stream.IntStream.range(0, 16).allMatch(i -> Files.exists(scripts.resolve("busy" + i + ".pid"))),
                    3000, "CGI capacity reached");
            check(request("GET", "/cgi/echo.py", "", new byte[0]).status == 503, "CGI process count bounded");
            check(request("GET", "/", "", new byte[0]).status == 200, "static request at CGI capacity");
        } finally {
            for (Socket client : busy) { client.setSoLinger(true, 0); client.close(); }
        }
        awaitClean();
    }

    private static Socket connect() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10000);
        return socket;
    }

    private static Socket open(String method, String target, String headers, byte[] body) throws Exception {
        Socket socket = connect();
        try {
            String framing = headers.contains("Transfer-Encoding:") ? "" : "Content-Length: " + body.length + "\r\n";
            socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: localhost\r\n" + framing + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            return socket;
        } catch (Exception e) { socket.close(); throw e; }
    }

    private static Response request(String method, String target, String headers, byte[] body) throws Exception {
        try (Socket socket = open(method, target, headers, body)) { return read(socket); }
    }

    private static Response read(Socket socket) throws Exception {
        byte[] wire = socket.getInputStream().readAllBytes();
        String text = new String(wire, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        check(end >= 0, "complete response headers");
        Response response = new Response();
        response.headers = text.substring(0, end);
        response.status = Integer.parseInt(text.split(" ")[1]);
        response.body = Arrays.copyOfRange(wire, end + 4, wire.length);
        if (response.status != 204 && response.status != 304) {
            check(response.headers.contains("Content-Length: " + response.body.length + "\r\n"), "exact response length");
        }
        return response;
    }

    private static class Response { int status; String headers; byte[] body; }

    private static void clean() throws Exception {
        try (var paths = Files.list(cgiTemp)) { check(paths.findAny().isEmpty(), "CGI temporary files cleaned"); }
    }

    private static void awaitClean() throws Exception {
        await(() -> {
            try (var paths = Files.list(cgiTemp)) { return paths.findAny().isEmpty(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, 3000, "cancelled CGI files cleaned");
    }

    private static void await(BooleanSupplier condition, long milliseconds, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(20); }
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
        checks++;
    }
}
