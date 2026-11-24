#pragma once

#include <cstdint>

// Pure state machine: no BLE/LVGL calls. All times are monotonic milliseconds.
// Departure timeout starts with the first observed loss, not after the
// 30-second display pause. Arrival always retains its previous 120s policy.
class CoffeePausePolicy {
 public:
  enum class State { Awake, Paused, PowerOffAfterLoss, PowerOffAfterArrival, PowerOffAfterClosing };
  static constexpr std::uint64_t kPauseAfterMs = 30'000;
  static constexpr std::uint64_t kArrivalOffAfterMs = 120'000;
  static constexpr std::uint8_t kDefaultMinutes = 10;
  static constexpr std::uint8_t kMaximumMinutes = 30;

  State update(std::uint64_t now_ms, bool ever_connected, bool connected,
               bool arrived, std::uint8_t minutes,
               bool voluntary_close = false) noexcept {
    if (!ever_connected) return State::Awake;
    if (connected) {
      if (!was_connected_) {
        loss_timing_ = false;
        if (!arrived) arrival_timing_ = false;
      }
      was_connected_ = true;
      if (arrived && !arrival_timing_) {
        arrival_timing_ = true;
        arrival_since_ = now_ms;
      } else if (!arrived) {
        arrival_timing_ = false;
      }
      if (arrival_timing_ &&
          now_ms - arrival_since_ >= kArrivalOffAfterMs) {
        return State::PowerOffAfterArrival;
      }
      return State::Awake;
    }
    if (!loss_timing_) {
      loss_since_ = now_ms;
      loss_timing_ = true;
    }
    was_connected_ = false;
    // Do not replace the 2-minute arrival countdown with a coffee pause
    // merely because the phone disconnects after reaching destination.
    if (arrival_timing_) {
      return now_ms - arrival_since_ >= kArrivalOffAfterMs
                 ? State::PowerOffAfterArrival : State::Awake;
    }
    if (voluntary_close) {
      return now_ms - loss_since_ >= kArrivalOffAfterMs
                 ? State::PowerOffAfterClosing : State::Awake;
    }
    const std::uint8_t bounded = minutes >= 1 && minutes <= kMaximumMinutes
                                     ? minutes : kDefaultMinutes;
    const auto elapsed = now_ms - loss_since_;
    if (elapsed >= static_cast<std::uint64_t>(bounded) * 60'000U)
      return State::PowerOffAfterLoss;
    return elapsed >= kPauseAfterMs ? State::Paused : State::Awake;
  }

 private:
  bool was_connected_ = false;
  bool loss_timing_ = false;
  bool arrival_timing_ = false;
  std::uint64_t loss_since_ = 0;
  std::uint64_t arrival_since_ = 0;
};
