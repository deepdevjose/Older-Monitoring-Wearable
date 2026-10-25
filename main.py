from __future__ import annotations

from contextlib import asynccontextmanager, closing
from datetime import datetime
import json
import logging
import math
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import FastAPI, HTTPException, Query, Request, Security
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader


DB_PATH = Path(__file__).resolve().parent / "sensor_data.db"
SENSOR_NAMES = (
    "max30102",
    "mpu6050",
    "gy906",
    "microphone",
    "photoresistor",
)

MAX_BODY_BYTES = 16 * 1024
MIN_POST_INTERVAL_SECONDS = 0.1
EXPECTED_DEVICE_ID = "elder-demo-01"
DEVICE_PATIENT_MAP = {EXPECTED_DEVICE_ID: "demo-patient-001"}
DEMO_SPO2_THRESHOLD = 95.0
CONSECUTIVE_LOW_READINGS = 3
CONSECUTIVE_NORMAL_READINGS = 3
SHANGHAI_TZ = ZoneInfo("Asia/Shanghai")
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)

latest_reading: dict[str, Any] | None = None
latest_received_at: str | None = None
last_request_by_client: dict[str, float] = {}
request_times_lock = threading.Lock()
logger = logging.getLogger(__name__)


def initialise_database() -> None:
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sensor_readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                uptime_ms INTEGER,
                max_i2c_address TEXT,
                max_status TEXT,
                max_finger_detected INTEGER,
                max_heart_rate_valid INTEGER,
                max_spo2_valid INTEGER,
                heart_rate_bpm REAL,
                spo2_percent REAL,
                max_ir_raw INTEGER,
                max_red_raw INTEGER,
                max_window_samples INTEGER,
                max_window_target INTEGER,
                mpu_i2c_address TEXT,
                mpu_online INTEGER,
                accel_x REAL,
                accel_y REAL,
                accel_z REAL,
                gyro_x REAL,
                gyro_y REAL,
                gyro_z REAL,
                gy_i2c_address TEXT,
                gy_online INTEGER,
                object_temp_c REAL,
                ambient_temp_c REAL,
                microphone_pin INTEGER,
                microphone_adc_raw INTEGER,
                photoresistor_pin INTEGER,
                photoresistor_adc_raw INTEGER,
                photoresistor_percent REAL,
                buzzer_pin INTEGER,
                received_at TEXT NOT NULL,
                sensor_timestamp TEXT,
                payload_json TEXT NOT NULL
            )
            """
        )
        existing_columns = {
            row[1]
            for row in conn.execute("PRAGMA table_info(sensor_readings)")
        }
        columns = {
            "uptime_ms": "INTEGER",
            "max_i2c_address": "TEXT",
            "max_status": "TEXT",
            "max_finger_detected": "INTEGER",
            "max_heart_rate_valid": "INTEGER",
            "max_spo2_valid": "INTEGER",
            "max_ir_raw": "INTEGER",
            "max_red_raw": "INTEGER",
            "max_window_samples": "INTEGER",
            "max_window_target": "INTEGER",
            "mpu_i2c_address": "TEXT",
            "mpu_online": "INTEGER",
            "accel_x": "REAL",
            "accel_y": "REAL",
            "accel_z": "REAL",
            "gyro_x": "REAL",
            "gyro_y": "REAL",
            "gyro_z": "REAL",
            "gy_i2c_address": "TEXT",
            "gy_online": "INTEGER",
            "object_temp_c": "REAL",
            "ambient_temp_c": "REAL",
            "microphone_pin": "INTEGER",
            "microphone_adc_raw": "INTEGER",
            "photoresistor_pin": "INTEGER",
            "photoresistor_adc_raw": "INTEGER",
            "photoresistor_percent": "REAL",
            "buzzer_pin": "INTEGER",
        }
        for name, data_type in columns.items():
            if name not in existing_columns:
                conn.execute(
                    f"ALTER TABLE sensor_readings ADD COLUMN {name} {data_type}"
                )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_sensor_readings_received_at
            ON sensor_readings (received_at DESC)
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reading_id INTEGER NOT NULL,
                patient_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                metric TEXT NOT NULL,
                observed_value REAL NOT NULL,
                threshold_value REAL NOT NULL,
                reason TEXT NOT NULL,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'new',
                FOREIGN KEY (reading_id) REFERENCES sensor_readings (id)
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_alerts_status_id
            ON alerts (status, id DESC)
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS alert_rule_state (
                device_id TEXT NOT NULL,
                metric TEXT NOT NULL,
                consecutive_low INTEGER NOT NULL DEFAULT 0,
                consecutive_normal INTEGER NOT NULL DEFAULT 0,
                active_alert_id INTEGER,
                PRIMARY KEY (device_id, metric),
                FOREIGN KEY (active_alert_id) REFERENCES alerts (id)
            )
            """
        )
        conn.commit()


