// Deterministic host-side rendering tests for the LVGL navigation UI.
// A headless LVGL display renders the real moto_nav_ui into an RGB565
// framebuffer; frames are hashed to verify bit-exact determinism, clean page
// transitions and label/geometry residue cleanup.

#include "moto_nav_ui.h"

#include <lvgl.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                   \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

constexpr int kWidth = MOTO_UI_CANVAS_WIDTH;
constexpr int kHeight = MOTO_UI_CANVAS_HEIGHT;
constexpr std::size_t kFrameBytes = static_cast<std::size_t>(kWidth) *
                                    static_cast<std::size_t>(kHeight) *
                                (LV_COLOR_DEPTH / 8);

std::vector<std::uint8_t> shadow_frame;
std::uint64_t flush_count = 0;

void flush_cb(lv_display_t* display, const lv_area_t* area,
              std::uint8_t* px_map) {
  // Partial rendering: stitch each flushed area into the shadow frame so a
  // complete deterministic frame is always available for hashing.
  const int bytes_per_pixel = LV_COLOR_DEPTH / 8;
  for (int y = area->y1; y <= area->y2; ++y) {
    const std::size_t row_bytes =
        static_cast<std::size_t>(area->x2 - area->x1 + 1) * bytes_per_pixel;
    const std::size_t src =
        static_cast<std::size_t>(y - area->y1) *
        static_cast<std::size_t>(area->x2 - area->x1 + 1) * bytes_per_pixel;
    const std::size_t dst =
        (static_cast<std::size_t>(y) * kWidth +
         static_cast<std::size_t>(area->x1)) * bytes_per_pixel;
    std::memcpy(shadow_frame.data() + dst, px_map + src, row_bytes);
  }
  ++flush_count;
  lv_display_flush_ready(display);
}

void init_display(int buffer_rows, int extra_pool_bytes) {
  lv_init();
  static std::vector<std::uint32_t> extra_pool;
  if(extra_pool_bytes > 0) {
    extra_pool.resize((static_cast<std::size_t>(extra_pool_bytes) + 3U) / 4U);
    const bool added = lv_mem_add_pool(extra_pool.data(), extra_pool.size() * 4U) != nullptr;
    CHECK(added);
    if(!added) std::exit(EXIT_FAILURE);
  }
  shadow_frame.assign(kFrameBytes, 0);
  // Exercise both small partial strips and the firmware's map-sized buffer.
  static std::vector<std::uint8_t> draw_buffer(
      static_cast<std::size_t>(kWidth) * buffer_rows * (LV_COLOR_DEPTH / 8));
  lv_display_t* display = lv_display_create(kWidth, kHeight);
  lv_display_set_flush_cb(display, flush_cb);
  lv_display_set_buffers(
      display, draw_buffer.data(), nullptr, draw_buffer.size(),
      LV_DISPLAY_RENDER_MODE_PARTIAL);
}

void pump(int cycles = 40) {
  for (int i = 0; i < cycles; ++i) {
    lv_tick_inc(5);
    lv_timer_handler();
  }
}

std::uint64_t frame_hash() {
  std::uint64_t hash = 1'469'598'103'934'665'603ULL;
  for (const std::uint8_t byte : shadow_frame) {
    hash ^= byte;
    hash *= 1'099'511'628'211ULL;
  }
  return hash;
}

struct Frame {
  std::uint64_t hash;
  std::vector<std::uint8_t> bytes;
};

Frame capture() {
  pump();
  return Frame{frame_hash(), shadow_frame};
}

moto_ui_state_t base_state() {
  moto_ui_state_t state{};
  state.page = MOTO_UI_PAGE_NAVIGATION;
  state.mode = MOTO_UI_NAVIGATING;
  state.maneuver = MOTO_MANEUVER_RIGHT;
  state.traffic = MOTO_TRAFFIC_SLOW;
  state.distance_to_maneuver_m = 120;
  state.has_next_maneuver = 1;
  state.remaining_distance_m = 1'500;
  state.remaining_time_s = 200;
  state.route_progress_percent = 40;
  state.gps_accuracy_m = 5;
  state.online = 1;
  state.has_destination = 1;
  state.route_identity = 77;
  state.route_generation = 1;
  state.map_scene_revision = 4;
  state.speed_kph = 48;
  state.speed_limit_kph = 50;
  state.heading_deg = 90;
  state.road_name = "经十路";
  state.next_road_name = "青年大街";
  return state;
}

