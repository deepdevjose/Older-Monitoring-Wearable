"""Raw optical visibility, limit notices and separation of real/synthetic episodes."""
import time

import pytest

from test_api import account, link, sample, service
from test_history import get_history


def put(client,headers,key,at,pulse,oxygen=98,source="device_diagnostic"):
    body=sample(key,at,source,pulse=pulse,oxygen=oxygen)
    body["payload"]["max30102"].update(status="poor_signal",heart_rate_valid=False,spo2_valid=False)
    result=client.post("/api/v1/readings",headers=headers,json=body)
    assert result.status_code==201,result.text
    return result.json()


@pytest.mark.parametrize("pulse,oxygen,expected",[(49,98,{"pulse_low"}),(50,95,set()),(120,95,set()),(121,98,{"pulse_high"}),(450,65,{"pulse_high","oxygen_low"}),(75,130,set())])
def test_unfiltered_estimates_are_stored_and_only_limits_create_notices(service,pulse,oxygen,expected):
    client,app,_=service
    elder_id,elder,_=account(client)
    _,caregiver,_=account(client,"carer","caregiver");link(client,elder,caregiver)
    now=int(time.time())
    for i in range(3):
        result=put(client,elder,f"raw-{i}",now-8+i,pulse,oxygen)
        if i<2:assert not result["alert_created"]
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert {a["type"] for a in alerts}==expected
    assert all(a["source"]=="device_diagnostic" and "Simulated" not in a["title"] for a in alerts)
    for metric,value in [("heart_rate_bpm",pulse),("spo2_percent",oxygen)]:
        result=get_history(client,elder_id,caregiver,metric).json()
        assert result["summary"]["count"]==3
        assert result["summary"]["latest"]==value
    notices=client.get("/api/v1/notifications",headers=caregiver).json()["items"]
    assert {item["data"]["type"] for item in notices if item["type"]=="alert"}==expected
    with app.state.db.connection() as conn:
        row=conn.execute("SELECT id,envelope FROM readings WHERE elder_id=? ORDER BY id DESC LIMIT 1",(elder_id,)).fetchone()
        payload=app.state.db.vault.open(row["envelope"],f"reading:{row['id']}")["payload"]["max30102"]
        assert payload["heart_rate_bpm"]==pulse and payload["spo2_percent"]==oxygen
        assert payload["heart_rate_valid"] is False and payload["spo2_valid"] is False


def test_device_and_synthetic_episodes_cannot_recover_or_suppress_each_other(service):
    client,_,_=service
    elder_id,elder,_=account(client);now=int(time.time())
    for i in range(3):put(client,elder,f"device-{i}",now-15+i,450,65)
    for i in range(3):put(client,elder,f"synthetic-{i}",now-12+i,75,98,source="synthetic")
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert len(alerts)==2 and all(a["episode_status"]=="open" for a in alerts)
    for i in range(3):assert not put(client,elder,f"same-episode-{i}",now-9+i,450,65)["alert_created"]
    for i in range(3):put(client,elder,f"recovery-{i}",now-6+i,75,98)
    alerts=client.get(f"/api/v1/patients/{elder_id}/alerts",headers=elder).json()["alerts"]
    assert len(alerts)==2 and all(a["episode_status"]=="recovered" for a in alerts)


def test_history_policy_upgrade_reindexes_without_altering_original_readings(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    put(client,elder,"retained",int(time.time())-20,450,130)
    with app.state.db.transaction() as conn:
        original=tuple(conn.execute("SELECT id,envelope,digest FROM readings WHERE elder_id=?",(elder_id,)).fetchone())
        conn.execute("UPDATE metric_history_policy SET version=1")
    app.state.db.initialize()
    with app.state.db.connection() as conn:
        assert tuple(conn.execute("SELECT id,envelope,digest FROM readings WHERE elder_id=?",(elder_id,)).fetchone())==original
        assert conn.execute("SELECT COUNT(*) FROM metric_history_indexed").fetchone()[0]==0
    assert get_history(client,elder_id,elder).json()["summary"]["latest"]==450
    app.state.db.initialize()
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM metric_history_indexed").fetchone()[0]==1


@pytest.mark.parametrize("value",[None,-999])
def test_missing_computation_does_not_become_a_measurement_or_notice(service,value):
    client,_,_=service;elder_id,elder,_=account(client);now=int(time.time())
    for i in range(3):assert not put(client,elder,f"absent-{i}",now-5+i,value,value)["alert_created"]
    assert get_history(client,elder_id,elder).json()["summary"]["count"]==0


def test_large_finite_estimates_do_not_overflow_history(service):
    client,_,_=service;elder_id,elder,_=account(client);now=int(time.time())
    for i in range(3):put(client,elder,f"large-{i}",now-5+i,1e308,1e308)
    for metric in ("heart_rate_bpm","spo2_percent"):
        result=get_history(client,elder_id,elder,metric)
        assert result.status_code==200
        assert result.json()["summary"]["average"]==1e308
