from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import sqlite3
import time

from fastapi.testclient import TestClient
import pytest

from care_monitor_server.admin import main
from care_monitor_server.app import MAX_BODY_BYTES, Limits, create_app
from care_monitor_server.database import connect, initialise, transaction
from care_monitor_server.security import issue, lookup, revoke
from care_monitor_server import alert_engine


@pytest.mark.parametrize("optical_status", ["partial", "warming_up", "no_finger"])
def test_receiver_retains_optical_quality_and_acceleration_in_same_row(db, client, headers, snapshot, optical_status):
    snapshot["max30102"].update(status=optical_status, heart_rate_valid=True,
                                spo2_valid=False, heart_rate_bpm=76.0, spo2_percent=None)
    response = client.post("/sensor-data", headers=headers, json=snapshot)
    assert response.status_code == 200
    with connect(db) as conn:
        saved = conn.execute("SELECT payload_json FROM cm_readings WHERE id=?",
                             (response.json()["record_id"],)).fetchone()
    data = json.loads(saved[0])
    assert data["max30102"] == snapshot["max30102"]
    assert data["mpu6050"] == snapshot["mpu6050"]
    assert data["max30102"]["spo2_percent"] is None


def test_key_entropy_shape_digest_default_expiry_and_no_legacy_bypass(db,client,token,headers,monkeypatch):
    key_id,secret = token.split(".")
    assert len(key_id)==12 and len(secret)==43
    with connect(db) as conn:
        row = conn.execute("SELECT * FROM cm_credentials WHERE key_id=?",(key_id,)).fetchone()
        assert row["digest"]==hashlib.sha256(token.encode()).hexdigest()
        assert row["expires_at"]-row["created_at"]==7*86400
        all_values = repr([tuple(r) for r in conn.execute("SELECT * FROM cm_credentials")])
        assert secret not in all_values and token not in all_values
    monkeypatch.setenv("SENSOR_API_KEY","legacy-test-key-must-not-authorise")
    assert client.get("/auth/status",headers={"X-API-Key":"legacy-test-key-must-not-authorise"}).status_code==401
    response = client.get("/auth/status?device_id=elder-demo-01",headers=headers)
    assert response.status_code==200
    data=response.json()
    assert data["status"]=="verified" and data["device_id"]=="elder-demo-01"
    assert data["key_id"]==key_id and data["server_id"].startswith("cm-")
    assert data["scopes"]==["alerts:ack","alerts:read","sensor:read","sensor:write"]
    assert token not in response.text and secret not in response.text and "digest" not in response.text


@pytest.mark.parametrize("path",["/auth/status","/sensor-data","/sensors","/sensors/max30102","/alerts"])
def test_protected_reads_no_key_or_wrong_key(client,path):
    assert client.get(path).status_code==401
    assert client.get(path,headers={"X-API-Key":"wrong-test-credential"}).status_code==401


def test_expired_revoked_same_safe_error(db,client,headers,token):
    now=int(time.time())
    with connect(db) as conn,transaction(conn):
        expired,_=issue(conn,label="Expired test",now=now-8*86400)
        revoked,_=issue(conn,label="Revoked test")
        revoke(conn,revoked.split(".")[0])
    expired_response=client.get("/auth/status",headers={"X-API-Key":expired})
    revoked_response=client.get("/auth/status",headers={"X-API-Key":revoked})
    assert expired_response.status_code==revoked_response.status_code==401
    assert expired_response.json()==revoked_response.json()=={"detail":"Invalid or expired credential"}
    assert client.get("/auth/status",headers=headers).status_code==200