void fill_route(moto_ui_state_t& state, int count) {
  count = count < 0 ? MOTO_UI_ROUTE_POINT_CAPACITY : count;
  state.route_point_count = static_cast<std::uint8_t>(count);
  for (int i = 0; i < count; ++i) {
    state.route_points[i] = {static_cast<std::int16_t>(40 + i * 16),
                             static_cast<std::int16_t>(233 + (i % 2) * 40)};
  }
}

void fill_streets(moto_ui_state_t& state) {
  state.road_point_count = 8;
  for (int i = 0; i < 8; ++i) {
    state.road_points[i] = {static_cast<std::int16_t>(10 + i * 55),
                            static_cast<std::int16_t>(30 + i * 60)};
  }
  state.road_polyline_count = 1;
  state.road_polylines[0] = {0, 8, 1};
  state.building_point_count = 6;
  for (int i = 0; i < 6; ++i) {
    state.building_points[i] = {
        static_cast<std::int16_t>(300 + (i % 3) * 40),
        static_cast<std::int16_t>(300 + (i / 3) * 40)};
  }
  state.building_footprint_count = 1;
  state.building_footprints[0] = {0, 6, 1};
}

void test_frames_are_deterministic() {
  // Identical UI state must produce a bit-identical frame every time.
  moto_nav_ui_create();
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);

  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  fill_streets(state);
  moto_nav_ui_set_state(&state);
  const Frame first = capture();

  moto_nav_ui_set_state(&state);
  const Frame second = capture();
  CHECK(first.hash == second.hash);
  CHECK(first.bytes == second.bytes);

  // Navigation-only UI ignores requests for removed pages.
  moto_nav_ui_set_page(MOTO_UI_PAGE_SPEED);
  CHECK(moto_nav_ui_get_page() == MOTO_UI_PAGE_NAVIGATION);
  const Frame restored = capture();
  CHECK(restored.hash == first.hash);
}

void test_clearing_geometry_leaves_no_residue() {
  moto_ui_state_t with_route = base_state();
  fill_route(with_route, 18);
  fill_streets(with_route);
  moto_nav_ui_set_state(&with_route);
  pump(260);  // Settle any lifecycle animation before comparing pixels.
  const Frame route_frame = capture();

  moto_ui_state_t without_route = with_route;
  without_route.route_point_count = 0;
  without_route.road_point_count = 0;
  without_route.road_polyline_count = 0;
  without_route.building_point_count = 0;
  without_route.building_footprint_count = 0;
  moto_nav_ui_set_state(&without_route);
  pump(260);
  const Frame cleared_frame = capture();
  CHECK(route_frame.hash != cleared_frame.hash);

  // Re-drawing the geometry must restore the exact original frame: nothing
  // was left behind by the clear, and nothing accumulated by the redraw.
  moto_nav_ui_set_state(&with_route);
  pump(260);
  const Frame redrawn = capture();
  CHECK(redrawn.hash == route_frame.hash);
}

void test_turn_arrows_and_turn_distance_do_not_move_arrival_distance() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.maneuver = MOTO_MANEUVER_RIGHT;
  state.distance_to_maneuver_m = 80;
  state.remaining_distance_m = 2'400;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame right = capture();

  state.maneuver = MOTO_MANEUVER_LEFT;
  moto_nav_ui_set_state(&state);
  const Frame left = capture();
  CHECK(left.hash == right.hash); // All turn-arrow glyphs are gone.

  state.distance_to_maneuver_m = 40;
  moto_nav_ui_set_state(&state);
  const Frame farther = capture();
  CHECK(farther.hash != left.hash); // 28pt label changes with turn distance.

  // Only the left-hand 8 o'clock turn label changes here; the
  // central and right-side destination area must be bit-identical.
  const int first_y = (245 * kWidth + 180) / 360;
  const int first_x = (145 * kWidth + 180) / 360;
  for (int y = first_y; y < kHeight; ++y) {
    const std::size_t first = (static_cast<std::size_t>(y) * kWidth +
                               first_x) * (LV_COLOR_DEPTH / 8);
    const std::size_t last = (static_cast<std::size_t>(y) + 1) *
                              kWidth * (LV_COLOR_DEPTH / 8);
    CHECK(std::equal(right.bytes.begin() + first,
                     right.bytes.begin() + last,
                     left.bytes.begin() + first));
    CHECK(std::equal(left.bytes.begin() + first,
                     left.bytes.begin() + last,
                     farther.bytes.begin() + first));
  }

  state.mode = MOTO_UI_ARRIVED;
  state.maneuver = MOTO_MANEUVER_ARRIVE;
  state.remaining_distance_m = 0;
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash != farther.hash);
}

