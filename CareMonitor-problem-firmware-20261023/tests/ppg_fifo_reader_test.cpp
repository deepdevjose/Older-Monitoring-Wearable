#include "../team_spp_no_sound/PpgFifoReader.h"

struct FakeBus {
  uint8_t writer = 1;
  uint8_t reader = 0;
  uint8_t overflow = 0;
  uint8_t unusedWriteBits = 0;
  uint8_t unusedReadBits = 0;
  uint8_t unusedOverflowBits = 0;
  bool pointerFailure = false;
  bool overflowClearFailure = false;
  uint8_t restoreFailuresLeft = 0;
  int failSampleAt = -1;
  int unusedBitsSampleAt = -1;
  uint32_t sampleReads = 0;
  uint32_t pointerReads = 0;
  uint32_t writes = 0;
  uint8_t lastWriteReg = 0;
  uint8_t lastWriteValue = 0;

  constexpr bool read(uint8_t reg, uint8_t* data, uint8_t length) {
    if (reg == 0x04 && length == 3) {
      ++pointerReads;
      if (pointerFailure) return false;
      data[0] = writer | unusedWriteBits;
      data[1] = overflow | unusedOverflowBits;
      data[2] = reader | unusedReadBits;
      return true;
    }
    if (reg != 0x07 || length != 6) return false;
    ++sampleReads;
    const uint8_t before = reader;
    // Simulate the documented worst case: the first byte advances RD_PTR
    // even when the remainder of this transaction is short/corrupt.
    reader = (reader + 1) & 0x1fu;
    overflow = 0;
    if (static_cast<int>(sampleReads) == failSampleAt) return false;
    data[0] = 1;
    data[1] = 0x34;
    data[2] = before;
    data[3] = 2;
    data[4] = 0x78;
    data[5] = before;
    if (static_cast<int>(sampleReads) == unusedBitsSampleAt) {
      data[0] |= 0x80;
      data[3] |= 0x40;
    }
    return true;
  }

  constexpr bool write(uint8_t reg, uint8_t value) {
    ++writes;
    lastWriteReg = reg;
    lastWriteValue = value;
    if (reg == 0x06) {
      if (restoreFailuresLeft != 0) {
        --restoreFailuresLeft;
        return false;
      }
      reader = value;
      return true;
    }
    if (reg == 0x05) {
      if (overflowClearFailure) return false;
      overflow = value;
      return true;
    }
    return false;
  }
};

constexpr bool emptyDoesNotReadData() {
  FakeBus bus;
  bus.writer = 0;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 0 && !batch.gapBefore && !batch.gapAfter &&
      bus.sampleReads == 0 && bus.writes == 0;
}

constexpr bool completeSampleDecoded() {
  FakeBus bus;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 1 && batch.samples[0].red == 0x013400u &&
      batch.samples[0].ir == 0x027800u && bus.reader == 1 && bus.writes == 0;
}

constexpr bool ordinaryBacklogRetained() {
  FakeBus bus;
  bus.writer = 7;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 7 && bus.sampleReads == 7 && bus.writes == 0 &&
      !batch.gapBefore && !batch.gapAfter;
}

constexpr bool pointerWrapHandled() {
  FakeBus bus;
  bus.reader = 30;
  bus.writer = 1;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 3 && bus.reader == 1 &&
      batch.samples[0].red == 0x01341eu && batch.samples[2].red == 0x013400u;
}

constexpr bool boundedMaximumBacklog() {
  FakeBus bus;
  bus.writer = 31;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 31 && bus.sampleReads == 31 && bus.reader == 31 &&
      !batch.gapBefore && !batch.gapAfter;
}

constexpr bool failedPointerProbeDoesNotInventGap() {
  FakeBus bus;
  bus.pointerFailure = true;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 0 && bus.sampleReads == 0 && bus.writes == 0 &&
      !batch.gapBefore && !batch.gapAfter && fifo.diagnostics().pointerReadErrors == 1;
}

constexpr bool unusedWritePointerBitsAreMasked() {
  FakeBus bus;
  bus.unusedWriteBits = 0x40;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 1 && bus.sampleReads == 1 && bus.writes == 0 &&
      !batch.gapBefore && !batch.gapAfter && fifo.diagnostics().pointerUnusedBitsObservations == 1;
}

