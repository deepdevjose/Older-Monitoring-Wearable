"""Transparent synthetic demonstrations, never clinical interpretation of device data."""
import json
import math
import time
import uuid
from .push import fanout, iso
from .system_text import ALERT_TEXT

CAPABILITIES = [
    {"id":"synthetic_rules","status":"educational_only","reason":"Three fresh, valid synthetic samples demonstrate configurable rules; no clinical validation."},
    {"id":"device_signals","status":"diagnostic_only","reason":"ESP32 samples are stored as diagnostics and cannot activate physiological alerts."},
    {"id":"fall_detection","status":"unvalidated","reason":"One-second movement snapshots cannot validate a fall; only synthetic events or confirmed SOS are demonstrated."},
    {"id":"blood_pressure","status":"unavailable","reason":"The installed sensors do not measure blood pressure."},
    {"id":"dehydration","status":"reminder_only","reason":"Dehydration cannot be diagnosed from these sensors. Use an agreed hydration reminder."},
    {"id":"wear_detection","status":"uncertain","reason":"Darkness alone does not establish wear; optical contact only corroborates an unvalidated inference."},
    {"id":"body_temperature","status":"surface_only","reason":"GY906 measures surface and ambient temperature, not validated core temperature."},
    {"id":"oxygen_high","status":"unavailable","reason":"A saturation above 100 percent is invalid; these sensors cannot diagnose hyperoxia."},
    {"id":"notifications","status":"private_long_poll","reason":"Private durable inbox over self-hosted HTTPS long polling. Delivery requires the opted-in phone service and connectivity; no cloud service or emergency delivery guarantee."},
]


def number(value):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)


def conditions(payload):
    out = {}
    ppg = payload.get("max30102",{})
    contact = ppg.get("finger_detected") is True
    ready = ppg.get("status") in {"ok","valid","partial"}
    hr = ppg.get("heart_rate_bpm")
    if contact and ready and ppg.get("heart_rate_valid") is True and number(hr) and 30<=hr<=220:
        if hr<50:
            out["pulse_low"] = ALERT_TEXT["pulse_low"]
        elif hr>120:
            out["pulse_high"] = ALERT_TEXT["pulse_high"]
    oxygen = ppg.get("spo2_percent")
    if contact and ready and ppg.get("spo2_valid") is True and number(oxygen) and 70<=oxygen<95:
        out["oxygen_low"] = ALERT_TEXT["oxygen_low"]
    temp = payload.get("gy906",{})
    surface,ambient = temp.get("object_temp_c"),temp.get("ambient_temp_c")
    if temp.get("online") is True and number(ambient) and -20<=ambient<=60:
        if ambient<10 or ambient>35:
            out["environment_extreme"] = ALERT_TEXT["environment_extreme"]
        if number(surface) and 10<=surface<=50 and (surface<28 or surface>39):
            out["surface_temperature_extreme"] = ALERT_TEXT["surface_temperature_extreme"]
    return out


def create_alert(conn, db, elder_id, kind, title, message, source, *, now=None, severity="attention"):
    now = int(time.time()) if now is None else now
    alert_id = uuid.uuid4().hex
    detail = {"severity":severity,"title":title,"message":message,"educational":True}
    conn.execute("INSERT INTO alerts(id,elder_id,type,created_at,source,detail) VALUES (?,?,?,?,?,?)",
                 (alert_id,elder_id,kind,now,source,db.vault.seal(detail,f"alert:{alert_id}")))
    fanout(conn,db,elder_id,"alert",{"patient_id":elder_id,"alert_id":alert_id,"type":kind,**detail},
           include_elder=True,occurrence_key=f"alert:{alert_id}",now=now)
    return alert_id


