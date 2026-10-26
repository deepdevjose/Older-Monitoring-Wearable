"""Security and delivery invariants against isolated disposable databases."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import pytest
from fastapi.testclient import TestClient

from caremonitor.api import create_app
from caremonitor.config import Settings
from caremonitor.push import process_due_reminders


def stamp(seconds=None):
    return datetime.fromtimestamp(time.time() if seconds is None else seconds,timezone.utc).isoformat()


@pytest.fixture
def service(tmp_path):
    settings=Settings(data_dir=tmp_path,scheduler_interval_seconds=3600,enforce_https=False)
    settings.initialize_key()
    app=create_app(settings)
    with TestClient(app) as client:
        yield client,app,settings


def account(client,name="elder",role="elder"):
    response=client.post("/api/v1/auth/register",json={"email":name+"@example.com","password":"A sufficiently long secret 42!","display_name":name,"role":role,"educational_ack":True})
    assert response.status_code==201,response.text
    data=response.json()
    return data["user"]["id"],{"Authorization":"Bearer "+data["access_token"]},data


def link(client,elder,caregiver):
    invitation=client.post("/api/v1/invitations",headers=elder).json()
    request=client.post("/api/v1/link-requests",headers=caregiver,json={"code":invitation["code"]})
    assert request.status_code==201,request.text
    request_id=request.json()["id"]
    approved=client.post("/api/v1/link-requests/"+request_id+"/approve",headers=elder)
    assert approved.status_code==200,approved.text
    return invitation,request_id


def sample(sample_id="s-1",when=None,source="synthetic",pulse=80,oxygen=98):
    return {"sample_id":sample_id,"captured_at":stamp(when),"source":source,
            "payload":{"device_id":"elder-demo-01","uptime_ms":1000,
                       "max30102":{"status":"ok","finger_detected":True,"heart_rate_valid":True,"spo2_valid":True,
                                    "heart_rate_bpm":pulse,"spo2_percent":oxygen},
                       "gy906":{"online":True,"object_temp_c":34,"ambient_temp_c":24},
                       "photoresistor":{"percent":5,"adc_raw":200}}}


def create_alert(client,headers):
    now=int(time.time())
    for i in range(3):
        response=client.post("/api/v1/readings",headers=headers,json=sample(f"alert-{i}",now-3+i,pulse=150))
        assert response.status_code==201,response.text
    assert response.json()["alert_created"]


def test_import_has_no_filesystem_side_effect(tmp_path):
    target=tmp_path/"not-initialized"
    env={**os.environ,"CAREMONITOR_HOME":str(target)}
    result=subprocess.run([sys.executable,"-c","from caremonitor.api import app; print(app.title)"],env=env,capture_output=True,text=True)
    assert result.returncode==0,result.stderr
    assert not target.exists()


def test_requires_explicit_private_key(tmp_path):
    settings=Settings(data_dir=tmp_path)
    with pytest.raises(RuntimeError,match="Encryption key missing"):
        with TestClient(create_app(settings)):
            pass
    settings.initialize_key()
    original=settings.key_path.read_bytes()
    settings.initialize_key()
    assert original==settings.key_path.read_bytes()
    settings.key_path.chmod(0o644)
    with pytest.raises(RuntimeError,match="0600"):
        settings.load_key()


def test_authentication_logout_expiry_and_role(service):
    client,app,_=service
    elder_id,elder,data=account(client)
    assert client.get("/api/v1/me").status_code==401
    assert client.get("/api/v1/me",headers={"X-API-Key":"old-shared-key"}).status_code==401
    assert client.get("/api/v1/me",headers=elder).json()["id"]==elder_id
    assert client.post("/api/v1/auth/login",json={"email":"elder@example.com","password":"wrong"}).status_code==401
    logged=client.post("/api/v1/auth/login",json={"email":"elder@example.com","password":"A sufficiently long secret 42!"})
    assert logged.status_code==200
    assert logged.json()["access_token"]!=data["access_token"]
    assert client.post("/api/v1/auth/logout",headers=elder).status_code==200
    assert client.get("/api/v1/me",headers=elder).status_code==401
    second={"Authorization":"Bearer "+logged.json()["access_token"]}
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE sessions SET expires_at=?",(int(time.time())-1,))
    assert client.get("/api/v1/me",headers=second).status_code==401


def test_registration_is_educational_and_errors_do_not_echo_secrets(service):
    client,_,_=service
    secret="Private forbidden password"
    response=client.post("/api/v1/auth/register",json={"email":"person@example.com","password":secret,"display_name":"Person","role":"admin","educational_ack":False})
    assert response.status_code==422
    assert secret not in response.text
    assert "person@example.com" not in response.text
    assert client.get("/docs").status_code==404


def test_invitation_requires_explicit_approval_and_single_use(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    _,other,_=account(client,"other","caregiver")
    invite=client.post("/api/v1/invitations",headers=elder).json()
    assert len(invite["code"])==4
    request=client.post("/api/v1/link-requests",headers=caregiver,json={"code":invite["code"]}).json()
    assert request["status"]=="pending"
    assert client.get("/api/v1/patients",headers=caregiver).json()=={"patients":[]}
    assert client.get(f"/api/v1/patients/{elder_id}/overview",headers=caregiver).status_code==404
    pending=client.get("/api/v1/link-requests",headers=elder).json()["requests"]
    assert pending[0]["caregiver"]["display_name"]=="carer"
    assert client.post("/api/v1/link-requests/"+request["id"]+"/approve",headers=caregiver).status_code==403
    assert client.post("/api/v1/link-requests/"+request["id"]+"/approve",headers=elder).status_code==200
    assert client.get(f"/api/v1/patients/{elder_id}/overview",headers=caregiver).status_code==200
    assert client.post("/api/v1/link-requests",headers=other,json={"code":invite["code"]}).status_code==400
    assert client.post("/api/v1/link-requests/"+request["id"]+"/approve",headers=elder).status_code==409


def test_invite_expiry_and_persistent_bruteforce_limit(service):
    client,app,_=service
    _,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    invite=client.post("/api/v1/invitations",headers=elder).json()
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE invitations SET expires_at=?",(int(time.time())-1,))
    assert client.post("/api/v1/link-requests",headers=caregiver,json={"code":invite["code"]}).status_code==400
    for _ in range(4):
        assert client.post("/api/v1/link-requests",headers=caregiver,json={"code":"9999"}).status_code==400
    assert client.post("/api/v1/link-requests",headers=caregiver,json={"code":"9999"}).status_code==429
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT SUM(count) FROM rate_limits WHERE scope='link:account'").fetchone()[0]==6


def test_login_attempts_survive_reopening_database(service):
    client,app,settings=service
    account(client)
    for _ in range(8):
        assert client.post("/api/v1/auth/login",json={"email":"elder@example.com","password":"incorrect"}).status_code==401
    with TestClient(create_app(settings)) as second:
        response=second.post("/api/v1/auth/login",json={"email":"elder@example.com","password":"A sufficiently long secret 42!"})
        assert response.status_code==429


def test_idor_cannot_read_or_write_other_patients(service):
    client,_,_=service
    first_id,first,_=account(client,"first")
    second_id,second,_=account(client,"second")
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,first,caregiver)
    for path in ("overview","readings","alerts","reminders"):
        assert client.get(f"/api/v1/patients/{second_id}/{path}",headers=caregiver).status_code==404
        assert client.get(f"/api/v1/patients/{first_id}/{path}",headers=second).status_code==404
    assert client.post("/api/v1/readings",headers=caregiver,json=sample()).status_code==403
    payload=sample();payload["patient_id"]=first_id
    assert client.post("/api/v1/readings",headers=second,json=payload).status_code==422
    bad=sample();bad["payload"]["device_id"]="unassigned-esp32"
    assert client.post("/api/v1/readings",headers=second,json=bad).status_code==403


def test_idempotency_conflict_and_capture_order(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    payload=sample("unique",now)
    first=client.post("/api/v1/readings",headers=elder,json=payload).json()
    second=client.post("/api/v1/readings",headers=elder,json=payload).json()
    assert first["record_id"]==second["record_id"]>0
    assert not first["duplicate"] and second["duplicate"]
    payload["payload"]["max30102"]["heart_rate_bpm"]=81
    assert client.post("/api/v1/readings",headers=elder,json=payload).status_code==409
    assert client.post("/api/v1/readings",headers=elder,json=sample("older",now-60)).status_code==201
    overview=client.get(f"/api/v1/patients/{elder_id}/overview",headers=elder).json()
    assert overview["reading"]["sample_id"]=="unique"
    assert len(client.get(f"/api/v1/patients/{elder_id}/readings",headers=elder).json()["readings"])==2


def test_all_caregivers_durable_notification_and_independent_ack(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    _,first,_=account(client,"first","caregiver")
    _,second,_=account(client,"second","caregiver")
    link(client,elder,first);link(client,elder,second)
    create_alert(client,elder)
    first_note=client.get("/api/v1/notifications",headers=first).json()["items"][0]
    second_note=client.get("/api/v1/notifications",headers=second).json()["items"][0]
    assert first_note["data"]["alert_id"]==second_note["data"]["alert_id"]
    assert client.post(f"/api/v1/notifications/{first_note['id']}/ack",headers=second).status_code==404
    assert client.post(f"/api/v1/notifications/{first_note['id']}/ack",headers=first).status_code==200
    # Transport receipt is not a clinical review and another carer's review remains independent.
    alert_id=first_note["data"]["alert_id"]
    assert not client.get(f"/api/v1/patients/{elder_id}/alerts",headers=first).json()["alerts"][0]["acknowledged"]
    response=client.post(f"/api/v1/alerts/{alert_id}/acknowledge",headers=first)
    assert response.json()["patient_resolved"] is False
    assert client.get(f"/api/v1/patients/{elder_id}/alerts",headers=first).json()["alerts"][0]["acknowledged"]
    assert not client.get(f"/api/v1/patients/{elder_id}/alerts",headers=second).json()["alerts"][0]["acknowledged"]


def test_revoke_removes_data_and_queued_notifications(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    caregiver_id,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    create_alert(client,elder)
    note=client.get("/api/v1/notifications",headers=caregiver).json()["items"][0]
    assert client.delete("/api/v1/caregivers/"+caregiver_id,headers=elder).status_code==200
    assert client.get("/api/v1/patients",headers=caregiver).json()["patients"]==[]
    for route in ("overview","alerts","readings","reminders"):
        assert client.get(f"/api/v1/patients/{elder_id}/{route}",headers=caregiver).status_code==404
    assert client.get("/api/v1/notifications",headers=caregiver).json()["items"]==[]
    assert client.post(f"/api/v1/notifications/{note['id']}/ack",headers=caregiver).status_code==404
    assert client.post("/api/v1/alerts/"+note["data"]["alert_id"]+"/acknowledge",headers=caregiver).status_code==404


@pytest.mark.parametrize("source,age",[("device_diagnostic",3),("synthetic",500)])
def test_diagnostic_and_stale_data_never_create_health_alerts(service,source,age):
    client,_,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    for i in range(3):
        response=client.post("/api/v1/readings",headers=elder,json=sample(str(i),now-age+i,source=source,pulse=170,oxygen=75))
        assert response.status_code==201
        assert not response.json()["alert_created"]
    assert client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]==[]
    if age>10:
        overview=client.get(f"/api/v1/patients/{elder_id}/overview",headers=elder).json()
        assert overview["freshness"]["state"]=="stale"
        assert overview["wear_inference"]["state"]=="unknown"


def test_invalid_optical_contact_does_not_trigger(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    for i in range(3):
        payload=sample(str(i),now-3+i,pulse=180,oxygen=80)
        payload["payload"]["max30102"]["finger_detected"]=False
        assert not client.post("/api/v1/readings",headers=elder,json=payload).json()["alert_created"]
    assert not client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]


def test_reminder_scheduler_exactly_once_and_self_reported_taken(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    data={"title":"Recordatorio académico de prueba","dose":"Según pauta acordada","instructions":"Revisar indicación","due_at":stamp(time.time()-5),"repeat_hours":1}
    assert client.post(f"/api/v1/patients/{elder_id}/reminders",headers=elder,json=data).status_code==404
    response=client.post(f"/api/v1/patients/{elder_id}/reminders",headers=caregiver,json=data)
    assert response.status_code==201,response.text
    reminder=response.json()
    process_due_reminders(app.state.db);process_due_reminders(app.state.db)
    elder_notes=client.get("/api/v1/notifications",headers=elder).json()["items"]
    caregiver_notes=client.get("/api/v1/notifications",headers=caregiver).json()["items"]
    assert len(elder_notes)==len(caregiver_notes)==1
    assert elder_notes[0]["data"]["dose"]==data["dose"]
    assert client.post("/api/v1/reminders/"+reminder["id"]+"/taken",headers=caregiver).status_code==403
    assert client.post("/api/v1/reminders/"+reminder["id"]+"/taken",headers=elder).status_code==200
    assert client.get(f"/api/v1/patients/{elder_id}/reminders",headers=elder).json()["reminders"][0]["taken_at"]
    # Repeated confirmation never claims ingestion was objectively verified.
    assert "Self-reported" in client.post("/api/v1/reminders/"+reminder["id"]+"/taken",headers=elder).text


def test_catchup_sends_latest_occurrence_without_dose_flood(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    client.post(f"/api/v1/patients/{elder_id}/reminders",headers=caregiver,json={"title":"Agua","due_at":stamp(time.time()-100),"repeat_hours":1})
    future=int(time.time())+24*3600
    process_due_reminders(app.state.db,future);process_due_reminders(app.state.db,future)
    items=client.get("/api/v1/notifications",headers=elder).json()["items"]
    assert len(items)==1
    assert items[0]["data"]["missed_occurrences"]==24


def test_location_consent_outside_only_ttl_and_revocation(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    location={"outside_home":True,"latitude":25.6872,"longitude":-100.3161,"accuracy_m":10,"recorded_at":stamp()}
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==403
    assert client.post("/api/v1/consents",headers=elder,json={"location":True,"audio":False}).status_code==200
    assert client.post("/api/v1/location",headers=elder,json={**location,"outside_home":False}).status_code==422
    assert client.post("/api/v1/location",headers=elder,json={**location,"latitude":100}).status_code==422
    location["recorded_at"]=stamp()
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==201
    assert client.get(f"/api/v1/patients/{elder_id}/overview",headers=caregiver).json()["location"]["latitude"]==location["latitude"]
    client.post("/api/v1/consents",headers=elder,json={"location":False,"audio":False})
    assert client.get(f"/api/v1/patients/{elder_id}/overview",headers=caregiver).json()["location"] is None
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM locations").fetchone()[0]==0
    client.post("/api/v1/consents",headers=elder,json={"location":True,"audio":False})
    location["recorded_at"]=stamp()
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==201
    process_due_reminders(app.state.db,int(time.time())+86401)
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM locations").fetchone()[0]==0


def test_audio_privacy_and_confirmed_sos(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    event={"type":"audio_impact","confirmed":False,"occurred_at":stamp(),"source":"synthetic"}
    assert client.post("/api/v1/events",headers=elder,json=event).status_code==403
    client.post("/api/v1/consents",headers=elder,json={"location":False,"audio":True})
    assert client.post("/api/v1/events",headers=elder,json={**event,"audio":"secret recording"}).status_code==422
    response=client.post("/api/v1/events",headers=elder,json={**event,"source":"device_diagnostic"})
    assert not response.json()["alert_created"]
    assert client.post("/api/v1/events",headers=elder,json={**event,"type":"sos"}).status_code==422
    response=client.post("/api/v1/events",headers=elder,json={**event,"type":"sos","confirmed":True,"source":"device_diagnostic"})
    assert response.json()["alert_created"]
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert len(alerts)==1 and alerts[0]["type"]=="sos"


def test_encrypted_pii_sensor_payloads_notifications_and_no_raw_tokens(service):
    client,app,settings=service
    elder_id,elder,data=account(client,"privateidentity")
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver);create_alert(client,elder)
    with app.state.db.connection() as conn:
        profile=conn.execute("SELECT profile,password_hash FROM users WHERE id=?",(elder_id,)).fetchone()
        assert "privateidentity" not in profile["profile"]
        assert profile["password_hash"].startswith("$argon2id$")
        assert "heart_rate_bpm" not in conn.execute("SELECT envelope FROM readings LIMIT 1").fetchone()[0]
        assert "Pulso" not in conn.execute("SELECT data FROM notifications LIMIT 1").fetchone()[0]
        assert data["access_token"] not in str([tuple(r) for r in conn.execute("SELECT * FROM sessions")])
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    database=settings.db_path.read_bytes()
    assert b"privateidentity@example.com" not in database
    assert b'"heart_rate_bpm"' not in database
    assert data["access_token"].encode() not in database
    with app.state.db.connection() as conn:
        audit=" ".join(str(tuple(row)) for row in conn.execute("SELECT * FROM audit"))
        assert "privateidentity" not in audit and "heart_rate_bpm" not in audit


def test_request_limits_headers_duplicate_keys_and_no_audio_upload(service):
    client,_,_=service
    _,elder,_=account(client)
    assert client.get("/health").headers["cache-control"]=="no-store"
    assert client.get("/health").headers["x-content-type-options"]=="nosniff"
    assert client.post("/api/v1/readings",headers=elder,content=b"x"*65537).status_code==413
    assert client.post("/api/v1/auth/login",headers={"Content-Type":"application/json"},content='{"email":"a@example.com","email":"b@example.com","password":"private"}').status_code==400
    assert client.post("/api/v1/readings",headers={**elder,"Content-Type":"application/json"},content='{"x":NaN}').status_code==400
    bad=sample();bad["payload"]["microphone"]={"raw_audio":"secret"}
    assert client.post("/api/v1/readings",headers=elder,json=bad).status_code==422
    bad=sample();bad["captured_at"]="2026-10-25T12:00:00"
    assert client.post("/api/v1/readings",headers=elder,json=bad).status_code==422


def test_https_required_when_enabled(tmp_path):
    settings=Settings(data_dir=tmp_path,enforce_https=True)
    settings.initialize_key()
    with TestClient(create_app(settings)) as client:
        assert client.get("/health").status_code==400
    with TestClient(create_app(settings),base_url="https://testserver") as client:
        response=client.get("/health")
        assert response.status_code==200
        assert response.headers["strict-transport-security"]=="max-age=31536000"


def test_sustained_condition_one_episode_valid_recovery_then_new_episode(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    for i in range(3):
        client.post("/api/v1/readings",headers=elder,json=sample(f"high-{i}",now-9+i,pulse=150,oxygen=94))
    with app.state.db.transaction() as conn:
        # Even a long-lived unresolved condition cannot generate an alert every ten minutes.
        conn.execute("UPDATE alerts SET created_at=?",(now-1000,))
    for i in range(3):
        result=client.post("/api/v1/readings",headers=elder,json=sample(f"sustain-{i}",now-6+i,pulse=150,oxygen=94)).json()
        assert not result["alert_created"]
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert len(alerts)==2  # One pulse episode and one saturation episode.
    for i in range(3):
        payload=sample(f"invalid-{i}",now-3+i)
        payload["payload"]["max30102"]["finger_detected"]=False
        client.post("/api/v1/readings",headers=elder,json=payload)
    assert all(a["episode_status"]=="open" for a in client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"])
    for i in range(3):
        client.post("/api/v1/readings",headers=elder,json=sample(f"normal-{i}",now+1+i))
    assert all(a["episode_status"]=="recovered" for a in client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"])
    for i in range(3):
        client.post("/api/v1/readings",headers=elder,json=sample(f"new-{i}",now+4+i,pulse=150,oxygen=94))
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert len(alerts)==4
    assert sum(a["episode_status"]=="open" for a in alerts)==2


def test_location_regrant_rejects_prior_capture(service):
    client,_,_=service
    _,elder,_=account(client)
    location={"outside_home":True,"latitude":0,"longitude":0,"accuracy_m":10,"recorded_at":stamp(time.time()-20)}
    client.post("/api/v1/consents",headers=elder,json={"location":True,"audio":False})
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==422
    location["recorded_at"]=stamp()
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==201
    client.post("/api/v1/consents",headers=elder,json={"location":False,"audio":False})
    client.post("/api/v1/consents",headers=elder,json={"location":True,"audio":False})
    assert client.post("/api/v1/location",headers=elder,json=location).status_code==422


def test_cancel_reminder_authorization_and_pending_fanout_cleanup(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    _,stranger,_=account(client,"stranger","caregiver")
    link(client,elder,caregiver)
    response=client.post(f"/api/v1/patients/{elder_id}/reminders",headers=caregiver,json={"title":"Prueba cancelación","due_at":stamp(time.time()-1),"repeat_hours":1})
    reminder=response.json()
    process_due_reminders(app.state.db)
    assert len(client.get("/api/v1/notifications",headers=elder).json()["items"])==1
    assert client.delete("/api/v1/reminders/"+reminder["id"],headers=stranger).status_code==404
    assert client.delete("/api/v1/reminders/"+reminder["id"],headers=elder).json()["status"]=="cancelled"
    assert client.get("/api/v1/notifications",headers=elder).json()["items"]==[]
    assert client.get("/api/v1/notifications",headers=caregiver).json()["items"]==[]
    process_due_reminders(app.state.db,int(time.time())+7200)
    assert client.get("/api/v1/notifications",headers=elder).json()["items"]==[]
    assert client.post("/api/v1/reminders/"+reminder["id"]+"/taken",headers=elder).status_code==409
    record=client.get(f"/api/v1/patients/{elder_id}/reminders",headers=elder).json()["reminders"][0]
    assert record["cancelled_at"] and not record["active"]


def test_caregiver_revocation_cancels_their_assigned_reminders(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    caregiver_id,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    response=client.post(f"/api/v1/patients/{elder_id}/reminders",headers=caregiver,json={"title":"Prueba revocación","due_at":stamp(time.time()-1),"repeat_hours":1})
    assert response.status_code==201
    process_due_reminders(app.state.db)
    client.delete("/api/v1/caregivers/"+caregiver_id,headers=elder)
    assert client.get("/api/v1/notifications",headers=elder).json()["items"]==[]
    record=client.get(f"/api/v1/patients/{elder_id}/reminders",headers=elder).json()["reminders"][0]
    assert not record["active"] and record["cancelled_at"]


def test_authenticated_write_limits_and_thirty_day_reading_retention(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    first=client.post("/api/v1/readings",headers=elder,json=sample("old",now-30*86400+10))
    assert first.status_code==201
    process_due_reminders(app.state.db,now+20)
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT COUNT(*) FROM readings").fetchone()[0]==0
        conn.execute("INSERT INTO rate_limits(scope,bucket,window_start,count) VALUES ('readings:user',?,?,180) ON CONFLICT(scope,bucket,window_start) DO UPDATE SET count=180",(elder_id,now))
    assert client.post("/api/v1/readings",headers=elder,json=sample("limited")).status_code==429
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM readings").fetchone()[0]==0


def test_sensor_variant_diagnostics_and_phone_metadata_accepted(service):
    client,_,_=service
    _,elder,_=account(client)
    payload=sample()
    payload["payload"].update({"phone_received_at":stamp(),"sample_id":"firmware-1","boot_nonce":"boot-random"})
    payload["payload"]["max30102"].update({"online":True,"sample_age_ms":10,"result_age_ms":20,"fifo_reader":"checked_direct","sample_loss_events":1,"samples_dropped_is_minimum":True,"overflow_recovery_successes":0})
    payload["payload"]["gy906"]["status"]="ok"
    assert client.post("/api/v1/readings",headers=elder,json=payload).status_code==201


def test_private_longpoll_delivers_durable_items_and_empty_heartbeat(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver")
    link(client,elder,caregiver)
    empty=client.get("/api/v1/notifications/wait?timeout=1",headers=caregiver)
    assert empty.status_code==200 and empty.json()=={"items":[],"cursor":0}
    create_alert(client,elder)
    ready=client.get("/api/v1/notifications/wait?timeout=1",headers=caregiver)
    assert ready.status_code==200 and len(ready.json()["items"])==1
    cursor=ready.json()["cursor"]
    assert client.get(f"/api/v1/notifications/wait?timeout=1&after={cursor}",headers=caregiver).json()=={"items":[],"cursor":cursor}
    assert client.get("/api/v1/notifications/wait?timeout=1").status_code==401


def test_private_longpoll_rechecks_logout_while_waiting(service):
    from concurrent.futures import ThreadPoolExecutor
    client,_,_=service
    _,elder,_=account(client)
    with ThreadPoolExecutor(max_workers=1) as executor:
        future=executor.submit(client.get,"/api/v1/notifications/wait?timeout=3",headers=elder)
        time.sleep(.1)
        assert client.post("/api/v1/auth/logout",headers=elder).status_code==200
        response=future.result(timeout=5)
    assert response.status_code==401
