"""Synthetic possible-fall software states in temporary SQLite databases only."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest

from care_monitor_server import account_auth, fall_events as fall

START = datetime(2026, 10, 26, 8, 0, tzinfo=timezone.utc)


def stamp(seconds=0):
    return (START + timedelta(seconds=seconds)).isoformat(timespec="microseconds")


def connection(path):
    conn = sqlite3.connect(path, isolation_level=None, timeout=10)
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


class FallEventTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / "synthetic-fall.db")
        self.conn = connection(self.path)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("CREATE TABLE cm_devices(device_id TEXT PRIMARY KEY,patient_id TEXT NOT NULL)")
        self.conn.executemany("INSERT INTO cm_devices VALUES (?,?)", [("test-device", "test-patient"), ("other-device", "other-patient")])
        account_auth.initialise(self.conn)
        # Deliberately non-authenticating fixture hashes: no real accounts/data.
        self.conn.executemany("""INSERT INTO cm_users(username,role,patient_id,salt,password_hash,iterations,active)
            VALUES (?,?,?,'fixture','not-a-password',1,?)""", [
                ("assigned.carer", "caregiver", None, 1), ("inactive.carer", "caregiver", None, 0),
                ("unassigned.carer", "caregiver", None, 1), ("patient.account", "patient", "test-patient", 1)])
        self.conn.executemany("INSERT INTO cm_caregiver_assignments VALUES (?,?)", [
            ("assigned.carer", "test-patient"), ("inactive.carer", "test-patient"),
            ("unassigned.carer", "other-patient"), ("patient.account", "test-patient")])
        fall.initialize(self.conn)

    def tearDown(self):
        self.conn.close()
        self.temp.cleanup()

    def create(self, now=0, **overrides):
        data = dict(event_id="synthetic-event", device_id="test-device", patient_id="test-patient",
                    detected_at=stamp(), now=stamp(now), payload={"source": "synthetic_test", "detector_validated": False})
        data.update(overrides)
        return fall.create_event(self.conn, **data)

    def cancel(self, second):
        return fall.cancel_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient", now=stamp(second))

    def help(self, second):
        return fall.request_help(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient", now=stamp(second))

    def outbox_count(self):
        return self.conn.execute("SELECT COUNT(*) FROM cm_fall_outbox").fetchone()[0]

    def test_cancel_at_14_999_seconds_prevents_escalation(self):
        self.create()
        result = self.cancel(14.999)
        self.assertTrue(result["cancellation_accepted"])
        self.assertEqual(result["status"], "cancelled")
        self.assertEqual(fall.sweep_due(self.conn, stamp(15)), [])
        self.assertEqual(self.outbox_count(), 0)

    def test_cancel_at_exact_15_seconds_escalates_once(self):
        self.create()
        result = self.cancel(15)
        self.assertFalse(result["cancellation_accepted"])
        self.assertEqual(result["status"], "escalated")
        self.assertEqual(result["escalation_reason"], "countdown_expired")
        self.cancel(16)
        fall.sweep_due(self.conn, stamp(20))
        self.assertEqual(self.outbox_count(), 1)
        self.assertEqual(fall.list_outbox(self.conn)[0]["status"], "queued")

    def test_delayed_upload_does_not_restart_countdown(self):
        result = self.create(now=20)
        self.assertEqual(result["deadline_at"], stamp(15))
        self.assertEqual(result["received_at"], stamp(20))
        self.assertEqual(result["receipt_latency_ms"], 20000)
        self.assertEqual(result["status"], "escalated")
        self.assertEqual(self.outbox_count(), 1)

    def test_arrival_before_deadline_has_only_remaining_time(self):
        result = self.create(now=12)
        self.assertEqual(result["status"], "pending")
        self.assertEqual(result["deadline_at"], stamp(15))
        self.assertEqual(len(fall.sweep_due(self.conn, stamp(15))), 1)

    def test_duplicate_keeps_original_times_and_advances_expiry(self):
        first = self.create()
        second = self.create(now=5)
        self.assertTrue(second["duplicate"])
        self.assertEqual(first["received_at"], second["received_at"])
        third = self.create(now=20)
        self.assertEqual(third["status"], "escalated")
        self.assertEqual(self.outbox_count(), 1)

    def test_duplicate_conflicts_on_payload_device_or_detection_time(self):
        self.create()
        with self.assertRaises(fall.FallConflict):
            self.create(now=1, payload={"source": "different"})
        with self.assertRaises(fall.FallConflict):
            self.create(now=1, device_id="other-device", patient_id="other-patient")
        with self.assertRaises(fall.FallConflict):
            self.create(now=1, detected_at=stamp(1))

    def test_one_unresolved_event_per_device(self):
        self.create()
        with self.assertRaises(fall.FallConflict):
            self.create(now=1, event_id="new-event")
        self.cancel(2)
        second = self.create(now=3, event_id="new-event", detected_at=stamp(3))
        self.assertEqual(second["status"], "pending")

    def test_immediate_help_bypasses_countdown_and_is_idempotent(self):
        self.create()
        result = self.help(1)
        self.assertEqual(result["status"], "escalated")
        self.assertEqual(result["escalation_reason"], "immediate_help_requested")
        self.help(2)
        self.assertEqual(self.outbox_count(), 1)

    def test_restart_preserves_pending_deadline_and_queue(self):
        self.create()
        self.conn.close()
        self.conn = connection(self.path)
        fall.initialize(self.conn)
        self.assertEqual(len(fall.sweep_due(self.conn, stamp(15))), 1)
        self.conn.close()
        self.conn = connection(self.path)
        self.assertEqual(fall.sweep_due(self.conn, stamp(16)), [])
        self.assertEqual(self.outbox_count(), 1)
        self.assertEqual(fall.get_event(self.conn, "synthetic-event")["status"], "escalated")

    def test_only_active_assigned_caregivers_are_queued(self):
        self.create(now=15)
        recipients = [row[0] for row in self.conn.execute("SELECT recipient_username FROM cm_fall_outbox")]
        self.assertEqual(recipients, ["assigned.carer"])
        self.conn.execute("UPDATE cm_users SET active=0 WHERE username='assigned.carer'")
        self.assertEqual(fall.list_outbox(self.conn, status="pending"), [])
        self.assertEqual(self.outbox_count(), 1)  # durable audit remains

    def test_retry_is_durable_and_delivery_requires_ack_reference(self):
        self.create(now=15)
        item = fall.list_outbox(self.conn)[0]
        retried = fall.mark_retry(self.conn, outbox_id=item["id"], now=stamp(16), error="synthetic transport unavailable")
        self.assertEqual(retried["status"], "retry")
        self.assertEqual(fall.list_outbox(self.conn, status="pending", now=stamp(17)), [])
        self.assertEqual(len(fall.list_outbox(self.conn, status="pending", now=stamp(18))), 1)
        with self.assertRaises(ValueError):
            fall.mark_delivered(self.conn, outbox_id=item["id"], now=stamp(18), delivery_reference="")
        delivered = fall.mark_delivered(self.conn, outbox_id=item["id"], now=stamp(18), delivery_reference="synthetic-test-confirmation")
        self.assertEqual(delivered["status"], "delivered")
        repeated = fall.mark_delivered(self.conn, outbox_id=item["id"], now=stamp(19), delivery_reference="synthetic-test-confirmation")
        self.assertEqual(repeated["attempts"], 2)
        self.assertEqual(self.outbox_count(), 1)

    def test_invalid_future_naive_and_nonfinite_times_or_payload_rejected(self):
        for invalid in ["not-time", "2026-10-26T08:00:00", True, 1.0, "1960-01-01T00:00:00Z", "9999-12-31T23:59:59-23:59"]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.create(detected_at=invalid)
        with self.assertRaises(ValueError):
            self.create(detected_at=stamp(0.001))
        with self.assertRaises(ValueError):
            self.create(payload={"score": float("nan")})
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM cm_fall_events").fetchone()[0], 0)

    def test_equivalent_timezone_and_payload_key_order_are_idempotent(self):
        self.create(payload={"b": 2, "a": 1})
        result = self.create(now=1, detected_at="2026-10-26T16:00:00+08:00", payload={"a": 1, "b": 2})
        self.assertTrue(result["duplicate"])

    def test_time_cannot_move_backwards_across_state_changes(self):
        self.create(now=2)
        with self.assertRaises(ValueError):
            self.cancel(1)
        self.help(3)
        with self.assertRaises(ValueError):
            self.cancel(2)

    def test_scope_binding_and_caregiver_resolution(self):
        with self.assertRaises(fall.FallConflict):
            self.create(patient_id="other-patient")
        self.create()
        with self.assertRaises(fall.FallNotFound):
            fall.cancel_event(self.conn, event_id="synthetic-event", device_id="other-device", patient_id="other-patient", now=stamp(1))
        self.help(1)
        with self.assertRaises(fall.FallConflict):
            fall.resolve_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient", now=stamp(2), resolved_by="unassigned.carer")
        result = fall.resolve_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient", now=stamp(2), resolved_by="assigned.carer")
        self.assertEqual(result["status"], "resolved")
        self.assertEqual(fall.list_outbox(self.conn, status="pending"), [])
        self.assertEqual(self.outbox_count(), 1)
        self.assertEqual(self.create(now=3, detected_at=stamp(3), event_id="next-event")["status"], "pending")

    def test_nested_transaction_does_not_commit_callers_work(self):
        self.conn.execute("BEGIN IMMEDIATE")
        self.create()
        self.conn.rollback()
        with self.assertRaises(fall.FallNotFound):
            fall.get_event(self.conn, "synthetic-event")

    def test_concurrent_cancel_and_expiry_at_deadline_queue_once(self):
        self.create()
        barrier = threading.Barrier(2)
        def cancel_worker():
            with closing(connection(self.path)) as conn:
                barrier.wait()
                result = fall.cancel_event(conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient", now=stamp(15))
                return result["cancellation_accepted"]
        def expiry_worker():
            with closing(connection(self.path)) as conn:
                barrier.wait()
                return fall.sweep_due(conn, stamp(15))
        with ThreadPoolExecutor(max_workers=2) as pool:
            cancel_future = pool.submit(cancel_worker)
            expiry_future = pool.submit(expiry_worker)
            self.assertFalse(cancel_future.result(timeout=10))
            expiry_future.result(timeout=10)
        self.assertEqual(fall.get_event(self.conn, "synthetic-event")["status"], "escalated")
        self.assertEqual(self.outbox_count(), 1)

    def test_concurrent_duplicate_creation_persists_one_event(self):
        barrier = threading.Barrier(2)
        def worker():
            with closing(connection(self.path)) as conn:
                barrier.wait()
                return fall.create_event(conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient",
                                         detected_at=stamp(), now=stamp(), payload={"source": "synthetic_test"})["duplicate"]
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: worker(), range(2)))
        self.assertEqual(sorted(results), [False, True])
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM cm_fall_events").fetchone()[0], 1)

    def test_no_caregiver_does_not_claim_delivery(self):
        self.conn.execute("DELETE FROM cm_caregiver_assignments")
        result = self.create(now=15)
        self.assertEqual(result["status"], "escalated")
        self.assertEqual(self.outbox_count(), 0)
        self.assertEqual(fall.list_outbox(self.conn, status="delivered"), [])
        self.assertEqual(result["notification_readiness"]["status"], "no_active_assigned_caregiver")
        self.assertFalse(result["notification_readiness"]["push_delivery_verified"])

    def test_late_caregiver_assignment_backfills_after_restart_once(self):
        self.conn.execute("DELETE FROM cm_caregiver_assignments")
        original = self.create(now=15)
        self.conn.close()
        self.conn = connection(self.path)
        fall.initialize(self.conn)
        self.conn.execute("INSERT INTO cm_caregiver_assignments VALUES ('assigned.carer','test-patient')")
        self.assertEqual(fall.notification_readiness(self.conn, "synthetic-event")["status"], "awaiting_queue")
        changed = fall.sweep_due(self.conn, stamp(20))
        self.assertEqual(len(changed), 1)
        self.assertEqual(changed[0]["deadline_at"], original["deadline_at"])
        self.assertEqual(changed[0]["escalated_at"], original["escalated_at"])
        self.assertEqual(changed[0]["notification_readiness"]["status"], "queued_for_caregiver")
        self.assertEqual(fall.sweep_due(self.conn, stamp(21)), [])
        self.assertEqual(self.outbox_count(), 1)
        self.assertFalse(changed[0]["notification_readiness"]["push_delivery_verified"])

    def test_newly_activated_caregiver_gets_unique_backfilled_notification(self):
        self.create(now=15)
        self.conn.execute("UPDATE cm_users SET active=1 WHERE username='inactive.carer'")
        self.assertEqual(fall.notification_readiness(self.conn, "synthetic-event")["unqueued_recipients"], 1)
        fall.sweep_due(self.conn, stamp(16))
        fall.sweep_due(self.conn, stamp(17))
        self.assertEqual(self.outbox_count(), 2)
        item = fall.list_outbox(self.conn)[0]
        fall.mark_delivered(self.conn, outbox_id=item["id"], now=stamp(18), delivery_reference="synthetic-recipient-app-ack")
        readiness = fall.notification_readiness(self.conn, "synthetic-event")
        self.assertEqual(readiness["active_assigned_caregivers"], 2)
        self.assertEqual(readiness["queued_recipients"], 1)
        self.assertEqual(readiness["acknowledged_recipients"], 1)
        self.assertFalse(readiness["push_delivery_verified"])

    def test_patient_reassignment_blocked_until_escalated_event_resolved(self):
        self.create()
        with self.assertRaisesRegex(sqlite3.IntegrityError, "unresolved fall"):
            self.conn.execute("UPDATE cm_devices SET patient_id='other-patient' WHERE device_id='test-device'")
        self.conn.execute("UPDATE cm_devices SET patient_id='test-patient' WHERE device_id='test-device'")
        self.help(1)
        with self.assertRaisesRegex(sqlite3.IntegrityError, "unresolved fall"):
            self.conn.execute("UPDATE cm_devices SET patient_id='other-patient' WHERE device_id='test-device'")
        fall.resolve_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient",
                           now=stamp(2), resolved_by="assigned.carer")
        self.conn.execute("UPDATE cm_devices SET patient_id='other-patient' WHERE device_id='test-device'")
        result = self.create(now=3, event_id="reassigned-event", patient_id="other-patient", detected_at=stamp(3))
        self.assertEqual(result["status"], "pending")
        self.assertEqual(fall.list_outbox(self.conn, status="pending"), [])

    def test_cancelled_event_allows_reassignment_without_orphaning_new_episode(self):
        self.create()
        self.cancel(1)
        self.conn.execute("UPDATE cm_devices SET patient_id='other-patient' WHERE device_id='test-device'")
        self.assertEqual(self.create(now=2, event_id="reassigned-event", patient_id="other-patient", detected_at=stamp(2))["status"], "pending")
        self.assertEqual(fall.notification_readiness(self.conn, "synthetic-event")["status"], "device_binding_changed")

    def test_reassigned_resolved_event_outbox_is_not_exposed_to_new_patient(self):
        self.create(now=15)
        item = fall.list_outbox(self.conn)[0]
        fall.mark_delivered(self.conn, outbox_id=item["id"], now=stamp(16), delivery_reference="synthetic-recipient-app-ack")
        fall.resolve_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient",
                           now=stamp(17), resolved_by="assigned.carer")
        self.conn.execute("UPDATE cm_devices SET patient_id='other-patient' WHERE device_id='test-device'")
        self.assertEqual(fall.list_outbox(self.conn, status="delivered"), [])
        self.assertEqual(fall.list_outbox(self.conn, status="pending"), [])
        self.assertEqual(self.outbox_count(), 1)  # retained historical evidence

    def test_resolved_event_is_not_backfilled_for_later_assignment(self):
        self.conn.execute("DELETE FROM cm_caregiver_assignments")
        self.create(now=15)
        self.conn.execute("INSERT INTO cm_caregiver_assignments VALUES ('assigned.carer','test-patient')")
        fall.resolve_event(self.conn, event_id="synthetic-event", device_id="test-device", patient_id="test-patient",
                           now=stamp(16), resolved_by="assigned.carer")
        self.assertEqual(fall.sweep_due(self.conn, stamp(17)), [])
        self.assertEqual(self.outbox_count(), 0)

    def test_cancelled_event_is_not_backfilled_for_later_assignment(self):
        self.conn.execute("DELETE FROM cm_caregiver_assignments")
        self.create()
        self.cancel(1)
        self.conn.execute("INSERT INTO cm_caregiver_assignments VALUES ('assigned.carer','test-patient')")
        self.assertEqual(fall.sweep_due(self.conn, stamp(15)), [])
        self.assertEqual(self.outbox_count(), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
