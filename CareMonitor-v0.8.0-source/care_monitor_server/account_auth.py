"""Candidate-only patient/caregiver accounts; no public registration or default passwords."""
from __future__ import annotations

import hashlib
import re
import secrets
import sqlite3
import time

ITERATIONS = 600_000
SESSION_SECONDS = 1800
USERNAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{2,63}\Z")
SESSION = re.compile(r"cmu_[A-Za-z0-9_-]{43}\Z")


def initialise(conn: sqlite3.Connection) -> None:
    conn.execute("""CREATE TABLE IF NOT EXISTS cm_users (
        username TEXT PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('patient','caregiver')),
        patient_id TEXT, salt TEXT NOT NULL, password_hash TEXT NOT NULL,
        iterations INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1,
        CHECK((role='patient' AND patient_id IS NOT NULL) OR
              (role='caregiver' AND patient_id IS NULL)))""")
    conn.execute("""CREATE TABLE IF NOT EXISTS cm_caregiver_assignments (
        username TEXT NOT NULL REFERENCES cm_users(username), patient_id TEXT NOT NULL,
        PRIMARY KEY(username,patient_id))""")
    conn.execute("""CREATE TABLE IF NOT EXISTS cm_user_sessions (
        token_hash TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES cm_users(username),
        expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)""")
    conn.execute("CREATE INDEX IF NOT EXISTS cm_user_sessions_user ON cm_user_sessions(username)")


def password_hash(password: str, salt: str, iterations: int = ITERATIONS) -> str:
    return hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), bytes.fromhex(salt), iterations).hex()


def create_user(conn, *, username: str, password: str, role: str, patient_id: str | None = None) -> None:
    if not isinstance(username, str) or not USERNAME.fullmatch(username):
        raise ValueError('Username must contain 3-64 letters, numbers, dots, underscores or hyphens')
    if not isinstance(password, str) or not 12 <= len(password) <= 128:
        raise ValueError('Password must contain 12-128 characters')
    if role not in ('patient', 'caregiver'):
        raise ValueError('Choose patient or caregiver')
    if role == 'patient':
        if not isinstance(patient_id, str) or not conn.execute(
                'SELECT 1 FROM cm_devices WHERE patient_id=?', (patient_id,)).fetchone():
            raise ValueError('Patient must already have an assigned device')
    elif patient_id is not None:
        raise ValueError('Assign caregivers to patients separately')
    salt = secrets.token_hex(16)
    conn.execute('INSERT INTO cm_users(username,role,patient_id,salt,password_hash,iterations) VALUES (?,?,?,?,?,?)',
                 (username, role, patient_id, salt, password_hash(password, salt), ITERATIONS))


def assign_caregiver(conn, username: str, patient_id: str) -> None:
    user = conn.execute('SELECT role,active FROM cm_users WHERE username=?', (username,)).fetchone()
    if not user or user['role'] != 'caregiver' or not user['active']:
        raise ValueError('An active caregiver account is required')
    if not conn.execute('SELECT 1 FROM cm_devices WHERE patient_id=?', (patient_id,)).fetchone():
        raise ValueError('Unknown patient')
    conn.execute('INSERT OR IGNORE INTO cm_caregiver_assignments VALUES (?,?)', (username, patient_id))


def login_credentials(conn, username: str):
    """Read a detached verification snapshot; callers close the connection before the KDF."""
    row = conn.execute('SELECT username,salt,password_hash,iterations,active FROM cm_users WHERE username=?',
                       (username,)).fetchone()
    return dict(row) if row is not None else None


def verify_password(credentials, password: str) -> bool:
    """CPU work only: run on a worker thread with no SQLite transaction held."""
    # Unknown names perform the same password KDF; error messages do not enumerate accounts.
    actual = password_hash(password, credentials['salt'] if credentials else '00' * 16,
                           credentials['iterations'] if credentials else ITERATIONS)
    return bool(secrets.compare_digest(actual, credentials['password_hash'] if credentials else '00' * 32)
                and credentials and credentials['active'])


def issue_session(conn, verified_credentials, now: int | None = None):
    """Recheck the verified snapshot inside the short session-creation transaction."""
    if verified_credentials is None:
        return None
    now = int(time.time()) if now is None else now
    row = conn.execute('SELECT * FROM cm_users WHERE username=?',
                       (verified_credentials['username'],)).fetchone()
    if (not row or not row['active'] or row['salt'] != verified_credentials['salt']
            or row['iterations'] != verified_credentials['iterations']
            or not secrets.compare_digest(row['password_hash'], verified_credentials['password_hash'])):
        return None
    token = 'cmu_' + secrets.token_urlsafe(32)
    conn.execute('DELETE FROM cm_user_sessions WHERE expires_at<=? OR revoked=1', (now,))
    conn.execute('INSERT INTO cm_user_sessions VALUES (?,?,?,0)',
                 (hashlib.sha256(token.encode()).hexdigest(), row['username'], now + SESSION_SECONDS))
    return {'access_token': token, 'token_type': 'Bearer', 'expires_in': SESSION_SECONDS,
            'username': row['username'], 'role': row['role']}


def lookup_session(conn, token: str | None, now: int | None = None):
    now = int(time.time()) if now is None else now
    if not isinstance(token, str) or not SESSION.fullmatch(token):
        return None
    return conn.execute('''SELECT u.username,u.role,u.patient_id,s.token_hash,s.expires_at FROM cm_users u
        JOIN cm_user_sessions s ON s.username=u.username
        WHERE s.token_hash=? AND s.expires_at>? AND s.revoked=0 AND u.active=1''',
        (hashlib.sha256(token.encode()).hexdigest(), now)).fetchone()


def devices_for(conn, user) -> list[dict]:
    if user['role'] == 'patient':
        rows = conn.execute('SELECT * FROM cm_devices WHERE patient_id=? ORDER BY device_id', (user['patient_id'],))
    else:
        rows = conn.execute('''SELECT d.* FROM cm_devices d JOIN cm_caregiver_assignments a
            ON a.patient_id=d.patient_id WHERE a.username=? ORDER BY d.device_id''', (user['username'],))
    return [dict(row) for row in rows]


def can_access(conn, user, device_id: str) -> bool:
    return current_patient(conn, user, device_id) is not None


def current_patient(conn, user, device_id: str) -> str | None:
    """Return the assigned patient, never the device's previous owners."""
    mapped = conn.execute('SELECT patient_id FROM cm_devices WHERE device_id=?', (device_id,)).fetchone()
    if mapped is None:
        return None
    patient_id = mapped['patient_id']
    if user['role'] == 'patient':
        return patient_id if patient_id == user['patient_id'] else None
    if user['role'] == 'caregiver' and conn.execute(
            'SELECT 1 FROM cm_caregiver_assignments WHERE username=? AND patient_id=?',
            (user['username'], patient_id)).fetchone():
        return patient_id
    return None


def revalidate(conn, user, now: int | None = None):
    """Reload current identity inside the transaction that performs the protected action."""
    now = int(time.time()) if now is None else now
    return conn.execute('''SELECT u.username,u.role,u.patient_id,s.token_hash,s.expires_at FROM cm_users u
        JOIN cm_user_sessions s ON s.username=u.username WHERE s.token_hash=?
        AND s.expires_at>? AND s.revoked=0 AND u.active=1''', (user['token_hash'], now)).fetchone()
