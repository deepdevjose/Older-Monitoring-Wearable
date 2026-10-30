#include "WearableBluetooth.h"

#include <esp32-hal-alloc-bt-classic-mem.h>
#include <esp32-hal-bt.h>
#include <esp_bt_main.h>
#include <cstring>

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED) || !defined(CONFIG_BT_SPP_ENABLED)
#error "WearableBluetooth requires Classic ESP32 Bluetooth SPP."
#endif

WearableBluetooth* WearableBluetooth::instance_ = nullptr;

bool WearableBluetooth::begin(const char* name) {
  if (!name || !*name || strlen(name) >= sizeof(name_)) return false;
  if (begun_ || (instance_ && instance_ != this)) return false;
  begun_ = true;
  instance_ = this;
  memcpy(name_, name, strlen(name) + 1);
  if (!btStarted() && !btStartMode(BT_MODE_CLASSIC_BT)) return false;
  esp_bluedroid_status_t state = esp_bluedroid_get_status();
  if (state == ESP_BLUEDROID_STATUS_UNINITIALIZED && esp_bluedroid_init() != ESP_OK) return false;
  if (esp_bluedroid_get_status() != ESP_BLUEDROID_STATUS_ENABLED && esp_bluedroid_enable() != ESP_OK) return false;
  if (esp_bt_gap_register_callback(gapCallback) != ESP_OK ||
      esp_spp_register_callback(sppCallback) != ESP_OK ||
      esp_bt_gap_set_device_name(name_) != ESP_OK) return false;
  esp_bt_io_cap_t capability = ESP_BT_IO_CAP_NONE;
  if (esp_bt_gap_set_security_param(ESP_BT_SP_IOCAP_MODE, &capability, sizeof(capability)) != ESP_OK) return false;
  esp_spp_cfg_t config = BT_SPP_DEFAULT_CONFIG();
  config.mode = ESP_SPP_MODE_CB;
  if (esp_spp_enhanced_init(&config) != ESP_OK) return false;
  const uint32_t started = millis();
  while (millis() - started < 4000) {
    portENTER_CRITICAL(&mux_);
    const bool ready = ready_;
    const bool failed = failed_;
    portEXIT_CRITICAL(&mux_);
    if (failed) return false;
    if (ready) {
      poll();
      return true;
    }
    delay(10);
  }
  return false;
}

bool WearableBluetooth::connected() const {
  portENTER_CRITICAL(&mux_);
  const bool result = owner_ && !disconnectPending_;
  portEXIT_CRITICAL(&mux_);
  return result;
}

uint32_t WearableBluetooth::generation() const {
  portENTER_CRITICAL(&mux_);
  const uint32_t result = generation_;
  portEXIT_CRITICAL(&mux_);
  return result;
}

WearableBluetooth::Stats WearableBluetooth::stats() const {
  portENTER_CRITICAL(&mux_);
  const Stats result = stats_;
  portEXIT_CRITICAL(&mux_);
  return result;
}

void WearableBluetooth::advanceGenerationLocked() {
  if (++generation_ == 0) ++generation_;
}

void WearableBluetooth::resetRecordsLocked() {
  rxLength_ = 0;
  rxCarriageReturn_ = false;
  rxHead_ = 0;
  rxCount_ = 0;
  txLength_ = 0;
  txOffset_ = 0;
  txChunk_ = 0;
  txSession_ = 0;
  txInFlight_ = false;
  congested_ = false;
}

void WearableBluetooth::rejectInputLocked() {
  ++stats_.rejectedInput;
  disconnectPending_ = true;
  rxLength_ = 0;
  rxCount_ = 0;
}

bool WearableBluetooth::readLine(String& line, uint32_t& session) {
  char copy[MAX_RX_LINE + 1];
  size_t length = 0;
  portENTER_CRITICAL(&mux_);
  if (!owner_ || disconnectPending_ || !rxCount_) {
    portEXIT_CRITICAL(&mux_);
    return false;
  }
  length = rxSizes_[rxHead_];
  memcpy(copy, rxLines_[rxHead_], length + 1);
  session = rxSessions_[rxHead_];
  rxHead_ = (rxHead_ + 1) % RX_QUEUE_SIZE;
  --rxCount_;
  portEXIT_CRITICAL(&mux_);
  line = copy;
  return line.length() == length;
}

