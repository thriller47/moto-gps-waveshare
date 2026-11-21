#!/usr/bin/env python3
"""Road DNA source benchmark; never alters an installed MotoPack.

Input: real Valhalla/Moto GeoJSON route(s), Italy PBF optional, API keys optional.
Default is OFFLINE ONLY. Online queries require --online and environment keys.
This is a research utility, not an Android implementation or a POI inventory.
"""
import argparse
import collections
import json
import math
import os
import sqlite3
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

# These labels are the benchmark vocabulary, not exhaustive OSM semantics.
TAGS = {
    ("tourism", "viewpoint"): "belvedere",
    ("historic", "castle"): "castello",
    ("historic", "monument"): "monumento",
    ("historic", "archaeological_site"): "archeologia",
    ("historic", "ruins"): "rovine",
    ("tourism", "museum"): "museo",
    ("tourism", "attraction"): "attrazione",
    ("natural", "beach"): "spiaggia",
    ("natural", "hot_spring"): "terme_naturali",
    ("leisure", "park"): "parco",
    ("leisure", "garden"): "giardino",
    ("leisure", "nature_reserve"): "riserva",
    ("boundary", "protected_area"): "riserva",
    ("natural", "peak"): "vetta",
    ("waterway", "waterfall"): "cascata",
}

GEOAPIFY = [
    "tourism.attraction.viewpoint",
    "tourism.sights.castle",
    "tourism.sights.memorial.monument",
    "tourism.sights.archaeological_site",
    "entertainment.museum",
    "natural.water",
    "natural.water.hot_spring",
    "natural.mountain.peak",
    "natural.protected_area",
    "national_park",
    "beach",
]


def classify(tags):
    found = {label for (key, val), label in TAGS.items()
             if tags.get(key) == val}
    if tags.get("natural") == "water" and tags.get("water") == "lake":
        found.add("lago")
    if tags.get("amenity") == "public_bath" and tags.get("bath:type") == "thermal":
        found.add("terme_stabilimento")
    return found


def valid(lon, lat):
    return math.isfinite(lon) and math.isfinite(lat) and -180 <= lon <= 180 and -90 <= lat <= 90


def parse_route(path):
    # GPX track/route is accepted without third-party converters.
    if Path(path).suffix.lower() == ".gpx":
        from xml.etree import ElementTree as ET
        root = ET.parse(path).getroot()
        for wanted in ("trkpt", "rtept"):
            coords = []
            for item in root.iter():
                if item.tag.rsplit("}", 1)[-1] != wanted:
                    continue
                lon, lat = float(item.attrib["lon"]), float(item.attrib["lat"])
                if not valid(lon, lat):
                    raise ValueError(f"{path}: invalid GPX track coordinate")
                if not coords or coords[-1] != [lon, lat]:
                    coords.append([lon, lat])
            if len(coords) >= 2:
                return coords
        raise ValueError(f"{path}: no GPX track with at least 2 points")
    root = json.loads(Path(path).read_text(encoding="utf-8"))
    if root.get("type") == "FeatureCollection":
        geoms = [x["geometry"] for x in root.get("features", [])]
    elif root.get("type") == "Feature":
        geoms = [root["geometry"]]
    elif root.get("type") in ("LineString", "MultiLineString"):
        geoms = [root]
    else:
        # Moto Chat GPS route envelope. Geometry objects use latitude_deg,
        # longitude_deg; array coordinates are GeoJSON [lon,lat].
        data = next((root[k] for k in ("geometry_gcj02", "polyline", "coordinates")
                     if isinstance(root.get(k), list)), None)
        if data is None:
            raise ValueError(f"{path}: not GeoJSON LineString or Moto route JSON")
        coords = []
        for entry in data:
            if isinstance(entry, dict):
                coords.append([entry.get("longitude_deg", entry.get("longitude")),
                               entry.get("latitude_deg", entry.get("latitude"))])
            elif isinstance(entry, list) and len(entry) >= 2:
                coords.append(entry[:2])
        geoms = [{"type": "LineString", "coordinates": coords}]
    lines = []
    for geo in geoms:
        if geo.get("type") == "LineString":
            lines.append(geo.get("coordinates", []))
        elif geo.get("type") == "MultiLineString":
            lines.extend(geo.get("coordinates", []))
    points = []
    for line in lines:
        for p in line:
            if len(p) < 2:
                continue
            lon, lat = float(p[0]), float(p[1])
            if not valid(lon, lat):
                raise ValueError(f"{path}: invalid geographic coordinate")
            if not points or points[-1] != [lon, lat]:
                points.append([lon, lat])
    if len(points) < 2:
        raise ValueError(f"{path}: requires at least two distinct route points")
    return points


