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
            assert status(prefix + b"Transfer-Encoding: chunked\r\n\r\n") == 501
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
                    assert response.endswith(b"\n\n" + payload.hex().encode() + b"\n")
            assert status(healthy) == 200
            print("PASS: fragmented headers/body, binary upload at limit, early EOF, body/header limits, CGI binary/empty input.")
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
