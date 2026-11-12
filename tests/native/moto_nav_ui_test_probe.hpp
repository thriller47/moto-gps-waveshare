#pragma once

#include "moto_nav_ui.h"

namespace moto::test {

void reset_nav_ui_probe();
int nav_ui_apply_count();
int waypoint_notice_events();
int waypoint_clear_events();
uint8_t last_waypoint_notice_index();
uint8_t last_waypoint_notice_total();
const moto_ui_state_t& last_applied_nav_ui_state();

}  // namespace moto::test
