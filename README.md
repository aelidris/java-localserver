# Java Local Server (`java-localserver`)

A lightweight, custom HTTP/1.1-compliant web server built from scratch in Java using non-blocking I/O (`java.nio`) and event-driven architecture. Designed to handle internal APIs, static content, and dynamic CGI scripts with zero external server frameworks or asynchronous runtimes (e.g., Netty, Jetty).

## 🚀 Key Features

- **Event-Driven Non-Blocking I/O:** Powered by Java NIO (`Selectors`, `Channels`) operating on a single-process, single-thread model to handle high concurrency efficiently.
- **HTTP/1.1 Protocol Compliance:** Full support for `GET`, `POST`, and `DELETE` methods, chunked/unchunked request management, and robust status code handling.
- **Dynamic Content via CGI:** Executes external scripts (e.g., Python) using `ProcessBuilder` with environment variable mapping (`PATH_INFO`).
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