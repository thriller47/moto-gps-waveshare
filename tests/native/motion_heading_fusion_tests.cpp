#include "motion_heading_fusion.hpp"
#include "motion_heading_axis.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>

namespace {

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

void feed_rate(MotionHeadingFusion& fusion, float rate_dps,
               std::uint64_t start_ms, std::uint64_t end_ms) {
  for (std::uint64_t time = start_ms; time <= end_ms; time += 8) {
    fusion.integrate(rate_dps, time);
  }
}

void test_fast_gyro_turn_and_wrap() {
  MotionHeadingFusion fusion;
  fusion.anchor(350.0F, 8.0F, true);
  feed_rate(fusion, 90.0F, 8, 248);
  CHECK(fusion.heading_deg() > 5.0F);
  CHECK(fusion.heading_deg() < 20.0F);
}

void test_stationary_phone_course_does_not_drag_handlebar_heading() {
  MotionHeadingFusion fusion;
  fusion.anchor(12.0F, 0.0F, true);
  fusion.anchor(210.0F, 0.2F, true);
  CHECK(std::abs(fusion.heading_deg() - 12.0F) < 0.01F);
}

void test_moving_phone_course_corrects_gyro_drift_progressively() {
  MotionHeadingFusion fusion;
  fusion.anchor(10.0F, 8.0F, true);
  // Large GNSS error is rejected once, then accepted when consistent.
  fusion.anchor(110.0F, 10.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 10.0F) < 0.01F);
  fusion.anchor(111.0F, 10.0F, true);
  CHECK(fusion.heading_deg() > 30.0F);
  CHECK(fusion.heading_deg() < 32.0F);
}

void test_active_turn_does_not_snap_toward_delayed_phone_course() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 15.0F, true);
  feed_rate(fusion, 90.0F, 8, 248);
  const float during_turn = fusion.heading_deg();
  fusion.anchor(90.0F, 15.0F, true);  // delayed course, still 90
  CHECK(std::abs(fusion.heading_deg() - during_turn) < 0.01F);
  feed_rate(fusion, 0.0F, 256, 856);
  fusion.anchor(90.0F, 15.0F, true);
  CHECK(fusion.heading_deg() < during_turn);
}

void test_first_phone_course_replaces_gyro_local_zero() {
  MotionHeadingFusion fusion;
  feed_rate(fusion, 90.0F, 8, 248);
  fusion.anchor(235.0F, 0.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 235.0F) < 0.01F);
  // The first moving fix must also replace an old stationary heading in one
  // update instead of leaving the selected road behind the fixed rider arrow.
  fusion.anchor(90.0F, 8.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 90.0F) < 0.01F);
  fusion.reset();
  feed_rate(fusion, -90.0F, 8, 248);
  fusion.anchor(180.0F, 12.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 180.0F) < 0.01F);
}

void test_startup_heading_fast_and_stationary_hold() {
  MotionHeadingFusion fusion;
  fusion.anchor(20.0F, 0.0F, true);
  fusion.anchor(110.0F, 8.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 110.0F) < 0.01F);
  fusion.anchor(120.0F, 8.0F, true);
  CHECK(fusion.heading_deg() > 111.0F);
  CHECK(fusion.heading_deg() < 113.0F);
  const float corrected = fusion.heading_deg();
  fusion.anchor(250.0F, 0.0F, true);
  CHECK(std::abs(fusion.heading_deg() - corrected) < 0.01F);
}

void test_unusable_course_never_anchors() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 12.0F, false);
  CHECK(!fusion.initialized());
  fusion.anchor(12.0F, 12.0F, true);
  fusion.anchor(180.0F, 12.0F, false);
  CHECK(std::abs(fusion.heading_deg() - 12.0F) < 0.01F);
}

void test_forward_facing_mount_yaw_does_not_reverse_at_zero_z() {
  const float front_tilt = clockwise_gravity_heading_rate(
      0.0F, 9.81F, 0.01F, 0.0F, -90.0F, 0.0F);
  const float back_tilt = clockwise_gravity_heading_rate(
      0.0F, 9.81F, -0.01F, 0.0F, -90.0F, 0.0F);
  CHECK(std::abs(front_tilt - 90.0F) < 0.01F);
  CHECK(std::abs(front_tilt - back_tilt) < 0.01F);
  // Rotating the mounting orientation leaves compass clockwise positive.
  CHECK(std::abs(clockwise_gravity_heading_rate(
      0.0F, 0.0F, 9.81F, 0.0F, 0.0F, -90.0F) - 90.0F) < 0.01F);
  CHECK(std::abs(clockwise_gravity_heading_rate(
      0.0F, 0.0F, -9.81F, 0.0F, 0.0F, 90.0F) - 90.0F) < 0.01F);
}

