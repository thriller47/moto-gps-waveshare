#include "moto_nav_presenter.hpp"
#include "moto_nav_ui_test_probe.hpp"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

using moto::nav::NavPresenter;
using moto::nav::NavSnapshot;
using moto::nav::NavState;
using moto::nav::NetworkState;
using moto::nav::ManeuverType;
using moto::nav::TrafficLevel;
using moto::nav::DisplayPage;

void test_default_state() {
  NavPresenter presenter;
  const auto& ui = presenter.ui_state();
  CHECK(ui.page == MOTO_UI_PAGE_NAVIGATION);
  CHECK(ui.mode == MOTO_UI_ACQUIRING_FIX);
  CHECK(ui.maneuver == MOTO_UI_STRAIGHT);
  CHECK(ui.traffic == MOTO_TRAFFIC_UNKNOWN);
  CHECK(ui.has_next_maneuver == 0);
  CHECK(ui.distance_to_maneuver_m == 0);
  CHECK(ui.remaining_distance_m == 0);
  CHECK(ui.remaining_time_s == 0);
  CHECK(ui.progress_percent == 0);
  CHECK(ui.speed_kph == 0);
  CHECK(ui.speed_limit_kph == 0);
  CHECK(ui.heading_deg == 0);
  CHECK(ui.route_point_count == 0);
  CHECK(ui.road_point_count == 0);
  CHECK(ui.road_polyline_count == 0);
  CHECK(ui.road_name == nullptr);
  CHECK(ui.next_road_name == nullptr);
}

void test_navigating_state_mapping() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.network = NetworkState::Online;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Right;
  snapshot.next_maneuver.road_name = "Corso Francia";
  snapshot.traffic_ahead = TrafficLevel::Slow;
  snapshot.distance_to_next_maneuver_m = 120.4;
  snapshot.remaining_distance_m = 3500.0;
  snapshot.total_distance_m = 5000.0;
  snapshot.route_progress_m = 1500.0;
  snapshot.remaining_duration_s = 420;
  snapshot.speed_mps = 13.8889F;
  snapshot.speed_limit_kph = 50;
  snapshot.heading_deg = 359.6F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0645, 7.6582};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0645, 7.6582};
  snapshot.route_view_points[1] = {45.0650, 7.6590};

  presenter.update(snapshot);
  const auto& ui = presenter.ui_state();

  CHECK(ui.mode == MOTO_UI_NAVIGATING);
  CHECK(ui.maneuver == MOTO_UI_RIGHT);
  CHECK(ui.traffic == MOTO_TRAFFIC_SLOW);
  CHECK(ui.has_next_maneuver == 1);
  CHECK(ui.distance_to_maneuver_m == 120);
  CHECK(ui.remaining_distance_m == 3500);
  CHECK(ui.remaining_time_s == 420);
  CHECK(ui.progress_percent == 30);
  CHECK(ui.speed_kph == 50);
  CHECK(ui.speed_limit_kph == 50);
  CHECK(ui.heading_deg == 0);
  CHECK(ui.route_point_count == 2);
  CHECK(ui.route_points[0].x == 120);
  CHECK(ui.route_points[0].y == 160);
  CHECK(ui.road_name != nullptr && std::string(ui.road_name) == "Corso Francia");
  CHECK(ui.next_road_name != nullptr &&
        std::string(ui.next_road_name) == "Corso Francia");
}

void test_offline_mode_mapping() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.network = NetworkState::Offline;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().mode == MOTO_UI_OFFLINE);
}

void test_maneuver_mapping_coverage() {
  const struct {
    ManeuverType input;
    moto_maneuver_t expected;
  } kCases[] = {
      {ManeuverType::Continue, MOTO_MANEUVER_STRAIGHT},
      {ManeuverType::SlightLeft, MOTO_MANEUVER_SLIGHT_LEFT},
      {ManeuverType::Left, MOTO_MANEUVER_LEFT},
      {ManeuverType::SharpLeft, MOTO_MANEUVER_LEFT},
      {ManeuverType::SlightRight, MOTO_MANEUVER_SLIGHT_RIGHT},
      {ManeuverType::Right, MOTO_MANEUVER_RIGHT},
      {ManeuverType::SharpRight, MOTO_MANEUVER_RIGHT},
      {ManeuverType::UTurnRight, MOTO_MANEUVER_UTURN},
      {ManeuverType::Roundabout, MOTO_MANEUVER_ROUNDABOUT},
      {ManeuverType::Exit, MOTO_MANEUVER_RIGHT},
      {ManeuverType::Arrive, MOTO_MANEUVER_ARRIVE},
  };

  NavPresenter presenter;
  for (const auto& item : kCases) {
    NavSnapshot snapshot;
    snapshot.state = NavState::Navigating;
    snapshot.has_next_maneuver = true;
    snapshot.next_maneuver.type = item.input;
    presenter.update(snapshot);
    CHECK(presenter.ui_state().maneuver == item.expected);
  }
}

