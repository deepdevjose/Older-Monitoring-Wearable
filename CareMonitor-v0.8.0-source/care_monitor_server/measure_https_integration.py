"""Real localhost HTTPS sockets with synthetic data and ephemeral credentials only.

Never targets or copies a live database, certificate, account or credential.
The certificate-generation interpreter needs cryptography; the server runtime
uses only its existing requirements plus the Python standard library.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory
import threading
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

import uvicorn

from . import account_auth, alert_engine
from .candidate_app import create_account_candidate
from .database import connect, initialise, transaction
from .security import issue

CERTIFICATE_SCRIPT = '''from pathlib import Path
import sys, ipaddress
from datetime import datetime, timedelta, timezone
from cryptography import x509
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
root=Path(sys.argv[1]); key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'CareMonitor temporary localhost test')])
now=datetime.now(timezone.utc)
cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
 .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1))
 .not_valid_after(now+timedelta(hours=1))
 .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),critical=False)
 .add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
 .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
 .sign(key,hashes.SHA256()))
(root/'test-certificate.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
(root/'test-key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
'''


def measure(certificate_python):
    root = Path(__file__).resolve().parents[1]
    manifest = root / 'evidence/ml/manifest.json'
    manifest_sha = '93f2ab696da2338bd059536cdf1f84b71eb98d4d7af290ce2e6c1515adceee7b'
    started = datetime.now(timezone.utc).isoformat()
    operations = []
    policy = {**copy.deepcopy(alert_engine.DEFAULT_POLICY), 'enabled': True, 'operator_acknowledged': True,
        'version': 'localhost-synthetic-check', 'label': 'SYNTHETIC SOFTWARE FIXTURE ONLY',
        'rules': [{'id': 'heart_rate_high', 'enabled': True, 'metric': 'heart_rate_bpm', 'direction': 'high',
                   'trigger': 100, 'recover': 95, 'persistence_seconds': 2, 'recovery_seconds': 2,
                   'min_samples': 3, 'severity': 'warning'}]}
    with patch.dict(os.environ, {'CM_ALERT_RULES_FILE': ''}), TemporaryDirectory(prefix='cm-local-https-') as directory:
        temporary = Path(directory)
        generator = temporary / 'generate_test_certificate.py'
        generator.write_text(CERTIFICATE_SCRIPT, encoding='utf-8')
        subprocess.run([str(certificate_python), str(generator), str(temporary)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=30)
        db = temporary / 'synthetic-only.db'
        initialise(db)
        password = secrets.token_urlsafe(24)
        with connect(db) as conn, transaction(conn):
            account_auth.initialise(conn)
            upload, _ = issue(conn, label='Ephemeral localhost test', scopes={'sensor:write'})
            account_auth.create_user(conn, username='synthetic.patient', password=password, role='patient', patient_id='demo-patient-001')
            account_auth.create_user(conn, username='synthetic.carer', password=password, role='caregiver')
            account_auth.assign_caregiver(conn, 'synthetic.carer', 'demo-patient-001')
            alert_engine.initialise(conn, policy)
        app = create_account_candidate(db, fall_worker_enabled=False,
            classification_manifest=manifest, classification_manifest_sha256=manifest_sha)
        cert, key = temporary / 'test-certificate.pem', temporary / 'test-key.pem'
        # Kernel-allocated ephemeral port, held open until passed to Uvicorn.
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
        config = uvicorn.Config(app, host='127.0.0.1', port=port,
            ssl_certfile=str(cert), ssl_keyfile=str(key), proxy_headers=False,
            access_log=False, log_level='error')
        server = uvicorn.Server(config)
        worker = threading.Thread(target=server.run, kwargs={'sockets': [sock]}, daemon=True)
        worker.start()
        context = ssl.create_default_context(cafile=str(cert))
        base = f'https://127.0.0.1:{port}'

        def request(method, path, body=None, token=None, machine=None, expected=200):
            headers = {}
            if token is not None:
                headers['Authorization'] = 'Bearer ' + token
            if machine is not None:
                headers['X-API-Key'] = machine
            data = None
            if body is not None:
                headers['Content-Type'] = 'application/json'
                data = json.dumps(body, allow_nan=False).encode()
            before = time.perf_counter()
            try:
                with urlopen(Request(base + path, data=data, headers=headers, method=method), context=context, timeout=10) as response:
                    status, payload = response.status, response.read()
            except HTTPError as response:
                status, payload = response.code, response.read()
            operations.append({'method': method, 'path': path.split('?')[0], 'status': status,
                               'milliseconds': round((time.perf_counter() - before) * 1000, 3)})
            assert status == expected, f'{method} {path.split("?")[0]} returned {status}, expected {expected}'
            return json.loads(payload)

        try:
            deadline = time.monotonic() + 10
            while not server.started:
                if not worker.is_alive() or time.monotonic() > deadline:
                    raise RuntimeError('Temporary HTTPS server did not start')
                time.sleep(0.02)
            request('GET', '/health')
            patient = request('POST', '/accounts/login', {'username': 'synthetic.patient', 'password': password})['access_token']
            carer = request('POST', '/accounts/login', {'username': 'synthetic.carer', 'password': password})['access_token']
            assert request('GET', '/accounts/me', token=patient)['role'] == 'patient'
            assert request('GET', '/accounts/me', token=carer)['role'] == 'caregiver'
            created = []
            record_ids = []
            synthetic_start = datetime.now(timezone.utc) - timedelta(seconds=5)
            for index in range(5):
                stamp = (synthetic_start + timedelta(seconds=index)).isoformat()
                sample = {'device_id': 'elder-demo-01', 'timestamp': stamp, 'phone_received_at': stamp,
                    'boot_nonce': 'synthetic-loopback-only', 'sample_id': index,
                    'max30102': {'online': True, 'status': 'ok', 'finger_detected': True,
                        'heart_rate_valid': True, 'spo2_valid': True, 'heart_rate_bpm': 120, 'spo2_percent': 98,
                        'sample_age_ms': 10, 'result_age_ms': 100},
                    'mpu6050': {'online': True, 'accel_g': {'x': 0.01, 'y': 0.02, 'z': 1.0}}}
                saved = request('POST', '/sensor-data', sample, machine=upload)
                record_ids.append(saved['record_id'])
                if saved['alert_created']:
                    created.append(saved['alert_id'])
            assert len(created) == 1
            query = '?device_id=elder-demo-01'
            history = request('GET', '/accounts/readings' + query, token=patient)
            assert len(history['readings']) == 5
            chosen = history['readings'][0]['id']
            assert chosen == record_ids[-1]
            assessed = request('GET', '/accounts/classification' + query + f'&reading_id={chosen}', token=patient)
            assert assessed['reading_id'] == chosen and assessed['status'] == 'available'
            assert assessed['results']['heart_rate']['classification'] == 'ABNORMAL'
            assert assessed['results']['oxygen']['classification'] == 'NORMAL'
            for result in assessed['results'].values():
                assert result['confidence'] == result['class_probabilities'][result['classification']]
                assert result['model']['manifest_sha256'] == manifest_sha
            alert = request('GET', '/accounts/alerts' + query, token=carer)['alerts'][0]
            assert alert['id'] == created[0] and alert['status'] == 'active'
            ack_path = f"/accounts/alerts/{alert['id']}/acknowledge"
            request('POST', ack_path, {'device_id': 'elder-demo-01'}, token=patient, expected=403)
            ack = request('POST', ack_path, {'device_id': 'elder-demo-01'}, token=carer)
            duplicate = request('POST', ack_path, {'device_id': 'elder-demo-01'}, token=carer)
            assert ack['alert']['status'] == 'acknowledged'
            assert duplicate['alert']['revision'] == ack['alert']['revision']
            request('GET', '/accounts/classification?device_id=unassigned', token=patient, expected=403)
            request('GET', '/accounts/classification' + query, machine=upload, expected=401)
            request('POST', '/accounts/logout', token=patient)
            request('GET', '/accounts/me', token=patient, expected=401)
            patient = request('POST', '/accounts/login', {'username': 'synthetic.patient', 'password': password})['access_token']
            with connect(db) as conn, transaction(conn):
                conn.execute('UPDATE cm_user_sessions SET expires_at=? WHERE token_hash=?',
                    (int(time.time()) - 1, hashlib.sha256(patient.encode()).hexdigest()))
            request('GET', '/accounts/classification' + query, token=patient, expected=401)
            with connect(db) as conn:
                assert conn.execute('SELECT count(*) FROM cm_readings').fetchone()[0] == 5
                assert conn.execute('SELECT count(*) FROM cm_alerts').fetchone()[0] == 1
        finally:
            server.should_exit = True
            worker.join(timeout=10)
            sock.close()
            if worker.is_alive():
                raise RuntimeError('Temporary HTTPS server did not shut down')
    return {'status': 'passed', 'recorded_at_utc': started,
        'scope': 'actual localhost TCP HTTPS with certificate and hostname verification; synthetic temporary data',
        'physical_sensor_test': False, 'android_phone_test': False, 'wifi_test': False,
        'production_database_accessed': False, 'live_policy_changed': False, 'clinical_validation': False,
        'temporary_database_and_credentials_removed': True, 'tls_verification_disabled': False,
        'model_manifest_sha256': manifest_sha, 'synthetic_readings': 5, 'stored_episodes': 1,
        'checks': ['login', 'me', 'machine_ingest', 'current_patient_history', 'exact_reading_classification',
                   'probability_and_provenance', 'alert_generation', 'duplicate_episode_suppression',
                   'caregiver_review', 'caregiver_only_acknowledgement', 'duplicate_acknowledgement',
                   'unassigned_device_denial', 'machine_key_not_account', 'logout', 'expired_session'],
        'operations': operations,
        'latency_meaning': 'Local software/TLS round trips only; excludes physical acquisition, phone, Wi-Fi and notification delivery',
        'sources_sha256': {name: hashlib.sha256((root / 'care_monitor_server' / name).read_bytes()).hexdigest()
            for name in ('measure_https_integration.py', 'account_routes.py', 'stored_classification.py', 'classification.py')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--certificate-python', type=Path, default=Path(sys.executable))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = measure(args.certificate_python)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'operations': len(result['operations']),
                      'scope': result['scope'], 'evidence': str(args.output)}))


if __name__ == '__main__':
    main()
