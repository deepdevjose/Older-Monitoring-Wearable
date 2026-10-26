"""Synthetic, temporary-DB checks; not evidence of phone or clinical performance."""
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from care_monitor_server import account_auth, stored_classification
from care_monitor_server.candidate_app import create_account_candidate
from care_monitor_server.database import connect, transaction

NOW = datetime(2026, 10, 26, 12, 0, tzinfo=timezone.utc)
DEVICE = 'elder-demo-01'
PATIENT = 'demo-patient-001'
ARTIFACT = Path(__file__).resolve().parents[2] / 'evidence' / 'ml' / 'manifest.json'


def at(seconds=0):
    return stored_classification.iso(NOW + timedelta(seconds=seconds))


def observation():
    return {'device_id': DEVICE, 'phone_received_at': at(), 'uptime_ms': 10,
            'max30102': {'online': True, 'status': 'ok', 'finger_detected': True,
                         'heart_rate_valid': True, 'spo2_valid': True,
                         'heart_rate_bpm': 76.0, 'spo2_percent': 98.0,
                         'sample_age_ms': 20, 'result_age_ms': 300}}


@pytest.fixture
def live_client(db, monkeypatch):
    monkeypatch.setattr(stored_classification, 'utc_now', lambda: NOW)
    monkeypatch.delenv('CM_ALERT_RULES_FILE', raising=False)
    app = create_account_candidate(db, fall_worker_enabled=False,
        classification_manifest=ARTIFACT,
        classification_manifest_sha256=hashlib.sha256(ARTIFACT.read_bytes()).hexdigest())
    with TestClient(app, base_url='https://testserver') as client:
        with connect(db) as conn, transaction(conn):
            account_auth.create_user(conn, username='patient.check', password='isolated-test-password', role='patient', patient_id=PATIENT)
            account_auth.create_user(conn, username='caregiver.check', password='isolated-test-password', role='caregiver')
            account_auth.assign_caregiver(conn, 'caregiver.check', PATIENT)
        result = client.post('/accounts/login', json={'username': 'patient.check', 'password': 'isolated-test-password'})
        assert result.status_code == 200
        client.headers.update({'Authorization': 'Bearer ' + result.json()['access_token']})
        yield client


def insert(db, value=None, receipt=None, sensor=None, patient=PATIENT, device=DEVICE):
    with connect(db) as conn, transaction(conn):
        return conn.execute('INSERT INTO cm_readings(device_id,patient_id,received_at,sensor_timestamp,payload_json) VALUES (?,?,?,?,?)',
            (device, patient, receipt or at(), sensor, json.dumps(observation() if value is None else value))).lastrowid


def classify(client, reading_id=None):
    params = {'device_id': DEVICE}
    if reading_id is not None:
        params['reading_id'] = reading_id
    result = client.get('/accounts/classification', params=params)
    assert result.status_code == 200, result.text
    return result.json()


def assert_unavailable(body, reason):
    assert body['status'] == 'unavailable'
    for result in body['results'].values():
        assert result['reason'] == reason
        assert result['status'] == 'unavailable'
        assert result['classification'] is None and result['confidence'] is None


def test_current_row_matches_readings_and_real_pinned_models(live_client, db):
    identity = insert(db)
    row = live_client.get('/accounts/readings', params={'device_id': DEVICE}).json()
    result = classify(live_client, row['readings'][0]['id'])
    assert result['reading_id'] == identity == row['readings'][0]['id']
    assert result['measurement_at'] == row['readings'][0]['measurement_at'] == at()
    assert result['server_time'] == row['server_time'] == at()
    assert result['freshness'] == row['readings'][0]['freshness']
    assert result['status'] == 'available'
    assert result['three_class_supported'] is False
    assert result['care_alert_created'] is False and result['clinical_validation'] is False
    for task in ('heart_rate', 'oxygen'):
        prediction = result['results'][task]
        assert prediction['classification'] == 'NORMAL'
        assert prediction['confidence'] == prediction['class_probabilities']['NORMAL']
        assert sum(prediction['class_probabilities'].values()) == pytest.approx(1)
        assert prediction['input_source'] == 'prototype_sensor'
        assert prediction['confidence_calibrated'] is False
        assert prediction['model']['synthetic_training'] is True
    with connect(db) as conn:
        assert conn.execute('SELECT count(*) FROM cm_alerts').fetchone()[0] == 0


def test_no_rows_returns_unavailable_not_fake_normal(live_client):
    result = classify(live_client)
    assert result['reading_id'] is None and result['measurement_at'] is None
    assert_unavailable(result, 'missing_reading')


