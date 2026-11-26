#!/usr/bin/env python3
"""Experimental Italian SpeedPack: preserve source records and road evidence, not live enforcement."""
import argparse
from collections import Counter, defaultdict
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path
import sqlite3

import osmium
from speed_camera_geometry import CELL, MAX_OFFSET, ROAD_TYPES, cell, project

MATCH_M = 20.0
ROAD_M = 15.0


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def read_mapatlas(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    if (data.get("type"), data.get("schemaVersion"), data.get("region")) != (
            "FeatureCollection", "1.0", "IT"):
        raise ValueError("Expected MapAtlas Italy GeoJSON schema 1.0")
    if not all(data.get(k) for k in ("snapshot", "licence", "attribution")):
        raise ValueError("Missing MapAtlas provenance")
    records = {}
    for feature in data["features"]:
        props = feature["properties"]
        ref = props["ref"]
        kind = props["class"]
        geometry = feature["geometry"]
        if not isinstance(ref, str) or not ref or ref in records:
            raise ValueError("Invalid or duplicate MapAtlas ref")
        if kind not in ("spot", "signal", "corridor") or geometry["type"] != "Point":
            raise ValueError("Unexpected MapAtlas class or geometry")
        lon, lat = geometry["coordinates"]
        if not (35 <= lat <= 48 and 6 <= lon <= 19):
            raise ValueError("Coordinate outside expected Italian envelope")
        records[ref] = (lat, lon, kind, props)
    return data, records


def limit_value(value):
    return value if type(value) is int and 0 < value <= 150 else None


def angle_difference(a, b):
    return abs((a-b+180) % 360-180)


def continuation(a, b):
    """Only a shared way endpoint, aligned axes and shared road identity.
    This is a local continuity hint, not proof of a single carriageway."""
    if a["grade"] or b["grade"] or a["highway"] != b["highway"]:
        return False
    if not ((a["ref"] and a["ref"] == b["ref"]) or
            (a["name"] and a["name"] == b["name"])):
        return False
    for node in set(a["ends"]) & set(b["ends"]):
        if node not in (a["a"], a["b"]) or node not in (b["a"], b["b"]):
            continue
        out_a = (a["projection"][3] + (180 if node == a["b"] else 0)) % 360
        out_b = (b["projection"][3] + (180 if node == b["b"] else 0)) % 360
        if angle_difference(out_a, out_b) >= 160:
            return True
    return False


def assess(candidates, osm_node=None):
    if not candidates:
        return "missing", None, []
    ranked = sorted(candidates.items(), key=lambda item: (item[1]["projection"][0], item[0]))
    best, item = ranked[0]
    # Do not upgrade any bridge/tunnel/layer ambiguity.
    if any(c["grade"] for c in candidates.values()):
        return "grade_review", None, []
    links = []
    # Only collapse a simple pair, never a transitive cluster or a junction.
    if len(ranked) == 2 and continuation(ranked[0][1], ranked[1][1]):
        links.append((ranked[0][0], ranked[1][0]))
    if item["projection"][0] > ROAD_M:
        return "offset_review", None, links
    if len(ranked) == 1:
        state = "shared_node_candidate" if osm_node in item["shared"] else "single_way_candidate"
        return state, best, links
    if links:
        return "continuity_candidate", best, links
    return "ambiguous", None, links


class CombinedGeometry(osmium.SimpleHandler):
    def __init__(self, points):
        super().__init__()
        self.grid = defaultdict(list)
        self.candidates = defaultdict(dict)
        for key, (lat, lon) in points.items():
            self.grid[cell(lat, lon)].append((key, lat, lon))

    def way(self, obj):
        tags = obj.tags
        highway = tags.get("highway")
        if highway not in ROAD_TYPES or tags.get("area") == "yes":
            return
        if any(tags.get(k) in ("private", "no") for k in ("access", "motor_vehicle", "motorcycle")):
            return
        grade = (tags.get("bridge") not in (None, "no") or
                 tags.get("tunnel") not in (None, "no") or
                 tags.get("layer") not in (None, "0"))
        oneway = tags.get("oneway")
        if oneway is None and (highway == "motorway" or tags.get("junction") == "roundabout"):
            oneway = "yes"
        nodes = list(obj.nodes)
        if len(nodes) < 2:
            return
        ends = (nodes[0].ref, nodes[-1].ref)
        shared = {n.ref for n in nodes}
        name, ref = tags.get("name"), tags.get("ref")
        for a, b in zip(nodes, nodes[1:]):
            if not a.location.valid() or not b.location.valid():
                continue
            p, q = (a.lat, a.lon), (b.lat, b.lon)
            lo = cell(min(p[0], q[0])-.0004, min(p[1], q[1])-.0007)
            hi = cell(max(p[0], q[0])+.0004, max(p[1], q[1])+.0007)
            for i in range(lo[0], hi[0]+1):
                for j in range(lo[1], hi[1]+1):
                    for key, lat, lon in self.grid.get((i, j), ()):
                        projection = project(lat, lon, p, q)
                        if projection is None or projection[0] > MAX_OFFSET:
                            continue
                        old = self.candidates[key].get(obj.id)
                        if old is not None and old["projection"][0] <= projection[0]:
                            continue
                        self.candidates[key][obj.id] = dict(
                            projection=projection, a=a.ref, b=b.ref, ends=ends,
                            grade=grade, highway=highway, name=name, ref=ref,
                            oneway=oneway, shared=shared)


def cross_matches(osm_points, atlas_points):
    """All pairs within 20 m; flag unique reciprocal nearest, never merge."""
    grid = defaultdict(list)
    for key, (lat, lon) in osm_points.items():
        grid[cell(lat, lon)].append((key, lat, lon))
    pairs = []
    by_osm, by_atlas = defaultdict(list), defaultdict(list)
    for atlas, (lat, lon) in atlas_points.items():
        i, j = cell(lat, lon)
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                for osm, a, b in grid.get((i+di, j+dj), ()):
                    d = 111195 * math.hypot(lat-a, (lon-b)*math.cos(math.radians(lat)))
                    if d <= MATCH_M:
                        pairs.append((osm, atlas, d))
                        by_osm[osm].append((d, atlas))
                        by_atlas[atlas].append((d, osm))
    def unique_best(values):
        values.sort()
        return values[0][1] if len(values) == 1 or values[1][0]-values[0][0] > .01 else None
    best_osm = {k: unique_best(v) for k, v in by_osm.items()}
    best_atlas = {k: unique_best(v) for k, v in by_atlas.items()}
    for osm, atlas, d in pairs:
        yield osm, atlas, d, int(best_osm[osm] == atlas and best_atlas[atlas] == osm)


def build(pbf, v3, mapatlas, output):
    part = output.with_name(output.name + ".part")
    if output.exists() or part.exists():
        raise FileExistsError("Output or .part already exists")
    for path in (pbf, v3, mapatlas):
        if not path.is_file():
            raise FileNotFoundError(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata, atlas = read_mapatlas(mapatlas)
    with closing(sqlite3.connect(v3.resolve().as_uri() + "?mode=ro", uri=True)) as src:
        if src.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise ValueError("Invalid V3 database")
        old_meta = dict(src.execute("SELECT key,value FROM metadata"))
        if old_meta.get("schema") != "speed-cameras-experimental-v3":
            raise ValueError("Expected OSM V3 database")
        if old_meta.get("source") != pbf.name:
            raise ValueError("Use the same PBF as V3")
        osm = {row[0]: row[1:] for row in src.execute(
            "SELECT osm_node,lat,lon,maxspeed,direction,confidence,reason FROM cameras")}
        points = {("osm", str(k)): (v[0], v[1]) for k, v in osm.items()}
        points.update({("mapatlas", k): (v[0], v[1]) for k, v in atlas.items() if v[2] == "spot"})
        print("Scanning PBF for", len(points), "candidate speed-control locations", flush=True)
        geom = CombinedGeometry(points)
        geom.apply_file(str(pbf), locations=True, idx="flex_mem")
        stats = Counter()
        try:
            # The legacy OSM tables are preserved exactly, with MapAtlas kept separate.
            with closing(sqlite3.connect(part)) as db:
                src.backup(db)
                db.executescript("""
                    CREATE TABLE speedpack_sources(source TEXT PRIMARY KEY, metadata_json TEXT NOT NULL);
                    CREATE TABLE mapatlas_records(ref TEXT PRIMARY KEY, lat REAL NOT NULL,
                        lon REAL NOT NULL, class TEXT NOT NULL, limit_kph INTEGER,
                        facing_deg REAL, approach TEXT, raw_json TEXT NOT NULL);
                    CREATE INDEX mapatlas_location ON mapatlas_records(lat,lon);
                    CREATE TABLE road_evidence(source TEXT NOT NULL, record_id TEXT NOT NULL,
                        osm_way INTEGER NOT NULL, segment_a INTEGER NOT NULL, segment_b INTEGER NOT NULL,
                        road_lat REAL NOT NULL, road_lon REAL NOT NULL, bearing REAL NOT NULL,
                        offset_m REAL NOT NULL, highway TEXT, name TEXT, road_ref TEXT,
                        oneway TEXT, grade INTEGER NOT NULL, shared_node INTEGER NOT NULL,
                        PRIMARY KEY(source,record_id,osm_way));
                    CREATE INDEX road_evidence_way ON road_evidence(osm_way);
                    CREATE TABLE road_assessment(source TEXT NOT NULL, record_id TEXT NOT NULL,
                        road_state TEXT NOT NULL, candidate_way INTEGER,
                        direction_state TEXT NOT NULL, direction_raw TEXT,
                        PRIMARY KEY(source,record_id));
                    CREATE TABLE continuity_hints(source TEXT NOT NULL, record_id TEXT NOT NULL,
                        way_a INTEGER NOT NULL, way_b INTEGER NOT NULL,
                        PRIMARY KEY(source,record_id,way_a,way_b));
                    CREATE TABLE cross_source_hints(osm_node INTEGER NOT NULL, atlas_ref TEXT NOT NULL,
                        distance_m REAL NOT NULL, reciprocal_nearest INTEGER NOT NULL,
                        limit_state TEXT NOT NULL, PRIMARY KEY(osm_node,atlas_ref));
                """)
                db.execute("UPDATE metadata SET value='speedpack-experimental-v1' WHERE key='schema'")
                db.executemany("INSERT INTO speedpack_sources VALUES (?,?)", [
                    ("osm", json.dumps(dict(old_meta, v3_sha256=digest(v3), pbf_sha256=digest(pbf),
                                            pbf_identity="V3 filename checked; original digest unavailable"))),
                    ("mapatlas", json.dumps(dict({k:v for k,v in metadata.items() if k != "features"},
                                                 sha256=digest(mapatlas))))])
                for ref, (lat, lon, kind, props) in atlas.items():
                    db.execute("INSERT INTO mapatlas_records VALUES (?,?,?,?,?,?,?,?)",
                               (ref, lat, lon, kind, limit_value(props.get("limitKph")),
                                props.get("facingDeg"), props.get("approach"), json.dumps(props)))
                    stats["mapatlas_" + kind] += 1
                for source, key in points:
                    node = int(key) if source == "osm" else None
                    candidates = geom.candidates.get((source,key), {})
                    state, way, links = assess(candidates, node)
                    if source == "osm":
                        _, _, _, direction, confidence, reason = osm[node]
                        # Relative OSM direction remains tied to legacy selected way.
                        direction_state = ("legacy_conflict" if reason == "direction_conflict" else
                                           "legacy_relative" if direction else "unknown")
                    else:
                        direction = atlas[key][3].get("approach")
                        direction_state = ("reported_both" if direction == "both" else
                                           "reported_uninterpreted" if direction or
                                           atlas[key][3].get("facingDeg") is not None else "unknown")
                    db.execute("INSERT INTO road_assessment VALUES (?,?,?,?,?,?)",
                               (source,key,state,way,direction_state,direction))
                    stats[source+"_"+state] += 1
                    for a,b in links:
                        db.execute("INSERT INTO continuity_hints VALUES (?,?,?,?)", (source,key,a,b))
                        stats["continuity_hints"] += 1
                    for road, c in candidates.items():
                        d,lat,lon,bearing = c["projection"]
                        db.execute("INSERT INTO road_evidence VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                   (source,key,road,c["a"],c["b"],lat,lon,bearing,d,
                                    c["highway"],c["name"],c["ref"],c["oneway"],
                                    int(c["grade"]),int(node in c["shared"])))
                        stats["road_evidence"] += 1
                for node, ref, d, mutual in cross_matches(
                        {k:(v[0],v[1]) for k,v in osm.items()},
                        {k:(v[0],v[1]) for k,v in atlas.items() if v[2]=="spot"}):
                    a,b = osm[node][2], limit_value(atlas[ref][3].get("limitKph"))
                    status = "missing" if a is None or b is None else "agree" if a==b else "conflict"
                    db.execute("INSERT INTO cross_source_hints VALUES (?,?,?,?,?)",
                               (node,ref,d,mutual,status))
                    stats["cross_pairs"] += 1
                    stats["reciprocal_nearest"] += mutual
                    if mutual:
                        stats["reciprocal_limit_"+status] += 1
                stats["osm_records"] = len(osm)
                stats["osm_average_candidates"] = db.execute(
                    "SELECT count(*) FROM average_speed_candidates").fetchone()[0]
                db.commit()
                if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                    raise ValueError("SpeedPack integrity check failed")
            part.rename(output)
        except BaseException:
            part.unlink(missing_ok=True)
            raise
    print(json.dumps({"stats": dict(stats), "output": str(output)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path)
    parser.add_argument("--osm-v3", required=True, type=Path)
    parser.add_argument("--mapatlas", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build(args.pbf, args.osm_v3, args.mapatlas, args.output)
