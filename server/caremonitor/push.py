"""Durable private inbox/outbox. Clients poll over authenticated TLS.

This local deployment does not pretend to have FCM/APNs delivery. Notifications remain
in SQLite across restarts; each device acknowledges receipt separately from alert review.
"""
import time


def enqueue(conn, db, user_id, elder_id, kind, data, occurrence_key=None, now=None):
    now = int(time.time()) if now is None else now
    cursor = conn.execute(
        "INSERT OR IGNORE INTO notifications(user_id,elder_id,type,created_at,data,occurrence_key) VALUES (?,?,?,?,?,?)",
        (user_id,elder_id,kind,now,"pending",occurrence_key),
    )
    if cursor.rowcount:
        notification_id = cursor.lastrowid
        conn.execute("UPDATE notifications SET data=? WHERE id=?",
                     (db.vault.seal(data, f"notification:{notification_id}"),notification_id))
        return notification_id
    return None


def fanout(conn, db, elder_id, kind, data, *, include_elder=False, occurrence_key=None, now=None):
    recipients = [row[0] for row in conn.execute("SELECT caregiver_id FROM memberships WHERE elder_id=?",(elder_id,))]
    if include_elder:
        recipients.append(elder_id)
    for user_id in recipients:
        enqueue(conn,db,user_id,elder_id,kind,data,occurrence_key,now)
    return len(recipients)


def process_due_reminders(db, now=None):
    """Advance schedules atomically. Unique occurrence keys make restarts/retries safe.

    A long outage emits the latest missed occurrence once, with missed_occurrences.
    It never floods a phone with days of repeated medicine prompts or duplicates doses.
    """
    now = int(time.time()) if now is None else now
    with db.transaction() as conn:
        conn.execute("DELETE FROM locations WHERE expires_at<=?",(now,))
        conn.execute("DELETE FROM notifications WHERE created_at<?",(now-30*86400,))
        conn.execute("DELETE FROM readings WHERE captured_at<?",(now-30*86400,))
        conn.execute("DELETE FROM events WHERE created_at<?",(now-30*86400,))
        conn.execute("DELETE FROM sessions WHERE expires_at<?",(now-7*86400,))
        conn.execute("DELETE FROM rate_limits WHERE window_start<?",(now-86400,))
        conn.execute("DELETE FROM audit WHERE created_at<?",(now-90*86400,))
        due = conn.execute("SELECT * FROM reminders WHERE active=1 AND next_due_at<=? ORDER BY next_due_at LIMIT 100",(now,)).fetchall()
        for reminder in due:
            repeat = reminder["repeat_hours"]
            original_due = reminder["next_due_at"]
            missed = 0
            occurrence_due = original_due
            if repeat:
                interval = repeat*3600
                missed = (now-original_due)//interval
                occurrence_due += missed*interval
            inserted = conn.execute("INSERT OR IGNORE INTO reminder_occurrences(reminder_id,due_at,notified_at) VALUES (?,?,?)",
                                    (reminder["id"],occurrence_due,now)).rowcount
            if inserted:
                detail = db.vault.open(reminder["detail"],f"reminder:{reminder['id']}")
                data = {"patient_id":reminder["elder_id"],"reminder_id":reminder["id"],**detail,
                        "due_at":iso(occurrence_due),"missed_occurrences":missed,
                        "message":"Recordatorio de cuidado. Confirma la indicación acordada; no dupliques una dosis omitida."}
                fanout(conn,db,reminder["elder_id"],"reminder",data,include_elder=True,
                       occurrence_key=f"reminder:{reminder['id']}:{occurrence_due}",now=now)
            if repeat:
                conn.execute("UPDATE reminders SET last_due_at=?,next_due_at=?,taken_at=NULL WHERE id=?",
                             (occurrence_due,occurrence_due+repeat*3600,reminder["id"]))
            else:
                conn.execute("UPDATE reminders SET last_due_at=?,active=0 WHERE id=?",(occurrence_due,reminder["id"]))
        return len(due)


def iso(timestamp):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(timestamp,timezone.utc).isoformat().replace("+00:00","Z")
