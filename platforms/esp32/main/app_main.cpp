#include "sdkconfig.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>

#include "ble_nav_transport.hpp"
#include "bsp_waveshare_1_28.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "motion_heading_sensor.h"
#include "motion_render_pacing.h"
#include "moto_nav_presenter.hpp"
#include "moto_nav_ui.h"
#include "phone_nav_bridge.hpp"

#ifdef CONFIG_MOTO_ENABLE_SIGNAL_CONTROLLER
#include "signal_mode_controller.hpp"
#endif

namespace {

constexpr char kTag[] = "main";
constexpr std::uint32_t kLvglTickPeriodMs = 5;
constexpr std::uint32_t kSensorSamplePeriodMs = 8;
constexpr std::uint32_t kStatsLogIntervalMs = 5000;
constexpr size_t kMinFreeSramForSensorTaskBytes = 8192;
constexpr size_t kMinFreeSramForBridgeTaskBytes = 8192;

#if CONFIG_SPIRAM
#define MOTO_PSRAM_BSS EXT_RAM_BSS_ATTR
#else
#define MOTO_PSRAM_BSS
#endif

// LVGL 9 requires at least 48KB heap for double-buffered 240x240 RGB565.
#if CONFIG_LV_MEM_SIZE < 49152
#error "LVGL memory size must be at least 48KB for Waveshare 1.28 display"
#endif

#if !CONFIG_SPIRAM
#warning "ESP32-S3 PSRAM is not enabled. Build may fail at runtime due to memory constraints."
#endif

MOTO_PSRAM_BSS static lv_color_t s_disp_buf1[BSP_LCD_DRAW_BUFF_SIZE];
MOTO_PSRAM_BSS static lv_color_t s_disp_buf2[BSP_LCD_DRAW_BUFF_SIZE];
static lv_display_t* s_display = nullptr;
static TaskHandle_t s_sensor_task_handle = nullptr;
static TaskHandle_t s_bridge_task_handle = nullptr;

#if CONFIG_SPIRAM
static StaticTask_t s_sensor_task_buffer;
static StackType_t* s_sensor_task_stack = nullptr;
static StaticTask_t s_bridge_task_buffer;
static StackType_t* s_bridge_task_stack = nullptr;
#endif

static bool s_display_pushed_once = false;

void bsp_display_flush_cb(lv_display_t* disp, const lv_area_t* area,
                          uint8_t* px_map) {
  s_display_pushed_once = true;
  bsp_lcd_flush(disp, area, px_map);
}

void bsp_increase_lvgl_tick(void* arg) {
  (void)arg;
  lv_tick_inc(kLvglTickPeriodMs);
}

void sensor_task(void* arg) {
  auto* sensor = static_cast<MotionHeadingSensor*>(arg);
  TickType_t last_wake_time = xTaskGetTickCount();

  while (true) {
    if (sensor != nullptr) {
      sensor->poll();
    }
    vTaskDelayUntil(&last_wake_time, pdMS_TO_TICKS(kSensorSamplePeriodMs));
  }
}

void bridge_task(void* arg) {
  auto* bridge = static_cast<PhoneNavBridge*>(arg);
  TickType_t last_wake_time = xTaskGetTickCount();

  while (true) {
    if (bridge != nullptr) {
      bridge->poll();
    }
    vTaskDelayUntil(&last_wake_time, pdMS_TO_TICKS(10));
  }
}

esp_err_t init_bsp() {
  ESP_LOGI(kTag, "Initializing Waveshare 1.28 BSP...");

  ESP_RETURN_ON_ERROR(bsp_i2c_init(), kTag, "bsp_i2c_init failed");
  ESP_RETURN_ON_ERROR(bsp_lcd_init(), kTag, "bsp_lcd_init failed");

  lv_init();

  s_display = lv_display_create(BSP_LCD_H_RES, BSP_LCD_V_RES);
  if (s_display == nullptr) {
    ESP_LOGE(kTag, "lv_display_create failed");
    return ESP_FAIL;
  }

  lv_display_set_buffers(s_display, s_disp_buf1, s_disp_buf2,
                         sizeof(s_disp_buf1),
                         LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(s_display, bsp_display_flush_cb);

  const esp_timer_create_args_t periodic_timer_args = {
      .callback = &bsp_increase_lvgl_tick,
      .arg = nullptr,
      .dispatch_method = ESP_TIMER_TASK,
      .name = "periodic_gui",
      .skip_unhandled_events = true,
  };
  esp_timer_handle_t periodic_timer = nullptr;
  ESP_RETURN_ON_ERROR(esp_timer_create(&periodic_timer_args, &periodic_timer),
                      kTag, "esp_timer_create failed");
  ESP_RETURN_ON_ERROR(
      esp_timer_start_periodic(periodic_timer, kLvglTickPeriodMs * 1000), kTag,
      "esp_timer_start_periodic failed");

  return ESP_OK;
}

void log_memory_stats() {
  const size_t free_sram = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
  const size_t min_sram =
      heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL);
  const size_t free_psram = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
  const size_t min_psram =
      heap_caps_get_minimum_free_size(MALLOC_CAP_SPIRAM);

  ESP_LOGI(kTag,
           "Memory: SRAM free=%u (min=%u), PSRAM free=%u (min=%u)",
           static_cast<unsigned>(free_sram), static_cast<unsigned>(min_sram),
           static_cast<unsigned>(free_psram),
           static_cast<unsigned>(min_psram));
}

bool check_memory_headroom(size_t required_bytes, const char* context) {
  const size_t free_sram = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
  if (free_sram < required_bytes) {
    ESP_LOGE(kTag, "Low SRAM for %s: %u < %u bytes", context,
             static_cast<unsigned>(free_sram),
             static_cast<unsigned>(required_bytes));
    return false;
  }
  return true;
}

}  // namespace

