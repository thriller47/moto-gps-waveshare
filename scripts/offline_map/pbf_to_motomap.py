#!/usr/bin/env python3
"""Convert a local OpenStreetMap .osm.pbf to a GPS-local .motomap ZIP archive.

Requires: python -m pip install osmium
Usage: python scripts/offline_map/pbf_to_motomap.py italy-nord-ovest.osm.pbf -o nord-ovest.motomap.zip

Import resulting ZIP in Moto-GPS Android > Gestione Mappe Offline.
A ZIP contains standard tile_15_X_Y.motomap entries; no Render or Overpass calls.
"""
import argparse
import collections
import io
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


def tile(lat, lon):
    lat = max(-85.0, min(85.0, lat))
    x = max(0, min(GRID - 1, int((lon + 180.0) / 360.0 * GRID)))
    phi = math.radians(lat)
    y = max(0, min(GRID - 1, int((1.0 - math.asinh(math.tan(phi)) / math.pi) * GRID / 2)))
    return x, y


def rows_for_way(way):
    highway = way.tags.get("highway")
    if highway not in KEEP:
        return ()
    cls = KEEP[highway]
    raw_speed = way.tags.get("maxspeed", "")
    m = re.fullmatch(r"(\d{1,3})(?: km/h)?", raw_speed.strip())
    speed = int(m.group(1)) if m and 5 <= int(m.group(1)) <= 140 else SPEED[cls]
    points = []
    for node in way.nodes:
        if not node.location.valid():
            continue
        coord = (round(node.lat * 1_000_000), round(node.lon * 1_000_000))
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
        line = f"{cls},{speed}|{','.join(map(str, box))}|" + ";".join(
            f"{lat},{lon}" for lat, lon in segment) + "\n"
        # Assign to all tile cells intersected by polyline, rather than just
        # the way's starting point. Long ways must remain accessible everywhere.
        touched = set()
        for (lat_a, lon_a), (lat_b, lon_b) in zip(segment, segment[1:]):
            x1, y1 = tile(lat_a / 1e6, lon_a / 1e6)
            x2, y2 = tile(lat_b / 1e6, lon_b / 1e6)
            steps = max(abs(x2-x1), abs(y2-y1), 1)
            for j in range(steps + 1):
                t = j / steps
                touched.add(tile((lat_a + (lat_b-lat_a)*t) / 1e6,
                                 (lon_a + (lon_b-lon_a)*t) / 1e6))
        output.append((touched, line))
    return output


class Converter(osmium.SimpleHandler):
    def __init__(self, archive):
        super().__init__()
        self.archive = archive
        self.buckets = collections.defaultdict(list)
        self.ways = 0
        self.roads = 0

    def way(self, way):
        for touched, line in rows_for_way(way):
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
    args = parser.parse_args()
    with zipfile.ZipFile(args.output, "w", allowZip64=True) as archive:
        handler = Converter(archive)
        handler.apply_file(args.pbf, locations=True)
        count = handler.finish()
    print(f"{handler.ways} road segments -> {count} tiles ({handler.roads} indexed road records)")
    print(f"Import on Android: {args.output}")


if __name__ == "__main__":
    main()
