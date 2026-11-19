#!/usr/bin/env python3
"""Convert a local OpenStreetMap .osm.pbf to a GPS-local .motomap ZIP archive.

Requires: python -m pip install osmium
Usage: python scripts/offline_map/pbf_to_motomap.py italy-nord-ovest.osm.pbf -o nord-ovest.motomap.zip

Import resulting ZIP in Moto-GPS Android > Gestione Mappe Offline.
MotoMap v4 stores OSM node IDs, road levels, and explicit OSM roundabout tags.
A ZIP contains standard tile_15_X_Y.motomap entries; no Render or Overpass calls.
"""
import argparse
import collections
import io
import json
import math
import re
import zipfile

import osmium

ZOOM = 15
GRID = 1 << ZOOM
KEEP = {
    "motorway": 0, "motorway_link": 0, "trunk": 0, "trunk_link": 0,
    "primary": 1, "primary_link": 1,
    "secondary": 2, "secondary_link": 2, "tertiary": 2, "tertiary_link": 2,
    "residential": 3, "unclassified": 3, "service": 3,
}
SPEED = [110, 90, 70, 50]
# Mandatory v4 junction code: 0=ordinary way, 1=junction=roundabout,
# 2=junction=circular. Never infer roundabouts from the highway class.
JUNCTION_KIND = {"roundabout": 1, "circular": 2}
JUNCTION_SCHEMA = "osm-junction-way-v4"

# OSM numeric km/h, selected Italian implicit limits and compatible units.
# A v4 row contains one speed only: divergent directional/compound limits
# cannot be represented faithfully and remain on the legacy class fallback.
ITALIAN_LIMITS = {
    "it:urban": 50,
    "it:rural": 90,
    "it:trunk": 110,
    "it:motorway": 130,
    "it:living_street": 20,
}
SPEED_RE = re.compile(r"(\d{1,3})(?:\s*(?:km/h|kmh|kph))?", re.IGNORECASE)


def parse_maxspeed(raw):
    if not isinstance(raw, str) or not raw.strip():
        return None
    values = []
    for token in raw.lower().split(";"):
        token = token.strip()
        if token in ITALIAN_LIMITS:
            speed = ITALIAN_LIMITS[token]
        else:
            matched = SPEED_RE.fullmatch(token)
            if matched is None:
                return None
            speed = int(matched.group(1))
        if not 5 <= speed <= 140:
            return None
        values.append(speed)
    # Preserve an unambiguous numeric value; do not arbitrarily choose 50
    # from "50;70", or confuse seasonal/conditional rules with static limits.
    return values[0] if values and len(set(values)) == 1 else None


def speed_for_way(tags, cls):
    direct = parse_maxspeed(tags.get("maxspeed", ""))
    if direct is not None:
        return direct
    # A single forward limit does not describe the reverse direction. Only
    # accept identical directional values when the main tag is missing.
    if not str(tags.get("maxspeed", "")).strip():
        forward = parse_maxspeed(tags.get("maxspeed:forward", ""))
        backward = parse_maxspeed(tags.get("maxspeed:backward", ""))
        if forward is not None and forward == backward:
            return forward
    # source:maxspeed describes provenance, not necessarily a proven
    # current limit. Keep existing v4 fallback until certainty can be encoded.
    return SPEED[cls]


def tile_xy_float(lat, lon):
    latitude = max(-85.0, min(85.0, lat))
    longitude = max(-180.0, min(180.0, lon))
    x = (longitude + 180.0) / 360.0 * GRID
    phi = math.radians(latitude)
    y = (1.0 - math.asinh(math.tan(phi)) / math.pi) * GRID / 2.0
    return (max(0.0, min(GRID - 1e-9, x)),
            max(0.0, min(GRID - 1e-9, y)))