def test_history_binding_does_not_silently_switch_to_newer_row(live_client, db):
    older = observation()
    older['phone_received_at'] = at(-4)
    older['max30102']['heart_rate_bpm'] = 120
    identity = insert(db, older, receipt=at(-4))
    latest = insert(db)
    result = classify(live_client, identity)
    assert result['reading_id'] == identity
    assert result['results']['heart_rate']['classification'] == 'ABNORMAL'
    assert classify(live_client)['reading_id'] == latest


@pytest.mark.parametrize('case,reason', [
    ('old_phone', 'stale_reading'), ('old_sensor', 'stale_reading'), ('old_receipt', 'stale_reading'),
    ('future_phone', 'future_measurement_time'), ('future_sensor', 'future_measurement_time'),
    ('future_receipt', 'future_receipt_time'), ('after_receipt', 'future_measurement_time'),
    ('naive_phone', 'invalid_measurement_time'), ('numeric_phone', 'invalid_measurement_time'),
    ('null_phone', 'invalid_measurement_time'), ('bad_sensor', 'invalid_measurement_time'),
    ('overflow_phone', 'invalid_measurement_time'), ('bad_receipt', 'invalid_receipt_time'),
    ('uptime_only', 'missing_measurement_time'),
])
def test_clock_gates(live_client, db, case, reason):
    data, receipt, sensor = observation(), at(), None
    if case == 'old_phone': data['phone_received_at'] = at(-11)
    if case == 'old_sensor': sensor = at(-11)
    if case == 'old_receipt': receipt = at(-11); data['phone_received_at'] = at(-11)
    if case == 'future_phone': data['phone_received_at'] = at(3)
    if case == 'future_sensor': sensor = at(3)
    if case == 'future_receipt': receipt = at(3)
    if case == 'after_receipt': receipt = at(-3)
    if case == 'naive_phone': data['phone_received_at'] = '2026-10-26T12:00:00'
    if case == 'numeric_phone': data['phone_received_at'] = 1793016000
    if case == 'null_phone': data['phone_received_at'] = None
    if case == 'bad_sensor': sensor = 'not-time'
    if case == 'overflow_phone': data['phone_received_at'] = '9999-12-31T23:59:59-23:59'
    if case == 'bad_receipt': receipt = 'not-time'
    if case == 'uptime_only': data.pop('phone_received_at'); data['uptime_ms'] = 1793016000000
    identity = insert(db, data, receipt, sensor)
    assert_unavailable(classify(live_client, identity), reason)


def test_legacy_phone_timestamp_and_optional_ages_are_explicit(live_client, db):
    data = observation()
    for field in ('online', 'sample_age_ms', 'result_age_ms'):
        data['max30102'].pop(field)
    data['phone_received_at'] = '2026-10-26T20:00:00+08:00'
    insert(db, data)
    result = classify(live_client)
    assert result['status'] == 'available' and result['measurement_at'] == at()
    assert result['measurement_time_source'] == 'phone'
    assert result['results']['heart_rate']['quality_coverage'] == {
        'online_reported': False, 'sample_age_reported': False, 'result_age_reported': False}


def test_oldest_timestamp_selected_and_tolerance_is_explicit(live_client, db):
    data = observation()
    data['phone_received_at'] = at(-10)
    insert(db, data, sensor=at(-3))
    result = classify(live_client)
    assert result['measurement_at'] == at(-10)
    assert result['freshness']['age_seconds'] == 10
    assert result['status'] == 'available'
    data['phone_received_at'] = at(2)
    insert(db, data)
    assert classify(live_client)['status'] == 'available'


@pytest.mark.parametrize('changes,reason', [
    ({'online': False}, 'optical_offline'), ({'online': 1}, 'invalid_optical_online'),
    ({'status': 'waiting'}, 'optical_not_ready'), ({'status': 'offline'}, 'optical_not_ready'),
    ({'finger_detected': False}, 'finger_not_detected'), ({'finger_detected': 1}, 'finger_not_detected'),
    ({'sample_age_ms': 500}, 'stale_or_invalid_optical_age'),
    ({'result_age_ms': 2500}, 'stale_or_invalid_optical_age'),
    ({'sample_age_ms': None}, 'stale_or_invalid_optical_age'),
    ({'result_age_ms': -1}, 'stale_or_invalid_optical_age'),
    ({'result_age_ms': '20'}, 'stale_or_invalid_optical_age'),
    ({'result_age_ms': True}, 'stale_or_invalid_optical_age'),
])
def test_optical_quality_gates(live_client, db, changes, reason):
    data = observation()
    data['max30102'].update(changes)
    insert(db, data)
    assert_unavailable(classify(live_client), reason)


