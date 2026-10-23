#include "ota_manager.h"

#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>

#ifdef ESP_PLATFORM
#include "board_port.h"
#include "esp_event.h"
#include "esp_http_server.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_gap.h"
#include "nvs_flash.h"
#else
#include "esp_log.h"
#endif

#include "moto_nav_ui.h"

static const char *TAG = "OTA";

#ifdef ESP_PLATFORM
static httpd_handle_t s_server = NULL;
static esp_netif_t *s_ap_netif = NULL;

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                               int32_t event_id, void *event_data) {
    if (event_base == WIFI_EVENT) {
        if (event_id == WIFI_EVENT_AP_STACONNECTED) {
            auto *event = (wifi_event_ap_staconnected_t *)event_data;
            ESP_LOGI(TAG, "Station " MACSTR " connected, AID=%d",
                     MAC2STR(event->mac), event->aid);
        } else if (event_id == WIFI_EVENT_AP_STADISCONNECTED) {
            auto *event = (wifi_event_ap_stadisconnected_t *)event_data;
            ESP_LOGI(TAG, "Station " MACSTR " disconnected, AID=%d",
                     MAC2STR(event->mac), event->aid);
        }
    } else if (event_base == IP_EVENT) {
        if (event_id == IP_EVENT_AP_STAIPASSIGNED) {
            auto *event = (ip_event_ap_staipassigned_t *)event_data;
            ESP_LOGI(TAG, "Station " MACSTR " assigned IP:" IPSTR,
                     MAC2STR(event->mac), IP2STR(&event->ip));
        }
    }
}
#endif

static bool s_active = false;
static bool s_failed = false;
static int s_progress = 0;

static void safe_ui_show_ota_screen(void) {
#ifdef ESP_PLATFORM
    if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_show_ota_screen();
        board_port_unlock();
    }
#else
    moto_nav_ui_show_ota_screen();
#endif
}

static void safe_ui_hide_ota_screen(void) {
#ifdef ESP_PLATFORM
    if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_hide_ota_screen();
        board_port_unlock();
    }
#else
    moto_nav_ui_hide_ota_screen();
#endif
}

static void safe_ui_update_ota_progress(int percent) {
#ifdef ESP_PLATFORM
    if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_update_ota_progress(percent);
        board_port_unlock();
    }
#else
    moto_nav_ui_update_ota_progress(percent);
#endif
}

bool ota_manager_has_failed(void) {
    return s_failed;
}

#ifdef ESP_PLATFORM
static esp_err_t status_get_handler(httpd_req_t *req) {
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    const char *response = "{\"status\":\"ready\",\"version\":\"1.0\"}";
    httpd_resp_send(req, response, HTTPD_RESP_USE_STRLEN);
    return ESP_OK;
}

static esp_err_t ota_post_handler(httpd_req_t *req) {
    ESP_LOGI(TAG, "OTA Upload POST request started; content_len=%d", (int)req->content_len);
    esp_err_t err;
    esp_ota_handle_t update_handle = 0;
    const esp_partition_t *update_partition = esp_ota_get_next_update_partition(NULL);

    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

    if (update_partition == NULL) {
        ESP_LOGE(TAG, "No update partition found");
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "No partition");
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "Writing OTA to partition %s (subtype %d) at address 0x%" PRIx32,
             update_partition->label, update_partition->subtype, update_partition->address);

    err = esp_ota_begin(update_partition, OTA_WITH_SEQUENTIAL_WRITES, &update_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_begin failed (%s)", esp_err_to_name(err));
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA begin failed");
        return ESP_FAIL;
    }

    char buf[4096];
    int received = 0;
    int total_bytes = req->content_len;

    while (true) {
        int ret = httpd_req_recv(req, buf, sizeof(buf));
        if (ret < 0) {
            if (ret == HTTPD_SOCK_ERR_TIMEOUT) {
                continue;
            }
            ESP_LOGE(TAG, "HTTP receive error (%d)", ret);
            esp_ota_end(update_handle);
            httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Recv error");
            return ESP_FAIL;
        }
        if (ret == 0) {
            ESP_LOGI(TAG, "HTTP stream finished. Total received bytes: %d", received);
            break;
        }

        err = esp_ota_write(update_handle, (const void *)buf, ret);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "esp_ota_write failed (%s)", esp_err_to_name(err));
            esp_ota_end(update_handle);
            httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA write failed");
            return ESP_FAIL;
        }

        received += ret;

        if (total_bytes > 0) {
            s_progress = (received * 100) / total_bytes;
            safe_ui_update_ota_progress(s_progress);
            ESP_LOGI(TAG, "OTA progress: %d%% (%d/%d bytes)", s_progress, received, total_bytes);
        } else {
            ESP_LOGI(TAG, "OTA received: %d bytes", received);
        }
    }

    err = esp_ota_end(update_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_end failed (%s)", esp_err_to_name(err));
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA end failed");
        return ESP_FAIL;
    }

    err = esp_ota_set_boot_partition(update_partition);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_set_boot_partition failed (%s)", esp_err_to_name(err));
        httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "Set boot partition failed");
        return ESP_FAIL;
    }

    s_progress = 100;
    safe_ui_update_ota_progress(100);

    ESP_LOGI(TAG, "OTA update successfully written and boot partition set!");
    httpd_resp_sendstr(req, "OTA OK");

    ESP_LOGI(TAG, "Rebooting system in 500ms...");
    vTaskDelay(pdMS_TO_TICKS(500));
    esp_restart();
    return ESP_OK;
}
#endif