constexpr bool unusedOverflowBitsDoNotInventOverflow() {
  FakeBus bus;
  bus.unusedOverflowBits = 0x80;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 1 && bus.sampleReads == 1 && bus.writes == 0 &&
      !batch.gapBefore && fifo.diagnostics().hardwareOverflowEvents == 0 &&
      fifo.diagnostics().pointerUnusedBitsObservations == 1;
}

constexpr bool unusedReadPointerBitsAreMasked() {
  FakeBus bus;
  bus.unusedReadBits = 0x20;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 1 && bus.sampleReads == 1 && bus.writes == 0 &&
      batch.samples[0].red == 0x013400u && fifo.diagnostics().pointerUnusedBitsObservations == 1;
}

constexpr bool shortReadRestoresExactPointer() {
  FakeBus bus;
  bus.reader = 30;
  bus.writer = 31;
  bus.failSampleAt = 1;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 0 && !batch.gapBefore && !batch.gapAfter &&
      bus.reader == 30 && bus.lastWriteReg == 0x06 && bus.lastWriteValue == 30 &&
      fifo.diagnostics().sampleReadErrors == 1 && fifo.diagnostics().restoreSuccesses == 1;
}

constexpr bool successfulRetryIsNotSkipped() {
  FakeBus bus;
  bus.failSampleAt = 1;
  PpgFifoReader fifo;
  const auto failed = fifo.poll(bus);
  const auto recovered = fifo.poll(bus);
  return failed.count == 0 && recovered.count == 1 &&
      recovered.samples[0].red == 0x013400u && bus.reader == 1;
}

constexpr bool unusedAdcBitsAreMaskedWithoutRetry() {
  FakeBus bus;
  bus.unusedBitsSampleAt = 1;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 1 && batch.samples[0].red == 0x013400u &&
      batch.samples[0].ir == 0x027800u && !batch.gapBefore && !batch.gapAfter &&
      bus.writes == 0 && fifo.diagnostics().adcUnusedBitsObservations == 1;
}

constexpr bool completeSamplesBeforeRetryArePreserved() {
  FakeBus bus;
  bus.writer = 3;
  bus.failSampleAt = 2;
  PpgFifoReader fifo;
  const auto first = fifo.poll(bus);
  const auto second = fifo.poll(bus);
  return first.count == 1 && !first.gapAfter && second.count == 2 &&
      first.samples[0].red == 0x013400u && second.samples[0].red == 0x013401u;
}

constexpr bool failedRestoreInvalidatesAfterCompleteSamples() {
  FakeBus bus;
  bus.writer = 3;
  bus.failSampleAt = 2;
  bus.restoreFailuresLeft = 1;
  PpgFifoReader fifo;
  const auto first = fifo.poll(bus);
  const auto second = fifo.poll(bus);
  return first.count == 1 && first.gapAfter && !first.gapBefore &&
      second.count == 2 && !second.gapAfter &&
      fifo.diagnostics().unrecoverableReadEvents == 1 &&
      fifo.diagnostics().restoreFailures == 1 && fifo.diagnostics().restoreSuccesses == 1;
}

constexpr bool pendingRestoreRetriesRemainBounded() {
  FakeBus bus;
  bus.failSampleAt = 1;
  bus.restoreFailuresLeft = 2;
  PpgFifoReader fifo;
  const auto first = fifo.poll(bus);
  const auto second = fifo.poll(bus);
  const auto third = fifo.poll(bus);
  return first.gapAfter && second.count == 0 && !second.gapAfter &&
      third.count == 1 && bus.sampleReads == 2 && bus.writes == 3;
}

constexpr bool verifiedOverflowDiscardsAmbiguousQueue() {
  FakeBus bus;
  bus.writer = 4;
  bus.overflow = 2;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.gapBefore && !batch.gapAfter && batch.count == 0 &&
      batch.confirmedLostMinimum == 2 && bus.sampleReads == 0 && bus.reader == 4 &&
      bus.overflow == 0 && bus.writes == 2 &&
      fifo.diagnostics().overflowDiscardedSamplesMinimum == 4;
}

