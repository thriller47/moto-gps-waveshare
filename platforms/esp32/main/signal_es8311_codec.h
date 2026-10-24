#pragma once

#include <cstdint>
#include "driver/i2c_master.h"
#include "esp_err.h"

// ES8311 16-bit DAC-only initialization on the Waveshare 1.75C.
// Uses the EXISTING bsp_i2c_get_handle() bus, shared with CST9217 and AXP2101.
// Not thread-safe: call from the dedicated radio-control/audio task.
class SignalEs8311Codec {
 public:
  esp_err_t start(i2c_master_bus_handle_t shared_bus, std::uint32_t rate_hz);
  esp_err_t set_rate(std::uint32_t rate_hz);
  esp_err_t set_volume(std::uint8_t percent);
  esp_err_t mute(bool enabled);
  void stop();
  bool ready() const noexcept { return ready_; }

 private:
  esp_err_t write_reg(std::uint8_t reg, std::uint8_t value);
  esp_err_t read_reg(std::uint8_t reg, std::uint8_t* value);
  esp_err_t configure_clock(std::uint32_t rate_hz);

  i2c_master_dev_handle_t device_ = nullptr;
  bool ready_ = false;
};
