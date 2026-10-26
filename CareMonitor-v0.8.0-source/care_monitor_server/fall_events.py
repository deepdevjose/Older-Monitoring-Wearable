"""Candidate-only possible-fall response workflow, not a fall detector.

The caller supplies a trustworthy, timezone-aware detection time. The deadline
is 15 seconds after that time, not 15 seconds after a delayed upload. Device and
server clocks must be synchronised. Root routes supply authentication; this
module binds records to registered devices and assigned active caregivers.

Mutations own an IMMEDIATE transaction. Inside a caller transaction they use a
savepoint and acquire the write lock before reading. A queued outbox record is
not evidence of notification delivery. No push transport is implemented here.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import re
import sqlite3
import uuid

COUNTDOWN_SECONDS = 15
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


class FallConflict(ValueError):
    """The identifier, binding, or requested state transition conflicts."""


class FallNotFound(LookupError):
    """No event/outbox record exists for the requested scope."""


def _time(value, name="now"):
    if isinstance(value, str):
        if len(value) > 80:
            raise ValueError(f"{name} must be an aware ISO timestamp")
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError(f"{name} must be an aware ISO timestamp") from None
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{name} must be a timezone-aware ISO timestamp")
    try:
        value = value.astimezone(timezone.utc)
    except (OverflowError, ValueError):
        raise ValueError(f"{name} is outside the supported UTC range") from None
    if value < EPOCH:
        raise ValueError(f"{name} cannot precede the Unix epoch")
    delta = value - EPOCH
    micros = (delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds
    return value, value.isoformat(timespec="microseconds"), micros


def _identifier(value, name):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError(f"Invalid {name}")
    return value


def _payload(value):
    if value is None:
        value = {}
    if not isinstance(value, dict):
        raise ValueError("payload must be a JSON object")
    try:
        result = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError, RecursionError):
        raise ValueError("payload must contain finite JSON values") from None
    if len(result.encode("utf-8")) > 8192:
        raise ValueError("payload is too large")
    return result


def _one(conn, sql, params=()):
    cursor = conn.execute(sql, params)
    row = cursor.fetchone()
    return None if row is None else dict(zip([column[0] for column in cursor.description], row))


def _many(conn, sql, params=()):
    cursor = conn.execute(sql, params)
    names = [column[0] for column in cursor.description]
    return [dict(zip(names, row)) for row in cursor.fetchall()]


@contextmanager
def _atomic(conn):
    nested = conn.in_transaction
    savepoint = "fall_" + uuid.uuid4().hex
    if nested:
        conn.execute("SAVEPOINT " + savepoint)
    else:
        conn.execute("BEGIN IMMEDIATE")
    try:
        if nested:
            # Force a write lock before any state/deadline decisions, even if
            # the surrounding transaction was deferred rather than IMMEDIATE.
            conn.execute("UPDATE cm_fall_lock SET singleton=singleton WHERE singleton=1")
        yield
    except BaseException:
        if nested:
            conn.execute("ROLLBACK TO " + savepoint)
            conn.execute("RELEASE " + savepoint)
        else:
            conn.rollback()
        raise
    else:
        if nested:
            conn.execute("RELEASE " + savepoint)
        else:
            conn.commit()


def initialize(conn: sqlite3.Connection) -> None:
    """Add tables; call after cm_devices and account_auth.initialise exist."""
    conn.execute("CREATE TABLE IF NOT EXISTS cm_fall_lock (singleton INTEGER PRIMARY KEY CHECK(singleton=1))")
    conn.execute("INSERT OR IGNORE INTO cm_fall_lock VALUES (1)")
    conn.execute("""CREATE TABLE IF NOT EXISTS cm_fall_events (
        event_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES cm_devices(device_id),
        patient_id TEXT NOT NULL, detected_at TEXT NOT NULL, received_at TEXT NOT NULL,
        deadline_at TEXT NOT NULL, detected_us INTEGER NOT NULL, received_us INTEGER NOT NULL,
        deadline_us INTEGER NOT NULL, receipt_latency_ms REAL NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('pending','cancelled','escalated','resolved')),
        payload_json TEXT NOT NULL, updated_at TEXT NOT NULL, updated_us INTEGER NOT NULL,
        cancelled_at TEXT, escalated_at TEXT, escalation_reason TEXT,
        resolved_at TEXT, resolved_by TEXT,
        CHECK(deadline_us=detected_us+15000000), CHECK(received_us>=detected_us))""")
    conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS cm_one_unresolved_fall_per_device
        ON cm_fall_events(device_id) WHERE status IN ('pending','escalated')""")
    conn.execute("CREATE INDEX IF NOT EXISTS cm_fall_deadline ON cm_fall_events(status,deadline_us)")
    conn.execute("""CREATE TRIGGER IF NOT EXISTS cm_fall_block_active_reassignment
        BEFORE UPDATE OF patient_id ON cm_devices
        WHEN NEW.patient_id<>OLD.patient_id AND EXISTS (
            SELECT 1 FROM cm_fall_events WHERE device_id=OLD.device_id
            AND status IN ('pending','escalated'))
        BEGIN
            SELECT RAISE(ABORT,'Cannot reassign device with an unresolved fall event');
        END""")
    conn.execute("""CREATE TABLE IF NOT EXISTS cm_fall_outbox (
        id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL REFERENCES cm_fall_events(event_id),
        recipient_username TEXT NOT NULL REFERENCES cm_users(username),
        status TEXT NOT NULL CHECK(status IN ('queued','retry','delivered')),
        created_at TEXT NOT NULL, created_us INTEGER NOT NULL, updated_at TEXT NOT NULL,
        updated_us INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
        next_attempt_at TEXT NOT NULL, next_attempt_us INTEGER NOT NULL,
        delivered_at TEXT, delivery_reference TEXT, last_error TEXT,
        UNIQUE(event_id,recipient_username))""")