void test_progress_percent_clamping() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.total_distance_m = 1000.0;
  snapshot.route_progress_m = -50.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().progress_percent == 0);

  snapshot.route_progress_m = 1500.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().progress_percent == 100);

  snapshot.state = NavState::Arrived;
  snapshot.route_progress_m = 0.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().progress_percent == 100);
}

void test_speed_saturation() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.speed_mps = -1.0F;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().speed_kph == 0);

  snapshot.speed_mps = 20000.0F;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().speed_kph == 65535);
}

void test_display_page_mapping() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.display_page = DisplayPage::Speed;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().page == MOTO_UI_PAGE_SPEED);

  snapshot.display_page = DisplayPage::Compass;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().page == MOTO_UI_PAGE_COMPASS);

  snapshot.display_page = DisplayPage::Music;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().page == MOTO_UI_PAGE_MUSIC);
}

void test_road_context_mapping() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0645, 7.6582};
  snapshot.road_context_point_count = 3;
  snapshot.road_context_points[0] = {45.0645, 7.6582};
  snapshot.road_context_points[1] = {45.0649, 7.6582};
  snapshot.road_context_points[2] = {45.0649, 7.6590};
  snapshot.road_context_polyline_count = 2;
  snapshot.road_context_polylines[0] = {0, 2, moto::nav::RoadClass::Primary};
  snapshot.road_context_polylines[1] = {1, 2, moto::nav::RoadClass::Residential};

  presenter.update(snapshot);
  const auto& ui = presenter.ui_state();

  CHECK(ui.road_point_count == 3);
  CHECK(ui.road_polyline_count == 2);
  CHECK(ui.road_polylines[0].first_point_index == 0);
  CHECK(ui.road_polylines[0].point_count == 2);
  CHECK(ui.road_polylines[0].road_class == MOTO_UI_ROAD_CLASS_PRIMARY);
  CHECK(ui.road_polylines[1].first_point_index == 1);
  CHECK(ui.road_polylines[1].point_count == 2);
  CHECK(ui.road_polylines[1].road_class == MOTO_UI_ROAD_CLASS_RESIDENTIAL);
}

void test_maneuver_zoom_magnifies_at_fifty_meters() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Right;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0, 9.0};
  // 50m north: at 1 deg lat ~ 111195m, 50m ~ 0.00044966 deg.
  snapshot.route_view_points[1] = {45.0 + 50.0 / 111195.0, 9.0};

  // Cruising baseline (>100 m to maneuver) is 1.0x scale.
  snapshot.distance_to_next_maneuver_m = 250.0;
  presenter.update(snapshot);
  const auto at_baseline = presenter.ui_state().route_points[1];

  // At 50 m to maneuver the scale is 2.5x.
  snapshot.distance_to_next_maneuver_m = 50.0;
  presenter.update(snapshot);
  const auto at_fifty = presenter.ui_state().route_points[1];

  // Heading is north, so points project straight up (decreasing y).
  const int baseline_dy = 160 - at_baseline.y;
  const int fifty_dy = 160 - at_fifty.y;
  CHECK(baseline_dy > 0);
  CHECK(fifty_dy > baseline_dy * 2);
  CHECK(fifty_dy < baseline_dy * 3);
}

void test_maneuver_map_zoom_active_flag() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Left;
  snapshot.distance_to_next_maneuver_m = 150.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 0);

  snapshot.distance_to_next_maneuver_m = 80.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 1);

  snapshot.distance_to_next_maneuver_m = 0.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 1);

  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 0);
}

void test_roundabout_exit_arrow_direction() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Roundabout;

  snapshot.next_maneuver.roundabout_exit = 1;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_RIGHT);

  snapshot.next_maneuver.roundabout_exit = 2;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_STRAIGHT);

  snapshot.next_maneuver.roundabout_exit = 3;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_LEFT);

  snapshot.next_maneuver.roundabout_exit = 4;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_UTURN);

  snapshot.next_maneuver.roundabout_exit = 7;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_ROUNDABOUT);
}

