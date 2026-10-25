#include "signal_radio_ui.h"
#include "signal_stations.h"
#include "board_port.h"
#include "esp_log.h"
#include "lvgl.h"
#include <cmath>
#include <cstdint>

namespace moto::signal {
namespace {
constexpr int kSize = 466;
constexpr int kCenter = kSize / 2;
constexpr int kRadius = 189;
constexpr int kButtonsRadius = 144;
constexpr uint32_t kOrange = 0xF59E0B;
constexpr uint32_t kMuted = 0x8E8E93;
constexpr uint32_t kGreen = 0x10B981;
constexpr uint32_t kDark = 0x1C1C1E;
constexpr uint32_t kRed = 0xEF4444;
constexpr float kPi = 3.14159265359f;
static_assert(kMp3StationCount == 12, "Radio dial requires exactly 12 stations");

struct StationDisplay {
  const char* code;
  const char* title;
  const char* subtitle;
  const char* genre;
};
constexpr StationDisplay kDetails[12] = {
  {"RPM", "RADIO\nPARADISE", "Main Mix", "ECLECTIC / ROCK"},
  {"MELL", "RP\nMELLOW", "Mellow Mix", "SOFT ROCK"},
  {"VR70", "VIRGIN\nROCK 70", "Classic 70s", "CLASSIC ROCK"},
  {"VRBL", "VIRGIN\nBALLADS", "Rock Ballads", "ROCK BALLADS"},
  {"VHRD", "VIRGIN\nHARD ROCK", "Hard Rock", "HARD ROCK"},
  {"KEXP", "KEXP\n90.3 FM", "Seattle", "INDIE / ALT"},
  {"CARO", "RADIO\nCAROLINE", "Album Rock", "CLASSIC ROCK"},
  {"STAR", "STAR FM\nBERLIN", "Maximum Rock", "ROCK"},
  {"INDI", "SOMAFM\nINDIE", "Indie Pop Rocks!", "INDIE ROCK"},
  {"U80S", "SOMAFM\nU80S", "Underground 80s", "NEW WAVE"},
  {"EAGL", "181.FM\nEAGLE", "The Eagle", "CLASSIC ROCK"},
  {"BUZZ", "181.FM\nBUZZ", "The Buzz", "ALT ROCK"}
};
RadioUiControl* control = nullptr;
lv_obj_t* labels[12] = {};
lv_obj_t* title_label = nullptr;
lv_obj_t* subtitle_label = nullptr;
lv_obj_t* genre_label = nullptr;
lv_obj_t* count_label = nullptr;
lv_obj_t* air_label = nullptr;
lv_obj_t* wifi_label = nullptr;
lv_obj_t* wifi_button = nullptr;
lv_obj_t* play_label = nullptr;
lv_obj_t* volume_label = nullptr;
int selected = 0;
int volume = 20;
bool playing = false;
bool wifi_connected = false;

void polar(lv_obj_t* object, int radius, float degrees) {
  const float rad = degrees * kPi / 180.0f;
  lv_obj_update_layout(object);
  lv_obj_set_pos(object,
      kCenter + static_cast<int>(radius * std::cos(rad)) - lv_obj_get_width(object) / 2,
      kCenter + static_cast<int>(radius * std::sin(rad)) - lv_obj_get_height(object) / 2);
}
void refresh_selection() {
  for (int i = 0; i < 12; ++i) {
    const int relative = (i - selected + 12) % 12;
    polar(labels[i], kRadius, 270.0f + relative * (360.0f / 12.0f));
    lv_obj_set_style_text_color(labels[i],
        lv_color_hex(relative == 0 ? kOrange : kMuted), 0);
  }
  lv_label_set_text(title_label, kDetails[selected].title);
  lv_label_set_text(subtitle_label, kDetails[selected].subtitle);
  lv_label_set_text(genre_label, kDetails[selected].genre);
  lv_label_set_text_fmt(count_label, "%02d / 12", selected + 1);
}
void select(int index) {
  selected = (index + 12) % 12;
  refresh_selection();
  if (playing) control->play_station.store(selected, std::memory_order_release);
}
void prior(lv_event_t*) { select(selected - 1); }
void next(lv_event_t*) { select(selected + 1); }
void play_stop(lv_event_t*) {
  if (playing) {
    control->stop.store(true, std::memory_order_release);
    playing = false;
    lv_label_set_text(air_label, "STOPPING");
  } else {
    control->play_station.store(selected, std::memory_order_release);
    lv_label_set_text(air_label, "CONNECTING");
  }
  lv_label_set_text(play_label, playing ? LV_SYMBOL_PAUSE : LV_SYMBOL_PLAY);
}
void change_volume(lv_event_t* event) {
  const intptr_t delta = reinterpret_cast<intptr_t>(lv_event_get_user_data(event));
  volume += static_cast<int>(delta);
  if (volume < 0) volume = 0;
  if (volume > 60) volume = 60;
  control->volume.store(volume, std::memory_order_release);
  lv_label_set_text_fmt(volume_label, "VOL %d%%", volume);
}
void wifi_connect(lv_event_t*) {
  // Real network operation occurs on console task, not inside LVGL callback.
  control->wifi_request.store(1, std::memory_order_release);
  lv_label_set_text(wifi_label, "CONNECT...");
}
lv_obj_t* circle_button(lv_obj_t* parent, const char* text, int size,
                        float angle, lv_event_cb_t callback, void* data = nullptr) {
  lv_obj_t* button = lv_button_create(parent);
  lv_obj_set_size(button, size, size);
  lv_obj_set_style_radius(button, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(button, lv_color_hex(kDark), 0);
  lv_obj_set_style_border_color(button, lv_color_hex(0x404045), 0);
  lv_obj_set_style_border_width(button, 1, 0);
  lv_obj_set_style_shadow_width(button, 0, 0);
  lv_obj_add_event_cb(button, callback, LV_EVENT_CLICKED, data);
  lv_obj_t* caption = lv_label_create(button);
  lv_label_set_text(caption, text);
  lv_obj_center(caption);
  polar(button, kButtonsRadius, angle);
  return button;
}
} // namespace

bool radio_ui_start(RadioUiControl& state) {
  if (board_port_init() != ESP_OK) {
    ESP_LOGE("signal_ui", "Display init failed; console remains available");
    return false;
  }
  if (!board_port_lock(2000)) return false;
  control = &state;
  lv_obj_t* screen = lv_screen_active();
  lv_obj_clean(screen);
  lv_obj_set_style_bg_color(screen, lv_color_hex(0x080808), 0);
  lv_obj_set_style_text_color(screen, lv_color_white(), 0);
  lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);

