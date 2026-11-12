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
      last_anchor_heading_deg_ = heading_deg;
      last_anchor_speed_mps_ = speed_mps;
      return pose_;
    }

    const std::uint64_t elapsed_ms = now_ms - last_tick_ms_;
    last_tick_ms_ = now_ms;
    const double dt = std::min<std::uint64_t>(elapsed_ms, 250U) / 1'000.0;
    const double speed = static_cast<double>(speed_mps);

    // Normal BLE route windows can arrive 3.1s apart. The old 1.5s cap
    // froze the moving map until the next window, causing 30m catch-ups.
    // Bound prediction at 3.4s, still freezing during a genuine outage.
    // GPS remains authoritative and the route geometry is not modified.
    if (std::isfinite(speed) && speed >= 1.5 && speed <= 60.0 &&
        std::isfinite(heading_deg) &&
        now_ms - last_fix_ms_ <= 3'400U) {
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
      double north = (measured.latitude_deg - pose_.latitude_deg) * lat_m;
      double east = (measured.longitude_deg - pose_.longitude_deg) * lon_m;
      const double raw_gap_m = std::hypot(north, east);
      // A geometry window contains the GPS position when Android CREATED it,
      // not at BLE receipt. At cruising speed the predicted pose has already
      // advanced while that anchor was in flight. Pulling it all the way back
      // causes a visible delay at fixed junctions even when FPS is smooth.
      // Cancel only a bounded part of a BACKWARD correction on a stable
      // straight. Never add forward movement to an already aligned anchor.
      // Sharp turns, stops, large cross-track errors and route changes
      // retain the unmodified GPS correction.
      const bool moving = std::isfinite(speed_mps) &&
          speed_mps >= 3.0F && speed_mps <= 35.0F &&
          std::isfinite(heading_deg) &&
          std::isfinite(last_anchor_heading_deg_) &&
          std::isfinite(last_anchor_speed_mps_);
      if (moving && raw_gap_m <= 35.0 && raw_gap_m > 0.5) {
        constexpr double kMaxAgeCompensationSeconds = 0.70;
        constexpr double kMaxAgeCompensationMeters = 14.0;
        const double heading = static_cast<double>(heading_deg) *
                               3.14159265358979323846 / 180.0;
        const double north_unit = std::cos(heading);
        const double east_unit = std::sin(heading);
        const double behind_m = -(north * north_unit + east * east_unit);
        const double lateral_m = std::abs(-north * east_unit +
                                          east * north_unit);
        // The heading change between BLE geometry anchors is a conservative
        // proxy for road curvature. Smoothly fade a backward-only correction
        // from full effect at <=12 deg to zero at >=70 deg. At a hairpin or
        // roundabout exit do not extrapolate through the next road.
        const double turn_delta = std::abs(std::remainder(
            static_cast<double>(heading_deg - last_anchor_heading_deg_),
            360.0));
        const double turn_weight = std::clamp(
            (70.0 - turn_delta) / 58.0, 0.0, 1.0);
        const double speed_tolerance = std::max(
            2.0, static_cast<double>(speed_mps) * 0.25);
        const double speed_delta = std::abs(
            static_cast<double>(speed_mps - last_anchor_speed_mps_));
        const double speed_weight = std::clamp(
            (2.0 * speed_tolerance - speed_delta) / speed_tolerance,
            0.0, 1.0);
        // Reject large lateral offsets: they could be an actual lane change,
        // missed turn or GPS jump rather than delayed BLE delivery.
        if (behind_m > 0.0 && lateral_m <= 14.0) {
          const double reduction_m = std::min(
              behind_m, std::min(static_cast<double>(speed_mps) *
                                kMaxAgeCompensationSeconds,
                                kMaxAgeCompensationMeters)) *
              turn_weight * speed_weight;
          north += north_unit * reduction_m;
          east += east_unit * reduction_m;
        }
      }
      last_anchor_heading_deg_ = heading_deg;
      last_anchor_speed_mps_ = speed_mps;
      if (raw_gap_m > 75.0) {
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
  float last_anchor_heading_deg_ = NAN;
  float last_anchor_speed_mps_ = 0.0F;
  bool initialized_ = false;
};