void test_instruction_sentence_never_renders_as_street_name() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;

  snapshot.next_maneuver.road_name =
      "svolta a destra in Via Roma poi prosegui dritto per trecento metri";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_name == nullptr);

  snapshot.next_maneuver.road_name = "Via Roma";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_name != nullptr &&
        std::string(presenter.ui_state().road_name) == "Via Roma");
}

void test_map_scale_does_not_jump_at_maneuver_distance_thresholds() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Right;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0, 9.0};
  snapshot.route_view_points[1] = {45.0 + 30.0 / 111195.0, 9.0};

  // 101 m (just outside maneuver zoom) vs 99 m (just inside).
  snapshot.distance_to_next_maneuver_m = 101.0;
  presenter.update(snapshot);
  const int y_outside = presenter.ui_state().route_points[1].y;

  snapshot.distance_to_next_maneuver_m = 99.0;
  presenter.update(snapshot);
  const int y_inside = presenter.ui_state().route_points[1].y;

  // With continuous interpolation, 2 m of progress moves the 30 m point
  // by at most 2 pixels. A discontinuous step would jump >20 px.
  CHECK(std::abs(y_outside - y_inside) <= 2);
}

void test_near_turn_zooms_route_and_roads_together_and_restores_scale() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Left;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0, 9.0};
  snapshot.route_view_points[1] = {45.0 + 40.0 / 111195.0, 9.0};
  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0, 9.0};
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = {45.0, 9.0};
  snapshot.road_context_points[1] = {45.0 + 40.0 / 111195.0, 9.0};
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2, moto::nav::RoadClass::Primary};

  // 1) Baseline far away.
  snapshot.distance_to_next_maneuver_m = 200.0;
  presenter.update(snapshot);
  const int route_y_base = presenter.ui_state().route_points[1].y;
  const int road_y_base = presenter.ui_state().road_points[1].y;
  CHECK(route_y_base == road_y_base);

  // 2) At 0 m (maximum 4.0x zoom): route and roads still match exactly.
  snapshot.distance_to_next_maneuver_m = 0.0;
  presenter.update(snapshot);
  const auto at_max_zoom = presenter.ui_state().route_points[1];
  CHECK(at_max_zoom.y < route_y_base);
  CHECK(presenter.ui_state().road_points[1].y == at_max_zoom.y);

  // 3) Turn complete: new maneuver announced 250 m ahead. Scale drops back.
  snapshot.distance_to_next_maneuver_m = 250.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == route_y_base);
  CHECK(presenter.ui_state().road_points[1].y == road_y_base);
}

void test_roundabout_dropout_holds_zoom_until_apex_then_releases() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Roundabout;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_id = "test-roundabout";
  snapshot.route_generation = 1;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0, 9.0};
  snapshot.route_view_points[1] = {45.0 + 20.0 / 111195.0, 9.0};
  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0, 9.0};
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = {45.0, 9.0};
  snapshot.road_context_points[1] = {45.0 + 20.0 / 111195.0, 9.0};
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2, moto::nav::RoadClass::Primary};

  // Cruising scale.
  snapshot.distance_to_next_maneuver_m = 250.0;
  presenter.update(snapshot);
  const int base_y = presenter.ui_state().route_points[1].y;

  // Maximum 4x zoom right at entry.
  snapshot.distance_to_next_maneuver_m = 0.0;
  presenter.update(snapshot);
  const auto at_max_zoom = presenter.ui_state().route_points[1];
  CHECK(at_max_zoom.y < base_y);
  CHECK(presenter.ui_state().road_points[1].y == at_max_zoom.y);

  // A transient has_next_maneuver=false at distance 0 is still inside the
  // roundabout: the old immediate 4x -> 1x drop is a visible regression.
  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == at_max_zoom.y);
  CHECK(presenter.ui_state().road_points[1].y == at_max_zoom.y);

  // Moving 20 m through the circuit holds full zoom (apex threshold is 35 m).
  snapshot.route_view_origin = {45.0 + 20.0 / 111195.0, 9.0};
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == at_max_zoom.y);

  // Moving 55 m past apex (total 90 m) smoothly decays back toward 1.0x.
  snapshot.route_view_origin = {45.0 + 90.0 / 111195.0, 9.0};
  presenter.update(snapshot);
  const int mid_y = presenter.ui_state().route_points[1].y;
  CHECK(mid_y > at_max_zoom.y);
  CHECK(mid_y < base_y);

  // Moving past release distance (total 125 m) completely restores 1.0x.
  snapshot.route_view_origin = {45.0 + 125.0 / 111195.0, 9.0};
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == base_y);
}

