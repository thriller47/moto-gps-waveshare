#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

// Short-term heading fusion for the handlebar-mounted display.
//
// The phone's Core Location course is an absolute, drift-free reference while
// the motorcycle is moving. The onboard QMI8658 gyroscope fills the latency
// between phone fixes and preserves responsive relative turns at very low
// speed. Since QMI8658 has no magnetometer, this class deliberately never
// claims to create an absolute north reference by itself.
class MotionHeadingFusion {
 public:
  void reset() noexcept {
    initialized_ = false;
    has_phone_reference_ = false;
    has_moving_course_ = false;
    stationary_ = false;
    heading_deg_ = 0.0F;
    filtered_rate_dps_ = 0.0F;
    heading_var_deg2_ = kInitialHeadingVarDeg2;
    last_sample_ms_ = 0;
    large_error_count_ = 0;
    pending_large_course_deg_ = 0.0F;
    pending_large_course_ms_ = 0;
    // Preserve learned gyro bias across a BLE reconnect.
  }

  // course_accuracy_deg is optional until the BLE payload negotiates an
  // angular-accuracy extension. Without it, confidence depends on speed.
  void anchor(float phone_heading_deg, float speed_mps, bool usable_fix,
              float course_accuracy_deg = NAN,
              std::uint64_t now_ms = 0) noexcept {
    if (!usable_fix || !std::isfinite(phone_heading_deg)) return;

    // Starting to move releases the gyro before GNSS course is trustworthy.
    // Coming to a stop resets moving-course lock so restarting at an
    // intersection or roundabout aligns promptly on the first moving fix.
    if (std::isfinite(speed_mps)) {
      if (speed_mps >= kStationaryReleaseSpeedMps) {
        stationary_ = false;
      } else if (speed_mps < kStationarySpeedMps) {
        stationary_ = true;
        has_moving_course_ = false;
        large_error_count_ = 0;
        pending_large_course_ms_ = 0;
      }
    }
    const float phone = normalize(phone_heading_deg);
    const bool moving_course =
        std::isfinite(speed_mps) && speed_mps >= kCourseAnchorSpeedMps;
    if (!has_phone_reference_ && !moving_course) {
      heading_deg_ = phone;  // stationary reference, not moving-course trust
      initialized_ = true;
      has_phone_reference_ = true;
      heading_var_deg2_ = kInitialHeadingVarDeg2;
      return;
    }
    if (!moving_course) {
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
      return;
    }

    const float sigma = std::isfinite(course_accuracy_deg) &&
                            course_accuracy_deg > 0.0F
        ? course_accuracy_deg
        : std::clamp(31.5F - (speed_mps - 1.5F) * 6.5F, 20.0F, 31.5F);
    if (sigma > 60.0F) {
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
      return;  // let the gyro continue without an unusable course
    }
    if (!has_phone_reference_ || !has_moving_course_) {
      if (sigma > 30.0F) return;  // no first snap from noisy startup
      heading_deg_ = phone;
      initialized_ = true;
      has_phone_reference_ = true;
      has_moving_course_ = true;
      heading_var_deg2_ = 100.0F;
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
      return;
    }

    // Only block extreme angular rates (> 90.0 dps) during active maneuvers.
    if (std::abs(filtered_rate_dps_) > 90.0F) return;

    const float error = shortest_delta(phone, heading_deg_);

    // Adaptive outlier gate: require confirmation only when heading uncertainty
    // is low (stable straight line: heading_var_deg2_ < 144.0F). During curves or
    // roundabouts (heading_var_deg2_ >= 144.0F), accumulated gyro uncertainty allows
    // the GPS anchor to correct the heading immediately without delays.
    if (heading_var_deg2_ < 144.0F && std::abs(error) > 35.0F && sigma > 12.0F) {
      const bool expired = now_ms != 0U && pending_large_course_ms_ != 0U &&
          (now_ms < pending_large_course_ms_ ||
           now_ms - pending_large_course_ms_ > kLargeErrorConfirmationTimeoutMs);
      if (large_error_count_ == 0 || expired ||
          std::abs(shortest_delta(phone, pending_large_course_deg_)) > 25.0F) {
        large_error_count_ = 1;
        pending_large_course_deg_ = phone;
        pending_large_course_ms_ = now_ms;
        return;
      }
      large_error_count_ = 2;
      pending_large_course_ms_ = 0;
    } else {
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
    }

    // 2-state adaptive Kalman gain:
    const float gain = std::clamp(
        heading_var_deg2_ / (heading_var_deg2_ + sigma * sigma),
        0.08F, 0.65F);
    heading_deg_ = normalize(heading_deg_ + error * gain);
    heading_var_deg2_ *= (1.0F - gain);

    if (std::abs(filtered_rate_dps_) < 3.0F) {
      bias_dps_ -= error * 0.002F;
      bias_dps_ = std::clamp(bias_dps_, -10.0F, 10.0F);
    }
  }