initialise = initialize


def _event(conn, event_id, device_id=None, patient_id=None):
    _identifier(event_id, "event_id")
    row = _one(conn, "SELECT * FROM cm_fall_events WHERE event_id=?", (event_id,))
    if row is None or (device_id is not None and row["device_id"] != device_id) or (patient_id is not None and row["patient_id"] != patient_id):
        raise FallNotFound("Fall event not found for this device/patient")
    return row


def _current_binding(conn, row):
    return conn.execute("SELECT 1 FROM cm_devices WHERE device_id=? AND patient_id=?",
                        (row["device_id"], row["patient_id"])).fetchone() is not None


def _readiness(conn, row):
    recipients = _many(conn, """SELECT u.username,o.status FROM cm_users u
        JOIN cm_caregiver_assignments a ON a.username=u.username
        LEFT JOIN cm_fall_outbox o ON o.recipient_username=u.username AND o.event_id=?
        WHERE a.patient_id=? AND u.role='caregiver' AND u.active=1""", (row["event_id"], row["patient_id"]))
    counts = {
        "active_assigned_caregivers": len(recipients),
        "queued_recipients": sum(recipient["status"] == "queued" for recipient in recipients),
        "retry_recipients": sum(recipient["status"] == "retry" for recipient in recipients),
        "acknowledged_recipients": sum(recipient["status"] == "delivered" for recipient in recipients),
        "unqueued_recipients": sum(recipient["status"] is None for recipient in recipients),
    }
    if not _current_binding(conn, row):
        status = "device_binding_changed"
    elif row["status"] in ("cancelled", "resolved"):
        status = "inactive_event"
    elif not recipients:
        status = "no_active_assigned_caregiver"
    elif row["status"] == "pending":
        status = "countdown_pending"
    elif counts["unqueued_recipients"]:
        status = "awaiting_queue"
    elif counts["queued_recipients"] or counts["retry_recipients"]:
        status = "queued_for_caregiver"
    else:
        status = "recipient_acknowledged"
    return {"status": status, **counts, "push_delivery_verified": False}


