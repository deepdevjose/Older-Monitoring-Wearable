# Care Monitor live test database

This local browser dashboard reads the existing Care Monitor SQLite database. The Android phone continues uploading to the existing HTTPS receiver on port 8000. The dashboard listens only on `127.0.0.1:8001` and refreshes its view once per second.

## Open without a terminal

Double-click **Open Care Monitor Database.cmd** in the workspace root, or **Care Monitor Live Database.lnk** when present. The launcher starts the existing HTTPS receiver and the local dashboard in the background, then opens:

**http://127.0.0.1:8001/**

The launcher uses the existing `work/secure_server_venv` environment. It preserves the database, credentials, certificates and Android configuration. It never stops an unrelated process occupying either port. Diagnostic logs remain in the protected database runtime folder.

## During a test

1. Put the phone and computer on the reachable shared hotspot. Connect the phone App to the ESP32 and enable its saved server relay.
2. Open the dashboard. Select the actual device and inspect the most recent sample and receipt times. A running web page does not establish that the phone is sending new readings.
3. Start a test session with a participant pseudonym, descriptive name, actual firmware version and placement. Existing historical records remain outside the new session.
4. Watch the incoming record count, table and charts. Invalid optical estimates remain unavailable; sensor error or missing finger contact is never labelled normal.
5. Stop the session, then export its CSV. The readings remain in SQLite, and the stopped session retains its record boundaries.

Incoming authenticated readings are saved by the receiver even when no test session is open. Starting/stopping a session groups the records; it does not switch the physical sensors on/off or stop global recording. Closing the browser stops the view, not the running background services. After a computer restart, open the launcher again.

## Record only valid primary measurements

The controls to the right of **Primary sensor measurements** provide a separate, one-click collection workflow:

1. Select the device, then click **Start recording**. Only newly arriving samples are considered; older database history is excluded.
2. Watch the valid, checked and skipped counts. A row is accepted only when its heart-rate estimate, SpO2 estimate and acceleration X/Y/Z are all valid in the **same received snapshot**. Missing contact, warm-up, invalid flags, stale queued samples or unavailable axes are skipped.
3. Click **Stop recording** to freeze the collection, then **Export CSV**. Export remains available after reloading the page. A collection with no eligible samples produces a header-only CSV rather than invented readings.

Each CSV row preserves the original values, units, record ID, device ID, measurement/phone/server UTC timestamps and quality flags. Optional firmware diagnostics reject expired optical samples/results when supplied. The supported numeric bounds are input checks, not a judgment that a person's health is normal. Freshness is evaluated at server receipt; downloading the CSV later does not invalidate earlier accepted samples.

The background collector persists its cursor and accepted rows in additive `cm_valid_recordings` and `cm_valid_recording_rows` tables. Browser closure or page reload does not end an active collection. Restarting the dashboard resumes it. Stop recording explicitly when a test ends. All original readings, including skipped samples, remain in the receiving server's `cm_readings` table.

Exports are capped at 20,000 accepted rows per download, with an explicit truncation indicator. Use shorter collections if you need a single complete CSV. The full-data test sessions lower on the page remain available for inspecting rejected measurements and adding participant/placement metadata. Valid-only CSVs are not automatically labelled ML training data.

## Timing and reference collection

Measurement time, phone reception time and server receipt time have different meanings. Queued uploads can arrive after their measurement period. The dashboard distinguishes stale/delayed readings, and session exports preserve timing and quality information. Check those fields before assigning paired references or treating a session as participant-labelled ML data. Record bounds segment server arrivals; they cannot establish who wore a device or when a reference was measured.

The CSV is an acquisition export, not automatically reviewed training data. Independently collected reference measurements, signal-quality review and outcome labels are still required by `care_monitor_ml/device_adaptation/`.

## Storage and access

The launcher uses the existing database at its actual Windows package-cache path. Set `CARE_MONITOR_DB` explicitly when moving the project to another environment. The dashboard refuses a missing database instead of silently creating an empty replacement. Test-session metadata uses additive tables; existing sensor rows and credentials are preserved.

The dashboard runs on this computer only. It has no cloud upload, external fonts, analytics, or CDN dependency. Its browser APIs expose approved sensor fields and test metadata, not API keys, private TLS material, complete payloads or patient identifiers. Loopback/Host/Origin checks and CSRF protection guard local controls. Keep the receiver's existing HTTPS and per-device API-key protection enabled.

The current dashboard contains no Sound module. Gyroscope/movement snapshots, surface temperature and ambient temperature retain their measurement meanings; this view does not introduce new clinical detectors or enable ML alerts.
