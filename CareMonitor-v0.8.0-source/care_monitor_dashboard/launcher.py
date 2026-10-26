"""Open the local test database UI and retain the existing HTTPS phone receiver."""
from __future__ import annotations

import argparse
import ctypes
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser

ROOT = Path(__file__).resolve().parents[1]
PHYSICAL_RUNTIME = Path(r'C:\Users\29682\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CareMonitor\server-v0.7.0')
DATABASE = Path(os.environ.get('CARE_MONITOR_DB', str(PHYSICAL_RUNTIME / 'care_monitor.db'))).expanduser().resolve()
PYTHON = ROOT / 'work/secure_server_venv/Scripts/python.exe'
URL = 'http://127.0.0.1:8001/'


def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.3):
            return True
    except OSError:
        return False


def health(url, identity, context=None):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                            urllib.request.HTTPSHandler(context=context))
        with opener.open(url, timeout=1) as response:
            data = json.load(response)
            return response.status == 200 and data.get('service') == identity
    except (OSError, ValueError, urllib.error.URLError):
        return False


def launch(module, args, name):
    environment = os.environ.copy()
    environment['CARE_MONITOR_DB'] = str(DATABASE)
    environment['PYTHONUNBUFFERED'] = '1'
    runtime = DATABASE.parent
    with (runtime / (name + '.stdout.log')).open('ab') as stdout, (runtime / (name + '.stderr.log')).open('ab') as stderr:
        process = subprocess.Popen([str(PYTHON), '-m', 'uvicorn', module, *args,
                                    '--no-proxy-headers', '--no-access-log'],
                                   cwd=str(ROOT), env=environment, stdin=subprocess.DEVNULL,
                                   stdout=stdout, stderr=stderr,
                                   creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    return process


def wait_for(url, identity, process, context=None):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if health(url, identity, context):
            return
        if process.poll() is not None:
            break
        time.sleep(0.25)
    raise RuntimeError('A Care Monitor service could not start. Its diagnostic log is in the protected database folder.')


def start():
    if not DATABASE.is_file():
        raise RuntimeError('The existing server database could not be found. Configure CARE_MONITOR_DB with its actual path.')
    if not PYTHON.is_file():
        raise RuntimeError('The Care Monitor Python environment is missing.')
    runtime = DATABASE.parent
    tls = ssl.create_default_context(cafile=str(runtime / 'local_ca.crt'))
    receiver_url = 'https://127.0.0.1:8000/health'
    if not health(receiver_url, 'care-monitor', tls):
        if port_open(8000):
            raise RuntimeError('Port 8000 is already in use by a different or unverified server. No process was stopped.')
        receiver = launch('care_monitor_server.app:app',
                          ['--host', '0.0.0.0', '--port', '8000',
                           '--ssl-certfile', str(runtime / 'local_server.crt'),
                           '--ssl-keyfile', str(runtime / 'local_server.key')], 'server')
        wait_for(receiver_url, 'care-monitor', receiver, tls)
    dashboard_url = URL + 'api/health'
    if not health(dashboard_url, 'care-monitor-test-dashboard'):
        if port_open(8001):
            raise RuntimeError('Port 8001 is already in use by a different or unverified service. No process was stopped.')
        dashboard = launch('care_monitor_dashboard.app:app',
                           ['--host', '127.0.0.1', '--port', '8001'], 'dashboard')
        wait_for(dashboard_url, 'care-monitor-test-dashboard', dashboard)
    result = {'status': 'ready', 'dashboard_url': URL, 'receiver_https': True,
              'dashboard_loopback_only': True, 'existing_database_preserved': True}
    (runtime / 'dashboard-launch-status.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-open', action='store_true', help='Start services without opening a browser')
    args = parser.parse_args()
    try:
        result = start()
        if not args.no_open:
            webbrowser.open(URL)
        if sys.stdout is not None:
            print(json.dumps(result))
        return 0
    except Exception as error:
        # No API keys or payloads are read into launch diagnostics.
        message = str(error) if isinstance(error, RuntimeError) else 'Care Monitor could not open the local database dashboard.'
        if not args.no_open and os.name == 'nt':
            ctypes.windll.user32.MessageBoxW(None, message, 'Care Monitor Database', 0x10)
        if sys.stderr is not None:
            print(message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