@pytest.mark.parametrize('field,value,task,reason', [
    ('heart_rate_valid', False, 'heart_rate', 'channel_invalid'),
    ('spo2_valid', 1, 'oxygen', 'channel_invalid'),
    ('heart_rate_bpm', None, 'heart_rate', 'missing_or_non_numeric'),
    ('heart_rate_bpm', float('nan'), 'heart_rate', 'missing_or_non_numeric'),
    ('spo2_percent', float('inf'), 'oxygen', 'missing_or_non_numeric'),
    ('heart_rate_bpm', True, 'heart_rate', 'missing_or_non_numeric'),
    ('spo2_percent', '98', 'oxygen', 'missing_or_non_numeric'),
    ('spo2_percent', 101, 'oxygen', 'outside_physical_range'),
    ('heart_rate_bpm', 0, 'heart_rate', 'outside_physical_range'),
    ('heart_rate_bpm', 150, 'heart_rate', 'outside_training_domain'),
    ('spo2_percent', 80, 'oxygen', 'outside_training_domain'),
])
def test_channels_independent_no_imputation(live_client, db, field, value, task, reason):
    data = observation()
    data['max30102'].update({field: value, 'status': 'partial'})
    insert(db, data)
    result = classify(live_client)
    assert result['status'] == 'partial'
    assert result['results'][task]['reason'] == reason
    assert result['results'][task]['classification'] is None
    other = 'oxygen' if task == 'heart_rate' else 'heart_rate'
    assert result['results'][other]['status'] == 'available'


@pytest.mark.parametrize('state,reason', [('disabled', 'model_disabled'), ('unavailable', 'model_unavailable')])
def test_model_not_ready_returns_200_unavailable(live_client, db, state, reason):
    insert(db)
    live_client.app.state.classifier.status = state
    assert_unavailable(classify(live_client), reason)


@pytest.mark.parametrize('data,reason', [([], 'invalid_payload'), ({'phone_received_at': at()}, 'optical_missing')])
def test_missing_module_or_invalid_payload(live_client, db, data, reason):
    insert(db, data)
    assert_unavailable(classify(live_client), reason)


def test_no_core_temperature_or_bp_inference(live_client, db):
    data = observation()
    data['gy906'] = {'online': True, 'object_temp_c': 36.9, 'ambient_temp_c': 23}
    insert(db, data)
    assert set(classify(live_client)['results']) == {'heart_rate', 'oxygen'}


def test_authentication_scope_and_unknown_identity(live_client, db, token):
    insert(db)
    path = '/accounts/classification?device_id=' + DEVICE
    assert live_client.get(path, headers={'Authorization': ''}).status_code == 401
    assert live_client.get(path, headers={'Authorization': 'Bearer ' + token}).status_code == 401
    assert live_client.get('/accounts/classification?device_id=another-device').status_code == 403
    assert live_client.get(path + '&reading_id=9223372036854775807').status_code == 404
    assert live_client.get(path + '&reading_id=0').status_code == 422


def test_caregiver_access_and_assignment_revocation(live_client, db):
    insert(db)
    login = live_client.post('/accounts/login', json={'username': 'caregiver.check', 'password': 'isolated-test-password'})
    live_client.headers.update({'Authorization': 'Bearer ' + login.json()['access_token']})
    assert classify(live_client)['status'] == 'available'
    with connect(db) as conn, transaction(conn):
        conn.execute('DELETE FROM cm_caregiver_assignments')
    assert live_client.get('/accounts/classification?device_id=' + DEVICE).status_code == 403


def test_reassignment_hides_previous_patient_history(live_client, db):
    old_id = insert(db)
    with connect(db) as conn, transaction(conn):
        conn.execute('UPDATE cm_devices SET patient_id=? WHERE device_id=?', ('patient-new', DEVICE))
        account_auth.create_user(conn, username='new.check', password='isolated-test-password', role='patient', patient_id='patient-new')
    assert live_client.get('/accounts/classification?device_id=' + DEVICE).status_code == 403
    login = live_client.post('/accounts/login', json={'username': 'new.check', 'password': 'isolated-test-password'})
    live_client.headers.update({'Authorization': 'Bearer ' + login.json()['access_token']})
    assert_unavailable(classify(live_client), 'missing_reading')
    assert live_client.get('/accounts/classification', params={'device_id': DEVICE, 'reading_id': old_id}).status_code == 404
    new_id = insert(db, patient='patient-new')
    assert classify(live_client)['reading_id'] == new_id