  lv_obj_t* scale = lv_scale_create(screen);
  lv_obj_set_size(scale, 450, 450);
  lv_obj_center(scale);
  lv_scale_set_mode(scale, LV_SCALE_MODE_ROUND_INNER);
  lv_scale_set_angle_range(scale, 360);
  lv_scale_set_rotation(scale, 270);
  lv_scale_set_range(scale, 0, 48);
  lv_scale_set_total_tick_count(scale, 49);
  lv_scale_set_major_tick_every(scale, 4);
  lv_scale_set_label_show(scale, false);
  lv_obj_set_style_length(scale, 5, LV_PART_ITEMS);
  lv_obj_set_style_line_color(scale, lv_color_hex(0x303036), LV_PART_ITEMS);
  lv_obj_set_style_length(scale, 13, LV_PART_INDICATOR);
  lv_obj_set_style_line_color(scale, lv_color_hex(kMuted), LV_PART_INDICATOR);

  for (int i = 0; i < 12; ++i) {
    labels[i] = lv_label_create(screen);
    lv_label_set_text(labels[i], kDetails[i].code);
  }
  lv_obj_t* pointer = lv_label_create(screen);
  lv_label_set_text(pointer, LV_SYMBOL_DOWN);
  lv_obj_set_style_text_color(pointer, lv_color_hex(kOrange), 0);
  lv_obj_align(pointer, LV_ALIGN_TOP_MID, 0, 11);

