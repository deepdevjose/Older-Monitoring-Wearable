# Care Monitor v0.6.2: phone and server setup

The primary route is **ESP32 classic Bluetooth SPP → Android phone → Wi-Fi HTTPS → computer server**. Keep the group's existing `Iot App(2)` firmware on the ESP32. The phone supplies the server connection. Explore demo remains separate and never uploads simulated readings.

## 1. Prepare the existing ESP32

1. Power the ESP32 running the group's classic Bluetooth firmware. It identifies itself as **ElderSensors-elder-demo-01**, with device ID **elder-demo-01**.
2. Open its Serial Monitor, using the baud rate configured in the team's sketch. The current sketch prints its Bluetooth MAC address at startup. Keep this window accessible for the numeric pairing confirmation.
3. Keep the ESP32 near the phone. There is no firmware replacement or flashing step for this app update if the team's current firmware is already running.

The source release also contains `firmware/care_monitor_ble/care_monitor_ble.ino`, the optional unchanged **v0.6.0 BLE** sketch. It is a different transport. Use **Bluetooth Low Energy (BLE)** only for a board already running that sketch; its device name is **CareMonitor-elder-demo-01**. Do not upload the legacy sketch when testing the team's SPP firmware.

## 2. Install and open Connections

1. Install/update **CareMonitor-v0.6.2.apk** on an Android 7.0+ phone. The source ZIP is for development, not installation. This version preserves the application ID and development certificate used by earlier Care Monitor versions.
2. Open **⋮ → Settings → Bluetooth connection → Connect / manage ESP32**. The welcome screen's **Connect a live ESP32** opens the same Connections screen without account credentials. Back returns to Settings when that was the entry route.
3. Choose **Classic Bluetooth (SPP)**, the default type for the group's firmware. The second option, **Bluetooth Low Energy (BLE)**, is for the older BLE sketch. Changing type during an active session asks to stop monitoring; it does not connect automatically.
4. Tap **Scan for ESP32**. Allow **Nearby devices** on Android 12+, or **Location** on Android 7–11 when requested for discovery. On older Android versions, enable system Location if discovery returns no devices. Accept Android's Bluetooth-enable prompt if needed. On Android 13+, notification permission makes the ongoing monitoring status and Stop action easy to find.
5. Choose **ElderSensors-elder-demo-01**. Matching paired devices are listed alongside discovery results. Discovery ends after about 20 seconds; **Stop scan** ends it sooner. If the name is not found, check the ESP32 startup output and enter its exact address in **Bluetooth MAC address**, then tap **Connect by MAC**. Select only the intended board.

## 3. Complete secure classic Bluetooth pairing

The app uses Android's system pairing and a secure SPP/RFCOMM connection. The group's ESP32 firmware uses numeric comparison and waits for a manual serial confirmation:

1. Check the pairing code displayed on the phone against the numeric comparison code printed in the ESP32 Serial Monitor.
2. If they match, approve the Android pairing request and type **`Y` in the ESP32 Serial Monitor within 30 seconds** when prompted. Follow both device prompts; a phone-only approval cannot replace the firmware's serial confirmation.
3. If the codes differ, reject the request. If the prompt expires, retry the connection and repeat the matching-code confirmation.
4. A previously paired board may reconnect without another pairing prompt. An ESP32 whose saved pairing state was erased can require pairing again through Android settings and a new serial confirmation.

The app does not use a hardcoded PIN, automatically approve requests, or fall back to an insecure socket. If pairing is blocked, check the phone's Bluetooth pairing notification and the ESP32's Serial Monitor instead of repeatedly scanning.

After connection, confirm the **received-message count increases**, then tap **View live readings**. Home shows the selected live Bluetooth type. An established socket is insufficient: a changing count confirms complete sensor JSON frames arrive. Before valid data arrives, unavailable readings stay unavailable. A sample becomes stale after about ten seconds without a fresh connected-device message. Reconnecting does not make an old sample fresh.

The optical sensor's invalid estimates remain `null`. Raw IR/red counts without valid BPM or SpO₂ require checking finger contact, positioning, signal quality, and calibration; the app will not turn raw counts into invented clinical values.

## 4. Start the team's HTTPS server