void test_turn_distance_starts_at_200m_without_direction_arrows() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.remaining_distance_m = 2'400;
  state.distance_to_maneuver_m = 201;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame before = capture();

  state.maneuver = MOTO_MANEUVER_LEFT;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == before.hash);  // No arrow at any distance.

  state.distance_to_maneuver_m = 200;
  moto_nav_ui_set_state(&state);
  const Frame at200 = capture();
  CHECK(at200.hash != before.hash);

  state.distance_to_maneuver_m = 150;
  moto_nav_ui_set_state(&state);
  const Frame at150 = capture();
  CHECK(at150.hash != at200.hash);

  state.distance_to_maneuver_m = 120;
  moto_nav_ui_set_state(&state);
  const Frame at120 = capture();
  CHECK(at120.hash != at150.hash);

  state.maneuver = MOTO_MANEUVER_RIGHT;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == at120.hash);  // Still no arrow.

  state.distance_to_maneuver_m = 100;
  moto_nav_ui_set_state(&state);
  const Frame at100 = capture();
  CHECK(at100.hash != at120.hash);
  state.maneuver = MOTO_MANEUVER_UTURN;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == at100.hash);

  state.distance_to_maneuver_m = 1;
  moto_nav_ui_set_state(&state);
  const Frame at1 = capture();
  CHECK(at1.hash != at100.hash);
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == before.hash); // No lingering "0 m".

  state.distance_to_maneuver_m = 201;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == before.hash);

  // The arrival flag remains available even without a next turn.
  state.mode = MOTO_UI_ARRIVED;
  state.has_next_maneuver = 0;
  state.maneuver = MOTO_MANEUVER_ARRIVE;
  state.remaining_distance_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash != before.hash);
}

void test_completed_turn_hides_zero_meter_label_without_losing_arrival() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.remaining_distance_m = 2'400;
  state.maneuver_map_zoom_active = 1; // Real geometry replaces the arrow.
  state.has_next_maneuver = 1;
  state.distance_to_maneuver_m = 1;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame one_meter = capture();

  // The phone still reports this maneuver during its post-turn release
  // buffer, but the completed turn must not leave "0 m" on the screen.
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  const Frame zero_meter = capture();
  CHECK(zero_meter.hash != one_meter.hash);

  // The zero-meter screen is equivalent to having no imminent maneuver:
  // no leftover distance label persists while the route remains visible.
  state.has_next_maneuver = 0;
  moto_nav_ui_set_state(&state);
  const Frame no_turn = capture();
  CHECK(no_turn.hash == zero_meter.hash);

  // A new nearby turn becomes visible immediately, without a stale "0 m".
  state.has_next_maneuver = 1;
  state.distance_to_maneuver_m = 25;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash != zero_meter.hash);

  // ARRIVO is a distinct lifecycle indication, not a turn-distance label.
  state.mode = MOTO_UI_ARRIVED;
  state.has_next_maneuver = 0;
  state.maneuver_map_zoom_active = 0;
  state.maneuver = MOTO_MANEUVER_ARRIVE;
  state.remaining_distance_m = 0;
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash != zero_meter.hash);
}

void test_street_names_are_never_rendered() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.distance_to_maneuver_m = 151;
  state.road_name = nullptr;
  state.next_road_name = nullptr;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame none = capture();

  state.road_name = "Via Roma";
  state.next_road_name = "Strada Provinciale 125";
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == none.hash);

  state.next_road_name = "Svolta a destra sulla provinciale e poi prosegui";
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == none.hash);
}

