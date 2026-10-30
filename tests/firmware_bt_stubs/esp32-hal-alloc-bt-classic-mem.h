#pragma once

// Mirror the official Arduino core's pre-app_main retention constructor.
// Native Classic transports must opt in before initArduino releases memory.
inline bool hostClassicMemoryRetained = false;
__attribute__((constructor)) static void retainClassicMemoryForTest() {
  hostClassicMemoryRetained = true;
}
