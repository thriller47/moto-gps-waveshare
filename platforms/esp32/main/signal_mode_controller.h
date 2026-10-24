#pragma once

// Experimental SIGNAL radio/navigation arbitration.
// This file deliberately has no ESP-IDF, LVGL, Wi-Fi, BLE or audio dependencies,
// so the transition policy can be exercised on a host before touching hardware.
namespace moto::signal {

enum class Mode { Navigation, Radio };
enum class RequestResult {
  Accepted,
  AlreadyActive,
  NavigationInProgress,
};

// Navigation is the safety-critical default. The radio may be opened only
// when there is no active route, and a navigation start always preempts radio.
// Connectivity (BLE/Wi-Fi) is owned by the mode adapters, not this policy.
class ModeController {
 public:
  Mode mode() const noexcept { return mode_; }
  bool navigation_active() const noexcept { return navigation_active_; }
  bool radio_permitted() const noexcept { return !navigation_active_; }

  RequestResult request_radio() noexcept {
    if (navigation_active_) return RequestResult::NavigationInProgress;
    if (mode_ == Mode::Radio) return RequestResult::AlreadyActive;
    mode_ = Mode::Radio;
    return RequestResult::Accepted;
  }

  RequestResult request_navigation() noexcept {
    if (mode_ == Mode::Navigation) return RequestResult::AlreadyActive;
    mode_ = Mode::Navigation;
    return RequestResult::Accepted;
  }

  // Called when the phone starts/stops a real navigation session.
  // When a route starts, immediately leave radio mode.
  void set_navigation_active(bool active) noexcept {
    navigation_active_ = active;
    if (active) mode_ = Mode::Navigation;
  }

 private:
  Mode mode_ = Mode::Navigation;
  bool navigation_active_ = false;
};

}  // namespace moto::signal
