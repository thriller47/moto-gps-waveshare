#include "signal_wifi.h"

#include <cstdio>
#include <cstring>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace {
constexpr char kTag[] = "signal_console";
constexpr std::size_t kLineLength = 160;

// Laboratory-only serial provisioning. Not an end-user secure provisioning
// interface: terminal echo, monitoring tools and scrollback may expose input.
// Never print credentials in firmware logs.
void console_task(void*) {
  SignalWifi wifi;
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
      vTaskDelay(pdMS_TO_TICKS(50));
      continue;
    }
    line[length] = '\0';
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

void signal_start_wifi_console() {
  if (xTaskCreate(console_task, "signal_wifi_cli", 4096, nullptr, 2,
                  nullptr) != pdPASS) {
    ESP_LOGE(kTag, "Could not start Wi-Fi console task");
  }
}
