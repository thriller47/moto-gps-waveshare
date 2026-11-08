#include "moto_nav_presenter.hpp"

#ifdef MOTO_NAV_UI_TEST_STUB
#include "moto_nav_ui_test_probe.hpp"
#endif

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>

namespace {

using namespace moto::nav;

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

void test_all_navigation_and_network_states() {
  struct StateCase {
    NavState input;
    moto_ui_mode_t expected;
  };
  constexpr std::array<StateCase, 6> cases{{
      {NavState::Idle, MOTO_UI_ACQUIRING_FIX},
      {NavState::Acquiring, MOTO_UI_ACQUIRING_FIX},
      {NavState::Planning, MOTO_UI_ACQUIRING_FIX},
      {NavState::Navigating, MOTO_UI_NAVIGATING},
      {NavState::Rerouting, MOTO_UI_REROUTING},
      {NavState::Arrived, MOTO_UI_ARRIVED},
  }};

  NavPresenter presenter;
  for (const StateCase& test : cases) {
    NavSnapshot snapshot;
    snapshot.state = test.input;
    snapshot.network = NetworkState::Online;
    presenter.update(snapshot);
    CHECK(presenter.ui_state().mode == test.expected);
    CHECK(presenter.ui_state().online == 1);
  }

  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.network = NetworkState::Offline;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().mode == MOTO_UI_OFFLINE);
  CHECK(presenter.ui_state().online == 0);

  snapshot.network = NetworkState::Connecting;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().mode == MOTO_UI_NAVIGATING);
  CHECK(presenter.ui_state().online == 0);

  snapshot.state = NavState::Rerouting;
  snapshot.network = NetworkState::Offline;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().mode == MOTO_UI_REROUTING);
  CHECK(presenter.ui_state().online == 0);

  snapshot.has_destination = true;
  snapshot.route_request_in_flight = true;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().has_destination == 1);
  CHECK(presenter.ui_state().route_request_in_flight == 1);
}

void test_display_pages_map_without_changing_navigation_mode() {
  struct PageCase {
    DisplayPage input;
    moto_ui_page_t expected;
  };
  constexpr std::array<PageCase, 4> cases{{
      {DisplayPage::Navigation, MOTO_UI_PAGE_NAVIGATION},
      {DisplayPage::Speed, MOTO_UI_PAGE_SPEED},
      {DisplayPage::Compass, MOTO_UI_PAGE_COMPASS},
      {DisplayPage::Music, MOTO_UI_PAGE_MUSIC},
  }};
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Rerouting;
  for(const PageCase& test : cases) {
    snapshot.display_page = test.input;
    presenter.update(snapshot);
    CHECK(presenter.ui_state().page == test.expected);
    CHECK(presenter.ui_state().mode == MOTO_UI_REROUTING);
  }
}

void test_all_maneuvers() {
  struct ManeuverCase {
    ManeuverType input;
    moto_maneuver_t expected;
  };
  constexpr std::array<ManeuverCase, 13> cases{{
      {ManeuverType::Unknown, MOTO_MANEUVER_STRAIGHT},
      {ManeuverType::Continue, MOTO_MANEUVER_STRAIGHT},
      {ManeuverType::SlightLeft, MOTO_MANEUVER_SLIGHT_LEFT},
      {ManeuverType::Left, MOTO_MANEUVER_LEFT},
      {ManeuverType::SharpLeft, MOTO_MANEUVER_LEFT},
      {ManeuverType::UTurnLeft, MOTO_MANEUVER_UTURN},
      {ManeuverType::SlightRight, MOTO_MANEUVER_SLIGHT_RIGHT},
      {ManeuverType::Right, MOTO_MANEUVER_RIGHT},
      {ManeuverType::SharpRight, MOTO_MANEUVER_RIGHT},
      {ManeuverType::UTurnRight, MOTO_MANEUVER_UTURN},
      {ManeuverType::Roundabout, MOTO_MANEUVER_ROUNDABOUT},
      {ManeuverType::Exit, MOTO_MANEUVER_RIGHT},
      {ManeuverType::Arrive, MOTO_MANEUVER_ARRIVE},
  }};

  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  for (const ManeuverCase& test : cases) {
    snapshot.next_maneuver.type = test.input;
    presenter.update(snapshot);
    CHECK(presenter.ui_state().maneuver == test.expected);
  }

  snapshot.has_next_maneuver = false;
  snapshot.state = NavState::Arrived;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_ARRIVE);
  CHECK(presenter.ui_state().has_next_maneuver == 0);

  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Left;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().has_next_maneuver == 1);
  CHECK(presenter.ui_state().maneuver == MOTO_MANEUVER_LEFT);
}

