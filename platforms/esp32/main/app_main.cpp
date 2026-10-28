#include <cstdint>

#include "ble_nav_transport_nimble.h"
#include "board_port.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "esp_attr.h"
#include "esp_system.h"
#include "lvgl.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "moto_nav_presenter.hpp"
#include "moto_nav_ui.h"
#include "motion_heading_sensor.h"
#include "phone_nav_bridge.h"

#ifdef MOTO_SIGNAL_AUDIO_BOOT_TEST
void signal_start_audio_selftest();
#endif
#ifdef MOTO_SIGNAL_WIFI_LAB_BOOT
void signal_run_wifi_console();
#endif

#ifdef ESP_PLATFORM
#include "esp_attr.h"
#define MOTO_PSRAM_BSS EXT_RAM_BSS_ATTR
#else
#define MOTO_PSRAM_BSS
#endif

// RTC survives software restart but is cleared by a cold power cycle.
// 0 = navigation (safe default), 1 = radio.
RTC_NOINIT_ATTR static uint32_t signal_boot_magic;
RTC_NOINIT_ATTR static uint32_t signal_boot_choice;
constexpr uint32_t kSignalBootMagic = 0x5349474e;
extern "C" void signal_request_mode(int radio) {
  signal_boot_choice = radio ? 1u : 0u;
  signal_boot_magic = kSignalBootMagic;
  esp_restart();
}

