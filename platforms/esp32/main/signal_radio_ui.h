#pragma once
#include <atomic>
#include <cstdint>

// Single-producer (LVGL task), single-consumer (radio console / decoder task)
// control channel. No LVGL calls from the audio task.
namespace moto::signal {
struct RadioUiControl {
  std::atomic<int> play_station{-1};
  std::atomic<bool> stop{false};
  std::atomic<int> volume{-1};
  std::atomic<int> wifi_request{0}; // +1 connect saved Wi-Fi, -1 disconnect
};
bool radio_ui_start(RadioUiControl& control);
void radio_ui_status(const char* station, bool playing, int volume);
}  // namespace moto::signal