@asynccontextmanager
async def lifespan(app: FastAPI):
    initialise_database()
    yield


app = FastAPI(
    lifespan=lifespan,
    title="Elder Sensors API",
    description="Receives and stores sensor snapshots from the ESP32.",
    version="1.0.0",
)


@app.middleware("http")
async def protect_requests(request: Request, call_next: Any) -> Any:
    """Reject oversized requests before FastAPI parses their JSON body."""

    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) > MAX_BODY_BYTES:
                return JSONResponse(
                    status_code=413,
                    content={"detail": "Request body is too large"},
                )
        except ValueError:
            return JSONResponse(
                status_code=400,
                content={"detail": "Invalid Content-Length header"},
            )

    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Cache-Control"] = "no-store"
    return response


def authenticate_request(
    request: Request,
    x_api_key: str | None,
    *,
    limit_write_rate: bool = False,
) -> None:
    configured_key = os.getenv("SENSOR_API_KEY")
    if not configured_key or len(configured_key) < 16:
        raise HTTPException(
            status_code=503,
            detail="SENSOR_API_KEY is not configured securely",
        )

    if not x_api_key or not secrets.compare_digest(x_api_key, configured_key):
        raise HTTPException(status_code=401, detail="Invalid API key")

    if limit_write_rate:
        client_id = request.client.host if request.client else "unknown"
        now = time.monotonic()
        with request_times_lock:
            previous = last_request_by_client.get(client_id)
            if (
                previous is not None
                and now - previous < MIN_POST_INTERVAL_SECONDS
            ):
                raise HTTPException(status_code=429, detail="Too many requests")
            last_request_by_client[client_id] = now


def received_timestamp() -> str:
    return datetime.now(SHANGHAI_TZ).isoformat(timespec="milliseconds")


def extract_sensor_value(
    reading: dict[str, Any],
    sensor_name: str,
    value_name: str,
) -> float | None:
    sensor = reading.get(sensor_name)
    if not isinstance(sensor, dict):
        return None
    value = sensor.get(value_name)
    return float(value) if isinstance(value, (int, float)) else None


def sensor_field(
    reading: dict[str, Any],
    sensor_name: str,
    field_name: str,
) -> Any:
    sensor = reading.get(sensor_name)
    return sensor.get(field_name) if isinstance(sensor, dict) else None


def nested_sensor_field(
    reading: dict[str, Any],
    sensor_name: str,
    group_name: str,
    field_name: str,
) -> Any:
    group = sensor_field(reading, sensor_name, group_name)
    return group.get(field_name) if isinstance(group, dict) else None


