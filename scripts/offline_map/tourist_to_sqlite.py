#!/usr/bin/env python3
"""Build Road DNA tourist SQLite from Italy OSM PBF without a giant node index.

Three streaming passes (checkpointed before polygon assembly):
  1. Select tagged multipolygon relations and their member ways.
  2. Index tourist nodes and collect tagged/referenced way node IDs.
  3. Resolve only the selected node coordinates.
Relations are assembled from outer and inner way linework with Shapely.
Relations with absent PBF member ways are omitted and counted explicitly:
never fabricate an area from a truncated boundary. --resume uses a validated
SQLite .part checkpoint to skip all three PBF passes. No network/pack writes.
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
CREATE TABLE candidate_way(
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


def relation_member_lines(members, way_points):
    """Reject a *whole* truncated relation, never a partial polygon/hole."""
    outer, inner, missing = [], [], []
    for way_id, role in members:
        line = way_points.get(way_id)
        if line is None:
            missing.append(way_id)
        elif role == "inner":
            inner.append(line)
        else:
            outer.append(line)
    return outer, inner, missing


class NodeRefFilter:
    """32 MiB Bloom filter: disk SQLite resolves all positives exactly.

    False positives cost SQLite lookups; false negatives are impossible.
    Only bit membership is held in memory, not millions of Python integers.
    """
    BITS = 1 << 28
    MASK = BITS - 1

    def __init__(self):
        self.bits = bytearray(self.BITS // 8)

    def hashes(self, osm_id):
        # SplitMix64-derived 64-bit pair, deterministic across Python runs.
        mask64 = (1 << 64) - 1
        x = (osm_id + 0x9e3779b97f4a7c15) & mask64
        x = ((x ^ (x >> 30)) * 0xbf58476d1ce4e5b9) & mask64
        x = ((x ^ (x >> 27)) * 0x94d049bb133111eb) & mask64
        h1 = (x ^ (x >> 31)) & self.MASK
        h2 = ((x >> 32) | 1) & self.MASK
        return (h1, (h1 + h2) & self.MASK, (h1 + 2 * h2) & self.MASK)

    def add(self, osm_id):
        for h in self.hashes(osm_id):
            self.bits[h >> 3] |= 1 << (h & 7)

    def maybe(self, osm_id):
        return all(self.bits[h >> 3] & (1 << (h & 7))
                   for h in self.hashes(osm_id))


def build(pbf, output, resume=False):
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
    if pending.exists() and not resume:
        raise FileExistsError("{} exists; rerun with --resume".format(pending))
    recovering = pending.exists()
    stat = source.stat()
    source_signature = json.dumps([str(source.resolve()), stat.st_size, stat.st_mtime_ns])

    relations = {}
    related_way_ids = set()
    relation_way_categories = collections.defaultdict(set)
    ref_filter = NodeRefFilter()
    ref_rows = 0
    way_points = {}
    counts = collections.Counter()
    failures = collections.Counter()
    missing_relation_examples = []
    db = sqlite3.connect(pending)
    db.execute("PRAGMA temp_store=FILE")
    db.execute("PRAGMA cache_size=-32768")
    if not recovering:
        db.executescript(SCHEMA)
        db.execute("""CREATE TABLE selected_nodes(
                       id INTEGER PRIMARY KEY, lon REAL, lat REAL)""")
        db.execute("""CREATE TABLE selected_relations(
                       osm_id INTEGER PRIMARY KEY, categories_json TEXT NOT NULL,
                       name TEXT NOT NULL, members_json TEXT NOT NULL)""")
        db.execute("CREATE TABLE build_checkpoint(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.executemany("INSERT INTO build_checkpoint VALUES (?,?)",
                       (("source", source_signature), ("phase", "collecting")))
        db.commit()

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
            db.execute("INSERT INTO selected_relations VALUES (?,?,?,?)",
                       (int(obj.id), json.dumps(categories), label(obj.tags),
                        json.dumps(members)))
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
            # Store referenced node IDs on disk, without an arbitrary RAM
            # cap. A small Bloom filter screens unrelated nodes in pass 3.
            nonlocal ref_rows
            db.executemany("INSERT OR IGNORE INTO selected_nodes(id) VALUES(?)",
                           ((ref,) for ref in refs))
            for ref in refs:
                ref_filter.add(ref)
            ref_rows += len(refs)
            if ref_rows // 2_000_000 != (ref_rows - len(refs)) // 2_000_000:
                print("Selected {:,} raw node references...".format(ref_rows),
                      flush=True)
            tags = {str(k): str(v) for k,v in obj.tags}
            db.execute("INSERT INTO candidate_way VALUES (?,?,?)",
                       (int(obj.id), json.dumps(tags), json.dumps(refs)))

    try:
        if recovering:
            try:
                checkpoint = dict(db.execute("SELECT key,value FROM build_checkpoint"))
                if checkpoint.get("phase") != "coordinates_ready":
                    raise RuntimeError("Checkpoint not complete; cannot resume polygon assembly")
                if checkpoint.get("source") != source_signature:
                    raise RuntimeError("PBF changed since checkpoint; refuse stale coordinate data")
                selected_unique = db.execute("SELECT COUNT(*) FROM selected_nodes").fetchone()[0]
                resolved = db.execute(
                    "SELECT COUNT(*) FROM selected_nodes WHERE lon IS NOT NULL AND lat IS NOT NULL"
                ).fetchone()[0]
                if resolved != selected_unique or selected_unique == 0:
                    raise RuntimeError("Incomplete saved node coordinates")
                for rel_id, categories_json, name, members_json in db.execute(
                        "SELECT osm_id,categories_json,name,members_json FROM selected_relations"):
                    categories, members = json.loads(categories_json), json.loads(members_json)
                    relations[rel_id] = (categories, name, members)
                    related_way_ids.update(way_id for way_id, _ in members)
                    for way_id, _ in members:
                        relation_way_categories[way_id].update(categories)
            except sqlite3.DatabaseError as exc:
                raise RuntimeError("Invalid .part checkpoint; inspect it before a fresh rebuild") from exc
            # Node POIs were stored in pass 2. Remove only partially assembled ways/relations.
            db.execute("DELETE FROM tourist_poi WHERE osm_type IN ('w','r')")
            db.commit()
            print("Road DNA resume: {:,} node coordinates ready; assembling polygons "
                  "without repeating PBF scans...".format(resolved), flush=True)
        else:
            print("Road DNA 1/3: scanning tagged tourist relations...", flush=True)
            Relations().apply_file(str(source), locations=False)
            print("Selected {} area relations and {} member ways".format(
                  len(relations), len(related_way_ids)), flush=True)
            print("Road DNA 2/3: tourist points and selected ways...", flush=True)
            Candidates().apply_file(str(source), locations=False)
            db.commit()
            selected_unique = db.execute("SELECT COUNT(*) FROM selected_nodes").fetchone()[0]
            print("Selected {:,} unique referenced nodes on disk; "
                  "resolving in pass 3/3...".format(selected_unique), flush=True)
            class Coordinates(osmium.SimpleHandler):
                def __init__(self):
                    super().__init__()
                    self.seen = 0
                    self.matched = 0
    
                def node(self, obj):
                    self.seen += 1
                    ref = int(obj.id)
                    if ref_filter.maybe(ref) and obj.location.valid():
                        cursor = db.execute(
                            "UPDATE selected_nodes SET lon=?,lat=? WHERE id=?",
                            (obj.location.lon, obj.location.lat, ref))
                        self.matched += max(0, cursor.rowcount)
                    if self.seen % 5_000_000 == 0:
                        print("Road DNA 3/3: {:,} nodes scanned, {:,} references "
                              "resolved".format(self.seen, self.matched), flush=True)
    
            if selected_unique:
                Coordinates().apply_file(str(source), locations=False)
            db.commit()
            resolved = db.execute(
                "SELECT COUNT(*) FROM selected_nodes WHERE lon IS NOT NULL").fetchone()[0]
            print("Resolved {:,}/{:,} unique node coordinates; "
                  "assembling polygons...".format(resolved, selected_unique),
                  flush=True)
            if resolved != selected_unique:
                raise RuntimeError("Missing {:,} referenced OSM node coordinates; "
                                   "not publishing incomplete index".format(
                                       selected_unique - resolved))
    
            db.execute("UPDATE build_checkpoint SET value='coordinates_ready' WHERE key='phase'")
            db.commit()
    
            # Extract by indexed SQL lookups per way. This trades random disk I/O
        # for bounded RAM; only member ways of area relations are retained.
        def read_way(refs):
            locations = {}
            for offset in range(0, len(refs), 500):
                batch = list(dict.fromkeys(refs[offset:offset + 500]))
                placeholders = ",".join("?" for _ in batch)
                for node_id, lon, lat in db.execute(
                        "SELECT id,lon,lat FROM selected_nodes WHERE id IN (" +
                        placeholders + ")", batch):
                    locations[node_id] = (lon, lat)
            return [locations[ref] for ref in refs if ref in locations]

        for osm_id, tags_json, refs_json in db.execute(
                "SELECT osm_id,tags_json,refs_json FROM candidate_way"):
            tags = json.loads(tags_json)
            refs = json.loads(refs_json)
            line = read_way(refs)
            if len(line) != len(refs) or len(line) < 2:
                failures["unresolved_way"] += 1
                continue
            if osm_id in related_way_ids:
                way_points[osm_id] = line
            categories = classify(tags)
            if not categories:
                continue
            # Do not publish tagged geometry from a multipolygon member as
            # an independent attraction when relation covers the category.
            categories = sorted(set(categories) - relation_way_categories[osm_id])
            if not categories:
                continue
            # A closed tourist way is an AREA (e.g. lake), not an
            # arbitrary point at the midpoint of the boundary. Using its
            # bounding box allows Road DNA to find large lakes near a route.
            if len(line) >= 4 and line[0] == line[-1]:
                from shapely.geometry import Polygon
                polygon = Polygon(line)
                if not polygon.is_valid:
                    # Polygon ring self-intersections occur in real OSM data.
                    # Buffer(0) may repair them into a valid MultiPolygon;
                    # never publish an invalid geometry as a verified area.
                    polygon = polygon.buffer(0)
                if polygon.is_empty or not polygon.is_valid or polygon.area <= 0:
                    failures["invalid_tourist_area_way"] += 1
                    continue
                point = polygon.representative_point()
                insert("w", osm_id, categories, label(tags), point.x, point.y,
                       polygon.bounds, "area", "representative")
            else:
                shape = LineString(line)
                if shape.length == 0:
                    failures["zero_length_way"] += 1
                    continue
                point = shape.interpolate(0.5, normalized=True)
                insert("w", osm_id, categories, label(tags), point.x, point.y,
                       shape.bounds, "way", "representative")

        for rel_id, (categories, name, members) in relations.items():
            outer, inner, missing = relation_member_lines(members, way_points)
            if missing:
                # Country-clipped PBFs can omit member ways crossing the border.
                # Keeping a partial outer ring or dropping missing holes is unsafe.
                failures["relation_missing_member_way"] += 1
                if len(missing_relation_examples) < 25:
                    missing_relation_examples.append(rel_id)
                continue
            geom = polygon_from_member_lines(outer, inner)
            if geom is None:
                failures["relation_unassembled_polygon"] += 1
                continue
            point = geom.representative_point()
            insert("r", rel_id, categories, name, point.x, point.y,
                   geom.bounds, "area", "representative")

        critical = {key: value for key, value in failures.items()
                    if key not in ("unsupported_relation_type", "relation_without_way_members",
                                   "relation_missing_member_way")}
        if critical:
            raise RuntimeError("Tourist areas incomplete: {}. "
                               "Not publishing this dataset.".format(critical))
        count = db.execute("SELECT COUNT(*) FROM tourist_poi").fetchone()[0]
        if count == 0:
            raise RuntimeError("No tourist points indexed")
        db.execute("DROP TABLE candidate_way")
        db.execute("DROP TABLE selected_nodes")
        db.execute("DROP TABLE selected_relations")
        db.execute("DROP TABLE build_checkpoint")
        db.execute("INSERT INTO tourist_metadata VALUES ('schema','road-dna-tourist-1')")
        db.execute("INSERT INTO tourist_metadata VALUES ('count',?)", (str(count),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_count',?)",
                   (str(len(relations)),))
        db.execute("INSERT INTO tourist_metadata VALUES ('unsupported_relation_types',?)",
                   (str(failures["unsupported_relation_type"]),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_without_way_members',?)",
                   (str(failures["relation_without_way_members"]),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_missing_member_way',?)",
                   (str(failures["relation_missing_member_way"]),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_missing_member_way_examples',?)",
                   (json.dumps(missing_relation_examples),))
        db.execute("INSERT INTO tourist_metadata VALUES ('relation_coverage',?)",
                   ("partial" if any(failures.values()) else "complete",))
        db.commit()
        db_counts = dict(db.execute(
            "SELECT category,COUNT(*) FROM tourist_poi GROUP BY category"))
        db.execute("VACUUM")
        db.close()
        pending.replace(dest)
        print("Road DNA tourist SQLite ready: {} rows, {} bytes".format(
              count, dest.stat().st_size), flush=True)
        print("By category:", dict(db_counts), flush=True)
        if failures["relation_missing_member_way"]:
            print("WARNING: {} clipped OSM relations omitted; see tourist_metadata."
                  .format(failures["relation_missing_member_way"]), flush=True)
    except BaseException:
        db.close()
        print("Road DNA checkpoint retained (if present): {}".format(pending), flush=True)
        raise

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume", action="store_true",
                        help="reuse a verified .part checkpoint after node resolution")
    args = parser.parse_args()
    build(args.pbf, args.output, resume=args.resume)

if __name__ == "__main__":
    main()
