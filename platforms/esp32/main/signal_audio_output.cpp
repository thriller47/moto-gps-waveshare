#include "signal_audio_output.h"

#include "driver/gpio.h"
#include "esp_log.h"

namespace {
constexpr char kTag[] = "signal_audio";
// Verified against SIGNAL's Waveshare 1.75C config.h.
constexpr gpio_num_t kBclk = GPIO_NUM_9;
constexpr gpio_num_t kWs = GPIO_NUM_45;
constexpr gpio_num_t kDout = GPIO_NUM_8;
constexpr gpio_num_t kMclk = GPIO_NUM_16;
bool valid_rate(std::uint32_t rate) {
  return rate == 22050 || rate == 32000 || rate == 44100 || rate == 48000;
}
}

esp_err_t SignalAudioOutput::start(std::uint32_t rate) {
  if (active_ || tx_ != nullptr) return ESP_ERR_INVALID_STATE;
  if (!valid_rate(rate)) return ESP_ERR_INVALID_ARG;
  i2s_chan_config_t channel = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_AUTO, I2S_ROLE_MASTER);
  esp_err_t err = i2s_new_channel(&channel, &tx_, nullptr);
  if (err != ESP_OK) return err;

  i2s_std_config_t config = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(rate),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
          I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
      .gpio_cfg = {
          .mclk = kMclk,
          .bclk = kBclk,
          .ws = kWs,
          .dout = kDout,
          .din = I2S_GPIO_UNUSED,
          .invert_flags = {},
      },
  };
  config.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_256;
  err = i2s_channel_init_std_mode(tx_, &config);
  if (err == ESP_OK) err = i2s_channel_enable(tx_);
  if (err != ESP_OK) {
    i2s_del_channel(tx_);
    tx_ = nullptr;
    return err;
  }
  active_ = true;
  ESP_LOGI(kTag, "I2S output ready at %lu Hz", static_cast<unsigned long>(rate));
  return ESP_OK;
}

esp_err_t SignalAudioOutput::change_rate(std::uint32_t rate) {
  if (!active_ || tx_ == nullptr) return ESP_ERR_INVALID_STATE;
  if (!valid_rate(rate)) return ESP_ERR_INVALID_ARG;
  esp_err_t err = i2s_channel_disable(tx_);
  if (err != ESP_OK) return err;
  active_ = false;
  i2s_std_clk_config_t clock = I2S_STD_CLK_DEFAULT_CONFIG(rate);
  clock.mclk_multiple = I2S_MCLK_MULTIPLE_256;
  err = i2s_channel_reconfig_std_clock(tx_, &clock);
  if (err == ESP_OK) err = i2s_channel_enable(tx_);
  if (err == ESP_OK) active_ = true;
  return err;
}

esp_err_t SignalAudioOutput::write_pcm(const std::int16_t* pcm,
                                       std::size_t frames,
                                       std::size_t* written) {
  if (written) *written = 0;
  if (!active_ || tx_ == nullptr) return ESP_ERR_INVALID_STATE;
  if (pcm == nullptr || frames == 0) return ESP_ERR_INVALID_ARG;
  std::size_t bytes = 0;
  const esp_err_t err = i2s_channel_write(tx_, pcm,
                                          frames * 2 * sizeof(std::int16_t),
                                          &bytes, 1000);
  if (written) *written = bytes / (2 * sizeof(std::int16_t));
  return err;
}

void SignalAudioOutput::stop() {
  if (tx_ != nullptr) {
    if (active_) i2s_channel_disable(tx_);
    i2s_del_channel(tx_);
    tx_ = nullptr;
  }
  active_ = false;
}