def notification_readiness(conn, event_id):
    """Report assignment/queue state without implying push delivery."""
    return _readiness(conn, _event(conn, event_id))


def _public(row, conn):
    result = {key: value for key, value in row.items() if not key.endswith("_us") and key != "payload_json"}
    result["payload"] = json.loads(row["payload_json"])
    result["countdown_seconds"] = COUNTDOWN_SECONDS
    result["detector_validated"] = False
    result["notification_readiness"] = _readiness(conn, row)
    return result


def _clock(row, micros):
    if micros < row["received_us"] or micros < row["updated_us"]:
        raise ValueError("now cannot precede the event receipt or last state change")


def _queue(conn, row, stamp, micros):
    if not _current_binding(conn, row):
        return 0
    caregivers = conn.execute("""SELECT u.username FROM cm_users u
        JOIN cm_caregiver_assignments a ON a.username=u.username
        WHERE a.patient_id=? AND u.role='caregiver' AND u.active=1 ORDER BY u.username""", (row["patient_id"],)).fetchall()
    inserted = 0
    for caregiver in caregivers:
        result = conn.execute("""INSERT OR IGNORE INTO cm_fall_outbox
            (event_id,recipient_username,status,created_at,created_us,updated_at,updated_us,next_attempt_at,next_attempt_us)
            VALUES (?,?,'queued',?,?,?,?,?,?)""", (row["event_id"], caregiver[0], stamp, micros, stamp, micros, stamp, micros))
        inserted += result.rowcount
    return inserted


def _escalate(conn, row, stamp, micros, reason):
    if row["status"] == "pending":
        conn.execute("""UPDATE cm_fall_events SET status='escalated',escalated_at=?,escalation_reason=?,
            updated_at=?,updated_us=? WHERE event_id=? AND status='pending'""",
                     (stamp, reason, stamp, micros, row["event_id"]))
        row = _event(conn, row["event_id"])
        _queue(conn, row, stamp, micros)
    return row


def _advance(conn, row, stamp, micros):
    _clock(row, micros)
    if row["status"] == "pending" and micros >= row["deadline_us"]:
        row = _escalate(conn, row, stamp, micros, "countdown_expired")
    return row


def get_event(conn, event_id):
    """Read-only snapshot; root endpoint must enforce patient/device scope."""
    return _public(_event(conn, event_id), conn)


def create_event(conn, *, event_id, device_id, patient_id, detected_at, now, payload=None):
    for value, name in [(event_id, "event_id"), (device_id, "device_id"), (patient_id, "patient_id")]:
        _identifier(value, name)
    detected, detected_stamp, detected_us = _time(detected_at, "detected_at")
    _, stamp, micros = _time(now)
    if detected_us > micros:
        raise ValueError("Future detected_at is invalid; synchronise device/server clocks")
    try:
        _, deadline_stamp, deadline_us = _time(detected + timedelta(seconds=COUNTDOWN_SECONDS), "deadline_at")
    except OverflowError:
        raise ValueError("Detection time cannot represent the deadline") from None
    payload_json = _payload(payload)
    with _atomic(conn):
        binding = _one(conn, "SELECT patient_id FROM cm_devices WHERE device_id=?", (device_id,))
        if binding is None or binding["patient_id"] != patient_id:
            raise FallConflict("Unknown device or mismatched patient assignment")
        existing = _one(conn, "SELECT * FROM cm_fall_events WHERE event_id=?", (event_id,))
        if existing:
            expected = (device_id, patient_id, detected_us, payload_json)
            actual = tuple(existing[key] for key in ["device_id", "patient_id", "detected_us", "payload_json"])
            if actual != expected:
                raise FallConflict("event_id already identifies a different immutable event")
            existing = _advance(conn, existing, stamp, micros)
            return {**_public(existing, conn), "duplicate": True}
        unresolved = _one(conn, "SELECT event_id FROM cm_fall_events WHERE device_id=? AND status IN ('pending','escalated')", (device_id,))
        if unresolved:
            raise FallConflict("An unresolved fall episode already exists: " + unresolved["event_id"])
        conn.execute("""INSERT INTO cm_fall_events
            (event_id,device_id,patient_id,detected_at,received_at,deadline_at,detected_us,received_us,deadline_us,
             receipt_latency_ms,status,payload_json,updated_at,updated_us)
            VALUES (?,?,?,?,?,?,?,?,?,?,'pending',?,?,?)""",
                     (event_id, device_id, patient_id, detected_stamp, stamp, deadline_stamp, detected_us, micros,
                      deadline_us, (micros - detected_us) / 1000, payload_json, stamp, micros))
        row = _advance(conn, _event(conn, event_id), stamp, micros)
        return {**_public(row, conn), "duplicate": False}


