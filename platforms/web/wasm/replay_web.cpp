// Browser trace player: the same NavPresenter and LVGL view used by ESP32.
// No GPS provider, BLE transport or synthetic road generation is hidden here.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>

#include <emscripten.h>
#include "moto_nav_presenter.hpp"
#include "moto_nav_ui.h"
#include "map_origin_predictor.hpp"

namespace {
using namespace moto::nav;
NavPresenter replay_presenter;
NavSnapshot trace;
std::uint32_t serial = 0;
std::uint8_t active_road_start = 0;
bool collecting_road = false;
// Replay-only comparison, not the full BLE+IMU pipeline. Geometry event
// arrivals are treated as anchors, and recorded time drives dead reckoning.
MapOriginPredictor replay_predictor;
std::uint32_t replay_geometry_sequence = 0;
MapOriginPredictor::Position replay_geometry_anchor{};
bool replay_predictor_enabled = false;

bool valid_point(double lat, double lon) {
  return std::isfinite(lat) && std::isfinite(lon) &&
      lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0;
}
void paint() {
  replay_presenter.update(trace);
  replay_presenter.apply_to_lvgl();
}
}

extern "C" {
EMSCRIPTEN_KEEPALIVE void moto_web_replay_reset() {
  trace = {};
  trace.state = NavState::Navigating;
  trace.network = NetworkState::Offline;
  trace.display_page = DisplayPage::Navigation;
  trace.has_usable_fix = true;
  trace.has_destination = true;
  trace.route_id = "browser-replay";
  trace.route_generation = 1;
  trace.has_road_context = false;
  serial = 0;
  collecting_road = false;
  replay_predictor = MapOriginPredictor{};
  replay_geometry_sequence = 0;
  replay_geometry_anchor = {};
  moto_nav_ui_set_demo_active(0);
  moto_nav_ui_set_phone_connection(MOTO_UI_PHONE_ONLINE);
  paint();
}

// A/B comparison of ESP's isolated position predictor (no physical IMU
// measurements and no actual BLE delivery timestamps are in the JSONL).
EMSCRIPTEN_KEEPALIVE void moto_web_replay_set_predictor_enabled(int enabled) {
  replay_predictor_enabled = enabled != 0;
  replay_predictor = MapOriginPredictor{};
}

// A/B comparison of geometry-aware zoom, only for the browser replay.
// Turning the experiment off restores exactly the existing maneuver zoom.
EMSCRIPTEN_KEEPALIVE void moto_web_replay_set_curve_zoom_enabled(int enabled) {
  replay_presenter.set_experimental_curve_zoom_enabled(enabled != 0);
  paint();
}
EMSCRIPTEN_KEEPALIVE double moto_web_replay_zoom_factor() {
  return replay_presenter.applied_zoom_factor();
}

// Route geometry is an Android->BLE local view, not the full Valhalla route.
// Coordinates are copied unchanged from the trace (no GCJ02 conversion).
EMSCRIPTEN_KEEPALIVE void moto_web_replay_route_begin(
    double origin_lat, double origin_lon, int generation, int token) {
  trace.has_route_view = false;
  trace.route_view_point_count = 0;
  if (valid_point(origin_lat, origin_lon)) {
    trace.route_view_origin = {origin_lat, origin_lon};
    replay_geometry_anchor = {origin_lat, origin_lon};
    ++replay_geometry_sequence;
  }
  trace.route_generation = static_cast<std::uint32_t>(std::max(1, generation));
  trace.route_id = "replay-" + std::to_string(
      static_cast<std::uint32_t>(token));
  trace.has_destination = true;
}
EMSCRIPTEN_KEEPALIVE void moto_web_replay_route_point(
    double lat, double lon) {
  if (!valid_point(lat, lon) ||
      trace.route_view_point_count >= kRouteViewPointCapacity) return;
  trace.route_view_points[trace.route_view_point_count++] = {lat, lon};
  trace.has_route_view = trace.route_view_point_count >= 2;
}

// A new scene clears old context atomically; road spans never merge unrelated
// ways. A malformed road is discarded without spending a BLE slot.
EMSCRIPTEN_KEEPALIVE void moto_web_replay_scene_begin(int revision) {
  trace.road_context_point_count = 0;
  trace.road_context_polyline_count = 0;
  trace.has_road_context = false;
  trace.map_scene_revision =
      static_cast<std::uint32_t>(std::max(0, revision));
  collecting_road = false;
}
EMSCRIPTEN_KEEPALIVE void moto_web_replay_road_begin() {
  collecting_road = trace.road_context_polyline_count <
      kRoadContextPolylineCapacity &&
      trace.road_context_point_count < kRoadContextPointCapacity - 1;
  active_road_start = trace.road_context_point_count;
}
EMSCRIPTEN_KEEPALIVE void moto_web_replay_road_point(
    double lat, double lon) {
  if (!collecting_road || !valid_point(lat, lon) ||
      trace.road_context_point_count >= kRoadContextPointCapacity) return;
  trace.road_context_points[trace.road_context_point_count++] = {lat, lon};
}
EMSCRIPTEN_KEEPALIVE void moto_web_replay_road_end(int road_class) {
  if (!collecting_road) return;
  collecting_road = false;
  const int length = trace.road_context_point_count - active_road_start;
  if (length < 2) {
    trace.road_context_point_count = active_road_start;
    return;
  }
  trace.road_context_polylines[trace.road_context_polyline_count++] = {
    active_road_start, static_cast<std::uint8_t>(length),
    static_cast<RoadContextClass>(std::clamp(road_class, 0, 5))
  };
  trace.has_road_context = true;
}

// 15 ordinary numeric args so the JS player can call cwrap without new
// dependencies. Snapshot/route/scene are separate trace events.
EMSCRIPTEN_KEEPALIVE void moto_web_replay_snapshot(
    int state, int maneuver, double lat, double lon, double heading,
    double speed_mps, double turn_m, double remaining_m,
    double total_m, double duration_s, double progress_m,
    int speed_limit, int roundabout_exit, int network, int has_turn,
    double replay_time_ms) {
  trace.state = static_cast<NavState>(std::clamp(state, 0, 5));
  trace.network = static_cast<NetworkState>(std::clamp(network, 0, 2));
  trace.has_usable_fix = valid_point(lat, lon);
  trace.gnss_stale = !trace.has_usable_fix;
  if (trace.has_usable_fix) {
    trace.position = {lat, lon};
    trace.route_view_origin = {lat, lon};
  }
  trace.heading_deg = std::isfinite(heading) ?
      static_cast<float>(heading) : 0.0F;
  trace.speed_mps = std::isfinite(speed_mps) ?
      static_cast<float>(std::max(0.0, speed_mps)) : 0.0F;
  trace.has_next_maneuver = has_turn != 0;
  trace.next_maneuver.type = static_cast<ManeuverType>(
      std::clamp(maneuver, 0, 12));
  trace.next_maneuver.roundabout_exit =
      static_cast<std::uint8_t>(std::clamp(roundabout_exit, 0, 32));
  trace.distance_to_next_maneuver_m = std::isfinite(turn_m) ?
      std::max(0.0, turn_m) : 0.0;
  trace.remaining_distance_m = std::isfinite(remaining_m) ?
      std::max(0.0, remaining_m) : 0.0;
  trace.total_distance_m = std::isfinite(total_m) ?
      std::max(0.0, total_m) : 0.0;
  trace.route_progress_m = std::isfinite(progress_m) ?
      std::max(0.0, progress_m) : 0.0;
  trace.remaining_duration_s = std::isfinite(duration_s) ?
      static_cast<std::uint32_t>(std::clamp(duration_s, 0.0, 4294967295.0)) : 0;
  trace.speed_limit_kph = static_cast<std::uint16_t>(
      std::clamp(speed_limit, 0, 300));
  trace.now_ms = ++serial;
  if (replay_predictor_enabled && trace.has_usable_fix &&
      trace.has_route_view && replay_geometry_sequence > 0 &&
      std::isfinite(replay_time_ms) && replay_time_ms >= 0.0) {
    const auto predicted = replay_predictor.advance(
        replay_geometry_anchor,
        static_cast<std::uint64_t>(replay_time_ms), replay_geometry_sequence,
        trace.route_id, trace.route_generation, trace.speed_mps,
        trace.heading_deg, trace.has_usable_fix);
    trace.route_view_origin = {predicted.latitude_deg,
                               predicted.longitude_deg};
  }
  paint();
}
}