@pytest.mark.parametrize("scopes",[{"sensor:write"},{"sensor:read"},{"alerts:read"},{"alerts:ack"}])
def test_scopes_and_device_binding(db,client,snapshot,scopes):
    with connect(db) as conn,transaction(conn):
        credential,_=issue(conn,label="Scoped test",scopes=scopes)
    headers={"X-API-Key":credential}
    assert client.get("/auth/status",headers=headers).status_code==200
    assert client.get("/auth/status?device_id=someone-else",headers=headers).status_code==403
    assert client.get("/sensor-data",headers=headers).status_code==(200 if "sensor:read" in scopes else 403)
    assert client.get("/alerts",headers=headers).status_code==(200 if "alerts:read" in scopes else 403)
    assert client.post("/sensor-data",headers=headers,json=snapshot).status_code==(200 if "sensor:write" in scopes else 403)
    assert client.post("/alerts/99999/acknowledge",headers=headers,json={"device_id":"elder-demo-01"}).status_code==(404 if "alerts:ack" in scopes else 403)


def test_wrong_device_and_no_read_cross_device(client,headers,snapshot,db):
    original=client.post("/sensor-data",json=snapshot,headers=headers)
    assert original.status_code==200
    other=dict(snapshot,device_id="different-device")
    assert client.post("/sensor-data",json=other,headers=headers).status_code==403
    for path in ("/sensor-data","/alerts","/sensors","/sensors/max30102"):
        assert client.get(path+"?device_id=different-device",headers=headers).status_code==403
    with connect(db) as conn,transaction(conn):
        other_key,_=issue(conn,label="Other device",device_id="different-device",patient_id="patient-two",scopes={"sensor:read","alerts:read"})
    read=client.get("/sensor-data",headers={"X-API-Key":other_key})
    assert read.status_code==200 and read.json()==[]


def test_storage_compatible_ack_persists_restart(client,headers,snapshot,db):
    response=client.post("/sensor-data",headers=headers,json=snapshot)
    assert response.status_code==200
    data=response.json()
    assert data["status"]=="received" and isinstance(data["record_id"],int) and data["record_id"]>0
    assert data["patient_id"]=="demo-patient-001"
    with TestClient(create_app(db),base_url="https://testserver") as restarted:
        saved=restarted.get("/sensor-data",headers=headers).json()
        assert saved[0]["data"]==snapshot
        assert saved[0]["id"]==data["record_id"]
        assert restarted.get("/sensors/max30102",headers=headers).json()["data"]==snapshot["max30102"]
        assert restarted.get("/auth/status",headers=headers).json()["server_id"]==client.get("/auth/status",headers=headers).json()["server_id"]


def test_old_lab_tables_untouched(tmp_path):
    db=tmp_path/"existing.db"
    with sqlite3.connect(db) as conn:
        conn.execute("CREATE TABLE sensor_readings (id INTEGER PRIMARY KEY, payload_json TEXT)")
        conn.execute("INSERT INTO sensor_readings VALUES (1,'legacy-reading')")
        conn.execute("CREATE TABLE alerts (id INTEGER PRIMARY KEY, reason TEXT)")
        conn.execute("INSERT INTO alerts VALUES (1,'legacy-alert')")
    initialise(db)
    initialise(db)
    with connect(db) as conn:
        assert tuple(conn.execute("SELECT * FROM sensor_readings").fetchone())==(1,"legacy-reading")
        assert tuple(conn.execute("SELECT * FROM alerts").fetchone())==(1,"legacy-alert")


def test_removed_microphone_hidden_from_modules_but_history_preserved(client,headers,snapshot):
    historical=dict(snapshot,microphone={"pin":34,"adc_raw":150})
    historical_response=client.post("/sensor-data",headers=headers,json=historical)
    assert historical_response.status_code==200
    historical_id=historical_response.json()["record_id"]

    # An older packet must not make the retired module visible again.
    modules=client.get("/sensors",headers=headers)
    assert modules.status_code==200
    assert set(modules.json()["sensors"])=={"max30102","mpu6050","gy906","photoresistor","buzzer"}
    removed=client.get("/sensors/microphone",headers=headers)
    assert removed.status_code==404 and removed.json()=={"detail":"Unknown sensor"}

    current_response=client.post("/sensor-data",headers=headers,json=snapshot)
    assert current_response.status_code==200
    readings=client.get("/sensor-data",headers=headers).json()
    assert readings[0]["data"]==snapshot and "microphone" not in readings[0]["data"]
    historical_reading=next(row for row in readings if row["id"]==historical_id)
    assert historical_reading["data"]==historical
    assert client.get("/sensors/gy906",headers=headers).json()["data"]==snapshot["gy906"]