def cancel_event(conn, *, event_id, device_id, patient_id, now):
    _, stamp, micros = _time(now)
    with _atomic(conn):
        row = _advance(conn, _event(conn, event_id, device_id, patient_id), stamp, micros)
        if row["status"] == "pending":
            conn.execute("""UPDATE cm_fall_events SET status='cancelled',cancelled_at=?,updated_at=?,updated_us=?
                WHERE event_id=? AND status='pending' AND deadline_us>?""", (stamp, stamp, micros, event_id, micros))
            row = _event(conn, event_id)
        return {**_public(row, conn), "cancellation_accepted": row["status"] == "cancelled"}


def request_help(conn, *, event_id, device_id, patient_id, now):
    _, stamp, micros = _time(now)
    with _atomic(conn):
        row = _event(conn, event_id, device_id, patient_id)
        _clock(row, micros)
        if row["status"] in ("cancelled", "resolved"):
            raise FallConflict("A closed event cannot request help; create a new event")
        row = _escalate(conn, row, stamp, micros, "immediate_help_requested")
        return _public(row, conn)


def sweep_due(conn, now):
    """Expire pending events and backfill newly assigned caregivers durably.

    Original detection, deadline and escalation times are never reset. A new
    assignment can receive an unresolved event; uniqueness prevents duplicates.
    """
    _, stamp, micros = _time(now)
    with _atomic(conn):
        rows = _many(conn, "SELECT * FROM cm_fall_events WHERE status='pending' AND deadline_us<=? ORDER BY deadline_us,event_id", (micros,))
        changed = {row["event_id"]: _advance(conn, row, stamp, micros) for row in rows}
        for row in _many(conn, "SELECT * FROM cm_fall_events WHERE status='escalated' ORDER BY event_id"):
            _clock(row, micros)
            if _queue(conn, row, stamp, micros):
                conn.execute("UPDATE cm_fall_events SET updated_at=?,updated_us=? WHERE event_id=?",
                             (stamp, micros, row["event_id"]))
                changed[row["event_id"]] = _event(conn, row["event_id"])
        return [_public(row, conn) for row in changed.values()]


def resolve_event(conn, *, event_id, device_id, patient_id, now, resolved_by):
    _, stamp, micros = _time(now)
    _identifier(resolved_by, "resolved_by")
    with _atomic(conn):
        row = _advance(conn, _event(conn, event_id, device_id, patient_id), stamp, micros)
        assigned = conn.execute("""SELECT 1 FROM cm_users u JOIN cm_caregiver_assignments a ON u.username=a.username
            WHERE u.username=? AND u.active=1 AND u.role='caregiver' AND a.patient_id=?""", (resolved_by, patient_id)).fetchone()
        if not assigned:
            raise FallConflict("An active assigned caregiver is required to resolve the event")
        if row["status"] not in ("escalated", "resolved"):
            raise FallConflict("Only an escalated event can be resolved")
        if row["status"] == "escalated":
            conn.execute("UPDATE cm_fall_events SET status='resolved',resolved_at=?,resolved_by=?,updated_at=?,updated_us=? WHERE event_id=?",
                         (stamp, resolved_by, stamp, micros, event_id))
        return _public(_event(conn, event_id), conn)


