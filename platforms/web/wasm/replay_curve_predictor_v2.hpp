#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include "map_origin_predictor.hpp"

// Replay-only C2 experiment. The input GPS snapshot is diagnostic: this
// cannot reproduce raw QMI8658 samples, their fusion, or BLE delivery times.
class ReplayCurvePredictorV2 {
 public:
  using Position = MapOriginPredictor::Position;
  void reset() {
    predictor_ = MapOriginPredictor{};
    last_heading_ = NAN;
    heading_reference_ms_ = 0;
    curve_weight_ = 0.0F;
    recent_turn_ms_ = 0;
    stationary_ = false;
  }
  Position advance(Position anchor, Position gps, std::uint64_t ms,
                   std::uint32_t sequence, const std::string& route_id,
                   std::uint32_t generation, float speed, float heading,
                   bool usable) {
    const bool gps_valid = usable &&
        std::isfinite(gps.latitude_deg) && std::isfinite(gps.longitude_deg) &&
        std::abs(gps.latitude_deg) <= 85.0 &&
        std::abs(gps.longitude_deg) <= 180.0;
    // Stop/creep: do not preserve a 20 m inertial offset. Also reset the
    // predictor so accelerating again starts from the next trusted anchor.
    if (gps_valid && std::isfinite(speed) && speed < 1.5F) {
      reset();
      stationary_ = true;
      return gps;
    }
    if (stationary_) {
      predictor_ = MapOriginPredictor{};
      stationary_ = false;
    }
    if (!gps_valid || !std::isfinite(speed) || !std::isfinite(heading)) {
      reset();
      return gps;
    }

    // Detect changes over >=100ms even during 60 Hz interpolated replay.
    if (!std::isfinite(last_heading_) || ms < heading_reference_ms_) {
      last_heading_ = heading;
      heading_reference_ms_ = ms;
      curve_weight_ = 0.0F;
      recent_turn_ms_ = 0;
    } else if (ms - heading_reference_ms_ >= 100U) {
      const auto span = ms - heading_reference_ms_;
      const float angle = std::abs(std::remainder(
          heading - last_heading_, 360.0F));
      const float rate = span <= 2500U ?
          angle * 1000.0F / static_cast<float>(span) : 0.0F;
      const float detected = std::clamp((rate - 6.0F) / 18.0F, 0.0F, 1.0F);
      curve_weight_ = std::max(detected, curve_weight_ * 0.55F);
      if (detected >= 0.12F) recent_turn_ms_ = ms;
      last_heading_ = heading;
      heading_reference_ms_ = ms;
    }

    const float weight = curve_weight_;
    // Reuse the unchanged production model and preserve straight movement.
    const auto predicted = predictor_.advance(
        anchor, ms, sequence, route_id, generation,
        speed * (1.0F - 0.90F * weight), heading, true);
    Position out = predicted;
    const double north = (gps.latitude_deg - out.latitude_deg) * 111195.0;
    const double east = (gps.longitude_deg - out.longitude_deg) * 111195.0 *
        std::cos(gps.latitude_deg * 3.14159265358979323846 / 180.0);
    if (!std::isfinite(north) || !std::isfinite(east) ||
        std::hypot(north, east) > 60.0) return out;

    // Keep a bounded GPS correction up to one second after the exit.
    float exit_weight = 0.0F;
    if (recent_turn_ms_ != 0 && ms >= recent_turn_ms_ &&
        ms - recent_turn_ms_ <= 1000U) {
      exit_weight = static_cast<float>(
          1.0 - static_cast<double>(ms - recent_turn_ms_) / 1000.0);
    }
    const double gain = std::clamp(
        std::max(0.86 * static_cast<double>(weight),
                 0.60 * static_cast<double>(exit_weight)), 0.0, 0.86);
    out.latitude_deg += (gps.latitude_deg - out.latitude_deg) * gain;
    out.longitude_deg += (gps.longitude_deg - out.longitude_deg) * gain;
    return out;
  }

 private:
  MapOriginPredictor predictor_;
  float last_heading_ = NAN;
  std::uint64_t heading_reference_ms_ = 0;
  float curve_weight_ = 0.0F;
  std::uint64_t recent_turn_ms_ = 0;
  bool stationary_ = false;
};