void test_all_traffic_levels() {
  struct TrafficCase {
    TrafficLevel input;
    moto_traffic_t expected;
  };
  constexpr std::array<TrafficCase, 5> cases{{
      {TrafficLevel::Unknown, MOTO_TRAFFIC_UNKNOWN},
      {TrafficLevel::FreeFlow, MOTO_TRAFFIC_CLEAR},
      {TrafficLevel::Slow, MOTO_TRAFFIC_SLOW},
      {TrafficLevel::Congested, MOTO_TRAFFIC_CONGESTED},
      {TrafficLevel::Severe, MOTO_TRAFFIC_SEVERE},
  }};

  NavPresenter presenter;
  NavSnapshot snapshot;
  for (const TrafficCase& test : cases) {
    snapshot.traffic_ahead = test.input;
    presenter.update(snapshot);
    CHECK(presenter.ui_state().traffic == test.expected);
  }
}

void test_metrics_are_rounded_clamped_and_deterministic() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.route_id = "route-a";
  snapshot.state = NavState::Navigating;
  snapshot.has_usable_fix = true;
  snapshot.distance_to_next_maneuver_m = 12.6;
  snapshot.remaining_distance_m = 1'000.49;
  snapshot.remaining_duration_s = 721;
  snapshot.route_progress_m = 50.0;
  snapshot.total_distance_m = 199.0;
  snapshot.horizontal_accuracy_m = 3.6F;
  snapshot.speed_mps = 8.0F;
  snapshot.heading_deg = 359.6F;
  snapshot.speed_limit_kph = 70;
  presenter.update(snapshot);

  const moto_ui_state_t& state = presenter.ui_state();
  CHECK(state.distance_to_maneuver_m == 13);
  CHECK(state.remaining_distance_m == 1'000);
  CHECK(state.remaining_time_s == 721);
  CHECK(state.route_progress_percent == 25);
  CHECK(state.gps_accuracy_m == 4);
  CHECK(state.speed_kph == 29);
  CHECK(state.heading_deg == 0);
  CHECK(state.speed_limit_kph == 70);
  CHECK(state.route_identity != 0);
  const std::uint32_t first_route_identity = state.route_identity;

  snapshot.route_id = "route-b";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_identity != first_route_identity);

  snapshot.distance_to_next_maneuver_m = -2.0;
  snapshot.remaining_distance_m =
      static_cast<double>(std::numeric_limits<std::uint32_t>::max()) +
      1'000.0;
  snapshot.route_progress_m = 250.0;
  snapshot.total_distance_m = 200.0;
  snapshot.horizontal_accuracy_m = 300.0F;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().distance_to_maneuver_m == 0);
  CHECK(presenter.ui_state().remaining_distance_m ==
        std::numeric_limits<std::uint32_t>::max());
  CHECK(presenter.ui_state().route_progress_percent == 100);
  CHECK(presenter.ui_state().gps_accuracy_m == 255);

  snapshot.state = NavState::Arrived;
  snapshot.route_progress_m = 0.0;
  snapshot.total_distance_m = 0.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_progress_percent == 100);
}

void test_heading_up_route_projection_keeps_rider_fixed() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.heading_deg = 90.0F;
  snapshot.distance_to_next_maneuver_m = 500.0;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {31.2304, 121.4737};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {31.2304, 121.4747};
  presenter.update(snapshot);

  const moto_ui_state_t& state = presenter.ui_state();
  CHECK(state.route_point_count == 2);
  constexpr int rider_x = MOTO_UI_CANVAS_WIDTH / 2;
  constexpr int rider_y =
      (196 * MOTO_UI_CANVAS_HEIGHT + 180) / 360;
  CHECK(state.route_points[0].x == rider_x);
  CHECK(state.route_points[0].y == rider_y);
  // Facing east means an eastbound route is drawn straight ahead/up while
  // the vehicle marker itself remains at the fixed shared-view anchor.
  CHECK(std::abs(state.route_points[1].x - rider_x) <= 1);
  CHECK(state.route_points[1].y < state.route_points[0].y);
}

