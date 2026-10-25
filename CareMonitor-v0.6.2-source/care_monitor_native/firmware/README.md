# Care Monitor ESP32 BLE firmware

This sketch replaces the ESP32-to-server network transport with an ESP32-to-Android BLE connection. The phone receives complete sensor snapshots and forwards them to the group's FastAPI server. The firmware contains no network address, server key, network credentials, certificate, or time-server setup.

Open `care_monitor_ble/care_monitor_ble.ino` in Arduino IDE. The original team sketch remains unchanged in the incoming project archive. This firmware targets an **original ESP32 Dev Module** with a MAX30102-compatible optical module, not MAX30100. Check the actual chip and board before uploading; the SparkFun MAX3010x algorithm is not a MAX30100 driver.

## Hardware and dependencies

| Connection | ESP32 pin/address |
| --- | --- |
| Shared I2C SDA / SCL | GPIO 21 / GPIO 22 |
| MAX30102 | I2C `0x57` |
| GY-906 / MLX90614 | I2C `0x5A` |
| MPU6050 | I2C `0x68` |
| Microphone analog signal | GPIO 34 |
| Photoresistor analog signal | GPIO 35 |
| Buzzer output | GPIO 25, held LOW |

Tested build dependencies:

- Arduino CLI 1.5.1.
- Espressif Arduino-ESP32 core 3.3.11, with its built-in `BLEDevice`, `BLEServer`, and `BLE2902` Bluedroid library.
- ArduinoJson 7.4.3.
- SparkFun MAX3010x Pulse and Proximity Sensor Library 1.1.2.
- FQBN: `esp32:esp32:esp32`.

Install the two Arduino libraries using Library Manager. Do not install a second third-party BLE library that shadows the ESP32 core's built-in BLE classes. With Arduino CLI:

```powershell
arduino-cli lib install "ArduinoJson@7.4.3" "SparkFun MAX3010x Pulse and Proximity Sensor Library@1.1.2"
arduino-cli compile --fqbn esp32:esp32:esp32 care_monitor_ble
```

To test with the group's hardware:

1. Open the sketch, choose **ESP32 Dev Module**, and select the board's actual USB port in Arduino IDE. Verify first, then upload when ready. The old network sketch and this BLE sketch cannot run on the same board at the same time.
2. Open the new Android app, select its live Bluetooth mode, grant the requested Bluetooth permissions, and connect to `CareMonitor-elder-demo-01` from inside the app. The ESP32 does not need to join the hotspot.
3. Configure the group's server address and required access details in the phone app. The phone and server computer must be able to reach each other through their network connection.
4. Check that the app receives changing raw sensor values. With no optical contact, heart rate and oxygen should remain unavailable. Hold the sensor steadily for several seconds; valid estimates depend on actual signal quality and calibration.
5. Disconnect and reconnect, verify the receive buffer starts with a complete new object, and confirm that the server receives new snapshots. Removing the optical module or stopping its samples should invalidate its estimates after two seconds.

## BLE protocol

| Item | Value |
| --- | --- |
| Device ID in every JSON object | `elder-demo-01` |
| Advertised device name | `CareMonitor-elder-demo-01` |
| Service UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| TX characteristic, Notify only | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| Client configuration descriptor | UUID `0x2902`; write `01 00` to subscribe |
| Framing | UTF-8 JSON object followed by exactly one LF byte (`0A`) |
| Normal reporting interval | 1,000 ms |
| Maximum notification payload | 20 bytes, including any final LF byte |
| Minimum interval between fragments | 8 ms |

The primary advertisement contains flags plus the complete service UUID (21 bytes total). The separate scan response contains the complete device name (27 bytes). Both fit within the respective 31-byte legacy BLE advertising limits.

