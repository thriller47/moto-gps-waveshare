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
  test_stale_stop_and_route_changes();
  if (failures) {
    std::cerr << failures << " map predictor checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "map origin predictor checks passed\n";
  return EXIT_SUCCESS;
}
