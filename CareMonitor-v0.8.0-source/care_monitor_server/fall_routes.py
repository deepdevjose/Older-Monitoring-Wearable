"""Candidate fall-response API. Manual fixtures are explicit; no fall classifier is implied."""
from datetime import datetime, timezone

from fastapi import HTTPException, Path as PathParameter, Query, Request

from . import fall_events
from .account_routes import authorize_action
from .database import connect, transaction


def now():
    return datetime.now(timezone.utc)


def register(application, target, read_object, authenticate):
    def binding(conn, user, device_id, *, patient=False, caregiver=False):
        current = authorize_action(conn, user, device_id, caregiver=caregiver)
        if patient and current['role'] != 'patient':
            raise HTTPException(403, 'The wearer account is required')
        mapped = conn.execute('SELECT patient_id FROM cm_devices WHERE device_id=?', (device_id,)).fetchone()
        if mapped is None:
            raise HTTPException(403, 'Device is not assigned')
        return current, mapped['patient_id']

    def call(operation, *args, **kwargs):
        try:
            return operation(*args, **kwargs)
        except fall_events.FallNotFound:
            raise HTTPException(404, 'Fall event not found for this account') from None
        except fall_events.FallConflict:
            raise HTTPException(409, 'Fall event or state conflicts with an existing record') from None
        except (ValueError, OverflowError):
            raise HTTPException(400, 'Invalid fall event fields or detection timestamp') from None

    @application.post('/accounts/fall-events')
    async def create(request: Request):
        user = authenticate(request)
        body = await read_object(request)
        if set(body) != {'event_id', 'device_id', 'detected_at', 'source'} or body['source'] not in ('manual_test', 'wearer_help') or any(not isinstance(body[k], str) for k in body):
            raise HTTPException(400, 'Provide event_id, device_id, detected_at and source manual_test or wearer_help')
        with connect(target) as conn, transaction(conn):
            _, patient_id = binding(conn, user, body['device_id'], patient=True)
            received = now()
            event = call(fall_events.create_event, conn, event_id=body['event_id'], device_id=body['device_id'],
                         patient_id=patient_id, detected_at=body['detected_at'], now=received,
                         payload={'source': body['source'], 'is_test': body['source'] == 'manual_test',
                                  'live_fall_detector': False})
            if body['source'] == 'wearer_help':
                event = call(fall_events.request_help, conn, event_id=event['event_id'], device_id=event['device_id'], patient_id=patient_id, now=received)
        return {'event': event, 'push_configured': False}

    @application.get('/accounts/fall-events/{event_id}')
    def get_event(event_id: str, request: Request, device_id: str):
        user = authenticate(request)
        with connect(target) as conn, transaction(conn):
            _, patient_id = binding(conn, user, device_id)
            event = call(fall_events.get_event, conn, event_id)
            if event['device_id'] != device_id or event['patient_id'] != patient_id:
                raise HTTPException(404, 'Fall event not found for this account')
        return {'event': event, 'server_time': now().isoformat(), 'push_configured': False}

    async def mutate(request, event_id, operation, *, caregiver=False):
        user = authenticate(request)
        body = await read_object(request)
        if set(body) != {'device_id'} or not isinstance(body['device_id'], str):
            raise HTTPException(400, 'A device_id is required')
        with connect(target) as conn, transaction(conn):
            current, patient_id = binding(conn, user, body['device_id'], patient=not caregiver, caregiver=caregiver)
            options = {'resolved_by': current['username']} if caregiver else {}
            event = call(operation, conn, event_id=event_id, device_id=body['device_id'],
                         patient_id=patient_id, now=now(), **options)
        return {'event': event, 'push_configured': False}

    @application.post('/accounts/fall-events/{event_id}/cancel')
    async def cancel(event_id: str, request: Request):
        return await mutate(request, event_id, fall_events.cancel_event)

    @application.post('/accounts/fall-events/{event_id}/help')
    async def help_now(event_id: str, request: Request):
        return await mutate(request, event_id, fall_events.request_help)

    @application.post('/accounts/fall-events/{event_id}/resolve')
    async def resolve(event_id: str, request: Request):
        return await mutate(request, event_id, fall_events.resolve_event, caregiver=True)

    @application.get('/accounts/notifications')
    def notifications(request: Request, after_id: int = Query(0, ge=0), limit: int = Query(100, ge=1, le=100)):
        user = authenticate(request, caregiver=True)
        with connect(target) as conn, transaction(conn):
            current = authorize_action(conn, user, caregiver=True)
            rows = conn.execute('''SELECT o.id,o.event_id,o.status,o.created_at,o.delivered_at,
                e.device_id,e.patient_id,e.detected_at,e.deadline_at,e.status AS event_status,e.payload_json
                FROM cm_fall_outbox o JOIN cm_fall_events e ON e.event_id=o.event_id
                JOIN cm_caregiver_assignments a ON a.patient_id=e.patient_id AND a.username=o.recipient_username
                JOIN cm_devices d ON d.device_id=e.device_id AND d.patient_id=e.patient_id
                WHERE o.recipient_username=? AND o.id>? ORDER BY o.id LIMIT ?''',
                (current['username'], after_id, limit)).fetchall()
        return {'notifications': [dict(row) for row in rows], 'push_configured': False,
                'delivery_meaning': 'Database inbox only; queued does not mean a phone received a push'}

    @application.post('/accounts/notifications/{notification_id}/seen')
    def seen(request: Request, notification_id: int = PathParameter(ge=1)):
        user = authenticate(request, caregiver=True)
        with connect(target) as conn, transaction(conn):
            current = authorize_action(conn, user, caregiver=True)
            row = conn.execute('''SELECT o.*,e.device_id,e.patient_id FROM cm_fall_outbox o
                JOIN cm_fall_events e ON e.event_id=o.event_id WHERE o.id=? AND o.recipient_username=?''',
                (notification_id, current['username'])).fetchone()
            if row is None:
                raise HTTPException(404, 'Notification not found')
            _, patient_id = binding(conn, current, row['device_id'], caregiver=True)
            if row['patient_id'] != patient_id:
                raise HTTPException(404, 'Notification not found')
            call(fall_events.mark_delivered, conn, outbox_id=notification_id, now=now(),
                 delivery_reference='authenticated-caregiver-app-seen')
        return {'status': 'seen_by_caregiver', 'event_resolved': False}

    @application.get('/accounts/response-system')
    def status(request: Request):
        authenticate(request)
        return {'fall_detector_available': False, 'countdown_seconds': 15, 'push_configured': False,
                'worker': dict(application.state.fall_worker_status)}
