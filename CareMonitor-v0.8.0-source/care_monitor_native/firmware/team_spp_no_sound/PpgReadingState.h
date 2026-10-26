#ifndef CARE_MONITOR_PPG_READING_STATE_H
#define CARE_MONITOR_PPG_READING_STATE_H

#include <stdint.h>

// Validity describes the last completed 4-second window, not the current
// individual FIFO sample. Calculations repeat every 25 effective samples.
class PpgReadingState {
 public:
  static constexpr uint16_t kWindowSamples = 100;
  static constexpr uint16_t kStepSamples = 25;
  static constexpr uint32_t kSampleTimeoutMs = 500;
  static constexpr uint32_t kResultTimeoutMs = 2500;

  enum class Status { NotFound, Waiting, NoFinger, WarmingUp, Ok, Partial,
                      PoorSignal, ReadError };

  constexpr PpgReadingState() = default;

  constexpr void initialize(bool online) {
    *this = PpgReadingState();
    online_ = online;
    status_ = online ? Status::Waiting : Status::NotFound;
  }

  constexpr void expire(uint32_t now) {
    if (!online_) return;
    if (hasSample_ && (uint32_t)(now - lastSampleAt_) >= kSampleTimeoutMs) {
      clearWindow();
      status_ = Status::ReadError;
    } else if (hasCalculation_ &&
               (uint32_t)(now - lastCalculationAt_) >= kResultTimeoutMs) {
      // Keep collecting the current window, but never hold an old result
      // indefinitely if the calculation cadence is interrupted.
      hasCalculation_ = false;
      hrValid_ = false;
      spo2Valid_ = false;
      status_ = finger_ ? Status::WarmingUp : Status::NoFinger;
    }
  }

  constexpr void noteSampleLoss(uint32_t dropped) {
    if (!online_) return;
    ++lossEvents_;
    droppedSamples_ += dropped;
    clearWindow();
    status_ = Status::ReadError;
  }

  constexpr void sample(uint32_t now, bool contact) {
    if (!online_) return;
    expire(now);
    hasSample_ = true;
    lastSampleAt_ = now;
    ++samplesReceived_;
    if (!contact) {
      clearWindow();
      status_ = Status::NoFinger;
      return;
    }
    finger_ = true;
    if (windowSamples_ < kWindowSamples) ++windowSamples_;
    status_ = resultStatus();
  }

  constexpr void calculated(uint32_t now, bool heartRateValid,
                            bool oxygenValid) {
    if (!finger_ || windowSamples_ != kWindowSamples) return;
    hasCalculation_ = true;
    lastCalculationAt_ = now;
    hrValid_ = heartRateValid;
    spo2Valid_ = oxygenValid;
    windowSamples_ = kWindowSamples - kStepSamples;
    status_ = resultStatus();
    ++calculations_;
  }

  constexpr bool ready() const { return windowSamples_ == kWindowSamples; }
  constexpr bool fingerDetected() const { return finger_; }
  constexpr bool heartRateValid() const { return hrValid_; }
  constexpr bool oxygenValid() const { return spo2Valid_; }
  constexpr bool hasSample() const { return hasSample_; }
  constexpr bool hasCalculation() const { return hasCalculation_; }
  constexpr uint16_t windowSamples() const { return windowSamples_; }
  constexpr uint32_t sampleAge(uint32_t now) const {
    return (uint32_t)(now - lastSampleAt_);
  }
  constexpr uint32_t resultAge(uint32_t now) const {
    return (uint32_t)(now - lastCalculationAt_);
  }
  constexpr uint32_t sampleCount() const { return samplesReceived_; }
  constexpr uint32_t calculationCount() const { return calculations_; }
  constexpr uint32_t lossEvents() const { return lossEvents_; }
  constexpr uint32_t droppedSamples() const { return droppedSamples_; }
  constexpr Status status() const { return status_; }

  const char* statusName() const {
    switch (status_) {
      case Status::NotFound: return "not_found";
      case Status::Waiting: return "waiting";
      case Status::NoFinger: return "no_finger";
      case Status::WarmingUp: return "warming_up";
      case Status::Ok: return "ok";
      case Status::Partial: return "partial";
      case Status::PoorSignal: return "poor_signal";
      case Status::ReadError: return "read_error";
    }
    return "read_error";
  }

 private:
  constexpr void clearWindow() {
    windowSamples_ = 0;
    finger_ = false;
    hrValid_ = false;
    spo2Valid_ = false;
    hasCalculation_ = false;
  }

  constexpr Status resultStatus() const {
    if (!hasCalculation_) return Status::WarmingUp;
    if (hrValid_ && spo2Valid_) return Status::Ok;
    if (hrValid_ || spo2Valid_) return Status::Partial;
    return Status::PoorSignal;
  }

  bool online_ = false;
  bool finger_ = false;
  bool hrValid_ = false;
  bool spo2Valid_ = false;
  bool hasSample_ = false;
  bool hasCalculation_ = false;
  uint16_t windowSamples_ = 0;
  uint32_t lastSampleAt_ = 0;
  uint32_t lastCalculationAt_ = 0;
  uint32_t samplesReceived_ = 0;
  uint32_t calculations_ = 0;
  uint32_t lossEvents_ = 0;
  uint32_t droppedSamples_ = 0;
  Status status_ = Status::NotFound;
};

#endif