void test_large_sensor_gap_is_not_integrated() {
  MotionHeadingFusion fusion;
  fusion.anchor(42.0F, 5.0F, true);
  fusion.integrate(180.0F, 10);
  CHECK(!fusion.integrate(180.0F, 1'000));
  CHECK(std::abs(fusion.heading_deg() - 42.0F) < 0.01F);
}

float heading_with_optional_stale_sample(bool include_stale_sample) {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 8.0F, true);
  for (std::uint64_t time = 8; time <= 248; time += 8) {
    fusion.integrate(90.0F, time);
    if (include_stale_sample && time == 120) {
      fusion.integrate(90.0F, 116);
    }
  }
  return fusion.heading_deg();
}

void test_stale_timestamp_does_not_rewind_integration_clock() {
  const float clean = heading_with_optional_stale_sample(false);
  const float with_stale_sample = heading_with_optional_stale_sample(true);
  CHECK(std::abs(clean - with_stale_sample) < 0.01F);
}

void test_stationary_freeze_holds_heading_until_riding_resumes() {
  MotionHeadingFusion fusion;
  fusion.anchor(30.0F, 8.0F, true);
  // Parking: low-speed fixes engage the stationary freeze with hysteresis.
  fusion.anchor(30.0F, 0.4F, true);
  feed_rate(fusion, 90.0F, 8, 2'008);
  CHECK(std::abs(fusion.heading_deg() - 30.0F) < 0.01F);
  // 0.6..1.0m/s holds the prior stationary state.
  fusion.anchor(30.0F, 0.8F, true);
  feed_rate(fusion, 90.0F, 2'016, 4'016);
  CHECK(std::abs(fusion.heading_deg() - 30.0F) < 0.01F);
  // IMU resumes from 1m/s, but stale phone course remains ignored below 3.
  fusion.anchor(220.0F, 1.0F, true);
  feed_rate(fusion, 90.0F, 4'024, 4'424);
  CHECK(fusion.heading_deg() > 45.0F);
  CHECK(fusion.heading_deg() < 80.0F);
  // reset() must also clear the freeze.
  fusion.reset();
  feed_rate(fusion, 90.0F, 8, 248);
  CHECK(fusion.heading_deg() > 5.0F);
}


void test_quality_weight_and_outlier_confirmation() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 10.0F, true, 8.0F);
  fusion.anchor(120.0F, 10.0F, true, 60.0F);
  const float uncertain = fusion.heading_deg();
  CHECK(uncertain > 90.0F && uncertain < 92.0F);
  fusion.anchor(120.0F, 10.0F, true, 8.0F);
  CHECK(fusion.heading_deg() > uncertain + 12.0F);
  const float good = fusion.heading_deg();
  fusion.anchor(260.0F, 6.0F, true, 40.0F);
  CHECK(std::abs(fusion.heading_deg() - good) < 0.01F);
  fusion.anchor(259.0F, 6.0F, true, 40.0F);
  CHECK(std::abs(fusion.heading_deg() - good) > 1.0F);
}

void test_large_outlier_confirmation_expires_after_timeout() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 10.0F, true, 8.0F, 1'000);
  const float baseline = fusion.heading_deg();
  // First large outlier at t=1,000ms: rejected as single outlier
  fusion.anchor(180.0F, 10.0F, true, 40.0F, 1'000);
  CHECK(std::abs(fusion.heading_deg() - baseline) < 0.01F);
  // Second outlier arrives at t=4,000ms (> 2,500ms later): the first candidate has expired,
  // so this second fix is treated as a new first candidate, NOT confirmation!
  fusion.anchor(180.0F, 10.0F, true, 40.0F, 4'000);
  CHECK(std::abs(fusion.heading_deg() - baseline) < 0.01F);
  // Third outlier arrives at t=4,500ms (<= 2,500ms after t=4,000ms): now confirmed!
  fusion.anchor(180.0F, 10.0F, true, 40.0F, 4'500);
  CHECK(std::abs(fusion.heading_deg() - baseline) > 1.0F);
}
void test_low_confidence_initial_course_and_held_phone_heading() {
  MotionHeadingFusion fusion;
  fusion.anchor(15.0F, 0.0F, true);
  fusion.anchor(185.0F, 2.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 15.0F) < 0.01F);
  fusion.anchor(185.0F, 3.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 15.0F) < 0.01F);
  fusion.anchor(185.0F, 8.0F, true);
  CHECK(std::abs(fusion.heading_deg() - 185.0F) < 0.01F);
}
void test_bias_learning_while_parked_and_persistence_after_reset() {
  MotionHeadingFusion fusion;
  fusion.anchor(90.0F, 0.0F, true);
  feed_rate(fusion, 1.0F, 8, 12'008);  // 12s at 125Hz
  CHECK(std::abs(fusion.heading_deg() - 90.0F) < 0.01F);
  fusion.anchor(90.0F, 2.0F, true); // release IMU, not course
  feed_rate(fusion, 1.0F, 12'016, 14'016);
  const float after_riding = fusion.heading_deg();
  CHECK(std::abs(after_riding - 90.0F) < 0.7F);
  fusion.reset(); // bias is retained across reconnection
  fusion.anchor(90.0F, 2.0F, true);
  feed_rate(fusion, 1.0F, 14'024, 16'024);
  CHECK(std::abs(fusion.heading_deg() - 90.0F) < 0.7F);
}

}  // namespace

int main() {
  test_fast_gyro_turn_and_wrap();
  test_active_turn_does_not_snap_toward_delayed_phone_course();
  test_stationary_phone_course_does_not_drag_handlebar_heading();
  test_moving_phone_course_corrects_gyro_drift_progressively();
  test_first_phone_course_replaces_gyro_local_zero();
  test_startup_heading_fast_and_stationary_hold();
  test_unusable_course_never_anchors();
  test_forward_facing_mount_yaw_does_not_reverse_at_zero_z();
  test_large_sensor_gap_is_not_integrated();
  test_stale_timestamp_does_not_rewind_integration_clock();
  test_stationary_freeze_holds_heading_until_riding_resumes();
  test_quality_weight_and_outlier_confirmation();
  test_large_outlier_confirmation_expires_after_timeout();
  test_low_confidence_initial_course_and_held_phone_heading();
  test_bias_learning_while_parked_and_persistence_after_reset();

  if (failures != 0) {
    std::cerr << failures << " motion heading checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All motion heading checks passed\n";
  return EXIT_SUCCESS;
}
