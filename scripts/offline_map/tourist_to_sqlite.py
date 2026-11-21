#!/usr/bin/env python3
"""Build Road DNA tourist SQLite from Italy OSM PBF without a giant node index.

Three streaming passes:
  1. Select tagged multipolygon relations and their member ways.
  2. Index tourist nodes and collect tagged/referenced way node IDs.
  3. Resolve only the selected node coordinates.
Relations are assembled from outer and inner way linework with Shapely.
If polygon geometry cannot be assembled, fail rather than publish an
apparently complete index. No network or MotoPack writes.
"""
import argparse
import collections
import json
import math
import sqlite3
from pathlib import Path

CATEGORIES = {
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

SCHEMA = """
PRAGMA user_version=1;
CREATE TABLE tourist_poi(
  osm_type TEXT NOT NULL CHECK(osm_type IN ('n','w','r')),
  osm_id INTEGER NOT NULL,
  category TEXT NOT NULL,
  name TEXT NOT NULL,
  lon REAL NOT NULL,
  lat REAL NOT NULL,
  min_lon REAL NOT NULL,
  min_lat REAL NOT NULL,
  max_lon REAL NOT NULL,
  max_lat REAL NOT NULL,
  geometry_type TEXT NOT NULL CHECK(geometry_type IN ('point','way','area')),
  location_quality TEXT NOT NULL CHECK(location_quality IN ('exact','representative')),
  PRIMARY KEY(osm_type,osm_id,category)
);
CREATE TABLE tourist_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TEMP TABLE candidate_way(
  osm_id INTEGER PRIMARY KEY, tags_json TEXT NOT NULL, refs_json TEXT NOT NULL
);
CREATE INDEX tourist_category_lat_lon ON tourist_poi(category,lat,lon);
CREATE INDEX tourist_category_bounds ON tourist_poi(category,min_lat,max_lat,min_lon,max_lon);
"""

def classify(tags):
    found = {category for (key, val), category in CATEGORIES.items()
             if tags.get(key) == val}
    if tags.get("natural") == "water" and tags.get("water") == "lake":
        found.add("lago")
    if tags.get("amenity") == "public_bath" and tags.get("bath:type") == "thermal":
        found.add("terme_stabilimento")
    return sorted(found)

def label(tags):
    return str(tags.get("name:it") or tags.get("name") or "").strip()[:160]

def safe_coords(points):
    return [p for p in points if all(math.isfinite(v) for v in p)
            and -180 <= p[0] <= 180 and -90 <= p[1] <= 90]

def polygon_from_member_lines(outer_lines, inner_lines):
    """Reconstruct polygon rings, including multi-way rings, preserving holes."""
    from shapely.geometry import LineString
    from shapely.ops import unary_union, polygonize
    if not outer_lines:
        return None
    outer = list(polygonize(unary_union(
        [LineString(line) for line in outer_lines if len(line) >= 2])))
    inner = list(polygonize(unary_union(
        [LineString(line) for line in inner_lines if len(line) >= 2])))
    if not outer:
        return None
    result = unary_union(outer)
    if inner:
        result = result.difference(unary_union(inner))
    if result.is_empty or not result.is_valid or result.area <= 0:
        return None
    return result

def build(pbf, output, max_refs=3_000_000):
    import osmium
    from shapely.geometry import LineString
    from shapely.ops import unary_union

    source, dest = Path(pbf), Path(output)
    if not source.is_file() or not source.stat().st_size:
        raise FileNotFoundError(source)
    if dest.exists():
        raise FileExistsError(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    pending = dest.with_name(dest.name + ".part")
    if pending.exists():
        raise FileExistsError(pending)

    relations = {}
    related_way_ids = set()
    relation_way_categories = collections.defaultdict(set)
    needed_refs = set()
    way_points = {}
    counts = collections.Counter()
    failures = collections.Counter()
    db = sqlite3.connect(pending)
    db.executescript(SCHEMA)

    def insert(kind, osm_id, categories, name, lon, lat, bounds, geometry_type, quality):
        if not all(math.isfinite(v) for v in (lon, lat, *bounds)):
            raise ValueError("Invalid POI coordinates")
        for category in categories:
            db.execute("""INSERT OR IGNORE INTO tourist_poi
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                (kind, osm_id, category, name, lon, lat, *bounds, geometry_type, quality))
            counts[category] += 1

    class Relations(osmium.SimpleHandler):
        def relation(self, obj):
            categories = classify(obj.tags)
            if not categories:
                return
            # Area relations are usually type=multipolygon/boundary. Others
            # have no reconstructible area and must be reported explicitly.
            if obj.tags.get("type") not in ("multipolygon", "boundary"):
                failures["unsupported_relation_type"] += 1
                return
            members = [(int(m.ref), str(m.role)) for m in obj.members
                       if m.type == "w" and str(m.role) in ("outer", "inner", "")]
            if not members:
                failures["relation_without_way_members"] += 1
                return
            relations[int(obj.id)] = (categories, label(obj.tags), members)
            related_way_ids.update(ref for ref, _ in members)
            for ref, _ in members:
                relation_way_categories[ref].update(categories)

    class Candidates(osmium.SimpleHandler):
        def node(self, obj):
            categories = classify(obj.tags)
            if categories and obj.location.valid():
                lon, lat = obj.location.lon, obj.location.lat
                insert("n", int(obj.id), categories, label(obj.tags),
                       lon, lat, (lon, lat, lon, lat), "point", "exact")
        def way(self, obj):
            if int(obj.id) not in related_way_ids and not classify(obj.tags):
                return
            refs = [int(node.ref) for node in obj.nodes]
            additional = set(refs) - needed_refs
            if len(needed_refs) + len(additional) > max_refs:
                raise RuntimeError("Too many selected OSM node refs: increase --max-refs "
                                   "after checking available memory")
            needed_refs.update(additional)
            tags = {str(k): str(v) for k,v in obj.tags}
            db.execute("INSERT INTO candidate_way VALUES (?,?,?)",
                       (int(obj.id), json.dumps(tags), json.dumps(refs)))

    try:
        print("Road DNA 1/3: scanning tagged tourist relations...", flush=True)
        Relations().apply_file(str(source), locations=False)
        print("Selected {} area relations and {} member ways".format(
              len(relations), len(related_way_ids)), flush=True)
        print("Road DNA 2/3: tourist points and selected ways...", flush=True)
        Candidates().apply_file(str(source), locations=False)
        db.commit()
        print("Selected {:,} node coordinates; resolving in pass 3/3...".format(
              len(needed_refs)), flush=True)
        locations = {}
        class Coordinates(osmium.SimpleHandler):
            def node(self, obj):
                ref = int(obj.id)
                if ref in needed_refs:
                    if obj.location.valid():
                        locations[ref] = (obj.location.lon, obj.location.lat)
                    needed_refs.discard(ref)
        if needed_refs:
            Coordinates().apply_file(str(source), locations=False)
        print("Resolved {:,} coordinates; assembling polygons...".format(
              len(locations)), flush=True)

        for osm_id, tags_json, refs_json in db.execute(
                "SELECT osm_id,tags_json,refs_json FROM candidate_way"):
            tags = json.loads(tags_json)
            refs = json.loads(refs_json)
            line = [locations[ref] for ref in refs if ref in locations]
            if len(line) != len(refs) or len(line) < 2:
                failures["unresolved_way"] += 1
                continue
            way_points[osm_id] = line
            categories = classify(tags)
            if not categories:
                continue
            # Do not publish tagged geometry from a multipolygon member as
            # an independent attraction when relation covers the category.
            categories = sorted(set(categories) - relation_way_categories[osm_id])
            if not categories:
                continue
            shape = LineString(line)
            if shape.length == 0:
                failures["zero_length_way"] += 1
                continue
            point = shape.interpolate(0.5, normalized=True)
            insert("w", osm_id, categories, label(tags), point.x, point.y,
                   shape.bounds, "way", "representative")

        for rel_id, (categories, name, members) in relations.items():
            outer, inner = [], []
            for way_id, role in members:
                line = way_points.get(way_id)
                if line is None:
                    failures["relation_missing_member_way"] += 1
                    break
                if role == "inner":
                    inner.append(line)
                else:
                    outer.append(line)
            else:
                geom = polygon_from_member_lines(outer, inner)
                if geom is None:
                    failures["relation_unassembled_polygon"] += 1
                    continue
                point = geom.representative_point()
                insert("r", rel_id, categories, name, point.x, point.y,
                       geom.bounds, "area", "representative")

        critical = {key: value for key, value in failures.items()
                    if key not in ("unsupported_relation_type", "relation_without_way_members")}
        if critical:
            raise RuntimeError("Tourist areas incomplete: {}. "
                               "Not publishing this dataset.".format(critical))
        count = db.execute("SELECT COUNT(*) FROM tourist_poi").fetchone()[0]
        if count == 0:
            raise RuntimeError("No tourist points indexed")
        db.execute("DROP TABLE candidate_way")
        db.execute("INSERT INTO tourist_metadata VALUES ('schema','road-dna-tourist-1')")
        db.execute("INSERT INTO tourist_metadata VALUES ('count',?)", (str(count),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_count',?)",
                   (str(len(relations)),))
        db.execute("INSERT INTO tourist_metadata VALUES ('unsupported_relation_types',?)",
                   (str(failures["unsupported_relation_type"]),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_without_way_members',?)",
                   (str(failures["relation_without_way_members"]),))
        db.commit()
        db.execute("VACUUM")
        db.close()
        pending.replace(dest)
        print("Road DNA tourist SQLite ready: {} rows, {} bytes".format(
              count, dest.stat().st_size), flush=True)
        print("By category:", dict(counts), flush=True)
    except BaseException:
        db.close()
        if pending.exists():
            pending.unlink()
        raise

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-refs", type=int, default=3_000_000)
    args = parser.parse_args()
    build(args.pbf, args.output, args.max_refs)

if __name__ == "__main__":
    main()
