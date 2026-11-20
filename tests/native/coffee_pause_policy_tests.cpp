#include "coffee_pause_policy.hpp"

#include <cstdlib>
#include <iostream>

namespace {
int failures = 0;
#define CHECK(v) do { if (!(v)) { ++failures; std::cerr << __LINE__ << " " #v "\n"; } } while (false)
using State = CoffeePausePolicy::State;

void test_never_connected_stays_awake() {
  CoffeePausePolicy p;
  CHECK(p.update(0, false, false, false, 10) == State::Awake);
  CHECK(p.update(2'000'000, false, false, false, 10) == State::Awake);
}

void test_default_coffee_and_reconnection() {
  CoffeePausePolicy p;
  CHECK(p.update(10'000, true, true, false, 10) == State::Awake);
  CHECK(p.update(11'000, true, false, false, 10) == State::Awake);
  CHECK(p.update(40'999, true, false, false, 10) == State::Awake);
  CHECK(p.update(41'000, true, false, false, 10) == State::Paused);
  CHECK(p.update(610'999, true, false, false, 10) == State::Paused);
  CHECK(p.update(611'000, true, false, false, 10) == State::PowerOffAfterLoss);
  // Reconnect inside a second session resets the missing-link deadline.
  CHECK(p.update(30'000, true, true, false, 10) == State::Awake);
  CHECK(p.update(31'000, true, false, false, 10) == State::Awake);
  CHECK(p.update(61'000, true, false, false, 10) == State::Paused);
  CHECK(p.update(630'999, true, false, false, 10) == State::Paused);
}

void test_maximum_duration_and_bounds() {
  CoffeePausePolicy p;
  CHECK(p.update(1, true, true, false, 30) == State::Awake);
  CHECK(p.update(2, true, false, false, 30) == State::Awake);
  CHECK(p.update(32, true, false, false, 30) == State::Awake);
  CHECK(p.update(30'002, true, false, false, 30) == State::Paused);
  CHECK(p.update(1'800'001, true, false, false, 30) == State::Paused);
  CHECK(p.update(1'800'002, true, false, false, 30) == State::PowerOffAfterLoss);
}

void test_arrival_keeps_its_two_minute_timer() {
  CoffeePausePolicy p;
  CHECK(p.update(100, true, true, false, 30) == State::Awake);
  CHECK(p.update(1'000, true, true, true, 30) == State::Awake);
  CHECK(p.update(5'000, true, false, false, 30) == State::Awake);
  CHECK(p.update(120'999, true, false, false, 30) == State::Awake);
  CHECK(p.update(121'000, true, false, false, 30) == State::PowerOffAfterArrival);
}

void test_short_duration_for_user_setting() {
  CoffeePausePolicy p;
  CHECK(p.update(0, true, true, false, 1) == State::Awake);
  CHECK(p.update(100, true, false, false, 1) == State::Awake);
  CHECK(p.update(30'100, true, false, false, 1) == State::Paused);
  CHECK(p.update(60'100, true, false, false, 1) == State::PowerOffAfterLoss);
}
} // namespace

int main() {
  test_never_connected_stays_awake();
  test_default_coffee_and_reconnection();
  test_maximum_duration_and_bounds();
  test_arrival_keeps_its_two_minute_timer();
  test_short_duration_for_user_setting();
  return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