void test_native_projection_stays_pixel_quantized_and_layers_aligned() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {44.4, 7.8};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  // A 0.1 m displacement at 1x is subpixel on the physical display.
  snapshot.route_view_points[1] = {44.4 + 0.1 / 111195.0, 7.8};
  snapshot.has_road_context = true;
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = snapshot.route_view_points[0];
  snapshot.road_context_points[1] = snapshot.route_view_points[1];
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2};
  presenter.update(snapshot);
  const auto& ui = presenter.ui_state();
  CHECK(ui.route_point_count == 2);
  CHECK(ui.road_point_count == 2);
  CHECK(ui.route_points[0].x == ui.route_points[1].x);
  CHECK(ui.route_points[0].y == ui.route_points[1].y);
  CHECK(ui.road_points[0].x == ui.route_points[0].x);
  CHECK(ui.road_points[0].y == ui.route_points[0].y);
  CHECK(ui.road_points[1].x == ui.route_points[1].x);
  CHECK(ui.road_points[1].y == ui.route_points[1].y);
}

void test_non_finite_heading_keeps_map_projection_finite() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.heading_deg = std::numeric_limits<float>::quiet_NaN();
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {31.2304, 121.4737};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {31.2314, 121.4737};
  presenter.update(snapshot);

  const moto_ui_state_t& state = presenter.ui_state();
  CHECK(state.heading_deg == 0);
  CHECK(state.route_point_count == 2);
  constexpr int rider_x = MOTO_UI_CANVAS_WIDTH / 2;
  constexpr int rider_y =
      (196 * MOTO_UI_CANVAS_HEIGHT + 180) / 360;
  CHECK(state.route_points[0].x == rider_x);
  CHECK(state.route_points[0].y == rider_y);
}

void test_real_road_context_uses_the_same_heading_up_transform() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.heading_deg = 90.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {36.67, 117.13};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {36.67, 117.131};

  snapshot.has_road_context = true;
  snapshot.road_context_point_count = 4;
  snapshot.road_context_points[0] = snapshot.route_view_origin;
  snapshot.road_context_points[1] = {36.67, 117.131};
  snapshot.road_context_points[2] = {36.669, 117.13};
  snapshot.road_context_points[3] = {36.668, 117.13};
  snapshot.road_context_polyline_count = 2;
  snapshot.road_context_polylines[0] = {0, 2};
  snapshot.road_context_polylines[1] = {2, 2};
  presenter.update(snapshot);

  const moto_ui_state_t& state = presenter.ui_state();
  CHECK(state.road_point_count == 4);
  CHECK(state.road_polyline_count == 2);
  CHECK(state.road_polylines[0].first_point_index == 0);
  CHECK(state.road_polylines[0].point_count == 2);
  CHECK(state.road_polylines[1].first_point_index == 2);
  CHECK(state.road_polylines[1].point_count == 2);
  CHECK(state.road_points[0].x == state.route_points[0].x);
  CHECK(state.road_points[0].y == state.route_points[0].y);
  CHECK(state.road_points[1].x == state.route_points[1].x);
  CHECK(state.road_points[1].y == state.route_points[1].y);
}

void test_map_scene_classes_and_capacity_share_route_transform() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.heading_deg = 90.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {36.67, 117.13};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {36.67, 117.131};

  snapshot.has_road_context = true;
  snapshot.road_context_point_count = kRoadContextPointCapacity;
  snapshot.road_context_polyline_count = kRoadContextPolylineCapacity;
  for (std::size_t index = 0; index < kRoadContextPointCapacity; ++index) {
    snapshot.road_context_points[index] = {
        36.67 + static_cast<double>(index) * 0.000001,
        117.13 + static_cast<double>(index) * 0.000001,
    };
  }
  for (std::size_t index = 0; index < kRoadContextPolylineCapacity;
       ++index) {
    snapshot.road_context_polylines[index] = {
        static_cast<std::uint8_t>(index * 8), 8,
        index == 0 ? RoadContextClass::Motorway
                   : RoadContextClass::Service,
    };
  }

  presenter.update(snapshot);
  const moto_ui_state_t& state = presenter.ui_state();
  CHECK(state.road_point_count == MOTO_UI_ROAD_POINT_CAPACITY);
  CHECK(state.road_polyline_count == MOTO_UI_ROAD_POLYLINE_CAPACITY);
  CHECK(state.road_polylines[0].road_class ==
        static_cast<std::uint8_t>(RoadContextClass::Motorway));
  CHECK(state.road_polylines[1].road_class ==
        static_cast<std::uint8_t>(RoadContextClass::Service));
  // Both road and route coordinates share one heading-up projection.
  CHECK(state.road_points[0].x == state.route_points[0].x);
  CHECK(state.road_points[0].y == state.route_points[0].y);
}

