from contextlib import contextmanager
import time

import pytest
from fastapi.testclient import TestClient

from care_monitor_server.app import create_app
from care_monitor_server.database import connect, initialise, transaction
from care_monitor_server.security import issue


@pytest.fixture
def db(tmp_path):
    path = tmp_path / "test.db"
    initialise(path)
    return path


@pytest.fixture
def token(db):
    with connect(db) as conn,transaction(conn):
        return issue(conn,label="Isolated test phone",scopes={"sensor:write","sensor:read","alerts:read","alerts:ack"})[0]


@pytest.fixture
def headers(token):
    return {"X-API-Key":token}


@pytest.fixture
def client(db):
    with TestClient(create_app(db),base_url="https://testserver") as session:
        yield session


@pytest.fixture
def snapshot():
    return {"device_id":"elder-demo-01","uptime_ms":1200,
            "max30102":{"status":"ok","finger_detected":True,"heart_rate_valid":True,
                        "spo2_valid":True,"heart_rate_bpm":76.0,"spo2_percent":98.0,
                        "ir_raw":88000,"red_raw":63000},
            "gy906":{"online":True,"object_temp_c":33.5,"ambient_temp_c":24.1},
            "mpu6050":{"online":True,"accel_g":{"x":0.01,"y":0.02,"z":1.0},
                        "gyro_dps":{"x":0.0,"y":0.0,"z":0.0}},
            "photoresistor":{"pin":35,"adc_raw":1600,"percent":39.0},"buzzer":{"pin":13}}