def list_outbox(conn, *, status="queued", limit=100, now=None):
    """Queue inspection, not delivery. Pending rows exclude resolved events.

    status='pending' includes queued and retry rows. With now, only due retries
    are returned. Account routes must filter recipient access separately.
    """
    if status not in ("queued", "retry", "pending", "delivered") or isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 1000:
        raise ValueError("Invalid outbox status or limit")
    states = ("queued", "retry") if status == "pending" else (status,)
    placeholders = ",".join("?" for _ in states)
    sql = """SELECT o.*,e.device_id,e.patient_id,e.detected_at,e.deadline_at,e.escalation_reason
        FROM cm_fall_outbox o JOIN cm_fall_events e ON o.event_id=e.event_id
        JOIN cm_devices d ON d.device_id=e.device_id AND d.patient_id=e.patient_id
        JOIN cm_users u ON o.recipient_username=u.username
        JOIN cm_caregiver_assignments a ON a.username=u.username AND a.patient_id=e.patient_id
        WHERE u.active=1 AND u.role='caregiver' AND o.status IN (""" + placeholders + ")"
    parameters = list(states)
    if status != "delivered":
        sql += " AND e.status='escalated'"
    if now is not None:
        sql += " AND o.next_attempt_us<=?"
        parameters.append(_time(now)[2])
    sql += " ORDER BY o.id LIMIT ?"
    parameters.append(limit)
    return [{key: value for key, value in row.items() if not key.endswith("_us")} for row in _many(conn, sql, parameters)]


def _outbox(conn, outbox_id, micros):
    if isinstance(outbox_id, bool) or not isinstance(outbox_id, int) or outbox_id <= 0:
        raise ValueError("Invalid outbox_id")
    row = _one(conn, "SELECT * FROM cm_fall_outbox WHERE id=?", (outbox_id,))
    if row is None:
        raise FallNotFound("Outbox item not found")
    if micros < row["updated_us"]:
        raise ValueError("now cannot precede the outbox state")
    return row


def mark_retry(conn, *, outbox_id, now, error):
    when, stamp, micros = _time(now)
    if not isinstance(error, str) or not 1 <= len(error) <= 512:
        raise ValueError("A bounded transport error is required")
    with _atomic(conn):
        row = _outbox(conn, outbox_id, micros)
        if row["status"] != "delivered":
            attempts = row["attempts"] + 1
            _, next_stamp, next_us = _time(when + timedelta(seconds=min(300, 2 ** min(attempts, 9))))
            conn.execute("""UPDATE cm_fall_outbox SET status='retry',attempts=?,last_error=?,updated_at=?,updated_us=?,
                next_attempt_at=?,next_attempt_us=? WHERE id=?""", (attempts, error, stamp, micros, next_stamp, next_us, outbox_id))
        return {key: value for key, value in _outbox(conn, outbox_id, micros).items() if not key.endswith("_us")}


def mark_delivered(conn, *, outbox_id, now, delivery_reference):
    """Require recipient device/app acknowledgement; never enqueue/provider acceptance.

    An FCM message ID means provider acceptance, not phone receipt. The caller
    must verify the intended recipient's acknowledgement before this transition.
    The root /accounts/notifications/{id}/seen route authenticates the intended
    recipient's acknowledgement. It does not prove that any push was delivered.
    """
    _, stamp, micros = _time(now)
    if not isinstance(delivery_reference, str) or not 1 <= len(delivery_reference) <= 256:
        raise ValueError("A recipient device/app acknowledgement reference is required")
    with _atomic(conn):
        row = _outbox(conn, outbox_id, micros)
        if row["status"] != "delivered":
            conn.execute("""UPDATE cm_fall_outbox SET status='delivered',attempts=attempts+1,delivered_at=?,
                delivery_reference=?,last_error=NULL,updated_at=?,updated_us=? WHERE id=?""",
                         (stamp, delivery_reference, stamp, micros, outbox_id))
        return {key: value for key, value in _outbox(conn, outbox_id, micros).items() if not key.endswith("_us")}
