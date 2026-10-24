#include <cassert>

#include "signal_mode_controller.h"

int main() {
  using moto::signal::Mode;
  using moto::signal::ModeController;
  using moto::signal::RequestResult;

  ModeController modes;
  assert(modes.mode() == Mode::Navigation);
  assert(modes.radio_permitted());
  assert(modes.request_radio() == RequestResult::Accepted);
  assert(modes.mode() == Mode::Radio);
  assert(modes.request_radio() == RequestResult::AlreadyActive);
  assert(modes.request_navigation() == RequestResult::Accepted);
  assert(modes.mode() == Mode::Navigation);

  modes.set_navigation_active(true);
  assert(!modes.radio_permitted());
  assert(modes.request_radio() == RequestResult::NavigationInProgress);
  assert(modes.mode() == Mode::Navigation);
  modes.set_navigation_active(false);
  assert(modes.request_radio() == RequestResult::Accepted);
  // An incoming route immediately overrides active radio mode.
  modes.set_navigation_active(true);
  assert(modes.mode() == Mode::Navigation);
  assert(modes.request_radio() == RequestResult::NavigationInProgress);
  return 0;
}