def save_reading(
    conn: sqlite3.Connection,
    reading: dict[str, Any],
    received_at: str,
) -> int:
    heart_rate = extract_sensor_value(
        reading,
        "max30102",
        "heart_rate_bpm",
    )
    spo2 = extract_sensor_value(
        reading,
        "max30102",
        "spo2_percent",
    )
    payload_json = json.dumps(reading, separators=(",", ":"), allow_nan=False)
    values = (
        reading.get("device_id"),
        reading.get("uptime_ms"),
        sensor_field(reading, "max30102", "i2c_address"),
        sensor_field(reading, "max30102", "status"),
        sensor_field(reading, "max30102", "finger_detected"),
        sensor_field(reading, "max30102", "heart_rate_valid"),
        sensor_field(reading, "max30102", "spo2_valid"),
        heart_rate,
        spo2,
        sensor_field(reading, "max30102", "ir_raw"),
        sensor_field(reading, "max30102", "red_raw"),
        sensor_field(reading, "max30102", "window_samples"),
        sensor_field(reading, "max30102", "window_target"),
        sensor_field(reading, "mpu6050", "i2c_address"),
        sensor_field(reading, "mpu6050", "online"),
        nested_sensor_field(reading, "mpu6050", "accel_g", "x"),
        nested_sensor_field(reading, "mpu6050", "accel_g", "y"),
        nested_sensor_field(reading, "mpu6050", "accel_g", "z"),
        nested_sensor_field(reading, "mpu6050", "gyro_dps", "x"),
        nested_sensor_field(reading, "mpu6050", "gyro_dps", "y"),
        nested_sensor_field(reading, "mpu6050", "gyro_dps", "z"),
        sensor_field(reading, "gy906", "i2c_address"),
        sensor_field(reading, "gy906", "online"),
        sensor_field(reading, "gy906", "object_temp_c"),
        sensor_field(reading, "gy906", "ambient_temp_c"),
        sensor_field(reading, "microphone", "pin"),
        sensor_field(reading, "microphone", "adc_raw"),
        sensor_field(reading, "photoresistor", "pin"),
        sensor_field(reading, "photoresistor", "adc_raw"),
        sensor_field(reading, "photoresistor", "percent"),
        sensor_field(reading, "buzzer", "pin"),
        received_at,
        reading.get("timestamp"),
        payload_json,
    )
    insert_columns = (
        "device_id",
        "uptime_ms",
        "max_i2c_address",
        "max_status",
        "max_finger_detected",
        "max_heart_rate_valid",
        "max_spo2_valid",
        "heart_rate_bpm",
        "spo2_percent",
        "max_ir_raw",
        "max_red_raw",
        "max_window_samples",
        "max_window_target",
        "mpu_i2c_address",
        "mpu_online",
        "accel_x",
        "accel_y",
        "accel_z",
        "gyro_x",
        "gyro_y",
        "gyro_z",
        "gy_i2c_address",
        "gy_online",
        "object_temp_c",
        "ambient_temp_c",
        "microphone_pin",
        "microphone_adc_raw",
        "photoresistor_pin",
        "photoresistor_adc_raw",
        "photoresistor_percent",
        "buzzer_pin",
        "received_at",
        "sensor_timestamp",
        "payload_json",
    )
    placeholders = ", ".join("?" for _ in values)

    cursor = conn.execute(
        f"INSERT INTO sensor_readings ({', '.join(insert_columns)}) "
        f"VALUES ({placeholders})",
        values,
    )
    return int(cursor.lastrowid)


def create_demo_alert(
    conn: sqlite3.Connection,
    reading_id: int,
    patient_id: str,
    reading: dict[str, Any],
    received_at: str,
) -> tuple[bool, int | None]:
    """Create at most one demo SpO2 alert per sustained low-reading episode."""

    conn.execute(
        """
        INSERT OR IGNORE INTO alert_rule_state (device_id, metric)
        VALUES (?, 'spo2_percent')
        """,
        (reading["device_id"],),
    )
    state = conn.execute(
        """
        SELECT consecutive_low, consecutive_normal, active_alert_id
        FROM alert_rule_state
        WHERE device_id = ? AND metric = 'spo2_percent'
        """,
        (reading["device_id"],),
    ).fetchone()
    if state is None:
        raise sqlite3.IntegrityError("Could not initialize alert rule state")

    low_count, normal_count, active_alert_id = state
    max30102 = reading.get("max30102")
    spo2 = max30102.get("spo2_percent") if isinstance(max30102, dict) else None
    valid = (
        isinstance(max30102, dict)
        and max30102.get("spo2_valid") is True
        and isinstance(spo2, (int, float))
        and not isinstance(spo2, bool)
        and math.isfinite(spo2)
        and 70 <= spo2 <= 100
    )
    alert_created = False

    if not valid:
        low_count = 0
        normal_count = 0
    elif spo2 < DEMO_SPO2_THRESHOLD:
        low_count += 1
        normal_count = 0
        if (
            low_count >= CONSECUTIVE_LOW_READINGS
            and active_alert_id is None
        ):
            cursor = conn.execute(
                """
                INSERT INTO alerts (
                    reading_id,
                    patient_id,
                    device_id,
                    metric,
                    observed_value,
                    threshold_value,
                    reason,
                    created_at,
                    status
                )
                VALUES (?, ?, ?, 'spo2_percent', ?, ?, ?, ?, 'new')
                """,
                (
                    reading_id,
                    patient_id,
                    reading["device_id"],
                    float(spo2),
                    DEMO_SPO2_THRESHOLD,
                    "SpO2 below demo threshold; review required",
                    received_at,
                ),
            )
            active_alert_id = int(cursor.lastrowid)
            alert_created = True
    else:
        low_count = 0
        normal_count += 1
        if (
            active_alert_id is not None
            and normal_count >= CONSECUTIVE_NORMAL_READINGS
        ):
            conn.execute(
                "UPDATE alerts SET status = 'resolved' WHERE id = ?",
                (active_alert_id,),
            )
            active_alert_id = None
            normal_count = 0

    conn.execute(
        """
        UPDATE alert_rule_state
        SET consecutive_low = ?,
            consecutive_normal = ?,
            active_alert_id = ?
        WHERE device_id = ? AND metric = 'spo2_percent'
        """,
        (low_count, normal_count, active_alert_id, reading["device_id"]),
    )
    return alert_created, active_alert_id


