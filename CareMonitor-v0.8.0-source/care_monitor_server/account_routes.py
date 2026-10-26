"""Optional account API. Machine ingest keys do not confer an account role."""
import json
import time

from fastapi import HTTPException, Path as PathParameter, Query, Request
from fastapi.concurrency import run_in_threadpool

from . import account_auth, alert_engine, stored_classification
from .database import connect, transaction
from .security import audit, take_limit, utc_timestamp


def authorize_action(conn, user, device_id=None, caregiver=False):
    current = account_auth.revalidate(conn, user)
    if current is None:
        raise HTTPException(401, 'Invalid or expired account session')
    if caregiver and current['role'] != 'caregiver':
        raise HTTPException(403, 'Caregiver role is required')
    if device_id is not None and not account_auth.can_access(conn, current, device_id):
        raise HTTPException(403, 'Account is not assigned to this device')
    return current


def resolve_current_patient(conn, user, device_id, caregiver=False):
    """Use inside the action transaction to bind historical rows to their actual patient."""
    current = authorize_action(conn, user, device_id, caregiver=caregiver)
    patient_id = account_auth.current_patient(conn, current, device_id)
    if patient_id is None:
        raise HTTPException(403, 'Account is not assigned to this device')
    return patient_id


def register(application, target, read_object):
    def authenticate(request: Request, device_id: str | None = None, caregiver=False):
        fields = request.headers.getlist('authorization')
        token = fields[0][7:] if len(fields) == 1 and fields[0].startswith('Bearer ') else None
        ip = request.client.host if request.client else 'unknown'
        failure = None
        with connect(target) as conn, transaction(conn):
            user = account_auth.lookup_session(conn, token)
            if user is None:
                allowed, retry = take_limit(conn, 'account-session-auth', ip, int(time.time()), capacity=10, window_seconds=60)
                failure = (HTTPException(401, 'Invalid or expired account session') if allowed else
                           HTTPException(429, 'Too many account authentication attempts', headers={'Retry-After': str(retry)}))
            else:
                allowed, retry = take_limit(conn, 'account-request', user['username'], int(time.time()), capacity=240, window_seconds=60)
        # Raise after committing so repeated invalid sessions cannot roll back their limit.
        if failure is not None:
            raise failure
        if not allowed:
            raise HTTPException(429, 'Account request limit exceeded', headers={'Retry-After': str(retry)})
        if caregiver and user['role'] != 'caregiver':
            raise HTTPException(403, 'Caregiver role is required')
        with connect(target) as conn:
            if device_id is not None and not account_auth.can_access(conn, user, device_id):
                raise HTTPException(403, 'Account is not assigned to this device')
        return user

    @application.post('/accounts/login')
    async def login(request: Request):
        ip = request.client.host if request.client else 'unknown'
        # Commit the limiter before credential checks so denial cannot roll it back.
        with connect(target) as conn, transaction(conn):
            allowed, retry = take_limit(conn, 'account-login', ip, int(time.time()), capacity=10, window_seconds=60)
        if not allowed:
            raise HTTPException(429, 'Too many login attempts', headers={'Retry-After': str(retry)})
        body = await read_object(request)
        if set(body) != {'username', 'password'} or not isinstance(body['username'], str) or not isinstance(body['password'], str) or not 1 <= len(body['username']) <= 64 or not 1 <= len(body['password']) <= 128:
            raise HTTPException(400, 'Username and password are required')
        with connect(target) as conn:
            credentials = account_auth.login_credentials(conn, body['username'])
        verified = await run_in_threadpool(account_auth.verify_password, credentials, body['password'])
        if not verified:
            raise HTTPException(401, 'Invalid username or password')
        with connect(target) as conn, transaction(conn):
            result = account_auth.issue_session(conn, credentials)
        if result is None:
            raise HTTPException(401, 'Invalid username or password')
        return result

    @application.get('/accounts/me')
    def me(request: Request):
        user = authenticate(request)
        with connect(target) as conn, transaction(conn):
            user = authorize_action(conn, user)
            devices = account_auth.devices_for(conn, user)
        return {'username': user['username'], 'role': user['role'], 'devices': devices,
                'can_acknowledge_alerts': user['role'] == 'caregiver'}

    @application.post('/accounts/logout')
    def logout(request: Request):
        user = authenticate(request)
        with connect(target) as conn, transaction(conn):
            user = authorize_action(conn, user)
            conn.execute('UPDATE cm_user_sessions SET revoked=1 WHERE token_hash=?', (user['token_hash'],))
        return {'status': 'signed_out'}

    @application.get('/accounts/readings')
    def readings(request: Request, device_id: str, limit: int = Query(20, ge=1, le=100)):
        user = authenticate(request, device_id)
        with connect(target) as conn, transaction(conn):
            patient_id = resolve_current_patient(conn, user, device_id)
            rows = conn.execute('SELECT id,received_at,sensor_timestamp,payload_json FROM cm_readings WHERE device_id=? AND patient_id=? ORDER BY id DESC LIMIT ?', (device_id, patient_id, limit)).fetchall()
        now = stored_classification.utc_now()
        return {'device_id': device_id, 'server_time': stored_classification.iso(now),
                'readings': [stored_classification.reading_view(row, now) for row in rows]}

    @application.get('/accounts/classification')
    def classification(request: Request, device_id: str, reading_id: int | None = Query(None, ge=1, le=2**63-1)):
        user = authenticate(request, device_id)
        with connect(target) as conn, transaction(conn):
            patient_id = resolve_current_patient(conn, user, device_id)
            sql = 'SELECT id,received_at,sensor_timestamp,payload_json FROM cm_readings WHERE device_id=? AND patient_id=?'
            args = [device_id, patient_id]
            if reading_id is not None:
                sql += ' AND id=?'
                args.append(reading_id)
            row = conn.execute(sql + ' ORDER BY id DESC LIMIT 1', args).fetchone()
            if reading_id is not None and row is None:
                raise HTTPException(404, 'Reading not found')
        return stored_classification.classify_row(device_id, row, application.state.classifier)

    @application.get('/accounts/alerts')
    def alerts(request: Request, device_id: str, after_revision: int = Query(0, ge=0, le=2**63-1), limit: int = Query(100, ge=1, le=100)):
        user = authenticate(request, device_id)
        with connect(target) as conn, transaction(conn):
            patient_id = resolve_current_patient(conn, user, device_id)
            rows = conn.execute('SELECT * FROM cm_alerts WHERE device_id=? AND patient_id=? AND revision>? ORDER BY revision ASC LIMIT ?',
                                (device_id, patient_id, after_revision, limit + 1)).fetchall()
            page = rows[:limit]
            return {'device_id': device_id, 'alerts': [alert_engine._alert(dict(row)) for row in page],
                    'next_revision': page[-1]['revision'] if page else after_revision, 'has_more': len(rows) > limit}

    @application.post('/accounts/alerts/{alert_id}/acknowledge')
    async def acknowledge(request: Request, alert_id: int = PathParameter(ge=1, le=2**63-1)):
        body = await read_object(request)
        if set(body) != {'device_id'} or not isinstance(body['device_id'], str):
            raise HTTPException(400, 'A device_id is required')
        user = authenticate(request, body['device_id'], caregiver=True)
        with connect(target) as conn, transaction(conn):
            user = authorize_action(conn, user, body['device_id'], caregiver=True)
            patient_id = resolve_current_patient(conn, user, body['device_id'], caregiver=True)
            if not conn.execute('SELECT 1 FROM cm_alerts WHERE id=? AND device_id=? AND patient_id=?',
                                (alert_id, body['device_id'], patient_id)).fetchone():
                raise HTTPException(404, 'Alert not found')
            try:
                entry = alert_engine.acknowledge(conn, alert_id, body['device_id'], utc_timestamp())
            except (KeyError, LookupError):
                raise HTTPException(404, 'Alert not found') from None
            if entry is None:
                raise HTTPException(404, 'Alert not found')
            audit(conn, 'account_alert_acknowledged', user['username'], body['device_id'], 'ok', int(time.time()))
        return {'status': 'acknowledged', 'alert': entry}

    return authenticate
