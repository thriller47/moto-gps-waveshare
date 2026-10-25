#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <atomic>
#include <algorithm>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "signal_serial_line.h"

#include "signal_stations.h"
#include "signal_radio_ui.h"
#include "bsp/esp-bsp.h"
#include "driver/i2s_std.h"
#include "esp_audio_simple_dec.h"
#include "esp_audio_simple_dec_default.h"
#include "esp_audio_dec.h"
#include "impl/esp_mp3_dec.h"
#include "esp_codec_dev.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_log.h"
#include "esp_timer.h"

namespace {
constexpr char kTag[] = "signal_stream";
constexpr std::size_t kStopLineLength = 48;
constexpr int kDefaultVolume = 20;
constexpr int kMaxSafeVolume = 60;
// Larger TLS reads reduce network/decoder handoffs; one buffer in PSRAM.
constexpr std::size_t kMp3Chunk = 4096;
constexpr std::size_t kPcmBytes = 16384;

constexpr uint32_t kRingCapacity = 65536;
constexpr uint32_t kPrebuffer = 16384;
struct RadioRing {
  uint8_t* memory = nullptr;
  esp_http_client_handle_t http = nullptr;
  TaskHandle_t parent = nullptr;
  std::atomic<uint32_t> write{0}, read{0};
  std::atomic<bool> cancel{false}, finished{false};
  int result = 0;
  uint32_t slow = 0, longest_ms = 0;
};
void radio_network_task(void* context) {
  auto* q = static_cast<RadioRing*>(context);
  while (!q->cancel.load(std::memory_order_acquire)) {
    const uint32_t w = q->write.load(std::memory_order_relaxed);
    const uint32_t r = q->read.load(std::memory_order_acquire);
    const uint32_t free_bytes = kRingCapacity - (w - r);
    if (free_bytes == 0) { vTaskDelay(pdMS_TO_TICKS(5)); continue; }
    const uint32_t len = std::min<uint32_t>(
        kMp3Chunk, std::min<uint32_t>(free_bytes, kRingCapacity - w % kRingCapacity));
    const int64_t started = esp_timer_get_time();
    const int count = esp_http_client_read(q->http,
        reinterpret_cast<char*>(q->memory + w % kRingCapacity), len);
    const uint32_t ms = (esp_timer_get_time() - started) / 1000;
    if (ms > q->longest_ms) q->longest_ms = ms;
    if (ms > 100) {
      ++q->slow;
      if (q->slow <= 5 || q->slow % 50 == 0)
        ESP_LOGW(kTag, "Slow TLS read %lu ms, %d bytes",
                 static_cast<unsigned long>(ms), count);
    }
    if (count <= 0) { q->result = count; break; }
    q->write.store(w + count, std::memory_order_release);
  }
  q->finished.store(true, std::memory_order_release);
  xTaskNotifyGive(q->parent);
  vTaskDelete(nullptr);
}


// Keep the BSP-owned ES8311 session open across station switches. All calls
// are serialized on the radio console task; there is no concurrent playback.
esp_codec_dev_handle_t retained_speaker = nullptr;
uint32_t retained_sample_rate = 0;
uint8_t retained_channels = 0;

esp_codec_dev_handle_t open_speaker(const esp_audio_simple_dec_info_t& info, int volume) {
  if (retained_speaker && retained_sample_rate == info.sample_rate &&
      retained_channels == info.channel) {
    if (esp_codec_dev_set_out_vol(retained_speaker, volume) != ESP_CODEC_DEV_OK) {
      ESP_LOGE(kTag, "Reused speaker volume configuration failed");
      return nullptr;
    }
    ESP_LOGI(kTag, "Reusing open ES8311 / I2S: %lu Hz, %u channels",
             static_cast<unsigned long>(info.sample_rate), info.channel);
    return retained_speaker;
  }
  if (retained_speaker) {
    // Only close/reconfigure when the next station requires a new PCM format.
    esp_codec_dev_close(retained_speaker);
    retained_speaker = nullptr;
    retained_sample_rate = 0;
    retained_channels = 0;
  }
  if (info.bits_per_sample != 16 || (info.channel != 1 && info.channel != 2) ||
      (info.sample_rate != 44100 && info.sample_rate != 48000 &&
       info.sample_rate != 32000 && info.sample_rate != 22050)) {
    ESP_LOGE(kTag, "Unsupported MP3 output: %lu Hz, %u channels, %u bits",
             static_cast<unsigned long>(info.sample_rate), info.channel,
             info.bits_per_sample);
    return nullptr;
  }
  // The BSP speaker path was physically verified with 16-bit stereo 44.1 kHz.
  // Preserve the stream's actual sample rate and channel layout.
  i2s_std_config_t cfg = {
      .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(info.sample_rate),
      .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
          I2S_DATA_BIT_WIDTH_16BIT,
          info.channel == 2 ? I2S_SLOT_MODE_STEREO : I2S_SLOT_MODE_MONO),
      .gpio_cfg = {
          .mclk = BSP_I2S_MCLK,
          .bclk = BSP_I2S_SCLK,
          .ws = BSP_I2S_LCLK,
          .dout = BSP_I2S_DOUT,
          .din = BSP_I2S_DSIN,
          .invert_flags = {},
      },
  };
  cfg.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_256;
  const esp_err_t err = bsp_audio_init(&cfg);
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "BSP I2S init failed: %s", esp_err_to_name(err));
    return nullptr;
  }
  esp_codec_dev_handle_t speaker = bsp_audio_codec_speaker_init();
  if (!speaker) {
    ESP_LOGE(kTag, "BSP ES8311 speaker initialization failed");
    return nullptr;
  }
  esp_codec_dev_sample_info_t sample{};
  sample.sample_rate = info.sample_rate;
  sample.channel = info.channel;
  sample.bits_per_sample = info.bits_per_sample;
  if (esp_codec_dev_open(speaker, &sample) != ESP_CODEC_DEV_OK) {
    ESP_LOGE(kTag, "Speaker codec open failed");
    // BSP owns this cached device handle; never delete it here.
    return nullptr;
  }
  if (esp_codec_dev_set_out_vol(speaker, volume) != ESP_CODEC_DEV_OK) {
    ESP_LOGE(kTag, "Speaker volume configuration failed");
    esp_codec_dev_close(speaker);
    // BSP owns this cached device handle; never delete it here.
    return nullptr;
  }
  retained_speaker = speaker;
  retained_sample_rate = info.sample_rate;
  retained_channels = info.channel;
  ESP_LOGI(kTag, "Speaker opened: %lu Hz, %u channels, 16-bit, %d%% volume",
           static_cast<unsigned long>(info.sample_rate), info.channel, volume);
  return speaker;
}

