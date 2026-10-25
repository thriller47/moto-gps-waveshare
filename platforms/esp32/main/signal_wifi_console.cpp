#include "signal_wifi.h"

#include <cstdio>
#include <cstring>
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

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
  char line[kLineLength] = {};
  ESP_LOGI(kTag, "Experimental Wi-Fi console ready. Commands: wifi set, wifi test, wifi off, wifi status");
  ESP_LOGW(kTag, "Password input may be visible in terminal history/echo; use a disposable test network");
  while (true) {
    std::size_t length = 0;
    int ch = 0;
    while ((ch = std::getchar()) != EOF) {
      if (ch == '\r' || ch == '\n') break;
      if (length + 1 < sizeof(line)) line[length++] = static_cast<char>(ch);
    }
    if (ch == EOF) {
      // Native USB Serial/JTAG stdin is initially non-blocking and sets EOF
      // when no character is available. Without clearerr(), all future
      // getchar() calls can return EOF even after a command is sent.
      std::clearerr(stdin);
      vTaskDelay(pdMS_TO_TICKS(25));
      continue;
    }
    line[length] = '\0';
    if (length != 0) ESP_LOGI(kTag, "Serial command received (%u bytes)",
                               static_cast<unsigned>(length));
    if (std::strcmp(line, "wifi status") == 0) {
      ESP_LOGI(kTag, "Wi-Fi connected: %s", wifi.connected() ? "yes" : "no");
    } else if (std::strcmp(line, "wifi off") == 0) {
      wifi.stop();
      ESP_LOGI(kTag, "Wi-Fi disabled");
    } else if (std::strcmp(line, "wifi test") == 0) {
      wifi.stop();
      const esp_err_t err = wifi.start_saved();
      ESP_LOGI(kTag, "Wi-Fi connect result: %s", esp_err_to_name(err));
    } else if (std::strncmp(line, "wifi set ", 9) == 0) {
      char* ssid = line + 9;
      char* separator = std::strchr(ssid, ' ');
      if (!separator) {
        ESP_LOGW(kTag, "Syntax: wifi set SSID PASSWORD (spaces unsupported in SSID)");
      } else {
        *separator = '\0';
        const char* password = separator + 1;
        const esp_err_t err = wifi.save_credentials(ssid, password);
        ESP_LOGI(kTag, "Wi-Fi credential storage: %s", esp_err_to_name(err));
      }
    } else if (length != 0) {
      ESP_LOGW(kTag, "Unknown command. Supported: wifi set, wifi test, wifi off, wifi status");
    }
    std::memset(line, 0, sizeof(line));
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
