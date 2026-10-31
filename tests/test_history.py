"""History authorization, exact aggregation, source provenance and upgrade coverage."""
from datetime import datetime, timedelta, timezone
import time

import pytest

from test_api import account, link, sample, service, stamp
from caremonitor import history
from caremonitor.push import process_due_reminders


def get_history(client, elder_id, headers, metric="heart_rate_bpm", **query):
    return client.get(f"/api/v1/patients/{elder_id}/metrics/{metric}/history", headers=headers, params=query)


def send(client, headers, identifier, when, pulse=80, source="device_diagnostic", **sensors):
    data=sample(identifier, when, source, pulse=pulse)
    data["payload"].update(sensors)
    response=client.post("/api/v1/readings",headers=headers,json=data)
    assert response.status_code==201,response.text
    return response.json()


def test_history_is_private_and_revocation_immediate(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    caregiver_id,caregiver,_=account(client,"carer","caregiver")
    _,other,_=account(client,"other")
    assert get_history(client,elder_id,{}).status_code==401
    assert get_history(client,elder_id,caregiver).status_code==404
    assert get_history(client,elder_id,other).status_code==404
    link(client,elder,caregiver)
    assert get_history(client,elder_id,caregiver).status_code==200
    with app.state.db.transaction() as conn:
        conn.execute("DELETE FROM memberships WHERE elder_id=? AND caregiver_id=?",(elder_id,caregiver_id))
    assert get_history(client,elder_id,caregiver).status_code==404


def test_rollups_use_weighted_exact_means_and_are_encrypted(service):
    client,app,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600
    for identifier,when,value in [("a",end-8000,60),("b",end-7900,90),("c",end-4000,120)]:
        send(client,elder,identifier,when,value)
    result=get_history(client,elder_id,elder,period="week",end=stamp(end)).json()
    assert result["summary"]["count"]==3
    assert result["summary"]["average"]==90
    assert result["summary"]["min"]==60
    assert result["summary"]["max"]==120
    assert result["summary"]["latest"]==120
    assert sum(point["count"] for point in result["points"])==3
    assert result["coverage"]["complete"]
    assert result["aggregation"]=="mean"
    with app.state.db.connection() as conn:
        rows=conn.execute("SELECT * FROM metric_history_rollups").fetchall()
        assert rows
        assert all('heart_rate_bpm' not in row["detail"] and 'latest' not in row["detail"] for row in rows)
        assert conn.execute("SELECT COUNT(*) FROM metric_history_indexed").fetchone()[0]==3


def test_partial_hours_exclude_samples_outside_range_without_double_counting(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600-1800
    start=end-86400
    for identifier,when,value in [("before",start-1,200),("first",start,60),("middle",end-4000,90),("last",end-1,120),("after",end,180)]:
        send(client,elder,identifier,when,value)
    result=get_history(client,elder_id,elder,end=stamp(end)).json()
    assert result["summary"]["count"]==3
    assert result["summary"]["average"]==90
    assert result["summary"]["min"]==60
    assert result["summary"]["max"]==120
    assert len(result["points"])==25
    assert result["points"][0]["count"]==1
    assert result["points"][-1]["count"]==1


def test_invalid_optical_states_leave_gaps_and_sources_are_separate(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600
    send(client,elder,"physical",end-4000,72)
    send(client,elder,"synthetic",end-4000,180,source="synthetic")
    send(client,elder,"invalid",end-8000,max30102={"status":"no_finger","heart_rate_valid":False,"spo2_valid":False,"finger_detected":False,"heart_rate_bpm":None,"spo2_percent":None})
    physical=get_history(client,elder_id,elder,end=stamp(end)).json()
    synthetic=get_history(client,elder_id,elder,end=stamp(end),source="synthetic").json()
    assert physical["summary"]["count"]==1 and physical["summary"]["latest"]==72
    assert synthetic["summary"]["count"]==1 and synthetic["summary"]["latest"]==180
    assert physical["coverage"]["invalid_readings"]==1
    assert any(point["value"] is None and point["count"]==0 for point in physical["points"])
    assert physical["source"]=="device_diagnostic"


def test_each_metric_has_its_own_validity_and_physical_units(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600
    send(client,elder,"metrics",end-4000,gy906={"online":True,"status":"ok","object_temp_c":34,"ambient_temp_c":24},
         mpu6050={"online":True,"accel_g":{"x":0,"y":0,"z":1}},photoresistor={"adc_raw":123})
    for metric,value,unit in [("surface_temp_c",34,"°C"),("ambient_temp_c",24,"°C"),("light_adc",123,"ADC"),("acceleration_g",1,"g"),("spo2_percent",98,"%")]:
        result=get_history(client,elder_id,elder,metric,end=stamp(end)).json()
        assert result["summary"]["latest"]==value
        assert result["unit"]==unit
    send(client,elder,"offline",end-3900,gy906={"online":False,"object_temp_c":999,"ambient_temp_c":-999},
         mpu6050={"online":False,"accel_g":{"x":1,"y":1,"z":1}},photoresistor={"adc_raw":5000})
    for metric in ("surface_temp_c","ambient_temp_c","light_adc","acceleration_g"):
        result=get_history(client,elder_id,elder,metric,end=stamp(end)).json()
        assert result["summary"]["count"]==1
        assert result["coverage"]["invalid_readings"]==1


def test_duplicate_ingestion_does_not_duplicate_history(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    when=int(time.time())-100
    first=send(client,elder,"same",when)
    second=send(client,elder,"same",when)
    assert not first["duplicate"] and second["duplicate"]
    assert get_history(client,elder_id,elder).json()["summary"]["count"]==1


def test_empty_day_still_exposes_older_saved_dates_without_mixing_sources_or_people(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    _,other,_=account(client,"other-person")
    end=int(time.time())
    send(client,elder,"older-wearable",end-3*86400,72)
    send(client,elder,"newer-synthetic",end-100,180,source="synthetic")
    send(client,other,"other-person-wearable",end-50,120)
    result=get_history(client,elder_id,elder,end=stamp(end)).json()
    assert result["summary"]["count"]==0
    assert result["available_readings"]=={"count":1,"first_at":history.stamp(end-3*86400),"latest_at":history.stamp(end-3*86400)}
    # Following the date returned by the server reveals the real older sample.
    latest=get_history(client,elder_id,elder,end=stamp(end-3*86400+1)).json()
    assert latest["summary"]["count"]==1 and latest["summary"]["latest"]==72
    synthetic=get_history(client,elder_id,elder,source="synthetic").json()
    assert synthetic["available_readings"]["count"]==1
    assert synthetic["available_readings"]["latest_at"]==history.stamp(end-100)


def test_saved_dates_describe_packets_without_claiming_sensor_validity(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    send(client,elder,"no-finger",int(time.time())-100,
         max30102={"status":"no_contact","finger_detected":False,"heart_rate_valid":False,"spo2_valid":False})
    result=get_history(client,elder_id,elder).json()
    assert result["summary"]["count"]==0
    assert result["available_readings"]["count"]==1
    assert result["coverage"]["invalid_readings"]==1


def test_legacy_backfill_is_idempotent_and_partial_coverage_explicit(service,monkeypatch):
    client,app,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600
    for i in range(3):send(client,elder,f"legacy-{i}",end-7200+i,60+i*30)
    with app.state.db.transaction() as conn:
        conn.execute("DELETE FROM metric_history_indexed")
        conn.execute("DELETE FROM metric_history_rollups")
    monkeypatch.setattr(history,"BACKFILL_LIMIT",2)
    first=get_history(client,elder_id,elder,end=stamp(end)).json()
    assert first["summary"]["count"]==2
    assert first["coverage"]["complete"] is False
    assert first["coverage"]["omitted_readings"]==1
    second=get_history(client,elder_id,elder,end=stamp(end)).json()
    third=get_history(client,elder_id,elder,end=stamp(end)).json()
    assert second["coverage"]["complete"] is True
    assert second["summary"]["count"]==3
    assert third["summary"]==second["summary"]
    assert second["summary"]["average"]==90


def test_edge_scan_cap_reports_omissions(service,monkeypatch):
    client,_,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600-1200
    for i in range(3):send(client,elder,f"edge-{i}",end-20+i,60+i)
    monkeypatch.setattr(history,"EDGE_LIMIT",2)
    result=get_history(client,elder_id,elder,end=stamp(end)).json()
    assert result["summary"]["count"]==2
    assert not result["coverage"]["complete"]
    assert result["coverage"]["omitted_readings"]==1


def test_retention_hides_expired_rollups_and_cleanup_cascades_indexes(service,monkeypatch):
    client,app,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())
    captured=now-7200
    send(client,elder,"expires",captured,100)
    future=now+30*86400+3600
    # Query must enforce retention before the scheduler next gets a turn.
    monkeypatch.setattr(history,"current_time",lambda:future)
    result=get_history(client,elder_id,elder,end=stamp(now)).json()
    assert result["summary"]["count"]==0
    assert result["summary"]["latest"] is None
    assert result["available_readings"]=={"count":0,"first_at":None,"latest_at":None}
    process_due_reminders(app.state.db,future)
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM readings").fetchone()[0]==0
        assert conn.execute("SELECT COUNT(*) FROM metric_history_indexed").fetchone()[0]==0
        assert conn.execute("SELECT COUNT(*) FROM metric_history_rollups").fetchone()[0]==0


def test_retention_cutoff_inside_hour_uses_retained_raw_samples_only(service,monkeypatch):
    client,_,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())//3600*3600
    send(client,elder,"old-boundary",now-7200,180)
    send(client,elder,"retained-boundary",now-6500,60)
    future=now-6800+30*86400
    monkeypatch.setattr(history,"current_time",lambda:future)
    result=get_history(client,elder_id,elder,period="month",end=stamp(now)).json()
    assert result["summary"]["count"]==1
    assert result["summary"]["latest"]==60


def test_partial_retention_hour_stays_exact_after_purge(service,monkeypatch):
    client,app,_=service
    elder_id,elder,_=account(client)
    now=int(time.time())//3600*3600
    send(client,elder,"expired-in-boundary",now-7200,180)
    send(client,elder,"kept-in-boundary",now-6500,60)
    send(client,elder,"kept-full-hour",now-3000,90)
    future=now-6800+30*86400
    monkeypatch.setattr(history,"current_time",lambda:future)
    before=get_history(client,elder_id,elder,period="month",end=stamp(now)).json()
    process_due_reminders(app.state.db,future)
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT COUNT(*) FROM readings").fetchone()[0]==2
        assert conn.execute("SELECT COUNT(*) FROM metric_history_indexed").fetchone()[0]==2
        assert conn.execute("SELECT COUNT(*) FROM metric_history_rollups").fetchone()[0]==2
    # The future scheduler run expires sessions too. Check aggregation directly
    # instead of bypassing the API's expired authentication.
    with app.state.db.transaction() as conn:
        after=history.metric_history(conn,app.state.db,elder_id,"heart_rate_bpm","month",now,"device_diagnostic")
    assert after["summary"]==before["summary"]
    assert after["summary"]["count"]==2 and after["summary"]["average"]==75


def test_scheduler_expires_learning_records_on_same_retention_boundary(service):
    client,app,_=service
    elder_id,_,_=account(client)
    now=int(time.time())
    cutoff=now-30*86400
    with app.state.db.transaction() as conn:
        for identifier,created in [("expired-learning",cutoff-1),("retained-learning",cutoff)]:
            detail=app.state.db.vault.seal({"source":"synthetic"},f"learning:{identifier}")
            conn.execute("INSERT INTO learning_records VALUES (?,?,?,?,?)",(identifier,elder_id,identifier,created,detail))
    process_due_reminders(app.state.db,now)
    with app.state.db.connection() as conn:
        assert [row[0] for row in conn.execute("SELECT id FROM learning_records")]==["retained-learning"]


def test_equivalent_timezone_offsets_select_identical_observations(service):
    client,_,_=service
    elder_id,elder,_=account(client)
    end=int(time.time())//3600*3600
    send(client,elder,"timezone",end-4000,78)
    utc=get_history(client,elder_id,elder,end=stamp(end)).json()
    local=datetime.fromtimestamp(end,timezone(timedelta(hours=8))).isoformat()
    offset=get_history(client,elder_id,elder,end=local).json()
    assert offset["start"]==utc["start"] and offset["end"]==utc["end"]
    assert offset["summary"]==utc["summary"] and offset["points"]==utc["points"]


def test_authorized_caregiver_rollups_remain_separate_per_patient(service):
    client,_,_=service
    first_id,first,_=account(client)
    second_id,second,_=account(client,"second")
    _,caregiver,_=account(client,"shared-carer","caregiver")
    link(client,first,caregiver)
    link(client,second,caregiver)
    end=int(time.time())//3600*3600
    send(client,first,"same-sample-id",end-4000,60)
    send(client,second,"same-sample-id",end-4000,120)
    for patient,expected in [(first_id,60),(second_id,120)]:
        result=get_history(client,patient,caregiver,end=stamp(end)).json()
        assert result["patient_id"]==patient
        assert result["summary"]["count"]==1 and result["summary"]["latest"]==expected


@pytest.mark.parametrize("query",[{"period":"year"},{"source":"combined"},{"end":"2026-01-01T12:00:00"},{"end":"2099-01-01T00:00:00Z"},{"end":"1960-01-01T00:00:00Z"}])
def test_invalid_ranges_and_sources_rejected(service,query):
    client,_,_=service
    elder_id,elder,_=account(client)
    assert get_history(client,elder_id,elder,**query).status_code==422
    assert get_history(client,elder_id,elder,"core_body_temperature").status_code==404


@pytest.mark.parametrize("period,maximum",[("day",25),("week",29),("month",31)])
def test_empty_history_keeps_bounded_real_timestamps_and_nulls(service,period,maximum):
    client,_,_=service
    elder_id,elder,_=account(client)
    result=get_history(client,elder_id,elder,period=period).json()
    assert result["summary"]["count"]==0
    assert result["summary"]["average"] is None
    assert result["summary"]["latest"] is None
    assert result["available_readings"]=={"count":0,"first_at":None,"latest_at":None}
    assert len(result["points"])<=maximum
    assert all(point["value"] is None for point in result["points"])
    assert all(point["start"]<point["end"] for point in result["points"])
    assert result["coverage"]["complete"]
