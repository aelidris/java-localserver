# Java Local Server (`java-localserver`)

A lightweight, custom HTTP/1.1-compliant web server built from scratch in Java using non-blocking I/O (`java.nio`) and event-driven architecture. Designed to handle internal APIs, static content, and dynamic CGI scripts with zero external server frameworks or asynchronous runtimes (e.g., Netty, Jetty).

## 🚀 Key Features

- **Event-Driven Non-Blocking I/O:** Powered by Java NIO (`Selectors`, `Channels`) with one server event-loop thread. CGI programs run in child processes through `ProcessBuilder`, without application worker threads.
- **HTTP Request Handling:** Supports `GET`, `POST`, and `DELETE`, with incremental header/body reads for `Content-Length` and chunked requests. Full HTTP/1.1 compliance remains in progress.
- **CGI Handler:** Configured file extensions execute through `ProcessBuilder`, with request environment mapping, binary stdin/stdout, response headers, and polling from the server event loop.
- **Configuration Engine:** Validated JSON configuration for host/ports, body limits, error page paths, and custom routes with per-route roots, methods, redirects, and directory listings.
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

## Configuration validation

Startup reads `config.json` from the working directory and validates it before
opening any listening sockets. Missing/unreadable files, invalid UTF-8 or JSON,
duplicate keys, unknown settings, and invalid field values stop startup with a
field-specific message and exit status 1. Files are limited to 1 MiB and JSON
nesting to 32 levels. Parsing uses Java Core only.

- `host`: a non-empty, resolvable IP address or hostname; defaults to `127.0.0.1`.
- `ports`: a non-empty list of distinct integers from 1 to 65535; defaults to `[8080]`.
- `client_max_body_size`: an integer from 0 to 2147483639; defaults to 1048576.
  The upper bound reflects the current in-memory body representation, not a
  guarantee that a given JVM has enough memory for that size.
- `error_pages`: an object mapping codes 400–599 to non-empty filesystem paths.
  Missing or unreadable error page files still use the built-in HTML fallback.
- `routes`: accepts custom path prefixes and must include `/` when supplied.
  Each route requires a non-empty `accepted_methods` list containing distinct
  `GET`, `POST`, or `DELETE` values. `root`, `default_file`, and the boolean
  `directory_listing` are read from the file. Default files must stay relative
  to the route root. Prefixes start with `/` and cannot contain query strings,
  fragments, percent escapes, whitespace, empty segments, or `.`/`..` segments.
  A trailing slash is removed; `/api` and `/api/` cannot both be configured.
  Unsupported options are rejected.

With `routes` omitted, `/` serves `./public/index.html` with `GET` allowed.
For declared routes, an omitted root defaults to `./public` (`./uploads` for the
existing `/uploads` route), and `default_file` defaults to `index.html`. The
listing flag defaults to false except for `/uploads`.
Filesystem paths remain relative to the working directory. Upload directories
may be absent at startup and are created on upload. Configuration is applied
only after all fields pass validation.

Run the configuration and startup-failure checks:

```bash
bash test-config.sh
```

## Custom routes

The longest matching prefix wins at a path-segment boundary: `/assets/private`
takes precedence over `/assets`, while `/assets-old` falls back to `/`. Query
strings do not affect matching. The matched prefix is stripped before resolving
the remainder under that route's root. Matching is case-sensitive. Request paths
are percent-decoded once as strict UTF-8 before matching; `+` remains a literal
plus, not a space. Invalid escapes/UTF-8 return 400, and decoded `.`/`..` segments
return 403. Query strings are separated before decoding, so `%3F` and `%23` can
refer to filenames containing `?` and `#`.

For example, use this `routes` object to serve static content and allow uploads
under a separate prefix:

```json
{
  "routes": {
    "/": { "root": "./public", "accepted_methods": ["GET"] },
    "/assets": { "root": "./static", "accepted_methods": ["GET"] },
    "/assets/private": { "root": "./private", "accepted_methods": ["GET"] },
    "/files": { "root": "./storage", "accepted_methods": ["GET", "POST", "DELETE"] }
  }
}
```

`GET /assets/logo.png` resolves to `./static/logo.png`. Each selected route's
`accepted_methods` controls access, including POST and DELETE; a denied method
does not fall back to a less specific route. POST is no longer restricted to
the name `/uploads`. For read-only content, configure `accepted_methods: ["GET"]`.

