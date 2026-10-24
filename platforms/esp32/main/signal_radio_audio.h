#pragma once
#include <cstddef>
#include <cstdint>
#include "driver/i2c_master.h"
#include "esp_err.h"
#include "signal_audio_output.h"
#include "signal_es8311_codec.h"

// Single-owner audio lifecycle for the SIGNAL experiment.
// The caller must serialize these calls (one radio worker/task).
// MUST start only AFTER board_port_init() has created the BSP I2C bus.
// Does not run automatically: no Wi-Fi, streaming or UI yet.
class SignalRadioAudio {
 public:
  esp_err_t start(i2c_master_bus_handle_t bsp_shared_i2c,
                  std::uint32_t sample_rate_hz = 44100);
  esp_err_t set_rate(std::uint32_t sample_rate_hz);
  esp_err_t set_mute(bool muted);
  esp_err_t set_volume(std::uint8_t percent);
  esp_err_t write_pcm(const std::int16_t* samples, std::size_t stereo_frames,
                      std::size_t* frames_written = nullptr);
  void stop();

  bool ready() const noexcept { return ready_; }
  bool muted() const noexcept { return muted_; }

 private:
  SignalAudioOutput i2s_{};
  SignalEs8311Codec codec_{};
  bool ready_ = false;
  bool muted_ = true;
};
