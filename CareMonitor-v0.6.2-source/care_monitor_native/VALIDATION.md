# Care Monitor 0.6.2 validation — 20 October 2026

Application ID `org.caremonitor.demo`; version 0.6.2, code 10; min API 24, target API 36. Native Android Java. The source ZIP includes the corresponding APK and actual build evidence.

## Scope

The app adds **Classic Bluetooth (SPP)** for the group's existing `Iot App(2)` firmware, while retaining the optional **Bluetooth Low Energy (BLE)** path. Settings remains an entry to Connections. SPP uses Android system pairing and a secure RFCOMM socket; the team's firmware requires matching the phone's comparison code and typing `Y` in its Serial Monitor within 30 seconds. This update does not modify/flash the firmware, retrain ML, or integrate a model into live alerts.

Classic paired-device selection, discovery, manual MAC address entry, cancellation, stream reception, and foreground-service ownership are part of this revision. Both transports deliver complete NDJSON frames into the same freshness and HTTPS relay paths. A connected socket is not treated as evidence of a fresh sensor sample.

## Evidence files and release verification

- `artifacts/BUILD_EVIDENCE.json` records the actual APK version, source input hashes, Android compilation, D8 conversion, alignment, signature checks, and APK SHA-256. `scripts/package_release.py` requires version 0.6.2/code 10 and compares current input hashes and the source ZIP's enclosed APK against that evidence.
- The preserved development signing certificate has SHA-256 `be0b46f60ca5871751697124a0ba4b50d5dff22bc2e9b560468bb321201d54af`.
- `artifacts/JVM_TEST_RESULTS.txt` records the current plain-JVM run. A passing framing or policy test does not establish an Android socket, pairing exchange, or real network operation. Release metadata reports the suites actually present in that output rather than assuming a fixed count.
- If generated for this release, `artifacts/PHONE_INSTALL_EVIDENCE.json` records the authorized phone update and launch result. The matching version/hash must be checked before using it as evidence. Installation alone does not verify SPP, BLE, or server delivery.
- No active API key, Wi-Fi password, server TLS private key, private signing key, or supplied patient database belongs in the APK/source release. The teammate's uploaded archive remains outside the distributed source. The public server certificate is retained for HTTPS verification.

## Prior completed checks

The v0.6.1 app was built, installed with `adb install -r`, and launched on the connected authorized Android phone with version 0.6.1/code 9 confirmed. All 290 baseline JVM checks passed: reading summary 26; alert inbox 105; strict UTF-8 framing/recovery 51; connection freshness 35; relay policy 73. Those results apply to that baseline; the current revision must be built and tested separately.

The optional BLE firmware remains byte-for-byte the v0.6.0 sketch. Its retained compile evidence targets `esp32:esp32:esp32`, Arduino-ESP32 3.3.11, ArduinoJson 7.4.3, and SparkFun MAX3010x 1.1.2: 1,142,419 flash bytes (87%) and 44,660 global RAM bytes (13%). This is historical compile evidence for that sketch, not a new classic SPP compile. No board was flashed. No separate v0.6.2 firmware ZIP is generated.

The previous server health request from the development computer did not establish reachability. No sensor POST or API key was sent by that check. The supplied FastAPI handler has no idempotency guard; acknowledged retries are at least once and can create duplicate database rows after a lost response.

## v0.6.2 execution record

The final APK build, alignment/signature and version checks passed. All 610 JVM checks passed: reading summary 26, inbox 105, framing 51, freshness 35, relay policy 73, and session buffer 320. Source review also checked socket cancellation, generation guards, protocol switching, retained permission actions and status-strip refresh without rebuilding editable forms.

The authorized connected phone was updated with `adb install -r` (Success), without uninstalling the previous version. Package inspection confirmed version 0.6.2/code 10, and Activity inspection confirmed MainActivity resumed after launch. The exact APK hash and observed results are in `artifacts/PHONE_INSTALL_EVIDENCE.json`. Interactive Settings taps and physical SPP pairing/streaming were not performed.

Build, current JVM output, and phone evidence are recorded in the artifact files above. The release packager rejects an APK whose current source inputs no longer match the completed build. It does not silently reuse the old version's APK or claim a new firmware compile.

A live connection to the group's physical ESP32, secure numeric pairing, SPP stream reception, optional BLE regression on hardware, AndroidKeyStore behavior during real uploads, Wi-Fi delivery to the team's server, and screen-off reconnect are **not established by compilation, plain-JVM tests, or installation**. Record those outcomes separately if actually observed. Do not mark these physical checks passed from a UI screenshot or an increasing demo value.

## Physical checks required

Follow `docs/PHONE_SETUP.md` with the existing classic SPP firmware. Verify the Settings route, Bluetooth permissions, paired/discovered selection, matching-code pairing plus serial `Y`, increasing complete-message counts, fresh values, invalid/null handling, disconnect/reconnect, ten-second staleness, transport switching, Stop, Wi-Fi-only queue/retry, acknowledged authenticated POST and the corresponding real SQLite row. Test the team's actual Android version, large fonts, and screen-off battery behavior.

Live mode does not auto-detect the alert types shown in the separate demo. Offline model scores do not establish patient-level accuracy or validate once-per-second snapshots for a high-rate fall model. No medical or clinical validation is claimed.