A notification is a fragment, **not a complete JSON message**. Android must append raw bytes until LF, then decode and parse the full object. Notification size does not depend on a successful larger MTU negotiation. The firmware waits for the phone to enable notifications; a connection alone does not start streaming. It discards a partial frame after a new connection, disconnect, or subscription change and starts again with a complete object. Android must also clear its receive buffer on disconnect and new subscription.

The first report is scheduled about 100 ms after a subscription change so the descriptor-write acknowledgment can reach Android first. This uses a timestamp check, not a sampling pause.

Only one frame can be in flight. A slow connection skips a new report rather than replacing or interleaving frames. Local GATT queue errors retry the same fragment on a later loop iteration. A frame stalled for four seconds causes a disconnect so an incomplete object cannot be concatenated with a new one. Advertising resumes from the main loop after disconnect. There is no application acknowledgment or guaranteed delivery queue on the ESP32; notification acceptance means only that the local BLE stack accepted it.

Every snapshot retains the team's existing `max30102`, `mpu6050`, `gy906`, `microphone`, `photoresistor`, and `buzzer` object shapes and adds:

```json
{
  "device_id": "elder-demo-01",
  "uptime_ms": 12345,
  "boot_nonce": "483d006fe07944a2",
  "sample_id": 12
}
```

`boot_nonce` is generated once per boot. `sample_id` increases within that boot; the `(device_id, boot_nonce, sample_id)` tuple identifies a snapshot across restarts. `uptime_ms` is monotonic device time, not a calendar timestamp. The phone adds its current UTC timestamp when forwarding. The example above shows metadata only, not a fabricated sensor snapshot.

## Sensor behavior and limits

The optical FIFO is drained frequently during BLE transmission. The original 100-sample calculation window, 25-sample step, LED configuration, finger threshold, validation bounds, I2C addresses, and sensor update periods are retained. This transport does not reset the optical FIFO to handle a notification backlog. Invalid heart-rate/oxygen estimates remain JSON `null`; raw IR/red readings are not converted into invented BPM or SpO2 values.

Two status-handling fixes accompany the transport change: a calculated optical status stays current between window calculations instead of being overwritten with `warming_up` after each sample, and a two-second absence of optical samples invalidates old estimates with `read_error`. Sample recovery starts a fresh calculation window without resetting the hardware FIFO.

The GY-906 object reading is surface temperature, not a calibrated core-body temperature. Microphone and light outputs remain raw ADC readings with the team's light percentage mapping. GPIO 25 is reported but this sketch does not remotely actuate the buzzer. Sensor calibration and meaningful signal validation still require testing with the actual hardware.

No blood-pressure, hydration, watch-contact, or confirmed fall-detection measurement has been added. One-second motion snapshots do not retain every 20 Hz acceleration sample and cannot guarantee detection of a short fall. Optical `finger_detected` describes optical contact only; it is not proof that a watch is worn correctly.

This is a classroom BLE prototype. Pairing, bonding, authenticated encryption, and medical validation are outside this implementation. Android v0.6.0 uses a connected-device foreground service for the phone connection and forwarding; the firmware does not control phone background behavior. See the app's live-connection guide for Android permissions, its ongoing service notification, and operating-system limits.

## Verification

The final sketch compiled successfully on **20 October 2026**, using the pinned dependencies above and the default ESP32 Dev Module partition: **1,142,419 bytes of flash (87% of 1,310,720 bytes)** and **44,660 bytes of global RAM (13% of 327,680 bytes)**. See `compile-v0.6.0.log` for the actual compiler output and `FIRMWARE_BUILD_EVIDENCE.json` for the successful exit status, final source SHA-256, dependency versions, and binary hashes. The final source text was also checked against the merged sketch input used by the compiler.

The optional compile-only helper captures a UTF-8 compiler log and regenerates the build receipt without uploading:

```powershell
python build_ble_firmware.py
```

A successful compile verifies the pinned ESP32/BLE APIs and linkage. It does not verify sensor readings, antenna range, reconnect behavior with a physical phone, or real end-to-end forwarding. No board was flashed during development.
