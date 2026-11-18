#include "map_origin_predictor.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
int failures = 0;
#define CHECK(expr) do { if (!(expr)) { \
  std::cerr << __LINE__ << " failed: " #expr << '\n'; ++failures; \
} } while (false)

double north_metres(MapOriginPredictor::Position p, double initial_lat) {
  return (p.latitude_deg - initial_lat) * 111'195.0;
}

double east_metres(MapOriginPredictor::Position p, double initial_lon) {
  return (p.longitude_deg - initial_lon) * 111'195.0 *
         std::cos(p.latitude_deg * 3.14159265358979323846 / 180.0);
}

double distance_metres(MapOriginPredictor::Position a,
                       MapOriginPredictor::Position b) {
  const double dn = north_metres(a, b.latitude_deg);
  const double de = east_metres(a, b.longitude_deg);
  return std::hypot(dn, de);
}

void test_subframe_motion_and_correction() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position origin{45.0, 9.0};
  const std::string route{"test-route"};
  auto first = predictor.advance(origin, 1'000, 1, route, 1, 20, 90, true);
  CHECK(std::abs(first.longitude_deg - origin.longitude_deg) < 1e-10);
  auto p25 = predictor.advance(origin, 1'025, 1, route, 1, 20, 90, true);
  auto p50 = predictor.advance(origin, 1'050, 1, route, 1, 20, 90, true);
  CHECK(east_metres(p25, origin.longitude_deg) > 0.45);
  CHECK(east_metres(p50, origin.longitude_deg) > 0.95);
  CHECK(east_metres(p50, origin.longitude_deg) < 1.05);

  // A fresh GPS origin five metres ahead is eased in, not teleported.
  MapOriginPredictor::Position gps{45.0,
      origin.longitude_deg + 6.0 / (111'195.0 * std::cos(45.0 * 3.14159265358979323846 / 180.0))};
  const auto corrected = predictor.advance(gps, 1'075, 2, route, 1, 20, 90, true);
  CHECK(east_metres(corrected, origin.longitude_deg) > 1.4);
  CHECK(east_metres(corrected, origin.longitude_deg) < 3.0);
  const auto settled = predictor.advance(gps, 1'575, 2, route, 1, 0, 90, true);
  CHECK(east_metres(settled, origin.longitude_deg) > east_metres(corrected, origin.longitude_deg));
  const auto stopped = predictor.advance(gps, 2'000, 2, route, 1, 0, 90, true);
  const auto still = predictor.advance(gps, 2'025, 2, route, 1, 0, 90, true);
  CHECK(east_metres(still, origin.longitude_deg) >= east_metres(stopped, origin.longitude_deg));
}

void test_prediction_freezes_after_missing_geometry_anchor() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 4, "offline-route", 1, 20, 90, true);
  MapOriginPredictor::Position at_limit{};
  for (std::uint64_t ms = 1'025; ms <= 5'000; ms += 25) {
    auto current = predictor.advance(start, ms, 4, "offline-route", 1, 20, 90, true);
    if (ms == 4'400) at_limit = current;
  }
  const auto frozen = predictor.advance(start, 5'025, 4, "offline-route", 1, 20, 90, true);
  CHECK(east_metres(at_limit, start.longitude_deg) > 67.0);
  CHECK(east_metres(at_limit, start.longitude_deg) < 69.0);
  CHECK(std::abs(frozen.longitude_deg - at_limit.longitude_deg) < 1e-10);
}

void test_three_second_ble_geometry_cadence_stays_in_motion() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
  MapOriginPredictor::Position moved{};
  for (std::uint64_t ms = 1'025; ms <= 4'000; ms += 25)
    moved = predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
  CHECK(east_metres(moved, start.longitude_deg) > 59.0);
  CHECK(east_metres(moved, start.longitude_deg) < 61.0);
  const double scale = 111'195.0 * std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position measured{45.0, 9.0 + 60.0 / scale};
  const auto renewed = predictor.advance(measured, 4'025, 2, "route", 1, 20, 90, true);
  CHECK(east_metres(renewed, start.longitude_deg) > 59.0);
  CHECK(east_metres(renewed, start.longitude_deg) < 62.0);
}

void test_cruising_speed_reduces_only_backward_anchor_correction() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
  // Previous prediction has advanced 60m in three seconds.
  MapOriginPredictor::Position cruising{};
  for (std::uint64_t ms = 1'025; ms <= 4'000; ms += 25)
    cruising = predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
  const double before = east_metres(cruising, start.longitude_deg);
  CHECK(before > 59.0 && before < 61.0);
  // Incoming BLE anchor is 12m behind the predicted current position.
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 48.0 / scale};
  const auto corrected = predictor.advance(delayed, 4'025, 2, "route", 1, 20, 90, true);
  // 0.7s at 20m/s compensates up to 14m. No backward pull.
  CHECK(east_metres(corrected, start.longitude_deg) >= before);
  CHECK(east_metres(corrected, start.longitude_deg) < before + 1.0);
}

void test_turning_and_stopping_do_not_hide_backward_corrections() {
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 48.0 / scale};
  auto run = [&](float update_speed, float update_heading) {
    MapOriginPredictor predictor;
    predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
    for (std::uint64_t ms = 1'025; ms <= 4'000; ms += 25)
      predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
    return predictor.advance(delayed, 4'025, 2, "route", 1,
                             update_speed, update_heading, true);
  };
  const auto bend = run(20, 45);
  const auto stopped = run(0, 90);
  // Both scenarios must keep the GPS correction, unlike steady cruising.
  CHECK(east_metres(bend, start.longitude_deg) < 60.0);
  CHECK(east_metres(stopped, start.longitude_deg) < 60.0);
}

void test_curve_compensation_tapers_instead_of_switching_off() {
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 48.0 / scale};
  auto after_anchor = [&](float new_heading, float new_speed) {
    MapOriginPredictor predictor;
    predictor.advance(start, 1'000, 1, "same-route", 1, 20, 90, true);
    for (std::uint64_t ms = 1'025; ms <= 4'000; ms += 25)
      predictor.advance(start, ms, 1, "same-route", 1, 20, 90, true);
    return predictor.advance(delayed, 4'025, 2, "same-route", 1,
                             new_speed, new_heading, true);
  };
  const auto straight = after_anchor(90, 20);
  const auto gentle = after_anchor(70, 20);  // 20-degree curve: partial.
  const auto bend = after_anchor(45, 20);    // 45-degree curve: less.
  const auto sharp = after_anchor(0, 20);    // 90-degree turn: no boost.
  const auto stopped = after_anchor(90, 0);  // stop: no boost.
  const double straight_e = east_metres(straight, start.longitude_deg);
  const double gentle_e = east_metres(gentle, start.longitude_deg);
  const double bend_e = east_metres(bend, start.longitude_deg);
  const double sharp_e = east_metres(sharp, start.longitude_deg);
  CHECK(straight_e > gentle_e);
  CHECK(gentle_e > bend_e);
  CHECK(bend_e > sharp_e);
  CHECK(stopped.longitude_deg < straight.longitude_deg);
}

void test_low_frame_rate_integrates_full_elapsed_time() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
  const auto p400 = predictor.advance(start, 1'400, 1, "route", 1, 20, 90, true);
  CHECK(east_metres(p400, start.longitude_deg) > 7.9);
  CHECK(east_metres(p400, start.longitude_deg) < 8.1);
  const auto p800 = predictor.advance(start, 1'800, 1, "route", 1, 20, 90, true);
  CHECK(east_metres(p800, start.longitude_deg) > 15.9);
  CHECK(east_metres(p800, start.longitude_deg) < 16.1);
}

void test_turning_continues_with_attenuated_step_along_live_heading() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 1, "route", 1, 15, 90, true);
  const auto straight = predictor.advance(start, 2'000, 1, "route", 1, 15, 90, true);
  const auto corner = predictor.advance(start, 2'400, 1, "route", 1, 15, 40, true);
  // Dead reckoning advances along the live fused heading with gradual turn
  // attenuation (turn_scale ~0.62 at 50 deg) instead of freezing completely.
  const double corner_north = north_metres(corner, start.latitude_deg);
  const double corner_east = east_metres(corner, start.longitude_deg);
  CHECK(corner_north > 2.5 && corner_north < 3.2);
  CHECK(corner_east > east_metres(straight, start.longitude_deg) + 2.0);
  CHECK(corner_east < east_metres(straight, start.longitude_deg) + 3.5);
}

void test_roundabout_exit_three_second_geometry_gap() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position entry{45.0, 9.0};
  const double lat_scale = 111'195.0;
  const double lon_scale =
      lat_scale * std::cos(45.0 * 3.14159265358979323846 / 180.0);

  // Entry window at t = 1,118,784 ms (heading 340.59 deg, speed 17.67 m/s).
  predictor.advance(entry, 1'118'784, 10, "roundabout-route", 1, 17.67F, 340.59F, true);

  // Advance in 250 ms steps through the roundabout exit while no new BLE
  // geometry arrives for 3,075 ms (until 1,121,859 ms).
  MapOriginPredictor::Position pose = entry;
  for (std::uint64_t t = 1'119'034; t <= 1'120'034; t += 250) {
    pose = predictor.advance(entry, t, 10, "roundabout-route", 1, 15.0F, 349.20F, true);
  }
  for (std::uint64_t t = 1'120'284; t <= 1'121'034; t += 250) {
    pose = predictor.advance(entry, t, 10, "roundabout-route", 1, 12.31F, 3.20F, true);
  }
  for (std::uint64_t t = 1'121'284; t <= 1'121'784; t += 250) {
    pose = predictor.advance(entry, t, 10, "roundabout-route", 1, 9.86F, 19.00F, true);
  }
  const auto before_new_window =
      predictor.advance(entry, 1'121'857, 10, "roundabout-route", 1, 9.86F, 19.00F, true);

  // Actual exit position ~36 m north and ~2 m east of the roundabout entry.
  const MapOriginPredictor::Position exit_anchor{
      entry.latitude_deg + 36.0 / lat_scale,
      entry.longitude_deg + 2.0 / lon_scale};
  const double before_distance = distance_metres(before_new_window, exit_anchor);
  // Gradual turn attenuation keeps the predicted pose tracking the exit arc
  // without accumulating the >25 m lag caused by hard-freezing in curves.
  CHECK(before_distance < 20.0);

  // New geometry window arrives at t = 1,121,859 ms and eases in smoothly.
  const auto at_new_window =
      predictor.advance(exit_anchor, 1'121'859, 11, "roundabout-route", 1, 9.86F, 19.00F, true);
  const auto after_easing =
      predictor.advance(exit_anchor, 1'122'359, 11, "roundabout-route", 1, 9.86F, 19.00F, true);
  CHECK(distance_metres(at_new_window, exit_anchor) <= before_distance + 0.1);
  CHECK(distance_metres(after_easing, exit_anchor) <
        distance_metres(at_new_window, exit_anchor));
}

}  // namespace

int main() {
  test_subframe_motion_and_correction();
  test_prediction_freezes_after_missing_geometry_anchor();
  test_three_second_ble_geometry_cadence_stays_in_motion();
  test_cruising_speed_reduces_only_backward_anchor_correction();
  test_turning_and_stopping_do_not_hide_backward_corrections();
  test_curve_compensation_tapers_instead_of_switching_off();
  test_low_frame_rate_integrates_full_elapsed_time();
  test_turning_continues_with_attenuated_step_along_live_heading();
  test_roundabout_exit_three_second_geometry_gap();

  if (failures != 0) {
    std::cerr << failures << " map predictor checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All map predictor checks passed\n";
  return EXIT_SUCCESS;
}