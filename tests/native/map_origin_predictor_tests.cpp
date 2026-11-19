#include "map_origin_predictor.hpp"
#include "../../platforms/web/wasm/replay_curve_predictor.hpp"

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
    if (ms == 2'800) at_limit = current;
  }
  const auto frozen = predictor.advance(start, 5'025, 4, "offline-route", 1, 20, 90, true);
  CHECK(east_metres(at_limit, start.longitude_deg) > 35.0);
  CHECK(east_metres(at_limit, start.longitude_deg) < 37.0);
  CHECK(std::abs(frozen.longitude_deg - at_limit.longitude_deg) < 1e-10);
}

void test_three_second_ble_geometry_cadence_stays_in_motion() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
  MapOriginPredictor::Position moved{};
  for (std::uint64_t ms = 1'025; ms <= 2'500; ms += 25)
    moved = predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
  CHECK(east_metres(moved, start.longitude_deg) > 29.0);
  CHECK(east_metres(moved, start.longitude_deg) < 31.0);
  const double scale = 111'195.0 * std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position measured{45.0, 9.0 + 30.0 / scale};
  const auto renewed = predictor.advance(measured, 2'525, 2, "route", 1, 20, 90, true);
  CHECK(east_metres(renewed, start.longitude_deg) > 29.0);
  CHECK(east_metres(renewed, start.longitude_deg) < 32.0);
}

void test_cruising_speed_reduces_only_backward_anchor_correction() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
  // Previous prediction has advanced 30m in 1.5 seconds.
  MapOriginPredictor::Position cruising{};
  for (std::uint64_t ms = 1'025; ms <= 2'500; ms += 25)
    cruising = predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
  const double before = east_metres(cruising, start.longitude_deg);
  CHECK(before > 29.0 && before < 31.0);
  // Incoming BLE anchor is 6m behind the predicted current position.
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 24.0 / scale};
  const auto corrected = predictor.advance(delayed, 2'525, 2, "route", 1, 20, 90, true);
  // 0.35s at 20m/s compensates up to 7m. No backward pull.
  CHECK(east_metres(corrected, start.longitude_deg) >= before);
  CHECK(east_metres(corrected, start.longitude_deg) < before + 1.0);
}

void test_turning_and_stopping_do_not_hide_backward_corrections() {
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 24.0 / scale};
  auto run = [&](float update_speed, float update_heading) {
    MapOriginPredictor predictor;
    predictor.advance(start, 1'000, 1, "route", 1, 20, 90, true);
    for (std::uint64_t ms = 1'025; ms <= 2'500; ms += 25)
      predictor.advance(start, ms, 1, "route", 1, 20, 90, true);
    return predictor.advance(delayed, 2'525, 2, "route", 1,
                             update_speed, update_heading, true);
  };
  const auto bend = run(20, 45);
  const auto stopped = run(0, 90);
  // Both scenarios must keep the GPS correction, unlike steady cruising.
  CHECK(east_metres(bend, start.longitude_deg) < 30.0);
  CHECK(east_metres(stopped, start.longitude_deg) < 30.0);
}

