/* Moto Chat GPS: parsing e indice eventi del replay, senza dipendenze né rete.
 * Modulo UMD: utilizzabile dal browser e dai test Node.js.
 * I tracciati GPS reali non devono mai essere committati nel repository.
 */
(function (root, factory) {
  const model = factory();
  if (typeof module === "object" && module.exports) module.exports = model;
  if (root) root.MotoReplayModel = model;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const SCHEMA = "moto-replay-v1";
  const MAX_EVENTS = 100000;
  const MAX_ROUTE_POINTS = 24;
  const MAX_ROADS = 24;
  const MAX_ROAD_POINTS = 192;

  function finite(value, fallback = 0) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  }
  function position(p) {
    if (!Array.isArray(p) || p.length < 2) return null;
    const lat = finite(p[0], NaN), lon = finite(p[1], NaN);
    return Number.isFinite(lat) && Number.isFinite(lon) &&
      Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? [lat, lon] : null;
  }
  function routeFrom(event) {
    const points = Array.isArray(event.points) ? event.points
      .map(position).filter(Boolean).slice(0, MAX_ROUTE_POINTS) : [];
    if (points.length < 2) return null;
    return {
      origin: position(event.origin) || points[0], points,
      token: finite(event.route_token, 0),
      generation: finite(event.generation, 1)
    };
  }
  function sceneFrom(event) {
    const roads = [];
    let remaining = MAX_ROAD_POINTS;
    for (const road of (Array.isArray(event.roads) ? event.roads : []).slice(0, MAX_ROADS)) {
      if (remaining < 2) break;
      const points = (Array.isArray(road.points) ? road.points : [])
        .map(position).filter(Boolean).slice(0, remaining);
      if (points.length < 2) continue;
      roads.push({points, clazz: finite(road.road_class ?? road.class, 3)});
      remaining -= points.length;
    }
    return {revision: finite(event.revision), roads, points: MAX_ROAD_POINTS - remaining};
  }
  function readRecords(source) {
    if (typeof source !== "string" || !source.trim()) throw Error("Tracciato vuoto");
    const input = source.trim();
    let records;
    if (input.startsWith("[") || input.startsWith("{")) {
      try {
        const json = JSON.parse(input);
        if (Array.isArray(json)) records = json;
        else if (json && Array.isArray(json.events)) {
          if (json.schema && json.schema !== SCHEMA) throw Error("Schema non supportato: " + json.schema);
          records = json.events;
        } else if (!input.includes("\n")) throw Error("Formato JSON senza eventi");
      } catch (error) {
        if (!input.includes("\n") || error.message.startsWith("Schema")) throw error;
      }
    }
    if (!records) records = input.split(/\r?\n/).filter(line => line.trim()).map((line, i) => {
      try { return JSON.parse(line); }
      catch { throw Error("JSONL non valido alla riga " + (i + 1)); }
    });
    if (!Array.isArray(records) || records.length > MAX_EVENTS) {
      throw Error("Numero di eventi non valido (max " + MAX_EVENTS + ")");
    }
    return records.map((record, original) => ({...record, original}))
      .sort((a,b) => finite(a.t_ms) - finite(b.t_ms) || a.original - b.original);
  }
  function findFrameAtOrAfter(frames, t) {
    if (!frames.length) return -1;
    let lo = 0, hi = frames.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (frames[mid].t < t) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }
  function parse(source) {
    const records = readRecords(source);
    const counts = {fix:0, route:0, scene:0, snapshot:0, waypoint:0};
    let route = null, scene = null, routeId = null, lastManeuver = null;
    let arrived = false, metadata = null;
    const frames = [], events = [];
    const mark = (kind, t, label) => events.push({kind, t, label});
    for (const e of records) {
      if (!e || typeof e !== "object" || Array.isArray(e)) continue;
      if (e.schema && e.schema !== SCHEMA) throw Error("Schema non supportato: " + e.schema);
      const t = Math.max(0, finite(e.t_ms));
      if (e.type === "meta" && metadata === null) metadata = e;
      if (Object.hasOwn(counts, e.type)) counts[e.type]++;
      if (e.type === "route") {
        const next = routeFrom(e);
        if (!next) continue;
        const id = String(next.token) + ":" + String(next.generation);
        if (id !== routeId) {
          mark(routeId === null ? "start" : "reroute", t,
            routeId === null ? "Avvio della rotta" : "Cambio rotta (token BLE)");
          routeId = id;
        }
        route = next;
      } else if (e.type === "scene") {
        scene = sceneFrom(e);
      } else if (e.type === "waypoint") {
        mark("waypoint", t, "Tappa " + finite(e.reached) + "/" + finite(e.total) + " raggiunta");
      } else if (e.type === "snapshot") {
        if (!position([e.lat, e.lon])) continue;
        const maneuver = String(e.maneuver || "unknown");
        if (maneuver === "roundabout" && lastManeuver !== "roundabout") {
          mark("roundabout", t, "Inizio guida rotatoria");
        }
        if (e.state === "arrived" && !arrived) {
          mark("arrived", t, "Arrivo finale"); arrived = true;
        }
        lastManeuver = maneuver;
        frames.push({t, snapshot:e, route, scene});
      }
    }
    if (!frames.length) throw Error("Nessuno snapshot con coordinate valide");
    events.forEach(e => { e.frameIndex = findFrameAtOrAfter(frames, e.t); });
    return {frames, events, counts, metadata,
      durationMs: frames.at(-1).t - frames[0].t,
      firstMs: frames[0].t, lastMs: frames.at(-1).t};
  }
  return {parse, findFrameAtOrAfter, finite, position, routeFrom, sceneFrom};
});
