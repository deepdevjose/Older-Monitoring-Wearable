# Candidate possible-fall response workflow

`fall_events.py` implements durable response states for an explicitly supplied possible-fall event. It is **not a fall detector**, and these software tests do not validate sensor detection or medical performance. The working v0.7.4 system is not changed.

## Deadline and state

`deadline_at = detected_at + 15 seconds`. Both detection time and server `now` must be timezone-aware timestamps; future detection times are rejected. The caller must provide a trustworthy detection time and synchronised clocks. Receipt time and receipt latency are also retained. A delayed upload does not start a new 15-second timer: an already-expired event escalates immediately on arrival.

- `pending` can become `cancelled` strictly before the deadline.
- At or after the deadline, a cancellation request instead persists `escalated` and returns `cancellation_accepted=false`.
- `request_help` immediately escalates a pending event.
- Only an active caregiver assigned to that patient can resolve an escalated event.
- An immutable event ID is idempotent; changed device, patient, detection time or payload conflicts. A partial unique index permits one unresolved event per device.

Database writes acquire an SQLite IMMEDIATE lock before reading state. A nested caller transaction uses a savepoint and write lock without committing the caller's work. This serialises cancellation and expiration races. Application routes still need authentication, device/patient scoping, rate limits and request validation.

## Integration

Initialise the existing device and account tables first, then call `fall_events.initialize(conn)` (`initialise` is an alias). Use:

```python
create_event(conn, event_id=..., device_id=..., patient_id=...,
             detected_at=..., now=..., payload={"source": "manual_test"})
cancel_event(conn, event_id=..., device_id=..., patient_id=..., now=...)
request_help(conn, event_id=..., device_id=..., patient_id=..., now=...)
resolve_event(conn, event_id=..., device_id=..., patient_id=...,
              now=..., resolved_by=...)
sweep_due(conn, now)  # call periodically and after restart
```

Results are flat event dictionaries containing status, both timestamps, deadline, receipt latency, payload and state-transition timestamps. `get_event` is read-only and does not advance timers; the calling route must enforce scope. `FallConflict` is a `ValueError`; `FallNotFound` is a `LookupError`. Root routes may map these to suitable HTTP statuses without leaking another patient's record.

No live background process is started by this module. A server worker must invoke `sweep_due` periodically. The deadline is exact in persisted state, but processing and notification timing can be later due to worker scheduling, server downtime or transport latency. Those latencies have not been measured on a physical deployment. After restart, the persisted deadline remains unchanged.

## Durable caregiver outbox

Escalation and notification enqueue occur in the same transaction. Each currently active assigned caregiver gets at most one outbox row for that event. Outbox uniqueness is `(event_id, recipient_username)`. No caregiver assignment means escalation exists but no recipient is queued; it must not be reported as delivered.

`list_outbox(conn, status="pending", now=...)` returns due queued/retry rows for currently active assigned caregivers and unresolved escalated events. `mark_retry` persists error, attempts and bounded exponential backoff. `mark_delivered` requires a verified acknowledgement from the intended recipient's device/app. FCM/provider acceptance or a returned message ID is insufficient. A queued row, an HTTP success, or a countdown expiry is not proof that a caregiver's phone received a push. The authenticated candidate `/accounts/notifications/{id}/seen` route records a recipient-client acknowledgement through `mark_delivered`; it does not claim FCM delivery or resolve the incident. This module implements no FCM credentials, push sender, SMS or email delivery.

## Verified synthetic software checks

Twenty-six state-machine tests passed in temporary SQLite databases. Cases cover cancellation at 14.999 seconds, rejection at exactly 15 seconds, delayed upload, immediate help, restart recovery, duplicate and conflicting events, one unresolved episode, active caregiver assignment, durable retries, delivery-reference requirements, invalid/naive/future/overflow timestamps, timezone equivalence, backward clocks, transaction rollback and concurrent cancellation/expiration and duplicate-creation races. No real fall, patient, caregiver device or live notification was involved.

Run from the candidate root with the project's existing Python environment:

```powershell
& '..\ml_venv\Scripts\python.exe' -m unittest discover -s '.\care_monitor_server\tests' -p 'test_fall_events.py' -v
```

The candidate account wrapper now starts a 250 ms deadline sweep. Notification readiness is explicit; unresolved escalated events backfill newly assigned caregivers. Patient reassignment is blocked by a database trigger while an incident remains pending or escalated. The final integrated server suite is recorded in evidence/FINAL_VERIFICATION.json.