void test_map_scale_does_not_jump_at_maneuver_distance_thresholds() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {36.67, 117.13};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {36.671, 117.13};
  snapshot.has_road_context = true;
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = snapshot.route_view_origin;
  snapshot.road_context_points[1] = {36.671, 117.13};
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2};

  constexpr std::array<double, 8> distances{{
      89.9, 90.0, 90.1, 259.9, 260.0, 260.1, 699.9, 700.1,
  }};
  moto_ui_point_t expected_route{};
  moto_ui_point_t expected_road{};
  bool have_expected = false;
  for (double distance : distances) {
    snapshot.distance_to_next_maneuver_m = distance;
    presenter.update(snapshot);
    const moto_ui_state_t& state = presenter.ui_state();
    CHECK(state.route_point_count == 2);
    CHECK(state.road_point_count == 2);
    if (!have_expected) {
      expected_route = state.route_points[1];
      expected_road = state.road_points[1];
      have_expected = true;
    } else {
      CHECK(state.route_points[1].x == expected_route.x);
      CHECK(state.route_points[1].y == expected_route.y);
      CHECK(state.road_points[1].x == expected_road.x);
      CHECK(state.road_points[1].y == expected_road.y);
    }
  }
}


void test_near_turn_zooms_route_and_roads_together_and_restores_scale() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Roundabout;
  snapshot.heading_deg = 0.0F;
  snapshot.has_route_view = true;
  snapshot.route_view_origin = {36.67, 117.13};
  snapshot.route_view_point_count = 2;
  snapshot.route_view_points[0] = snapshot.route_view_origin;
  snapshot.route_view_points[1] = {36.6704, 117.13};
  snapshot.has_road_context = true;
  snapshot.road_context_point_count = 2;
  snapshot.road_context_points[0] = snapshot.route_view_origin;
  snapshot.road_context_points[1] = snapshot.route_view_points[1];
  snapshot.road_context_polyline_count = 1;
  snapshot.road_context_polylines[0] = {0, 2};
  snapshot.distance_to_next_maneuver_m = 101.0;
  presenter.update(snapshot);
  const auto far = presenter.ui_state().route_points[1];
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 0);
  snapshot.distance_to_next_maneuver_m = 100.0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_points[1].y == far.y);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 1);

  snapshot.distance_to_next_maneuver_m = 45.0;
  presenter.update(snapshot);
  const auto close = presenter.ui_state();
  CHECK(close.route_points[1].y < far.y);
  CHECK(close.road_points[1].x == close.route_points[1].x);
  CHECK(close.road_points[1].y == close.route_points[1].y);
  CHECK(close.maneuver_map_zoom_active == 1);

  snapshot.distance_to_next_maneuver_m = 0.0;
  presenter.update(snapshot);
  const auto at_max_zoom = presenter.ui_state().route_points[1];
  CHECK(at_max_zoom.y < close.route_points[1].y);
  // All map layers use the same precise maximum scale: 4.0x.
  constexpr double rider_y = 196.0 * MOTO_UI_CANVAS_HEIGHT / 360.0;
  const double baseline_offset = rider_y - far.y;
  const double zoom_offset = rider_y - at_max_zoom.y;
  CHECK(std::abs(zoom_offset - 4.0 * baseline_offset) <= 4.0);

  // Without MotoMap / Overpass roads, navigation still shows the green
  // route at full 4.0x. This is the real-world case where the legacy
  // arrow fallback used to be selected.
  snapshot.has_road_context = false;
  snapshot.road_context_point_count = 0;
  snapshot.road_context_polyline_count = 0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 1);
  CHECK(presenter.ui_state().road_point_count == 0);
  CHECK(presenter.ui_state().route_points[1].y == at_max_zoom.y);
  // Completing the roundabout releases zoom even without scene data.
  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 0);
  CHECK(presenter.ui_state().route_points[1].y == far.y);

  // Missing selected-route geometry is different from missing optional
  // background map data: do not try to zoom without route vertices.
  snapshot.has_next_maneuver = true;
  snapshot.has_route_view = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().maneuver_map_zoom_active == 0);
  CHECK(presenter.ui_state().route_point_count == 0);
}


