#pragma once

#include <cmath>
#include <cstdint>

// Pure pacing policy shared by the ESP32 render task and host regression tests.
// Only IMU/motion renders are throttled. Navigation, route, scene, connection,
// media, and waypoint events bypass this policy.
namespace moto::nav {
constexpr std::uint32_t kMovingMotionPeriodMs = 50;   // at most 20 Hz
constexpr std::uint32_t kIdleMotionPeriodMs = 250;     // at most 4 Hz
constexpr float kIdleSpeedThresholdMps = 0.5F;
constexpr float kIdleHeadingDeltaDeg = 1.5F;

inline float shortest_heading_delta_deg(float previous, float current) {
  float delta = std::fmod(std::abs(current - previous), 360.0F);
  if(delta > 180.0F) delta = 360.0F - delta;
  return delta;
}

inline std::uint32_t motion_render_period_ms(float speed_mps,
                                              float last_heading_deg,
                                              float next_heading_deg) {
  const bool stationary = std::isfinite(speed_mps) &&
      speed_mps >= 0.0F && speed_mps < kIdleSpeedThresholdMps;
  const bool heading_stable = std::isfinite(last_heading_deg) &&
      std::isfinite(next_heading_deg) &&
      shortest_heading_delta_deg(last_heading_deg, next_heading_deg) <
          kIdleHeadingDeltaDeg;
  return stationary && heading_stable ? kIdleMotionPeriodMs
                                      : kMovingMotionPeriodMs;
}

inline std::uint32_t remaining_motion_wait_ms(std::uint64_t last_render_ms,
                                               std::uint64_t now_ms,
                                               std::uint32_t period_ms) {
  if(last_render_ms == 0 || now_ms < last_render_ms ||
     now_ms - last_render_ms >= period_ms) return 0;
  return static_cast<std::uint32_t>(period_ms - (now_ms - last_render_ms));
}
}  // namespace moto::nav