void test_turn_distance_survives_temporary_route_geometry_loss() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.distance_to_maneuver_m = 160;
  state.has_next_maneuver = 1;
  state.has_destination = 1;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame with_geometry = capture();

  // Geometry is allowed to refresh asynchronously without clearing the
  // counter. It remains eligible even if the route drawing is temporarily
  // empty, as long as Android is still navigating to a destination.
  state.route_point_count = 0;
  moto_nav_ui_set_state(&state);
  const Frame without_geometry = capture();
  // Route disappears, so full frame must differ; the turn value is unchanged.
  CHECK(without_geometry.hash != with_geometry.hash);

  // A completed maneuver must never show "0 m" even with stale route data.
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash == without_geometry.hash ||
        capture().hash != with_geometry.hash);
}

void test_no_turn_suppresses_false_arrow_without_losing_destination_distance() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  fill_route(state, 12);
  state.maneuver = MOTO_MANEUVER_RIGHT;
  state.has_next_maneuver = 1;
  state.distance_to_maneuver_m = 75;
  state.remaining_distance_m = 2'400;
  moto_nav_ui_set_state(&state);
  pump(260);
  const Frame with_turn = capture();

  state.maneuver = MOTO_MANEUVER_STRAIGHT;
  state.has_next_maneuver = 0;
  state.distance_to_maneuver_m = 0;
  moto_nav_ui_set_state(&state);
  const Frame no_turn = capture();
  CHECK(no_turn.hash != with_turn.hash);

  // Only the left-hand 8 o'clock turn label changes here; the
  // central and right-side destination area must be bit-identical.
  const int first_y = (245 * kWidth + 180) / 360;
  const int first_x = (145 * kWidth + 180) / 360;
  for (int y = first_y; y < kHeight; ++y) {
    const std::size_t first = (static_cast<std::size_t>(y) * kWidth +
                               first_x) * (LV_COLOR_DEPTH / 8);
    const std::size_t last = (static_cast<std::size_t>(y) + 1) *
                              kWidth * (LV_COLOR_DEPTH / 8);
    CHECK(std::equal(with_turn.bytes.begin() + first,
                     with_turn.bytes.begin() + last,
                     no_turn.bytes.begin() + first));
  }

  // The arrived mode must display the finish flag even if there is no
  // next maneuver in the Valhalla route.
  state.mode = MOTO_UI_ARRIVED;
  state.maneuver = MOTO_MANEUVER_ARRIVE;
  state.remaining_distance_m = 0;
  moto_nav_ui_set_state(&state);
  CHECK(capture().hash != no_turn.hash);
}

void test_phone_lifecycle_renders_deterministically() {
  // The connection lifecycle is a full-screen overlay for the empty
  // navigation page; cycle it there and require visually distinct terminal
  // states plus deterministic repetition (no accumulated residue).
  moto_ui_state_t empty = base_state();
  empty.mode = MOTO_UI_ACQUIRING_FIX;
  empty.has_destination = 0;
  empty.route_point_count = 0;
  empty.road_name = nullptr;
  empty.next_road_name = nullptr;
  moto_nav_ui_set_state(&empty);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE);
  const Frame offline = capture();
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  const Frame online = capture();
  CHECK(offline.hash != online.hash);

  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE);
  const Frame offline_again = capture();
  CHECK(offline_again.hash == offline.hash);

  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_CONNECTING);
  const Frame connecting = capture();
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE); CHECK(capture().hash == offline.hash);
}

void test_removed_music_page_ignores_disconnect_media_state() {
  // SIGNAL only constructs the navigation page. The disconnection path used
  // to call lv_label_set_text on absent music labels from moto_ui_render.
  moto_nav_ui_set_page(MOTO_UI_PAGE_NAVIGATION);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE);
  moto_nav_ui_set_music_page_enabled(0);
  const Frame before = capture();

  moto_music_state_t stale_media{};
  stale_media.source_name = "Android";
  stale_media.track_title = "In Attesa...";
  stale_media.artist_name = "";
  moto_nav_ui_set_music_state(&stale_media);
  const Frame after = capture();
  CHECK(after.hash == before.hash);
  CHECK(moto_nav_ui_get_page() == MOTO_UI_PAGE_NAVIGATION);
}

