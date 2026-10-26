"""Runtime-issued phone credentials. No plaintext credentials are persisted."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import re
import secrets
import sqlite3
import time

SCOPES = frozenset({"sensor:write", "sensor:read", "alerts:read", "alerts:ack"})
DEFAULT_EXPIRY_DAYS = 7
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z")
TOKEN = re.compile(r"([A-Za-z0-9_-]{12})\.([A-Za-z0-9_-]{43})\Z")
DUMMY_DIGEST = hashlib.sha256(b"care-monitor-no-such-credential").hexdigest()


def utc_timestamp(seconds: float | int | None = None) -> str:
    return datetime.fromtimestamp(time.time() if seconds is None else seconds,
                                  timezone.utc).isoformat(timespec="milliseconds")


@dataclass(frozen=True)
class Credential:
    key_id: str
    device_id: str
    scopes: frozenset[str]
    expires_at: int


def audit(conn: sqlite3.Connection, event: str, key_id: str | None,
          device_id: str | None, result: str, now: int) -> None:
    """Only fixed event names and non-secret IDs; never headers or payloads."""
    conn.execute("INSERT INTO cm_audit(occurred_at,event,key_id,device_id,result) VALUES (?,?,?,?,?)",
                 (now, event, key_id, device_id, result))


def issue(conn: sqlite3.Connection, *, label: str, device_id: str = "elder-demo-01",
          patient_id: str | None = None, scopes: set[str] | frozenset[str] = frozenset({"sensor:write"}),
          days: int = DEFAULT_EXPIRY_DAYS, now: int | None = None) -> tuple[str, Credential]:
    """Return a new token once. Caller commits the transaction before showing it."""
    issued = int(time.time()) if now is None else now
    if not isinstance(days, int) or isinstance(days, bool) or not 1 <= days <= 90:
        raise ValueError("Expiry must be between 1 and 90 days")
    if not label.strip() or len(label) > 80 or any(ord(c) < 32 for c in label):
        raise ValueError("A printable label of 1 to 80 characters is required")
    if not IDENTIFIER.fullmatch(device_id):
        raise ValueError("Invalid device identifier")
    if not scopes or not scopes.issubset(SCOPES):
        raise ValueError("Choose at least one supported scope")
    mapped = conn.execute("SELECT patient_id FROM cm_devices WHERE device_id=?", (device_id,)).fetchone()
    if mapped is None:
        if not patient_id or not IDENTIFIER.fullmatch(patient_id):
            raise ValueError("A patient identifier is required for a new device")
        conn.execute("INSERT INTO cm_devices VALUES (?,?)", (device_id, patient_id))
    elif patient_id is not None and patient_id != mapped[0]:
        raise ValueError("Device already has a different patient mapping")
    key_id = secrets.token_urlsafe(9)
    secret = secrets.token_urlsafe(32)
    token = key_id + "." + secret
    digest = hashlib.sha256(token.encode("ascii")).hexdigest()
    expiry = issued + days * 86400
    conn.execute("""INSERT INTO cm_credentials
        (key_id,digest,label,device_id,scopes_json,created_at,expires_at)
        VALUES (?,?,?,?,?,?,?)""", (key_id,digest,label,device_id,json.dumps(sorted(scopes)),issued,expiry))
    audit(conn, "credential_created", key_id, device_id, "ok", issued)
    return token, Credential(key_id, device_id, frozenset(scopes), expiry)


def lookup(conn: sqlite3.Connection, token: str | None, now: int) -> Credential | None:
    # Hash and constant-time compare even for an unknown ID. Never surface which test failed.
    bounded = token if isinstance(token, str) and len(token) <= 128 else ""
    match = TOKEN.fullmatch(bounded)
    row = conn.execute("SELECT * FROM cm_credentials WHERE key_id=?", (match[1],)).fetchone() if match else None
    candidate = hashlib.sha256(bounded.encode("utf-8")).hexdigest()
    matches = secrets.compare_digest(candidate, row["digest"] if row else DUMMY_DIGEST)
    if row is None or not matches or row["revoked_at"] is not None or row["expires_at"] <= now:
        return None
    return Credential(row["key_id"], row["device_id"], frozenset(json.loads(row["scopes_json"])), row["expires_at"])


def revoke(conn: sqlite3.Connection, key_id: str, *, now: int | None = None) -> bool:
    stamp = int(time.time()) if now is None else now
    row = conn.execute("SELECT device_id FROM cm_credentials WHERE key_id=?", (key_id,)).fetchone()
    if row is None:
        return False
    conn.execute("UPDATE cm_credentials SET revoked_at=COALESCE(revoked_at,?) WHERE key_id=?", (stamp,key_id))
    audit(conn, "credential_revoked", key_id, row[0], "ok", stamp)
    return True


def take_limit(conn: sqlite3.Connection, category: str, subject: str, now: int,
               *, capacity: int, window_seconds: int) -> tuple[bool, int]:
    """Fixed-window, SQLite-persisted limiter. Caller must BEGIN IMMEDIATE."""
    start = (now // window_seconds) * window_seconds
    retry = window_seconds - (now - start)
    conn.execute("""INSERT INTO cm_rate_limits(category,subject,window_start,count)
        VALUES (?,?,?,1) ON CONFLICT(category,subject) DO UPDATE SET
        count=CASE WHEN window_start=excluded.window_start THEN MIN(count+1,?) ELSE 1 END,
        window_start=excluded.window_start""", (category,subject,start,capacity+1))
    count = conn.execute("SELECT count FROM cm_rate_limits WHERE category=? AND subject=?", (category,subject)).fetchone()[0]
    # Expired bucket cleanup bounds disk growth from changing unauthenticated IPs.
    conn.execute("DELETE FROM cm_rate_limits WHERE window_start < ?", (start - max(3600,window_seconds*2),))
    return count <= capacity, retry


def failed_limit_active(conn: sqlite3.Connection, ip: str, now: int, *, capacity: int, window_seconds: int) -> bool:
    start = (now // window_seconds) * window_seconds
    row = conn.execute("SELECT count FROM cm_rate_limits WHERE category='failed-auth' AND subject=? AND window_start=?",
                       (ip,start)).fetchone()
    return row is not None and row[0] >= capacity
