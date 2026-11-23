#include "motion_heading_sensor.h"
#include "motion_heading_axis.hpp"

#include <algorithm>
#include <cmath>

#include "bsp/esp-bsp.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// The Waveshare qmi8658 header defines M_PI unconditionally. ESP-IDF 6.1
// may already define it in <cmath>, so remove that definition before including
// the vendor header, then avoid leaking the vendor macro into our C++ code.
#ifdef M_PI
#undef M_PI
#endif
#include "qmi8658.h"
#ifdef M_PI
#undef M_PI
#endif

namespace {
constexpr char kTag[] = "motion_heading";
constexpr TickType_t kSampleDelay = pdMS_TO_TICKS(8);  // 125 Hz target.
constexpr int kCalibrationSamples = 120;
constexpr std::uint64_t kCalibrationDeadlineMs = 4'000;
constexpr float kMinimumGravity = 6.8F;
constexpr float kMaximumGravity = 12.8F;

constexpr float kGravityMinNorm = 9.36F;   // 9.81F - 0.45F
constexpr float kGravityMaxNorm = 10.26F;  // 9.81F + 0.45F
constexpr float kMaxDynamicGyroRateDps = 4.0F;
constexpr float kGravityAlpha = 0.002F;  // ~4s time constant at 125 Hz to resist gentle accel/brake tilt.

struct GravityEstimate {
  float cal_ax = 0.0F;
  float cal_ay = 0.0F;
  float cal_az = 1.0F;
  bool initialized = false;
};

std::uint64_t monotonic_ms() {
  return static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
}

float magnitude(float x, float y, float z) {
  return std::sqrt(x * x + y * y + z * z);
}

bool update_gravity(GravityEstimate& gravity, const qmi8658_data_t& sample,
                    float blend) {
  const float accel_norm = magnitude(sample.accelX, sample.accelY, sample.accelZ);
  const float gyro_norm = magnitude(sample.gyroX, sample.gyroY, sample.gyroZ);
  if (!std::isfinite(accel_norm) || !std::isfinite(gyro_norm)) {
    return gravity.initialized;
  }
  if (!gravity.initialized) {
    if (accel_norm >= kMinimumGravity && accel_norm <= kMaximumGravity) {
      gravity.cal_ax = sample.accelX;
      gravity.cal_ay = sample.accelY;
      gravity.cal_az = sample.accelZ;
      gravity.initialized = true;
    }
    return gravity.initialized;
  }
  // Update calibrated mounting gravity only when the vehicle is not subject to
  // dynamic accelerations (braking, accelerating in traffic, or centrifugal forces).
  if (accel_norm >= kGravityMinNorm && accel_norm <= kGravityMaxNorm &&
      gyro_norm < kMaxDynamicGyroRateDps) {
    gravity.cal_ax += (sample.accelX - gravity.cal_ax) * blend;
    gravity.cal_ay += (sample.accelY - gravity.cal_ay) * blend;
    gravity.cal_az += (sample.accelZ - gravity.cal_az) * blend;
  }
  return true;
}

// Project angular velocity onto the board's calibrated gravity axis so a moderately
// tilted or forward-facing mount still responds to a turn around vertical.
float clockwise_heading_rate(const GravityEstimate& gravity,
                             const qmi8658_data_t& sample) {
  if (!gravity.initialized) return 0.0F;
  return clockwise_gravity_heading_rate(
      gravity.cal_ax, gravity.cal_ay, gravity.cal_az,
      sample.gyroX, sample.gyroY, sample.gyroZ);
}
}  // namespace