def evaluate_reading(conn, db, elder_id, reading, *, now=None):
    now = int(time.time()) if now is None else now
    captured = int(reading.captured_at.timestamp())
    if reading.source != "synthetic" or not now-120<=captured<=now+30:
        return []
    # Latest by captured timestamp; late/out-of-order packets cannot replay old rules.
    rows = conn.execute("SELECT * FROM readings WHERE elder_id=? ORDER BY captured_at DESC,id DESC LIMIT 3",(elder_id,)).fetchall()
    if len(rows)<3 or any(row["source"]!="synthetic" or not now-120<=row["captured_at"]<=now+30 for row in rows):
        return []
    if rows[0]["sample_id"] != reading.sample_id:
        return []
    stamps = [row["captured_at"] for row in rows]
    if any(not 1<=stamps[i]-stamps[i+1]<=30 for i in range(2)):
        return []
    candidates = []
    for row in rows:
        envelope = db.vault.open(row["envelope"],f"reading:{row['id']}")
        candidates.append(conditions(envelope["payload"]))
    created = []
    # Recovery requires three *valid* normal observations of this specific signal.
    # Invalid contact, disconnected sensors and missing values can never close an episode.
    def eligible(payload,kind):
        ppg=payload.get("max30102",{})
        contact=ppg.get("finger_detected") is True and ppg.get("status") in {"ok","valid","partial"}
        if kind in {"pulse_low","pulse_high"}:
            value=ppg.get("heart_rate_bpm")
            return contact and ppg.get("heart_rate_valid") is True and number(value) and 30<=value<=220
        if kind=="oxygen_low":
            value=ppg.get("spo2_percent")
            return contact and ppg.get("spo2_valid") is True and number(value) and 70<=value<=100
        temp=payload.get("gy906",{})
        ambient,surface=temp.get("ambient_temp_c"),temp.get("object_temp_c")
        base=temp.get("online") is True and number(ambient) and -20<=ambient<=60
        if kind=="environment_extreme":
            return base
        return base and number(surface) and 10<=surface<=50

    payloads=[db.vault.open(row["envelope"],f"reading:{row['id']}")["payload"] for row in rows]
    for state in conn.execute("SELECT * FROM alert_rule_state WHERE elder_id=? AND active_alert_id IS NOT NULL",(elder_id,)).fetchall():
        kind=state["type"]
        if all(eligible(payload,kind) and kind not in condition for payload,condition in zip(payloads,candidates)):
            conn.execute("UPDATE alerts SET recovered_at=? WHERE id=?",(now,state["active_alert_id"]))
            conn.execute("UPDATE alert_rule_state SET active_alert_id=NULL,updated_at=? WHERE elder_id=? AND type=?",(now,elder_id,kind))
    for kind,(title,message) in candidates[0].items():
        if not all(kind in candidate for candidate in candidates[1:]):
            continue
        active=conn.execute("SELECT active_alert_id FROM alert_rule_state WHERE elder_id=? AND type=?",(elder_id,kind)).fetchone()
        if active and active[0]:
            continue
        alert_id=create_alert(conn,db,elder_id,kind,title,message,"synthetic",now=now)
        conn.execute("INSERT INTO alert_rule_state VALUES (?,?,?,?) ON CONFLICT(elder_id,type) DO UPDATE SET active_alert_id=excluded.active_alert_id,updated_at=excluded.updated_at",(elder_id,kind,alert_id,now))
        created.append(alert_id)
    return created


def wear_inference(payload):
    photo=payload.get("photoresistor",{})
    ppg=payload.get("max30102",{})
    light=photo.get("percent")
    if not number(light) or not 0<=light<=100:
        return {"state":"unknown","validated":False,"reason":"No valid light sample"}
    if light<10 and ppg.get("finger_detected") is True and ppg.get("heart_rate_valid") is True:
        return {"state":"possible_worn","validated":False,"reason":"Darkness with optical contact is only corroborating evidence"}
    return {"state":"uncertain","validated":False,"reason":"Light level alone cannot determine whether the wearable is being worn"}
