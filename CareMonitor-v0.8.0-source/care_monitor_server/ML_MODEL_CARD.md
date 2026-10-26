# Server alerts and shadow model card

Release: 2026-10-22. Engine `care-monitor-alerts-v1`.

This server introduces durable rule/quality alert episodes and **shadow-only**
evaluation of the two previously exported synthetic-label JSON trees. It does
not retrain a model, make a clinical diagnosis, or use an ML prediction to create
a care alert or notification.

## Model provenance and integrity

Only `models/heart_rate_tree.json` and `models/oxygen_tree.json` are used. They
are byte-identical copies of the earlier `outputs/ml/vitals` JSON exports; their
original `deployed:false` field documents the earlier offline export. This
server explicitly wraps them in shadow mode. `models/manifest.json` records the
release hashes, which are also pinned in executable code. The server verifies
both SHA-256 and the model schema, graph and metadata before inference. A
missing, modified, cyclic or malformed model produces **unavailable**, never a
fallback NORMAL label. No pickle/joblib file is loaded.

Inference uses the exported float32 comparisons to preserve agreement with the
original decision-tree implementation. There is no invented confidence score
or probability. The possible model outputs `NORMAL` / `ABNORMAL` describe the
**supplied synthetic training labels**, not the person's health. No clinical
accuracy is claimed. The supported training ranges are 50–139 bpm and 84–100%
SpO2; otherwise the model returns `out_of_training_range` with no label.

## Reading quality and current observations

Heart rate and SpO2 require processed numeric estimates, `status:"ok"`,
`finger_detected:true`, the corresponding validity flag exactly `true`, and
`online` not explicitly `false`. Nulls, booleans, strings, non-finite values,
raw optical counts and physically unsupported processed values are unavailable.
One unavailable channel does not manufacture a valid result for that channel.

A timezone-aware ISO `timestamp` or `phone_received_at` is required. If both
exist, both are checked and the older timestamp determines sample order.
Samples older than the configurable 30-second default, more than five seconds
in the future, duplicate sample identities/timestamps or out-of-order samples
are excluded from current rules and ML. They may still be stored as historical
readings by the ingestion route. Server ingestion time cannot turn queued old
readings into fresh health alerts. Clocks on phone and server must agree.

Input bounds of 1–300 bpm, 1–100% SpO2, −70–380 °C surface and −40–125 °C
ambient are validation bounds for the input/sensor, **not normal-health ranges**.
GY906 channels additionally require an online module with an acceptable status.
Surface temperature is never relabelled as validated core body temperature.

## Rules are disabled by default

No health thresholds are silently extracted from the synthetic trees. To enable
numeric rules an operator must prepare a JSON file, explicitly set both global
`enabled:true` and `operator_acknowledged:true`, enable each selected rule, and
set `CM_ALERT_RULES_FILE` before server startup. `rules.example.json` is a
**disabled demonstration**, including arbitrary, unvalidated example thresholds;
it is not a patient-care policy. Rules require an input metric, high/low direction,
trigger threshold, separate recovery threshold, persistence/recovery durations,
minimum actual samples, severity and version. Invalid configuration fails closed
and is visible in capabilities. Existing database policy is preserved if an
admin operation opens it without the environment file; explicitly pass a disabled
policy file when intentionally disabling it.

Rules support processed heart rate, SpO2, surface temperature and ambient
temperature only. Enabled rules are the source of `source:"rule"` alert events.
An optional, explicitly enabled `quality_alerts` policy creates a
`source:"quality"`, `type:"sensor_unavailable"` episode after persistent bad
signal quality. It asks the caregiver to check the sensor; it is not a health
diagnosis. Quality assessment remains unavailable even when this optional event
is disabled.

## Alert lifecycle and persistence

SQLite tables `cm_alerts`, `cm_alert_state` and `cm_alert_revision` persist episodes,
consecutive-sample state and global monotonic revisions. `cm_alert_config` freezes
the active policy. Engine functions do not commit: ingestion and its associated
assessment/alert changes belong to the caller's transaction.

Repeated abnormal samples retain the same active episode without one revision
or notification per second. Acknowledging an episode records review and **does
not resolve it**. Resolution requires consecutive eligible readings crossing the
separate recovery threshold for the configured duration and sample count. A
later recurrence creates a new event ID. Missing/invalid readings never resolve
an existing alert. Data gaps greater than the configured three-second default
reset pending trigger/recovery counters. Fast bursts cannot substitute for elapsed
sample time. No new-upload callback means no new assessment; detecting complete
stream silence requires separate server liveness monitoring.

Every episode retains evidence, input units, relevant timestamps, rule version
and policy label. Delta retrieval is ordered by its latest revision; acknowledgement
and resolution are meaningful revisions. The Android client must merge by event
ID and retain its cursor separately per server/credential/device binding.

## Shadow data is separate from care alerts

`cm_shadow_predictions` stores the two model statuses/labels, reason, reading ID,
device/patient binding, timestamps, exact model version/hash and
`clinical_validation:0`. It cannot generate `cm_alerts` entries. Retention is
globally bounded to the most recent 10,000 rows by default (two rows per snapshot,
including unavailable predictions); operators may configure 2–100,000 rows.
This short-lived evaluation log is not a clinical record archive.

## Unsupported detection

* **Fall:** the existing offline whole-trial SisFall model requires separate
  higher-frequency inertial windows. Current approximately 1 Hz phone snapshots
  cannot run it, and raw acceleration is not treated as a fall detector.
* **Blood pressure:** no suitable measured input is provided.
* **Core body temperature:** GY906 currently provides surface/ambient channels.
* **Wear state and dehydration:** supported measurements and labelled validation
  data have not been supplied.

Client-supplied `alerts`, labels, severities and claimed ML diagnoses are ignored
by this engine. Only authenticated server calculations create server events.

## Validation and next gate

Automated tests use isolated temporary/in-memory SQLite databases and fixed
synthetic fixtures. They check invalid/missing/no-finger inputs, stale/future and
duplicate/out-of-order observations, raw-data rejection, JSON integrity failures,
shadow isolation and bounded retention, durable episode deduplication,
acknowledgement, hysteretic recovery/reopening, sample-time persistence, gaps,
delta cursors and no implicit commits. These tests establish software behavior,
not clinical performance or live hardware accuracy.

Before enabling ML-triggered care notifications, collect labelled recordings from
the actual device and review the intended outcome definition. Split evaluation by
unseen participants and sessions. Report missed events, false alarms per hour,
precision/recall, detection delay and failures by signal quality/subgroup, with
ground-truth review. Validate a separate streaming fall detector against actual
high-frequency windows. Any future promotion of a model out of shadow mode needs
a new reviewed policy and version; it cannot be enabled by editing a client flag.