bool ota_manager_start_ap_server(void) {
    if (s_active) return true;
    s_failed = false;

    ESP_LOGI(TAG, "Starting Wi-Fi SoftAP and OTA HTTP server in software coexistence mode with BLE...");

#ifdef ESP_PLATFORM
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS partition needs erase; re-initializing NVS...");
        nvs_flash_erase();
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "nvs_flash_init failed: %s", esp_err_to_name(ret));
    }

    if (ble_gap_adv_active()) {
        ESP_LOGI(TAG, "Pausing BLE advertising for Wi-Fi SoftAP coexistence...");
        ble_gap_adv_stop();
    }

    ret = esp_netif_init();
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_netif_init failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    ret = esp_event_loop_create_default();
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_event_loop_create_default failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    static bool s_handlers_registered = false;
    if (!s_handlers_registered) {
        esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                            &wifi_event_handler, NULL, NULL);
        esp_event_handler_instance_register(IP_EVENT, IP_EVENT_AP_STAIPASSIGNED,
                                            &wifi_event_handler, NULL, NULL);
        s_handlers_registered = true;
    }

    if (s_ap_netif == NULL) {
        s_ap_netif = esp_netif_create_default_wifi_ap();
        if (s_ap_netif == NULL) {
            ESP_LOGE(TAG, "esp_netif_create_default_wifi_ap failed");
            s_failed = true;
            safe_ui_show_ota_screen();
            return false;
        }
    }

    esp_netif_ip_info_t ip_info;
    std::memset(&ip_info, 0, sizeof(ip_info));
    IP4_ADDR(&ip_info.ip, 192, 168, 4, 1);
    IP4_ADDR(&ip_info.gw, 192, 168, 4, 1);
    IP4_ADDR(&ip_info.netmask, 255, 255, 255, 0);

    esp_netif_dhcps_stop(s_ap_netif);
    ret = esp_netif_set_ip_info(s_ap_netif, &ip_info);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_netif_set_ip_info failed: %s", esp_err_to_name(ret));
    }
    ret = esp_netif_dhcps_start(s_ap_netif);
    if (ret != ESP_OK && ret != ESP_ERR_ESP_NETIF_DHCPS_ALREADY_STARTED) {
        ESP_LOGE(TAG, "esp_netif_dhcps_start failed: %s", esp_err_to_name(ret));
    }

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ret = esp_wifi_init(&cfg);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_wifi_init failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    ret = esp_wifi_set_storage(WIFI_STORAGE_RAM);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_storage failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    wifi_config_t wifi_config = {};
    std::memset(&wifi_config, 0, sizeof(wifi_config));
    std::strcpy((char *)wifi_config.ap.ssid, "MotoGPS-OTA");
    wifi_config.ap.ssid_len = std::strlen("MotoGPS-OTA");
    wifi_config.ap.ssid_hidden = 0;
    std::strcpy((char *)wifi_config.ap.password, "motogps123");
    wifi_config.ap.max_connection = 4;
    wifi_config.ap.authmode = WIFI_AUTH_WPA2_PSK;
    wifi_config.ap.channel = 1;
    wifi_config.ap.beacon_interval = 100;

    ret = esp_wifi_set_mode(WIFI_MODE_AP);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_mode failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    ret = esp_wifi_set_config(WIFI_IF_AP, &wifi_config);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_config failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    ret = esp_wifi_start();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_start failed: %s", esp_err_to_name(ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = 80;
    config.stack_size = 8192;

    esp_err_t http_ret = httpd_start(&s_server, &config);
    if (http_ret == ESP_OK) {
        httpd_uri_t status_uri;
        std::memset(&status_uri, 0, sizeof(status_uri));
        status_uri.uri = "/status";
        status_uri.method = HTTP_GET;
        status_uri.handler = status_get_handler;
        status_uri.user_ctx = NULL;
        httpd_register_uri_handler(s_server, &status_uri);

        httpd_uri_t ota_uri;
        std::memset(&ota_uri, 0, sizeof(ota_uri));
        ota_uri.uri = "/ota";
        ota_uri.method = HTTP_POST;
        ota_uri.handler = ota_post_handler;
        ota_uri.user_ctx = NULL;
        httpd_register_uri_handler(s_server, &ota_uri);
    } else {
        ESP_LOGE(TAG, "httpd_start failed: %s", esp_err_to_name(http_ret));
        s_failed = true;
        safe_ui_show_ota_screen();
        return false;
    }
#endif

    s_active = true;
    s_failed = false;
    s_progress = 0;
    safe_ui_show_ota_screen();
    return true;
}

void ota_manager_stop_ap_server(void) {
    if (!s_active) return;
#ifdef ESP_PLATFORM
    if (s_server) {
        httpd_stop(s_server);
        s_server = NULL;
    }
    esp_wifi_stop();
#endif
    s_active = false;
    safe_ui_hide_ota_screen();
}

bool ota_manager_start(void) {
    if (!s_active) {
        return ota_manager_start_ap_server();
    } else {
        ota_manager_stop_ap_server();
        return false;
    }
}

int ota_manager_get_progress(void) {
    return s_progress;
}

bool ota_manager_is_active(void) {
    return s_active;
}
