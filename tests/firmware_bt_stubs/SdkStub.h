#pragma once

// Narrow host substitutes for deterministic transport state-machine tests.
// They prove software transitions, never controller encryption or RF behavior.
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#define CONFIG_BT_ENABLED 1
#define CONFIG_BLUEDROID_ENABLED 1
#define CONFIG_BT_SPP_ENABLED 1
using portMUX_TYPE = int;
#define portMUX_INITIALIZER_UNLOCKED 0
#define portENTER_CRITICAL(mux) ((void)(mux))
#define portEXIT_CRITICAL(mux) ((void)(mux))

class String {
 public:
  String() = default;
  String(const char* value) : value_(value) {}
  String(const std::string& value) : value_(value) {}
  String& operator=(const char* value) { value_ = value; return *this; }
  size_t length() const { return value_.length(); }
  const char* c_str() const { return value_.c_str(); }
  int indexOf(char value) const {
    const size_t result = value_.find(value);
    return result == std::string::npos ? -1 : static_cast<int>(result);
  }
 private:
  std::string value_;
};

uint32_t millis();
void delay(uint32_t duration);
using esp_err_t = int;
constexpr int ESP_OK = 0;
constexpr int ESP_FAIL = -1;
enum bt_mode { BT_MODE_CLASSIC_BT };
extern bool hostClassicMemoryRetained;
bool btStarted();
bool btStartMode(bt_mode);
enum esp_bluedroid_status_t {
  ESP_BLUEDROID_STATUS_UNINITIALIZED,
  ESP_BLUEDROID_STATUS_INITIALIZED,
  ESP_BLUEDROID_STATUS_ENABLED
};
esp_bluedroid_status_t esp_bluedroid_get_status();
esp_err_t esp_bluedroid_init();
esp_err_t esp_bluedroid_enable();

enum esp_bt_gap_cb_event_t {
  ESP_BT_GAP_CFM_REQ_EVT,
  ESP_BT_GAP_PIN_REQ_EVT,
  ESP_BT_GAP_KEY_REQ_EVT
};
struct esp_bt_gap_cb_param_t {
  struct { uint8_t bda[6]; } cfm_req;
  struct { uint8_t bda[6]; } pin_req;
  struct { uint8_t bda[6]; } key_req;
};
using esp_bt_gap_cb_t = void (*)(esp_bt_gap_cb_event_t, esp_bt_gap_cb_param_t*);
using esp_bt_io_cap_t = uint8_t;
constexpr int ESP_BT_IO_CAP_NONE = 3;
constexpr int ESP_BT_SP_IOCAP_MODE = 0;
enum esp_bt_connection_mode_t { ESP_BT_NON_CONNECTABLE, ESP_BT_CONNECTABLE };
enum esp_bt_discovery_mode_t { ESP_BT_NON_DISCOVERABLE, ESP_BT_GENERAL_DISCOVERABLE };
esp_err_t esp_bt_gap_register_callback(esp_bt_gap_cb_t callback);
esp_err_t esp_bt_gap_set_device_name(const char* name);
esp_err_t esp_bt_gap_set_security_param(int type, void* value, uint8_t length);
esp_err_t esp_bt_gap_set_scan_mode(esp_bt_connection_mode_t, esp_bt_discovery_mode_t);
esp_err_t esp_bt_gap_ssp_confirm_reply(uint8_t* address, bool accept);
esp_err_t esp_bt_gap_pin_reply(uint8_t* address, bool accept, uint8_t length, uint8_t* pin);
esp_err_t esp_bt_gap_ssp_passkey_reply(uint8_t* address, bool accept, uint32_t passkey);

enum esp_spp_cb_event_t {
  ESP_SPP_INIT_EVT,
  ESP_SPP_START_EVT,
  ESP_SPP_SRV_OPEN_EVT,
  ESP_SPP_CLOSE_EVT,
  ESP_SPP_DATA_IND_EVT,
  ESP_SPP_WRITE_EVT,
  ESP_SPP_CONG_EVT
};
constexpr int ESP_SPP_SUCCESS = 0;
constexpr int ESP_SPP_SEC_AUTHENTICATE = 0x0012;
constexpr int ESP_SPP_SEC_ENCRYPT = 0x0024;
constexpr int ESP_SPP_ROLE_SLAVE = 1;
constexpr int ESP_SPP_MODE_CB = 0;
struct esp_spp_cb_param_t {
  struct { int status; } init;
  struct { int status; } start;
  struct { int status; uint32_t handle; uint8_t rem_bda[6]; } srv_open;
  struct { int status; uint32_t handle; bool async; } close;
  struct { uint32_t handle; size_t len; uint8_t* data; } data_ind;
  struct { int status; uint32_t handle; int len; bool cong; } write;
  struct { uint32_t handle; bool cong; } cong;
};
using esp_spp_cb_t = void (*)(esp_spp_cb_event_t, esp_spp_cb_param_t*);
struct esp_spp_cfg_t { int mode; bool enable_l2cap_ertm; int tx_buffer_size; };
#define BT_SPP_DEFAULT_CONFIG() { 1, true, 9900 }
esp_err_t esp_spp_register_callback(esp_spp_cb_t callback);
esp_err_t esp_spp_enhanced_init(esp_spp_cfg_t* config);
esp_err_t esp_spp_start_srv(int security, int role, uint8_t channel, const char* name);
esp_err_t esp_spp_disconnect(uint32_t handle);
esp_err_t esp_spp_write(uint32_t handle, int length, uint8_t* data);