GET directory requests use `default_file`, including nested directories. An
existing file can also be a route root, served only at the mount itself (with
an optional trailing slash). POST and DELETE cannot target directories. Dot
segments and resolved symlinks leading outside a directory root are rejected.

Run the routing regression checks:

```bash
bash test-routing.sh
```

## Directory listings

For GET requests targeting a directory, a regular `default_file` takes priority.
When no default file is found, `directory_listing: true` produces a 200 HTML
index; false returns 403. An empty directory also returns a valid HTML page.
The existing `/uploads` route in `config.json` already enables listings.

The index sorts directories first, then names in case-sensitive order. Names
and titles are HTML-escaped, and root-relative links percent-encode UTF-8 names,
including spaces, plus signs, percent signs, and Arabic text. Links work with or
without a trailing slash on the requested directory and retain custom route
prefixes. The HTML shows the URL path, not the server's filesystem path.

Only ordinary files and directories are listed. Symlinks, special files, and
names containing control characters or backslashes are omitted. Directory
streams close after enumeration. Listings are bounded to 10,000 scanned entries
and 2 MiB of generated UTF-8 HTML; exceeding either limit returns 500 instead of
a partial index. Responses use the same non-blocking writer as other pages.

Run the directory listing and generated-link checks:

```bash
bash test-listing.sh
```

## Route redirects

Add a `redirect` object to a route, for example this entry inside `routes`:

```json
{
  "/old": {
    "accepted_methods": ["GET"],
    "redirect": { "status": 301, "location": "/new" }
  }
}
```

Supported statuses are 301, 302, 303, 307, and 308; omitted `status` defaults to
302. `location` is required and accepts a path starting with a single `/` or an
absolute HTTP(S) URL with a valid host. URI query strings and fragments are
allowed. Unicode path characters are encoded for the ASCII `Location` header;
existing percent escapes are preserved. Invalid URIs, raw whitespace/control
characters, and unsupported status codes fail configuration validation.

The longest matching route and its allowed methods are checked first. A redirect
then returns the configured status, `Location`, and `Content-Length: 0` through
the normal non-blocking writer. It does not read, upload, or delete a file, and
does not require the route root to exist. A more specific non-redirect route can
still serve files beneath a redirect prefix. The destination is fixed: neither
the unmatched path suffix nor the incoming query is appended. For example,
`/old/child?x=1` also redirects to `/new` with the configuration above.

Run the redirect configuration, routing, and response checks:

```bash
bash test-redirects.sh
```

## Request receiving

Each connection accumulates its own request across socket reads. Routing and
upload writes happen only after the headers and declared body have arrived.
Raw upload bodies stay as bytes, preserving binary files; multipart form parsing
is not implemented. Headers are limited to 16 KiB, including the request line and
final blank line. Bodies are buffered in memory up to `client_max_body_size`.

Invalid or repeated `Content-Length` headers receive 400; declared bodies above
the configured limit receive 413 without waiting for the body. Requests with
neither `Content-Length` nor `Transfer-Encoding` have an empty body.