def test_dedup_identity_and_conflicts(client,headers,snapshot,db):
    identified=dict(snapshot,boot_nonce="boot-test-001",sample_id=10)
    first=client.post("/sensor-data",json=identified,headers=headers)
    second=client.post("/sensor-data",json=identified,headers=headers)
    assert first.status_code==second.status_code==200
    assert first.json()["record_id"]==second.json()["record_id"]
    assert second.json()["duplicate"] is True
    assert client.post("/sensor-data",json=dict(identified,uptime_ms=1400),headers=headers).status_code==409
    assert client.post("/sensor-data",json=dict(identified,boot_nonce="boot-test-002"),headers=headers).json()["record_id"]!=first.json()["record_id"]
    # Existing firmware remains accepted but cannot promise retry dedup without IDs.
    assert client.post("/sensor-data",json=snapshot,headers=headers).json()["record_id"]!=client.post("/sensor-data",json=snapshot,headers=headers).json()["record_id"]


@pytest.mark.parametrize("fields",[{"sample_id":""},{"boot_nonce":"boot"},{"sample_id":10},{"sample_id":None,"boot_nonce":"boot"},{"sample_id":False,"boot_nonce":"boot"},{"sample_id":"abc","boot_nonce":""}])
def test_dedup_requires_both_valid_nonempty_fields(client,headers,snapshot,fields):
    assert client.post("/sensor-data",headers=headers,json=dict(snapshot,**fields)).status_code==400


def test_tls_and_no_forwarded_header_bypass(db,headers,snapshot):
    with TestClient(create_app(db),base_url="http://testserver") as insecure:
        assert insecure.get("/health").status_code==200
        for path in ("/auth/status","/alerts","/sensor-data"):
            response=insecure.get(path,headers={**headers,"X-Forwarded-Proto":"https","Forwarded":"proto=https"})
            assert response.status_code==426
    with TestClient(create_app(db),base_url="https://testserver") as secure:
        assert secure.post("/sensor-data",headers=headers,json=snapshot).status_code==200


def test_failed_auth_limiter_persists_between_app_instances(db):
    limits=Limits(failed_auth=2,credential_requests=100)
    with TestClient(create_app(db,limits=limits),base_url="https://testserver") as a:
        assert a.get("/auth/status").status_code==401
        assert a.get("/auth/status").status_code==401
    with TestClient(create_app(db,limits=limits),base_url="https://testserver") as b:
        response=b.get("/auth/status")
        assert response.status_code==429 and "Retry-After" in response.headers


def test_percredential_limiter_shared_across_workers(db,headers):
    limits=Limits(failed_auth=10,credential_requests=2)
    with TestClient(create_app(db,limits=limits),base_url="https://testserver") as a:
        assert a.get("/auth/status",headers=headers).status_code==200
    with TestClient(create_app(db,limits=limits),base_url="https://testserver") as b:
        assert b.get("/auth/status",headers=headers).status_code==200
        response=b.get("/alerts",headers=headers)
        assert response.status_code==429 and int(response.headers["Retry-After"])>0


@pytest.mark.parametrize("content",[b"not-json",b"[]",b'{"device_id":"elder-demo-01","n":NaN}',b'{"device_id":"elder-demo-01","n":1e400}',b'{"device_id":"elder-demo-01","device_id":"someone"}',b'\xff'])
def test_invalid_snapshot_safe_errors(client,headers,content):
    response=client.post("/sensor-data",headers=headers,content=content)
    assert response.status_code==400
    assert response.json().keys()=={"detail"}


def test_oversized_content_length_and_actual_body(client,headers):
    assert client.post("/sensor-data",headers=headers,content=b"x"*(MAX_BODY_BYTES+1)).status_code==413
    assert client.post("/sensor-data",headers={**headers,"Content-Length":"invalid"},content=b"{}").status_code==400
    assert client.post("/sensor-data",headers={**headers,"Content-Length":"-1"},content=b"{}").status_code==400
    assert client.post("/sensor-data",headers={**headers,"Content-Length":"2"},content=b"x"*(MAX_BODY_BYTES+1)).status_code==413


