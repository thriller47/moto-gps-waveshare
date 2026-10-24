#pragma once
#include <cstddef>
#include <cstdint>
#include "driver/i2s_std.h"
#include "esp_err.h"

// ESP-IDF-only I2S output for SIGNAL's ES8311. No Arduino runtime.
// The codec must be initialized separately before enabling the amplifier.
class SignalAudioOutput {
 public:
  SignalAudioOutput() = default;
  SignalAudioOutput(const SignalAudioOutput&) = delete;
  SignalAudioOutput& operator=(const SignalAudioOutput&) = delete;
  esp_err_t start(std::uint32_t sample_rate_hz = 44100);
  esp_err_t change_rate(std::uint32_t sample_rate_hz);
  esp_err_t write_pcm(const std::int16_t* stereo_samples,
                      std::size_t frame_count,
                      std::size_t* frames_written = nullptr);
  void stop();
  bool active() const { return active_; }
 private:
  i2s_chan_handle_t tx_ = nullptr;
  bool active_ = false;
};