`Transfer-Encoding: chunked` is decoded incrementally, even when chunk sizes,
binary data, or CRLF separators arrive across separate reads. The body limit
applies to the sum of decoded bytes; a chunk that would exceed it receives 413
before its data is read. Completion requires the zero-sized chunk and the final
empty trailer line, as described in [RFC 9112 section 7.1](https://www.rfc-editor.org/rfc/rfc9112.html#section-7.1).
Valid chunk extensions and trailers are parsed and discarded. Size/trailer lines
are limited to 8 KiB excluding CRLF; total extensions and the complete trailer
section each have a separate 16 KiB limit.

Malformed framing, repeated `Transfer-Encoding`, a request combining
`Transfer-Encoding` with `Content-Length`, and framing fields in trailers receive
400. Only a single `chunked` transfer coding is supported: additional codings such
as `gzip, chunked` receive 501; a coding list not ending in `chunked` receives 400.

Connections close after one complete response. Files, text responses, and error pages retain unsent bytes and
resume on `OP_WRITE` events, including when a socket temporarily accepts zero
bytes. Write failures close only the affected connection. Responses are still
buffered in memory; streaming files from disk remains a future improvement.

Run the Content-Length and chunked request regression checks (JDK 11+):

```bash
bash test-requests.sh
```

Run the response writer regression checks, including short/zero writes, binary
bodies, UTF-8 byte lengths, and custom/fallback error pages:

```bash
bash test-responses.sh
```

## CGI routes

The example configuration enables `/cgi` using the trusted `./cgi` directory:

```json
{
  "/cgi": {
    "accepted_methods": ["GET", "POST"],
    "root": "./cgi",
    "default_file": "hello.py",
    "cgi": { ".py": "python3" }
  }
}
```

With Python 3 available and the server running, try:

```bash
curl 'http://127.0.0.1:8080/cgi/hello.py/extra?name=world'
curl --data-binary 'hello' 'http://127.0.0.1:8080/cgi/hello.py'
```

CGI mappings are case-sensitive extensions to a single executable name or path;
shell commands and argument arrays are not accepted. Relative executable paths
are resolved from the server's working directory. The resolved script is the
interpreter's first argument, and its directory becomes the process working
directory. Directory default files and file roots can also invoke CGI.

The server waits for the complete request body, including decoded chunked input,
and checks method permissions and script containment before starting a job.
`REQUEST_METHOD`, raw `QUERY_STRING`, `CONTENT_TYPE`, actual `CONTENT_LENGTH`,
`SERVER_NAME`, `SERVER_PORT`, `SERVER_PROTOCOL`, `REMOTE_ADDR`, and `REMOTE_PORT`
are supplied. `Host`, `Cookie`, `Accept`, and `User-Agent` are forwarded through
their `HTTP_*` variables; arbitrary request headers do not become environment
variables. Repeated request cookies are joined with `; `.

`SCRIPT_FILENAME` is the absolute resolved file, `SCRIPT_NAME` its URL path, and
`PATH_INFO` the decoded suffix after the script. `PATH_TRANSLATED` maps that
suffix through the configured route roots and is empty when `PATH_INFO` is empty.
These distinctions follow the [CGI 1.1 variables](https://www.rfc-editor.org/rfc/rfc3875.html#section-4.1.5).
For `/cgi/hello.py/extra`, `PATH_INFO` is `/extra`, not the script's filesystem path.

CGI-enabled roots are trusted program directories: POST/DELETE on those routes
dispatch matching scripts, and do not upload or delete ordinary files. Other
non-CGI routes cannot read or mutate paths inside a configured CGI root, including
through resolved symlinks. Ordinary upload routes do not execute files just
because they have a `.py` extension. Keep untrusted uploads outside CGI roots.

Up to 16 CGI jobs run concurrently; additional CGI requests receive 503 while
static requests remain available. Jobs are polled at most 50 ms apart while the
event loop is otherwise idle, enforcing the handler's five-second timeout and
2 MiB combined stdout/stderr limit. Execution, parsing, or resource-limit failures
receive 500. TCP resets cancel jobs immediately when observed; a read-side FIN
may be a valid half-close, so that job can finish and send its response, or time
out. Completion, cancellation, and errors clean up CGI temporary files.

The HTTP writer forwards parsed CGI status and headers (including repeated
`Set-Cookie`), preserves binary bodies, and supplies its own response framing.
Scripts' `Content-Length` values are discarded, and 204/304 responses omit body
framing. Socket writes remain incremental.

Run routing/response checks and the localhost integration suite (JDK 11+, Python
3, and permission to bind a local socket):

```bash
bash test-cgi-routing.sh
bash test-cgi-server.sh
```

The integration suite tests environment variables, binary/chunked POST, relative
files, concurrent static requests, timeouts, output limits, connection reset and
half-close, process capacity, cleanup, and script-root protection.

## Standalone CGI handler usage

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
hop-by-hop headers are rejected. `Server` integrates polling and connection
cleanup, and `HttpResponseWriter.fromCgi` supplies framing and partial writes.

Run the standalone CGI regression checks with a JDK 11+ and POSIX shell utilities:

```bash
bash test-cgi.sh
```

The checks exercise environment variables, binary input/output, process failures,
timeouts, stdout/stderr limits, cancellation, cleanup, and response parsing without
starting the HTTP server.
