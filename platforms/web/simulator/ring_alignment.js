/* Offline diagnostic for the first (priority) MotoMap ring in a BLE replay.
 * Compares transmitted polylines; never changes navigation or drawing.
 * Route is a moving BLE window, not the whole Valhalla itinerary.
 */
(function (root, factory) {
  const analyzer = factory();
  if (typeof module === "object" && module.exports) module.exports = analyzer;
  if (root) root.MotoRingAlignment = analyzer;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const DEG_M = 111195.0;
  const NEAR_METERS = 32.0; // Current Android forward-ring proximity criterion.
  const ON_RING_METERS = 3.0; // Diagnostic only, not a routing/snapping threshold.

  function validPoint(p) {
    return Array.isArray(p) && p.length >= 2 && Number.isFinite(p[0]) &&
      Number.isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;
  }
  function projection(origin) {
    const xScale = DEG_M * Math.cos(origin[0] * Math.PI / 180);
    return p => [(p[1] - origin[1]) * xScale, (p[0] - origin[0]) * DEG_M];
  }
  function length(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }
  function ringFrom(scene) {
    // Road 0 is the priority ring *only when* the Android scene selector
    // selected a circuit. Do not treat arbitrary nearby loops as guidance.
    const points = scene?.roads?.[0]?.points;
    if (!Array.isArray(points) || points.length < 5 || points.length > 192 ||
        !points.every(validPoint)) return null;
    const toXY = projection(points[0]);
    const xy = points.map(toXY);
    if (length(xy[0], xy[xy.length - 1]) > 2.5) return null;
    let perimeter = 0, area2 = 0;
    for (let i = 1; i < xy.length; i++) {
      perimeter += length(xy[i - 1], xy[i]);
      area2 += xy[i - 1][0] * xy[i][1] - xy[i][0] * xy[i - 1][1];
    }
    if (perimeter < 35 || perimeter > 550) return null;
    const compactness = 2 * Math.PI * Math.abs(area2) / (perimeter * perimeter);
    if (compactness < 0.17 || compactness > 1.06) return null;
    return {points, perimeter};
  }
  function pointSegment(p, a, b) {
    const dx = b[0] - a[0], dy = b[1] - a[1];
    const sq = dx * dx + dy * dy;
    const t = sq > 1e-12 ? Math.max(0, Math.min(1,
      ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / sq)) : 0;
    return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
  }
  function segmentsCross(a, b, c, d) {
    const cross = (u, v, w) => (v[0] - u[0]) * (w[1] - u[1]) -
      (v[1] - u[1]) * (w[0] - u[0]);
    const abC = cross(a, b, c), abD = cross(a, b, d);
    const cdA = cross(c, d, a), cdB = cross(c, d, b);
    return (abC * abD < 0 && cdA * cdB < 0);
  }
  function segmentGap(a, b, c, d) {
    if (segmentsCross(a, b, c, d)) return 0;
    return Math.min(pointSegment(a, c, d), pointSegment(b, c, d),
      pointSegment(c, a, b), pointSegment(d, a, b));
  }
  function polylineGap(a, b) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length < 2 || b.length < 2 ||
        !a.every(validPoint) || !b.every(validPoint)) return null;
    const toXY = projection(a[0]), aa = a.map(toXY), bb = b.map(toXY);
    let best = Infinity;
    for (let i = 1; i < aa.length; ++i) {
      for (let j = 1; j < bb.length; ++j) {
        best = Math.min(best, segmentGap(aa[i-1], aa[i], bb[j-1], bb[j]));
        if (best < 0.005) return 0;
      }
    }
    return Number.isFinite(best) ? best : null;
  }
  function inspect(frame) {
    const ring = ringFrom(frame?.scene);
    if (!ring) return {status: "no-ring"};
    const route = frame?.route?.points;
    const gap = polylineGap(ring.points, route);
    if (gap === null) return {status: "no-route", ringPoints: ring.points.length};
    const status = gap <= ON_RING_METERS ? "aligned" :
      gap <= NEAR_METERS ? "nearby" : "separated";
    return {status, gapMeters: gap, ringPoints: ring.points.length};
  }
  return {inspect, ringFrom, polylineGap};
});
