#include "signal_wifi.h"
#include "signal_serial_line.h"

#include <cstdio>
#include <cstring>
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

void signal_radio_play_test();

namespace {
constexpr char kTag[] = "signal_console";
constexpr std::size_t kLineLength = 160;

// Laboratory-only serial provisioning. Not an end-user secure provisioning
// interface: terminal echo, monitoring tools and scrollback may expose input.
// Run on the existing ESP-IDF app_main task: no additional 4 KiB task stack.
// Never print credentials in firmware logs.
void run_console() {
  SignalWifi wifi;
  // Receive individual bytes promptly over the native USB Serial/JTAG VFS.
  std::setvbuf(stdin, nullptr, _IONBF, 0);
  moto::signal::SerialLine<kLineLength> input;
  ESP_LOGI(kTag, "Experimental Wi-Fi console ready. Commands: wifi set, wifi test, wifi off, wifi status, radio play");
  ESP_LOGW(kTag, "Password input may be visible in terminal history/echo; use a disposable test network");
  while (true) {
    const int ch = std::getchar();
    if (ch == EOF) {
      // Empty native USB read: DO NOT discard previously received bytes.
      // The old parser reset its local buffer after each temporary EOF,
      // so longer commands were silently lost while short ones sometimes worked.
      std::clearerr(stdin);
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }

    const auto result = input.push(static_cast<char>(ch));
    if (result == moto::signal::SerialLine<kLineLength>::Result::Pending)
      continue;
    if (result == moto::signal::SerialLine<kLineLength>::Result::TooLong) {
      ESP_LOGW(kTag, "Wi-Fi command too long; input dropped safely");
      continue;
    }

    // A complete newline-terminated line has arrived (including any USB
    // read gaps). Never print its contents; it may contain a password.
    ESP_LOGI(kTag, "Serial command received (%u bytes)",
             static_cast<unsigned>(input.length()));
    char* command = moto::signal::skip_spaces(input.data());
    moto::signal::trim_right(command);
    if (std::strcmp(command, "wifi status") == 0) {
      ESP_LOGI(kTag, "Wi-Fi connected: %s", wifi.connected() ? "yes" : "no");
    } else if (std::strcmp(command, "wifi off") == 0) {
      wifi.stop();
      ESP_LOGI(kTag, "Wi-Fi disabled");
    } else if (std::strcmp(command, "wifi test") == 0) {
      wifi.stop();
      ESP_LOGI(kTag, "Connecting to saved 2.4 GHz hotspot...");
      const esp_err_t err = wifi.start_saved();
      ESP_LOGI(kTag, "Wi-Fi connect result: %s", esp_err_to_name(err));
    } else if (std::strcmp(command, "radio play") == 0) {
      if (!wifi.connected()) {
        ESP_LOGW(kTag, "Connect first with wifi test");
      } else {
        signal_radio_play_test();  // Continuous until radio stop or stream failure.
      }
    } else if (std::strcmp(command, "radio stop") == 0) {
      ESP_LOGI(kTag, "Radio is not playing");
    } else if (std::strncmp(command, "wifi set ", 9) == 0) {
      char* ssid = nullptr;
      char* password = nullptr;
      if (!moto::signal::parse_wifi_credentials(command + 9, ssid, password)) {
        ESP_LOGW(kTag, "Syntax: wifi set SSID|PASSWORD (spaces allowed in SSID)");
      } else {
        const esp_err_t err = wifi.save_credentials(ssid, password);
        ESP_LOGI(kTag, "Wi-Fi credential storage: %s", esp_err_to_name(err));
      }
    } else if (std::strcmp(command, "wifi help") == 0 ||
               std::strcmp(command, "wifi set") == 0) {
      ESP_LOGI(kTag, "Commands: wifi status, wifi set SSID|PASSWORD, wifi test, radio play, radio stop (during playback), wifi off");
    } else {
      ESP_LOGW(kTag, "Unknown Wi-Fi command (length=%u). Send 'wifi help'",
               static_cast<unsigned>(std::strlen(command)));
    }
    input.clear();  // Also erase the parsed password from RAM.
  }
}
}  // namespace

// This is intentionally a standalone radio diagnostics boot mode. The GPS
// UI and NimBLE are never started in this mode, so Wi-Fi has the internal
// heap required by ESP-IDF. A later, proper mode-switch UI is still needed.
void signal_run_wifi_console() {
  ESP_LOGW(kTag, "SIGNAL RADIO WI-FI LAB MODE: GPS UI and BLE are disabled for this boot");
  ESP_LOGI(kTag, "Before Wi-Fi: internal free=%u largest=%u, PSRAM free=%u",
           static_cast<unsigned>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned>(heap_caps_get_free_size(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT)));
  const esp_err_t err = nvs_flash_init();
  if (err != ESP_OK) {
    ESP_LOGE(kTag, "NVS initialization failed: %s; refusing to erase saved data",
             esp_err_to_name(err));
    return;
  }
  run_console();  // Intentionally runs forever on the already allocated main stack.
}
