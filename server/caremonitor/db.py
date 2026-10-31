"""SQLite persistence with explicit transactions and no import-time side effects."""
from contextlib import contextmanager
import sqlite3
import time
from .security import Vault

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, email_lookup TEXT NOT NULL UNIQUE, profile TEXT NOT NULL,
 password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('elder','caregiver')),
 created_at INTEGER NOT NULL, educational_ack_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS patients (
 id TEXT PRIMARY KEY REFERENCES users(id), device_id TEXT NOT NULL DEFAULT 'elder-demo-01'
);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS memberships (
 elder_id TEXT NOT NULL REFERENCES patients(id), caregiver_id TEXT NOT NULL REFERENCES users(id),
 approved_at INTEGER NOT NULL, PRIMARY KEY(elder_id, caregiver_id)
);
CREATE TABLE IF NOT EXISTS invitations (
 id TEXT PRIMARY KEY, elder_id TEXT NOT NULL REFERENCES patients(id), code_lookup TEXT NOT NULL,
 expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER, revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS invitations_code ON invitations(code_lookup, expires_at);
CREATE TABLE IF NOT EXISTS link_requests (
 id TEXT PRIMARY KEY, invitation_id TEXT NOT NULL REFERENCES invitations(id),
 elder_id TEXT NOT NULL REFERENCES patients(id), caregiver_id TEXT NOT NULL REFERENCES users(id),
 status TEXT NOT NULL CHECK(status IN ('pending','approved','expired')), created_at INTEGER NOT NULL,
 UNIQUE(invitation_id,caregiver_id)
);
CREATE TABLE IF NOT EXISTS readings (
 id INTEGER PRIMARY KEY AUTOINCREMENT, elder_id TEXT NOT NULL REFERENCES patients(id),
 sample_id TEXT NOT NULL, captured_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
 source TEXT NOT NULL CHECK(source IN ('synthetic','device_diagnostic')),
 envelope TEXT NOT NULL, digest TEXT NOT NULL, UNIQUE(elder_id,sample_id)
);
CREATE INDEX IF NOT EXISTS readings_patient_time ON readings(elder_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS readings_history_source ON readings(elder_id,source,captured_at);
CREATE TABLE IF NOT EXISTS metric_history_rollups (
 elder_id TEXT NOT NULL REFERENCES patients(id), source TEXT NOT NULL,
 hour_start INTEGER NOT NULL, detail TEXT NOT NULL,
 PRIMARY KEY(elder_id,source,hour_start)
);
CREATE TABLE IF NOT EXISTS metric_history_indexed (
 reading_id INTEGER PRIMARY KEY REFERENCES readings(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS metric_history_policy (
 id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS learning_records (
 id TEXT PRIMARY KEY, elder_id TEXT NOT NULL REFERENCES patients(id),
 sample_id TEXT NOT NULL, created_at INTEGER NOT NULL, detail TEXT NOT NULL,
 UNIQUE(elder_id,sample_id)
);
CREATE INDEX IF NOT EXISTS learning_patient_time ON learning_records(elder_id,created_at DESC,id);
CREATE TABLE IF NOT EXISTS alerts (
 id TEXT PRIMARY KEY, elder_id TEXT NOT NULL REFERENCES patients(id), type TEXT NOT NULL,
 created_at INTEGER NOT NULL, source TEXT NOT NULL, detail TEXT NOT NULL, recovered_at INTEGER
);
CREATE INDEX IF NOT EXISTS alerts_patient ON alerts(elder_id,created_at);
CREATE TABLE IF NOT EXISTS alert_rule_state (
 elder_id TEXT NOT NULL REFERENCES patients(id), type TEXT NOT NULL, active_alert_id TEXT REFERENCES alerts(id),
 updated_at INTEGER NOT NULL, PRIMARY KEY(elder_id,type)
);
CREATE TABLE IF NOT EXISTS alert_acknowledgements (
 alert_id TEXT NOT NULL REFERENCES alerts(id), user_id TEXT NOT NULL REFERENCES users(id),
 acknowledged_at INTEGER NOT NULL, PRIMARY KEY(alert_id,user_id)
);
CREATE TABLE IF NOT EXISTS notifications (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 elder_id TEXT NOT NULL REFERENCES patients(id), type TEXT NOT NULL, created_at INTEGER NOT NULL,
 data TEXT NOT NULL, acknowledged_at INTEGER, occurrence_key TEXT,
 UNIQUE(user_id,occurrence_key)
);
CREATE INDEX IF NOT EXISTS notifications_user ON notifications(user_id,id);
CREATE TABLE IF NOT EXISTS reminders (
 id TEXT PRIMARY KEY, elder_id TEXT NOT NULL REFERENCES patients(id), creator_id TEXT NOT NULL REFERENCES users(id),
 detail TEXT NOT NULL, next_due_at INTEGER NOT NULL, repeat_hours INTEGER,
 created_at INTEGER NOT NULL, last_due_at INTEGER, taken_at INTEGER, active INTEGER NOT NULL DEFAULT 1, cancelled_at INTEGER
);
CREATE TABLE IF NOT EXISTS reminder_occurrences (
 reminder_id TEXT NOT NULL REFERENCES reminders(id), due_at INTEGER NOT NULL,
 notified_at INTEGER NOT NULL, taken_at INTEGER, PRIMARY KEY(reminder_id,due_at)
);
CREATE TABLE IF NOT EXISTS consents (
 elder_id TEXT PRIMARY KEY REFERENCES patients(id), location INTEGER NOT NULL DEFAULT 0,
 audio INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, location_granted_at REAL
);
CREATE TABLE IF NOT EXISTS locations (
 id INTEGER PRIMARY KEY AUTOINCREMENT, elder_id TEXT NOT NULL REFERENCES patients(id),
 recorded_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, detail TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS locations_patient ON locations(elder_id,recorded_at DESC);
CREATE TABLE IF NOT EXISTS events (
 id TEXT PRIMARY KEY, elder_id TEXT NOT NULL REFERENCES patients(id), type TEXT NOT NULL,
 occurred_at INTEGER NOT NULL, created_at INTEGER NOT NULL, detail TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limits (
 scope TEXT NOT NULL, bucket TEXT NOT NULL, window_start INTEGER NOT NULL,
 count INTEGER NOT NULL, PRIMARY KEY(scope,bucket,window_start)
);
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, actor_id TEXT, action TEXT NOT NULL,
 target_id TEXT, created_at INTEGER NOT NULL
);
PRAGMA user_version=1;
"""


class Database:
    def __init__(self, settings):
        self.settings = settings
        self.vault = Vault(settings.load_key())

    def connect(self):
        conn = sqlite3.connect(str(self.settings.db_path), timeout=10, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("PRAGMA secure_delete=ON")
        return conn

    def initialize(self):
        self.settings.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.settings.data_dir.chmod(0o700)
        with self.connection() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=FULL")
            conn.executescript(SCHEMA)
            # Rebuild only derived aggregates once when optical display policy changes.
            # Original encrypted readings are retained; bounded lazy backfill reports coverage.
            conn.execute("BEGIN IMMEDIATE")
            try:
                policy=conn.execute("SELECT version FROM metric_history_policy WHERE id=1").fetchone()
                if policy is None or policy[0] != 2:
                    conn.execute("DELETE FROM metric_history_indexed")
                    conn.execute("DELETE FROM metric_history_rollups")
                    conn.execute("INSERT INTO metric_history_policy VALUES(1,2) ON CONFLICT(id) DO UPDATE SET version=excluded.version")
                conn.commit()
            except BaseException:
                conn.rollback()
                raise
            columns={row[1] for row in conn.execute("PRAGMA table_info(alerts)")}
            if "recovered_at" not in columns:
                conn.execute("ALTER TABLE alerts ADD COLUMN recovered_at INTEGER")
            consent_columns={row[1] for row in conn.execute("PRAGMA table_info(consents)")}
            if "location_granted_at" not in consent_columns:
                conn.execute("ALTER TABLE consents ADD COLUMN location_granted_at REAL")
                # Existing grants cannot authorize locations captured before this upgrade.
                conn.execute("UPDATE consents SET location=0")
                conn.execute("DELETE FROM locations")
            reminder_columns={row[1] for row in conn.execute("PRAGMA table_info(reminders)")}
            if "cancelled_at" not in reminder_columns:
                conn.execute("ALTER TABLE reminders ADD COLUMN cancelled_at INTEGER")
        self.settings.db_path.chmod(0o600)

    @contextmanager
    def connection(self):
        conn = self.connect()
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def transaction(self):
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                yield conn
                conn.commit()
            except BaseException:
                conn.rollback()
                raise

    @staticmethod
    def audit(conn, actor_id, action, target_id=None):
        conn.execute("INSERT INTO audit(actor_id,action,target_id,created_at) VALUES (?,?,?,?)",
                     (actor_id, action, target_id, int(time.time())))

    def rate_limit(self, scope, bucket, limit, period, now=None):
        """Persist attempts independently so rejected requests still consume their attempt."""
        now = int(time.time()) if now is None else now
        # Rolling windows prevent doubling the budget across fixed-window boundaries.
        with self.transaction() as conn:
            total = conn.execute("SELECT COALESCE(SUM(count),0) FROM rate_limits WHERE scope=? AND bucket=? AND window_start>?",
                                 (scope,bucket,now-period)).fetchone()[0]
            conn.execute("INSERT INTO rate_limits VALUES (?,?,?,1) ON CONFLICT(scope,bucket,window_start) DO UPDATE SET count=count+1",
                         (scope,bucket,now))
            conn.execute("DELETE FROM rate_limits WHERE window_start<?", (now-86400,))
        return total < limit
