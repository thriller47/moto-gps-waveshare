#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

// ESP32-only experimental position-predictor assist. The fused heading from
// the QMI8658 + BLE phone course remains unchanged. This produces a bounded
// curve confidence for motion *translation* only, not map rotation.
// C2's synthetic "GPS every frame" correction is deliberately NOT ported.
class TurnAssistHeadingPolicy {
 public:
  void reset() noexcept {
    sample_heading_ = NAN;
    sample_ms_ = 0;
    intensity_ = 0.0F;
    last_turn_ms_ = 0;
  }
  float observe(float heading_deg, float speed_mps, std::uint64_t now_ms,
                bool usable) noexcept {
    if (!usable || !std::isfinite(heading_deg) || heading_deg < 0.0F ||
        heading_deg >= 360.0F || !std::isfinite(speed_mps) ||
        speed_mps < 3.0F || speed_mps > 35.0F) {
      reset();
      return 0.0F;
    }
    if (!std::isfinite(sample_heading_) || now_ms < sample_ms_ ||
        now_ms - sample_ms_ > 2500U) {
      sample_heading_ = heading_deg;
      sample_ms_ = now_ms;
      intensity_ = 0.0F;
      last_turn_ms_ = 0;
      return 0.0F;
    }
    // Accumulate heading changes over at least 100 ms. At 40 Hz a single
    // 25 ms sample is too short to distinguish a turn from gyro jitter.
    if (now_ms - sample_ms_ >= 100U) {
      const float dt = static_cast<float>(now_ms - sample_ms_) / 1000.0F;
      const float angular_change = std::abs(std::remainder(
          heading_deg - sample_heading_, 360.0F));
      const float rate = angular_change / dt;
      const float detected = std::clamp((rate - 6.0F) / 18.0F, 0.0F, 1.0F);
      intensity_ = std::max(detected, intensity_ * 0.55F);
      if (detected >= 0.12F) last_turn_ms_ = now_ms;
      sample_heading_ = heading_deg;
      sample_ms_ = now_ms;
    }
    // A brief tail helps the next fresh BLE geometry settle on curve exit,
    // without continuously slowing the motorcycle on the following straight.
    if (last_turn_ms_ != 0U && now_ms >= last_turn_ms_ &&
        now_ms - last_turn_ms_ <= 800U) {
      const float tail = 0.35F *
          (1.0F - static_cast<float>(now_ms - last_turn_ms_) / 800.0F);
      return std::clamp(std::max(intensity_, tail), 0.0F, 1.0F);
    }
    return intensity_;
  }
 private:
  float sample_heading_ = NAN;
  std::uint64_t sample_ms_ = 0;
  float intensity_ = 0.0F;
  std::uint64_t last_turn_ms_ = 0;
};
