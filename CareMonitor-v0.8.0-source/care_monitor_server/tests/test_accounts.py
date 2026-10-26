"""Candidate-only account boundaries with isolated temporary databases."""
import hashlib
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from care_monitor_server import account_auth, alert_engine
from care_monitor_server.candidate_app import create_account_candidate
from care_monitor_server.database import connect, transaction
from care_monitor_server.security import utc_timestamp


@pytest.fixture
def account_client(db):
    with TestClient(create_account_candidate(db), base_url='https://testserver') as client:
        with connect(db) as conn, transaction(conn):
            conn.execute('INSERT INTO cm_devices VALUES (?,?)', ('another-device', 'another-patient'))
            account_auth.create_user(conn, username='patient.test', password='test-only-long-password', role='patient', patient_id='demo-patient-001')
            account_auth.create_user(conn, username='carer.test', password='test-only-long-password', role='caregiver')
            account_auth.assign_caregiver(conn, 'carer.test', 'demo-patient-001')
            now = utc_timestamp()
            client.test_alert = alert_engine._create(conn, 'elder-demo-01', 'demo-patient-001', 'heart_rate_high', 'warning', 'rule', now, now, {'test_only': True}, 'fixture', 'fixture-only')
        yield client


def session(client, username='patient.test'):
    response = client.post('/accounts/login', json={'username': username, 'password': 'test-only-long-password'})
    assert response.status_code == 200
    return {'Authorization': 'Bearer ' + response.json()['access_token']}


def test_patient_account_and_device_scope(account_client):
    headers = session(account_client)
    me = account_client.get('/accounts/me', headers=headers)
    assert me.json()['role'] == 'patient'
    assert me.json()['can_acknowledge_alerts'] is False
    assert [d['device_id'] for d in me.json()['devices']] == ['elder-demo-01']
    assert account_client.get('/accounts/readings?device_id=elder-demo-01', headers=headers).status_code == 200
    assert account_client.get('/accounts/readings?device_id=another-device', headers=headers).status_code == 403
    assert account_client.get('/accounts/alerts?device_id=another-device', headers=headers).status_code == 403


def test_caregiver_acknowledges_only_assigned_patient(account_client):
    patient = session(account_client)
    carer = session(account_client, 'carer.test')
    path = '/accounts/alerts/' + str(account_client.test_alert) + '/acknowledge'
    body = {'device_id': 'elder-demo-01'}
    assert account_client.post(path, headers=patient, json=body).status_code == 403
    assert account_client.post(path, headers=carer, json={'device_id': 'another-device'}).status_code == 403
    result = account_client.post(path, headers=carer, json=body)
    assert result.status_code == 200
    assert result.json()['alert']['status'] == 'acknowledged'
    assert account_client.get('/accounts/alerts?device_id=elder-demo-01', headers=carer).status_code == 200


def test_logout_revokes_session_and_expiry_is_enforced(account_client, db):
    headers = session(account_client)
    assert account_client.post('/accounts/logout', headers=headers).status_code == 200
    assert account_client.get('/accounts/me', headers=headers).status_code == 401
    headers = session(account_client)
    with connect(db) as conn, transaction(conn):
        conn.execute('UPDATE cm_user_sessions SET expires_at=?', (int(time.time()) - 1,))
    assert account_client.get('/accounts/me', headers=headers).status_code == 401


def test_disabled_user_and_removed_assignment_take_effect(account_client, db):
    headers = session(account_client, 'carer.test')
    with connect(db) as conn, transaction(conn):
        conn.execute('DELETE FROM cm_caregiver_assignments')
    assert account_client.get('/accounts/readings?device_id=elder-demo-01', headers=headers).status_code == 403
    with connect(db) as conn, transaction(conn):
        conn.execute("UPDATE cm_users SET active=0 WHERE username='carer.test'")
    assert account_client.get('/accounts/me', headers=headers).status_code == 401


def test_login_does_not_allow_role_or_patient_self_assignment(account_client):
    body = {'username': 'patient.test', 'password': 'test-only-long-password', 'role': 'caregiver'}
    assert account_client.post('/accounts/login', json=body).status_code == 400
    assert account_client.post('/accounts/register', json=body).status_code == 404


