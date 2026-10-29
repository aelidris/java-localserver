#!/usr/bin/env python3
"""Optional Firefox/WebDriver check; needs firefox and geckodriver on PATH."""
import base64
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

repo = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='browser-') as directory:
    work = Path(directory)
    (work / 'classes').mkdir()
    shutil.copytree(repo / 'public', work / 'public')
    site = work / 'public'
    (site / 'browser.html').write_text('<link rel="stylesheet" href="/browser.css"><p id="result">loading</p>'
                                     '<script src="/browser.js"></script>')
    (site / 'browser.css').write_text('#result { color: rgb(0, 128, 0); }')
    (site / 'browser.js').write_text('document.title = "JS loaded"; document.getElementById("result").textContent = "ready";')
    subprocess.run(['javac', '-d', str(work / 'classes'), *map(str, (repo / 'src').rglob('*.java'))], check=True)
    ports = []
    for _ in range(2):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            ports.append(sock.getsockname()[1])
    (work / 'config.json').write_text(json.dumps({'ports':[ports[0]]}))

    def driver(path, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(f'http://127.0.0.1:{ports[1]}' + path, data=data,
                                         headers={'Content-Type':'application/json'}, method=method)
        with urllib.request.urlopen(request, timeout=45) as response:
            value = json.load(response)['value']
            assert not isinstance(value, dict) or 'error' not in value, value
            return value

    with (work / 'server.log').open('w+') as log, (work / 'browser.log').open('w+') as browser_log:
        server = subprocess.Popen(['java', '-cp', str(work / 'classes'), 'Main'], cwd=work, stdout=log, stderr=log)
        gecko = subprocess.Popen(['geckodriver', '--port', str(ports[1]), '--log', 'error'],
                                 stdout=browser_log, stderr=browser_log)
        session = None
        try:
            for _ in range(100):
                try:
                    driver('/status')
                    break
                except OSError:
                    time.sleep(.05)
            session = driver('/session', {'capabilities':{'alwaysMatch':{'browserName':'firefox',
                                         'moz:firefoxOptions':{'args':['-headless']}}}})['sessionId']
            prefix = '/session/' + session
            driver(prefix + '/url', {'url':f'http://127.0.0.1:{ports[0]}/browser.html'})
            result = driver(prefix + '/execute/sync', {'script':
                'return [document.title, document.getElementById("result").textContent, '
                'getComputedStyle(document.getElementById("result")).color];', 'args':[]})
            assert result == ['JS loaded', 'ready', 'rgb(0, 128, 0)'], result
            script = ('const done = arguments[0]; fetch("/test.py").then(r=>r.text()).then(done)'
                      '.catch(e=>done(String(e)));')
            first = driver(prefix + '/execute/async', {'script':script, 'args':[]})
            second = driver(prefix + '/execute/async', {'script':script, 'args':[]})
            def field(text, name):
                return next(line.split(': ', 1)[1] for line in text.splitlines() if line.startswith(name + ': '))
            assert field(first, 'Session ID') == field(second, 'Session ID')
            assert int(field(second, 'Session visits')) > int(field(first, 'Session visits'))
            cookies = driver(prefix + '/cookie')
            assert any(c['name'] == 'SESSION_ID' and c['httpOnly'] for c in cookies), cookies
            screenshot = driver(prefix + '/screenshot')
            Path('/tmp/java-localserver-browser.png').write_bytes(base64.b64decode(screenshot))
            print('PASS: Firefox loaded HTML, external CSS and JavaScript; CGI fetch and HttpOnly session persistence work.')
        except Exception:
            browser_log.flush()
            browser_log.seek(0)
            print(browser_log.read())
            raise
        finally:
            if session:
                try:
                    driver('/session/' + session, method='DELETE')
                except OSError:
                    pass
            gecko.terminate()
            server.terminate()
            gecko.wait(timeout=5)
            server.wait(timeout=5)
