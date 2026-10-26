"""Additive, namespaced SQLite storage; never drops legacy lab tables."""
from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
import os
import secrets
import sqlite3
from typing import Iterator


def default_path() -> Path:
    return Path(os.environ.get("CARE_MONITOR_DB", "./data/care_monitor.db")).expanduser()


@contextmanager
def connect(path: Path | str) -> Iterator[sqlite3.Connection]:
    conn = sqlite3.connect(str(path), timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=15000")
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def transaction(conn: sqlite3.Connection) -> Iterator[None]:
    """Serialise writes across processes, including rate and episode state."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        conn.rollback()
        raise
    else:
        conn.commit()


def initialise(path: Path | str) -> None:
    from . import alert_engine

    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with connect(target) as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        with transaction(conn):
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_metadata (
                name TEXT PRIMARY KEY, value TEXT NOT NULL)""")
            conn.execute("INSERT OR IGNORE INTO cm_metadata VALUES ('server_id', ?)",
                         ("cm-" + secrets.token_hex(16),))
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_devices (
                device_id TEXT PRIMARY KEY, patient_id TEXT NOT NULL)""")
            conn.execute("INSERT OR IGNORE INTO cm_devices VALUES (?, ?)",
                         ("elder-demo-01", "demo-patient-001"))
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_credentials (
                key_id TEXT PRIMARY KEY, digest TEXT NOT NULL UNIQUE,
                label TEXT NOT NULL, device_id TEXT NOT NULL,
                scopes_json TEXT NOT NULL, created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL, revoked_at INTEGER,
                FOREIGN KEY(device_id) REFERENCES cm_devices(device_id))""")
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_rate_limits (
                category TEXT NOT NULL, subject TEXT NOT NULL,
                window_start INTEGER NOT NULL, count INTEGER NOT NULL,
                PRIMARY KEY(category, subject))""")
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL, patient_id TEXT NOT NULL,
                received_at TEXT NOT NULL, sensor_timestamp TEXT,
                uptime_ms INTEGER, boot_nonce TEXT, sample_id TEXT,
                payload_json TEXT NOT NULL,
                FOREIGN KEY(device_id) REFERENCES cm_devices(device_id))""")
            conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS cm_readings_identity
                ON cm_readings(device_id, boot_nonce, sample_id)
                WHERE boot_nonce IS NOT NULL AND sample_id IS NOT NULL""")
            conn.execute("CREATE INDEX IF NOT EXISTS cm_readings_device_id ON cm_readings(device_id,id)")
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                occurred_at INTEGER NOT NULL, event TEXT NOT NULL,
                key_id TEXT, device_id TEXT, result TEXT NOT NULL)""")
            alert_engine.initialise(conn)


def server_id(conn: sqlite3.Connection) -> str:
    return str(conn.execute("SELECT value FROM cm_metadata WHERE name='server_id'").fetchone()[0])