bool stop_requested(moto::signal::SerialLine<kStopLineLength>& input,
                    esp_codec_dev_handle_t speaker, int& volume) {
  // USB serial stdin returns EOF when no bytes are available.
  for (int i = 0; i < 64; ++i) {
    const int ch = std::getchar();
    if (ch == EOF) {
      std::clearerr(stdin);
      return false;
    }
    const auto result = input.push(static_cast<char>(ch));
    if (result == moto::signal::SerialLine<kStopLineLength>::Result::Pending)
      continue;
    if (result == moto::signal::SerialLine<kStopLineLength>::Result::TooLong) {
      ESP_LOGW(kTag, "Playback command too long; discarded");
      input.clear();
      continue;
    }
    char* command = moto::signal::skip_spaces(input.data());
    moto::signal::trim_right(command);
    const bool stop = std::strcmp(command, "radio stop") == 0;
    if (!stop && std::strncmp(command, "radio volume ", 13) == 0) {
      const char* value = command + 13;
      char* end = nullptr;
      errno = 0;
      const long requested = std::strtol(value, &end, 10);
      if (errno || value == end || *end != '\0' ||
          requested < 0 || requested > kMaxSafeVolume) {
        ESP_LOGW(kTag, "Volume must be 0..%d", kMaxSafeVolume);
      } else if (speaker &&
                 esp_codec_dev_set_out_vol(speaker, static_cast<int>(requested))
                     != ESP_CODEC_DEV_OK) {
        ESP_LOGE(kTag, "Volume update failed");
      } else {
        volume = static_cast<int>(requested);
        ESP_LOGI(kTag, "Radio volume: %d%%", volume);
      }
    } else if (!stop) {
      ESP_LOGW(kTag, "During playback: radio stop or radio volume 0..%d",
               kMaxSafeVolume);
    }
    input.clear();
    if (stop) return true;
  }
  return false;
}
}  // namespace