def bbox(points, meters):
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    delta_lat = meters / 111_000
    delta_lon = delta_lat / max(.15, math.cos(math.radians(sum(lats) / len(lats))))
    return (min(lons) - delta_lon, min(lats) - delta_lat,
            max(lons) + delta_lon, max(lats) + delta_lat)


def meters_from_route(lon, lat, route):
    # Local equirectangular segment projection; suitable for short Italian
    # route segments, not for precise polygon topology or road accessibility.
    scale_x = 111_320 * math.cos(math.radians(lat))
    scale_y = 111_320
    pts = [((x - lon) * scale_x, (y - lat) * scale_y) for x, y in route]
    best = float("inf")
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        dx, dy = bx - ax, by - ay
        t = max(0., min(1., -(ax * dx + ay * dy) / (dx * dx + dy * dy))) if dx or dy else 0.
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


def route_matches(positions, routes, radius):
    matches = []
    if not positions:
        return matches
    for name, line in routes.items():
        box = bbox(line, radius)
        # Use way vertices, not solely centroid; still not a full
        # multipolygon intersection or correct entrance to an extensive area.
        near = False
        for lon, lat in positions:
            if box[0] <= lon <= box[2] and box[1] <= lat <= box[3]:
                if meters_from_route(lon, lat, line) <= radius:
                    near = True
                    break
        if near:
            matches.append(name)
    return matches


def pbf_benchmark(pbf, db_path, routes, radius):
    try:
        import osmium
    except ImportError as exc:
        raise RuntimeError("Install pyosmium in WSL: pip install osmium") from exc
    counts = collections.Counter()
    relations = collections.Counter()
    by_route = {name: collections.Counter() for name in routes}
    db = sqlite3.connect(str(db_path))
    db.executescript("""CREATE TABLE poi(
      osm_type TEXT, osm_id INTEGER, category TEXT, name TEXT,
      lon REAL, lat REAL, PRIMARY KEY(osm_type,osm_id,category));
      CREATE INDEX poi_category_location ON poi(category,lat,lon);""")
    insert = "INSERT OR IGNORE INTO poi VALUES(?,?,?,?,?,?)"
    class Handler(osmium.SimpleHandler):
        def add(self, osm_type, obj, positions):
            tag = obj.tags
            categories = classify(tag)
            if not categories or not positions:
                return
            # The record location is illustrative; for large polygons the
            # mean of vertices may not represent a useful destination.
            lon = sum(p[0] for p in positions) / len(positions)
            lat = sum(p[1] for p in positions) / len(positions)
            name = str(tag.get("name", tag.get("name:it", "")))[:160]
            nearby = route_matches(positions, routes, radius)
            for cat in categories:
                counts[cat] += 1
                db.execute(insert, (osm_type, int(obj.id), cat, name, lon, lat))
                for route_name in nearby:
                    by_route[route_name][cat] += 1
        def node(self, obj):
            if obj.location.valid():
                self.add("n", obj, [(obj.location.lon, obj.location.lat)])
        def way(self, obj):
            if not classify(obj.tags):
                return
            positions = [(n.location.lon, n.location.lat)
                         for n in obj.nodes if n.location.valid()]
            if positions:
                self.add("w", obj, positions)
        def relation(self, obj):
            # No location without multipolygon assembly; DO NOT assign this
            # relation to a route nor count it as part of the spatial index.
            for cat in classify(obj.tags):
                relations[cat] += 1
    try:
        with tempfile.TemporaryDirectory(prefix="road-dna-", dir=db_path.parent) as temp:
            Handler().apply_file(str(pbf), locations=True,
                                 idx="sparse_file_array," + str(Path(temp) / "nodes.idx"))
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()
    return {"indexed_nodes_and_ways": dict(counts),
            "unlocated_relations_italy": dict(relations),
            "route_counts_approx": {k: dict(v) for k, v in by_route.items()},
            "standalone_sqlite_bytes": db_path.stat().st_size,
            "size_note": "Standalone proxy only; NOT measured MotoPack growth. "
                         "Large multipolygons/relations undercounted."}


def fetch_json(url, key, body=None):
    headers = {"Accept": "application/json", "User-Agent": "MotoChatGPS-RoadDNA-Research/0.1"}
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["x-api-key"] = key
        req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers=headers, method="POST")
    else:
        req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=18) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        # Never log response URLs, keys, or private route coordinates.
        raise RuntimeError(f"HTTP {exc.code}") from None


