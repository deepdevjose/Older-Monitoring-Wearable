# Care Monitor server: v0.8.0 integration candidate with the v0.7.0 core

**Start with [../START-HERE.md](../START-HERE.md) for this integration candidate.** Run `run_candidate.py` from the package root to include patient/caregiver accounts, current-assignment access checks, stored-reading classification, and the durable possible-fall response workflow. Running the legacy `uvicorn care_monitor_server.app:app` command below starts only the core device-key API; it does **not** register the `/accounts/*` routes.

The v0.8.0 additions are experimental integration code. Binary synthetic-data classification does not establish clinical accuracy; no validated automatic fall detector or FCM delivery is implied. Numeric alert policies remain disabled by default. See [STORED_CLASSIFICATION_API.txt](STORED_CLASSIFICATION_API.txt) for the account API, freshness gates, and local provisioning commands. The remaining setup instructions describe the retained v0.7.0 device-key service and its compatible ingest API.

This standalone Linux FastAPI service receives the team's existing JSON snapshots from the Android phone, stores them in SQLite, and provides authenticated, durable server alert episodes. It adds per-phone credentials and records ML predictions in **shadow mode only**. It does not change or flash the ESP32 firmware and does not contact the old server during installation.

## What is enabled

- `POST /sensor-data` keeps the original upload path and acknowledgement shape: `status: "received"` and a positive integer `record_id` mean the SQLite transaction committed.
- A 32-random-byte secret is issued separately to each phone. Tokens have the form `key_id.secret`; the server stores only the SHA-256 digest, key ID, label, device binding, permissions and expiry. Default expiry is seven days.
- Every protected route requires HTTPS. The retained device-key routes require `X-API-Key`; the candidate `/accounts/*` routes instead use an individual `Authorization: Bearer ...` session after login. Machine ingest keys never confer a patient/caregiver role. The old shared `SENSOR_API_KEY` variable cannot authenticate this server.
- SQLite holds readings, credential state, rate-limit buckets, alert episodes, acknowledgements and revisions across process restarts. New tables use `cm_` prefixes; old lab `sensor_readings` and `alerts` tables are never dropped or rewritten.
- Missing, invalid, stale or out-of-order inputs are unavailable for a current assessment. Processed optical estimates need explicit validity/contact flags; raw ADC values are never relabelled as BPM or oxygen percentage.
- Numeric rules are disabled until an operator reviews and explicitly enables a policy. The pinned JSON ML trees only record experimental predictions, never patient-care notifications. Read [ML_MODEL_CARD.md](ML_MODEL_CARD.md) before using them.
- No new real credentials, certificates, private keys or patient database are included in the source package.

## Linux setup

Run from the directory that contains `care_monitor_server/`:

```sh
umask 077
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r care_monitor_server/requirements.txt
export CARE_MONITOR_DB="$HOME/.local/share/care-monitor/care_monitor.db"
python -m care_monitor_server.admin init
```

This is a new database by default (`./data/care_monitor.db` when the environment variable is absent). The original team's database is not automatically opened or imported. Back up any existing database before deliberately pointing `CARE_MONITOR_DB` at it; additive creation retains its legacy tables, but historical legacy records are not copied into the new authenticated APIs. Keep the database directory owner-only, outside shared drives and web roots. Protect backups the same way because snapshots and patient IDs are sensitive.

Use the team's existing TLS certificate and private-key paths at runtime. They are not copied into this project:

```sh
export CM_TLS_CERT="/absolute/path/to/existing/server-certificate.pem"
export CM_TLS_KEY="/absolute/path/to/existing/server-private-key.pem"
python -m uvicorn care_monitor_server.app:app \
  --host 0.0.0.0 --port 8000 \
  --ssl-certfile "$CM_TLS_CERT" --ssl-keyfile "$CM_TLS_KEY" \
  --no-proxy-headers --no-access-log
```

Configure the phone endpoint as `https://<current-server-address>:8000/sensor-data`, replacing the placeholder with the server's reachable address. Earlier hotspot addresses are historical and may change. The certificate must be trusted by the phone app and cover the configured IP address (or matching hostname). Do not bypass certificate or hostname checks. Connect the server computer and phone to networks that can reach each other; allow inbound TCP 8000 only for the intended local network. Disabling the entire firewall is unnecessary.

