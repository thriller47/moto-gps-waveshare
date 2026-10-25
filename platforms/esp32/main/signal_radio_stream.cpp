#include <cstdint>
#include <cstring>
#include <cstdio>

#include "signal_serial_line.h"

#include "signal_stations.h"
#include "bsp/esp-bsp.h"
#include "driver/i2s_std.h"
#include "esp_audio_simple_dec.h"
#include "esp_audio_simple_dec_default.h"
#include "esp_audio_dec.h"
#include "impl/esp_mp3_dec.h"
#include "esp_codec_dev.h"
#include "esp_heap_caps.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_timer.h"

namespace {
constexpr char kTag[] = "signal_stream";
constexpr std::size_t kStopLineLength = 48;
constexpr std::size_t kMp3Chunk = 1024;
constexpr std::size_t kPcmBytes = 16384;

esp_codec_dev_handle_t open_speaker(const esp_audio_simple_dec_info_t& info) {
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
    esp_codec_dev_delete(speaker);
    return nullptr;
  }
  (void)esp_codec_dev_set_out_vol(speaker, 20);  // Conservative first test.
  ESP_LOGI(kTag, "Speaker opened: %lu Hz, %u channels, 16-bit, 20%% volume",
           static_cast<unsigned long>(info.sample_rate), info.channel);
  return speaker;
}

bool stop_requested(moto::signal::SerialLine<kStopLineLength>& input) {
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
    if (!stop) ESP_LOGW(kTag, "During playback only 'radio stop' is accepted");
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
void signal_radio_play_test() {
  ESP_LOGI(kTag, "Radio Paradise Main Mix: MP3 128 kbps (continuous). Send radio stop + Enter to stop");
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
    config.url = moto::signal::kFirstStation.stream_url;
    config.timeout_ms = 3000;
    config.buffer_size = 2048;
    config.disable_auto_redirect = false;
    http = esp_http_client_init(&config);
    if (!http) {
      ESP_LOGE(kTag, "HTTP client allocation failed");
      break;
    }
    esp_http_client_set_header(http, "Icy-MetaData", "0");
    const esp_err_t open_result = esp_http_client_open(http, 0);
    if (open_result != ESP_OK) {
      ESP_LOGE(kTag, "MP3 HTTP connect failed: %s", esp_err_to_name(open_result));
      break;
    }
    (void)esp_http_client_fetch_headers(http);
    const int status = esp_http_client_get_status_code(http);
    ESP_LOGI(kTag, "Stream HTTP status=%d", status);
    if (status != 200) {
      ESP_LOGE(kTag, "MP3 stream refused (HTTP %d)", status);
      break;
    }

    bool decode_failed = false;
    while (true) {
      if (stop_requested(stop_input)) {
        user_stopped = true;
        ESP_LOGI(kTag, "Radio stop requested");
        break;
      }
      const int read_bytes = esp_http_client_read(
          http, reinterpret_cast<char*>(encoded), kMp3Chunk);
      if (read_bytes < 0) {
        ESP_LOGE(kTag, "MP3 HTTP read error: %d", read_bytes);
        break;
      }
      if (read_bytes == 0) {
        ESP_LOGW(kTag, "MP3 stream ended or stalled");
        break;
      }
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
            speaker = open_speaker(info);
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
  } while (false);

  if (speaker) {
    esp_codec_dev_close(speaker);
    esp_codec_dev_delete(speaker);
  }
  if (http) {
    esp_http_client_close(http);
    esp_http_client_cleanup(http);
  }
  if (decoder) esp_audio_simple_dec_close(decoder);
  if (registered) esp_audio_simple_dec_unregister_default();
  if (mp3_registered) esp_audio_dec_unregister(ESP_AUDIO_TYPE_MP3);
  heap_caps_free(encoded);
  heap_caps_free(pcm);
  ESP_LOGI(kTag, "Radio playback %s: MP3 received=%llu bytes, PCM output=%llu bytes",
           user_stopped ? "stopped by user" : "ended", bytes_received, pcm_written);
}