void test_roundabout_dropout_holds_zoom_between_nearby_circuits() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.route_id = "double-circuit";
  snapshot.route_generation = 1;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = {45.0, 9.0};
  snapshot.route_view_points[1] = {45.0 + 20.0 / 111195.0, 9.0};
  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0, 9.0};
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = {45.0, 9.0};
  snapshot.road_context_points[1] = {45.0 + 20.0 / 111195.0, 9.0};
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2};

  // Exit first roundabout: has_next_maneuver=false, entering hold.
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Roundabout;
  snapshot.distance_to_next_maneuver_m = 7.0;
  presenter.update(snapshot);
  const auto zoom_at_first = presenter.ui_state().route_points[1].y;

  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == zoom_at_first);

  // 40m later: second roundabout announced at 80m. The held zoom must not
  // instantly collapse to the lower 80m maneuver factor.
  snapshot.route_view_origin = {45.0 + 40.0 / 111195.0, 9.0};
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Roundabout;
  snapshot.distance_to_next_maneuver_m = 80.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y < zoom_at_first + 5);

  // New route cancels the previous hold cleanly.
  snapshot.route_id = "different-route";
  snapshot.route_generation = 2;
  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  // Cruising scale restored.
  snapshot.distance_to_next_maneuver_m = 250.0;
  presenter.update(snapshot);
  const int base_y = presenter.ui_state().route_points[1].y;
  CHECK(presenter.ui_state().route_points[1].y == base_y);
}

struct CurveZoomPoint {
  double latitude_deg = 0.0;
  double longitude_deg = 0.0;
};

void test_experimental_curve_zoom_does_not_change_default_or_roundabouts() {
  NavPresenter presenter;
  NavSnapshot s;
  s.state = NavState::Navigating;
  s.route_id = "curve-route";
  s.route_generation = 1;
  s.heading_deg = 90.0F;
  s.has_route_view = true;
  s.route_view_origin = {45.0, 9.0};
  // Ninety-degree bend across 20 m (hairpin radius ~10 m).
  const double lat_step = 10.0 / 111195.0;
  const double lon_step = 10.0 / (111195.0 * std::cos(45.0 * 3.141592653589793 / 180.0));
  s.route_view_point_count = 5;
  s.route_view_points[0] = {45.0, 9.0};
  s.route_view_points[1] = {45.0, 9.0 + lon_step};
  s.route_view_points[2] = {45.0 + lat_step * 0.3, 9.0 + lon_step * 1.5};
  s.route_view_points[3] = {45.0 + lat_step, 9.0 + lon_step * 1.8};
  s.route_view_points[4] = {45.0 + lat_step * 2.0, 9.0 + lon_step * 2.0};
  s.has_road_context = true;
  s.road_context_origin = s.route_view_origin;
  s.road_context_point_count = 2;
  s.road_context_points[0] = s.route_view_points[0];
  s.road_context_points[1] = s.route_view_points[1];
  s.road_context_polyline_count = 1;
  s.road_context_polylines[0] = {0, 2};

  // By default, experimental curve zoom is DISABLED: scale is 1.0x.
  s.distance_to_next_maneuver_m = 500.0;
  presenter.update(s);
  const int default_y = presenter.ui_state().route_points[1].y;

  // Enabling the experiment magnifies the upcoming hairpin.
  presenter.set_experimental_curve_zoom_enabled(true);
  presenter.update(s);
  const int curve_y = presenter.ui_state().route_points[1].y;
  CHECK(curve_y < default_y - 15);
  // Route and road layers remain transformed together.
  CHECK(presenter.ui_state().road_points[1].y == curve_y);

  // Maneuver zoom (e.g. 50 m to right turn = 2.5x) takes precedence when larger.
  s.has_next_maneuver = true;
  s.next_maneuver.type = ManeuverType::Right;
  s.distance_to_next_maneuver_m = 50.0;
  presenter.update(s);
  const int combined_y = presenter.ui_state().route_points[1].y;
  CHECK(combined_y <= curve_y);

  // Roundabouts still retain their exit-hold logic when curve zoom is enabled.
  s.has_next_maneuver = true;
  s.next_maneuver.type = ManeuverType::Roundabout;
  s.distance_to_next_maneuver_m = 0;
  presenter.update(s);
  const int roundabout_zoom = presenter.ui_state().route_points[1].y;

  // Switching off the independent hairpin experiment must not cancel
  // the roundabout hold at the exit: has_next_maneuver can drop to false
  // while the motorcycle is still circulating.
  presenter.set_experimental_curve_zoom_enabled(false);
  s.has_next_maneuver = false;
  presenter.update(s);
  CHECK(presenter.ui_state().route_points[1].y == roundabout_zoom);
}