@app.get("/")
def health_check() -> dict[str, str]:
    return {"status": "ok", "service": "elder-sensors-api"}


@app.post("/sensor-data")
def receive_sensor_data(
    reading: dict[str, Any],
    request: Request,
    x_api_key: str | None = Security(api_key_header),
) -> dict[str, Any]:
    """Validate, store, and retain the complete JSON snapshot."""

    authenticate_request(request, x_api_key, limit_write_rate=True)
    global latest_reading, latest_received_at

    device_id = reading.get("device_id")
    patient_id = DEVICE_PATIENT_MAP.get(device_id)
    if patient_id is None:
        raise HTTPException(status_code=400, detail="Device has no patient mapping")

    try:
        received_at = received_timestamp()
        with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn:
            conn.execute("PRAGMA foreign_keys = ON")
            with conn:
                record_id = save_reading(conn, reading, received_at)
                alert_created, alert_id = create_demo_alert(
                    conn,
                    record_id,
                    patient_id,
                    reading,
                    received_at,
                )
    except (sqlite3.Error, TypeError, ValueError, OverflowError) as exc:
        logger.exception("Failed to store sensor reading")
        raise HTTPException(
            status_code=500,
            detail="Could not store sensor reading",
        ) from exc

    latest_reading = reading
    latest_received_at = received_at
    print(
        f"[{received_at}] Saved record {record_id} from {device_id}",
        flush=True,
    )
    return {
        "status": "received",
        "record_id": record_id,
        "patient_id": patient_id,
        "received_at": received_at,
        "alert_created": alert_created,
        "alert_id": alert_id,
        "data": reading,
    }


@app.get("/sensor-data")
def get_sensor_data(
    request: Request,
    x_api_key: str | None = Security(api_key_header),
) -> list[dict[str, Any]]:
    """Return the 20 most recent persisted readings."""

    authenticate_request(request, x_api_key)
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT * FROM sensor_readings
            ORDER BY id DESC
            LIMIT 20
            """
        ).fetchall()

    result: list[dict[str, Any]] = []
    for row in rows:
        item = dict(row)
        item["data"] = json.loads(item.pop("payload_json"))
        result.append(item)
    return result


@app.get("/alerts")
def get_alerts(
    request: Request,
    x_api_key: str | None = Security(api_key_header),
    limit: int = Query(default=20, ge=1, le=100),
    include_resolved: bool = False,
) -> list[dict[str, Any]]:
    """Return the newest patient-linked alerts for the monitoring app."""

    authenticate_request(request, x_api_key)
    status_filter = "" if include_resolved else "WHERE status = 'new'"
    with closing(sqlite3.connect(DB_PATH, timeout=10)) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            f"""
            SELECT
                id,
                reading_id,
                patient_id,
                device_id,
                metric,
                observed_value,
                threshold_value,
                reason,
                created_at,
                status
            FROM alerts
            {status_filter}
            ORDER BY id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]


@app.get("/sensors")
def list_sensors(
    request: Request,
    x_api_key: str | None = Security(api_key_header),
) -> dict[str, Any]:
    """Return the latest status/value block for every sensor."""

    authenticate_request(request, x_api_key)
    if latest_reading is None:
        raise HTTPException(status_code=404, detail="No sensor reading received")
    return {
        "status": "ok",
        "received_at": latest_received_at,
        "device_id": latest_reading.get("device_id"),
        "sensors": {
            name: latest_reading.get(name) for name in SENSOR_NAMES
        },
    }


@app.get("/sensors/{sensor_name}")
def get_sensor(
    sensor_name: str,
    request: Request,
    x_api_key: str | None = Security(api_key_header),
) -> dict[str, Any]:
    """Return one sensor block, for example /sensors/max30102."""

    authenticate_request(request, x_api_key)
    if sensor_name not in SENSOR_NAMES:
        raise HTTPException(
            status_code=404,
            detail=f"Unknown sensor '{sensor_name}'. "
            f"Use one of: {', '.join(SENSOR_NAMES)}",
        )
    if latest_reading is None:
        raise HTTPException(status_code=404, detail="No sensor reading received")

    return {
        "status": "ok",
        "sensor": sensor_name,
        "received_at": latest_received_at,
        "device_id": latest_reading.get("device_id"),
        "data": latest_reading.get(sensor_name),
    }
