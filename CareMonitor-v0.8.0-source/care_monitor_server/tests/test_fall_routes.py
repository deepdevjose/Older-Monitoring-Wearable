"""Authenticated fall-response workflow checks using explicit synthetic test events."""
from datetime import datetime, timedelta, timezone
import sqlite3
import pytest

from care_monitor_server.tests.test_accounts import account_client, session
from care_monitor_server.database import connect, transaction


def event_body(age=1, source='manual_test', event_id='isolated-fall-fixture'):
    return {'event_id': event_id, 'device_id': 'elder-demo-01', 'source': source,
            'detected_at': (datetime.now(timezone.utc) - timedelta(seconds=age)).isoformat()}


def test_cancel_before_deadline_no_caregiver_notification(account_client):
    patient = session(account_client)
    carer = session(account_client, 'carer.test')
    result = account_client.post('/accounts/fall-events', headers=patient, json=event_body())
    assert result.status_code == 200
    assert result.json()['event']['status'] == 'pending'
    cancelled = account_client.post('/accounts/fall-events/isolated-fall-fixture/cancel', headers=patient, json={'device_id': 'elder-demo-01'})
    assert cancelled.json()['event']['cancellation_accepted'] is True
    assert cancelled.json()['event']['status'] == 'cancelled'
    assert account_client.get('/accounts/notifications', headers=carer).json()['notifications'] == []


def test_late_arrival_escalates_once_seen_and_resolution_separate(account_client):
    patient = session(account_client)
    carer = session(account_client, 'carer.test')
    body = event_body(age=20)
    first = account_client.post('/accounts/fall-events', headers=patient, json=body)
    assert first.status_code == 200
    assert first.json()['event']['status'] == 'escalated'
    assert first.json()['push_configured'] is False
    duplicate = account_client.post('/accounts/fall-events', headers=patient, json=body)
    assert duplicate.json()['event']['duplicate'] is True
    notifications = account_client.get('/accounts/notifications', headers=carer).json()['notifications']
    assert len(notifications) == 1
    assert notifications[0]['status'] == 'queued'
    seen = account_client.post('/accounts/notifications/' + str(notifications[0]['id']) + '/seen', headers=carer)
    assert seen.json() == {'status': 'seen_by_caregiver', 'event_resolved': False}
    event = account_client.get('/accounts/fall-events/isolated-fall-fixture?device_id=elder-demo-01', headers=patient)
    assert event.json()['event']['status'] == 'escalated'
    late = account_client.post('/accounts/fall-events/isolated-fall-fixture/cancel', headers=patient, json={'device_id': 'elder-demo-01'})
    assert late.json()['event']['cancellation_accepted'] is False
    resolved = account_client.post('/accounts/fall-events/isolated-fall-fixture/resolve', headers=carer, json={'device_id': 'elder-demo-01'})
    assert resolved.json()['event']['status'] == 'resolved'


def test_help_bypasses_deadline_and_role_boundaries(account_client):
    patient = session(account_client)
    carer = session(account_client, 'carer.test')
    body = event_body(source='wearer_help')
    assert account_client.post('/accounts/fall-events', headers=carer, json=body).status_code == 403
    result = account_client.post('/accounts/fall-events', headers=patient, json=body)
    assert result.json()['event']['status'] == 'escalated'
    assert account_client.get('/accounts/notifications', headers=patient).status_code == 403
    assert account_client.post('/accounts/fall-events/isolated-fall-fixture/resolve', headers=patient, json={'device_id': 'elder-demo-01'}).status_code == 403


def test_no_claimed_live_detector_or_unauthenticated_events(account_client):
    assert account_client.post('/accounts/fall-events', json=event_body()).status_code == 401
    patient = session(account_client)
    assert account_client.post('/accounts/fall-events', headers=patient, json=event_body(source='ML_detected_fall')).status_code == 400
    assert account_client.post('/accounts/fall-events', headers=patient, json=event_body(age=-30)).status_code == 400
    status = account_client.get('/accounts/response-system', headers=patient).json()
    assert status['countdown_seconds'] == 15
    assert status['fall_detector_available'] is False
    assert status['push_configured'] is False


def test_reassignment_does_not_leak_old_notification(account_client, db):
    patient = session(account_client)
    carer = session(account_client, 'carer.test')
    account_client.post('/accounts/fall-events', headers=patient, json=event_body(age=20))
    rows = account_client.get('/accounts/notifications', headers=carer).json()['notifications']
    assert len(rows) == 1
    with connect(db) as conn, transaction(conn):
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute("UPDATE cm_devices SET patient_id='another-patient' WHERE device_id='elder-demo-01'")
    assert account_client.post('/accounts/fall-events/isolated-fall-fixture/resolve', headers=carer,
                               json={'device_id': 'elder-demo-01'}).status_code == 200
    with connect(db) as conn, transaction(conn):
        conn.execute("UPDATE cm_devices SET patient_id='another-patient' WHERE device_id='elder-demo-01'")
    assert account_client.get('/accounts/notifications', headers=carer).json()['notifications'] == []
    assert account_client.post('/accounts/notifications/' + str(rows[0]['id']) + '/seen', headers=carer).status_code == 403
