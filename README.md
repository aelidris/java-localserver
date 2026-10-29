# Java NIO HTTP Server

A small HTTP server using Java Core, one application thread and one NIO `Selector`.
It handles GET, POST and DELETE, static files, Python CGI, uploads, cookies and sessions.
No server framework or asynchronous runtime is used.

## Run

Requires JDK 21+ and Python 3 for CGI. No Maven or external Java dependencies.

```bash
./run.sh
# Or use the example with two websites sharing a port:
./run.sh config-multi.json
```

Manual compilation:

```bash
mkdir -p out
javac -d out src/*.java src/utils/*.java
java -cp out Main config.json
```

Open http://localhost:8080. The test page exercises CGI, sessions and upload/delete.

## Configuration

The existing single-server format in `config.json` remains supported. To configure
independent websites, put their configurations in a top-level `servers` array:

```json
{
  "servers": [
    {
      "host": "127.0.0.1",
      "ports": [8080, 8081],
      "server_names": ["test.com", "localhost"],
      "default_server": true,
      "client_max_body_size": 1048576,
      "request_timeout_ms": 60000,
      "error_pages": {"404": "error_pages/404.html"},
      "routes": {
        "/": {
          "root": "./public",
          "default_file": "index.html",
          "accepted_methods": ["GET", "POST"],
          "directory_listing": false,
          "cgi": {".py": "python3"}
        },
        "/uploads": {
          "root": "./uploads",
          "accepted_methods": ["GET", "POST", "DELETE"],
          "directory_listing": true,
          "cgi": {}
        }
      }
    },
    {
      "host": "127.0.0.1",
      "ports": [8080],
      "server_names": ["other.com"],
      "routes": {"/": {"root": "./public/empty.html", "cgi": {}}}
    }
  ]
}
```

- `host` is the listening address; `server_names` are names matched against the
  request's `Host` header, ignoring case, a trailing dot and the optional port.
- Virtual hosts on the same listening address/port share one socket. An unknown
  hostname selects `default_server`, or the first valid server if none is explicit.
- Each server has its own routes, body limit, error pages and sessions.
- Duplicate ports inside a server, overlapping server names and multiple explicit
  defaults on one binding are configuration errors. Shared ports with distinct
  names are supported.
- In a `servers` array, invalid/conflicting entries are reported and skipped.
  A port that cannot bind is also skipped; other listeners continue. Startup fails
  when no valid configuration or listening socket remains. Invalid top-level JSON
  or unknown top-level settings fail startup.
- Defaults: host `127.0.0.1`, ports `[8080]`, body limit 1 MiB and timeout 60 seconds.
  Ports range from 1 to 65535; timeout ranges from 100 to 3600000 milliseconds.
- Paths are relative to the process working directory. Configuration is UTF-8 JSON,
  limited to 1 MiB. Missing custom error files fall back to built-in error pages.

Try the virtual hosts with:

```bash
curl --resolve test.com:8080:127.0.0.1 http://test.com:8080/
curl --resolve other.com:8080:127.0.0.1 http://other.com:8080/
```

## Routes and HTTP

The longest matching route prefix wins at a path-segment boundary. For example,
`/images/logo.png` maps to `logo.png` inside the root configured for `/images`.
A file root serves the exact route path. A directory root appends the remaining path.
Traversal and symlinks leading outside the route root are rejected.

Every routes object must include `/`. Defaults are GET, `./public`, `index.html`,
listing disabled, and Python CGI mapped by `.py`. Set `"cgi": {}` to disable CGI
on a route. Uploads send raw bytes with POST to the desired filename:

```bash
curl --data-binary @my-file.png http://localhost:8080/uploads/my-file.png
curl http://localhost:8080/uploads/my-file.png -o downloaded.png
curl -X DELETE http://localhost:8080/uploads/my-file.png
```

Fixed-length and chunked bodies are supported. The selected virtual host's body
limit is checked before accepting an oversized body. `Expect: 100-continue` is
supported; other expectations receive 417. Invalid/missing/duplicate `Host` headers
receive 400. Disallowed methods receive 405 with `Allow`.

Static files have MIME types and Content-Length. Responses include Date and
Connection: close. The server handles one request per connection. Built-in error
pages cover 400, 403, 404, 405, 413 and 500.

An existing `default_file` takes priority over directory listings. Listings escape
HTML and encode links; scans are limited to 10,000 entries and output to 2 MiB.

Redirect example inside `routes`:

```json
"/old": {"redirect": {"status": 301, "location": "/new"}}
```

Supported statuses: 301, 302 (default), 303, 307 and 308. Locations must be local
absolute paths or HTTP(S) URLs. The location is used as configured, without appending
the original suffix or query. Route method permissions still apply.

## CGI and sessions

`cgi` maps extensions to interpreter executables. ProcessBuilder receives the
interpreter and absolute script filename as separate arguments, without a shell.
The script's directory is its working directory. Environment includes REQUEST_METHOD,
QUERY_STRING, REQUEST_URI, CONTENT_TYPE, CONTENT_LENGTH, HTTP_COOKIE, PATH_INFO and
SCRIPT_FILENAME. PATH_INFO and SCRIPT_FILENAME contain the full script path.

CGI input/output use temporary files so process pipes cannot block the event loop.
Jobs are polled, limited to 16 concurrent scripts, 5 seconds and 16 MiB output per job.
Failures return 500; exhausted CGI capacity returns 503. Temporary files and running
children are cleaned up when jobs finish, fail, time out or clients reset.

The server creates random SESSION_ID cookies and stores per-session data, including
a visit counter. CGI receives SESSION_ID and SESSION_VISITS; `public/test.py` shows
persistence across requests. Session cookies use HttpOnly, SameSite=Lax, Path=/ and
a 30-minute maximum age. Sessions expire after 30 minutes of inactivity, are isolated
per virtual server, and the store evicts the least recently used entry at 10,000 sessions.

## Resource handling

Socket reads and writes happen only after readiness events, with at most one socket
read or write per client per select cycle. Partial writes keep their position.
Static files and custom error pages stream in 64 KiB buffers instead of loading the
whole file. Regular filesystem operations still run synchronously on the event thread.

There is a 256-client cap and a shared buffer budget of the smaller of 32 MiB or
one eighth of the maximum heap. Buffer pressure receives 503; connections beyond
the client cap are closed. Parser/body copies require additional temporary heap.
Both idle connections and requests exceeding the absolute request deadline are closed,
so sending a slow trickle cannot keep an unfinished request alive indefinitely.

## Tests

```bash
bash test.sh                 # All Java and localhost integration tests
python3 test-browser.py      # Optional: needs Firefox and geckodriver
python3 test-stress.py       # Needs Siege and jcmd (included with the JDK)
```

Use `SIEGE=/path/to/siege python3 test-stress.py` when Siege is not on PATH.
Tests create temporary configurations, isolated web roots and local servers; they
clean them up on completion. The stress test runs three 10-second rounds with
25 concurrent Siege clients, requires at least 99.5% availability and successful
HTTP 200 transactions, and checks post-GC heap stability and descriptor recovery.

Verified locally: all functional suites passed, Firefox loaded external CSS/JS and
preserved sessions, and all three Siege rounds achieved 100% availability. An 80 MiB
file streamed correctly with a 32 MiB JVM heap. Short stress runs are evidence for
these conditions, not a guarantee against every possible workload or memory leak.
