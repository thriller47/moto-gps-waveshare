#include "signal_radio_audio.h"
#include "bsp/esp-bsp.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace {
constexpr char kTag[] = "signal_test";
constexpr unsigned kSampleRate = 44100;
constexpr unsigned kFrequency = 440;
constexpr unsigned kFramesPerBuffer = 256;
constexpr unsigned kDurationMs = 500;

// Volume-limited 440 Hz triangular test wave (no decoder/network needed).
// A deliberately quiet PCM amplitude plus codec volume 12% prevents
// inadvertently playing a loud sound at first hardware bring-up.
void tone_task(void*) {
  ESP_LOGI(kTag, "Starting one-shot ES8311 speaker test");
  SignalRadioAudio audio;
  esp_err_t err = audio.start(bsp_i2c_get_handle(), kSampleRate);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "Audio startup failed: %s", esp_err_to_name(err));
    vTaskDelete(nullptr);
    return;
  }
  err = audio.set_volume(12);
  if (err == ESP_OK) err = audio.set_mute(false);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "Unable to unmute codec: %s", esp_err_to_name(err));
    audio.stop();
    vTaskDelete(nullptr);
    return;
  }

  std::int16_t pcm[kFramesPerBuffer * 2];
  std::uint32_t phase = 0;
  const std::uint32_t step = (static_cast<std::uint64_t>(kFrequency) << 32) / kSampleRate;
  const unsigned total_frames = kDurationMs * kSampleRate / 1000U;
  unsigned emitted = 0;
  while (emitted < total_frames) {
    const unsigned frames = (total_frames - emitted < kFramesPerBuffer)
                                ? (total_frames - emitted) : kFramesPerBuffer;
    for (unsigned i = 0; i < frames; ++i) {
      // Two monotonic half-cycles with integer-only synthesis.
      const std::uint32_t folded = (phase & 0x80000000U)
          ? ~phase : phase;
      const auto sample = static_cast<std::int16_t>(
          (static_cast<std::int64_t>(folded) * 6000 / 0x7fffffffU) - 3000);
      pcm[2 * i] = sample;
      pcm[2 * i + 1] = sample;
      phase += step;
    }
    std::size_t written = 0;
    err = audio.write_pcm(pcm, frames, &written);
    if (err != ESP_OK || written != frames) {
      ESP_LOGE(kTag, "PCM output failed: %s (frames=%u/%u)",
               esp_err_to_name(err), static_cast<unsigned>(written), frames);
      break;
    }
    emitted += frames;
  }
  (void)audio.set_mute(true);
  audio.stop();
  ESP_LOGI(kTag, "Speaker test finished; audio powered down");
  vTaskDelete(nullptr);
}
}  // namespace

void signal_start_audio_selftest() {
  if (xTaskCreate(tone_task, "signal_tone", 6144, nullptr, 2, nullptr) != pdPASS) {
    ESP_LOGE(kTag, "Could not start speaker test task");
  }
}
