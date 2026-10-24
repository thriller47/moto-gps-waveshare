#include "signal_radio_audio.h"
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
  SignalRadioAudio audio;
  esp_err_t err = audio.start(bsp_i2c_get_handle(), kSampleRate);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "Audio startup failed: %s", esp_err_to_name(err));
    vTaskDelete(nullptr);
    return;
  }

  err = audio.set_volume(kCodecVolume);
  if (err == ESP_OK) err = audio.set_mute(false);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "Cannot enable speaker: %s", esp_err_to_name(err));
    audio.stop();
    vTaskDelete(nullptr);
    return;
  }

  std::int16_t pcm[kFramesPerBuffer * 2];
  const unsigned total_frames = kNoteDurationMs * kSampleRate / 1000U;
  const unsigned fade_frames = kFadeMs * kSampleRate / 1000U;
  for (const unsigned hz : kNotesHz) {
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
      std::size_t written = 0;
      err = audio.write_pcm(pcm, frames, &written);
      if (err != ESP_OK || written != frames) {
        ESP_LOGE(kTag, "PCM error at %uHz: %s (frames=%u/%u)",
                 hz, esp_err_to_name(err), static_cast<unsigned>(written), frames);
        break;
      }
      emitted += frames;
    }
    if (err != ESP_OK) break;
  }

  (void)audio.set_mute(true);
  audio.stop();
  ESP_LOGI(kTag, "Melody completed; amplifier and I2S stopped");
  vTaskDelete(nullptr);
}
}  // namespace

void signal_start_audio_selftest() {
  if (xTaskCreate(tone_task, "signal_tone", 6144, nullptr, 2, nullptr) != pdPASS) {
    ESP_LOGE(kTag, "Could not start speaker test task");
  }
}