void test_experimental_curve_zoom_does_not_change_default_or_roundabouts() {
  NavPresenter presenter;
  NavSnapshot s;
  s.state = NavState::Navigating;
  s.heading_deg = 90.0F;
  s.speed_mps = 9.0F;
  s.has_route_view = true;
  s.route_view_origin = {44.4, 7.8};

  const double latitude = 44.4, longitude = 7.8;
  const double lat_scale = 111194.9266;
  const double lon_scale = lat_scale * std::cos(latitude * 3.14159265358979323846 / 180.0);
  // Tight synthetic hairpin: geometry must produce >=1.5x so the
  // presenter's deliberate curve-hold gate is actually exercised.
  // The previous shallow fixture only produced ~1.25x, so expecting
  // the 55 m hold after switching to a straight window was incorrect.
  const std::array<std::array<double, 2>, 9> xy{{
      {{-30,0}},{{0,0}},{{30,0}},{{39,6}},{{40,15}},
      {{39,24}},{{30,30}},{{5,30}},{{-25,30}}
  }};
  s.route_view_point_count = static_cast<std::uint8_t>(xy.size());
  for (std::size_t i=0;i<xy.size();++i) {
    s.route_view_points[i] = {
        latitude + xy[i][1]/lat_scale,
        longitude + xy[i][0]/lon_scale
    };
  }

  presenter.update(s);
  CHECK(presenter.applied_zoom_factor() == 1.0);  // simulator OFF by default
  presenter.set_experimental_curve_zoom_enabled(true);
  presenter.update(s);
  CHECK(presenter.applied_zoom_factor() >= 1.5);
  CHECK(presenter.applied_zoom_factor() <= 3.0);

  // The rider has passed the apex and is travelling west along the return
  // leg. Zoom must remain active through the second half of the hairpin.
  // Previously the 40 m backward window dropped the bend too early.
  s.route_view_origin = {
      latitude + 40.0/lat_scale,
      longitude + 15.0/lon_scale
  };
  s.heading_deg = 270.0F;
  presenter.update(s);
  CHECK(presenter.applied_zoom_factor() >= 1.5); // hold gate remains active
  CHECK(presenter.applied_zoom_factor() <= 3.0);

  // When Android slides its 24-point window away from the hairpin,
  // zoom must not collapse at the apex. Simulate movement along a
  // straight local replacement window in 10 m GPS steps.
  s.route_view_point_count = 2;
  s.route_view_points[0] = {latitude + 40.0/lat_scale,
                            longitude + 15.0/lon_scale};
  s.route_view_points[1] = {latitude + 180.0/lat_scale,
                            longitude + 15.0/lon_scale};
  s.heading_deg = 0.0F;
  for (int step=1; step<=5; ++step) {
    s.route_view_origin.latitude_deg = latitude + (40.0+step*10.0)/lat_scale;
    presenter.update(s);
  }
  CHECK(presenter.applied_zoom_factor() > 1.05);
  for (int step=6; step<=15; ++step) {
    s.route_view_origin.latitude_deg = latitude + (40.0+step*10.0)/lat_scale;
    presenter.update(s);
  }
  CHECK(std::abs(presenter.applied_zoom_factor()-1.0) < 0.001);

  s.has_next_maneuver = true;
  s.next_maneuver.type = ManeuverType::Roundabout;
  s.distance_to_next_maneuver_m = 0;
  presenter.update(s);
  CHECK(std::abs(presenter.applied_zoom_factor()-4.0) < 0.001);

  presenter.set_experimental_curve_zoom_enabled(false);
  s.has_next_maneuver = false;
  presenter.update(s);
  CHECK(presenter.applied_zoom_factor() == 1.0);
  s.has_route_view = false;
  presenter.set_experimental_curve_zoom_enabled(true);
  presenter.update(s);
  CHECK(presenter.applied_zoom_factor() == 1.0);
}

void test_missing_or_incomplete_geometry_stays_empty() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.network = NetworkState::Online;
  snapshot.distance_to_next_maneuver_m = 300.0;

  // Navigation metadata is not route geometry. The formal display must wait
  // for phone-provided points instead of inventing a decorative route.
  snapshot.has_route_view = false;
  snapshot.route_view_point_count = 0;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_point_count == 0);

  snapshot.has_route_view = true;
  snapshot.route_view_point_count = 1;
  snapshot.route_view_points[0] = {31.2304, 121.4737};
  presenter.update(snapshot);
  CHECK(presenter.ui_state().route_point_count == 0);
}