def geoapify_benchmark(line, radius, key):
    geometry = {"type": "LineString", "coordinates": line}
    if len(line) > 10000 or len(json.dumps(geometry).encode()) > 102400:
        return {"status": "skipped_geometry_limits", "count": None}
    body = {"categories": GEOAPIFY,
            "filter": {"type": "polyline", "geometry": geometry, "buffer": radius},
            "limit": 100, "offset": 0, "lang": "it"}
    t0 = time.monotonic()
    try:
        result = fetch_json("https://api.geoapify.com/v2/places", key, body)
    except Exception as exc:
        return {"status": "error", "detail": type(exc).__name__ + ": " + str(exc),
                "count": None}
    features = result.get("features", [])
    ids = {(f.get("properties") or {}).get("place_id", i)
           for i, f in enumerate(features)}
    categories = collections.Counter()
    for feature in features:
        for category in (feature.get("properties") or {}).get("categories", []):
            if category in GEOAPIFY:
                categories[category] += 1
    return {"status": "partial_limit_hit" if len(features) >= 100 else "ok",
            "count": len(ids), "categories_returned": dict(categories),
            "duration_sec": round(time.monotonic() - t0, 2),
            "queries": 1, "note": "Geoapify near-route, not road detour; "
                                "1 page of 100 only (not exhaustive)."}


def opentripmap_benchmark(line, radius, key):
    # Three sampled centers: intentionally incomplete, unlike Geoapify's
    # whole-route corridor. Report sample coverage rather than inventing parity.
    positions = [line[i] for i in sorted(set((0, len(line)//2, len(line)-1)))]
    seen = set()
    t0 = time.monotonic()
    try:
        for lon, lat in positions:
            from urllib.parse import urlencode
            params = urlencode({"lat": lat, "lon": lon, "radius": radius,
                                "limit": 100, "format": "json", "kinds": "interesting_places",
                                "apikey": key})
            result = fetch_json("https://api.opentripmap.com/0.1/en/places/radius?" + params,
                                key)
            if not isinstance(result, list):
                raise ValueError("Unexpected OpenTripMap response shape")
            for place in result:
                pos = place.get("point") or {}
                x, y = pos.get("lon"), pos.get("lat")
                if isinstance(x, (int,float)) and isinstance(y, (int,float)):
                    if meters_from_route(x, y, line) <= radius:
                        seen.add(place.get("xid") or (round(x,5),round(y,5)))
    except Exception as exc:
        return {"status": "error", "detail": type(exc).__name__ + ": " + str(exc),
                "count": None}
    return {"status": "sample_only", "count": len(seen), "queries": len(positions),
            "duration_sec": round(time.monotonic()-t0, 2),
            "note": "Three radius samples and 100 results each. "
                    "NOT directly comparable to entire-route Geoapify."}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--route", type=Path, action="append", required=True,
                    help="Valhalla Moto route JSON or GeoJSON LineString; repeatable")
    ap.add_argument("--pbf", type=Path, help="Italy OSM PBF; local only")
    ap.add_argument("--output", type=Path, default=Path("road-dna-benchmark"))
    ap.add_argument("--buffer-m", type=int, default=2000)
    ap.add_argument("--online", action="store_true",
                    help="Explicitly authorize HTTP API calls with configured keys")
    args = ap.parse_args()
    if not 100 <= args.buffer_m <= 10000:
        ap.error("--buffer-m must be 100..10000")
    routes = {}
    for path in args.route:
        routes[path.stem] = parse_route(path)
    if len(routes) != len(args.route):
        ap.error("Distinct route filenames must have distinct stems")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"source": "Moto Chat GPS Road DNA", "buffer_m": args.buffer_m,
              "limitations": ["No real detour or visibility inference",
                              "Counts across providers are not equivalently exhaustive",
                              "No API request without --online"],
              "routes": {name: {"vertices": len(line)} for name,line in routes.items()}}
    if args.pbf:
        if not args.pbf.is_file():
            ap.error("Italy PBF file not found")
        report["offline"] = pbf_benchmark(args.pbf, args.output/"tourist-pois-proxy.sqlite",
                                           routes, args.buffer_m)
    else:
        report["offline"] = {"status": "skipped_no_pbf"}
    if args.online:
        gkey = os.getenv("GEOAPIFY_API_KEY", "").strip()
        okey = os.getenv("OPENTRIPMAP_API_KEY", "").strip()
        for name, line in routes.items():
            report["routes"][name]["geoapify"] = (
                geoapify_benchmark(line, args.buffer_m, gkey) if gkey
                else {"status": "skipped_no_api_key"})
            report["routes"][name]["opentripmap"] = (
                opentripmap_benchmark(line, args.buffer_m, okey) if okey
                else {"status": "skipped_no_api_key"})
    else:
        report["online"] = {"status": "disabled_by_default"}
    out = args.output/"report.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Road DNA source benchmark:", out)
    for name in routes:
        print("-", name, report["routes"][name])
    print("API keys are never saved. Send only the report if GPS privacy permits.")


if __name__ == "__main__":
    main()
