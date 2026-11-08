#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace moto::nav {

struct CurveZoomPoint {
  double latitude_deg = 0.0;
  double longitude_deg = 0.0;
};

// Experimental, stateless hairpin detector for the browser simulator.
// The caller supplies the visible selected-route window in provider coords
// and the route-view origin (NOT a WGS84 fix). No dynamic allocations.
inline double experimental_curve_zoom(
    double origin_lat, double origin_lon, double heading_deg,
    const CurveZoomPoint* points, std::size_t count,
    bool heading_reliable = true) noexcept {
  constexpr double kPi = 3.14159265358979323846;
  constexpr double kEarthMetersPerRadian = 6371000.0;
  constexpr std::size_t kMaxPoints = 24;
  constexpr double kChordM = 40.0;
  if (points == nullptr || count < 4 || count > kMaxPoints ||
      !std::isfinite(origin_lat) || !std::isfinite(origin_lon) ||
      !std::isfinite(heading_deg) || std::abs(origin_lat) > 90.0 ||
      std::abs(origin_lon) > 180.0) return 1.0;
  struct XY { double x = 0.0, y = 0.0; };
  std::array<XY, kMaxPoints> poly{};
  std::array<double, kMaxPoints> along{};
  const double lat_scale = (kPi / 180.0) * kEarthMetersPerRadian;
  const double lon_scale = lat_scale * std::cos(origin_lat * kPi / 180.0);
  for (std::size_t i = 0; i < count; ++i) {
    const auto& p = points[i];
    if (!std::isfinite(p.latitude_deg) || !std::isfinite(p.longitude_deg) ||
        std::abs(p.latitude_deg) > 90.0 ||
        std::abs(p.longitude_deg) > 180.0) return 1.0;
    poly[i] = {(p.longitude_deg - origin_lon) * lon_scale,
               (p.latitude_deg - origin_lat) * lat_scale};
    if (i) along[i] = along[i-1] +
        std::hypot(poly[i].x - poly[i-1].x,
                   poly[i].y - poly[i-1].y);
  }
  if (along[count-1] < 2 * kChordM) return 1.0;
  const double heading = heading_deg * kPi / 180.0;
  const XY facing{std::sin(heading), std::cos(heading)};
  double best_score = 1e30, nearest_distance = 1e30, rider_s = 0.0;
  for (std::size_t i = 0; i + 1 < count; ++i) {
    const XY d{poly[i+1].x-poly[i].x, poly[i+1].y-poly[i].y};
    const double len = along[i+1] - along[i];
    if (len < 0.5) continue;
    const double t = std::clamp(
        -(poly[i].x*d.x + poly[i].y*d.y) / (len*len), 0.0, 1.0);
    const double px = poly[i].x + t*d.x, py = poly[i].y + t*d.y;
    const double distance = std::hypot(px, py);
    const double cos_dir = (d.x*facing.x + d.y*facing.y) / len;
    // Nearby antiparallel legs of a hairpin must not be confused. Avoid
    // penalizing direction while stopped or with an unreliable heading.
    const double score = distance +
        (heading_reliable ? 8.0 * (1.0 - cos_dir) : 0.0);
    if (score < best_score) {
      best_score = score;
      nearest_distance = distance;
      rider_s = along[i] + t*len;
    }
  }
  if (nearest_distance > 40.0) return 1.0;
  const auto position = [&](double s) -> XY {
    s = std::clamp(s, 0.0, along[count-1]);
    for (std::size_t j = 0; j + 1 < count; ++j) {
      if (s <= along[j+1] || j + 2 == count) {
        const double len = along[j+1] - along[j];
        const double t = len < 0.001 ? 0.0 : (s - along[j]) / len;
        return {poly[j].x+(poly[j+1].x-poly[j].x)*t,
                poly[j].y+(poly[j+1].y-poly[j].y)*t};
      }
    }
    return poly[count-1];
  };
  double max_zoom = 1.0;
  // Scan a longer section behind the rider: the bend is not finished at
  // its apex, particularly on a narrow hairpin with parallel legs.
  const double first = std::max(kChordM, rider_s - 95.0);
  const double last = std::min(along[count-1] - kChordM, rider_s + 110.0);
  for (double center = first; center <= last; center += 8.0) {
    const XY before = position(center-kChordM), at = position(center),
             after = position(center+kChordM);
    const XY a{at.x-before.x, at.y-before.y};
    const XY b{after.x-at.x, after.y-at.y};
    if (std::hypot(a.x,a.y) < 18.0 || std::hypot(b.x,b.y) < 18.0)
      continue;
    const double radians = std::atan2(
        a.x*b.y-a.y*b.x, a.x*b.x+a.y*b.y);
    const double angle = std::abs(radians) * 180.0 / kPi;
    if (angle < 95.0) continue;
    const double severity = std::clamp((angle-95.0)/55.0, 0.0, 1.0);
    const double ahead = center - rider_s;
    const double proximity = ahead >= 0.0
        ? std::clamp((110.0-ahead)/90.0, 0.0, 1.0)
        : std::clamp((95.0+ahead)/95.0, 0.0, 1.0);
    max_zoom = std::max(max_zoom, 1.0 + 2.0*severity*proximity);
  }
  return max_zoom;
}

}  // namespace moto::nav
