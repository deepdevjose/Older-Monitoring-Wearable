#include "../team_spp_no_sound/PpgReadingState.h"

using State = PpgReadingState;

constexpr State fullWindow(uint32_t start = 0) {
  State state;
  state.initialize(true);
  for (uint32_t i = 1; i <= 100; ++i) state.sample(start + i * 40, true);
  return state;
}

constexpr bool initialWindowRequires100Samples() {
  State state;
  state.initialize(true);
  for (uint32_t i = 1; i < 100; ++i) {
    state.sample(i * 40, true);
    if (state.ready() || state.heartRateValid() || state.oxygenValid()) return false;
  }
  state.sample(4000, true);
  return state.ready() && state.status() == State::Status::WarmingUp;
}

constexpr bool completedResultPersistsBetweenCalculations() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  for (uint32_t i = 1; i < 25; ++i) {
    state.sample(4000 + i * 40, true);
    if (state.status() != State::Status::Ok || !state.heartRateValid() ||
        !state.oxygenValid() || state.ready()) return false;
  }
  state.sample(5000, true);
  return state.ready() && state.status() == State::Status::Ok &&
      state.calculationCount() == 1;
}

constexpr bool individualChannelValidity() {
  State state = fullWindow();
  state.calculated(4000, true, false);
  state.sample(4040, true);
  if (state.status() != State::Status::Partial || !state.heartRateValid() ||
      state.oxygenValid()) return false;
  for (uint32_t i = 2; i <= 25; ++i) state.sample(4000 + i * 40, true);
  state.calculated(5000, false, true);
  return state.status() == State::Status::Partial && !state.heartRateValid() &&
      state.oxygenValid();
}

constexpr bool contactLossRestartsWholeWindow() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  state.sample(4040, false);
  if (state.status() != State::Status::NoFinger || state.heartRateValid() ||
      state.oxygenValid() || state.windowSamples() || state.hasCalculation()) return false;
  for (uint32_t i = 1; i < 100; ++i) state.sample(4040 + i * 40, true);
  return !state.ready() && state.status() == State::Status::WarmingUp;
}

constexpr bool noSampleTimeoutAndRecovery() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  state.expire(4499);
  if (!state.heartRateValid()) return false;
  state.expire(4500);
  if (state.status() != State::Status::ReadError || state.heartRateValid() ||
      state.oxygenValid() || state.fingerDetected() || state.windowSamples()) return false;
  state.sample(4501, true);
  return state.status() == State::Status::WarmingUp && state.windowSamples() == 1;
}

constexpr bool omittedCalculationExpires() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  // Deliberately omit subsequent calculations while samples keep arriving.
  for (uint32_t i = 1; i <= 63; ++i) state.sample(4000 + i * 40, true);
  return !state.heartRateValid() && !state.oxygenValid() &&
      state.status() == State::Status::WarmingUp;
}

constexpr bool droppedSamplesRejectOldWindow() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  state.noteSampleLoss(5);
  if (state.heartRateValid() || state.oxygenValid() || state.windowSamples() ||
      state.status() != State::Status::ReadError) return false;
  state.sample(4040, true);
  return state.lossEvents() == 1 && state.droppedSamples() == 5 &&
      state.windowSamples() == 1 && state.status() == State::Status::WarmingUp;
}

constexpr bool newestPoorWindowDoesNotHoldGoodValues() {
  State state = fullWindow();
  state.calculated(4000, true, true);
  for (uint32_t i = 1; i <= 25; ++i) state.sample(4000 + i * 40, true);
  state.calculated(5000, false, false);
  return state.status() == State::Status::PoorSignal && !state.heartRateValid() &&
      !state.oxygenValid() && state.calculationCount() == 2;
}

constexpr bool millisWrapHandled() {
  State state = fullWindow(0xfffff000u);
  const uint32_t completed = 0xfffff000u + 4000u;
  state.calculated(completed, true, true);
  state.sample(completed + 40u, true);
  state.expire(completed + 499u);
  if (!state.heartRateValid()) return false;
  state.expire(completed + 540u);
  return !state.heartRateValid() && state.status() == State::Status::ReadError;
}

constexpr bool disconnectedCannotProduceResult() {
  State state;
  state.initialize(false);
  state.sample(40, true);
  state.noteSampleLoss(4);
  state.expire(5000);
  state.calculated(5000, true, true);
  return state.status() == State::Status::NotFound && !state.heartRateValid() &&
      !state.oxygenValid();
}

static_assert(initialWindowRequires100Samples(), "must collect full initial window");
static_assert(completedResultPersistsBetweenCalculations(), "result status must persist");
static_assert(individualChannelValidity(), "partial results must remain independent");
static_assert(contactLossRestartsWholeWindow(), "contact loss must discard prior window");
static_assert(noSampleTimeoutAndRecovery(), "stalled readings must clear and restart");
static_assert(omittedCalculationExpires(), "result age must be bounded");
static_assert(droppedSamplesRejectOldWindow(), "sample loss must discard incomplete history");
static_assert(newestPoorWindowDoesNotHoldGoodValues(), "invalid current window must clear result");
static_assert(millisWrapHandled(), "expiry must support unsigned millis rollover");
static_assert(disconnectedCannotProduceResult(), "offline device must stay invalid");