// Blocking, continuous radio playback with serial stop; called from the radio-only lab
// console after 'wifi test' returned ESP_OK. No background decoder task,
// resource leaks on repeated play, or GPS/BLE activity in this lab build.
// MP3 is a continuous stream; plain HTTP is intentional for first test.
// No credentials or sensitive information are sent in the stream request.
void signal_radio_play_test(std::size_t station_index, moto::signal::RadioUiControl* ui) {
  if (station_index >= moto::signal::kMp3StationCount) {
    ESP_LOGE(kTag, "Invalid station number");
    return;
  }
  const auto& station = moto::signal::kMp3Stations[station_index];
  ESP_LOGI(kTag, "Tuning: %s", station.name);
  ESP_LOGI(kTag, "MP3 stream (continuous). Send radio stop or radio volume 0..60 + Enter");
  if (bsp_i2c_get_handle() == nullptr) {
    const esp_err_t init = bsp_i2c_init();
    if (init != ESP_OK) {
      ESP_LOGE(kTag, "Waveshare I2C init failed: %s", esp_err_to_name(init));
      return;
    }
  }

  uint8_t* encoded = static_cast<uint8_t*>(
      heap_caps_malloc(kMp3Chunk, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  uint8_t* pcm = static_cast<uint8_t*>(
      heap_caps_malloc(kPcmBytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  if (!encoded || !pcm) {
    ESP_LOGE(kTag, "MP3 input/PCM PSRAM allocation failed");
    heap_caps_free(encoded);
    heap_caps_free(pcm);
    return;
  }

  esp_audio_simple_dec_handle_t decoder = nullptr;
  esp_codec_dev_handle_t speaker = nullptr;
  esp_http_client_handle_t http = nullptr;
  bool registered = false;
  bool mp3_registered = false;
  unsigned long long bytes_received = 0;
  unsigned long long pcm_written = 0;
  moto::signal::SerialLine<kStopLineLength> stop_input;
  bool user_stopped = false;
  // Retain the last volume across station changes and repeated play calls.
  // Playback runs on the console task, so no extra synchronization is needed.
  static int saved_volume = kDefaultVolume;
  int volume = saved_volume;
  if (ui) {
    const int pending = ui->volume.exchange(-1);
    if (pending >= 0 && pending <= kMaxSafeVolume) volume = pending;
    ui->stop.store(false);
    moto::signal::radio_ui_status(station.name, true, volume);
  }

  do {
    if (esp_audio_simple_dec_register_default() != ESP_AUDIO_ERR_OK) {
      ESP_LOGE(kTag, "Could not register MP3 decoder");
      break;
    }
    registered = true;
    // The simple decoder registers the MP3 parser, but the actual MP3 frame
    // decoder can be disabled by esp_audio_codec Kconfig defaults. Register
    // the MP3 frame decoder explicitly so a build never silently omits it.
    const esp_audio_err_t mp3_reg = esp_mp3_dec_register();
    if (mp3_reg != ESP_AUDIO_ERR_OK) {
      ESP_LOGE(kTag, "MP3 frame decoder registration failed: %d",
               static_cast<int>(mp3_reg));
      break;
    }
    mp3_registered = true;
    ESP_LOGI(kTag, "MP3 frame decoder explicitly registered");
    esp_audio_simple_dec_cfg_t dcfg{};
    dcfg.dec_type = ESP_AUDIO_SIMPLE_DEC_TYPE_MP3;
    if (esp_audio_simple_dec_open(&dcfg, &decoder) != ESP_AUDIO_ERR_OK) {
      ESP_LOGE(kTag, "MP3 decoder open failed");
      break;
    }

    esp_http_client_config_t config{};
    config.url = station.stream_url;
    config.timeout_ms = 3000;
    config.buffer_size = 4096;
    config.buffer_size_tx = 1024;
    config.disable_auto_redirect = false;
    // Validate HTTPS stream servers and HTTPS redirect targets using the
    // Espressif root-CA bundle; never disable TLS certificate checks.
    config.crt_bundle_attach = esp_crt_bundle_attach;
    http = esp_http_client_init(&config);
    if (!http) {
      ESP_LOGE(kTag, "HTTP client allocation failed");
      break;
    }
    esp_http_client_set_header(http, "Icy-MetaData", "0");
    // Streaming uses the manual open/read API, which does not automatically
    // follow redirects (disable_auto_redirect only governs perform()).
    // Limit redirect hops to avoid loops, retaining TLS certificate checks.
    int status = 0;
    for (int redirect = 0; redirect <= 5; ++redirect) {
      const esp_err_t open_result = esp_http_client_open(http, 0);
      if (open_result != ESP_OK) {
        ESP_LOGE(kTag, "MP3 HTTP connect failed: %s", esp_err_to_name(open_result));
        break;
      }
      (void)esp_http_client_fetch_headers(http);
      status = esp_http_client_get_status_code(http);
      ESP_LOGI(kTag, "Stream HTTP status=%d (hop %d)", status, redirect);
      if (status != 301 && status != 302 && status != 303 &&
          status != 307 && status != 308) break;
      if (redirect == 5) {
        ESP_LOGE(kTag, "Too many stream redirects");
        break;
      }
      const esp_err_t redirected = esp_http_client_set_redirection(http);
      if (redirected != ESP_OK) {
        ESP_LOGE(kTag, "Invalid HTTP redirect: %s", esp_err_to_name(redirected));
        break;
      }
      esp_http_client_close(http);
      status = 0;
    }
    if (status != 200) {
      ESP_LOGE(kTag, "MP3 stream refused (HTTP %d)", status);
      break;
    }

    RadioRing ring;
    ring.memory = static_cast<uint8_t*>(heap_caps_malloc(
        kRingCapacity, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!ring.memory) {
      ESP_LOGE(kTag, "PSRAM radio ring allocation failed");
      break;
    }
    ring.http = http;
    ring.parent = xTaskGetCurrentTaskHandle();
    TaskHandle_t producer = nullptr;
    if (xTaskCreate(radio_network_task, "radio_http", 6144, &ring, 4,
                    &producer) != pdPASS) {
      ESP_LOGE(kTag, "Radio HTTP task creation failed");
      heap_caps_free(ring.memory);
      break;
    }
    bool decode_failed = false;
    bool buffered = false;
    uint32_t underruns = 0;
    while (true) {
      if (ui) {
        const int pending = ui->volume.exchange(-1);
        if (pending >= 0 && pending <= kMaxSafeVolume) {
          if (!speaker || esp_codec_dev_set_out_vol(speaker, pending) ==
                                  ESP_CODEC_DEV_OK) {
            volume = pending;
            moto::signal::radio_ui_status(nullptr, true, volume);
          }
        }
        if (ui->wifi_request.load(std::memory_order_acquire) != 0 ||
            ui->stop.exchange(false) ||
            ui->play_station.load(std::memory_order_acquire) >= 0) {
          user_stopped = true;
          ESP_LOGI(kTag, "Radio touch stop / station switch requested");
          break;
        }
      }
      if (stop_requested(stop_input, speaker, volume)) {
        user_stopped = true;
        ESP_LOGI(kTag, "Radio stop requested");
        break;
      }
      const uint32_t read_pos = ring.read.load(std::memory_order_relaxed);
      const uint32_t available =
          ring.write.load(std::memory_order_acquire) - read_pos;
      const bool finished = ring.finished.load(std::memory_order_acquire);
      if (!buffered) {
        if (available < kPrebuffer && !finished) {
          vTaskDelay(pdMS_TO_TICKS(10));
          continue;
        }
        buffered = true;
        ESP_LOGI(kTag, "Radio prebuffer: %lu bytes",
                 static_cast<unsigned long>(available));
      }
      if (available == 0) {
        if (finished) {
          ESP_LOGW(kTag, "Radio network ended: %d", ring.result);
          break;
        }
        ++underruns;
        if (underruns <= 5 || underruns % 50 == 0)
          ESP_LOGW(kTag, "MP3 buffer underrun: %lu",
                   static_cast<unsigned long>(underruns));
        vTaskDelay(pdMS_TO_TICKS(10));
        continue;
      }
      const uint32_t read_bytes = std::min<uint32_t>(
          kMp3Chunk,
          std::min<uint32_t>(available, kRingCapacity - read_pos % kRingCapacity));
      std::memcpy(encoded, ring.memory + read_pos % kRingCapacity, read_bytes);
      ring.read.store(read_pos + read_bytes, std::memory_order_release);
      bytes_received += static_cast<unsigned>(read_bytes);
      esp_audio_simple_dec_raw_t raw{};
      raw.buffer = encoded;
      raw.len = static_cast<uint32_t>(read_bytes);
      while (raw.consumed < raw.len) {
        const uint32_t available = raw.len - raw.consumed;
        esp_audio_simple_dec_raw_t part{};
        part.buffer = raw.buffer + raw.consumed;
        part.len = available;
        esp_audio_simple_dec_out_t out{};
        out.buffer = pcm;
        out.len = kPcmBytes;
        const esp_audio_err_t decode_result =
            esp_audio_simple_dec_process(decoder, &part, &out);
        if (decode_result != ESP_AUDIO_ERR_OK) {
          ESP_LOGE(kTag, "MP3 decoder error: %d, requested PCM size=%lu",
                   static_cast<int>(decode_result),
                   static_cast<unsigned long>(out.needed_size));
          decode_failed = true;
          break;
        }
        if (part.consumed == 0 && out.decoded_size == 0) {
          ESP_LOGE(kTag, "MP3 decoder made no progress");
          decode_failed = true;
          break;
        }
        raw.consumed += part.consumed;
        if (out.decoded_size) {
          if (!speaker) {
            esp_audio_simple_dec_info_t info{};
            if (esp_audio_simple_dec_get_info(decoder, &info) != ESP_AUDIO_ERR_OK) {
              ESP_LOGE(kTag, "MP3 audio format unavailable");
              decode_failed = true;
              break;
            }
            speaker = open_speaker(info, volume);
            if (!speaker) {
              decode_failed = true;
              break;
            }
          }
          if (esp_codec_dev_write(speaker, pcm, out.decoded_size) !=
              ESP_CODEC_DEV_OK) {
            ESP_LOGE(kTag, "ES8311 PCM write failed");
            decode_failed = true;
            break;
          }
          pcm_written += out.decoded_size;
        }
      }
      if (decode_failed) break;
    }
    ring.cancel.store(true, std::memory_order_release);
    // HTTP cleanup and PSRAM release must wait for the producer to exit.
    while (!ring.finished.load(std::memory_order_acquire))
      (void)ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));
    ESP_LOGI(kTag, "Radio buffering: underruns=%lu slow=%lu max_ms=%lu",
             static_cast<unsigned long>(underruns),
             static_cast<unsigned long>(ring.slow),
             static_cast<unsigned long>(ring.longest_ms));
    heap_caps_free(ring.memory);
  } while (false);

  // Keep the codec open for the next station with the same sample format.
  // The next call to open_speaker() reconfigures it only when necessary.
  if (http) {
    esp_http_client_close(http);
    esp_http_client_cleanup(http);
  }
  if (decoder) esp_audio_simple_dec_close(decoder);
  if (registered) esp_audio_simple_dec_unregister_default();
  if (mp3_registered) esp_audio_dec_unregister(ESP_AUDIO_TYPE_MP3);
  heap_caps_free(encoded);
  heap_caps_free(pcm);
  saved_volume = volume;
  if (ui) moto::signal::radio_ui_status(station.name, false, volume);
  ESP_LOGI(kTag, "Radio playback %s: MP3 received=%llu bytes, PCM output=%llu bytes",
           user_stopped ? "stopped by user" : "ended", bytes_received, pcm_written);
}
