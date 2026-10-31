"""Sustained sensor-limit notices, separate from clinical diagnosis and synthetic examples."""
import json
import math
import time
import uuid
from .push import fanout, iso
from .system_text import ALERT_TEXT

DEVICE_ALERT_TEXT = {
    "pulse_low": ("Low heart rate estimate", "Three sensor estimates were below the configured 50 bpm limit. Review the reading and sensor contact."),
    "pulse_high": ("High heart rate estimate", "Three sensor estimates exceeded the configured 120 bpm limit. Review the reading and sensor contact."),
    "oxygen_low": ("Low oxygen estimate", "Three sensor estimates were below the configured 95% limit. Review the reading and sensor contact."),
    "environment_extreme": ("Check ambient temperature", "Three readings were outside the configured 10–35°C ambient range. Review the surroundings."),
    "surface_temperature_extreme": ("Check surface temperature", "Three readings were outside the configured 28–39°C surface range. Review the reading and sensor position."),
}

CAPABILITIES = [
    {"id":"synthetic_rules","status":"educational_only","reason":"Three fresh, valid synthetic samples demonstrate configurable rules; no clinical validation."},
    {"id":"device_signals","status":"configured_limit_notices","reason":"Three fresh sensor estimates outside configured limits can create a review notice. Raw estimates and quality flags are retained; these rules are not clinically validated."},
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
    hr = ppg.get("heart_rate_bpm")
    if contact and number(hr) and hr != -999:
        if hr<50:
            out["pulse_low"] = ALERT_TEXT["pulse_low"]
        elif hr>120:
            out["pulse_high"] = ALERT_TEXT["pulse_high"]
    oxygen = ppg.get("spo2_percent")
    if contact and number(oxygen) and oxygen != -999 and oxygen<95:
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
    if reading.source not in {"synthetic","device_diagnostic"} or not now-120<=captured<=now+30:
        return []
    # Latest by captured timestamp; late/out-of-order packets cannot replay old rules.
    rows = conn.execute("SELECT * FROM readings WHERE elder_id=? AND source=? ORDER BY captured_at DESC,id DESC LIMIT 3",(elder_id,reading.source)).fetchall()
    if len(rows)<3 or any(not now-120<=row["captured_at"]<=now+30 for row in rows):
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
    # Recovery requires three present, normal estimates of this specific signal.
    # Missing contact or an absent computation cannot close an episode.
    def eligible(payload,kind):
        ppg=payload.get("max30102",{})
        contact=ppg.get("finger_detected") is True
        if kind in {"pulse_low","pulse_high"}:
            value=ppg.get("heart_rate_bpm")
            return contact and number(value) and value != -999
        if kind=="oxygen_low":
            value=ppg.get("spo2_percent")
            return contact and number(value) and value != -999
        temp=payload.get("gy906",{})
        ambient,surface=temp.get("ambient_temp_c"),temp.get("object_temp_c")
        base=temp.get("online") is True and number(ambient) and -20<=ambient<=60
        if kind=="environment_extreme":
            return base
        return base and number(surface) and 10<=surface<=50

    payloads=[db.vault.open(row["envelope"],f"reading:{row['id']}")["payload"] for row in rows]
    for state in conn.execute("SELECT s.*,a.type AS alert_kind FROM alert_rule_state s JOIN alerts a ON a.id=s.active_alert_id WHERE s.elder_id=? AND a.source=?",(elder_id,reading.source)).fetchall():
        kind=state["alert_kind"]
        if all(eligible(payload,kind) and kind not in condition for payload,condition in zip(payloads,candidates)):
            conn.execute("UPDATE alerts SET recovered_at=? WHERE id=?",(now,state["active_alert_id"]))
            conn.execute("UPDATE alert_rule_state SET active_alert_id=NULL,updated_at=? WHERE elder_id=? AND type=?",(now,elder_id,state["type"]))
    for kind,(title,message) in candidates[0].items():
        if not all(kind in candidate for candidate in candidates[1:]):
            continue
        rule_key=kind if reading.source=="synthetic" else "device_diagnostic:"+kind
        active=conn.execute("SELECT active_alert_id FROM alert_rule_state WHERE elder_id=? AND type=?",(elder_id,rule_key)).fetchone()
        if active and active[0]:
            continue
        if reading.source=="device_diagnostic":
            title,message=DEVICE_ALERT_TEXT[kind]
        alert_id=create_alert(conn,db,elder_id,kind,title,message,reading.source,now=now)
        conn.execute("INSERT INTO alert_rule_state VALUES (?,?,?,?) ON CONFLICT(elder_id,type) DO UPDATE SET active_alert_id=excluded.active_alert_id,updated_at=excluded.updated_at",(elder_id,rule_key,alert_id,now))
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
