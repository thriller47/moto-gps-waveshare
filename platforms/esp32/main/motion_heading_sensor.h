#pragma once

#include <atomic>
#include <cstdint>

#include "esp_err.h"

class MotionHeadingSensor {
 public:
  using SampleCallback = void (*)(float heading_rate_dps,
                                  std::uint64_t sample_ms,
                                  void* context);

  MotionHeadingSensor() = default;
  MotionHeadingSensor(const MotionHeadingSensor&) = delete;
  MotionHeadingSensor& operator=(const MotionHeadingSensor&) = delete;

  // Starts one low-priority 125 Hz sensor task. Failure is non-fatal: phone
  // course navigation continues to work without the local latency filler.
  esp_err_t start(SampleCallback callback, void* context);
  void set_coffee_paused(bool paused) noexcept {
    coffee_paused_.store(paused, std::memory_order_relaxed);
  }

 private:
  static void task_entry(void* context);
  esp_err_t run();

  SampleCallback callback_ = nullptr;
  void* callback_context_ = nullptr;
  bool started_ = false;
  std::atomic<bool> coffee_paused_{false};
};
