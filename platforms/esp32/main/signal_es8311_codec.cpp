/*
 * SPDX-FileCopyrightText: 2026 Moto GPS contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Register startup sequence adapted from Espressif Systems' Apache-2.0
 * ES8311 driver, as distributed with zhitianfu/signal-internet-radio.
 * I2C transport is ESP-IDF 5.x's shared master-bus API, not Arduino Wire.
 */
#include "signal_es8311_codec.h"

#include "signal_codec_clock.h"
#include "esp_log.h"

namespace {
constexpr char kTag[] = "signal_es8311";
constexpr std::uint16_t kAddress = 0x18;  // ES8311 CE low, Waveshare 1.75C
constexpr int kI2cTimeoutMs = 100;

// The four supported source rates share these exact coefficients in
// Espressif's ES8311 table at MCLK=256*fs:
// pre_div=1, pre_multi=0, adc_div=dac_div=1, fs_mode=0,
// lrck_h=0, lrck_l=255, bclk_div=4, adc_osr=dac_osr=16.
struct RegisterSetting {
  std::uint8_t address;
  std::uint8_t value;
};
constexpr RegisterSetting kPowerUp[] = {
    {0x0D, 0x01}, {0x0E, 0x02}, {0x12, 0x00},
    {0x13, 0x10}, {0x1C, 0x6A}, {0x37, 0x08},
};
}

esp_err_t SignalEs8311Codec::write_reg(std::uint8_t reg, std::uint8_t value) {
  if (device_ == nullptr) return ESP_ERR_INVALID_STATE;
  const std::uint8_t data[2] = {reg, value};
  return i2c_master_transmit(device_, data, sizeof(data), kI2cTimeoutMs);
}

esp_err_t SignalEs8311Codec::read_reg(std::uint8_t reg, std::uint8_t* value) {
  if (device_ == nullptr || value == nullptr) return ESP_ERR_INVALID_STATE;
  return i2c_master_transmit_receive(device_, &reg, 1, value, 1, kI2cTimeoutMs);
}

esp_err_t SignalEs8311Codec::configure_clock(std::uint32_t rate_hz) {
  if (!moto::signal::codec_rate_supported(rate_hz)) return ESP_ERR_INVALID_ARG;
  // Reg 01: enable all codec clocks, select MCLK pin; no clock inversion.
  esp_err_t err = write_reg(0x01, 0x3F);
  if (err != ESP_OK) return err;

  std::uint8_t value = 0;
  err = read_reg(0x02, &value);
  if (err != ESP_OK) return err;
  // pre-div 1 / multiplier 1, preserve unrelated low clock control bits.
  err = write_reg(0x02, value & 0x07);
  if (err != ESP_OK) return err;
  constexpr RegisterSetting clocks[] = {
      {0x03, 0x10}, {0x04, 0x10}, {0x05, 0x00},
  };
  for (const auto setting : clocks) {
    err = write_reg(setting.address, setting.value);
    if (err != ESP_OK) return err;
  }
  err = read_reg(0x06, &value);
  if (err != ESP_OK) return err;
  // BCLK divider 4, leave upper clock bits, clear BCLK inversion.
  err = write_reg(0x06, static_cast<std::uint8_t>((value & 0xC0) | 0x03));
  if (err != ESP_OK) return err;
  err = read_reg(0x07, &value);
  if (err != ESP_OK) return err;
  err = write_reg(0x07, value & 0xC0);
  if (err != ESP_OK) return err;
  return write_reg(0x08, 0xFF);
}

esp_err_t SignalEs8311Codec::start(i2c_master_bus_handle_t bus,
                                   std::uint32_t rate_hz) {
  if (ready_ || device_ != nullptr) return ESP_ERR_INVALID_STATE;
  if (bus == nullptr || !moto::signal::codec_rate_supported(rate_hz))
    return ESP_ERR_INVALID_ARG;

  i2c_device_config_t config{};
  config.dev_addr_length = I2C_ADDR_BIT_LEN_7;
  config.device_address = kAddress;
  config.scl_speed_hz = 400'000;
  esp_err_t err = i2c_master_bus_add_device(bus, &config, &device_);
  if (err != ESP_OK) return err;

  std::uint8_t chip_id = 0;
  err = read_reg(0xFD, &chip_id);
  if (err != ESP_OK) goto fail;
  ESP_LOGI(kTag, "ES8311 identified on shared I2C 0x18; ID1=0x%02x", chip_id);

  // Reset/power-on from Espressif ES8311 init; no amplifier is enabled here.
  for (const std::uint8_t value : {std::uint8_t{0x1F},
                                    std::uint8_t{0x00},
                                    std::uint8_t{0x80}}) {
    err = write_reg(0x00, value);
    if (err != ESP_OK) goto fail;
  }
  err = configure_clock(rate_hz);
  if (err != ESP_OK) goto fail;
  // Slave I2S serial ports, 16 bits (resolution code 3 in bits 4:2).
  err = write_reg(0x00, 0x80 & 0xBF);
  if (err != ESP_OK) goto fail;
  err = write_reg(0x09, 0x0C);
  if (err != ESP_OK) goto fail;
  err = write_reg(0x0A, 0x0C);
  if (err != ESP_OK) goto fail;
  for (const auto setting : kPowerUp) {
    err = write_reg(setting.address, setting.value);
    if (err != ESP_OK) goto fail;
  }
  // Initialize muted and at a conservative volume. PA is owned by the
  // radio-audio coordinator and remains OFF until I2S/codec both succeed.
  err = set_volume(50);
  if (err != ESP_OK) goto fail;
  err = mute(true);
  if (err != ESP_OK) goto fail;
  ready_ = true;
  ESP_LOGI(kTag, "codec initialized, muted, rate=%lu MCLK=%lu",
           static_cast<unsigned long>(rate_hz),
           static_cast<unsigned long>(moto::signal::codec_mclk_hz(rate_hz)));
  return ESP_OK;

fail:
  ESP_LOGE(kTag, "codec init failed: %s", esp_err_to_name(err));
  stop();
  return err;
}

esp_err_t SignalEs8311Codec::set_rate(std::uint32_t rate_hz) {
  if (!ready_) return ESP_ERR_INVALID_STATE;
  if (!moto::signal::codec_rate_supported(rate_hz)) return ESP_ERR_INVALID_ARG;
  return configure_clock(rate_hz);
}

esp_err_t SignalEs8311Codec::set_volume(std::uint8_t percent) {
  if (device_ == nullptr) return ESP_ERR_INVALID_STATE;
  if (percent > 100) return ESP_ERR_INVALID_ARG;
  const std::uint8_t reg = percent == 0
      ? 0
      : static_cast<std::uint8_t>((static_cast<unsigned>(percent) * 256U / 100U) - 1U);
  return write_reg(0x32, reg);
}

esp_err_t SignalEs8311Codec::mute(bool enabled) {
  if (device_ == nullptr) return ESP_ERR_INVALID_STATE;
  std::uint8_t value = 0;
  esp_err_t err = read_reg(0x31, &value);
  if (err != ESP_OK) return err;
  const std::uint8_t mask = 0x60;
  return write_reg(0x31, enabled ? (value | mask) : (value & ~mask));
}

void SignalEs8311Codec::stop() {
  if (device_ != nullptr) {
    (void)mute(true);
    (void)write_reg(0x12, 0x01);  // power down DAC
    const esp_err_t err = i2c_master_bus_rm_device(device_);
    if (err != ESP_OK) {
      ESP_LOGW(kTag, "codec I2C remove failed: %s", esp_err_to_name(err));
    }
    device_ = nullptr;
  }
  ready_ = false;
}
