#include <cstdint>
#include "esp_codec_dev.h"
#include "driver/i2s_std.h"
#include "bsp/esp-bsp.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace {
constexpr char kTag[] = "signal_test";
constexpr unsigned kSampleRate = 44100;
constexpr unsigned kFramesPerBuffer = 256;
constexpr unsigned kNoteDurationMs = 420;
constexpr unsigned kFadeMs = 35;
constexpr std::int32_t kPeakPcm = 6500;  // Under 20% of full-scale PCM.
constexpr std::uint8_t kCodecVolume = 22;  // Still conservative.
constexpr unsigned kNotesHz[] = {523, 659, 784};  // C5 E5 G5

// Runs once at boot on the experimental branch only. Each note has a
// 35 ms attack/release envelope to suppress loud switching clicks.
void tone_task(void*) {
  ESP_LOGI(kTag, "Starting three-note ES8311 speaker melody (volume=%u%%)",
           static_cast<unsigned>(kCodecVolume));
  // Official Waveshare path: reuse BSP I2C (already initialized by the UI),
  // let BSP own I2S and use esp_codec_dev for ES8311 + PA lifecycle.
  i2s_std_config_t i2s_cfg = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(kSampleRate),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
          I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
      .gpio_cfg = {
          .mclk = BSP_I2S_MCLK,
          .bclk = BSP_I2S_SCLK,
          .ws = BSP_I2S_LCLK,
          .dout = BSP_I2S_DOUT,
          .din = BSP_I2S_DSIN,
          .invert_flags = {},
      },
  };
  i2s_cfg.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_256;
  esp_err_t err = bsp_audio_init(&i2s_cfg);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "Waveshare BSP I2S initialization failed: %s",
             esp_err_to_name(err));
    vTaskDelete(nullptr);
    return;
  }
  esp_codec_dev_handle_t speaker = bsp_audio_codec_speaker_init();
  if (speaker == nullptr) {
    ESP_LOGE(kTag, "Waveshare BSP speaker codec init failed");
    vTaskDelete(nullptr);
    return;
  }
  esp_codec_dev_sample_info_t fs{};
  fs.sample_rate = kSampleRate;
  fs.channel = 2;
  fs.bits_per_sample = 16;
  int codec_err = esp_codec_dev_open(speaker, &fs);
  if (codec_err != ESP_CODEC_DEV_OK) {
    ESP_LOGE(kTag, "Official codec open failed: %d", codec_err);
    esp_codec_dev_delete(speaker);
    vTaskDelete(nullptr);
    return;
  }
  codec_err = esp_codec_dev_set_out_vol(speaker, kCodecVolume);
  if (codec_err != ESP_CODEC_DEV_OK) {
    ESP_LOGE(kTag, "Official codec set volume failed: %d", codec_err);
    esp_codec_dev_close(speaker);
    esp_codec_dev_delete(speaker);
    vTaskDelete(nullptr);
    return;
  }
  ESP_LOGI(kTag, "Official Waveshare ES8311 speaker path opened at %u Hz",
           kSampleRate);

  std::int16_t pcm[kFramesPerBuffer * 2];
  const unsigned total_frames = kNoteDurationMs * kSampleRate / 1000U;
  const unsigned fade_frames = kFadeMs * kSampleRate / 1000U;
  for (const unsigned hz : kNotesHz) {
    ESP_LOGI(kTag, "Playing note %u Hz (%u ms)", hz, kNoteDurationMs);
    std::uint32_t phase = 0;
    const std::uint32_t step =
        static_cast<std::uint32_t>((static_cast<std::uint64_t>(hz) << 32) / kSampleRate);
    unsigned emitted = 0;
    while (emitted < total_frames) {
      const unsigned frames = (total_frames - emitted < kFramesPerBuffer)
                                  ? (total_frames - emitted) : kFramesPerBuffer;
      for (unsigned i = 0; i < frames; ++i) {
        const unsigned position = emitted + i;
        const unsigned envelope = position < fade_frames
            ? position
            : (position >= total_frames - fade_frames
                ? total_frames - position - 1 : fade_frames);
        const std::uint32_t folded = (phase & 0x80000000U) ? ~phase : phase;
        const std::int32_t triangle =
            static_cast<std::int32_t>((static_cast<std::uint64_t>(folded) *
                                      (2U * kPeakPcm)) / 0x7FFFFFFFU) - kPeakPcm;
        const auto sample = static_cast<std::int16_t>(
            triangle * static_cast<std::int32_t>(envelope) /
            static_cast<std::int32_t>(fade_frames));
        pcm[2 * i] = sample;
        pcm[2 * i + 1] = sample;
        phase += step;
      }
      codec_err = esp_codec_dev_write(speaker, pcm,
                                       frames * 2U * sizeof(std::int16_t));
      if (codec_err != ESP_CODEC_DEV_OK) {
        ESP_LOGE(kTag, "BSP PCM error at %uHz: %d", hz, codec_err);
        err = ESP_FAIL;
        break;
      }
      emitted += frames;
    }
    if (err != ESP_OK) break;
    ESP_LOGI(kTag, "Note %u Hz: %u PCM frames written", hz, emitted);
  }

  esp_codec_dev_close(speaker);
  esp_codec_dev_delete(speaker);
  ESP_LOGI(kTag, "BSP melody finished; codec closed and amplifier disabled");
  vTaskDelete(nullptr);
}
}  // namespace

void signal_start_audio_selftest() {
  if (xTaskCreate(tone_task, "signal_tone", 6144, nullptr, 2, nullptr) != pdPASS) {
    ESP_LOGE(kTag, "Could not start speaker test task");
  }
}
