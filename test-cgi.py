#!/usr/bin/env python3
"""Exercise asynchronous CGI on localhost, in an isolated temporary directory."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parent
checks = 0

def check(condition, message):
    global checks
    checks += 1
    assert condition, message

def until(condition, timeout=3):
    deadline = time.monotonic() + timeout
    while not condition():
        if time.monotonic() >= deadline:
            raise AssertionError("Condition timed out")
        time.sleep(0.02)

def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False

with tempfile.TemporaryDirectory(prefix="cgi-test-") as directory:
    work = Path(directory)
    site = work / "public"
    site.mkdir()
    (site / "index.html").write_text("Static page")
    (work / "jobs").mkdir()
    (work / "classes").mkdir()
    (site / "script.py").write_text('''import os, sys, time
from pathlib import Path
mode = os.environ.get("QUERY_STRING", "")
Path(mode + ".pid").write_text(str(os.getpid()))
if mode == "slow": time.sleep(1.5)
if mode == "half": time.sleep(0.2)
if mode == "timeout" or mode.startswith("busy") or mode == "reset": time.sleep(30)
if mode == "fail": sys.exit(3)
if mode == "bad":
    print("invalid CGI headers\\n")
    sys.exit()
if mode == "stderr": sys.stderr.write("x" * (17 * 1024 * 1024))
if mode == "redirect":
    sys.stdout.write("Location: /\\n\\n")
    sys.exit()
if mode == "empty":
    sys.stdout.write("Status: 204 No Content\\n\\n")
    sys.exit()
if mode == "echo":
    data = sys.stdin.buffer.read()
    sys.stdout.buffer.write(b"Status: 201 Created\\r\\nContent-Type: application/octet-stream\\r\\n"
        b"Content-Length: 1\\r\\nSet-Cookie: a=1\\r\\nSet-Cookie: b=2\\r\\n\\r\\n" + data)
    sys.exit()
if mode == "env":
    import json
    print("Content-Type: application/json\\n")
    print(json.dumps({"method":os.environ.get("REQUEST_METHOD"), "cookie":os.environ.get("HTTP_COOKIE"),
        "type":os.environ.get("CONTENT_TYPE"), "length":os.environ.get("CONTENT_LENGTH"),
        "path":os.environ.get("PATH_INFO"), "script":sys.argv[0], "cwd":os.getcwd(),
        "input":sys.stdin.buffer.read().hex()}))
    sys.exit()
sys.stdout.write("Content-Type: text/plain\\n\\n")
sys.stdout.write("x" * (17 * 1024 * 1024) if mode == "limit" else "done")
''')
    subprocess.run(["javac", "-d", str(work / "classes"),
                    *map(str, (repo / "src").rglob("*.java"))], check=True)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    (work / "config.json").write_text(json.dumps({"host":"127.0.0.1", "ports":[port],
        "routes":{"/":{"root":"./public", "accepted_methods":["GET", "POST"]}}}))

    def connect(mode=None):
        client = socket.create_connection(("127.0.0.1", port), timeout=8)
        if mode is not None:
            client.sendall(f"GET /script.py?{mode} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
        return client

    def receive(client):
        data = bytearray()
        while chunk := client.recv(65536):
            data.extend(chunk)
        headers, body = bytes(data).split(b"\r\n\r\n", 1)
        status = int(headers.split(b" ", 2)[1])
        if status != 204:
            check(b"Content-Length: " + str(len(body)).encode() + b"\r\n" in headers + b"\r\n", "Wrong content length")
        return status, headers, body

    def request(mode):
        with connect(mode) as client:
            return receive(client)

    def static():
        with connect() as client:
            client.sendall(b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            check(receive(client)[2] == b"Static page", "Static request failed")

    def clean():
        until(lambda: not list((work / "jobs").iterdir()))
        check(True, "Temporary files cleaned")

    def reset(client):
        client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        client.close()

    with (work / "server.log").open("w+") as log:
        server = subprocess.Popen(["java", f"-Djava.io.tmpdir={work / 'jobs'}", "-cp", str(work / "classes"), "Main"],
                                  cwd=work, stdout=log, stderr=log)
        clients = []
        try:
            for _ in range(100):
                check(server.poll() is None, "Server exited during startup")
                try:
                    with connect(): break
                except OSError: time.sleep(0.03)
            else: raise AssertionError("Server did not start")
            with connect("slow") as slow:
                until(lambda: (site / "slow.pid").exists())
                begin = time.monotonic()
                static()
                check(time.monotonic() - begin < 0.75, "Slow CGI blocked static request")
                check(receive(slow)[2] == b"done", "Slow CGI failed")
            clean()
            payload = bytes(range(256)) * 32
            with connect() as client:
                client.sendall(b"POST /script.py?echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                               + str(len(payload)).encode() + b"\r\n\r\n" + payload)
                status, headers, body = receive(client)
                check(status == 201 and body == payload, "CGI binary response changed")
                check(headers.count(b"Set-Cookie:") == 3 and b"Set-Cookie: a=1\r\n" in headers
                      and b"Set-Cookie: b=2\r\n" in headers, "Repeated CGI/session cookies lost")
                check(b"\r\nStatus:" not in headers, "CGI Status leaked as a header")
            clean()
            with connect() as client:
                client.sendall(b"POST /script.py?env HTTP/1.1\r\nHost: localhost\r\nCookie: test=ok\r\n"
                               b"Content-Type: application/octet-stream\r\nContent-Length: 2\r\n\r\n\x00\xff")
                env = json.loads(receive(client)[2])
                check(env["method"] == "POST" and env["cookie"] == "test=ok", "CGI environment lost")
                check(env["type"] == "application/octet-stream" and env["length"] == "2", "CGI body metadata wrong")
                check(env["input"] == "00ff" and env["path"] == env["script"] == str(site / "script.py"), "CGI paths/input wrong")
                check(env["cwd"] == str(site), "CGI working directory wrong")
            with connect("half") as client:
                client.shutdown(socket.SHUT_WR)
                check(receive(client)[2] == b"done", "Half-close lost CGI response")
            check(request("redirect")[0] == 302, "CGI redirect status wrong")
            check(request("empty")[0] == 204, "CGI empty status wrong")
            for mode in ("fail", "bad", "limit", "stderr", "timeout"):
                check(request(mode)[0] == 500, "Expected CGI failure: " + mode)
                clean()
                static()
                pid = int((site / (mode + ".pid")).read_text())
                until(lambda: not alive(pid))
            client = connect("reset")
            until(lambda: (site / "reset.pid").exists())
            pid = int((site / "reset.pid").read_text())
            reset(client)
            clean()
            until(lambda: not alive(pid))
            for i in range(16): clients.append(connect(f"busy{i}"))
            until(lambda: all((site / f"busy{i}.pid").exists() for i in range(16)))
            check(request("overflow")[0] == 503, "CGI capacity limit failed")
            check(not (site / "overflow.pid").exists(), "Rejected CGI was started")
            static()
            for client in clients: reset(client)
            clients.clear()
            clean()
            for i in range(16):
                pid = int((site / f"busy{i}.pid").read_text())
                until(lambda: not alive(pid))
            check(request("half")[0] == 200, "Capacity was not released")
            clean()
            print(f"PASS: {checks} CGI checks (concurrency, binary, failures, timeout, cleanup, half-close, reset, capacity)")
        except Exception:
            log.flush()
            log.seek(0)
            print(log.read())
            raise
        finally:
            for client in clients: client.close()
            # Also clean up children if an assertion fails before the server cancels them.
            for marker in site.glob("*.pid"):
                try: os.kill(int(marker.read_text()), 9)
                except ProcessLookupError: pass
            server.terminate()
            try: server.wait(timeout=3)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
