#include "WearableBluetooth.h"

#include <algorithm>
#include <cassert>
#include <functional>
#include <iostream>
#include <utility>
#include <vector>

namespace {
uint32_t now = 1;
esp_bt_gap_cb_t gap = nullptr;
esp_spp_cb_t spp = nullptr;
int securityMask = 0;
bool confirmation = false;
bool legacyAccepted = true;
uint8_t ioCapability = 255;
std::vector<uint32_t> rejected;
std::vector<std::pair<uint32_t, std::string>> writes;
std::vector<std::pair<int, int>> scans;
std::function<void()> writeHook;
uint32_t pendingHandle = 0;
size_t pendingLength = 0;

void open(uint32_t handle) {
  esp_spp_cb_param_t event = {};
  event.srv_open.status = ESP_SPP_SUCCESS;
  event.srv_open.handle = handle;
  std::fill_n(event.srv_open.rem_bda, 6, static_cast<uint8_t>(handle));
  spp(ESP_SPP_SRV_OPEN_EVT, &event);
}
void close(uint32_t handle) {
  esp_spp_cb_param_t event = {};
  event.close.handle = handle;
  event.close.status = ESP_SPP_SUCCESS;
  event.close.async = true;
  spp(ESP_SPP_CLOSE_EVT, &event);
}
void input(uint32_t handle, const std::string& value) {
  esp_spp_cb_param_t event = {};
  event.data_ind.handle = handle;
  event.data_ind.len = value.length();
  event.data_ind.data = reinterpret_cast<uint8_t*>(const_cast<char*>(value.data()));
  spp(ESP_SPP_DATA_IND_EVT, &event);
}
void complete(uint32_t handle, size_t length, bool congested = false) {
  esp_spp_cb_param_t event = {};
  event.write.handle = handle;
  event.write.len = static_cast<int>(length);
  event.write.status = ESP_SPP_SUCCESS;
  event.write.cong = congested;
  spp(ESP_SPP_WRITE_EVT, &event);
}
void congestion(uint32_t handle, bool congested) {
  esp_spp_cb_param_t event = {};
  event.cong.handle = handle;
  event.cong.cong = congested;
  spp(ESP_SPP_CONG_EVT, &event);
}
void drain(WearableBluetooth& transport) {
  // Each iteration permits at most one chunk and acknowledges exactly it.
  for (unsigned i = 0; i < 20; ++i) {
    const size_t count = writes.size();
    transport.poll();
    if (count == writes.size()) break;
    complete(pendingHandle, pendingLength);
  }
}
}  // namespace

uint32_t millis() { return now; }
void delay(uint32_t duration) { now += duration; }
bool btStarted() { return false; }
bool btStartMode(bt_mode) { assert(hostClassicMemoryRetained); return true; }
esp_bluedroid_status_t esp_bluedroid_get_status() { return ESP_BLUEDROID_STATUS_ENABLED; }
esp_err_t esp_bluedroid_init() { return ESP_OK; }
esp_err_t esp_bluedroid_enable() { return ESP_OK; }
esp_err_t esp_bt_gap_register_callback(esp_bt_gap_cb_t value) { gap = value; return ESP_OK; }
esp_err_t esp_bt_gap_set_device_name(const char*) { return ESP_OK; }
esp_err_t esp_bt_gap_set_security_param(int, void* value, uint8_t) {
  ioCapability = *static_cast<uint8_t*>(value); return ESP_OK;
}
esp_err_t esp_bt_gap_set_scan_mode(esp_bt_connection_mode_t connected, esp_bt_discovery_mode_t discovery) {
  scans.emplace_back(connected, discovery); return ESP_OK;
}
esp_err_t esp_bt_gap_ssp_confirm_reply(uint8_t*, bool accept) { confirmation = accept; return ESP_OK; }
esp_err_t esp_bt_gap_pin_reply(uint8_t*, bool accept, uint8_t, uint8_t*) { legacyAccepted = accept; return ESP_OK; }
esp_err_t esp_bt_gap_ssp_passkey_reply(uint8_t*, bool accept, uint32_t) { legacyAccepted = accept; return ESP_OK; }
esp_err_t esp_spp_register_callback(esp_spp_cb_t value) { spp = value; return ESP_OK; }
esp_err_t esp_spp_enhanced_init(esp_spp_cfg_t* config) {
  assert(config->mode == ESP_SPP_MODE_CB);
  esp_spp_cb_param_t event = {};
  spp(ESP_SPP_INIT_EVT, &event); return ESP_OK;
}
esp_err_t esp_spp_start_srv(int security, int, uint8_t, const char*) {
  securityMask = security;
  esp_spp_cb_param_t event = {};
  spp(ESP_SPP_START_EVT, &event); return ESP_OK;
}
esp_err_t esp_spp_disconnect(uint32_t handle) { rejected.push_back(handle); return ESP_OK; }
esp_err_t esp_spp_write(uint32_t handle, int length, uint8_t* data) {
  if (writeHook) { auto hook = std::move(writeHook); writeHook = {}; hook(); }
  writes.emplace_back(handle, std::string(reinterpret_cast<char*>(data), length));
  pendingHandle = handle; pendingLength = length; return ESP_OK;
}

