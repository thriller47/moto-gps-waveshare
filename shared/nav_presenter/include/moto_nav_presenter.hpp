#pragma once

#include "moto_nav_ui.h"
#include "moto_curve_zoom.hpp"
#include "nav_core/nav_core.hpp"

#include <string>

namespace moto::nav {

// Deterministic bridge between the shared navigation model and the shared
// LVGL view. The char pointers in ui_state() remain valid until the next
// update() call or destruction of this presenter.
class NavPresenter {
 public:
  NavPresenter();
  NavPresenter(const NavPresenter&) = delete;
  NavPresenter& operator=(const NavPresenter&) = delete;
  NavPresenter(NavPresenter&&) = delete;
  NavPresenter& operator=(NavPresenter&&) = delete;

  void update(const NavSnapshot& snapshot);
  // Simulator-only A/B experiment; default OFF on every target (including ESP32).
  void set_experimental_curve_zoom_enabled(bool enabled) noexcept;
  [[nodiscard]] double applied_zoom_factor() const noexcept;
  [[nodiscard]] const moto_ui_state_t& ui_state() const;

  // Applies the last mapped state to the platform-independent LVGL UI API.
  // LVGL initialization remains the responsibility of the platform adapter.
  void apply_to_lvgl() const;

 private:
  std::string road_name_;
  std::string next_road_name_;
  moto_ui_state_t ui_state_{};
  bool experimental_curve_zoom_enabled_ = false;
  double applied_zoom_factor_ = 1.0;
};

}  // namespace moto::nav