void test_nov17_replay_zoom_uses_nearby_route_not_stale_maneuver() {
  NavPresenter presenter;
  auto zoom=[&](const auto& pts,double lat,double lon,double turn_m){
    NavPresenter p;
    p.set_geometry_aware_zoom_enabled(true);
    NavSnapshot s;
    s.state=NavState::Navigating;
    s.has_route_view=true;
    s.route_id="nov17";
    s.route_generation=1;
    s.route_view_origin={lat,lon};
    s.route_view_point_count=static_cast<std::uint8_t>(pts.size());
    for(std::size_t i=0;i<pts.size();++i)
      s.route_view_points[i]={pts[i].latitude_deg,pts[i].longitude_deg};
    s.has_next_maneuver=true;
    s.next_maneuver.type=ManeuverType::Roundabout;
    s.distance_to_next_maneuver_m=turn_m;
    p.update(s);
    return p.applied_zoom_factor();
  };
  const std::array<CurveZoomPoint,14> missing_174430{{
    {44.403931,7.830093},{44.403377,7.829516},{44.403329,7.829428},
    {44.403320,7.829348},{44.403335,7.829270},{44.403309,7.829205},
    {44.403259,7.829180},{44.403140,7.829232},{44.403088,7.829213},
    {44.402928,7.829073},{44.402137,7.828264},{44.400565,7.826724},
    {44.400393,7.826621},{44.399920,7.826445}
  }};
  CHECK(zoom(missing_174430,44.403906,7.830140,734.0)>1.40);
  const std::array<CurveZoomPoint,18> phantom_174520{{
    {44.400858,7.826997},{44.400634,7.826783},{44.400456,7.826649},
    {44.399992,7.826481},{44.399753,7.826326},{44.398557,7.825238},
    {44.398454,7.825071},{44.398410,7.824947},{44.398432,7.824800},
    {44.398403,7.824708},{44.398351,7.824649},{44.398263,7.824631},
    {44.398208,7.824660},{44.398166,7.824716},{44.398143,7.824792},
    {44.397999,7.824814},{44.397565,7.824611},{44.397404,7.824568}
  }};
  CHECK(zoom(phantom_174520,44.400842,7.827027,12.0)<1.03);
  const std::array<CurveZoomPoint,18> phantom_174540{{
    {44.399478,7.826080},{44.398557,7.825238},{44.398454,7.825071},
    {44.398410,7.824947},{44.398434,7.824851},{44.398422,7.824751},
    {44.398378,7.824672},{44.398322,7.824634},{44.398263,7.824631},
    {44.398208,7.824660},{44.398166,7.824716},{44.398143,7.824792},
    {44.397999,7.824814},{44.397565,7.824611},{44.397404,7.824568},
    {44.396563,7.824618},{44.396246,7.824575},{44.396055,7.824521}
  }};
  CHECK(zoom(phantom_174540,44.399523,7.825985,74.0)<1.03);
  const std::array<CurveZoomPoint,18> missing_174600{{
    {44.398433,7.824859},{44.398403,7.824708},{44.398322,7.824634},
    {44.398263,7.824631},{44.398208,7.824660},{44.398166,7.824716},
    {44.398143,7.824792},{44.397999,7.824814},{44.397565,7.824611},
    {44.397404,7.824568},{44.396563,7.824618},{44.396193,7.824564},
    {44.395978,7.824491},{44.395633,7.824320},{44.395248,7.824056},
    {44.395207,7.824010},{44.395183,7.823943},{44.395028,7.823880}
  }};
  CHECK(zoom(missing_174600,44.398448,7.824874,18.0)>1.15);
}