  count_label = lv_label_create(screen);
  lv_obj_set_style_text_color(count_label, lv_color_hex(kMuted), 0);
  lv_obj_align(count_label, LV_ALIGN_TOP_MID, 0, 56);

  wifi_label = lv_label_create(screen);
  lv_label_set_text(wifi_label, "WIFI OFF");
  lv_obj_set_style_text_color(wifi_label, lv_color_hex(kMuted), 0);
  lv_obj_align(wifi_label, LV_ALIGN_TOP_MID, 0, 82);

  air_label = lv_label_create(screen);
  lv_label_set_text(air_label, "OFF AIR");
  lv_obj_set_style_text_color(air_label, lv_color_hex(kRed), 0);
  lv_obj_align(air_label, LV_ALIGN_TOP_MID, 0, 108);

  title_label = lv_label_create(screen);
  lv_obj_set_width(title_label, 290);
  lv_obj_set_style_text_align(title_label, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_align(title_label, LV_ALIGN_CENTER, 0, -57);
  subtitle_label = lv_label_create(screen);
  lv_obj_align(subtitle_label, LV_ALIGN_CENTER, 0, -6);
  genre_label = lv_label_create(screen);
  lv_obj_set_style_text_color(genre_label, lv_color_hex(kOrange), 0);
  lv_obj_align(genre_label, LV_ALIGN_CENTER, 0, 21);

  volume_label = lv_label_create(screen);
  lv_label_set_text(volume_label, "VOL 20%");
  lv_obj_align(volume_label, LV_ALIGN_CENTER, 0, 67);

  wifi_button = circle_button(screen, LV_SYMBOL_WIFI, 50, 222, wifi_connect);
  lv_obj_set_style_border_color(wifi_button, lv_color_hex(kMuted), 0);

  circle_button(screen, "-", 50, 142, change_volume,
                reinterpret_cast<void*>(static_cast<intptr_t>(-5)));
  circle_button(screen, LV_SYMBOL_PREV, 54, 116, prior);
  lv_obj_t* play_button = circle_button(screen, LV_SYMBOL_PLAY, 64, 90, play_stop);
  lv_obj_set_style_bg_color(play_button, lv_color_white(), 0);
  play_label = lv_obj_get_child(play_button, 0);
  lv_obj_set_style_text_color(play_label, lv_color_black(), 0);
  circle_button(screen, LV_SYMBOL_NEXT, 54, 64, next);
  circle_button(screen, "+", 50, 38, change_volume,
                reinterpret_cast<void*>(static_cast<intptr_t>(5)));
  refresh_selection();

  lv_refr_now(board_port_get_display());
  const esp_err_t shown = board_port_reveal_display();
  board_port_unlock();
  return shown == ESP_OK;
}
void radio_ui_status(const char* station, bool is_playing, int level) {
  if (!control || !board_port_lock(100)) return;
  // Station pointer points to the immutable shared preset table.
  if (station) {
    for (int i = 0; i < 12; ++i) {
      if (station == kMp3Stations[i].name) { selected = i; break; }
    }
    refresh_selection();
  }
  playing = is_playing;
  volume = level;
  lv_label_set_text(air_label, playing ? "ON AIR" : "OFF AIR");
  lv_label_set_text(play_label, playing ? LV_SYMBOL_PAUSE : LV_SYMBOL_PLAY);
  lv_label_set_text_fmt(volume_label, "VOL %d%%", volume);
  board_port_unlock();
}
void radio_ui_wifi_status(bool connected) {
  if (!control || !board_port_lock(100)) return;
  wifi_connected = connected;
  lv_label_set_text(wifi_label, connected ? "WIFI ON" : "WIFI OFF");
  lv_obj_set_style_text_color(wifi_label, lv_color_hex(connected ? kGreen : kMuted), 0);
  lv_obj_set_style_border_color(wifi_button, lv_color_hex(connected ? kGreen : kMuted), 0);
  board_port_unlock();
}
} // namespace moto::signal