**TLS must terminate in this application as shown above.** The API rejects protected requests whose actual ASGI request scheme is not `https`; it does not use a submitted `X-Forwarded-Proto` as proof of TLS. Keep `--no-proxy-headers`. Reverse-proxy deployment requires a separate trusted-proxy design, and is not enabled by this lab setup.

Root `/` and `/health` are unauthenticated coarse health responses only. Interactive API docs and OpenAPI output are disabled. Do not enable debug mode, body/header logging or a public admin endpoint.

## Issue, inspect, revoke and replace a credential

Run administration locally on the server computer, in the same environment with the same `CARE_MONITOR_DB` value. No HTTP administration route exists.

An upload-only phone:

```sh
python -m care_monitor_server.admin create \
  --label "Group Android phone" --device-id elder-demo-01
```

The default permissions are **only `sensor:write`** and the expiry is seven days. The complete key is intentionally printed once in the local terminal. Copy it directly into the phone's server-key field; do not save the output in shared transcripts, screenshots, chat, Git, source code, an APK or a URL. The private secret cannot be recovered from `list`.

Create a separate credential with only the permissions that an authorised caregiver needs. The Android app keeps this in a separate encrypted caregiver-key field:

```sh
python -m care_monitor_server.admin create \
  --label "Authorised caregiver phone" --device-id elder-demo-01 \
  --scope alerts:read --scope alerts:ack
```

Supported permissions:

| Scope | Allows |
|---|---|
| `sensor:write` | Submit snapshots for the bound device |
| `sensor:read` | Read stored snapshots and sensor blocks for the bound device |
| `alerts:read` | Read persisted alert changes and capability information for the bound device |
| `alerts:ack` | Acknowledge an alert for the bound device; this does not resolve it |

The legacy connection workflow uses two separate device-bound credentials: an upload key for sensor submission and an alerts key for alert reading/acknowledgement. The API can technically issue explicitly combined scopes, but ordinary upload credentials do not receive alert permissions. The v0.8.0 candidate additionally provides individually provisioned patient/caregiver accounts and assignments through `accounts_admin`; its account workflow uses Bearer sessions and rechecks access against the current patient/device assignment. MFA and an admin web page are not implemented.

```sh
python -m care_monitor_server.admin list
python -m care_monitor_server.admin revoke KEY_ID_FROM_LIST
python -m care_monitor_server.admin rotate KEY_ID_FROM_LIST \
  --label "Replacement for lost phone"
```

`rotate` revokes the old credential and issues its replacement in one transaction; the device binding and permissions are retained unless the operator explicitly supplies new scopes. Rotation cannot transfer a credential to a different device. Issuing a credential for a new device also requires `--patient-id` to create its mapping. Expired or revoked keys fail the next authenticated request, including requests from a phone that still has a saved copy. Revoke a lost phone promptly. Historical readings and alerts remain stored.

The server uses one generic rejection for missing, unknown, wrong, expired and revoked credentials. A verified key's metadata is available only to its holder through `/auth/status`. The app can distinguish locally saved credentials, server verification, rejected credentials and unreachable/TLS-failed servers; a blank masked input is not evidence of deletion.

## Request contract

All protected requests use the key in the **request header**, never in the URL. These examples show shapes rather than a real key.