1. On the Linux server computer, use the existing FastAPI environment and project. Run its supplied `start_server.sh` from that project folder. The supplied configuration uses HTTPS on **10.238.118.117:8000** with the team's certificate/private key.
2. Connect phone and computer to the same reachable Wi-Fi network or hotspot. Client isolation, school captive portals, VPN routes, or a blocked TCP port can prevent access. Confirm the computer owns the address and permits inbound TCP 8000 on the intended network.
3. Ask the server teammate for the **active `SENSOR_API_KEY`**. Paste its value, without the variable name, export statement, or surrounding quotes. No active server key is bundled in the app/source release.

The default POST endpoint is:

```text
https://10.238.118.117:8000/sensor-data
```

The bundled public certificate supports the supplied address **10.238.118.117**. Keep the phone clock correct. If the server IP changes, provide a certificate with the matching subject alternative name and update the bundled public certificate as necessary. Retain TLS verification and hostname checks.

## 5. Configure uploads and confirm receipt

1. In **Connections → Wi-Fi server**, enter the full **HTTPS endpoint** and **Server API key**. Tap **Save server settings**. Wait briefly and retry if the service is still starting. Check the resulting status; the key field clears after submitting configuration and the saved key is encrypted with AndroidKeyStore.
2. While monitoring runs, actual received samples enter the persistent upload queue. The app uses Wi-Fi and never falls back to mobile data. Watch **Server**, **Queued**, and **Last upload**. **Server acknowledged real sample** means a 2xx reply contained `status: "received"` and a positive `record_id`.
3. Ask the server teammate to confirm a new saved record in SQLite or authenticated `/sensor-data` history. Match `device_id`, device sample identifiers when supplied, and timestamps to distinguish this run from older rows. The app displays upload time/status rather than the returned record ID. Avoid an extra GET after every POST: protected server routes share a 100 ms request limit.
4. The phone adds `phone_received_at` in UTC and supplies `timestamp` in UTC when missing. The server separately records its receive time in Asia/Shanghai. Device uptime is not a calendar date. Compare identifiers and account for time zones.

## 6. Stop, recover, and retain data

- **Stop live monitoring**, the ongoing notification's **Stop**, or changing to demo ends the Bluetooth session and pauses uploads. Pending samples, encrypted server settings, and the local profile remain saved. Stop cannot undo a request the server already accepted. Reopen Connections and select a device to resume.
- The private durable FIFO holds **200 samples**. If full, the newest sample is dropped with a visible status. Pending input is also bounded. A drop notice represents missing data, not a complete offline recording.
- Pending samples are bound to their original endpoint. Deliver them first or explicitly confirm **Discard queued uploads** before switching servers. Discard affects the phone queue, not rows already saved on the computer.
- A 401/403 response needs correction of the active API key. Other persistent client errors retain the queue; 429 and temporary server/network failures retry with backoff. A TLS error requires checking certificate, address, and phone clock.
- Delivery is **at least once**. A lost response after server commit can create a duplicate on retry because the current handler does not deduplicate device sample IDs.
- Android process limits, screen-off battery policy, force-stop, reboot, or firmware reset can interrupt monitoring. Test screen-off behavior on the team's actual phone. There is no promised automatic restart after reboot or force-stop. Live chart history is not a complete patient record.
- Live mode does not manufacture fall, pressure, hydration, wear-state, oxygen, heart-rate, or temperature alerts. Optional device-supplied alerts are reviewed locally; the supplied server has no alert acknowledgement API. Surface temperature is not calibrated core temperature. No ML model has been integrated by this Bluetooth update.

## 7. Physical acceptance test

Record the APK version, phone model/Android version, firmware identity, transport, and observed result:

1. Verify Settings opens Connections, SPP is selected, and Back returns correctly. Check the optional BLE selector without connecting to an unrelated device.
2. Pair the intended board using matching phone/serial numeric confirmation. Confirm increasing complete-message counts, actual changing values, and correct unavailable/null handling.
3. Disconnect/reconnect the board. Verify stale status after ten seconds and that a fresh sample is required after reconnection. Stop monitoring and confirm the session ends.
4. With reachable Wi-Fi/server settings, confirm an acknowledged upload and the corresponding real database row. Briefly interrupt Wi-Fi and confirm queue retention/resumption without mobile-data forwarding.
5. Test screen-off monitoring and any manufacturer battery restrictions on the actual phone.

See `VALIDATION.md`, `artifacts/BUILD_EVIDENCE.json`, and any phone installation evidence for the checks completed for this build. Compilation, unit checks, APK installation, and UI navigation do not prove that the ESP32 has paired, sensor estimates are calibrated, or the server has accepted a record.
