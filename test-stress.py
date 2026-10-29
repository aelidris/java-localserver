#!/usr/bin/env python3
"""Run three Siege rounds on localhost; check availability, heap and descriptor recovery."""
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time

repo = Path(__file__).resolve().parent
siege = os.environ.get('SIEGE') or shutil.which('siege')
if not siege:
    raise SystemExit('Install siege, or set SIEGE to its executable path.')

with tempfile.TemporaryDirectory(prefix='stress-') as directory:
    work = Path(directory)
    (work / 'classes').mkdir()
    (work / 'public').mkdir()
    (work / 'public' / 'empty.html').write_bytes(b'')
    subprocess.run(['javac', '-d', str(work / 'classes'), *map(str, (repo / 'src').rglob('*.java'))], check=True)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    (work / 'config.json').write_text(json.dumps({'ports':[port]}))
    rc = work / 'siegerc'
    rc.write_text('protocol = HTTP/1.1\nchunked = true\nverbose = false\nlogging = false\nconnection = close\n')
    with (work / 'server.log').open('w+') as log:
        server = subprocess.Popen(['java', '-Xms64m', '-Xmx64m', '-XX:+UseG1GC', '-cp',
                                   str(work / 'classes'), 'Main'], cwd=work, stdout=log, stderr=log)

        def healthy():
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=3)
            try:
                connection.request('GET', '/empty.html')
                response = connection.getresponse()
                assert response.status == 200 and response.read() == b''
            finally:
                connection.close()

        def heap():
            subprocess.run(['jcmd', str(server.pid), 'GC.run'], check=True, capture_output=True)
            output = subprocess.check_output(['jcmd', str(server.pid), 'GC.heap_info'], text=True)
            return int(re.search(r'heap\s+total\s+\d+K,\s+used\s+(\d+)K', output)[1])

        try:
            for _ in range(100):
                assert server.poll() is None, 'Server exited'
                try:
                    healthy()
                    break
                except OSError:
                    time.sleep(.03)
            else:
                raise AssertionError('Startup timed out')
            heap()  # Warm up the JVM attach listener before counting descriptors.
            baseline = len(list(Path(f'/proc/{server.pid}/fd').iterdir()))
            heaps = []
            for round_number in range(1, 4):
                command = [siege, '-R', str(rc), '-b', '-c25', '-t10S', '-j', '--no-parser',
                           f'http://127.0.0.1:{port}/empty.html']
                run = subprocess.run(command, capture_output=True, text=True, timeout=25)
                if run.returncode:
                    raise AssertionError(run.stdout + run.stderr)
                stats = json.loads(re.search(r'\{.*\}', run.stdout, re.S)[0])
                assert float(stats['availability']) >= 99.5, stats
                assert int(stats['successful_transactions']) > 0, stats
                assert int(stats['successful_transactions']) == int(stats['transactions']), stats
                healthy()
                time.sleep(.2)
                descriptors = len(list(Path(f'/proc/{server.pid}/fd').iterdir()))
                assert descriptors == baseline, f'Descriptors leaked: {baseline} -> {descriptors}'
                heaps.append(heap())
                print(f'Round {round_number}: {stats["availability"]}% availability; '
                      f'{stats["successful_transactions"]} successful transactions; '
                      f'heap after GC {heaps[-1]} KiB; descriptors {descriptors}', flush=True)
            assert max(heaps) - min(heaps) < 4096, f'Unexpected retained heap growth: {heaps}'
            print('PASS: Siege >=99.5%, bounded retained heap, no hanging completed connections or descriptor growth.')
        finally:
            server.terminate()
            server.wait(timeout=5)