def test_asgi_chunked_bytes_without_content_length(db):
    application=create_app(db)
    messages=[{"type":"http.request","body":b"x"*8192,"more_body":True},
              {"type":"http.request","body":b"x"*8192,"more_body":True},
              {"type":"http.request","body":b"x","more_body":False}]
    sent=[]
    async def receive(): return messages.pop(0)
    async def send(message): sent.append(message)
    scope={"type":"http","asgi":{"version":"3.0"},"http_version":"1.1","method":"POST",
           "scheme":"https","path":"/sensor-data","raw_path":b"/sensor-data","query_string":b"",
           "headers":[],"client":("127.0.0.1",123),"server":("testserver",443)}
    asyncio.run(application(scope,receive,send))
    assert sent[0]["status"]==413


def test_health_coarse_and_read_headers(client,headers):
    assert client.get("/").json()=={"status":"ok","service":"care-monitor"}
    response=client.get("/auth/status",headers=headers)
    assert "no-store" in response.headers["Cache-Control"]
    assert response.headers["X-Content-Type-Options"]=="nosniff"
    assert client.get("/docs").status_code==404


def test_local_admin_create_list_revoke_rotate_and_no_secret_recovery(db,capsys):
    assert main(["--db",str(db),"create","--label","Test-only phone","--scope","sensor:write","--scope","alerts:read"])==0
    token=capsys.readouterr().out.strip().splitlines()[-1]
    old_id=token.split(".")[0]
    assert main(["--db",str(db),"list"])==0
    listing=capsys.readouterr().out
    assert old_id in listing and token not in listing and token.split(".")[1] not in listing
    assert main(["--db",str(db),"rotate",old_id,"--label","Test-only replacement"])==0
    new_token=capsys.readouterr().out.strip().splitlines()[-1]
    with connect(db) as conn:
        assert lookup(conn,token,int(time.time())) is None
        active=lookup(conn,new_token,int(time.time()))
        assert active is not None and active.scopes=={"sensor:write","alerts:read"}
    assert main(["--db",str(db),"revoke",new_token.split(".")[0]])==0
    capsys.readouterr()
    with connect(db) as conn:
        assert lookup(conn,new_token,int(time.time())) is None


