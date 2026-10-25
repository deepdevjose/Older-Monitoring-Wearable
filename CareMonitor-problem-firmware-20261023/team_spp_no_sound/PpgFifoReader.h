#ifndef CARE_MONITOR_PPG_FIFO_READER_H
#define CARE_MONITOR_PPG_FIFO_READER_H

#include <stdint.h>

// MAX30102 RED+IR mode: 32 hardware FIFO samples, 6 bytes per sample.
// Bus::read() must check ACK and the exact returned byte count. Bus::write()
// must check ACK. Failed FIFO transfers are retried only after restoring the
// pre-read pointer, because the pointer can advance after the first byte.
struct PpgFifoSample {
  uint32_t red = 0;
  uint32_t ir = 0;
};

struct PpgFifoBatch {
  PpgFifoSample samples[32] = {};
  uint8_t count = 0;
  bool gapBefore = false;
  bool gapAfter = false;
  uint8_t confirmedLostMinimum = 0;
};

struct PpgFifoDiagnostics {
  uint32_t pointerReadErrors = 0;
  uint32_t pointerUnusedBitsObservations = 0;
  uint32_t sampleReadErrors = 0;
  uint32_t adcUnusedBitsObservations = 0;
  uint32_t restoreAttempts = 0;
  uint32_t restoreFailures = 0;
  uint32_t restoreSuccesses = 0;
  uint32_t unrecoverableReadEvents = 0;
  uint32_t hardwareOverflowEvents = 0;
  uint32_t confirmedLostMinimum = 0;
  uint32_t overflowRecoveryAttempts = 0;
  uint32_t overflowRecoveryFailures = 0;
  uint32_t overflowRecoverySuccesses = 0;
  uint32_t overflowDiscardedSamplesMinimum = 0;
};

class PpgFifoReader {
 public:
  template <typename Bus>
  constexpr PpgFifoBatch poll(Bus& bus) {
    PpgFifoBatch result;
    if (restorePending_) {
      ++diagnostics_.restoreAttempts;
      if (!bus.write(0x06, restorePointer_)) {
        ++diagnostics_.restoreFailures;
        return result;  // The first failure already invalidated the window.
      }
      ++diagnostics_.restoreSuccesses;
      restorePending_ = false;
    }

    uint8_t pointers[3] = {};
    if (!bus.read(0x04, pointers, 3)) {
      ++diagnostics_.pointerReadErrors;
      return result;  // No FIFO data was consumed: retry without a fake gap.
    }
    if (((pointers[0] | pointers[1] | pointers[2]) & 0xe0u) != 0) {
      ++diagnostics_.pointerUnusedBitsObservations;
    }

    // Decode documented five-bit fields. Unused upper bits are not specified
    // as an error condition and must not manufacture a backlog or a gap.
    const uint8_t writePointer = pointers[0] & 0x1fu;
    const uint8_t overflow = pointers[1] & 0x1fu;
    uint8_t readPointer = pointers[2] & 0x1fu;
    uint8_t available = (writePointer - readPointer) & 0x1fu;
    if (overflow == 0) overflowObserved_ = false;
    if (overflow != 0) {
      if (!overflowObserved_) {
        overflowObserved_ = true;
        result.gapBefore = true;
        result.confirmedLostMinimum = overflow;
        ++diagnostics_.hardwareOverflowEvents;
        diagnostics_.confirmedLostMinimum += overflow;
      }
      // Do not join overflow-era queued samples to later fresh samples. Only
      // verified overflow discards the queue; ordinary backlogs are retained.
      ++diagnostics_.overflowRecoveryAttempts;
      if (!bus.write(0x06, writePointer)) {
        ++diagnostics_.overflowRecoveryFailures;
        return result;
      }
      // Equal pointers can mean either full or an already discarded queue
      // while acknowledging an earlier overflow. Report only a known minimum.
      diagnostics_.overflowDiscardedSamplesMinimum += available;
      // This counter is R/W. Clear its acknowledged overflow indication so
      // an empty, resynchronized FIFO can accept a fresh sample next pass.
      if (!bus.write(0x05, 0)) {
        ++diagnostics_.overflowRecoveryFailures;
        return result;
      }
      ++diagnostics_.overflowRecoverySuccesses;
      overflowObserved_ = false;
      return result;
    }

    for (uint8_t i = 0; i < available; ++i) {
      uint8_t bytes[6] = {};
      if (!bus.read(0x07, bytes, 6)) {
        ++diagnostics_.sampleReadErrors;
        recover(bus, readPointer, result);
        return result;
      }
      if (((bytes[0] | bytes[3]) & 0xfcu) != 0) {
        ++diagnostics_.adcUnusedBitsObservations;
      }
      // The ADC field is 18 bits; bits18..23 are not used. Decode as the
      // manufacturer's reference driver does, without rejecting those bits.
      result.samples[result.count].red =
          (((uint32_t)bytes[0] << 16) | ((uint32_t)bytes[1] << 8) | bytes[2]) & 0x3ffffu;
      result.samples[result.count].ir =
          (((uint32_t)bytes[3] << 16) | ((uint32_t)bytes[4] << 8) | bytes[5]) & 0x3ffffu;
      ++result.count;
      readPointer = (readPointer + 1) & 0x1fu;
      // A completed pop clears the hardware overflow counter.
      overflowObserved_ = false;
    }
    return result;
  }

  constexpr const PpgFifoDiagnostics& diagnostics() const { return diagnostics_; }

 private:
  template <typename Bus>
  constexpr void recover(Bus& bus, uint8_t pointer, PpgFifoBatch& result) {
    ++diagnostics_.restoreAttempts;
    if (bus.write(0x06, pointer)) {
      ++diagnostics_.restoreSuccesses;
      return;  // Retry the same sample next loop; no sample has been lost.
    }
    ++diagnostics_.restoreFailures;
    ++diagnostics_.unrecoverableReadEvents;
    restorePending_ = true;
    restorePointer_ = pointer;
    result.gapAfter = true;
  }

  PpgFifoDiagnostics diagnostics_;
  bool restorePending_ = false;
  uint8_t restorePointer_ = 0;
  bool overflowObserved_ = false;
};

#endif