bool WearableBluetooth::sendLine(const String& line, uint32_t session) {
  const size_t length = line.length();
  if (!length || length > MAX_TX_LINE || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return false;
  portENTER_CRITICAL(&mux_);
  if (!owner_ || disconnectPending_ || retiring_ || txLength_ ||
      (session && generation_ != session)) {
    portEXIT_CRITICAL(&mux_);
    return false;
  }
  memcpy(txBuffer_, line.c_str(), length);
  txBuffer_[length] = '\n';
  txLength_ = length + 1;
  txOffset_ = 0;
  txSession_ = generation_;
  txProgressAt_ = millis();
  portEXIT_CRITICAL(&mux_);
  return true;
}

void WearableBluetooth::disconnect(uint32_t session) {
  portENTER_CRITICAL(&mux_);
  if (owner_ && (!session || generation_ == session)) disconnectPending_ = true;
  portEXIT_CRITICAL(&mux_);
}

void WearableBluetooth::finishOperation() {
  portENTER_CRITICAL(&mux_);
  if (operations_) --operations_;
  if (!operations_ && retiring_) {
    retiring_ = false;
    scanDirty_ = true;
  }
  portEXIT_CRITICAL(&mux_);
}

void WearableBluetooth::poll() {
  uint32_t disconnectHandle = 0;
  uint32_t disconnectSession = 0;
  uint32_t writeHandle = 0;
  uint32_t writeSession = 0;
  size_t writeOffset = 0;
  size_t writeSize = 0;
  bool updateScan = false;
  bool available = false;
  const uint32_t now = millis();
  portENTER_CRITICAL(&mux_);
  if (owner_ && txLength_ && !disconnectPending_ && now - txProgressAt_ >= TX_TIMEOUT_MS) {
    ++stats_.txTimeouts;
    disconnectPending_ = true;
  }
  if (owner_ && disconnectPending_ && !disconnectIssued_) {
    disconnectIssued_ = true;
    disconnectHandle = owner_;
    disconnectSession = generation_;
    ++operations_;
  } else if (owner_ && !disconnectPending_ && txLength_ && !txInFlight_ && !congested_) {
    writeHandle = owner_;
    writeSession = generation_;
    writeOffset = txOffset_;
    writeSize = txLength_ - txOffset_;
    if (writeSize > TX_CHUNK_SIZE) writeSize = TX_CHUNK_SIZE;
    txChunk_ = writeSize;
    txInFlight_ = true;
    ++operations_;
  }
  if (ready_ && scanDirty_) {
    scanDirty_ = false;
    available = !owner_ && !retiring_;
    updateScan = true;
  }
  portEXIT_CRITICAL(&mux_);

  if (disconnectHandle) {
    const esp_err_t result = esp_spp_disconnect(disconnectHandle);
    if (result != ESP_OK) {
      portENTER_CRITICAL(&mux_);
      if (owner_ == disconnectHandle && generation_ == disconnectSession) ++stats_.txFailures;
      portEXIT_CRITICAL(&mux_);
      // Remain unavailable on failure: never admit another phone alongside an
      // owner whose disconnection has not been confirmed by the controller.
    }
    finishOperation();
  }
  if (writeHandle) {
    const esp_err_t result = esp_spp_write(writeHandle, writeSize, txBuffer_ + writeOffset);
    if (result != ESP_OK) {
      portENTER_CRITICAL(&mux_);
      if (owner_ == writeHandle && generation_ == writeSession) {
        ++stats_.txFailures;
        disconnectPending_ = true;
        txInFlight_ = false;
      }
      portEXIT_CRITICAL(&mux_);
    }
    finishOperation();
  }
  if (updateScan) {
    const esp_err_t result = esp_bt_gap_set_scan_mode(
        available ? ESP_BT_CONNECTABLE : ESP_BT_NON_CONNECTABLE,
        available ? ESP_BT_GENERAL_DISCOVERABLE : ESP_BT_NON_DISCOVERABLE);
    portENTER_CRITICAL(&mux_);
    // A callback may have changed ownership while the asynchronous command was
    // queued; poll again until the requested mode matches the current state.
    if (result != ESP_OK || available != (!owner_ && !retiring_)) scanDirty_ = true;
    portEXIT_CRITICAL(&mux_);
  }
}

void WearableBluetooth::gapCallback(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t* param) {
  if (instance_) instance_->onGap(event, param);
}

void WearableBluetooth::sppCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t* param) {
  if (instance_) instance_->onSpp(event, param);
}

void WearableBluetooth::onGap(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t* param) {
  if (event == ESP_BT_GAP_CFM_REQ_EVT) {
    portENTER_CRITICAL(&mux_);
    const bool accept = !retiring_ && (!owner_ || memcmp(ownerAddress_, param->cfm_req.bda, 6) == 0);
    portEXIT_CRITICAL(&mux_);
    // NoInputNoOutput SSP: Android may still show its own system dialog. App
    // authorization is a separate challenge/response before any sensor data.
    esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, accept);
  } else if (event == ESP_BT_GAP_PIN_REQ_EVT) {
    // No fixed legacy PIN or serial-terminal interaction is supported.
    esp_bt_gap_pin_reply(param->pin_req.bda, false, 0, nullptr);
  } else if (event == ESP_BT_GAP_KEY_REQ_EVT) {
    esp_bt_gap_ssp_passkey_reply(param->key_req.bda, false, 0);
  }
}