void test_presenter_owns_road_name_storage() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Right;
  snapshot.next_maneuver.road_name = "中山路";
  snapshot.next_maneuver.instruction = "右转";
  presenter.update(snapshot);

  snapshot.next_maneuver.road_name.assign(2'048, 'x');
  snapshot.next_maneuver.instruction.clear();
  CHECK(presenter.ui_state().road_name != nullptr);
  CHECK(presenter.ui_state().next_road_name != nullptr);
  CHECK(std::strcmp(presenter.ui_state().road_name, "中山路") == 0);
  CHECK(std::strcmp(presenter.ui_state().next_road_name, "中山路") == 0);

  snapshot.next_maneuver.road_name.clear();
  snapshot.next_maneuver.instruction = "靠左行驶";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_name == nullptr);
  // Do not use the maneuver instruction as a road label.
  CHECK(presenter.ui_state().next_road_name == nullptr);

  snapshot.has_next_maneuver = false;
  presenter.update(snapshot);
  CHECK(presenter.ui_state().road_name == nullptr);
  CHECK(presenter.ui_state().next_road_name == nullptr);
}

void test_street_arc_rejects_instructions_and_overlong_names() {
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Right;
  snapshot.next_maneuver.instruction =
      "Svolta a destra sulla provinciale e poi prosegui sulla statale";

  // A valid name remains available, even when the instruction is long.
  snapshot.next_maneuver.road_name = "Strada Provinciale 125";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name != nullptr);
  CHECK(std::strcmp(presenter.ui_state().next_road_name,
                    "Strada Provinciale 125") == 0);

  // Empty road fields must never fall back to a spoken route sentence.
  snapshot.next_maneuver.road_name.clear();
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name == nullptr);
  CHECK(presenter.ui_state().road_name == nullptr);

  // Misclassified directions in the road-name field must be suppressed.
  snapshot.next_maneuver.road_name =
      "Svolta a destra sulla provinciale e poi prosegui";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name == nullptr);

  snapshot.next_maneuver.road_name = "Prosegui sulla SS 125";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name == nullptr);

  // No arbitrary truncation or UTF-8 cutting on very long names.
  snapshot.next_maneuver.road_name = std::string(80, 'A');
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name == nullptr);
  snapshot.next_maneuver.road_name = "Via della Repubblica";
  presenter.update(snapshot);
  CHECK(presenter.ui_state().next_road_name != nullptr);
  CHECK(std::strcmp(presenter.ui_state().next_road_name,
                    "Via della Repubblica") == 0);
}

#ifdef MOTO_NAV_UI_TEST_STUB
void test_apply_to_lvgl_uses_last_owned_state() {
  moto::test::reset_nav_ui_probe();
  NavPresenter presenter;
  NavSnapshot snapshot;
  snapshot.state = NavState::Navigating;
  snapshot.has_next_maneuver = true;
  snapshot.next_maneuver.type = ManeuverType::Left;
  snapshot.next_maneuver.road_name = "环城北路";
  presenter.update(snapshot);
  presenter.apply_to_lvgl();

  CHECK(moto::test::nav_ui_apply_count() == 1);
  const moto_ui_state_t& applied =
      moto::test::last_applied_nav_ui_state();
  CHECK(applied.maneuver == MOTO_MANEUVER_LEFT);
  CHECK(applied.next_road_name != nullptr);
  CHECK(std::strcmp(applied.next_road_name, "环城北路") == 0);
}
#endif

}  // namespace

int main() {
  test_all_navigation_and_network_states();
  test_display_pages_map_without_changing_navigation_mode();
  test_all_maneuvers();
  test_all_traffic_levels();
  test_metrics_are_rounded_clamped_and_deterministic();
  test_heading_up_route_projection_keeps_rider_fixed();
  test_native_projection_stays_pixel_quantized_and_layers_aligned();
  test_non_finite_heading_keeps_map_projection_finite();
  test_real_road_context_uses_the_same_heading_up_transform();
  test_map_scene_classes_and_capacity_share_route_transform();
  test_map_scale_does_not_jump_at_maneuver_distance_thresholds();
  test_near_turn_zooms_route_and_roads_together_and_restores_scale();
  test_experimental_curve_zoom_does_not_change_default_or_roundabouts();
  test_missing_or_incomplete_geometry_stays_empty();
  test_presenter_owns_road_name_storage();
  test_street_arc_rejects_instructions_and_overlong_names();
#ifdef MOTO_NAV_UI_TEST_STUB
  test_apply_to_lvgl_uses_last_owned_state();
#endif

  if (failures != 0) {
    std::cerr << failures << " nav_presenter checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All nav_presenter checks passed\n";
  return EXIT_SUCCESS;
}