def test_alert_api_acknowledgement_durable_and_scoped(client,headers,snapshot,db):
    policy={"enabled":True,"operator_acknowledged":True,"version":"test-lab-only",
            "label":"Test fixtures only, not clinical thresholds",
            "rules":[{"id":"heart_rate_high","enabled":True,"metric":"heart_rate_bpm",
                      "direction":"high","trigger":100,"recover":90,
                      "persistence_seconds":1,"recovery_seconds":1,"min_samples":2,"severity":"warning"}]}
    with connect(db) as conn,transaction(conn):
        alert_engine.initialise(conn,policy=policy)
    start=datetime.now(timezone.utc)-timedelta(seconds=3)
    for seconds in (0,1):
        body=json.loads(json.dumps(snapshot))
        body["timestamp"]=(start+timedelta(seconds=seconds)).isoformat()
        body["max30102"]["heart_rate_bpm"]=120.0
        assert client.post("/sensor-data",headers=headers,json=body).status_code==200
    response=client.get("/alerts?device_id=elder-demo-01&after_revision=0&limit=50",headers=headers)
    assert response.status_code==200
    envelope=response.json()
    assert envelope["status"]=="ok" and envelope["mode"]=="shadow"
    assert len(envelope["alerts"])==1
    entry=envelope["alerts"][0]
    assert entry["status"]=="active" and entry["source"]=="rule"
    acknowledgement=client.post(f'/alerts/{entry["id"]}/acknowledge',headers=headers,json={"device_id":"elder-demo-01"})
    assert acknowledgement.status_code==200
    acknowledged=acknowledgement.json()
    assert acknowledged["status"]=="acknowledged"
    assert acknowledged["device_id"]=="elder-demo-01" and acknowledged["server_id"]==envelope["server_id"]
    assert acknowledged["alert"]["status"]=="acknowledged" and acknowledged["alert"]["acknowledged_at"]
    assert acknowledged["alert"]["resolved_at"] is None
    assert acknowledged["alert"]["revision"]>entry["revision"]
    same=client.post(f'/alerts/{entry["id"]}/acknowledge',headers=headers,json={"device_id":"elder-demo-01"})
    assert same.json()["alert"]==acknowledged["alert"]
    with TestClient(create_app(db),base_url="https://testserver") as restarted:
        persisted=restarted.get(f'/alerts?after_revision={entry["revision"]}',headers=headers).json()
        assert persisted["alerts"][0]==acknowledged["alert"]
        assert persisted["capabilities"]["rule_policy"]["enabled"] is True
    with connect(db) as conn,transaction(conn):
        other_key,_=issue(conn,label="Other test caregiver",device_id="other-device",patient_id="other-patient",scopes={"alerts:read","alerts:ack"})
        upload_only,_=issue(conn,label="Upload-only test")
    assert client.get("/alerts",headers={"X-API-Key":other_key}).json()["alerts"]==[]
    assert client.post(f'/alerts/{entry["id"]}/acknowledge',headers={"X-API-Key":other_key},json={"device_id":"other-device"}).status_code==404
    assert client.get("/alerts",headers={"X-API-Key":upload_only}).status_code==403
    assert client.post(f'/alerts/{entry["id"]}/acknowledge',headers={"X-API-Key":upload_only},json={"device_id":"elder-demo-01"}).status_code==403


def test_client_submitted_alert_and_ml_label_ignored(client,headers,snapshot):
    snapshot["alerts"]=[{"type":"fall","severity":"urgent","source":"ml","status":"active"}]
    snapshot["ml_prediction"]={"label":"ABNORMAL","confidence":1.0}
    response=client.post("/sensor-data",headers=headers,json=snapshot)
    assert response.status_code==200 and response.json()["alert_created"] is False
    assert client.get("/alerts",headers=headers).json()["alerts"]==[]


def test_duplicate_key_headers_are_not_accepted(client,token):
    response=client.get("/auth/status",headers=[("X-API-Key",token),("X-API-Key",token)])
    assert response.status_code==401


def test_internal_error_redacts_exception_and_payload(db,headers,snapshot,monkeypatch):
    marker="test-only-secret-marker-never-echo"
    def fail(*args,**kwargs): raise RuntimeError(marker)
    monkeypatch.setattr(alert_engine,"process_snapshot",fail)
    with TestClient(create_app(db),base_url="https://testserver",raise_server_exceptions=False) as session:
        response=session.post("/sensor-data",headers=headers,json=dict(snapshot,private_field=marker))
    assert response.status_code==500 and marker not in response.text
    assert response.json()=={"detail":"Server could not complete the request"}
    with connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM cm_readings").fetchone()[0]==0


def test_parallel_rate_updates_are_atomic(db,headers):
    limits=Limits(credential_requests=5)
    first=TestClient(create_app(db,limits=limits),base_url="https://testserver")
    second=TestClient(create_app(db,limits=limits),base_url="https://testserver")
    with first,second:
        def request(i): return (first if i%2 else second).get("/auth/status",headers=headers).status_code
        with ThreadPoolExecutor(max_workers=8) as workers:
            statuses=list(workers.map(request,range(12)))
    assert statuses.count(200)==5 and statuses.count(429)==7


@pytest.mark.parametrize("path",["/alerts?after_revision=-1","/alerts?after_revision=99999999999999999999999",
                                  "/alerts?limit=101","/sensor-data?limit=0"])
def test_invalid_query_does_not_echo_input(client,headers,path):
    response=client.get(path,headers=headers)
    assert response.status_code==422 and response.json()=={"detail":"Invalid request parameters"}
