#include "signal_wifi.h"

#include <cstring>
#include "esp_log.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "freertos/task.h"
#include "lwip/ip4_addr.h"

namespace {
constexpr char kTag[] = "signal_wifi";
constexpr char kNamespace[] = "signal_wifi";
constexpr EventBits_t kConnected = BIT0;
constexpr EventBits_t kFailed = BIT1;
constexpr unsigned kMaxRetries = 4;
}

esp_err_t SignalWifi::save_credentials(const char* ssid, const char* password) {
  if (!ssid || !password) return ESP_ERR_INVALID_ARG;
  const std::size_t ssid_len = std::strlen(ssid);
  const std::size_t pass_len = std::strlen(password);
  if (ssid_len == 0 || ssid_len > 32 || pass_len > 63 ||
      (pass_len != 0 && pass_len < 8)) return ESP_ERR_INVALID_ARG;
  nvs_handle_t handle = 0;
  esp_err_t err = nvs_open(kNamespace, NVS_READWRITE, &handle);
  if (err != ESP_OK) return err;
  err = nvs_set_str(handle, "ssid", ssid);
  if (err == ESP_OK) err = nvs_set_str(handle, "password", password);
  if (err == ESP_OK) err = nvs_commit(handle);
  nvs_close(handle);
  return err;
}

esp_err_t SignalWifi::load_credentials(char* ssid, std::size_t ssid_capacity,
                                        char* password, std::size_t password_capacity) {
  if (!ssid || !password || ssid_capacity < 33 || password_capacity < 64)
    return ESP_ERR_INVALID_ARG;
  ssid[0] = '\0';
  password[0] = '\0';
  nvs_handle_t handle = 0;
  esp_err_t err = nvs_open(kNamespace, NVS_READONLY, &handle);
  if (err != ESP_OK) return err;
  std::size_t n = ssid_capacity;
  err = nvs_get_str(handle, "ssid", ssid, &n);
  if (err == ESP_OK) {
    n = password_capacity;
    err = nvs_get_str(handle, "password", password, &n);
  }
  nvs_close(handle);
  if (err != ESP_OK) {
    ssid[0] = '\0';
    password[0] = '\0';
  }
  return err;
}

void SignalWifi::on_event(void* ctx, esp_event_base_t base,
                           int32_t id, void* event_data) {
  auto* self = static_cast<SignalWifi*>(ctx);
  if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
    self->connected_ = false;
    if (self->driver_started_ && self->retries_++ < kMaxRetries) {
      (void)esp_wifi_connect();
    } else if (self->events_) {
      xEventGroupSetBits(self->events_, kFailed);
    }
  } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_CONNECTED) {
    ESP_LOGI(kTag, "Associated with hotspot; waiting for DHCP address");
  } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
    self->connected_ = true;
    self->retries_ = 0;
    const auto* got_ip = static_cast<const ip_event_got_ip_t*>(event_data);
    ESP_LOGI(kTag, "Radio Wi-Fi connected, IP " IPSTR,
             IP2STR(&got_ip->ip_info.ip));
    if (self->events_) xEventGroupSetBits(self->events_, kConnected);
  }
}

esp_err_t SignalWifi::start_saved(std::uint32_t timeout_ms) {
  if (netif_ || driver_initialized_ || events_) return ESP_ERR_INVALID_STATE;
  char ssid[33] = {};
  char password[64] = {};
  esp_err_t err = load_credentials(ssid, sizeof(ssid), password, sizeof(password));
  if (err != ESP_OK) {
    ESP_LOGW(kTag, "No Wi-Fi credentials saved in NVS; radio Wi-Fi not started");
    return err;
  }
  err = esp_netif_init();
  if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) goto fail;
  err = esp_event_loop_create_default();
  if (err == ESP_OK) loop_owned_ = true;
  else if (err != ESP_ERR_INVALID_STATE) goto fail;
  events_ = xEventGroupCreate();
  if (!events_) { err = ESP_ERR_NO_MEM; goto fail; }
  netif_ = esp_netif_create_default_wifi_sta();
  if (!netif_) { err = ESP_ERR_NO_MEM; goto fail; }

  {
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&init);
  }
  if (err != ESP_OK) goto fail;
  driver_initialized_ = true;
  err = esp_event_handler_instance_register(WIFI_EVENT, WIFI_EVENT_STA_DISCONNECTED,
                                             &SignalWifi::on_event, this, &wifi_handler_);
  if (err != ESP_OK) goto fail;
  err = esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                             &SignalWifi::on_event, this, &ip_handler_);
  if (err != ESP_OK) goto fail;
  {
    wifi_config_t config{};
    std::memcpy(config.sta.ssid, ssid, std::strlen(ssid));
    std::memcpy(config.sta.password, password, std::strlen(password));
    config.sta.threshold.authmode =
        password[0] == '\0' ? WIFI_AUTH_OPEN : WIFI_AUTH_WPA2_PSK;
    err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &config);
  }
  std::memset(password, 0, sizeof(password));
  if (err != ESP_OK) goto fail;
  err = esp_wifi_start();
  if (err != ESP_OK) goto fail;
  driver_started_ = true;
  (void)esp_wifi_set_ps(WIFI_PS_NONE);  // Reliable continuous audio transport.
  err = esp_wifi_connect();
  if (err != ESP_OK) goto fail;
  ESP_LOGI(kTag, "Connecting to saved radio Wi-Fi SSID: %s", ssid);
  {
    const EventBits_t bits = xEventGroupWaitBits(
        events_, kConnected | kFailed, pdFALSE, pdFALSE,
        pdMS_TO_TICKS(timeout_ms));
    if (bits & kConnected) return ESP_OK;
    err = (bits & kFailed) ? ESP_ERR_WIFI_CONN : ESP_ERR_TIMEOUT;
  }
fail:
  std::memset(password, 0, sizeof(password));
  ESP_LOGW(kTag, "Radio Wi-Fi startup failed (%s); shutting down",
           esp_err_to_name(err));
  stop();
  return err;
}

void SignalWifi::stop() {
  driver_started_ = false;
  connected_ = false;
  if (driver_initialized_) {
    (void)esp_wifi_disconnect();
    (void)esp_wifi_stop();
  }
  if (wifi_handler_) {
    (void)esp_event_handler_instance_unregister(WIFI_EVENT, WIFI_EVENT_STA_DISCONNECTED, wifi_handler_);
    wifi_handler_ = nullptr;
  }
  if (ip_handler_) {
    (void)esp_event_handler_instance_unregister(IP_EVENT, IP_EVENT_STA_GOT_IP, ip_handler_);
    ip_handler_ = nullptr;
  }
  if (driver_initialized_) {
    (void)esp_wifi_deinit();
    driver_initialized_ = false;
  }
  if (netif_) {
    esp_netif_destroy_default_wifi(netif_);
    netif_ = nullptr;
  }
  if (events_) {
    vEventGroupDelete(events_);
    events_ = nullptr;
  }
  if (loop_owned_) {
    (void)esp_event_loop_delete_default();
    loop_owned_ = false;
  }
  retries_ = 0;
  ESP_LOGI(kTag, "Radio Wi-Fi stopped");
}
