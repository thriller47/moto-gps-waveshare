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
    // Match Android's >=1.6-1.8m/s fresh-course policy; below that the phone
    // may transmit a previously held bearing, not a new measured course.
    const bool moving_course =
        std::isfinite(speed_mps) && speed_mps >= kCourseAnchorSpeedMps;
    if (!has_phone_reference_ && !moving_course) {
      heading_deg_ = phone;  // stationary reference, not moving-course trust
      initialized_ = true;
      has_phone_reference_ = true;
      return;
    }
    if (!moving_course) {
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
      return;
    }
    // Without true bearing accuracy over BLE, estimate angular sigma from
    // speed: <=30 deg at >=1.8 m/s for restart/startup lock, floored at 20 deg
    // at cruising speed to avoid over-reacting to straight-line GNSS spikes.
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
      large_error_count_ = 0;
      pending_large_course_ms_ = 0;
      return;
    }
    // The phone course lags rapid gyro turns. Do not correct during yaw.
    if (std::abs(filtered_rate_dps_) > 5.0F) return;
    const float error = shortest_delta(phone, heading_deg_);
    // A single large jump must never rotate the map. Require two mutually
    // consistent phone courses (no fast 0.82 branch exists in current code).
    // Threshold set to 35 deg so 40 deg GNSS spikes on straight roads are confirmed
    // rather than accepted immediately without gyro confirmation.
    // The confirmation candidate expires if not corroborated within 2,500 ms.
    if (std::abs(error) > 35.0F && sigma > 12.0F) {
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
    // Scalar complementary/Kalman-like observation weight; not a full
    // covariance-propagating Kalman filter.
    const float gain = kGyroSigmaDeg * kGyroSigmaDeg /
                       (kGyroSigmaDeg * kGyroSigmaDeg + sigma * sigma);
    heading_deg_ = normalize(heading_deg_ + error * gain);
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

    const float bounded =
        std::clamp(yaw_rate_dps - bias_dps_, -500.0F, 500.0F);
    // About 20 ms of smoothing at the 125 Hz sensor rate: vibration is
    // suppressed without the sluggish response of phone-only course updates.
    filtered_rate_dps_ += (bounded - filtered_rate_dps_) * 0.15F;
    const float active_rate = std::abs(filtered_rate_dps_) < kDeadbandDps
                                  ? 0.0F
                                  : filtered_rate_dps_;
    if (active_rate == 0.0F) {
      return false;
    }
    heading_deg_ = normalize(
        heading_deg_ + active_rate * static_cast<float>(elapsed_ms) / 1'000.0F);
    return true;
  }

  [[nodiscard]] bool initialized() const noexcept { return initialized_; }
  [[nodiscard]] float heading_deg() const noexcept { return heading_deg_; }

 private:
  static constexpr float kCourseAnchorSpeedMps = 1.8F;
  static constexpr float kStationarySpeedMps = 0.6F;
  static constexpr float kStationaryReleaseSpeedMps = 1.0F;
  static constexpr float kDeadbandDps = 0.3F;
  static constexpr float kGyroSigmaDeg = 14.0F;
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
  std::uint64_t last_sample_ms_ = 0;
  float bias_dps_ = 0.0F;
  float pending_large_course_deg_ = 0.0F;
  std::uint64_t pending_large_course_ms_ = 0;
  std::uint8_t large_error_count_ = 0;
};