void WearableBluetooth::onSpp(esp_spp_cb_event_t event, esp_spp_cb_param_t* param) {
  if (event == ESP_SPP_INIT_EVT) {
    if (param->init.status != ESP_SPP_SUCCESS ||
        esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE | ESP_SPP_SEC_ENCRYPT,
                          ESP_SPP_ROLE_SLAVE, 0, name_) != ESP_OK) {
      portENTER_CRITICAL(&mux_);
      failed_ = true;
      portEXIT_CRITICAL(&mux_);
    }
    return;
  }
  if (event == ESP_SPP_START_EVT) {
    portENTER_CRITICAL(&mux_);
    ready_ = param->start.status == ESP_SPP_SUCCESS;
    failed_ = !ready_;
    scanDirty_ = ready_;
    portEXIT_CRITICAL(&mux_);
    return;
  }
  if (event == ESP_SPP_SRV_OPEN_EVT && param->srv_open.status == ESP_SPP_SUCCESS) {
    bool reject = false;
    portENTER_CRITICAL(&mux_);
    if (owner_ || retiring_ || operations_ || !ready_) {
      ++stats_.rejectedConnections;
      reject = true;
    } else {
      owner_ = param->srv_open.handle;
      advanceGenerationLocked();
      memcpy(ownerAddress_, param->srv_open.rem_bda, 6);
      resetRecordsLocked();
      disconnectPending_ = false;
      disconnectIssued_ = false;
      ++stats_.acceptedConnections;
      scanDirty_ = true;
    }
    portEXIT_CRITICAL(&mux_);
    if (reject) esp_spp_disconnect(param->srv_open.handle);
    else esp_bt_gap_set_scan_mode(ESP_BT_NON_CONNECTABLE, ESP_BT_NON_DISCOVERABLE);
    return;
  }
  if (event == ESP_SPP_CLOSE_EVT) {
    portENTER_CRITICAL(&mux_);
    if (owner_ && param->close.handle == owner_ &&
        (param->close.status == ESP_SPP_SUCCESS || param->close.async)) {
      owner_ = 0;
      advanceGenerationLocked();
      memset(ownerAddress_, 0, sizeof(ownerAddress_));
      resetRecordsLocked();
      disconnectPending_ = false;
      disconnectIssued_ = false;
      retiring_ = operations_ != 0;
      scanDirty_ = true;
    }
    portEXIT_CRITICAL(&mux_);
    return;
  }
  if (event == ESP_SPP_DATA_IND_EVT) {
    portENTER_CRITICAL(&mux_);
    if (owner_ && param->data_ind.handle == owner_ && !disconnectPending_) {
      for (size_t i = 0; i < param->data_ind.len && !disconnectPending_; ++i) {
        const uint8_t value = param->data_ind.data[i];
        if (value == '\n') {
          if (rxLength_) {
            if (rxCount_ == RX_QUEUE_SIZE) {
              rejectInputLocked();
              break;
            }
            const size_t tail = (rxHead_ + rxCount_) % RX_QUEUE_SIZE;
            memcpy(rxLines_[tail], rxPartial_, rxLength_);
            rxLines_[tail][rxLength_] = 0;
            rxSizes_[tail] = rxLength_;
            rxSessions_[tail] = generation_;
            ++rxCount_;
          }
          rxLength_ = 0;
          rxCarriageReturn_ = false;
        } else if (value == '\r' && !rxCarriageReturn_) {
          rxCarriageReturn_ = true;
        } else if (rxCarriageReturn_ || value < 0x20 || value > 0x7e || rxLength_ >= MAX_RX_LINE) {
          rejectInputLocked();
        } else {
          rxPartial_[rxLength_++] = value;
        }
      }
    }
    portEXIT_CRITICAL(&mux_);
    return;
  }
  if (event == ESP_SPP_WRITE_EVT) {
    portENTER_CRITICAL(&mux_);
    if (owner_ && param->write.handle == owner_ && txInFlight_ && txSession_ == generation_) {
      txInFlight_ = false;
      if (param->write.status != ESP_SPP_SUCCESS || param->write.len < 0 ||
          static_cast<size_t>(param->write.len) != txChunk_) {
        ++stats_.txFailures;
        disconnectPending_ = true;
      } else {
        txOffset_ += txChunk_;
        txProgressAt_ = millis();
        congested_ = param->write.cong;
        if (txOffset_ == txLength_) {
          txLength_ = 0;
          txOffset_ = 0;
        }
      }
    }
    portEXIT_CRITICAL(&mux_);
    return;
  }
  if (event == ESP_SPP_CONG_EVT) {
    portENTER_CRITICAL(&mux_);
    if (owner_ && param->cong.handle == owner_) {
      congested_ = param->cong.cong;
      if (!congested_) txProgressAt_ = millis();
    }
    portEXIT_CRITICAL(&mux_);
  }
}