def supercover_grid(x0, y0, x1, y1):
    """Return every z15 tile touched by a segment, including corner touches.

    Grid traversal is O(crossed vertical + horizontal boundaries), unlike
    oversampling a diagonal or scanning its rectangular bounding box.
    """
    if x1 - x0 > GRID / 2:
        x1 -= GRID
    elif x0 - x1 > GRID / 2:
        x1 += GRID
    touched = set()
    def add(x, y):
        if 0 <= y < GRID:
            touched.add((x % GRID, y))
    x, y = math.floor(x0), math.floor(y0)
    dx, dy = x1 - x0, y1 - y0
    sx = (dx > 0) - (dx < 0)
    sy = (dy > 0) - (dy < 0)
    next_x = ((x + 1 - x0) / dx if sx > 0 else (x - x0) / dx) if sx else math.inf
    next_y = ((y + 1 - y0) / dy if sy > 0 else (y - y0) / dy) if sy else math.inf
    interval_x = 1 / abs(dx) if sx else math.inf
    interval_y = 1 / abs(dy) if sy else math.inf
    tolerance = 1e-12
    while True:
        add(x, y)
        if min(next_x, next_y) > 1 + tolerance:
            break
        if next_x < next_y - tolerance:
            x += sx
            next_x += interval_x
        elif next_y < next_x - tolerance:
            y += sy
            next_y += interval_y
        else:
            # At a corner both side tiles count, even if traversed only
            # at one point; the diagonal tile is visited next.
            if sx:
                add(x + sx, y)
            if sy:
                add(x, y + sy)
            x += sx
            y += sy
            next_x += interval_x
            next_y += interval_y

    # Include tiles touching exact-boundary endpoints, and both sides of
    # a path that runs precisely along one grid line.
    basic = set(touched)
    if sx == 0 and abs(x0 - round(x0)) <= tolerance:
        touched.update(((a - 1) % GRID, b) for a, b in basic)
    if sy == 0 and abs(y0 - round(y0)) <= tolerance:
        touched.update((a, b - 1) for a, b in basic if b > 0)
    for px, py in ((x0, y0), (x1, y1)):
        xx, yy = math.floor(px), math.floor(py)
        xs = (xx - 1, xx) if abs(px - round(px)) <= tolerance else (xx,)
        ys = (yy - 1, yy) if abs(py - round(py)) <= tolerance else (yy,)
        for xi in xs:
            for yi in ys:
                add(xi, yi)
    return touched


def segment_tiles(lat_a, lon_a, lat_b, lon_b):
    x0, y0 = tile_xy_float(lat_a, lon_a)
    x1, y1 = tile_xy_float(lat_b, lon_b)
    return supercover_grid(x0, y0, x1, y1)


def tile(lat, lon):
    lat = max(-85.0, min(85.0, lat))
    x = max(0, min(GRID - 1, int((lon + 180.0) / 360.0 * GRID)))
    phi = math.radians(lat)
    y = max(0, min(GRID - 1, int((1.0 - math.asinh(math.tan(phi)) / math.pi) * GRID / 2)))
    return x, y


def to_base36(value):
    """Compact, lossless encoding of OSM's positive 64-bit node IDs."""
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if value <= 0:
        raise ValueError("OSM node IDs must be positive")
    output = ""
    while value:
        value, digit = divmod(value, 36)
        output = digits[digit] + output
    return output


def road_level(tags):
    """Use OSM layer when provided; bridge/tunnel as a conservative fallback."""
    layer = tags.get("layer", "").strip()
    if re.fullmatch(r"-?\d{1,2}", layer):
        return max(-9, min(9, int(layer)))
    if tags.get("bridge", "no") not in ("no", "false", "0"):
        return 1
    if tags.get("tunnel", "no") not in ("no", "false", "0"):
        return -1
    return 0


def rows_for_way(way):
    highway = way.tags.get("highway")
    if highway not in KEEP:
        return ()
    cls = KEEP[highway]
    speed = speed_for_way(way.tags, cls)
    level = road_level(way.tags)
    junction_kind = JUNCTION_KIND.get(way.tags.get("junction"), 0)
    points = []
    for node in way.nodes:
        if not node.location.valid() or node.ref <= 0:
            continue
        coord = (round(node.lat * 1_000_000), round(node.lon * 1_000_000),
                 to_base36(node.ref))
        # Do not drop different OSM nodes just because their coordinates
        # round to the same E6 position: exits depend on exact NodeId links.
        if not points or points[-1] != coord:
            points.append(coord)
    if len(points) < 2:
        return ()
    output = []
    for i in range(0, len(points) - 1, 2047):
        segment = points[i:i + 2048]
        if len(segment) < 2:
            continue
        box = (min(p[0] for p in segment), min(p[1] for p in segment),
               max(p[0] for p in segment), max(p[1] for p in segment))
        line = f"{cls},{speed},{level},{way.id},{junction_kind}|{','.join(map(str, box))}|" + ";".join(
            f"{lat},{lon},{node_id}" for lat, lon, node_id in segment) + "\n"
        # Assign to all tile cells intersected by polyline, rather than just
        # the way's starting point. Long ways must remain accessible everywhere.
        touched = set()
        for (lat_a, lon_a, _), (lat_b, lon_b, _) in zip(segment, segment[1:]):
            touched.update(segment_tiles(
                lat_a / 1e6, lon_a / 1e6,
                lat_b / 1e6, lon_b / 1e6))
        output.append((touched, line))
    return output