- `GET /auth/status?device_id=elder-demo-01` returns `status: "verified"`, `key_id`, `device_id`, `scopes`, UTC `expires_at` and a stable database `server_id`. No secret or digest is returned.
- `POST /sensor-data` accepts the team's existing JSON object with `device_id: "elder-demo-01"`. It stores the complete JSON without renaming sensor fields, evaluates quality/rules/shadow inference transactionally, then acknowledges the committed record.
- `GET /sensor-data?device_id=elder-demo-01&limit=20` returns only readings for the authenticated credential's bound device, newest first. `limit` is 1–100.
- `GET /sensors` and `GET /sensors/max30102` return latest stored sensor blocks only with `sensor:read` permission. Current modules are MAX30102, MPU6050, GY906, photoresistor and the buzzer actuator. The removed microphone module is no longer listed, and `/sensors/microphone` returns 404. Device filtering is enforced on every read.
- Existing stored payloads and historical microphone fields are preserved in `/sensor-data`; no database migration or history deletion is performed when retiring a module.
- `GET /alerts?device_id=elder-demo-01&after_revision=0&limit=50` returns `status: "ok"`, `device_id`, `server_id`, `alerts`, `next_revision`, `has_more`, `server_time`, `mode: "shadow"` and `capabilities`. Alert `source` distinguishes rule and quality entries; ML predictions do not become alerts.
- `POST /alerts/123/acknowledge` with body `{"device_id":"elder-demo-01"}` returns `status: "acknowledged"`, `device_id`, `server_id` and the confirmed `alert`. It requires `alerts:ack`. Repeating an acknowledgement is idempotent. Acknowledgement means reviewed, not recovered.

If provided, `boot_nonce` and `sample_id` must both be nonempty valid identifiers. A nonnegative integer `sample_id` is also supported. An identical retry with the same device/boot/sample identity returns the same `record_id` and `duplicate: true`, without evaluating the episode twice. Reusing the identity with changed data returns 409. Existing firmware without these fields remains compatible, but exact transport retry deduplication cannot be guaranteed; the app does not invent an ESP32 boot identity.

Keep real acquisition timestamps (`timestamp` and/or a trustworthy `phone_received_at`) in snapshots. The alert engine rejects stale/out-of-order timestamps for a *current* assessment while retaining the original stored reading. Server receipt time alone must not make delayed queued readings look fresh. See the model card for supported timestamp formats and rule policy behaviour.

The body limit is **16 KiB of actual request bytes**, including chunked uploads without a Content-Length. Invalid JSON, duplicate JSON fields, non-finite numbers, invalid sample identities and excessive nesting are rejected safely. Default rate limits are 240 authenticated requests per key per minute and a 10-failed-authentication-attempt IP cooldown per minute. Fixed-window counters are stored in SQLite and shared across workers; changing workers cannot reset them. Forwarded IP headers are not trusted.

Codes: 400 invalid body, 401 missing/invalid/expired/revoked credential, 403 insufficient scope or wrong device, 404 no accessible resource, 409 reused sample identity, 413 oversized body, 422 invalid query/path parameter, 426 HTTPS required, 429 rate limit, 500 generic internal failure. None returns submitted keys, raw exception details or request bodies.

## Alert rules and ML rollout

The engine and its [example policy](rules.example.json) support sustained high/low processed heart-rate or oxygen readings and separate surface/ambient-temperature rules. Example thresholds are lab configuration, not a medical reference. Review and version the policy before enabling any numeric notifications:

```sh
cp care_monitor_server/rules.example.json /private/path/reviewed-rules.json
# Review/edit the copy and explicitly enable and acknowledge its lab policy.
export CM_ALERT_RULES_FILE="/private/path/reviewed-rules.json"
# Restart the HTTPS server with the command above.
```

Test a reviewed copy before deployment. ML shadow outputs remain experimental even when rule alerts are enabled. There is no automatic blood-pressure, dehydration or watch-wearing detector, no validated core-body-temperature reading, and no streaming fall detector from approximately 1 Hz snapshot data. The offline SisFall model is deliberately not deployed here. A network outage cannot be detected from an absent incoming packet alone; separate liveness monitoring is still needed.

## Verification

Tests use generated throwaway credentials and temporary databases only. They never upload fabricated readings to the teammate's computer or touch the original lab database:

```sh
python -m pip install -r care_monitor_server/requirements-test.txt
python -m pytest care_monitor_server/tests -q
```

Before considering deployment complete, test the actual phone and server together: valid upload accepted, wrong/expired/revoked keys rejected, wrong-device/scope attempts rejected, trusted TLS verified, alert acknowledgement survives restart, no full key in logs, and loss of Wi-Fi preserves the phone's queued data. Offline tests do not prove the current server is reachable or its certificate trusted.