extern "C" void app_main() {
  ESP_LOGI(kTag, "Starting Moto GPS Waveshare firmware...");

#if CONFIG_SPIRAM
  ESP_LOGI(kTag, "PSRAM initialized: size=%d bytes",
           esp_spiram_get_size());
#else
  ESP_LOGW(kTag, "Running without PSRAM support!");
#endif

  ESP_ERROR_CHECK(init_bsp());

  bsp_lcd_set_backlight(false);

  ESP_ERROR_CHECK(moto_nav_ui_init());

  MOTO_PSRAM_BSS static moto::nav::NavPresenter presenter;
  // Use the proven maneuver zoom by default. The 17 Nov geometry-only
  // experiment remains opt-in in the simulator after the replay regression.
#ifdef MOTO_ESP_CURVE_ZOOM_EXPERIMENT
  // Test-only firmware: use the identical detector and 55 m / 80 m
  // hysteresis already exercised by browser replay/native tests.
  presenter.set_experimental_curve_zoom_enabled(true);
#endif
#if defined(MOTO_ESP_CURVE_ZOOM_EXPERIMENT) || defined(MOTO_ESP_SUBPIXEL_EXPERIMENT)
  ESP_LOGW(kTag, "ESP NAV EXPERIMENT curve=%d subpixel=%d (test branch only)",
#ifdef MOTO_ESP_CURVE_ZOOM_EXPERIMENT
           1,
#else
           0,
#endif
#ifdef MOTO_ESP_SUBPIXEL_EXPERIMENT
           1
#else
           0
#endif
  );
#endif
  MOTO_PSRAM_BSS static PhoneNavBridge phone_bridge(presenter);
  MOTO_PSRAM_BSS static BleNavTransport transport;
  static MotionHeadingSensor motion_sensor;

  ESP_ERROR_CHECK(phone_bridge.init(&transport, &motion_sensor));

#ifdef CONFIG_MOTO_ENABLE_SIGNAL_CONTROLLER
  static SignalModeController signal_controller;
  phone_bridge.attach_signal_controller(&signal_controller);
  ESP_LOGI(kTag, "Signal mode controller attached");
#endif

  if (check_memory_headroom(kMinFreeSramForSensorTaskBytes, "sensor_task")) {
#if CONFIG_SPIRAM
    s_sensor_task_stack = static_cast<StackType_t*>(
        heap_caps_malloc(4096 * sizeof(StackType_t), MALLOC_CAP_SPIRAM));
    if (s_sensor_task_stack != nullptr) {
      s_sensor_task_handle = xTaskCreateStatic(
          sensor_task, "sensor_task", 4096, &motion_sensor,
          configMAX_PRIORITIES - 2, s_sensor_task_stack, &s_sensor_task_buffer);
    } else {
      ESP_LOGW(kTag, "Failed to allocate sensor task stack in PSRAM, falling back to internal SRAM");
      xTaskCreate(sensor_task, "sensor_task", 3072, &motion_sensor,
                  configMAX_PRIORITIES - 2, &s_sensor_task_handle);
    }
#else
    xTaskCreate(sensor_task, "sensor_task", 3072, &motion_sensor,
                configMAX_PRIORITIES - 2, &s_sensor_task_handle);
#endif
    if (s_sensor_task_handle == nullptr) {
      ESP_LOGE(kTag, "Failed to create sensor_task");
    }
  }

  if (check_memory_headroom(kMinFreeSramForBridgeTaskBytes, "bridge_task")) {
#if CONFIG_SPIRAM
    s_bridge_task_stack = static_cast<StackType_t*>(
        heap_caps_malloc(4096 * sizeof(StackType_t), MALLOC_CAP_SPIRAM));
    if (s_bridge_task_stack != nullptr) {
      s_bridge_task_handle = xTaskCreateStatic(
          bridge_task, "bridge_task", 4096, &phone_bridge,
          configMAX_PRIORITIES - 3, s_bridge_task_stack, &s_bridge_task_buffer);
    } else {
      ESP_LOGW(kTag, "Failed to allocate bridge task stack in PSRAM, falling back to internal SRAM");
      xTaskCreate(bridge_task, "bridge_task", 3072, &phone_bridge,
                  configMAX_PRIORITIES - 3, &s_bridge_task_handle);
    }
#else
    xTaskCreate(bridge_task, "bridge_task", 3072, &phone_bridge,
                configMAX_PRIORITIES - 3, &s_bridge_task_handle);
#endif
    if (s_bridge_task_handle == nullptr) {
      ESP_LOGE(kTag, "Failed to create bridge_task");
    }
  }

  TickType_t last_stats_log = xTaskGetTickCount();
  bool backlight_turned_on = false;

  ESP_LOGI(kTag, "Entering main LVGL loop...");

  uint32_t last_speed_kph = UINT32_MAX;
  uint32_t last_scene_rev = UINT32_MAX;
  int64_t last_draw_us = esp_timer_get_time();

  while (true) {
    if (bsp_display_lock(pdMS_TO_TICKS(10))) {
      const auto& state = presenter.ui_state();
      const int64_t now_us = esp_timer_get_time();
      if (s_display != nullptr &&
          moto_should_render_motion_frame(
              state.speed_kph, last_speed_kph,
              state.map_scene_revision, last_scene_rev,
              now_us - last_draw_us)) {
        lv_display_refr_timer(s_display);
        last_speed_kph = state.speed_kph;
        last_scene_rev = state.map_scene_revision;
        last_draw_us = now_us;
      }
      lv_timer_handler();
      bsp_display_unlock();
    }

    if (!backlight_turned_on && s_display_pushed_once) {
      bsp_lcd_set_backlight(true);
      backlight_turned_on = true;
      ESP_LOGI(kTag, "First frame pushed to display, backlight enabled");
    }

    const TickType_t now = xTaskGetTickCount();
    if (pdTICKS_TO_MS(now - last_stats_log) >= kStatsLogIntervalMs) {
      log_memory_stats();
      last_stats_log = now;
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}
