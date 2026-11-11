#pragma once

#include "moto_nav_ui.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

// The moving BLE route window is simplified with many vertices at bends and
// very few on straight roads. Equal vertex indices are NOT equal geographic
// positions. Match new vertices by their fractional distance along the green
// line before interpolating, so no false diagonal is ever invented.
namespace moto_nav_route_sampling {

inline void align_by_arc_fraction(
    const float *current_x, const float *current_y, std::uint8_t current_count,
    const float *target_x, const float *target_y, std::uint8_t target_count,
    float *out_x, float *out_y) {
    if(current_count < 2 || target_count < 2 ||
       current_count > MOTO_UI_ROUTE_POINT_CAPACITY ||
       target_count > MOTO_UI_ROUTE_POINT_CAPACITY) return;

    float cumulative[MOTO_UI_ROUTE_POINT_CAPACITY]{};
    float target_cumulative[MOTO_UI_ROUTE_POINT_CAPACITY]{};
    for(std::uint8_t i = 1; i < current_count; ++i) {
        cumulative[i] = cumulative[i - 1] +
            std::hypot(current_x[i] - current_x[i - 1],
                       current_y[i] - current_y[i - 1]);
    }
    for(std::uint8_t i = 1; i < target_count; ++i) {
        target_cumulative[i] = target_cumulative[i - 1] +
            std::hypot(target_x[i] - target_x[i - 1],
                       target_y[i] - target_y[i - 1]);
    }
    const float total = cumulative[current_count - 1];
    const float target_total = target_cumulative[target_count - 1];
    std::uint8_t segment = 1;
    for(std::uint8_t i = 0; i < target_count; ++i) {
        const float position = target_total > 0.001F
            ? target_cumulative[i] / target_total
            : static_cast<float>(i) / static_cast<float>(target_count - 1);
        const float along = total * position;
        while(segment + 1 < current_count &&
              cumulative[segment] < along) ++segment;
        const float length = cumulative[segment] - cumulative[segment - 1];
        const float fraction = length > 0.001F
            ? std::clamp((along - cumulative[segment - 1]) / length,
                         0.0F, 1.0F)
            : 0.0F;
        out_x[i] = current_x[segment - 1] +
            fraction * (current_x[segment] - current_x[segment - 1]);
        out_y[i] = current_y[segment - 1] +
            fraction * (current_y[segment] - current_y[segment - 1]);
    }
}

} // namespace moto_nav_route_sampling
