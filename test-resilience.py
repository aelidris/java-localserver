#!/usr/bin/env python3
"""Check bad requests and disconnected clients without changing project files."""
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time
from html.parser import HTMLParser
from urllib.parse import quote

repo = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix="localserver-test-") as directory:
    work = Path(directory)
    (work / "classes").mkdir()
    shutil.copytree(repo / "public", work / "public")
    shutil.copytree(repo / "error_pages", work / "error_pages")
    sources = sorted(str(path) for path in (repo / "src").rglob("*.java"))
    subprocess.run(["javac", "-d", str(work / "classes"), *sources], check=True)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    config = json.loads((repo / "config.json").read_text())
    config["host"], config["ports"] = "127.0.0.1", [port]
    config["client_max_body_size"] = 16384
    (work / "config.json").write_text(json.dumps(config))

    def connect():
        return socket.create_connection(("127.0.0.1", port), timeout=3)

    def receive(client):
        response = b""
        while True:
            try:
                chunk = client.recv(4096)
            except ConnectionResetError:
                break
            if not chunk:
                return response
            response += chunk
        return response

    def status(request):
        with connect() as client:
            client.sendall(request)
            response = receive(client)
        return int(response.split(b" ", 2)[1])

    healthy = b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
    with (work / "server.log").open("w+") as log:
        server = subprocess.Popen(
            ["java", "-cp", str(work / "classes"), "Main"],
            cwd=work, stdout=log, stderr=log,
        )
        try:
            for attempt in range(100):
                assert server.poll() is None, "Server exited during startup"
                try:
                    with connect():
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise AssertionError("Server did not start")

            bad_lines = [b"BAD_REQUEST", b"GET /", b"GET / HTTP/1.1 extra",
                         b"GET ? HTTP/1.1", b"GET /bad\x00path HTTP/1.1",
                         b"GET /bad\tpath HTTP/1.1", b"GET /#fragment HTTP/1.1"]
            requests = [line + b"\r\nHost: localhost\r\n\r\n" for line in bad_lines]
            for length in (b"abc", b"-1", b"+1", b"999999999999999999999",
                           b"1\r\nContent-Length: 1"):
                requests.append(b"POST /uploads/test HTTP/1.1\r\nHost: localhost\r\n"
                                b"Content-Length: " + length + b"\r\n\r\n")
            for request in requests:
                assert status(request) == 400, repr(request)
                assert status(healthy) == 200, "Server stopped after a bad request"
            assert not (work / "uploads/test").exists(), "Rejected POST wrote a file"

            # An empty query must not cause an indexing error in the CGI handler.
            assert status(b"GET /test.py? HTTP/1.1\r\nHost: localhost\r\n\r\n") == 200
            for _ in range(10):
                with connect() as client:
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    client.sendall(healthy)
                assert status(healthy) == 200, "Server stopped after a client reset"
            print("PASS: 12 invalid requests, 10 client resets, CGI empty query; server stays available.")

            # Split both the header delimiter and a binary body across socket writes.
            body = bytes(range(256)) * 64
            header = (b"POST /uploads/binary HTTP/1.1\r\nHost: localhost\r\n"
                      b"content-length: 16384\r\n\r\n")
            with connect() as client:
                for part in (header[:-3], header[-3:-1], header[-1:], body[:100], body[100:9000]):
                    client.sendall(part)
                    client.settimeout(0.05)
                    try:
                        client.recv(1)
                        raise AssertionError("Server responded before the body was complete")
                    except socket.timeout:
                        pass
                    assert not (work / "uploads/binary").exists()
                    assert status(healthy) == 200, "An incomplete request blocked other clients"
                client.settimeout(3)
                client.sendall(body[9000:])
                assert receive(client).startswith(b"HTTP/1.1 201 ")
            assert (work / "uploads/binary").read_bytes() == body

            # A disconnected upload must not overwrite an existing file.
            with connect() as client:
                client.sendall(header + b"incomplete")
                client.shutdown(socket.SHUT_WR)
                assert receive(client).startswith(b"HTTP/1.1 400 ")
            assert (work / "uploads/binary").read_bytes() == body

            prefix = b"POST /uploads/rejected HTTP/1.1\r\nHost: localhost\r\n"
            assert status(prefix + b"Content-Length: 16385\r\n\r\n") == 413
            assert status(prefix + b"Transfer-Encoding: gzip, chunked\r\n\r\n") == 501
            assert status(prefix + b"Transfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n") == 400
            assert status(prefix + b"X-Large: " + b"a" * 17000 + b"\r\n\r\n") == 400
            assert not (work / "uploads/rejected").exists()
            assert status(prefix.replace(b"rejected", b"empty") + b"Content-Length: 0\r\n\r\n") == 201
            assert (work / "uploads/empty").read_bytes() == b""

            # CGI input also keeps its original bytes and receives EOF on empty POST.
            (work / "public/echo.py").write_text(
                'import sys\nprint("Content-Type: text/plain\\n")\n'
                'print(sys.stdin.buffer.read().hex())\n'
            )
            for payload in (bytes(range(256)), b""):
                with connect() as client:
                    client.sendall(b"POST /echo.py HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                                   + str(len(payload)).encode() + b"\r\n\r\n" + payload)
                    response = receive(client)
                    assert response.startswith(b"HTTP/1.1 200 ")
                    assert response.split(b"\r\n\r\n", 1)[1] == payload.hex().encode() + b"\n"
            assert status(healthy) == 200
            print("PASS: fragmented headers/body, binary upload at limit, early EOF, body/header limits, CGI binary/empty input.")

            chunk_header = (b"POST /uploads/chunked HTTP/1.1\r\nHost: localhost\r\n"
                            b"Transfer-Encoding: chunked\r\n\r\n")
            with connect() as client:
                # A complete data chunk is not the end of the request.
                for part in (chunk_header, b"100;name=test\r", b"\n", bytes(range(256)), b"\r\n0\r\nX-Note: ignored\r\n"):
                    client.sendall(part)
                    client.settimeout(0.05)
                    try:
                        client.recv(1)
                        raise AssertionError("Chunked request completed too early")
                    except socket.timeout:
                        pass
                    assert not (work / "uploads/chunked").exists()
                    assert status(healthy) == 200
                client.settimeout(3)
                client.sendall(b"\r\n")
                assert receive(client).startswith(b"HTTP/1.1 201 ")
            assert (work / "uploads/chunked").read_bytes() == bytes(range(256))
            for malformed, expected in ((b"z\r\n", 400), (b"1\r\nx!", 400), (b"4001\r\n", 413)):
                assert status(chunk_header + malformed) == expected
                assert (work / "uploads/chunked").read_bytes() == bytes(range(256))
            with connect() as client:
                client.sendall(chunk_header + b"1\r\nx\r\n0\r\n")
                client.shutdown(socket.SHUT_WR)
                assert receive(client).startswith(b"HTTP/1.1 400 ")
            assert (work / "uploads/chunked").read_bytes() == bytes(range(256))
            with connect() as client:
                client.sendall(chunk_header.replace(b"/uploads/chunked", b"/echo.py")
                               + b"3\r\n\x00\xffA\r\n0\r\n\r\n")
                response = receive(client)
                assert response.startswith(b"HTTP/1.1 200 ")
                assert response.endswith(b"00ff41\n")
            assert status(healthy) == 200
            print("PASS: live chunked binary upload, trailers, incomplete/malformed/oversize rejection, CGI decoded input.")

            # Keep a large response pending while another client requests a page.
            large_body = bytes(range(256)) * 32768  # 8 MiB, larger than socket buffers.
            (work / "public/large.bin").write_bytes(large_body)
            (work / "error_pages/404.html").write_bytes(large_body)
            (work / "public/large.py").write_text(
                'import sys\nsys.stdout.write("Content-Type: text/plain\\r\\n\\r\\n" + "x" * 8388608)\n'
            )
            for path, expected_status, expected_body in (
                    (b"/large.bin", 200, large_body), (b"/missing-large", 404, large_body),
                    (b"/large.py", 200, b"x" * 8388608)):
                with connect() as client:
                    client.sendall(b"GET " + path + b" HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    response = bytearray(client.recv(1))
                    time.sleep(0.1)
                    assert status(healthy) == 200, "A slow reader blocked other clients"
                    while chunk := client.recv(65536):
                        response.extend(chunk)
                    headers, received_body = bytes(response).split(b"\r\n\r\n", 1)
                    assert int(headers.split(b" ", 2)[1]) == expected_status
                    assert received_body == expected_body, "Response truncated or changed: " + repr(path)
                    if path != b"/large.py":
                        assert b"Content-Length: 8388608" in headers
                        assert b"Connection: close" in headers
            with connect() as client:
                client.sendall(b"GET /large.bin HTTP/1.1\r\nHost: localhost\r\n\r\n")
                assert client.recv(1)
                client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            assert status(healthy) == 200
            print("PASS: complete 8 MiB static/error/CGI responses, concurrent requests during slow reads, reset during response.")

            # Restart with different roots and an index name to verify configuration is applied.
            server.terminate()
            server.wait(timeout=3)
            site = work / "configured site"
            site.mkdir()
            (site / "welcome.html").write_bytes(b"Configured home page")
            (site / "images").mkdir()
            config["routes"]["/"].update(root="./configured site", default_file="welcome.html", directory_listing=False)
            config["routes"]["/uploads"]["root"] = "./saved files"
            assets = work / "assets"
            assets.mkdir()
            (assets / "welcome.html").write_bytes(b"Assets index")
            (assets / "logo.txt").write_bytes(b"Parent route")
            (site / "images-old").write_bytes(b"Root fallback")
            icons = work / "icons"
            icons.mkdir()
            (icons / "logo.txt").write_bytes(b"Nested route")
            config["routes"]["/assets/"] = {"root": "./assets", "default_file": "welcome.html", "accepted_methods": ["GET"]}
            config["routes"]["/assets/icons"] = {"root": "./icons", "accepted_methods": ["GET"]}
            config["routes"]["/files"] = {"root": "./custom uploads", "accepted_methods": ["POST", "DELETE"]}
            (assets / "keep.txt").write_bytes(b"Keep this file")
            for code in (301, 302, 303, 307, 308):
                config["routes"][f"/move{code}"] = {
                    "root": "./assets", "accepted_methods": ["GET", "POST", "DELETE"],
                    "redirect": {"status": code, "location": "/assets/logo.txt"}}
            config["routes"]["/old"] = {"redirect": {"location": "/assets/"}}
            browse = work / "browse"
            browse.mkdir()
            names = ["plain.txt", "with space.txt", "مرحبا.txt", "a+b.txt", "100%.txt", "hash#.txt", "query?.txt", "<script>&.txt"]
            for name in names:
                (browse / name).write_bytes(b"Listed file\x00\xff")
            (browse / "folder name").mkdir()
            (browse / "folder name/nested.txt").write_bytes(b"Nested file")
            (browse / "outside-link").symlink_to(site, target_is_directory=True)
            config["routes"]["/browse"] = {"root": "./browse", "directory_listing": True}
            config["routes"]["/closed"] = {"root": "./browse", "directory_listing": False}
            (work / "config.json").write_text(json.dumps(config))
            server = subprocess.Popen(["java", "-cp", str(work / "classes"), "Main"],
                                      cwd=work, stdout=log, stderr=log)
            for attempt in range(100):
                assert server.poll() is None, "Server failed with custom roots"
                try:
                    with connect():
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise AssertionError("Server did not restart")
            with connect() as client:
                client.sendall(healthy)
                assert receive(client).endswith(b"\r\n\r\nConfigured home page")
            assert status(b"GET /images HTTP/1.1\r\nHost: localhost\r\n\r\n") == 403
            assert status(b"POST /uploads/config.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\ndata") == 201
            assert (work / "saved files/config.txt").read_bytes() == b"data"
            assert not (work / "uploads/config.txt").exists()
            print("PASS: configured roots, default file, directory access flag and upload destination.")
            for path, content in ((b"/assets", b"Assets index"), (b"/assets/", b"Assets index"),
                                  (b"/assets/logo.txt?x=1", b"Parent route"),
                                  (b"/assets/icons/logo.txt", b"Nested route"), (b"/images-old", b"Root fallback")):
                with connect() as client:
                    client.sendall(b"GET " + path + b" HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    response = receive(client)
                    assert response.startswith(b"HTTP/1.1 200 ")
                    assert response.endswith(b"\r\n\r\n" + content)
            assert status(b"POST /assets/logo.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n") == 405
            assert (assets / "logo.txt").read_bytes() == b"Parent route"
            assert status(b"POST /files/data.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\ndata") == 201
            assert (work / "custom uploads/data.txt").read_bytes() == b"data"
            assert status(b"GET /files/data.txt HTTP/1.1\r\nHost: localhost\r\n\r\n") == 405
            assert status(b"DELETE /files/data.txt HTTP/1.1\r\nHost: localhost\r\n\r\n") == 200
            assert not (work / "custom uploads/data.txt").exists()
            print("PASS: custom/nested routes, default indexes, query strings and route-specific upload/delete permissions.")
            for code in (301, 302, 303, 307, 308):
                for method in (b"GET", b"POST", b"DELETE"):
                    with connect() as client:
                        client.sendall(method + f" /move{code}/keep.txt?x=ignored HTTP/1.1\r\n".encode()
                                       + b"Host: localhost\r\nContent-Length: 0\r\n\r\n")
                        headers, body = receive(client).split(b"\r\n\r\n", 1)
                        assert int(headers.split(b" ", 2)[1]) == code
                        assert b"\r\nLocation: /assets/logo.txt\r\n" in headers
                        assert b"Content-Length: 0" in headers and body == b""
                    assert (assets / "keep.txt").read_bytes() == b"Keep this file"
            assert status(b"GET /old HTTP/1.1\r\nHost: localhost\r\n\r\n") == 302
            assert status(b"POST /old HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n") == 405
            followed = subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--max-redirs", "3",
                                       "--max-time", "5", f"http://127.0.0.1:{port}/old"], capture_output=True, check=True)
            assert followed.stdout == b"Assets index"
            print("PASS: redirect statuses, Location, empty body, method permissions, no file mutation and curl follow.")

            def get(path):
                with connect() as client:
                    client.sendall(b"GET " + path.encode("ascii") + b" HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    headers, body = receive(client).split(b"\r\n\r\n", 1)
                    assert headers.startswith(b"HTTP/1.1 200 ")
                    assert b"Content-Length: " + str(len(body)).encode() + b"\r\n" in headers + b"\r\n"
                    return body

            class Links(HTMLParser):
                def __init__(self):
                    super().__init__()
                    self.paths = []

                def handle_starttag(self, tag, attrs):
                    if tag == "a":
                        self.paths.append(dict(attrs)["href"])

            listing = get("/browse")
            assert listing == get("/browse/")
            assert b"&lt;script&gt;&amp;.txt" in listing and b"<script>" not in listing
            links = Links()
            links.feed(listing.decode("utf-8"))
            assert links.paths[0] == "/browse/folder%20name/"
            expected_links = {"/browse/" + quote(name) for name in names}
            assert set(links.paths) == expected_links | {"/browse/folder%20name/"}
            for link in expected_links:
                assert get(link) == b"Listed file\x00\xff"
            assert b'href="/browse/folder%20name/nested.txt"' in get(links.paths[0])
            assert get("/browse/folder%20name/nested.txt") == b"Nested file"
            assert status(b"GET /closed HTTP/1.1\r\nHost: localhost\r\n\r\n") == 403
            assert status(b"GET /browse/%2e%2e/config.json HTTP/1.1\r\nHost: localhost\r\n\r\n") == 403
            assert status(b"GET /browse/outside-link/welcome.html HTTP/1.1\r\nHost: localhost\r\n\r\n") == 403
            assert status(b"GET /browse/%ff HTTP/1.1\r\nHost: localhost\r\n\r\n") == 400
            (browse / "index.html").write_bytes(b"Index takes priority")
            assert get("/browse") == b"Index takes priority"
            assert get("/closed") == b"Index takes priority"
            print("PASS: directory listings, working encoded links, nested directories, HTML escaping, disabled listing and index priority.")
        except Exception:
            log.flush()
            log.seek(0)
            print(log.read())
            raise
        finally:
            server.terminate()
            try:
                server.wait(timeout=3)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
