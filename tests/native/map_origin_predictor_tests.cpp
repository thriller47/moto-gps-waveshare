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

double east_metres(MapOriginPredictor::Position p, double initial_lon) {
  return (p.longitude_deg - initial_lon) * 111'195.0 *
         std::cos(p.latitude_deg * 3.14159265358979323846 / 180.0);
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
  const auto gentle = after_anchor(70, 20);   // 20-degree curve: partial.
  const auto bend = after_anchor(45, 20);     // 45-degree curve: less.
  const auto sharp = after_anchor(0, 20);     // 90-degree turn: no boost.
  const auto stopped = after_anchor(90, 0);   // stop: no boost.
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

void test_turning_freezes_straight_line_extrapolation_until_new_geometry() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 1, "route", 1, 15, 90, true);
  const auto straight = predictor.advance(start, 2'000, 1, "route", 1, 15, 90, true);
  const auto corner = predictor.advance(start, 2'400, 1, "route", 1, 15, 40, true);
  CHECK(std::abs(corner.latitude_deg - straight.latitude_deg) < 1e-10);
  CHECK(std::abs(corner.longitude_deg - straight.longitude_deg) < 1e-10);
}

void test_curve_and_braking_freeze_extrapolation_before_new_geometry() {
  const MapOriginPredictor::Position start{45.0, 9.0};

  // Case 1: Heading divergence > 15 deg freezes extrapolation
  {
    MapOriginPredictor p;
    p.advance(start, 1'000, 1, "route", 1, 20.0F, 90.0F, true);
    auto p1 = p.advance(start, 1'200, 1, "route", 1, 20.0F, 90.0F, true);
    CHECK(p1.longitude_deg > start.longitude_deg);
    // Diverge by 20 deg (110 vs 90) -> freezes
    auto p2 = p.advance(start, 1'400, 1, "route", 1, 20.0F, 110.0F, true);
    CHECK(std::abs(p2.latitude_deg - p1.latitude_deg) < 1e-10);
    CHECK(std::abs(p2.longitude_deg - p1.longitude_deg) < 1e-10);
  }

  // Case 2: Braking > 25% (speed < 0.75 * anchor) freezes extrapolation
  {
    MapOriginPredictor p;
    p.advance(start, 1'000, 1, "route", 1, 20.0F, 90.0F, true);
    auto p1 = p.advance(start, 1'200, 1, "route", 1, 20.0F, 90.0F, true);
    CHECK(p1.longitude_deg > start.longitude_deg);
    // Speed drops to 14 m/s (< 0.75 * 20 = 15 m/s) -> freezes
    auto p2 = p.advance(start, 1'400, 1, "route", 1, 14.0F, 90.0F, true);
    CHECK(std::abs(p2.latitude_deg - p1.latitude_deg) < 1e-10);
    CHECK(std::abs(p2.longitude_deg - p1.longitude_deg) < 1e-10);
  }

  // Case 3: Both curve > 15 deg and braking > 25% freeze extrapolation
  {
    MapOriginPredictor p;
    p.advance(start, 1'000, 1, "route", 1, 20.0F, 90.0F, true);
    auto p1 = p.advance(start, 1'200, 1, "route", 1, 20.0F, 90.0F, true);
    CHECK(p1.longitude_deg > start.longitude_deg);
    // Both 110 deg and 13 m/s -> freezes
    auto p2 = p.advance(start, 1'400, 1, "route", 1, 13.0F, 110.0F, true);
    CHECK(std::abs(p2.latitude_deg - p1.latitude_deg) < 1e-10);
    CHECK(std::abs(p2.longitude_deg - p1.longitude_deg) < 1e-10);
  }
}

void test_roundabout_exit_three_second_geometry_gap() {
  // Synthetic regression, no user GPS data. Approximately mirrors the
  // recorded 3,075 ms gap, turning 340 -> 19 degrees while decelerating.
  const MapOriginPredictor::Position origin{45.0, 9.0};
  const auto north = [](MapOriginPredictor::Position p) {
    return (p.latitude_deg - 45.0) * 111'195.0;
  };
  const auto east = [](MapOriginPredictor::Position p) {
    return east_metres(p, 9.0);
  };
  MapOriginPredictor predictor;
  constexpr std::uint64_t anchor_ms = 10'000;
  const std::string route{"roundabout-synthetic"};
  const auto first = predictor.advance(origin, anchor_ms, 1, route, 1,
                                       17.67F, 340.59F, true);
  CHECK(std::abs(north(first)) < 0.01);
  // At the beginning, the speed/heading guardrails both permit movement.
  const auto straight = predictor.advance(origin, anchor_ms + 250, 1,
                                          route, 1, 17.0F, 344.0F, true);
  CHECK(north(straight) > 3.0);
  // With this speed ratio (13.5/17.67), only the heading still allows motion.
  const auto gentle = predictor.advance(origin, anchor_ms + 500, 1,
                                        route, 1, 13.5F, 349.2F, true);
  CHECK(north(gentle) > north(straight));
  // At 3.2 deg, angular difference to the last anchor exceeds 15 deg
  // and speed has fallen below 75% of the anchor.
  const auto gated = predictor.advance(origin, anchor_ms + 750, 1,
                                       route, 1, 12.31F, 3.2F, true);
  CHECK(std::abs(north(gated) - north(gentle)) < 0.01);
  CHECK(std::abs(east(gated) - east(gentle)) < 0.01);
  // Repeated ticks keep the actual code alive, but no straight-line
  // extrapolation resumes before a new geometry anchor.
  MapOriginPredictor::Position frozen = gated;
  for (std::uint64_t ms = anchor_ms + 1'000; ms <= anchor_ms + 3'000; ms += 250) {
    frozen = predictor.advance(origin, ms, 1, route, 1,
                               9.86F, 19.0F, true);
  }
  CHECK(std::abs(north(frozen) - north(gated)) < 0.01);
  CHECK(std::abs(east(frozen) - east(gated)) < 0.01);

  // This *distinct* next window arrives 3,075 ms after the last anchor.
  // It is only 75 ms after the previous render tick, so the predictor
  // corrects smoothly rather than resetting on a >1s tick gap.
  const double longitude_scale =
      111'195.0 * std::cos(45.0 * 3.14159265358979323846 / 180.0);
  const MapOriginPredictor::Position next_anchor{
      45.0 + 45.0 / 111'195.0, 9.0 + 10.0 / longitude_scale};
  const auto before_distance = std::hypot(45.0 - north(frozen),
                                          10.0 - east(frozen));
  const auto corrected = predictor.advance(
      next_anchor, anchor_ms + 3'075, 2, route, 1, 9.86F, 19.0F, true);
  const auto immediately_after = std::hypot(
      45.0 - north(corrected), 10.0 - east(corrected));
  CHECK(before_distance > 25.0);
  CHECK(immediately_after < before_distance);
  CHECK(immediately_after > 1.0); // No instantaneous position teleport.
  // During the next half second the soft GPS correction keeps converging.
  const auto recovering = predictor.advance(
      next_anchor, anchor_ms + 3'575, 2, route, 1, 9.86F, 19.0F, true);
  const auto after_half_second = std::hypot(
      45.0 - north(recovering), 10.0 - east(recovering));
  CHECK(after_half_second < immediately_after);
  CHECK(after_half_second < before_distance * 0.6);
}

void test_stale_stop_and_route_changes() {
  MapOriginPredictor predictor;
  const MapOriginPredictor::Position start{45.0, 9.0};
  predictor.advance(start, 1'000, 3, "route-A", 1, 15, 0, true);
  auto moved = predictor.advance(start, 1'200, 3, "route-A", 1, 15, 0, true);
  CHECK(moved.latitude_deg > start.latitude_deg);
  auto idle = predictor.advance(start, 1'225, 3, "route-A", 1, 15, 0, false);
  CHECK(idle.latitude_deg == start.latitude_deg);
  auto resumed = predictor.advance(start, 1'250, 3, "route-A", 1, 15, 0, true);
  CHECK(resumed.latitude_deg == start.latitude_deg);
  auto rerouted = predictor.advance(start, 1'275, 3, "route-B", 1, 15, 0, true);
  CHECK(rerouted.latitude_deg == start.latitude_deg);
  auto other_generation = predictor.advance(start, 1'300, 3, "route-B", 2, 15, 0, true);
  CHECK(other_generation.latitude_deg == start.latitude_deg);
  predictor.advance(start, 1'325, 3, "route-B", 2, 0, 0, true);
  auto parked = predictor.advance(start, 1'825, 3, "route-B", 2, 0, 0, true);
  CHECK(parked.latitude_deg == start.latitude_deg);
  auto frozen = predictor.advance(start, 6'200, 3, "route-B", 2, 15, 0, true);
  CHECK(frozen.latitude_deg == start.latitude_deg);
}
} // namespace

int main() {
  test_subframe_motion_and_correction();
  test_low_frame_rate_integrates_full_elapsed_time();
  test_turning_freezes_straight_line_extrapolation_until_new_geometry();
  test_curve_and_braking_freeze_extrapolation_before_new_geometry();
  test_roundabout_exit_three_second_geometry_gap();
  test_stale_stop_and_route_changes();
  test_prediction_freezes_after_missing_geometry_anchor();
  test_three_second_ble_geometry_cadence_stays_in_motion();
  test_cruising_speed_reduces_only_backward_anchor_correction();
  test_turning_and_stopping_do_not_hide_backward_corrections();
  test_curve_compensation_tapers_instead_of_switching_off();
  if (failures) {
    std::cerr << failures << " map predictor checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "map origin predictor checks passed\n";
  return EXIT_SUCCESS;
}
