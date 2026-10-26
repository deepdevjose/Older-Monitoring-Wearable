# Integration status and evidence boundary — 26 October 2026

This document supersedes the Android-integration status in the copied earlier candidate checklist. The earlier test/timing files remain historical evidence, not new measurements of this APK.

| Requested item | Implemented or verified | Remaining boundary |
|---|---|---|
| Defensible three-class targets | Audited actual CSV, original assessment brief and existing code | No approved Warning/Critical definitions; preserved binary targets |
| Same-task two-model comparison | DT/RF on all four synthetic binary tasks; identical features, partitions and evaluation; source/evidence hashes checked | 100% label reproduction does not establish real-sensor accuracy; confidence is uncalibrated |
| Classification/confidence API | Existing validated `/ml/classify`; new account-authorised `/accounts/classification` for a specific persisted reading, timestamp/quality gates and unavailable reasons | Freshness is an engineering policy, not a clinical threshold |
| Android classification integration | HTTPS requests, reading binding, numeric confidence, loading/unavailable/error/stale states and existing design | Physical device acceptance remains unperformed |
| Genuine patient/caregiver roles | Password records, salted hashing, expiring hashed sessions, assignments, per-action authorisation; Android account login and assigned views | Deployment-specific accounts/assignments must be provisioned by the group; no public account administration |
| Charts/history | Assigned stored readings, per-channel gaps, 100-record view and up to 500 loaded/updated alert events with explicit limits/pagination | No claim of unlimited history or phone visual/accessibility verification |
| Working sensor path | Existing SPP, relay, public CA and stable firmware preserved | No new firmware flash or physical sensor trial performed |
| Alerts | Existing stored alert history and assigned-caregiver acknowledgement are integrated | Live numeric policy remains off; no new clinical rules or automatic fall/wear/dehydration detectors |
| 15-second response | Existing durable backend cancellation/escalation queue retained | No phone push delivery configured or tested; Firebase setup remains stopped |
| Quantitative evidence | Historical in-process timings retained with their scope; new test evidence is separate | No missed-Critical/false-Warning rates without those labels; no controlled sensor before/after study |

## UNRESOLVED — NEEDS HUMAN CONFIRMATION

| Item | What is uncertain and why | Missing evidence / what the group supplies | Can other work continue? |
|---|---|---|---|
| Three-class target definition | Binary ABNORMAL cannot justify Warning versus Critical | Tutor-approved definitions/labels for each task, units, precedence, missing-data policy and labelled evaluation data | Yes, with clearly binary output; three-class assessment remains incomplete |
| Dataset provenance | A file hash identifies bytes, not publisher, generation process or licence | Original dataset source/link and tutor approval/licence information | Yes for local inspection; do not claim provenance or redistribute as verified |
| Assessment data permission | Written brief limits assessment data to supplied synthetic/approved anonymised data; prior verbal permission for group sensor tests does not establish a written exception | Tutor's clarification on what evidence/data may be submitted | Yes using synthetic software fixtures; do not turn real readings into assessment/model evidence without clarification |
| Native Java framework acceptance | No explicit framework approval was found | Tutor's confirmation that native Java Android meets the framework requirement | Yes technically, but assessment compliance remains unconfirmed |
| Live alert thresholds and target populations | No approved severity policy or clinical validation exists | Approved demonstration rules and intended units/population, clearly distinguished from clinical validation | Yes with classification review and policy disabled |
| Physical Android acceptance | No test phone is connected; build success cannot prove UI/Keystore/Wi-Fi behaviour | Connect a test phone, reachable deployed candidate server, confirmed account assignments and perform the acceptance script | Yes: APK/source and desktop tests can be completed |
| Caregiver delivery | Backend queue is not notification delivery; no push project configured and user declined setup | A later explicit decision on notification transport and real delivery tests | Yes; current account alert retrieval remains available |
| Contribution evidence | Repository existence does not establish individual ownership of this integration | Actual responsibilities, student IDs and genuine commit/review/tag history from members | Yes; do not manufacture commits or backdate contributions |

## Historical numbers that remain correctly scoped

The preceding candidate measured 100 classification requests after 10 warmups with an in-process TestClient: median 14.71185 ms, p95 17.1239 ms. One software fall-response run queued the incident after 15.1988759 seconds. Neither measures Android/Wi-Fi delivery. They are copied historical results, not reruns on v0.8.0 and not evidence of clinical performance. See the underlying JSON files under `evidence/`.

Original data partitions: 35,993 train / 12,005 validation / 12,002 test, seed 20261020, grouping identical five-vital-sign vectors. This is not a participant-disjoint study. The previously examined test split was reused for the comparison; do not describe it as a new untouched clinical holdout. Ties select the simpler Decision Tree by validation macro-F1; no speed or accuracy advantage over Random Forest was measured.