def test_credentials_not_saved_plaintext_or_returned(account_client, db):
    headers = session(account_client)
    raw_token = headers['Authorization'][7:]
    with connect(db) as conn:
        user = dict(conn.execute("SELECT * FROM cm_users WHERE username='patient.test'").fetchone())
        stored = dict(conn.execute('SELECT * FROM cm_user_sessions').fetchone())
    assert 'test-only-long-password' not in repr(user)
    assert raw_token not in repr(stored)
    assert stored['token_hash'] == hashlib.sha256(raw_token.encode()).hexdigest()
    assert user['iterations'] == 600_000
    assert not {'salt', 'password_hash', 'token_hash'} & set(account_client.get('/accounts/me', headers=headers).json())


def test_unknown_and_wrong_password_same_response_and_rate_limit(account_client):
    a = account_client.post('/accounts/login', json={'username': 'unknown.user', 'password': 'wrong-password'})
    b = account_client.post('/accounts/login', json={'username': 'patient.test', 'password': 'wrong-password'})
    assert a.status_code == b.status_code == 401
    assert a.json() == b.json()
    for _ in range(8):
        assert account_client.post('/accounts/login', json={'bad': 'payload'}).status_code == 400
    assert account_client.post('/accounts/login', json={'bad': 'payload'}).status_code == 429


def test_machine_key_is_not_a_patient_or_caregiver_session(account_client, token):
    assert account_client.get('/accounts/me', headers={'X-API-Key': token}).status_code == 401
    assert account_client.get('/accounts/me', headers={'Authorization': 'Bearer ' + token}).status_code == 401


def test_https_required_for_login(db):
    with TestClient(create_account_candidate(db), base_url='http://testserver') as client:
        response = client.post('/accounts/login', json={'username': 'patient.test', 'password': 'test-only-long-password'})
        assert response.status_code == 426


@pytest.mark.parametrize('role,patient_id', [('administrator', None), ('patient', None), ('patient', 'unknown-patient'), ('caregiver', 'demo-patient-001')])
def test_invalid_provisioning_fails(db, role, patient_id):
    with connect(db) as conn, transaction(conn):
        account_auth.initialise(conn)
        with pytest.raises(ValueError):
            account_auth.create_user(conn, username='test.user', password='test-only-long-password', role=role, patient_id=patient_id)


@pytest.mark.parametrize('path', [None, '', '  '])
def test_candidate_rejects_implicit_live_database(path):
    with pytest.raises(ValueError):
        create_account_candidate(path)


def test_action_revalidates_after_earlier_authentication(account_client, db):
    from care_monitor_server.account_routes import authorize_action
    from fastapi import HTTPException
    headers = session(account_client, 'carer.test')
    with connect(db) as conn:
        stale_user = account_auth.lookup_session(conn, headers['Authorization'][7:])
    with connect(db) as conn, transaction(conn):
        conn.execute('DELETE FROM cm_caregiver_assignments')
    with connect(db) as conn, transaction(conn):
        with pytest.raises(HTTPException) as error:
            authorize_action(conn, stale_user, 'elder-demo-01')
        assert error.value.status_code == 403
        conn.execute('UPDATE cm_user_sessions SET revoked=1')
        with pytest.raises(HTTPException) as error:
            authorize_action(conn, stale_user)
        assert error.value.status_code == 401


def test_password_work_does_not_hold_database_or_event_loop(account_client, db, monkeypatch):
    entered, release = threading.Event(), threading.Event()
    original = account_auth.verify_password

    def paused_verification(credentials, password):
        entered.set()
        assert release.wait(5), 'Test did not release password verification'
        return original(credentials, password)

    monkeypatch.setattr(account_auth, 'verify_password', paused_verification)
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = pool.submit(account_client.post, '/accounts/login',
                              json={'username': 'patient.test', 'password': 'test-only-long-password'})
        try:
            assert entered.wait(3)
            # A separate writer and an ordinary HTTP request must complete during the KDF.
            with connect(db) as conn:
                conn.execute('PRAGMA busy_timeout=100')
                with transaction(conn):
                    conn.execute('SELECT 1')
            health = pool.submit(account_client.get, '/health')
            assert health.result(timeout=3).status_code == 200
        finally:
            release.set()
        assert pending.result(timeout=5).status_code == 200


