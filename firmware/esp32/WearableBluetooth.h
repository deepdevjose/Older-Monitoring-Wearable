#pragma once

#include <Arduino.h>
#include <esp_gap_bt_api.h>
#include <esp_spp_api.h>
#include <freertos/FreeRTOS.h>

// Encrypted Classic SPP transport. Application authentication belongs to the
// sketch: the transport never treats Bluetooth bonding as app authorization.
// One live owner, including an unauthenticated owner, excludes other clients.
// Call poll() on every loop iteration. Callers should pass a captured session
// to sendLine/disconnect so delayed work cannot affect a replacement owner.
class WearableBluetooth {
 public:
  static constexpr size_t MAX_RX_LINE = 192;
  static constexpr size_t MAX_TX_LINE = 4095;

  struct Stats {
    uint32_t acceptedConnections;
    uint32_t rejectedConnections;
    uint32_t rejectedInput;
    uint32_t txTimeouts;
    uint32_t txFailures;
  };

  bool begin(const char* name);
  bool connected() const;
  uint32_t generation() const;
  bool readLine(String& line, uint32_t& session);
  // Queues one complete newline-delimited record; false means busy or closed.
  bool sendLine(const String& line, uint32_t session = 0);
  void disconnect(uint32_t session = 0);
  void poll();
  Stats stats() const;

 private:
  static constexpr size_t RX_QUEUE_SIZE = 4;
  static constexpr size_t TX_CHUNK_SIZE = 330;
  static constexpr uint32_t TX_TIMEOUT_MS = 5000;
  static WearableBluetooth* instance_;
  static void gapCallback(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t* param);
  static void sppCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t* param);
  void onGap(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t* param);
  void onSpp(esp_spp_cb_event_t event, esp_spp_cb_param_t* param);
  void resetRecordsLocked();
  void rejectInputLocked();
  void advanceGenerationLocked();
  void finishOperation();

  mutable portMUX_TYPE mux_ = portMUX_INITIALIZER_UNLOCKED;
  char name_[64] = {};
  bool begun_ = false;
  bool ready_ = false;
  bool failed_ = false;
  uint32_t owner_ = 0;
  uint32_t generation_ = 0;
  uint8_t ownerAddress_[6] = {};
  // A close racing a write keeps new owners excluded until that API call has
  // returned; this prevents an old handle snapshot reaching a replacement.
  unsigned operations_ = 0;
  bool retiring_ = false;
  bool scanDirty_ = false;
  bool disconnectPending_ = false;
  bool disconnectIssued_ = false;

  char rxPartial_[MAX_RX_LINE + 1] = {};
  size_t rxLength_ = 0;
  bool rxCarriageReturn_ = false;
  char rxLines_[RX_QUEUE_SIZE][MAX_RX_LINE + 1] = {};
  size_t rxSizes_[RX_QUEUE_SIZE] = {};
  uint32_t rxSessions_[RX_QUEUE_SIZE] = {};
  size_t rxHead_ = 0;
  size_t rxCount_ = 0;

  uint8_t txBuffer_[MAX_TX_LINE + 1] = {};
  size_t txLength_ = 0;
  size_t txOffset_ = 0;
  size_t txChunk_ = 0;
  uint32_t txSession_ = 0;
  uint32_t txProgressAt_ = 0;
  bool txInFlight_ = false;
  bool congested_ = false;
  Stats stats_ = {};
};
