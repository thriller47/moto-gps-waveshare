#include "moto_perf_telemetry.h"
#include "sdkconfig.h"

#if defined(MOTO_ESP_PERF_TELEMETRY_EXPERIMENT)
#if !CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS || !CONFIG_FREERTOS_RUN_TIME_STATS_USING_ESP_TIMER || !CONFIG_FREERTOS_VTASKLIST_INCLUDE_COREID
#error "Enable ESP_TIMER FreeRTOS runtime stats and VTASKLIST_INCLUDE_COREID via sdkconfig.perf.defaults"
#endif

#include <cstdint>
#include <cstdio>
#include <cstring>
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "moto_perf_math.hpp"

namespace {
constexpr char kTag[] = "CORE_PERF";
constexpr int kIntervalMs = 5000;
constexpr UBaseType_t kMaxTasks = 64;
struct Previous {
  TaskHandle_t handle = nullptr;
  UBaseType_t id = 0;
  std::uint64_t runtime = 0;
};
struct Top {
  char name[configMAX_TASK_NAME_LEN]{};
  unsigned pct = 0;
  int affinity = -1;
};
// No heap traffic in periodic snapshots. Keep large buffers out of DMA SRAM.
EXT_RAM_BSS_ATTR static TaskStatus_t snapshot[kMaxTasks];
EXT_RAM_BSS_ATTR static Previous previous[kMaxTasks];
UBaseType_t previous_count = 0;
std::int64_t previous_wall_us = 0;

void save_baseline(UBaseType_t count, std::int64_t wall_us) {
  previous_count = count;
  previous_wall_us = wall_us;
  for(UBaseType_t i = 0; i < count; ++i) {
    previous[i] = {snapshot[i].xHandle, snapshot[i].xTaskNumber,
                   static_cast<std::uint64_t>(snapshot[i].ulRunTimeCounter)};
  }
}

bool includes(const char *s, const char *fragment) {
  return s != nullptr && std::strstr(s, fragment) != nullptr;
}

void sample_once() {
  // uxTaskGetSystemState briefly suspends scheduling. Never invoke from the
  // LVGL, BLE, ISR or navigation task; do not log while it is executing.
  const UBaseType_t live = uxTaskGetNumberOfTasks();
  if(live > kMaxTasks) {
    ESP_LOGW(kTag, "skip=too_many_tasks live=%u limit=%u",
             static_cast<unsigned>(live), static_cast<unsigned>(kMaxTasks));
    previous_count = 0;
    return;
  }
  const std::int64_t start = esp_timer_get_time();
  const UBaseType_t count = uxTaskGetSystemState(snapshot, kMaxTasks, nullptr);
  const std::int64_t cost_us = esp_timer_get_time() - start;
  if(count == 0) {
    ESP_LOGW(kTag, "skip=unavailable_snapshot");
    previous_count = 0;
    return;
  }
  if(previous_count == 0 || start <= previous_wall_us) {
    save_baseline(count, start);
    return;
  }
  const auto elapsed_us = static_cast<std::uint64_t>(start - previous_wall_us);
  bool have_idle0 = false, have_idle1 = false;
  std::uint64_t idle0_us = 0, idle1_us = 0;
  std::uint64_t ui_us = 0, lvgl_us = 0, ble_us = 0;
  Top top[3]{};
  for(UBaseType_t i = 0; i < count; ++i) {
    const auto &task = snapshot[i];
    const char *name = task.pcTaskName != nullptr ? task.pcTaskName : "";
    std::uint64_t spent = 0;
    bool valid = false;
    for(UBaseType_t j = 0; j < previous_count; ++j) {
      if(previous[j].handle == task.xHandle &&
         previous[j].id == task.xTaskNumber) {
        valid = moto_perf::delta(previous[j].runtime,
               static_cast<std::uint64_t>(task.ulRunTimeCounter), spent);
        break;
      }
    }
    if(!valid) continue; // New/deleted task or reset runtime counter.
    if(std::strcmp(name, "IDLE0") == 0) {
      have_idle0 = true; idle0_us = spent; continue;
    }
    if(std::strcmp(name, "IDLE1") == 0) {
      have_idle1 = true; idle1_us = spent; continue;
    }
    if(std::strcmp(name, "moto_ui_render") == 0) ui_us += spent;
    if(includes(name, "lvgl") || includes(name, "LVGL") ||
       includes(name, "lv_adapter")) lvgl_us += spent;
    if(includes(name, "ble") || includes(name, "BLE") ||
       includes(name, "nimble") || includes(name, "NimBLE")) ble_us += spent;
    const unsigned pc = moto_perf::percentage(spent, elapsed_us);
    for(int j = 0; j < 3; ++j) {
      if(pc <= top[j].pct) continue;
      for(int k = 2; k > j; --k) top[k] = top[k - 1];
      std::snprintf(top[j].name, sizeof(top[j].name), "%s", name);
      top[j].pct = pc;
      top[j].affinity = (task.xCoreID == 0 || task.xCoreID == 1)
                            ? static_cast<int>(task.xCoreID) : -1;
      break;
    }
  }
  // Baseline is stored before logging; UART backpressure must never distort
  // the next period's delta.
  save_baseline(count, start);
  const int core0 = have_idle0 ? static_cast<int>(
      moto_perf::busy_from_idle(idle0_us, elapsed_us)) : -1;
  const int core1 = have_idle1 ? static_cast<int>(
      moto_perf::busy_from_idle(idle1_us, elapsed_us)) : -1;
  ESP_LOGI(kTag,
           "CPU0=%d%% CPU1=%d%% UI=%u%% LVGL=%u%% BLE=%u%% sample_us=%lld tasks=%u",
           core0, core1,
           moto_perf::percentage(ui_us, elapsed_us),
           moto_perf::percentage(lvgl_us, elapsed_us),
           moto_perf::percentage(ble_us, elapsed_us),
           static_cast<long long>(cost_us), static_cast<unsigned>(count));
  ESP_LOGI(kTag,
           "TOP1=%s:%u%%:aff%d TOP2=%s:%u%%:aff%d TOP3=%s:%u%%:aff%d",
           top[0].name, top[0].pct, top[0].affinity,
           top[1].name, top[1].pct, top[1].affinity,
           top[2].name, top[2].pct, top[2].affinity);
}

void perf_task(void *) {
  ESP_LOGI(kTag, "enabled period_ms=%d clock=esp_timer cap=%u",
           kIntervalMs, static_cast<unsigned>(kMaxTasks));
  sample_once(); // First baseline, then five-second deltas.
  for(;;) {
    vTaskDelay(pdMS_TO_TICKS(kIntervalMs));
    sample_once();
  }
}
} // namespace
#endif

void moto_perf_telemetry_start() {
#if defined(MOTO_ESP_PERF_TELEMETRY_EXPERIMENT)
  TaskHandle_t handle = nullptr;
#if CONFIG_FREERTOS_TASK_CREATE_ALLOW_EXT_MEM
  const BaseType_t result = xTaskCreateWithCaps(
      perf_task, "moto_core_perf", 5120, nullptr, 1, &handle,
      MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
#else
  const BaseType_t result = xTaskCreate(
      perf_task, "moto_core_perf", 5120, nullptr, 1, &handle);
#endif
  if(result != pdPASS) {
    ESP_LOGW(kTag, "task_start_failed; telemetry disabled");
  }
#endif
}