namespace {
constexpr char kTag[] = "moto_gps";
constexpr std::uint64_t kPowerHoldMs = 3'000;

moto::ble::AckStatus receive_phone_message(
    const moto::ble::ReassembledMessage& message, void* context) {
  return static_cast<PhoneNavBridge*>(context)->on_message(message);
}

void update_phone_link(bool active, void* context) {
  static_cast<PhoneNavBridge*>(context)->on_link_state(active);
}

void update_motion_heading(float heading_rate_dps, std::uint64_t sample_ms,
                           void* context) {
  static_cast<PhoneNavBridge*>(context)->on_imu_sample(heading_rate_dps,
                                                       sample_ms);
}

void demo_tick_task(void* context) {
  auto* bridge = static_cast<PhoneNavBridge*>(context);
  while (true) {
    const auto now_ms = static_cast<std::uint64_t>(esp_timer_get_time()) /
                        1'000U;
    bridge->update_demo(now_ms);
    // Match the display and route interpolation at 40 Hz. Keeping all three
    // clocks phase-compatible avoids periodic long frame gaps.
    vTaskDelay(pdMS_TO_TICKS(25));
  }
}

void enter_radio(lv_event_t*) {
  signal_request_mode(1);
}
void add_radio_entry() {
  lv_obj_t* btn = lv_button_create(lv_screen_active());
  lv_obj_set_size(btn, 50, 50);
  // Right-hand three-o'clock position on the 466px circular display.
  lv_obj_align(btn, LV_ALIGN_CENTER, 144, 0);
  lv_obj_set_style_radius(btn, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(btn, lv_color_hex(0x1C1C1E), 0);
  lv_obj_set_style_border_color(btn, lv_color_hex(0x8E8E93), 0);
  lv_obj_set_style_border_width(btn, 1, 0);
  lv_obj_set_style_shadow_width(btn, 0, 0);
  lv_obj_add_event_cb(btn, enter_radio, LV_EVENT_CLICKED, nullptr);
  lv_obj_t* txt = lv_label_create(btn);
  lv_label_set_text(txt, LV_SYMBOL_WIFI);
  lv_obj_center(txt);
}
void power_button_task(void*) {
  std::uint64_t pressed_since_ms = 0;
  // The AXP2101 needs roughly a one-second press to power the board on, so
  // the task usually starts while the user is still holding PWR. Arm the
  // hold-to-shutdown detector only after the line has been seen low once;
  // otherwise the tail of the power-on press is counted as a new 3-second
  // hold and the freshly booted unit switches itself off.
  bool released_once = false;
  while (true) {
    const std::uint64_t now_ms =
        static_cast<std::uint64_t>(esp_timer_get_time()) / 1'000U;
    if (!board_port_power_button_pressed()) {
      if (!released_once) {
        released_once = true;
        ESP_LOGI(kTag, "PWR ready: button released, hold detection armed");
      }
      pressed_since_ms = 0;
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    if (!released_once) {
      // Startup press still in progress; do not start the hold timer.
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }
    if (pressed_since_ms == 0) {
      pressed_since_ms = now_ms;
      ESP_LOGI(kTag, "PWR press started");
    } else if (now_ms - pressed_since_ms >= kPowerHoldMs) {
      ESP_LOGI(kTag, "PWR held for 3 seconds; requesting shutdown");
      if (board_port_lock(UINT32_MAX)) {
        moto_nav_ui_show_power_off_screen();
        board_port_unlock();
      }
      vTaskDelay(pdMS_TO_TICKS(300));
      const esp_err_t result = board_port_power_off();
      if (result != ESP_OK) {
        ESP_LOGE(kTag, "AXP2101 software power-off failed: %s",
                 esp_err_to_name(result));
      }

      // AXP2101 should remove the switched rails here. If this particular
      // board remains alive while USB is attached, blank the panel and enter
      // deep sleep after key release. GPIO3 high wakes it on the next press.
      vTaskDelay(pdMS_TO_TICKS(500));
      while (board_port_power_button_pressed()) {
        vTaskDelay(pdMS_TO_TICKS(20));
      }
      ESP_ERROR_CHECK(esp_sleep_enable_ext1_wakeup_io(
          1ULL << 3, ESP_EXT1_WAKEUP_ANY_HIGH));
      esp_deep_sleep_start();
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

} // namespace

extern "C" void app_main(void) {
#ifdef MOTO_SIGNAL_MODE_SWITCH
  const bool radio_requested =
      esp_reset_reason() == ESP_RST_SW &&
      signal_boot_magic == kSignalBootMagic && signal_boot_choice == 1u;
  // Consume immediately to avoid a stale radio selection on later resets.
  signal_boot_magic = 0;
  signal_boot_choice = 0;
  if (radio_requested) {
    ESP_LOGI(kTag, "Starting radio after controlled mode switch");
    signal_run_wifi_console();
    return;
  }
#elif defined(MOTO_SIGNAL_WIFI_LAB_BOOT)
  // Isolated bench-test only. Wi-Fi and BLE share scarce internal RAM on
  // ESP32-S3, while the full GPS/LVGL session leaves <2 KiB free.
  // Avoid starting GPS/BLE/display at all in this temporary lab firmware.
  signal_run_wifi_console();
  return;
#endif
  static_assert(MOTO_DISPLAY_WIDTH == MOTO_UI_CANVAS_WIDTH &&
                    MOTO_DISPLAY_HEIGHT == MOTO_UI_CANVAS_HEIGHT,
                "The board and shared UI canvas must have one resolution");
  static_assert(LV_COLOR_DEPTH == MOTO_DISPLAY_BITS_PER_PIXEL,
                "Firmware and Web must both build the shared UI as RGB565");

  ESP_LOGI(kTag, "starting %dx%d RGB565 firmware target",
           MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT);
  ESP_LOGI(kTag, "BOOT_DIAG stage=app_main reset_reason=%d free_internal=%lu largest_internal=%lu",
           static_cast<int>(esp_reset_reason()),
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned long>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));

  MOTO_PSRAM_BSS static moto::nav::NavPresenter presenter;
  MOTO_PSRAM_BSS static PhoneNavBridge phone_bridge(presenter);
  MOTO_PSRAM_BSS static BleNavTransport transport;
  static MotionHeadingSensor motion_sensor;

  // 1. Initialize NVS, BLE stack (NimBLE / phy_init), QMI8658 sensor, and PWR button task FIRST.
  phone_bridge.set_sender(BleNavTransport::send_from_bridge, &transport);
  transport.set_callbacks(receive_phone_message, update_phone_link,
                          &phone_bridge);
  ESP_LOGI(kTag, "BOOT_DIAG stage=ble_init_start");
  const esp_err_t ble_result = transport.start();
  ESP_LOGI(kTag, "BOOT_DIAG stage=ble_init_done err=%s internal=%lu",
           esp_err_to_name(ble_result),
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));
  if (ble_result != ESP_OK) {
    ESP_LOGE(kTag, "BLE startup failed; display remains in offline mode: %s",
             esp_err_to_name(ble_result));
  }

  const esp_err_t motion_result = motion_sensor.start(update_motion_heading,
                                                      &phone_bridge);
  if (motion_result != ESP_OK) {
    ESP_LOGW(kTag, "QMI8658 heading assist could not start: %s",
             esp_err_to_name(motion_result));
  }

  if (xTaskCreate(power_button_task, "moto_power", 3'072, nullptr, 3,
                  nullptr) != pdPASS) {
    ESP_LOGW(kTag, "PWR long-hold task could not start");
  }

  // Allow BLE_INIT and phy_init to complete startup so phy_init never interrupts display DMA.
  vTaskDelay(pdMS_TO_TICKS(100));

  // 2. ONLY NOW start the CO5300 display, CST9217 touch, and LVGL rendering task!
  ESP_LOGI(kTag, "BOOT_DIAG stage=display_init_start internal=%lu largest=%lu",
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned long>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));
  const esp_err_t init_result = board_port_init();
  ESP_LOGI(kTag, "BOOT_DIAG stage=display_init_done err=%s internal=%lu",
           esp_err_to_name(init_result),
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));
  if (init_result != ESP_OK) {
    ESP_LOGE(kTag, "board initialization stopped before shared UI startup: %s",
             esp_err_to_name(init_result));
    vTaskDelete(nullptr);
    return;
  }

  lv_display_t *const display = board_port_get_display();
  if (display == nullptr) {
    ESP_LOGE(kTag, "board port returned no LVGL display");
    vTaskDelete(nullptr);
    return;
  }

  if (lv_display_get_horizontal_resolution(display) != MOTO_DISPLAY_WIDTH ||
      lv_display_get_vertical_resolution(display) != MOTO_DISPLAY_HEIGHT ||
      lv_display_get_color_format(display) != LV_COLOR_FORMAT_RGB565) {
    ESP_LOGE(kTag, "board display violates the shared RGB565 portability "
                   "contract");
    vTaskDelete(nullptr);
    return;
  }

  if (!board_port_lock(UINT32_MAX)) {
    ESP_LOGE(kTag, "could not acquire LVGL lock");
    vTaskDelete(nullptr);
    return;
  }

  ESP_LOGI(kTag, "BOOT_DIAG stage=splash_create_start");
  moto_nav_ui_show_boot_screen();
  ESP_LOGI(kTag, "BOOT_DIAG stage=splash_created");
  // Commit the deliberately black first animation frame while the physical
  // panel is still hidden, then reveal it. This removes the white frame that
  // used to leak from LVGL's default startup screen.
  lv_refr_now(display);
  const esp_err_t reveal_result = board_port_reveal_display();
  ESP_LOGI(kTag, "BOOT_DIAG stage=display_revealed err=%s",
           esp_err_to_name(reveal_result));
  if (reveal_result != ESP_OK) {
    ESP_LOGE(kTag, "could not reveal boot animation: %s",
             esp_err_to_name(reveal_result));
  }
  board_port_unlock();

  // Give the display worker enough time to commit the monochrome power-on
  // frame before constructing the full production UI.
  vTaskDelay(pdMS_TO_TICKS(1'250));

  if (!board_port_lock(UINT32_MAX)) {
    ESP_LOGE(kTag, "could not reacquire LVGL lock after boot screen");
    vTaskDelete(nullptr);
    return;
  }

  ESP_LOGI(kTag, "BOOT_DIAG stage=navigation_ui_create_start internal=%lu largest=%lu",
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned long>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));
  moto_nav_ui_create();
#ifdef MOTO_SIGNAL_MODE_SWITCH
  add_radio_entry();
#endif
  ESP_LOGI(kTag, "BOOT_DIAG stage=navigation_ui_ready internal=%lu largest=%lu",
           static_cast<unsigned long>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)),
           static_cast<unsigned long>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)));
  // NavSnapshot contains the complete bounded roads/buildings window and is
  // several kilobytes. A temporary here inflates app_main's stack frame for
  // the entire display/BSP startup call chain, which can trip the FreeRTOS
  // main-task canary before this line is even reached. Keep the immutable
  // initial state in static storage just like the bridge's retained snapshots.
  static const moto::nav::NavSnapshot initial_snapshot{};
  presenter.update(initial_snapshot);
  presenter.apply_to_lvgl();
  phone_bridge.install_ui_callbacks();
  board_port_unlock();

  if (!phone_bridge.start_renderer()) {
    ESP_LOGE(kTag, "BOOT_DIAG stage=renderer_failed");
    vTaskDelete(nullptr);
    return;
  }
  ESP_LOGI(kTag, "BOOT_DIAG stage=renderer_ready");

  // The NimBLE host/PHY must start before the panel to avoid interrupting
  // display DMA during initialization. However, the GATT peripheral must NOT
  // become connectable until LVGL objects and moto_ui_render exist: a phone
  // already scanning during cold boot otherwise streams navigation/map frames
  // while the UI is still being constructed.
  if (ble_result == ESP_OK) {
    ESP_LOGI(kTag, "BOOT_DIAG stage=ble_advertising_start");
    transport.enable_advertising();
    ESP_LOGI(kTag, "BOOT_DIAG stage=ble_advertising_requested");
  }

  // Demo generation fills the retained snapshot in place, but geometry and
  // LVGL projection still use deeper C++ call frames than a trivial task.
  if (xTaskCreate(demo_tick_task, "moto_demo", 6'144, &phone_bridge, 2,
                  nullptr) != pdPASS) {
    ESP_LOGW(kTag, "navigation demo task could not start");
  }

#ifdef MOTO_SIGNAL_AUDIO_BOOT_TEST
  // Run only after the display/BSP has initialized the shared I2C bus.
  // A single quiet tone, then stop I2S and the amplifier. No Wi-Fi.
  signal_start_audio_selftest();
#endif

  ESP_LOGI(kTag,
           "iPhone BLE + QMI heading -> NavPresenter -> shared LVGL running");
  vTaskDelete(nullptr);
}