esp_err_t MotionHeadingSensor::start(SampleCallback callback, void* context) {
  if (callback == nullptr) return ESP_ERR_INVALID_ARG;
  if (started_) return ESP_ERR_INVALID_STATE;
  callback_ = callback;
  callback_context_ = context;
  // Sensor callbacks reach the presenter and LVGL adapter. Keep enough margin
  // for those nested frames even though the large map snapshot now remains in
  // PhoneNavBridge member storage.
  if (xTaskCreate(task_entry, "moto_qmi_heading", 8'192, this, 3, nullptr) !=
      pdPASS) {
    callback_ = nullptr;
    callback_context_ = nullptr;
    return ESP_ERR_NO_MEM;
  }
  started_ = true;
  return ESP_OK;
}

void MotionHeadingSensor::task_entry(void* context) {
  static_cast<MotionHeadingSensor*>(context)->run();
  vTaskDelete(nullptr);
}

esp_err_t MotionHeadingSensor::run() {
  qmi8658_dev_t device{};
  // Explicitly handle an I2C bus that has not yet been initialized.
  if (bsp_i2c_get_handle() == nullptr) {
    ESP_RETURN_ON_ERROR(bsp_i2c_init(), kTag, "bsp_i2c_init failed");
  }
  const esp_err_t init = qmi8658_init(
      &device, bsp_i2c_get_handle(), QMI8658_ADDRESS_HIGH);
  if (init != ESP_OK) {
    ESP_LOGW(kTag, "QMI8658 unavailable; using phone course only: %s",
             esp_err_to_name(init));
    return init;
  }
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_range(&device, QMI8658_ACCEL_RANGE_4G));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_accel_odr(&device, QMI8658_ACCEL_ODR_125HZ));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_range(&device, QMI8658_GYRO_RANGE_512DPS));
  ESP_ERROR_CHECK_WITHOUT_ABORT(
      qmi8658_set_gyro_odr(&device, QMI8658_GYRO_ODR_125HZ));
  qmi8658_set_accel_unit_mps2(&device, true);
  qmi8658_set_gyro_unit_dps(&device, true);

  GravityEstimate gravity;
  float bias_sum = 0.0F;
  int stable_samples = 0;
  const std::uint64_t calibration_start = monotonic_ms();
  qmi8658_data_t data{};
  TickType_t next_sample_tick = xTaskGetTickCount();
  while (stable_samples < kCalibrationSamples &&
         monotonic_ms() - calibration_start < kCalibrationDeadlineMs) {
    bool ready = false;
    if (qmi8658_is_data_ready(&device, &ready) == ESP_OK && ready &&
        qmi8658_read_sensor_data(&device, &data) == ESP_OK) {
      update_gravity(gravity, data, 0.08F);
      const float projected = clockwise_heading_rate(gravity, data);
      const float angular = magnitude(data.gyroX, data.gyroY, data.gyroZ);
      if (std::isfinite(projected) && angular < 4.0F) {
        bias_sum += projected;
        ++stable_samples;
      } else {
        bias_sum = 0.0F;
        stable_samples = 0;
      }
    }
    xTaskDelayUntil(&next_sample_tick, kSampleDelay);
  }

  // Require at least 40 stable samples (~320 ms) before trusting cold-start bias.
  float bias_dps = stable_samples >= 40 ? bias_sum / stable_samples : 0.0F;
  ESP_LOGI(kTag,
           "QMI8658 yaw assist ready (bias %.3f dps, %d stable samples)",
           static_cast<double>(bias_dps), stable_samples);

  int consecutive_failures = 0;
  next_sample_tick = xTaskGetTickCount();
  while (true) {
    if (coffee_paused_.load(std::memory_order_relaxed)) {
      vTaskDelay(pdMS_TO_TICKS(250));
      // vTaskDelayUntil requires a fresh reference after a long pause.
      next_sample_tick = xTaskGetTickCount();
      continue;
    }
    bool ready = false;
    esp_err_t result = qmi8658_is_data_ready(&device, &ready);
    if (result == ESP_OK && ready) {
      result = qmi8658_read_sensor_data(&device, &data);
      if (result == ESP_OK) {
        consecutive_failures = 0;
        update_gravity(gravity, data, kGravityAlpha);
        const float raw_rate = clockwise_heading_rate(gravity, data);
        // Continuous runtime bias adaptation is owned solely by MotionHeadingFusion
        // to avoid two cascaded integrators fighting each other.
        const float corrected_rate = raw_rate - bias_dps;
        callback_(corrected_rate, monotonic_ms(), callback_context_);
      }
    }
    if (result != ESP_OK) {
      ++consecutive_failures;
      if (consecutive_failures == 25) {
        ESP_LOGW(kTag, "QMI8658 read errors continue: %s; attempting re-init",
                 esp_err_to_name(result));
        if (qmi8658_init(&device, bsp_i2c_get_handle(), QMI8658_ADDRESS_HIGH) == ESP_OK) {
          qmi8658_set_accel_range(&device, QMI8658_ACCEL_RANGE_4G);
          qmi8658_set_accel_odr(&device, QMI8658_ACCEL_ODR_125HZ);
          qmi8658_set_gyro_range(&device, QMI8658_GYRO_RANGE_512DPS);
          qmi8658_set_gyro_odr(&device, QMI8658_GYRO_ODR_125HZ);
          qmi8658_set_accel_unit_mps2(&device, true);
          qmi8658_set_gyro_unit_dps(&device, true);
          consecutive_failures = 0;
        }
      }
      if (consecutive_failures >= 25) {
        vTaskDelay(pdMS_TO_TICKS(250));
        next_sample_tick = xTaskGetTickCount();
        continue;
      }
    }
    xTaskDelayUntil(&next_sample_tick, kSampleDelay);
  }
}
