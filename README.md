# Java NIO Local HTTP Server (`java-localserver`)

A high-performance, lightweight, single-threaded, non-blocking HTTP/1.1 server built from scratch in **Java using Java NIO**. Designed to handle event-driven network I/O, robust routing, CGI script execution (Python/Perl), file uploads and deletions, cookie/session management, and comprehensive HTTP error handling.

---

## 🚀 Key Features

- **Non-Blocking Java NIO Architecture:** Uses `Selector`, `ServerSocketChannel`, and `SocketChannel` for efficient event-driven multiplexing on a single thread.
- **CGI Script Support:** Executes `.py` and `.pl` scripts through `ProcessBuilder`, supporting `GET` requests (query strings and environment variables) and `POST` requests (stdin body streaming), with binary- and text-safe output buffering.
- **HTTP/1.1 Method Support:** Supports `GET` (static files and CGI), `POST` (data uploads and CGI input), and `DELETE` (file removal workflows).
- **Session & Cookie Management:** Parses incoming `Cookie` headers and passes them to CGI/server routines, enabling stateful tracking and session persistence.
- **Connection Timeout Management:** Automatically cleans up and closes inactive client connections after a configurable timeout (60 seconds).
- **Security & Path Traversal Protection:** Prevents unauthorized path traversal attacks and safeguards root files from accidental overwrites.
- **Robust Error Handling:** Supports configurable custom error responses for HTTP status codes, including `400 Bad Request`, `403 Forbidden`, `404 Not Found`, `405 Method Not Allowed`, `413 Payload Too Large`, and `500 Internal Server Error`.
- **Interactive Web Dashboard:** Includes a web interface (`public/index.html`) for testing status codes, CGI scripts, file operations, and cookies in real time.

---

## 🛠️ Project Architecture

```text
java-localserver/
├── public/                 # Web root: HTML, static assets, and CGI scripts
│   ├── index.html          # Interactive testing dashboard
│   └── test.py             # Python CGI test handler
├── src/                    # Java source code
│   ├── utils               # Cookie and session utilities
│   │   ├── Coockie.java
│   │   └── Session.java 
│   ├── Server.java         # Main NIO event loop, connection handling, and routing
│   ├── Router.java         # URL path resolution, security validation, and method filtering
│   ├── CGIHandler.java     # CGI subprocess management and environment/stdin/stdout piping
│   ├── ConfigLoader.java   # JSON configuration parser
│   ├── error.java          # HTTP error response generator
│   └── Main.java           # Server entry point
├── config.json             # Server configuration
├── run.sh                  # Build and execution helper script
└── README.md               # Project documentation
```

### Core Components

- **`Server.java`:** Drives the main event loop using a Java NIO `Selector`. Handles accept/read events, manages keep-alive connections and timeouts, parses headers, and dispatches requests.
- **`Router.java`:** Normalizes URIs, checks supported HTTP methods, enforces security boundaries to prevent directory traversal and root-file tampering, and identifies CGI scripts.
- **`CGIHandler.java`:** Manages external script execution using `ProcessBuilder`. Configures CGI environment variables such as `REQUEST_METHOD`, `QUERY_STRING`, `CONTENT_LENGTH`, and `HTTP_COOKIE`. Streams POST data to the process's standard input and captures output through `ByteArrayOutputStream`.
- **`ConfigLoader.java`:** Loads server bindings, host addresses, maximum request body sizes, and error-page mappings from `config.json`.

---

## ⚙️ Configuration

The `config.json` file defines the host, ports, document root, and maximum request body size.

Example configuration:

```json
{
  "host": "127.0.0.1",
  "ports": [8080, 8081],
  "maxBodySize": 1048576,
  "root": "./public"
}
```

---

## 🚀 Getting Started

### Prerequisites

- Java Development Kit (JDK) 21 or higher
- Apache Maven, if required by your build setup
- Python 3, for running Python CGI tests

### Compilation and Execution

Make the provided shell script executable:

```bash
chmod +x run.sh
```

Run the server:

```bash
./run.sh
```

Alternatively, compile and run manually if your project does not require additional dependencies:

```bash
mkdir -p out
javac -d out src/*.java
java -cp out Main
```

Once the server has started, open your browser and navigate to:

**http://localhost:8080**

---

## 🧪 Testing and Audit Workflows

The built-in web dashboard provides interactive tests for the server's main features.

- **HTTP Status Responses:** Test `200 OK`, `400 Bad Request`, `403 Forbidden`, `404 Not Found`, `405 Method Not Allowed`, `413 Payload Too Large`, and `500 Internal Server Error`.
- **CGI Testing (GET and POST):** Execute `public/test.py` dynamically and verify environment variables, query-string parameters, and standard-input streaming.
- **Cookies and Sessions:** Verify `Set-Cookie` persistence and the `SESSION_ID` value returned by backend CGI scripts.
- **File Upload and Deletion:** Test file creation through POST uploads and resource cleanup through DELETE requests.

---

### ⚖️ Testing Payload Limits (413 Payload Too Large)

To verify that the server correctly enforces maximum body size limitations (configured in `config.json`), you can generate a test file larger than the threshold (e.g., a 2MB file when the limit is set to 1MB) and upload it using `curl` with `--data-binary`:

**Generate a test file (> 1MB)**:

  ```bash
   dd if=/dev/zero of=uploads/bigfile.txt bs=1M count=2
  ```

  ```bash 
   curl -X POST http://localhost:8080/uploads/bigfile.txt \
     --data-binary "@uploads/bigfile.txt" \
     -H "Content-Type: application/octet-stream"    
  ```