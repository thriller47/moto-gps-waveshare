#include "ota_update_service.h"

#include <esp_err.h>
#include <esp_log.h>
#include <esp_ota_ops.h>
#include <esp_system.h>
#include <esp_wifi.h>
#include <esp_netif.h>
#include <esp_event.h>
#include <esp_http_server.h>
#include <esp_timer.h>
#include <nvs_flash.h>

#include <algorithm>
#include <cstring>
#include <cstdio>

#include "moto_nav_ui.h"

static const char *TAG = "ota_service";
static httpd_handle_t server_handle = NULL;

namespace {

esp_err_t ota_post_handler(httpd_req_t *req) {
  char buf[1024];
  int remaining = req->content_len;
  
  const esp_partition_t *update_partition = esp_ota_get_next_update_partition(NULL);
  if (update_partition == NULL) {
    ESP_LOGE(TAG, "Nessuna partizione OTA disponibile per l'aggiornamento");
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA partition not found");
    return ESP_FAIL;
  }

  esp_ota_handle_t update_handle = 0;
  esp_err_t err = esp_ota_begin(update_partition, OTA_SIZE_UNKNOWN, &update_handle);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_begin fallito (%s)", esp_err_to_name(err));
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA begin failed");
    return ESP_FAIL;
  }

  ESP_LOGI(TAG, "Inizio scrittura sulla partizione OTA: %s", update_partition->label);

  int total_len = req->content_len;
  uint64_t last_ui_update_ms = 0;

  while (remaining > 0) {
    int recv_len = httpd_req_recv(req, buf, std::min(remaining, (int)sizeof(buf)));
    if (recv_len <= 0) {
      if (recv_len == HTTPD_SOCK_ERR_TIMEOUT) {
        continue;
      }
      esp_ota_end(update_handle);
      httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Socket receive error");
      return ESP_FAIL;
    }

    err = esp_ota_write(update_handle, (const void *)buf, recv_len);
    if (err != ESP_OK) {
      esp_ota_end(update_handle);
      httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA write failed");
      return ESP_FAIL;
    }

    remaining -= recv_len;

    // Aggiornamento UI throttled a 4 Hz (~250 ms)
    uint64_t now_ms = esp_timer_get_time() / 1000ULL;
    if (now_ms - last_ui_update_ms >= 250 || remaining == 0) {
      last_ui_update_ms = now_ms;
      if (total_len > 0) {
        uint8_t percent = (uint8_t)(((total_len - remaining) * 100) / total_len);
        char msg[48];
        snprintf(msg, sizeof(msg), "Aggiornamento: %u%%", percent);
        ESP_LOGI(TAG, "%s", msg);
      }
    }
  }

  if (esp_ota_end(update_handle) != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_end fallito!");
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA end failed");
    return ESP_FAIL;
  }

  err = esp_ota_set_boot_partition(update_partition);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "esp_ota_set_boot_partition fallito (%s)!", esp_err_to_name(err));
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Set boot partition failed");
    return ESP_FAIL;
  }

  ESP_LOGI(TAG, "Aggiornamento OTA completato con successo! Riavvio in corso...");
  httpd_resp_sendstr(req, "OTA Success! Rebooting...");

  vTaskDelay(pdMS_TO_TICKS(1000));
  esp_restart();

  return ESP_OK;
}

} // namespace

esp_err_t ota_update_service_start(void) {
  // A second StartOta command must not reinitialize the Wi-Fi driver.
  if (server_handle != nullptr) {
    ESP_LOGI(TAG, "OTA HTTP server already running");
    return ESP_OK;
  }

  esp_err_t err = nvs_flash_init();
  if (err == ESP_ERR_NVS_NO_FREE_PAGES ||
      err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    // Do not erase user settings automatically just to launch OTA.
    ESP_LOGE(TAG, "NVS requires recovery: %s", esp_err_to_name(err));
    return err;
  }
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "NVS initialization failed: %s", esp_err_to_name(err));
    return err;
  }

  err = esp_netif_init();
  if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
    ESP_LOGE(TAG, "esp_netif_init failed: %s", esp_err_to_name(err));
    return err;
  }
  err = esp_event_loop_create_default();
  if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
    ESP_LOGE(TAG, "event loop initialization failed: %s", esp_err_to_name(err));
    return err;
  }

  static esp_netif_t* ota_ap_netif = nullptr;
  if (ota_ap_netif == nullptr) {
    ota_ap_netif = esp_netif_create_default_wifi_ap();
    if (ota_ap_netif == nullptr) {
      ESP_LOGE(TAG, "Could not create default Wi-Fi AP interface");
      return ESP_FAIL;
    }
  }

  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  err = esp_wifi_init(&cfg);
  if (err != ESP_OK && err != ESP_ERR_WIFI_INIT_STATE) {
    ESP_LOGE(TAG, "Wi-Fi initialization failed: %s", esp_err_to_name(err));
    return err;
  }

  wifi_config_t wifi_config = {};
  strcpy((char*)wifi_config.ap.ssid, "MOTO-GPS-OTA");
  wifi_config.ap.ssid_len = strlen("MOTO-GPS-OTA");
  wifi_config.ap.channel = 1;
  wifi_config.ap.max_connection = 4;
  wifi_config.ap.authmode = WIFI_AUTH_OPEN;

  err = esp_wifi_set_mode(WIFI_MODE_AP);
  if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_AP, &wifi_config);
  if (err == ESP_OK) err = esp_wifi_start();
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "Wi-Fi AP startup failed: %s", esp_err_to_name(err));
    return err;
  }

  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
  err = httpd_start(&server_handle, &config);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "OTA HTTP server failed: %s", esp_err_to_name(err));
    return err;
  }

  httpd_uri_t ota_uri = {
      .uri = "/ota",
      .method = HTTP_POST,
      .handler = ota_post_handler,
      .user_ctx = NULL,
  };
  err = httpd_register_uri_handler(server_handle, &ota_uri);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "OTA handler registration failed: %s", esp_err_to_name(err));
    httpd_stop(server_handle);
    server_handle = nullptr;
    return err;
  }
  ESP_LOGI(TAG, "OTA ready: MOTO-GPS-OTA, POST http://192.168.4.1/ota");
  return ESP_OK;
}