constexpr bool fullOverflowDoesNotReadUnboundedData() {
  FakeBus bus;
  bus.writer = bus.reader = 0;
  bus.overflow = 31;
  PpgFifoReader fifo;
  const auto batch = fifo.poll(bus);
  return batch.count == 0 && batch.gapBefore && batch.confirmedLostMinimum == 31 &&
      bus.sampleReads == 0 && bus.writes == 2 && bus.overflow == 0;
}

constexpr bool overflowPointerRecoveryFailureDoesNotReadQueue() {
  FakeBus bus;
  bus.writer = 4;
  bus.overflow = 3;
  bus.restoreFailuresLeft = 1;
  PpgFifoReader fifo;
  const auto first = fifo.poll(bus);
  const auto second = fifo.poll(bus);
  return first.gapBefore && first.count == 0 && second.count == 0 &&
      bus.sampleReads == 0 && bus.reader == 4 && bus.overflow == 0 &&
      fifo.diagnostics().hardwareOverflowEvents == 1 &&
      fifo.diagnostics().overflowRecoveryFailures == 1;
}

constexpr bool overflowAckFailureRetainsInvalidationAndRetries() {
  FakeBus bus;
  bus.writer = 4;
  bus.overflow = 3;
  bus.overflowClearFailure = true;
  PpgFifoReader fifo;
  const auto first = fifo.poll(bus);
  bus.overflowClearFailure = false;
  const auto second = fifo.poll(bus);
  return first.gapBefore && first.count == 0 && second.count == 0 &&
      bus.sampleReads == 0 && bus.reader == 4 && bus.overflow == 0 &&
      fifo.diagnostics().hardwareOverflowEvents == 1 &&
      fifo.diagnostics().overflowDiscardedSamplesMinimum == 4;
}

constexpr bool freshSamplesAfterOverflowStartNewWindow() {
  FakeBus bus;
  bus.writer = 4;
  bus.overflow = 1;
  PpgFifoReader fifo;
  const auto old = fifo.poll(bus);
  bus.writer = 5;
  const auto fresh = fifo.poll(bus);
  return old.count == 0 && old.gapBefore && fresh.count == 1 &&
      fresh.samples[0].red == 0x013404u && !fresh.gapBefore && !fresh.gapAfter;
}

static_assert(emptyDoesNotReadData(), "empty FIFO must not synthesize samples");
static_assert(completeSampleDecoded(), "complete RED/IR record must decode exactly");
static_assert(ordinaryBacklogRetained(), "small backlog must not reset the window");
static_assert(pointerWrapHandled(), "five-bit pointer wrap must be handled");
static_assert(boundedMaximumBacklog(), "at most31 ordinary samples per pass");
static_assert(failedPointerProbeDoesNotInventGap(), "failed probes consume no data");
static_assert(unusedWritePointerBitsAreMasked(), "unused write-pointer bits must not invent backlog");
static_assert(unusedOverflowBitsDoNotInventOverflow(), "unused overflow bits must not invent an overflow");
static_assert(unusedReadPointerBitsAreMasked(), "unused read-pointer bits must not invent backlog");
static_assert(shortReadRestoresExactPointer(), "partial record must restore its original pointer");
static_assert(successfulRetryIsNotSkipped(), "retried sample must not be skipped");
static_assert(unusedAdcBitsAreMaskedWithoutRetry(), "unused ADC bits must not reject a complete sample");
static_assert(completeSamplesBeforeRetryArePreserved(), "complete preceding samples remain ordered");
static_assert(failedRestoreInvalidatesAfterCompleteSamples(), "unknown gap occurs after preceding data");
static_assert(pendingRestoreRetriesRemainBounded(), "failed restore must be bounded per pass");
static_assert(verifiedOverflowDiscardsAmbiguousQueue(), "overflow-era queue must be discarded");
static_assert(fullOverflowDoesNotReadUnboundedData(), "full FIFO recovery must remain bounded");
static_assert(overflowPointerRecoveryFailureDoesNotReadQueue(), "failed overflow sync must not read data");
static_assert(overflowAckFailureRetainsInvalidationAndRetries(), "overflow acknowledgement must be retried");
static_assert(freshSamplesAfterOverflowStartNewWindow(), "only fresh post-overflow data starts the next window");
