# Java Local Server (`java-localserver`)

A lightweight, custom HTTP/1.1-compliant web server built from scratch in Java using non-blocking I/O (`java.nio`) and event-driven architecture. Designed to handle internal APIs, static content, and dynamic CGI scripts with zero external server frameworks or asynchronous runtimes (e.g., Netty, Jetty).

## 🚀 Key Features

- **Event-Driven Non-Blocking I/O:** Powered by Java NIO (`Selectors`, `Channels`) operating on a single-process, single-thread model to handle high concurrency efficiently.
- **HTTP Request Handling:** Supports `GET`, `POST`, and `DELETE`, with incremental header/body reads for `Content-Length` requests. Chunked requests are not yet supported; full HTTP/1.1 compliance remains in progress.
- **CGI Handler:** Provides script execution through `ProcessBuilder`, request environment mapping, and response parsing. HTTP routing to the handler is not yet connected in `Server.java`.
- **Configuration Engine:** JSON-based configuration supporting host/port binding, custom routes, redirection, directory listings, and body size limits.
- **Session & Cookie Management:** Built-in state handling for user sessions and cookies.
- **Robust Error Handling & Safety:** Default custom HTML error pages for `400`, `403`, `404`, `405`, `413`, and `500` errors, designed to prevent crashes, memory leaks, and file descriptor exhaustion.

## 🛠️ Tech Stack & Constraints
- **Language:** Java (Core Libraries only)
- **Networking & I/O:** `java.net`, `java.nio`
- **Testing:** Load-tested using `siege` for high availability targets (99.5%+).

## 📁 Project Structure

```
/java-server
├── /src
│   ├── Main.java         # Entry point & server bootstrapper
│   ├── Server.java       # NIO event loop & lifecycle management
│   ├── Router.java       # Request routing & method dispatching
│   ├── CGIHandler.java   # External script execution via ProcessBuilder
│   ├── ConfigLoader.java # Configuration parsing engine
│   └── utils/
│       ├── Session.java  # Session state tracking
│       └── Cookie.java   # Cookie utility functions
├── config.json           # Server routing & environment config
├── error_pages/          # Custom HTML error response files
└── README.md
```

## Request receiving

Each connection accumulates its own request across socket reads. Routing and
upload writes happen only after the headers and declared body have arrived.
Raw upload bodies stay as bytes, preserving binary files; multipart form parsing
is not implemented. Headers are limited to 16 KiB, including the request line and
final blank line. Bodies are buffered in memory up to `client_max_body_size`.

Invalid or repeated `Content-Length` headers receive 400; declared bodies above
the configured limit receive 413 without waiting for the body. Requests without
`Content-Length` have an empty body. Until chunked decoding is implemented,
`Transfer-Encoding` requests are rejected with 400. Connections close after one
complete response. Files, text responses, and error pages retain unsent bytes and
resume on `OP_WRITE` events, including when a socket temporarily accepts zero
bytes. Write failures close only the affected connection. Responses are still
buffered in memory; streaming files from disk remains a future improvement.

Run the request reader regression checks (JDK 11+):

```bash
bash test-requests.sh
```

Run the response writer regression checks, including short/zero writes, binary
bodies, UTF-8 byte lengths, and custom/fallback error pages:

```bash
bash test-responses.sh
```

## CGI handler usage

`CGIHandler.start` launches an authorized script with a complete request body.
Pass the interpreter as separate arguments; no shell command is constructed.
The caller must validate the script path, allowed HTTP method, and request body
size before starting it. Never execute scripts from an untrusted upload directory.

```java
CGIHandler.Job job = CGIHandler.start(
    Path.of("cgi/hello.py"),
    List.of("python3"),
    Map.of("REQUEST_METHOD", "POST",
           "SCRIPT_NAME", "/cgi/hello.py",
           "QUERY_STRING", "name=world",
           "PATH_INFO", "",
           "CONTENT_TYPE", "text/plain",
           "SERVER_NAME", "localhost",
           "SERVER_PORT", "8080"),
    requestBody);

// Keep the job with the connection and poll it on subsequent event-loop ticks.
CGIHandler.Response response = job.poll();
if (response != null) {
    byte[] body = response.getBody();
    // Write statusCode, headers, your own Content-Length, and body to the client.
}
```

Use `java.nio.file.Path`, `java.util.List`, and `java.util.Map` for this example.
`requestBody` is the complete request as a byte array. The handler calculates
`CONTENT_LENGTH` from those bytes. Polling returns `null` while the process is
running and delivers the response once; polling a finished or cancelled job fails.
Always call `job.close()` if the client disconnects or request handling is abandoned.
Completion and failures clean up temporary input, output, and error files.
Map execution/parsing failures (`IOException`) to an appropriate HTTP error.

Jobs have a five-second timeout and a combined 2 MiB stdout/stderr limit, checked
on each poll. Poll regularly even when no sockets are ready. These checks are not
OS resource quotas: a script can exceed the disk limit between polls. Scripts are
trusted programs running with the server's permissions and inherited environment.

Response parsing accepts LF or CRLF headers, optional `Status`, repeated headers
such as `Set-Cookie`, and binary bodies. Headers are limited to 16 KiB. A `Location`
without `Status` produces a 302 client redirect; internal CGI redirects and NPH
scripts are not implemented. Script-supplied `Content-Length` is discarded and
hop-by-hop headers are rejected. The HTTP writer must supply framing and handle
partial socket writes. Connecting jobs to HTTP routing and connection lifecycle
management remains a separate server integration step.

Run the standalone CGI regression checks with a JDK 11+ and POSIX shell utilities:

```bash
bash test-cgi.sh
```

The checks exercise environment variables, binary input/output, process failures,
timeouts, stdout/stderr limits, cancellation, cleanup, and response parsing without
starting the HTTP server.
