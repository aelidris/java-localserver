#!/usr/bin/env python3
"""Mandatory features: virtual hosts, isolation, sessions, headers, streaming and deadlines."""
import http.client
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parent
checks = 0


def check(condition, message):
    global checks
    checks += 1
    assert condition, message


with tempfile.TemporaryDirectory(prefix='requirements-') as directory:
    work = Path(directory)
    classes = work / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-d', str(classes), *map(str, (repo / 'src').rglob('*.java'))], check=True)
    held = []
    for _ in range(4):
        sock = socket.socket()
        sock.bind(('127.0.0.1', 0))
        held.append(sock)
    ports = [sock.getsockname()[1] for sock in held]
    # Keep the last port occupied to check listener isolation.
    for sock in held[:3]:
        sock.close()
    for name in ('alpha', 'beta', 'gamma'):
        root = work / name
        root.mkdir()
        (root / 'index.html').write_text(name)
    root = work / 'alpha'
    (root / 'style.css').write_text('body { color: red; }')
    (root / 'app.js').write_text('console.log("ok")')
    (root / 'logo.png').write_bytes(b'\x89PNG\r\n\x1a\n')
    (root / 'custom.html').write_text('alpha custom missing page')
    (root / 'echo.cgi').write_text('''import os, sys, json
from pathlib import Path
body = sys.stdin.buffer.read()
print('Content-Type: application/json\\n')
print(json.dumps({'id':os.environ['SESSION_ID'], 'visits':int(os.environ['SESSION_VISITS']),
    'body':body.hex(), 'path':os.environ['PATH_INFO'], 'cwd':str(Path.cwd())}))
''')
    # A sparse file larger than the entire JVM heap must still be served correctly.
    large_size = 80 * 1024 * 1024
    with (root / 'large.bin').open('wb') as output:
        output.truncate(large_size)
    base = {'ports':[ports[0], ports[3]], 'server_names':['alpha.test'], 'client_max_body_size':32,
            'error_pages':{'404':str(root / 'custom.html')},
            'routes':{'/':{'root':str(root), 'cgi':{'.cgi':'python3'}, 'accepted_methods':['GET', 'POST']},
                      '/uploads':{'root':str(work / 'uploads'), 'accepted_methods':['GET', 'POST', 'DELETE'], 'cgi':{}}}}
    beta = {'ports':[ports[0]], 'server_names':['beta.test'], 'default_server':True,
            'client_max_body_size':128, 'routes':{'/':{'root':str(work / 'beta')}}}
    gamma = {'ports':[ports[1]], 'server_names':['gamma.test'], 'routes':{'/':{'root':str(work / 'gamma')}}}
    fast = {'ports':[ports[2]], 'request_timeout_ms':500, 'routes':{'/':{'root':str(work / 'gamma')}}}
    config = {'servers':[base, {'ports':[0]}, beta, gamma, fast,
                           {'ports':[ports[0]], 'server_names':['alpha.test']}]}
    (work / 'config.json').write_text(json.dumps(config))
    log = (work / 'server.log').open('w+')
    server = subprocess.Popen(['java', '-Xmx32m', '-cp', str(classes), 'Main'], cwd=work, stdout=log, stderr=log)

    def request(path='/', host='alpha.test', method='GET', body=None, headers=None, port=ports[0]):
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=8)
        try:
            connection.request(method, path, body=body, headers={'Host':host, **(headers or {})})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def raw(wire):
        with socket.create_connection(('127.0.0.1', ports[0]), timeout=3) as client:
            client.sendall(wire)
            response = b''
            while True:
                part = client.recv(65536)
                if not part:
                    return response
                response += part

    try:
        for _ in range(100):
            check(server.poll() is None, 'Server exited during startup')
            try:
                request()
                break
            except OSError:
                time.sleep(.03)
        else:
            raise AssertionError('Startup timed out')
        check(request()[2] == b'alpha', 'Alpha hostname selected wrong site')
        check(request(host='beta.test')[2] == b'beta', 'Beta hostname selected wrong site')
        check(request(host='ALPHA.TEST.:' + str(ports[0]))[2] == b'alpha', 'Host case/port/trailing dot failed')
        check(request(host='unknown.test')[2] == b'beta', 'Explicit default server ignored')
        check(request(host='gamma.test', port=ports[1])[2] == b'gamma', 'Independent port config ignored')
        check(request('/missing')[2] == b'alpha custom missing page', 'Host-specific error page ignored')
        check(request('/missing', host='beta.test')[0] == 404, 'Default error page failed')
        for host in (b'', b'bad host', b'evil@alpha.test', b'alpha.test:0', b'alpha.test:65536', b'alpha.test/'):
            response = raw(b'GET / HTTP/1.1\r\nHost: ' + host + b'\r\n\r\n')
            check(response.startswith(b'HTTP/1.1 400 '), 'Invalid Host accepted: ' + repr(host))
        for headers in (b'', b'Host: alpha.test\r\nHost: beta.test\r\n'):
            check(raw(b'GET / HTTP/1.1\r\n' + headers + b'\r\n').startswith(b'HTTP/1.1 400 '), 'Missing/duplicate Host accepted')
        for path, mime in (('/style.css','text/css'), ('/app.js','text/javascript'), ('/logo.png','image/png')):
            status, headers, _ = request(path)
            check(status == 200 and headers['Content-Type'].startswith(mime), 'Wrong MIME for ' + path)
            check('Date' in headers and int(headers['Content-Length']) > 0, 'Missing response headers')
        status, headers, _ = request(method='DELETE')
        check(status == 405 and headers['Allow'] == 'GET, POST', '405 Allow missing/wrong')
        check(request('/uploads/too-big', method='POST', body=b'x'*33)[0] == 413, 'Selected host body limit ignored')
        wire = b'POST /uploads/too-big HTTP/1.1\r\nHost: alpha.test\r\nTransfer-Encoding: chunked\r\n\r\n21\r\n'
        check(raw(wire).startswith(b'HTTP/1.1 413 '), 'Chunked host body limit ignored')
        expect = b'POST /uploads/expect HTTP/1.1\r\nHost: alpha.test\r\nExpect: 100-continue\r\n'
        check(raw(expect + b'Content-Length: 33\r\n\r\n').startswith(b'HTTP/1.1 413 '), 'Expected body bypassed size limit')
        with socket.create_connection(('127.0.0.1', ports[0]), timeout=3) as client:
            client.sendall(expect + b'Content-Length: 3\r\n\r\n')
            interim = b''
            while not interim.endswith(b'\r\n\r\n'):
                interim += client.recv(1024)
            check(interim == b'HTTP/1.1 100 Continue\r\n\r\n', '100 Continue not sent through event loop')
            client.sendall(b'abc')
            final = b''
            while part := client.recv(4096):
                final += part
            check(final.startswith(b'HTTP/1.1 201 '), 'Upload after 100 Continue failed')
        check(request('/uploads/expect')[2] == b'abc', 'Expected upload corrupted')
        # Beta accepts 33 bytes before applying its GET-only route permission.
        check(request(method='POST', host='beta.test', body=b'x'*33)[0] == 405, 'Used another host body limit')
        check(request('/uploads/data', method='POST', body=b'\x00\xffdata')[0] == 201, 'Upload failed')
        check(request('/uploads/data')[2] == b'\x00\xffdata', 'Uploaded binary corrupted')
        check(request('/uploads/data', method='DELETE')[0] == 200, 'Delete failed')
        status, headers, body = request('/echo.cgi')
        first = json.loads(body)
        cookie = headers['Set-Cookie'].split(';', 1)[0]
        check(status == 200 and first['visits'] == 1 and first['id'] in cookie, 'Session not created')
        check('HttpOnly' in headers['Set-Cookie'] and 'Max-Age=1800' in headers['Set-Cookie'], 'Session cookie attributes missing')
        second = json.loads(request('/echo.cgi', headers={'Cookie':cookie})[2])
        check(first['id'] == second['id'] and second['visits'] == 2, 'Session did not persist')
        forged = json.loads(request('/echo.cgi', headers={'Cookie':'SESSION_ID=forged'})[2])
        check(forged['id'] != 'forged', 'Accepted a forged session')
        other = request(host='beta.test', headers={'Cookie':cookie})[1]['Set-Cookie']
        check(first['id'] not in other, 'Session leaked across virtual hosts')
        payload = bytes(range(16))
        fixed = json.loads(request('/echo.cgi', method='POST', body=payload)[2])
        check(fixed['body'] == payload.hex() and fixed['path'] == str(root / 'echo.cgi')
              and fixed['cwd'] == str(root), 'Configured CGI body/path/cwd failed')
        chunked = raw(b'POST /echo.cgi HTTP/1.1\r\nHost: alpha.test\r\nTransfer-Encoding: chunked\r\n\r\n'
                      b'3\r\nabc\r\n0\r\n\r\n').split(b'\r\n\r\n',1)[1]
        check(json.loads(chunked)['body'] == b'abc'.hex(), 'Configured CGI chunked input failed')
        before = len(list(Path(f'/proc/{server.pid}/fd').iterdir()))
        # Stream and verify the 80 MiB response without storing it in the test process.
        connection = http.client.HTTPConnection('127.0.0.1', ports[0], timeout=8)
        connection.request('GET', '/large.bin', headers={'Host':'alpha.test'})
        response = connection.getresponse()
        total = 0
        while chunk := response.read(65536):
            check(not any(chunk), 'Large binary corrupted')
            total += len(chunk)
        connection.close()
        check(total == large_size and response.status == 200, 'Large file streaming failed under 32 MiB heap')
        for _ in range(100):
            request()
        time.sleep(.2)
        after = len(list(Path(f'/proc/{server.pid}/fd').iterdir()))
        check(after == before, f'File/socket descriptors leaked: {before} -> {after}')
        with socket.create_connection(('127.0.0.1', ports[2]), timeout=3) as client:
            client.sendall(b'GET / HTTP/1.1\r\nHost: localhost\r\nX-Slow: ')
            started = time.monotonic()
            closed = False
            for _ in range(10):
                time.sleep(.12)
                try:
                    client.sendall(b'x')
                    client.settimeout(.01)
                    if client.recv(1) == b'':
                        closed = True
                        break
                except (BrokenPipeError, ConnectionResetError):
                    closed = True
                    break
                except socket.timeout:
                    pass
            check(closed and time.monotonic()-started < 1.2, 'Slow trickle bypassed absolute deadline')
        check(request()[2] == b'alpha', 'Server unhealthy after deadline/error tests')
        log.flush()
        log.seek(0)
        logs = log.read()
        check('Skipping server 2:' in logs and 'Skipping server 6:' in logs, 'Invalid/conflicting config not reported')
        check('Skipping listener' in logs and 'Address already in use' in logs, 'Occupied port not isolated/reported')
        print(f'PASS: {checks} requirement checks; virtual hosts, config isolation, sessions, headers, CGI, '
              '80 MiB streaming under 32 MiB heap, descriptor cleanup and slow-request deadline.')
    except Exception:
        log.flush()
        log.seek(0)
        print(log.read())
        raise
    finally:
        server.terminate()
        server.wait(timeout=5)
        log.close()
        held[3].close()
