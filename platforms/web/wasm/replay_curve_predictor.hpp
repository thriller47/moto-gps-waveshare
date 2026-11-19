#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include "map_origin_predictor.hpp"

// Browser-only candidate C: a turn-sensitive overlay on the unchanged
// production predictor. Uses snapshot GPS as a *diagnostic* correction,
// so this is not a faithful raw-IMU/BLE hardware replay.
class ReplayCurvePredictor {
 public:
  void reset() {
    predictor_ = MapOriginPredictor{};
    last_heading_ = NAN;
    last_ms_ = 0;
    turn_weight_ = 0.0F;
  }
  MapOriginPredictor::Position advance(
      MapOriginPredictor::Position anchor,
      MapOriginPredictor::Position snapshot,
      std::uint64_t ms, std::uint32_t sequence,
      const std::string& route_id, std::uint32_t generation,
      float speed, float heading, bool valid) {
    // The player also supplies 60 Hz interpolated frames. Keep one
    // heading reference for >=100 ms, otherwise 16 ms samples would skip
    // all turns. Decay the detected turn gradually between windows.
    if (valid && std::isfinite(heading) &&
        std::isfinite(speed) && speed >= 3.0F) {
      if (!std::isfinite(last_heading_) || ms < last_ms_) {
        last_heading_ = heading;
        last_ms_ = ms;
        turn_weight_ = 0.0F;
      } else if (ms - last_ms_ >= 100U) {
        const std::uint64_t gap = ms - last_ms_;
        const float delta = std::abs(std::remainder(
            heading - last_heading_, 360.0F));
        const float detected = gap <= 2500U ?
            std::clamp((delta * 1000.0F / gap - 8.0F) / 22.0F,
                       0.0F, 1.0F) : 0.0F;
        turn_weight_ = std::max(detected, turn_weight_ * 0.65F);
        last_heading_ = heading;
        last_ms_ = ms;
      }
    } else {
      turn_weight_ = 0.0F;
      if (std::isfinite(heading)) last_heading_ = heading;
      last_ms_ = ms;
    }
    const float weight = turn_weight_;
    auto result = predictor_.advance(
        anchor, ms, sequence, route_id, generation,
        speed * (1.0F - 0.80F * weight), heading, valid);
    if (!valid || weight <= 0.0F ||
        !std::isfinite(snapshot.latitude_deg) ||
        !std::isfinite(snapshot.longitude_deg)) return result;
    const double north = (snapshot.latitude_deg - result.latitude_deg) * 111195.0;
    const double east = (snapshot.longitude_deg - result.longitude_deg) *
        111195.0 * std::cos(snapshot.latitude_deg * 3.141592653589793 / 180.0);
    if (std::isfinite(north) && std::isfinite(east) &&
        std::hypot(north, east) <= 60.0) {
      const double gain = 0.70 * weight;
      result.latitude_deg += (snapshot.latitude_deg - result.latitude_deg) * gain;
      result.longitude_deg += (snapshot.longitude_deg - result.longitude_deg) * gain;
    }
    return result;
  }
 private:
  MapOriginPredictor predictor_;
  float last_heading_ = NAN;
  std::uint64_t last_ms_ = 0;
  float turn_weight_ = 0.0F;
};
