#pragma once

#include <algorithm>
#include <cstdint>

#include "lvgl.h"

// Conservative fast rejection for gray road edges. Deliberately does NOT
// truncate the line: LVGL's existing draw_map_line logic still clips/bands
// accepted edges and preserves endcaps/antialiasing.
namespace moto_road_segment_clip {

inline bool intersects(const lv_point_precise_t &a,
                       const lv_point_precise_t &b,
                       std::int32_t offset_x, std::int32_t offset_y,
                       std::int32_t stroke_width, const lv_area_t &clip) {
    // +2 ensures the fast test is strictly more permissive than the original
    // draw_map_line test (which expands by stroke_width and truncates to int).
    const float pad = static_cast<float>(stroke_width + 2);
    const float min_x = std::min(static_cast<float>(a.x),
                                 static_cast<float>(b.x)) + offset_x - pad;
    const float max_x = std::max(static_cast<float>(a.x),
                                 static_cast<float>(b.x)) + offset_x + pad;
    const float min_y = std::min(static_cast<float>(a.y),
                                 static_cast<float>(b.y)) + offset_y - pad;
    const float max_y = std::max(static_cast<float>(a.y),
                                 static_cast<float>(b.y)) + offset_y + pad;
    return max_x >= clip.x1 && min_x <= clip.x2 &&
           max_y >= clip.y1 && min_y <= clip.y2;
}

}  // namespace moto_road_segment_clip