def certified_z14_cells(poly_path):
    """Yield z14 cells entirely covered by the exact Geofabrik .poly boundary.

    Requires shapely; partially covered boundary cells are intentionally omitted.
    """
    from shapely.geometry import Polygon, box
    from shapely.ops import unary_union
    rings = []
    with open(poly_path, encoding="utf-8") as stream:
        next(stream)  # polygon name
        current, hole = [], False
        for raw in stream:
            line = raw.strip()
            if not line:
                continue
            if line == "END":
                if current:
                    rings.append((hole, current))
                    current = []
                else:
                    break
            elif not current and len(line.split()) == 1:
                hole = line.startswith("!")
            else:
                lon, lat = map(float, line.split()[:2])
                current.append((lon, lat))
    shells = [Polygon(points) for is_hole, points in rings if not is_hole]
    holes = [Polygon(points) for is_hole, points in rings if is_hole]
    if not shells:
        raise ValueError("Empty or invalid .poly boundary")
    geom = unary_union(shells)
    if holes:
        geom = geom.difference(unary_union(holes))
    if not geom.is_valid or geom.is_empty:
        raise ValueError("Invalid extraction polygon")
    z, scale = 14, 1 << 14
    min_lon, min_lat, max_lon, max_lat = geom.bounds
    def latitude(y):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / scale))))
    x_min = max(0, int((min_lon + 180) / 360 * scale) - 1)
    x_max = min(scale - 1, int((max_lon + 180) / 360 * scale) + 1)
    y_min = max(0, tile(max_lat, min_lon)[1] // 2 - 1)
    y_max = min(scale - 1, tile(min_lat, max_lon)[1] // 2 + 1)
    for x in range(x_min, x_max + 1):
        for y in range(y_min, y_max + 1):
            cell = box(x / scale * 360 - 180, latitude(y + 1),
                       (x + 1) / scale * 360 - 180, latitude(y))
            if geom.covers(cell):
                yield x, y


class Converter(osmium.SimpleHandler):
    def __init__(self, archive):
        super().__init__()
        self.archive = archive
        self.buckets = collections.defaultdict(list)
        self.ways = 0
        self.roads = 0
        self.roundabout_ways = 0
        self.circular_ways = 0

    def way(self, way):
        rows = rows_for_way(way)
        if rows:
            junction_kind = JUNCTION_KIND.get(way.tags.get("junction"), 0)
            if junction_kind == 1:
                self.roundabout_ways += 1
            elif junction_kind == 2:
                self.circular_ways += 1
        for touched, line in rows:
            for x, y in touched:
                self.buckets[(x, y)].append(line)
                self.roads += 1
            self.ways += 1

    def finish(self):
        for (x, y), lines in sorted(self.buckets.items()):
            name = f"tile_{ZOOM}_{x}_{y}.motomap"
            self.archive.writestr(name, "# OpenStreetMap contributors (ODbL)\n" +
                                  "".join(lines), compress_type=zipfile.ZIP_DEFLATED,
                                  compresslevel=6)
        return len(self.buckets)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", help="Geofabrik OSM PBF input")
    parser.add_argument("-o", "--output", required=True, help="Destination .motomap.zip")
    parser.add_argument("--coverage-poly", help="Exact matching extraction boundary .poly (requires shapely)")
    args = parser.parse_args()
    with zipfile.ZipFile(args.output, "w", allowZip64=True) as archive:
        handler = Converter(archive)
        handler.apply_file(args.pbf, locations=True)
        count = handler.finish()
        covered = list(certified_z14_cells(args.coverage_poly)) if args.coverage_poly else []
        if covered:
            archive.writestr("coverage_z14.txt",
                "".join(f"{x},{y}\n" for x, y in covered),
                compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("motomap_v4.json", json.dumps({
            "format": "motomap", "version": 4, "zoom": ZOOM,
            "topology": "osm-node-ids-base36", "level": "osm-layer-or-bridge-tunnel",
            "junction": JUNCTION_SCHEMA,
            "coverage": "poly-full-z14" if args.coverage_poly else "unknown",
            "certified_z14": len(covered), "road_tiles": count,
            "roundabout_ways": handler.roundabout_ways,
            "circular_ways": handler.circular_ways
        }), compress_type=zipfile.ZIP_DEFLATED)
    print(f"{handler.ways} road segments -> {count} tiles ({handler.roads} indexed road records)")
    print(f"Certified z14 coverage cells: {len(covered)}")
    print(f"OSM junction-tagged ways: roundabout={handler.roundabout_ways}, "
          f"circular={handler.circular_ways}")
    print(f"Import on Android: {args.output}")


if __name__ == "__main__":
    main()