void test_curve_compensation_tapers_instead_of_switching_off() {
  const MapOriginPredictor::Position start{45.0, 9.0};
  const double scale = 111'195.0 *
      std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position delayed{45.0, 9.0 + 24.0 / scale};
  auto after_anchor = [&](float new_heading, float new_speed) {
    MapOriginPredictor predictor;
    predictor.advance(start, 1'000, 1, "same-route", 1, 20, 90, true);
    for (std::uint64_t ms = 1'025; ms <= 2'500; ms += 25)
      predictor.advance(start, ms, 1, "same-route", 1, 20, 90, true);
    return predictor.advance(delayed, 2'525, 2, "same-route", 1,
                             new_speed, new_heading, true);
  };
  const auto straight = after_anchor(90, 20);
  const auto gentle = after_anchor(75, 20);  // 15-degree curve: partial (turn_weight ~0.68).
  const auto bend = after_anchor(68, 20);    // 22-degree curve: less (turn_weight ~0.36).
  const auto sharp = after_anchor(0, 20);    // 90-degree turn: no boost (turn_weight = 0.0).
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
  // Dead reckoning advances along the live fused heading with early turn
  // attenuation (turn_scale = 0.10 at 50 deg) instead of freezing completely.
  const double corner_north = north_metres(corner, start.latitude_deg);
  const double corner_east = east_metres(corner, start.longitude_deg);
  CHECK(corner_north > 0.40 && corner_north < 0.55);
  CHECK(corner_east > east_metres(straight, start.longitude_deg) + 0.30);
  CHECK(corner_east < east_metres(straight, start.longitude_deg) + 0.50);
}

void test_turn_scale_threshold_and_floor() {
  const MapOriginPredictor::Position start{45.0, 9.0};
  auto step_with_heading = [&](float new_heading) {
    MapOriginPredictor predictor;
    predictor.advance(start, 1'000, 1, "test-route", 1, 15, 90, true);
    return predictor.advance(start, 1'400, 1, "test-route", 1, 15, new_heading, true);
  };
  const auto straight = step_with_heading(90);       // delta = 0 deg -> turn_scale = 1.0
  const auto at_threshold = step_with_heading(86);   // delta = 4 deg -> turn_scale = 1.0
  const auto past_threshold = step_with_heading(80); // delta = 10 deg -> turn_scale < 1.0
  const auto sharp36 = step_with_heading(54);        // delta = 36 deg -> turn_scale = floor 0.10
  const auto sharp70 = step_with_heading(20);        // delta = 70 deg -> turn_scale = floor 0.10
  const auto sharp90 = step_with_heading(0);         // delta = 90 deg -> turn_scale = floor 0.10

  const double straight_dist = distance_metres(straight, start);
  const double thresh_dist = distance_metres(at_threshold, start);
  const double past_dist = distance_metres(past_threshold, start);
  const double sharp36_dist = distance_metres(sharp36, start);
  const double sharp70_dist = distance_metres(sharp70, start);
  const double sharp90_dist = distance_metres(sharp90, start);

  // <= 4 deg maintains full scale (15 m/s * 0.4s * 1.0 = 6.0 m)
  CHECK(std::abs(straight_dist - 6.0) < 0.05);
  CHECK(std::abs(thresh_dist - 6.0) < 0.05);
  // > 4 deg attenuates step distance
  CHECK(past_dist < 6.0 && past_dist > 4.5);
  // >= 36 deg reaches floor 0.10 (15 m/s * 0.4s * 0.10 = 0.6 m)
  CHECK(std::abs(sharp36_dist - 0.6) < 0.05);
  CHECK(std::abs(sharp70_dist - 0.6) < 0.05);
  CHECK(std::abs(sharp90_dist - 0.6) < 0.05);

  // Low speed in curve (< 7.0 m/s) scales step further for Doppler lag compensation:
  // 5 m/s * 0.4s * 0.10 * (5/7) = ~0.143 m
  MapOriginPredictor predictor_slow;
  predictor_slow.advance(start, 1'000, 1, "test-route", 1, 5, 90, true);
  const auto slow_turn = predictor_slow.advance(start, 1'400, 1, "test-route", 1, 5, 20, true);
  const double slow_dist = distance_metres(slow_turn, start);
  CHECK(std::abs(slow_dist - (5.0 * 0.4 * 0.10 * (5.0 / 7.0))) < 0.02);
}

void test_roundabout_exit_three_second_geometry_gap() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position entry{45.0, 9.0};
  constexpr double kPi = 3.14159265358979323846;
  const double lat_scale = 111'195.0;
  const double lon_scale = lat_scale * std::cos(45.0 * kPi / 180.0);

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
  // Dead reckoning pauses after 1,800 ms of missing geometry anchors, keeping
  // before_distance bounded (~17.7 m) during a 3 s gap until the new anchor arrives.
  CHECK(before_distance < 19.0);

  // New geometry window arrives at t = 1,121,859 ms without snapping.
  const auto at_new_window =
      predictor.advance(exit_anchor, 1'121'859, 11, "roundabout-route", 1, 9.86F, 19.00F, true);
  CHECK(distance_metres(at_new_window, exit_anchor) <= before_distance + 0.1);

  // 500 ms later at 9.86 m/s (19 deg), the bike has progressed past exit_anchor and soft correction converges.
  const auto after_easing =
      predictor.advance(exit_anchor, 1'122'359, 11, "roundabout-route", 1, 9.86F, 19.00F, true);
  const double expected_north = 36.0 + 9.86 * 0.5 * std::cos(19.0 * kPi / 180.0);
  const double expected_east = 2.0 + 9.86 * 0.5 * std::sin(19.0 * kPi / 180.0);
  const MapOriginPredictor::Position moving_exit{
      entry.latitude_deg + expected_north / lat_scale,
      entry.longitude_deg + expected_east / lon_scale};
  CHECK(distance_metres(after_easing, moving_exit) < 8.0);
}

void test_replay_curve_candidate_baseline_and_turn() {
  const MapOriginPredictor::Position origin{45.0, 9.0};
  ReplayCurvePredictor curve;
  MapOriginPredictor production;
  curve.advance(origin, origin, 1000, 1, "test", 1, 12, 90, true);
  production.advance(origin, 1000, 1, "test", 1, 12, 90, true);
  const auto straight = curve.advance(
      origin, origin, 1100, 1, "test", 1, 12, 90, true);
  const auto baseline_straight = production.advance(
      origin, 1100, 1, "test", 1, 12, 90, true);
  CHECK(distance_metres(straight, baseline_straight) < 0.001);

  // A steep heading change is diagnosed in the recorded snapshot; GPS
  // correction reduces the hypothetical displacement relative to that fix.
  const auto in_curve = curve.advance(
      origin, origin, 1200, 1, "test", 1, 12, 130, true);
  const auto baseline_curve = production.advance(
      origin, 1200, 1, "test", 1, 12, 130, true);
  CHECK(distance_metres(in_curve, origin) < distance_metres(baseline_curve, origin));
  // A 60 Hz browser replay must detect an accumulated turn over 100 ms;
  // no individual 16 ms sample should be mistaken for a straight line.
  ReplayCurvePredictor interpolated;
  MapOriginPredictor interpolated_baseline;
  interpolated.advance(origin, origin, 1000, 1, "test", 1, 12, 90, true);
  interpolated_baseline.advance(origin, 1000, 1, "test", 1, 12, 90, true);
  MapOriginPredictor::Position curve_frame{};
  MapOriginPredictor::Position baseline_frame{};
  for (int i = 1; i <= 8; ++i) {
    const auto t = static_cast<std::uint64_t>(1000 + 16 * i);
    const float heading = 90.0F + i * 5.0F;
    curve_frame = interpolated.advance(
        origin, origin, t, 1, "test", 1, 12, heading, true);
    baseline_frame = interpolated_baseline.advance(
        origin, t, 1, "test", 1, 12, heading, true);
  }
  CHECK(distance_metres(curve_frame, origin) <
        distance_metres(baseline_frame, origin));

  curve.reset();
  const auto after_reset = curve.advance(
      origin, origin, 1000, 1, "test", 1, 12, 90, true);
  CHECK(distance_metres(after_reset, origin) < 0.001);
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
  test_turn_scale_threshold_and_floor();
  test_replay_curve_candidate_baseline_and_turn();

  if (failures != 0) {
    std::cerr << failures << " map predictor checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All map predictor checks passed\n";
  return EXIT_SUCCESS;
}