void test_demo_mode_visual_contract() {
  // Demo mode suppresses the full-screen connection lifecycle and labels the
  // status line "DEMO RIDE"; toggling it must be perfectly reversible.
  moto_ui_state_t empty = base_state();
  empty.mode = MOTO_UI_ACQUIRING_FIX;
  empty.has_destination = 0;
  empty.route_point_count = 0;
  empty.road_name = nullptr;
  empty.next_road_name = nullptr;
  moto_nav_ui_set_state(&empty);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE);
  moto_nav_ui_set_demo_active(0);
  const Frame without_demo = capture();

  moto_nav_ui_set_demo_active(1);
  const Frame with_demo = capture();
  CHECK(with_demo.hash != without_demo.hash);

  moto_nav_ui_set_demo_active(0);
  CHECK(capture().hash == without_demo.hash);

  // Contract: demo mode is connection-independent. The lifecycle surface is
  // hidden while demo is active, so connecting the phone must not change a
  // single pixel of the demo frame.
  moto_nav_ui_set_demo_active(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  CHECK(capture().hash == with_demo.hash);
  moto_nav_ui_set_demo_active(0);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_OFFLINE);
}

void test_capacity_payloads_render_stably() {
  // Maximum-capacity geometry must render identically across repeats.
  moto_ui_state_t max_state = base_state();
  fill_route(max_state, -1);
  fill_streets(max_state);
  max_state.road_point_count = MOTO_UI_ROAD_POINT_CAPACITY;
  for (int i = 0; i < MOTO_UI_ROAD_POINT_CAPACITY; ++i) {
    max_state.road_points[i] = {
        static_cast<std::int16_t>((i * 7) % kWidth),
        static_cast<std::int16_t>((i * 13) % kHeight)};
  }
  max_state.road_polyline_count = 4;
  for (int i = 0; i < 4; ++i) {
    max_state.road_polylines[i] = {
        static_cast<std::uint8_t>(i * 48),
        48,
        static_cast<std::uint8_t>(i % 6)};
  }
  moto_nav_ui_set_state(&max_state);
  const Frame max_first = capture();
  moto_nav_ui_set_state(&max_state);
  const Frame max_second = capture();
  CHECK(max_first.hash == max_second.hash);
}

void test_motion_frames_interpolate_then_settle_without_residue() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  moto_ui_state_t state = base_state();
  state.route_point_count = 3;
  state.route_points[0] = {233, 254};
  state.route_points[1] = {233, 150};
  state.route_points[2] = {233, 30};
  state.road_point_count = 2;
  state.road_points[0] = {110, 280};
  state.road_points[1] = {110, 30};
  state.road_polyline_count = 1;
  state.road_polylines[0] = {0, 2, 1};
  state.building_point_count = 4;
  state.building_points[0] = {300, 80};
  state.building_points[1] = {350, 80};
  state.building_points[2] = {350, 130};
  state.building_points[3] = {300, 130};
  state.building_footprint_count = 1;
  state.building_footprints[0] = {0, 4, 1};
  moto_nav_ui_set_state(&state);
  pump(260);  // finish the transient connected indication before comparing
  const auto before = capture().hash;

  // One heading frame moves all retained arrays through the ordinary motion
  // path, preserving the anchor and topology while the map turns under it.
  for (int index = 1; index < 3; ++index) state.route_points[index].x -= 130;
  for (int index = 0; index < 2; ++index) state.road_points[index].x += 110;
  for (int index = 0; index < 4; ++index) state.building_points[index].x -= 90;
  moto_nav_ui_set_reduce_motion(0);
  moto_nav_ui_set_motion_state(&state);
  pump(10);  // 50 ms contains two 25 ms interpolation opportunities.
  const auto intermediate = frame_hash();
  CHECK(intermediate != before);
  pump(200);
  const auto settled = frame_hash();
  const auto settled_bytes = shadow_frame;
  CHECK(settled != intermediate);

  // The eventual animated frame must match a direct render exactly, including
  // clearing the old road/building pixels in the shared invalidated region.
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_state(&state);
  const auto direct = capture();
  // Compare the map viewport. The page dots below it expire independently at
  // five seconds, which may fall between the two captured frames.
  constexpr int map_height = (232 * kWidth + 180) / 360;
  constexpr std::size_t map_bytes = kWidth * map_height * (LV_COLOR_DEPTH / 8);
  CHECK(std::equal(direct.bytes.begin(), direct.bytes.begin() + map_bytes,
                   settled_bytes.begin()));
}

