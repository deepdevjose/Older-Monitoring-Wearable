# Care Monitor 0.6.2 — classic Bluetooth SPP support

Native Android Java application, version code 10, ID `org.caremonitor.demo`, Android 7.0+ (API 24). The development signing certificate is preserved for updates from previous Care Monitor releases.

The primary live route is **the team's ESP32 classic Bluetooth SPP firmware → Android phone → Wi-Fi HTTPS → the team's FastAPI server**. Keep the team's existing `Iot App(2)` firmware on the ESP32. This update adds the matching phone transport; no ESP32 upload is required if that firmware is already running. The older Care Monitor BLE transport remains available for boards using the optional legacy BLE sketch. Demo mode is separate and never supplies simulated data to the upload queue.

## Start here

Read **[docs/PHONE_SETUP.md](docs/PHONE_SETUP.md)** for pairing, connection, server setup, and the physical acceptance test.

1. Install `CareMonitor-v0.6.2.apk`. Open **⋮ → Settings → Bluetooth connection → Connect / manage ESP32**, or **Connect a live ESP32** on the welcome screen.
2. Select **Classic Bluetooth (SPP)** for the team's existing firmware. Tap **Scan for ESP32** and choose **ElderSensors-elder-demo-01**. Android handles pairing. When the ESP32 prints a numeric comparison request, check that the code matches the phone and type `Y` in the ESP32 Serial Monitor within 30 seconds. Approve the matching request on the phone. You can also enter the firmware's printed address in **Bluetooth MAC address → Connect by MAC**.
3. Confirm the received-message count increases, then use **View live readings**. A connected Bluetooth socket alone does not prove that complete sensor JSON is arriving.
4. Connect the phone and server computer to reachable Wi-Fi. Configure the full HTTPS endpoint and active `X-API-Key` in Connections. The default endpoint is `https://10.238.118.117:8000/sensor-data`. The key is entered at runtime and encrypted with AndroidKeyStore; no active key or TLS private key is included in this release.
5. Confirm an acknowledged upload and corresponding rows on the real server. Pairing, sensor signal quality, screen-off operation, and end-to-end server delivery need physical testing; see VALIDATION.md for completed checks.

## Implemented behavior

- Settings has a **Bluetooth connection** card, so connection controls remain accessible after signing in or entering demo. Opening Connections does not replace demo readings; use **View live readings** after receiving actual samples.
- **Classic Bluetooth (SPP)** uses Android's secure RFCOMM socket and system pairing flow. **Scan for ESP32** lists matching paired devices and discovers nearby devices. **Stop scan** cancels discovery. **Bluetooth MAC address → Connect by MAC** connects to the address printed by the supplied firmware. The app does not supply a fixed PIN or silently approve pairing.
- **Bluetooth Low Energy (BLE)** retains service-filtered scanning, GATT discovery, and notification subscription for the optional older BLE firmware. SPP and BLE are different transports; select the one implemented by the board. Changing type during live monitoring requires confirming that monitoring will stop. Toggling the type does not automatically start a connection.
- Strict UTF-8 NDJSON framing accepts complete sensor snapshots from either transport, with a 16 KiB line limit, recovery after malformed/oversized frames, connection reset, and stale-callback guards.
- A user-started connected-device foreground service owns the live session, with an ongoing notification and Stop action. Reconnect uses bounded delays. A new connection needs a new complete sample; readings expire after ten seconds without one.
- Phone charts contain actual received history. Invalid heart-rate/SpO₂ estimates remain unavailable and raw optical counts remain separate. No demo readings are inserted in live mode.
- Wi-Fi-bound HTTPS POST preserves the server's JSON schema and uses `X-API-Key`, standard hostname checks, and Android plus the supplied public-certificate trust. There is no mobile-data fallback or disabled TLS verification.
- A private durable FIFO holds at most 200 real samples, with bounded pending input, explicit overflow notices, retry/backoff, and per-server queue binding. A sample leaves after positive `record_id` acknowledgement and atomic local queue update.
- Existing Home, native sensor charts, local profile, appearances, and 13 alert interfaces remain. Demo history is confined to Explore demo. This update does not integrate or retrain an ML model.

## Limits to preserve

This is a prototype gateway, not a diagnosis system. Receiving raw or processed sensor fields does not establish clinical validity. Surface temperature is not validated core-body temperature. Live mode does not manufacture the 13 demo alert types. Optional device-supplied alerts can populate the local inbox, but review remains local because the supplied server has no alert/acknowledgement API. The once-per-second snapshots in the teammate firmware do not supply the continuous high-rate movement series needed to validate the offline fall model.

The FastAPI handler appends accepted POSTs without deduplicating sample IDs. Delivery is **at least once**: a timeout after server commit can cause a repeated database row. The phone preserves device identifiers and timestamps already present; it adds UTC `phone_received_at` and supplies `timestamp` when missing. A device uptime is not a wall-clock timestamp. Do not assume every firmware version provides a boot identifier or a durable disconnected-sample queue.

The public certificate must match the active server address and date. Update the certificate when needed instead of disabling TLS or hostname validation. Android force-stop, restart, foreground-service limits, or manufacturer battery policy can interrupt monitoring. There is no promised automatic startup after reboot.

## Firmware included with this source

`firmware/care_monitor_ble/care_monitor_ble.ino` is the **optional, unchanged legacy v0.6.0 BLE sketch**. Its README and compile evidence describe that historical sketch. It is not the teammate's classic SPP firmware and is not required for the primary v0.6.2 route. Select BLE only if that older sketch is already on the board. Do not replace the teammate firmware to test SPP.

The teammate's uploaded server/firmware archive remains a private development input and is excluded from the source release. It contains environment-specific settings and is not redistributed here. No new firmware compile or board flash is claimed for this app update, and no separately versioned v0.6.2 firmware ZIP is produced.

## Build and checks

Use Python 3, a Java 21-compatible JDK, Android API 36, and Build Tools 36.0.0:

```bash
python scripts/test_reading_summary.py
python scripts/build_apk.py
python scripts/package_release.py
```

The builder accepts `--jdk` and `--sdk`. The local portable toolchain is `C:/Users/29682/Documents/Codex/toolchains/android-build`. Keep the original development signing key outside the project; a different key cannot update an existing installation signed with the original key.

The builder uses official aapt2, javac, D8, apksigner, and alignment verification. `artifacts/BUILD_EVIDENCE.json` records actual build inputs, version, checks, and APK hash. The packager verifies those hashes against current sources and the enclosed APK. `artifacts/JVM_TEST_RESULTS.txt` and VALIDATION.md distinguish model/protocol checks from phone installation and actual hardware tests.

## Main files

- MainActivity.java: live/demo entrances, permissions, transport selection, connection configuration, and native screens.
- LiveMonitorService.java: foreground session, sample freshness, reconnect, and relay ownership.
- BleSensorClient.java / classic SPP client: selectable Bluetooth transports.
- NdjsonFramer.java: shared strict UTF-8 stream framing.
- ServerRelay.java / RelaySecureSettings.java / RelayPolicy.java: durable HTTPS forwarding and encrypted runtime key settings.
- docs/PHONE_SETUP.md: practical phone pairing and server instructions.
- docs/HTTPS_RELAY.md: relay contract, persistence, retry, and security details.
