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
  "client_max_body_size": 1048576,
  "error_pages": {"404": "error_pages/404.html"},
  "routes": {
    "/": {
      "root": "./public",
      "default_file": "index.html",
      "accepted_methods": ["GET"],
      "directory_listing": false
    },
    "/uploads": {
      "root": "./uploads",
      "accepted_methods": ["GET", "POST", "DELETE"],
      "directory_listing": false
    }
  }
}
```

Configuration is parsed as UTF-8 JSON using Java Core, with a 1 MiB file limit.
Invalid JSON, duplicate or unknown settings, incorrect types, duplicate ports,
and out-of-range values stop startup with a clear error and exit code 1.
Ports must be between 1 and 65535; the body limit is between 0 and 2147483639 bytes.
Paths are relative to the working directory used to start the server. Missing custom
error pages use the built-in error response.

Routes can use custom prefixes such as `/images` or `/api/files`.
The longest matching path prefix wins at a segment boundary: `/images/icons`
takes precedence over `/images`, while `/images-old` does not match `/images`.
A trailing slash in a configured prefix is removed; duplicate normalized prefixes
and paths containing `.` or `..` segments, repeated slashes, queries or fragments are rejected.

If `routes` is provided, it must contain `/`. Omitted route methods default to GET;
the default file is `index.html`, and directory listing defaults to false.
When the default file is absent, `directory_listing: true` returns an HTML listing;
`false` returns 403. An existing default file always takes priority.
CGI mappings and multiple server configurations are later steps.

For example, add this entry inside `routes` to serve `/images/logo.png` from
`./public/images/logo.png`:

```json
"/images": {
  "root": "./public/images",
  "accepted_methods": ["GET"],
  "default_file": "index.html",
  "directory_listing": false
}
```

Each route applies its allowed methods, including to CGI requests. POST uploads
and DELETE work on custom routes when explicitly enabled. Existing file roots
serve only the exact route path; directory roots use the remaining URL path.
GET checks the configured default file inside each requested directory.
Path checks reject traversal and symlinks leading outside the selected root.

To redirect a route, add a `redirect` object:

```json
"/old": {
  "accepted_methods": ["GET"],
  "redirect": {"status": 301, "location": "/new"}
}
```

Supported statuses are 301, 302 (the default), 303, 307 and 308. The location must
be a path starting with a single `/` or an absolute HTTP(S) URL. Spaces, control
characters and malformed URIs are rejected at startup; Unicode is percent-encoded.
The configured location is used as-is for the whole route, without appending the
original path suffix or query. Methods are checked first; an allowed redirect
returns an empty body and a `Location` header without accessing the route's files.
Use `bash test-redirects.sh` to test configuration, routing and partial writes for redirects.

To enable directory browsing, set `"directory_listing": true` on the desired route.
Listings show directories first, then files, sorted by name. Links use encoded UTF-8
paths, so spaces, Arabic text and characters such as `+`, `%`, `?` and `#` work.
Names are escaped for HTML; symlinks, special files, control characters and backslashes
are omitted. Empty directories return an empty list. `/folder` and `/folder/` both
use links starting from `/`, so links work with either URL.

Directory scans are limited to 10,000 entries and HTML output to 2 MiB; exceeding
either limit returns 500. `bash test-listing.sh` checks listing output, URL decoding,
path boundaries and these limits. HTTP tests also follow generated links.

Run `bash test-config.sh` for configuration checks, `bash test-routing.sh` for
routing checks, and `python3 test-resilience.py` for HTTP tests, including custom
and nested routes, roots, default files, uploads and deletes.

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
