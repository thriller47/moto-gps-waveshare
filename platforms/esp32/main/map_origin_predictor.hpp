#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

// Smoothly translate the same route/road geometry between GPS-derived BLE
// geometry windows. Only the renderer calls this class; no BLE/flash/LVGL
// operations are performed here.
class MapOriginPredictor {
 public:
  struct Position {
    double latitude_deg = 0.0;
    double longitude_deg = 0.0;
  };

  Position advance(Position measured, std::uint64_t now_ms,
                   std::uint32_t geometry_sequence,
                   const std::string& route_id,
                   std::uint32_t route_generation, float speed_mps,
                   float heading_deg, bool usable) {
    if (!usable || route_id.empty() || !std::isfinite(measured.latitude_deg) ||
        !std::isfinite(measured.longitude_deg) ||
        std::abs(measured.latitude_deg) > 85.0 ||
        std::abs(measured.longitude_deg) > 180.0) {
      initialized_ = false;
      return measured;
    }

    if (!initialized_ || route_id_ != route_id ||
        route_generation_ != route_generation ||
        now_ms < last_tick_ms_ || now_ms - last_tick_ms_ > 1'000U) {
      initialized_ = true;
      route_id_ = route_id;
      route_generation_ = route_generation;
      geometry_sequence_ = geometry_sequence;
      pose_ = measured;
      last_tick_ms_ = now_ms;
      last_fix_ms_ = now_ms;
      correction_north_m_ = 0.0;
      correction_east_m_ = 0.0;
      return pose_;
    }

    const std::uint64_t elapsed_ms = now_ms - last_tick_ms_;
    last_tick_ms_ = now_ms;
    const double dt = std::min<std::uint64_t>(elapsed_ms, 250U) / 1'000.0;
    const double speed = static_cast<double>(speed_mps);

    // Guard against stale fixes and GPS speeds that would indicate a bad
    // sample. Never extrapolate indefinitely or while stopped.
    if (std::isfinite(speed) && speed >= 1.5 && speed <= 60.0 &&
        std::isfinite(heading_deg) &&
        now_ms - last_fix_ms_ <= 4'200U) {
      const double heading = static_cast<double>(heading_deg) *
                             3.14159265358979323846 / 180.0;
      move(std::cos(heading) * speed * dt,
           std::sin(heading) * speed * dt);
    }

    if (geometry_sequence != geometry_sequence_) {
      geometry_sequence_ = geometry_sequence;
      last_fix_ms_ = now_ms;
      const double lat_m = metres_per_degree_lat();
      const double lon_m = metres_per_degree_lon(measured.latitude_deg);
      const double north = (measured.latitude_deg - pose_.latitude_deg) * lat_m;
      const double east = (measured.longitude_deg - pose_.longitude_deg) * lon_m;
      if (std::hypot(north, east) > 75.0) {
        // New route snap, a tunnel exit or a large GPS correction must not
        // cause several seconds of inaccurate movement.
        pose_ = measured;
        correction_north_m_ = 0.0;
        correction_east_m_ = 0.0;
      } else {
        correction_north_m_ = north;
        correction_east_m_ = east;
      }
    }

    // A soft correction eliminates the 1–3 second GPS window jumps without
    // changing the actual route, the maneuver distances or zoom.
    if (dt > 0.0) {
      const double alpha = std::min(1.0, dt / 0.55);
      const double north = correction_north_m_ * alpha;
      const double east = correction_east_m_ * alpha;
      move(north, east);
      correction_north_m_ -= north;
      correction_east_m_ -= east;
    }
    return pose_;
  }

 private:
  static constexpr double metres_per_degree_lat() {
    return 111'195.0;
  }

  static double metres_per_degree_lon(double latitude) {
    constexpr double kPi = 3.14159265358979323846;
    return metres_per_degree_lat() *
           std::max(0.05, std::cos(latitude * kPi / 180.0));
  }

  void move(double north_m, double east_m) {
    pose_.latitude_deg += north_m / metres_per_degree_lat();
    pose_.longitude_deg += east_m /
                           metres_per_degree_lon(pose_.latitude_deg);
  }

  Position pose_{};
  std::string route_id_;
  std::uint32_t route_generation_ = 0;
  std::uint32_t geometry_sequence_ = 0;
  std::uint64_t last_tick_ms_ = 0;
  std::uint64_t last_fix_ms_ = 0;
  double correction_north_m_ = 0.0;
  double correction_east_m_ = 0.0;
  bool initialized_ = false;
};
