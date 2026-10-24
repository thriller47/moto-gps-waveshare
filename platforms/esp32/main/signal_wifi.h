#pragma once
#include <cstddef>
#include <cstdint>
#include "esp_err.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"

// Signal radio-only Wi-Fi station. No Wi-Fi is started by the GPS firmware.
// One owner task calls start/stop; callbacks can run in the ESP event task.
// Credentials are stored in NVS, NOT committed in source or build logs.
class SignalWifi {
 public:
  esp_err_t save_credentials(const char* ssid, const char* password);
  esp_err_t load_credentials(char* ssid, std::size_t ssid_capacity,
                             char* password, std::size_t password_capacity);
  esp_err_t start_saved(std::uint32_t timeout_ms = 15000);
  void stop();
  bool connected() const { return connected_; }

 private:
  static void on_event(void* ctx, esp_event_base_t base,
                       int32_t event_id, void* event_data);
  esp_netif_t* netif_ = nullptr;
  EventGroupHandle_t events_ = nullptr;
  esp_event_handler_instance_t wifi_handler_ = nullptr;
  esp_event_handler_instance_t ip_handler_ = nullptr;
  bool loop_owned_ = false;
  bool driver_initialized_ = false;
  bool driver_started_ = false;
  volatile bool connected_ = false;
  unsigned retries_ = 0;
};
