# Changelog

## 0.6.2 — 20 October 2026

Added secure classic Bluetooth SPP reception for the group's existing ESP32 firmware, with Android system pairing, matching paired-device listing, discovery, manual MAC entry, and connection controls from Settings. Classic Bluetooth (SPP) is the default; Bluetooth Low Energy (BLE) remains selectable for the older Care Monitor BLE sketch. Both transports use shared NDJSON handling, freshness rules, foreground monitoring, and the Wi-Fi HTTPS queue. Switching transport during monitoring requires confirming that monitoring stops.

Version code 10 preserves the application ID and development certificate. Keep the teammate firmware unchanged; its numeric pairing requires checking the phone code and typing Y in the ESP32 Serial Monitor within 30 seconds. The source still includes the unchanged optional v0.6.0 BLE sketch with historical compile evidence; no v0.6.2 firmware ZIP is generated. ML behavior is unchanged. See VALIDATION.md and artifact evidence for completed build/install checks and physical testing boundaries.

## 0.6.1 — 20 October 2026

Placed a dedicated Bluetooth connection card first in Settings, with current connection state and a Connect / manage ESP32 action. Connection controls remain accessible after demo sign-in. Back returns to Settings, including after switching to live mode or recreating the Activity. Added a Bluetooth icon and clearer menu labels.

Version code 9 preserves application data and the development certificate. The APK was installed and launched on the connected Android phone. BLE firmware and ML behavior are unchanged.

## 0.6.0 — 20 October 2026

Implemented the ESP32 BLE → Android → Wi-Fi HTTPS route with matching BLE firmware, runtime permissions, foreground monitoring, connection-scoped freshness, automatic reconnect, real received-history charts, encrypted runtime server key, certificate/hostname verification and a bounded durable upload queue. Explore demo stays separate and never uploads mock data. No automatic medical-alert algorithms have been added.

Version code 8 preserves the application ID and development certificate. Physical BLE/server testing remains a team setup step.

## 0.5.0 — 20 October 2026

Added 13 alert/event types to the unified inbox and detail flow, category icons, All/To review/Reviewed filters, individual acknowledgement, accurate pending badges and matching Home review state. Watch worn is an informational event. Alert source and detection limitations remain visible. All events are still simulated; Bluetooth reception and Wi-Fi server forwarding await protocol/API details.

Version code 7 preserves the application ID and development certificate.

## 0.4.0 — 16 October 2026

Implemented the approved redesign: compact demo strip, prominent Home reading status, Device ID, native sensor charts, orange surface/blue ambient temperatures, saved local profile, top-right Settings/About menu and Home/Sensors/Alerts/Profile navigation. Removed Scenarios. Added Clear care and Health focus appearances. Data remains simulated.

Version code 6 preserves the application ID and development certificate for updates from 0.3.2.

## 0.3.2 — 15 October 2026

Added small English labels beneath the original bottom navigation icons.