int main() {
  WearableBluetooth transport;
  assert(transport.begin("ElderSensors-owned"));
  assert(securityMask == (ESP_SPP_SEC_AUTHENTICATE | ESP_SPP_SEC_ENCRYPT));
  assert(ioCapability == ESP_BT_IO_CAP_NONE);
  assert(!transport.connected());
  assert(scans.back() == std::make_pair(int(ESP_BT_CONNECTABLE), int(ESP_BT_GENERAL_DISCOVERABLE)));
  esp_bt_gap_cb_param_t gapEvent = {};
  gap(ESP_BT_GAP_CFM_REQ_EVT, &gapEvent);
  assert(confirmation);  // No serial input while unoccupied.
  gap(ESP_BT_GAP_PIN_REQ_EVT, &gapEvent);
  assert(!legacyAccepted);

  open(1);
  const uint32_t first = transport.generation();
  assert(transport.connected() && first);
  assert(scans.back() == std::make_pair(int(ESP_BT_NON_CONNECTABLE), int(ESP_BT_NON_DISCOVERABLE)));
  open(2);
  assert(rejected.back() == 2 && transport.stats().acceptedConnections == 1);
  input(2, "intruder\n");
  String line;
  uint32_t session = 0;
  assert(!transport.readLine(line, session));
  close(2);
  assert(transport.connected() && transport.generation() == first);
  gap(ESP_BT_GAP_CFM_REQ_EVT, &gapEvent);
  assert(!confirmation);  // A second remote cannot pair while occupied.
  std::fill_n(gapEvent.cfm_req.bda, 6, 1);
  gap(ESP_BT_GAP_CFM_REQ_EVT, &gapEvent);
  assert(confirmation);

  input(1, "CM1 HEL");
  assert(!transport.readLine(line, session));
  input(1, "LO\r\nsecond\n");
  assert(transport.readLine(line, session) && std::string(line.c_str()) == "CM1 HELLO" && session == first);
  assert(transport.readLine(line, session) && std::string(line.c_str()) == "second");
  assert(!transport.readLine(line, session));
  assert(!transport.sendLine("stale", first + 1));
  transport.disconnect(first + 1);
  assert(transport.connected());

  assert(transport.sendLine(String(std::string(800, 'a')), first));
  assert(!transport.sendLine("busy", first));
  transport.poll();
  const size_t afterFirstChunk = writes.size();
  transport.poll();
  assert(writes.size() == afterFirstChunk && writes.back().second.length() == 330);
  complete(2, 330);  // A different handle cannot release the in-flight write.
  transport.poll();
  assert(writes.size() == afterFirstChunk);
  complete(1, 330, true);
  transport.poll();
  assert(writes.size() == afterFirstChunk);
  congestion(2, false);
  transport.poll();
  assert(writes.size() == afterFirstChunk);
  congestion(1, false);
  drain(transport);
  std::string whole;
  for (const auto& write : writes) { assert(write.first == 1); whole += write.second; }
  assert(whole == std::string(800, 'a') + "\n");
  assert(!transport.sendLine("embedded\nline", first));
  assert(!transport.sendLine(String(std::string(4096, 'x')), first));

  input(1, std::string(192, 'b') + "\n");
  assert(transport.readLine(line, session) && line.length() == 192);
  input(1, std::string(193, 'b') + "\n");
  assert(!transport.connected() && transport.stats().rejectedInput == 1);
  transport.poll();
  assert(rejected.back() == 1);
  close(1); transport.poll(); open(3);
  assert(transport.connected() && transport.generation() != first);
  assert(!transport.sendLine("old-work", first));
  transport.disconnect(first);
  assert(transport.connected());
  input(3, "one\ntwo\nthree\nfour\nfive\n");
  assert(!transport.connected() && !transport.readLine(line, session));
  transport.poll(); close(3); transport.poll(); open(4);
  input(4, "hello\rX\n");
  assert(!transport.connected());
  transport.poll(); close(4); transport.poll(); open(5);
  input(5, std::string("nul\0byte\n", 9));
  assert(!transport.connected());
  transport.poll(); close(5); transport.poll(); open(6);

  assert(transport.sendLine("challenge", transport.generation()));
  transport.poll();
  now += 5001;
  transport.poll();
  assert(!transport.connected() && transport.stats().txTimeouts == 1 && rejected.back() == 6);
  close(6); transport.poll(); open(7);
  assert(transport.sendLine("old connection", transport.generation()));
  writeHook = [] { close(7); open(8); };
  transport.poll();
  assert(!transport.connected() && rejected.back() == 8);
  assert(writes.back().first == 7 && writes.back().second == "old connection\n");
  transport.poll(); open(9);
  assert(transport.connected());
  complete(7, 15);  // Late events from the former owner cannot affect owner 9.
  assert(transport.sendLine("new connection", transport.generation()));
  drain(transport);
  assert(writes.back().first == 9 && writes.back().second == "new connection\n");
  assert(transport.stats().rejectedConnections == 2);
  transport.disconnect(transport.generation());
  transport.poll();
  assert(!transport.connected() && rejected.back() == 9);
  close(9); transport.poll();
  assert(scans.back() == std::make_pair(int(ESP_BT_CONNECTABLE), int(ESP_BT_GENERAL_DISCOVERABLE)));
  std::cout << "PASS: native SPP ownership, framing, generation, congestion, timeout and close/write race checks\n";
}
