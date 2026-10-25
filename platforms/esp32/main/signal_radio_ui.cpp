#include "signal_radio_ui.h"
#include "signal_stations.h"
#include "board_port.h"
#include "esp_log.h"
#include "lvgl.h"

namespace moto::signal {
namespace {
RadioUiControl* control = nullptr;
lv_obj_t* station_label = nullptr;
lv_obj_t* status_label = nullptr;
lv_obj_t* volume_label = nullptr;
int selected = 0;
int volume = 20;
void select_station(int station) {
  selected = (station + static_cast<int>(kMp3StationCount)) %
             static_cast<int>(kMp3StationCount);
  lv_label_set_text(station_label, kMp3Stations[selected].name);
}
void previous(lv_event_t*) { select_station(selected - 1); }
void next(lv_event_t*) { select_station(selected + 1); }
void play(lv_event_t*) {
  control->play_station.store(selected, std::memory_order_release);
  lv_label_set_text(status_label, "Connecting...");
}
void stop(lv_event_t*) {
  control->stop.store(true, std::memory_order_release);
  lv_label_set_text(status_label, "Stopping...");
}
void adjust_volume(lv_event_t* event) {
  const int delta = static_cast<int>(reinterpret_cast<intptr_t>(lv_event_get_user_data(event)));
  volume += delta;
  if (volume < 0) volume = 0;
  if (volume > 60) volume = 60;
  control->volume.store(volume, std::memory_order_release);
  lv_label_set_text_fmt(volume_label, "VOL %d%%", volume);
}
lv_obj_t* button(lv_obj_t* parent, const char* title, lv_event_cb_t fn,
                 void* data = nullptr) {
  lv_obj_t* obj = lv_button_create(parent);
  lv_obj_set_size(obj, 120, 66);
  lv_obj_add_event_cb(obj, fn, LV_EVENT_CLICKED, data);
  lv_obj_t* label = lv_label_create(obj);
  lv_label_set_text(label, title);
  lv_obj_center(label);
  return obj;
}
}  // namespace

bool radio_ui_start(RadioUiControl& state) {
  if (board_port_init() != ESP_OK) {
    ESP_LOGW("signal_ui", "Display unavailable; serial radio remains usable");
    return false;
  }
  if (!board_port_lock(2000)) return false;
  control = &state;
  lv_obj_t* screen = lv_screen_active();
  lv_obj_set_style_bg_color(screen, lv_color_black(), 0);
  lv_obj_set_style_text_color(screen, lv_color_white(), 0);

  lv_obj_t* title = lv_label_create(screen);
  lv_label_set_text(title, "MOTO GPS  |  RADIO");
  lv_obj_align(title, LV_ALIGN_TOP_MID, 0, 34);
  lv_obj_set_style_text_font(title, &lv_font_montserrat_20, 0);
  station_label = lv_label_create(screen);
  lv_obj_set_width(station_label, 370);
  lv_label_set_long_mode(station_label, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(station_label, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_align(station_label, LV_ALIGN_TOP_MID, 0, 115);
  select_station(0);
  status_label = lv_label_create(screen);
  lv_label_set_text(status_label, "Ready");
  lv_obj_align(status_label, LV_ALIGN_TOP_MID, 0, 178);

  lv_obj_t* nav = lv_obj_create(screen);
  lv_obj_set_size(nav, 425, 82);
  lv_obj_align(nav, LV_ALIGN_TOP_MID, 0, 224);
  lv_obj_set_flex_flow(nav, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(nav, LV_FLEX_ALIGN_SPACE_EVENLY,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  button(nav, "<", previous);
  button(nav, "PLAY", play);
  button(nav, ">", next);

  lv_obj_t* row = lv_obj_create(screen);
  lv_obj_set_size(row, 425, 82);
  lv_obj_align(row, LV_ALIGN_TOP_MID, 0, 318);
  lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_EVENLY,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  button(row, "-", adjust_volume, reinterpret_cast<void*>(static_cast<intptr_t>(-5)));
  volume_label = lv_label_create(row);
  lv_label_set_text(volume_label, "VOL 20%");
  button(row, "+", adjust_volume, reinterpret_cast<void*>(static_cast<intptr_t>(5)));
  lv_obj_t* stop_btn = button(screen, "STOP", stop);
  lv_obj_set_size(stop_btn, 120, 48);
  lv_obj_align(stop_btn, LV_ALIGN_BOTTOM_MID, 0, -12);
  lv_refr_now(board_port_get_display());
  const esp_err_t reveal = board_port_reveal_display();
  board_port_unlock();
  return reveal == ESP_OK;
}

void radio_ui_status(const char* station, bool playing, int level) {
  if (!control || !board_port_lock(100)) return;
  if (station && station_label) lv_label_set_text(station_label, station);
  if (status_label) lv_label_set_text(status_label, playing ? "Playing" : "Stopped");
  if (volume_label) lv_label_set_text_fmt(volume_label, "VOL %d%%", level);
  board_port_unlock();
}
}  // namespace moto::signal
