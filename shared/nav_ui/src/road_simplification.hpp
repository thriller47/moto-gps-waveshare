#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "moto_nav_ui.h"

// Display-space Douglas-Peucker simplification for gray context roads only.
// Each span is independent: its first and last point are always retained.
// Short spans and sharp local turns are retained to protect roundabout exits
// and visible intersections. Maximum deviation is 1.25 display pixels.
// Recomputed only when snapped/interpolated screen pixels change.
namespace moto_road_simplification {
template <typename Point>
void retain(const Point* points, unsigned count, std::uint8_t* keep) {
  if(count == 0 || count > MOTO_UI_ROAD_POINT_CAPACITY) return;
  std::fill_n(keep, count, static_cast<std::uint8_t>(0));
  if(count <= 8) {
    std::fill_n(keep, count, static_cast<std::uint8_t>(1));
    return;
  }
  keep[0] = keep[count - 1] = 1;
  for(unsigned i = 1; i + 1 < count; ++i) {
    const float ax = static_cast<float>(points[i].x - points[i - 1].x);
    const float ay = static_cast<float>(points[i].y - points[i - 1].y);
    const float bx = static_cast<float>(points[i + 1].x - points[i].x);
    const float by = static_cast<float>(points[i + 1].y - points[i].y);
    const float a2 = ax * ax + ay * ay;
    const float b2 = bx * bx + by * by;
    // Keep sharp bends when both legs are substantial (>=3 screen pixels).
    if(a2 >= 9.0F && b2 >= 9.0F &&
       ax * bx + ay * by < 0.819152F * std::sqrt(a2 * b2)) {
      keep[i] = 1;
    }
  }
  struct Range { unsigned first, last; };
  std::array<Range, MOTO_UI_ROAD_POINT_CAPACITY> stack{};
  unsigned top = 0;
  unsigned from = 0;
  // Mandatory corners split the RDP search; never discard these vertices.
  for(unsigned to = 1; to < count; ++to) {
    if(!keep[to]) continue;
    if(to > from + 1) stack[top++] = {from, to};
    from = to;
  }
  constexpr float kToleranceSquared = 1.25F * 1.25F;
  while(top > 0) {
    const Range range = stack[--top];
    const float ax = static_cast<float>(points[range.first].x);
    const float ay = static_cast<float>(points[range.first].y);
    const float bx = static_cast<float>(points[range.last].x);
    const float by = static_cast<float>(points[range.last].y);
    const float dx = bx - ax, dy = by - ay;
    const float length2 = dx * dx + dy * dy;
    float max_distance2 = 0.0F;
    unsigned farthest = range.first;
    for(unsigned i = range.first + 1; i < range.last; ++i) {
      const float px = static_cast<float>(points[i].x) - ax;
      const float py = static_cast<float>(points[i].y) - ay;
      const float t = length2 > 0.0F
          ? std::clamp((px * dx + py * dy) / length2, 0.0F, 1.0F) : 0.0F;
      const float ex = px - t * dx, ey = py - t * dy;
      const float distance2 = ex * ex + ey * ey;
      if(distance2 > max_distance2) {
        max_distance2 = distance2;
        farthest = i;
      }
    }
    if(max_distance2 > kToleranceSquared && farthest > range.first) {
      keep[farthest] = 1;
      // At most count-1 ranges can exist on the stack.
      if(farthest + 1 < range.last) stack[top++] = {farthest, range.last};
      if(range.first + 1 < farthest) stack[top++] = {range.first, farthest};
    }
  }
}
} // namespace moto_road_simplification
