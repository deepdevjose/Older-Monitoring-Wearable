# Unchanged, working ESP32 baseline

These active sources are copied byte-for-byte from the fixed v0.7.4 release:

- `team_spp_no_sound.ino`: SHA-256 `869fe3d60297464345a87b64fd590d2ddc8b0de51c7d5b50c16f7edbd401d4b5`
- `PpgReadingState.h`: SHA-256 `39465776bd444eba5a5de8364f9d55fda6b7451b129e5fca4c70c40f2e82da05`

Revision: `ppg-team-sparkfun-baseline-20261023`. The original SparkFun FIFO acquisition path, 100-sample window with 25-sample updates, 1000 ms setup delay, 1 ms loop delay, one-second report interval and Classic SPP send path are unchanged. The removed microphone is not restored.

The v0.8.0 account/ML integration does not require reflashing the ESP32. No new physical test or firmware flash was performed during this integration.

For a later authorised firmware build, copy `secrets.example.h` to local `secrets.h` and fill network values privately. Use the classic ESP32 Dev Module, Arduino-ESP32 3.3.11, ArduinoJson 7.4.3 and SparkFun MAX3010x library 1.1.2, with `esp32:esp32:esp32:PartitionScheme=huge_app`. This SPP firmware is not for an ESP32-S3/C3. Preserve the existing board's partition and pairing data. No actual credentials, flash backup or compiled firmware is distributed here.