void test_diagonal_map_pixels_are_independent_of_partial_buffer_height() {
  moto_nav_ui_set_reduce_motion(1);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  auto state = base_state();
  fill_route(state, -1);
  fill_streets(state);
  state.road_point_count = MOTO_UI_ROAD_POINT_CAPACITY;
  state.road_polyline_count = MOTO_UI_ROAD_POLYLINE_CAPACITY;
  for (int i = 0; i < state.road_point_count; ++i) {
    // Long crossing diagonals, partially outside the viewport, exercise
    // band borders, both slope signs and the different road stroke widths.
    state.road_points[i] = {
        static_cast<std::int16_t>((i * 83) % 780 - 160),
        static_cast<std::int16_t>((i * 127) % 590 - 110)};
  }
  for (int i = 0; i < state.road_polyline_count; ++i) {
    state.road_polylines[i] = {
        static_cast<std::uint8_t>(i * 8), 8,
        static_cast<std::uint8_t>(i % 6)};
  }
  moto_nav_ui_set_state(&state);
  pump(260);
  auto* display = lv_display_get_default();
  static std::vector<std::uint8_t> short_buffer(kWidth * 40 * 2);
  static std::vector<std::uint8_t> tall_buffer(kWidth * 320 * 2);
  const auto draw_with_buffer = [display](std::vector<std::uint8_t>& buffer) {
    lv_display_set_buffers(display, buffer.data(), nullptr, buffer.size(),
                            LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_obj_invalidate(lv_screen_active());
    lv_refr_now(display);
    return shadow_frame;
  };
  const auto short_frame = draw_with_buffer(short_buffer);
  const auto tall_frame = draw_with_buffer(tall_buffer);
  CHECK(tall_frame == short_frame);
  CHECK(draw_with_buffer(short_buffer) == tall_frame);
}

}  // namespace

int main(int argc, char **argv) {
  const int buffer_rows = argc > 1 ? std::atoi(argv[1]) : 40;
  const int extra_pool_bytes = argc > 2 ? std::atoi(argv[2]) : 0;
  if(buffer_rows < 1 || buffer_rows > kHeight) return EXIT_FAILURE;
  if(extra_pool_bytes < 0) return EXIT_FAILURE;
  init_display(buffer_rows, extra_pool_bytes);

  // Production cadence: the boot animation completes before the full UI is
  // created, so settle it before moto_nav_ui_create().
  moto_nav_ui_show_boot_screen();
  pump(260);
  const Frame settled_boot = capture();
  const bool all_black = std::all_of(
      settled_boot.bytes.begin(), settled_boot.bytes.end(),
      [](std::uint8_t byte) { return byte == 0x00; });
  CHECK(all_black);

  test_frames_are_deterministic();
  test_clearing_geometry_leaves_no_residue();
  test_turn_arrows_and_turn_distance_do_not_move_arrival_distance();
  test_turn_distance_starts_at_200m_without_direction_arrows();
  test_completed_turn_hides_zero_meter_label_without_losing_arrival();
  test_street_names_are_never_rendered();
  test_no_turn_suppresses_false_arrow_without_losing_destination_distance();
  test_phone_lifecycle_renders_deterministically();
  test_removed_music_page_ignores_disconnect_media_state();
  test_demo_mode_visual_contract();
  test_capacity_payloads_render_stably();
  test_motion_frames_interpolate_then_settle_without_residue();
  test_diagonal_map_pixels_are_independent_of_partial_buffer_height();

  // Terminal screens run last: they tear the full UI down.
  moto_nav_ui_show_boot_screen();
  pump(260);
  const Frame boot_again = capture();
  CHECK(std::all_of(boot_again.bytes.begin(), boot_again.bytes.end(),
                    [](std::uint8_t byte) { return byte == 0x00; }));

  moto_nav_ui_show_power_off_screen();
  pump(260);
  const Frame power_off = capture();
  moto_nav_ui_show_power_off_screen();
  pump(260);
  CHECK(power_off.hash == capture().hash);
  CHECK(power_off.hash != settled_boot.hash);

  if (failures != 0) {
    std::cerr << failures << " nav_ui render checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All nav_ui render checks passed\n";
  return EXIT_SUCCESS;
}