@pytest.mark.parametrize('change', ['password_hash', 'active'])
def test_account_changed_between_password_check_and_issue_is_rejected(account_client, db, monkeypatch, change):
    original = account_auth.verify_password

    def change_after_verification(credentials, password):
        verified = original(credentials, password)
        assert verified
        with connect(db) as conn, transaction(conn):
            if change == 'password_hash':
                updated = account_auth.password_hash('different-long-test-password', credentials['salt'])
                conn.execute('UPDATE cm_users SET password_hash=? WHERE username=?', (updated, credentials['username']))
            else:
                conn.execute('UPDATE cm_users SET active=0 WHERE username=?', (credentials['username'],))
        return verified

    monkeypatch.setattr(account_auth, 'verify_password', change_after_verification)
    response = account_client.post('/accounts/login', json={
        'username': 'patient.test', 'password': 'test-only-long-password'})
    assert response.status_code == 401
    assert 'access_token' not in response.json()
    with connect(db) as conn:
        assert conn.execute('SELECT COUNT(*) FROM cm_user_sessions').fetchone()[0] == 0


def test_invalid_session_failures_are_committed_and_limited(account_client, db, monkeypatch):
    from care_monitor_server import account_routes
    stamp = int(time.time())
    monkeypatch.setattr(account_routes, 'time', SimpleNamespace(time=lambda: stamp))
    for _ in range(10):
        assert account_client.get('/accounts/me', headers={'Authorization': 'Bearer invalid'}).status_code == 401
    response = account_client.get('/accounts/me')
    assert response.status_code == 429
    assert 1 <= int(response.headers['Retry-After']) <= 60
    with connect(db) as conn:
        count = conn.execute("SELECT count FROM cm_rate_limits WHERE category='account-session-auth'").fetchone()[0]
        assert count == 11
    # Invalid attempts do not lock out a valid bearer session on the same shared IP.
    headers = session(account_client)
    assert account_client.get('/accounts/me', headers=headers).status_code == 200


def test_device_reassignment_does_not_expose_previous_patient_records(account_client, db):
    old_session = session(account_client)
    with connect(db) as conn, transaction(conn):
        now = utc_timestamp()
        conn.execute('INSERT INTO cm_readings(device_id,patient_id,received_at,payload_json) VALUES (?,?,?,?)',
                     ('elder-demo-01', 'demo-patient-001', now, json.dumps({'fixture_owner': 'previous'})))
        conn.execute('UPDATE cm_devices SET patient_id=? WHERE device_id=?', ('another-patient', 'elder-demo-01'))
        account_auth.create_user(conn, username='new.patient', password='test-only-long-password',
                                 role='patient', patient_id='another-patient')
        account_auth.assign_caregiver(conn, 'carer.test', 'another-patient')
        conn.execute('INSERT INTO cm_readings(device_id,patient_id,received_at,payload_json) VALUES (?,?,?,?)',
                     ('elder-demo-01', 'another-patient', now, json.dumps({'fixture_owner': 'current'})))
        new_alert = alert_engine._create(conn, 'elder-demo-01', 'another-patient', 'spo2_low',
                                        'warning', 'rule', now, now, {'test_only': True}, 'fixture', 'fixture-only')
    new_session = session(account_client, 'new.patient')
    carer = session(account_client, 'carer.test')
    assert account_client.get('/accounts/readings?device_id=elder-demo-01', headers=old_session).status_code == 403
    for headers in (new_session, carer):
        readings = account_client.get('/accounts/readings?device_id=elder-demo-01', headers=headers)
        assert readings.status_code == 200
        assert [item['data']['fixture_owner'] for item in readings.json()['readings']] == ['current']
        alerts = account_client.get('/accounts/alerts?device_id=elder-demo-01&limit=1', headers=headers)
        assert alerts.status_code == 200
        assert [item['id'] for item in alerts.json()['alerts']] == [new_alert]
        assert alerts.json()['has_more'] is False
    old_ack = account_client.post(f'/accounts/alerts/{account_client.test_alert}/acknowledge',
                                  headers=carer, json={'device_id': 'elder-demo-01'})
    assert old_ack.status_code == 404
    assert account_client.post(f'/accounts/alerts/{new_alert}/acknowledge',
                               headers=carer, json={'device_id': 'elder-demo-01'}).status_code == 200
    with connect(db) as conn:
        assert conn.execute('SELECT status FROM cm_alerts WHERE id=?',
                            (account_client.test_alert,)).fetchone()[0] == 'active'
