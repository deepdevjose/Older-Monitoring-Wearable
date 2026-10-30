#pragma once
// Copy to secrets.h and supply your own Wi-Fi network.
// Wi-Fi provides optional NTP only; health data travels over Bluetooth SPP.
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"

// Generate a different 128-bit credential for each physical board (32 lowercase hex digits).
// Enter it once in the older adult app. Never put the real value in source control.
#define WEARABLE_SETUP_KEY_HEX "REPLACE_WITH_PRIVATE_32_HEX_KEY!!"
