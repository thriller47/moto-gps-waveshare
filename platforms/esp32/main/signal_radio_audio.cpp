#include "signal_radio_audio.h"

#include "driver/gpio.h"
#include "esp_log.h"
#include "signal_codec_clock.h"

namespace {
constexpr char kTag[] = "signal_radio_audio";
constexpr gpio_num_t kAmpEnable = GPIO_NUM_46;  // Waveshare 1.75C PA, HIGH=ON
void amplifier_off() { (void)gpio_set_level(kAmpEnable, 0); }
}

esp_err_t SignalRadioAudio::start(i2c_master_bus_handle_t shared_i2c,
                                  std::uint32_t hz) {
  if (ready_) return ESP_ERR_INVALID_STATE;
  if (shared_i2c == nullptr || !moto::signal::codec_rate_supported(hz))
    return ESP_ERR_INVALID_ARG;

  // Initialize the PA GPIO low before bringing up I2S/codec to suppress pops.
  gpio_config_t config{};
  config.pin_bit_mask = 1ULL << static_cast<unsigned>(kAmpEnable);
  config.mode = GPIO_MODE_OUTPUT;
  esp_err_t err = gpio_config(&config);
  if (err != ESP_OK) return err;
  amplifier_off();

  err = i2s_.start(hz);   // Clock is running before configuring the codec.
  if (err != ESP_OK) goto fail;
  err = codec_.start(shared_i2c, hz);
  if (err != ESP_OK) goto fail;
  // Codec is initialized muted at 50%. PA stays off until explicit unmute.
  ready_ = true;
  muted_ = true;
  ESP_LOGI(kTag, "audio transport prepared, muted, %lu Hz",
           static_cast<unsigned long>(hz));
  return ESP_OK;

fail:
  ESP_LOGE(kTag, "audio startup failed: %s", esp_err_to_name(err));
  stop();
  return err;
}

esp_err_t SignalRadioAudio::set_rate(std::uint32_t hz) {
  if (!ready_) return ESP_ERR_INVALID_STATE;
  if (!moto::signal::codec_rate_supported(hz)) return ESP_ERR_INVALID_ARG;
  // Quiesce amplifier for a clock transition; the new I2S and codec rates
  // must either BOTH succeed or the complete audio path is shut down.
  const bool restore_playback = !muted_;
  amplifier_off();
  esp_err_t err = codec_.mute(true);
  if (err == ESP_OK) err = i2s_.change_rate(hz);
  if (err == ESP_OK) err = codec_.set_rate(hz);
  if (err == ESP_OK && restore_playback) err = codec_.mute(false);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "rate switch failed (%lu Hz): %s",
             static_cast<unsigned long>(hz), esp_err_to_name(err));
    stop();
    return err;
  }
  if (restore_playback) (void)gpio_set_level(kAmpEnable, 1);
  ESP_LOGI(kTag, "audio/codec synchronized at %lu Hz", static_cast<unsigned long>(hz));
  return ESP_OK;
}

esp_err_t SignalRadioAudio::set_mute(bool muted) {
  if (!ready_) return ESP_ERR_INVALID_STATE;
  if (muted) amplifier_off();
  const esp_err_t err = codec_.mute(muted);
  if (err != ESP_OK) {
    amplifier_off();
    stop();
    return err;
  }
  muted_ = muted;
  if (!muted) (void)gpio_set_level(kAmpEnable, 1);
  return ESP_OK;
}

esp_err_t SignalRadioAudio::set_volume(std::uint8_t percent) {
  if (!ready_) return ESP_ERR_INVALID_STATE;
  return codec_.set_volume(percent);
}

esp_err_t SignalRadioAudio::write_pcm(const std::int16_t* samples,
                                      std::size_t frames,
                                      std::size_t* written) {
  if (!ready_) return ESP_ERR_INVALID_STATE;
  return i2s_.write_pcm(samples, frames, written);
}

void SignalRadioAudio::stop() {
  // PA off BEFORE stopping PCM clock or modifying codec configuration.
  amplifier_off();
  codec_.stop();
  i2s_.stop();
  ready_ = false;
  muted_ = true;
}