  // yaw_rate_dps is positive for increasing compass heading (clockwise).
  // Returns true when a new display heading is available.
  bool integrate(float yaw_rate_dps, std::uint64_t sample_ms) noexcept {
    if (!std::isfinite(yaw_rate_dps)) {
      return false;
    }
    if (stationary_) {
      // Learn zero-rate gyro bias only from small stationary readings. Keep
      // the timestamp fresh so resuming does not rely on a huge-gap guard.
      if (sample_ms > last_sample_ms_) {
        last_sample_ms_ = sample_ms;
        if (std::abs(yaw_rate_dps) < 3.0F) {
          bias_dps_ += (yaw_rate_dps - bias_dps_) * 0.002F;
          // Same defensive bound already used by the GPS-course bias update.
          bias_dps_ = std::clamp(bias_dps_, -10.0F, 10.0F);
        }
      }
      filtered_rate_dps_ = 0.0F;
      return false;
    }
    if (!initialized_) {
      // Relative motion remains useful before the first trustworthy course;
      // zero degrees is explicitly just a temporary local reference.
      initialized_ = true;
      heading_deg_ = 0.0F;
      heading_var_deg2_ = kInitialHeadingVarDeg2;
    }
    if (last_sample_ms_ == 0) {
      last_sample_ms_ = sample_ms;
      return false;
    }
    if (sample_ms <= last_sample_ms_) {
      return false;
    }

    const std::uint64_t elapsed_ms = sample_ms - last_sample_ms_;
    last_sample_ms_ = sample_ms;
    if (elapsed_ms > kMaximumIntegrationGapMs) {
      filtered_rate_dps_ = 0.0F;
      return false;
    }

    const float dt_s = static_cast<float>(elapsed_ms) / 1'000.0F;
    const float bounded =
        std::clamp(yaw_rate_dps - bias_dps_, -500.0F, 500.0F);
    // Time-step-aware low-pass filter (~45 ms tau) so RTOS jitter does not skew gyro phase.
    const float alpha = 1.0F - std::exp(-dt_s / 0.045F);
    filtered_rate_dps_ += (bounded - filtered_rate_dps_) * alpha;
    const float active_rate = std::abs(filtered_rate_dps_) < kDeadbandDps
                                  ? 0.0F
                                  : filtered_rate_dps_;

    // Propagate angular variance with an upper bound for long tunnels without GPS.
    const float rate_noise = 0.08F * std::abs(active_rate) * dt_s;
    heading_var_deg2_ = std::min(
        900.0F, heading_var_deg2_ + (rate_noise * rate_noise) + 0.5F * dt_s);

    if (active_rate == 0.0F) {
      return false;
    }
    heading_deg_ = normalize(
        heading_deg_ + active_rate * dt_s);
    return true;
  }

  [[nodiscard]] bool initialized() const noexcept { return initialized_; }
  [[nodiscard]] float heading_deg() const noexcept { return heading_deg_; }
  [[nodiscard]] float heading_var_deg2() const noexcept { return heading_var_deg2_; }

 private:
  static constexpr float kCourseAnchorSpeedMps = 2.0F;
  static constexpr float kStationarySpeedMps = 0.6F;
  static constexpr float kStationaryReleaseSpeedMps = 1.5F;
  static constexpr float kDeadbandDps = 1.1F;
  static constexpr float kInitialHeadingVarDeg2 = 196.0F;
  static constexpr std::uint64_t kMaximumIntegrationGapMs = 160;
  static constexpr std::uint64_t kLargeErrorConfirmationTimeoutMs = 2'500;

  static float normalize(float value) noexcept {
    float result = std::fmod(value, 360.0F);
    if (result < 0.0F) result += 360.0F;
    return result;
  }

  static float shortest_delta(float target, float current) noexcept {
    float delta = normalize(target) - normalize(current);
    if (delta > 180.0F) delta -= 360.0F;
    if (delta < -180.0F) delta += 360.0F;
    return delta;
  }

  bool initialized_ = false;
  bool has_phone_reference_ = false;
  bool has_moving_course_ = false;
  bool stationary_ = false;
  float heading_deg_ = 0.0F;
  float filtered_rate_dps_ = 0.0F;
  float heading_var_deg2_ = kInitialHeadingVarDeg2;
  std::uint64_t last_sample_ms_ = 0;
  float bias_dps_ = 0.0F;
  float pending_large_course_deg_ = 0.0F;
  std::uint64_t pending_large_course_ms_ = 0;
  std::uint8_t large_error_count_ = 0;
};
