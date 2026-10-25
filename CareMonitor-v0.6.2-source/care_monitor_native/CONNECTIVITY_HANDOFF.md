# Archived v0.5.0 interface notes — 20 October 2026

These notes record the historical interface review before v0.6.0. Current v0.6.2 supports the team's classic Bluetooth SPP firmware as the default, plus the optional older BLE firmware, with foreground monitoring and Wi-Fi HTTPS forwarding. Use README.md, docs/PHONE_SETUP.md and docs/HTTPS_RELAY.md for current behavior. Statements below that transport is unimplemented describe v0.5.0 and its original archive only.

The later `Iot App(2)` firmware uses secure classic SPP, advertises **ElderSensors-elder-demo-01**, prints its MAC address, and requires comparing the phone pairing code then typing `Y` in the ESP32 Serial Monitor within 30 seconds. v0.6.2 adds the matching phone transport while keeping that firmware unchanged. The legacy BLE sketch is a separate optional v0.6.0 artifact; neither a new firmware compile nor a board flash is part of the v0.6.2 update.

The agreed route is:

ESP32 sensors → Bluetooth → Android app → Wi-Fi → teammate's computer server

The phone receives sensor messages, presents their current validity/status, and forwards data to the computer. A server failure must not be presented as a Bluetooth failure, and a Bluetooth disconnect is not proof that a watch is not worn.

## Confirmed from the supplied Iot App.zip

The uploaded firmware still uses direct ESP32 Wi-Fi/HTTPS and has no BLE or SPP service. Bluetooth selection and firmware work are still required. The server supports POST /sensor-data with a full JSON snapshot, Content-Type application/json and X-API-Key. Its current validation accepts device_id elder-demo-01. A successful response includes status=received, record_id, received_at and data. The sender can be Android; no ESP32-specific transport is required by this POST handler.

Protected GET routes are /sensor-data (latest 20 rows), /sensors (in-memory latest snapshot) and /sensors/{sensor_name}. There are no /alerts or acknowledgement routes and no patient alert rules. Sensor status/valid flags are not alert events.

All protected routes share a 100 ms per-client-IP rate limit. Do not immediately POST then GET. POST has no sample-ID deduplication, so a retry after a timeout can create a duplicate database row. Latest-snapshot GET values are held in memory and can be unavailable after a server restart until the next POST.

The server uses a local HTTPS certificate and an API key. Android needs an explicitly trusted public certificate and a matching server hostname/IP. Do not disable certificate verification. Configure credentials at runtime; the server's API key, TLS private key and database are excluded from the mobile source release. The supplied server was not modified or started, and no request was sent to its LAN address.

## Historical questions before transport implementation

1. BLE/GATT or classic Bluetooth/SPP; provide the current ESP32 sketch.
2. For BLE, the service and notify/read characteristic UUIDs and pairing requirements; for SPP, the service/connection parameters.
3. Message encoding/framing, an actual complete payload, sample/report frequency and whether messages can exceed a single Bluetooth notification.
4. Confirm the computer's current reachable LAN URL. The existing POST /sensor-data and X-API-Key contract is now known; the TLS trust setup and runtime credential configuration still need implementation.
5. Which side produces alert events and thresholds: ESP32, phone or server. Fall/wear/hydration algorithms and clinical thresholds are not implemented by the current UI.

## Integration behavior to preserve

- Keep packet fragments in a receive buffer until a complete message is available; do not treat one BLE notification as one JSON document without a framing agreement.
- Preserve device_id, firmware uptime, nulls and sensor-valid flags. Invalid estimates stay unavailable.
- Retain event IDs and occurrence times through forwarding. Duplicate delivery must not create duplicate alerts or reset acknowledgement.
- Use separate Bluetooth-reception and server-forwarding status. Queue/retry behavior and maximum local retention must be agreed before implementation.
- Keep simulated data explicitly separate from live data. A reconnect must not silently substitute demo readings.
- Keep blood pressure/hydration/wear capabilities unavailable until an actual signal/detector is supplied. Surface temperature is not a validated core body-temperature measurement.

## Alert UI currently implemented

The UI handles event identity, category, original reading, time, device ID, severity and per-event review state. Internal saved-state keys are id, type, occurred_at (milliseconds), device_id, reading and acknowledged_at (milliseconds; zero means unreviewed). These keys document the current local model; they are not a claimed external API contract.

Supported type IDs:

possible_fall, blood_pressure_high, heart_rate_high, heart_rate_low, oxygen_low, oxygen_high, body_temp_high, body_temp_low, ambient_temp_high, ambient_temp_low, watch_not_worn, watch_worn, hydration_check.

The current body-temperature type IDs display surface-temperature wording to match available hardware. Watch-worn events are informational. No new physiological thresholds or automatic inference rules have been added. The existing below-95 classroom oxygen demo rule is preserved separately.

Version 0.5.0 has no Bluetooth or HTTP transport. Its snapshot and 13 event records are bundled simulations. An acknowledgement updates only this demo session, not a server or ESP32.