void test_missing_or_incomplete_geometry_stays_empty() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {45.0, 9.0};
  snapshot.route_view_point_count = 1;
  snapshot.route_view_points[0] = {45.0, 9.0};
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_point_count == 0);

  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0, 9.0};
  snapshot.road_context_point_count = 1;
  snapshot.road_context_points[0] = {45.0, 9.0};
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 1};
  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_point_count == 0);
  CHECK(presenter.ui_state().road_polyline_count == 0);
}

void test_invalid_road_polyline_indices_are_ignored() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.has_road_context = true;
  snapshot.road_context_origin = {45.0, 9.0};
  snapshot.road_context_point_count = 3;
  snapshot.road_context_points[0] = {45.0, 9.0};
  snapshot.road_context_points[1] = {45.01, 9.0};
  snapshot.road_context_points[2] = {45.02, 9.0};
  snapshot.road_context_polyline_count = 3;
  // Valid.
  snapshot.road_context_polylines[0] = {0, 2};
  // Out of bounds: first_point_index >= point_count.
  snapshot.road_context_polylines[1] = {5, 2};
  // Out of bounds: first + count > point_count.
  snapshot.road_context_polylines[2] = {1, 3};

  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_point_count == 3);
  CHECK(presenter.ui_state().road_polyline_count == 1);
}

void test_nov17_geometry_zoom_is_off_unless_explicitly_enabled() {
  NavPresenter p;
  NavSnapshot s;
  s.state=NavState::Navigating;
  s.route_id="replay1711";
  s.route_generation=1;
  s.has_route_view=true;
  s.route_view_origin={44.4036699,7.8299015};
  s.has_next_maneuver=true;
  s.next_maneuver.type=ManeuverType::Roundabout;
  s.distance_to_next_maneuver_m=652.0;
  // Raw 17:44:35 route vertices include a false bend on a straight road.
  constexpr std::array<CurveZoomPoint,15> points{{
    {44.403753,7.829907},{44.403377,7.829516},
    {44.403329,7.829428},{44.403320,7.829348},
    {44.403335,7.829270},{44.403309,7.829205},
    {44.403259,7.829180},{44.403140,7.829232},
    {44.403088,7.829213},{44.402928,7.829073},
    {44.402137,7.828264},{44.400634,7.826783},
    {44.400456,7.826649},{44.399951,7.826462},
    {44.399723,7.826301}
  }};
  s.route_view_point_count=static_cast<std::uint8_t>(points.size());
  for(std::size_t i=0;i<points.size();++i)
    s.route_view_points[i]={points[i].latitude_deg,points[i].longitude_deg};
  p.update(s);
  CHECK(std::abs(p.applied_zoom_factor()-1.0)<0.001);
  p.set_geometry_aware_zoom_enabled(true);
  p.update(s);
  CHECK(p.applied_zoom_factor()>1.1);
  p.set_geometry_aware_zoom_enabled(false);
  p.update(s);
  CHECK(std::abs(p.applied_zoom_factor()-1.0)<0.001);
  s.distance_to_next_maneuver_m=25.0;
  p.update(s);
  CHECK(p.applied_zoom_factor()>3.0); // legacy roundabout zoom remains
}

}  // namespace

int main() {
  test_default_state();
  test_navigating_state_mapping();
  test_offline_mode_mapping();
  test_maneuver_mapping_coverage();
  test_progress_percent_clamping();
  test_speed_saturation();
  test_display_page_mapping();
  test_road_context_mapping();
  test_maneuver_zoom_magnifies_at_fifty_meters();
  test_maneuver_map_zoom_active_flag();
  test_roundabout_exit_arrow_direction();
  test_instruction_sentence_never_renders_as_street_name();
  test_map_scale_does_not_jump_at_maneuver_distance_thresholds();
  test_near_turn_zooms_route_and_roads_together_and_restores_scale();
  test_roundabout_dropout_holds_zoom_until_apex_then_releases();
  test_roundabout_dropout_holds_zoom_between_nearby_circuits();
  test_experimental_curve_zoom_does_not_change_default_or_roundabouts();
  test_nov17_replay_zoom_uses_nearby_route_not_stale_maneuver();
  test_missing_or_incomplete_geometry_stays_empty();
  test_invalid_road_polyline_indices_are_ignored();
  test_nov17_geometry_zoom_is_off_unless_explicitly_enabled();

  if (failures != 0) {
    std::cerr << failures << " checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All presenter mapping checks passed\n";
  return EXIT_SUCCESS;
}
