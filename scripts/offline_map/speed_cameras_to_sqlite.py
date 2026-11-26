#!/usr/bin/env python3
"""Experimental OSM speed-control index. OSM data ODbL; not verified active devices."""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import sqlite3
import osmium
from speed_camera_geometry import Geometry, resolve_geometry
from speed_camera_candidates import candidate_rows, duplicate_pairs

ROAD_TYPES = set("motorway trunk primary secondary tertiary unclassified residential living_street motorway_link trunk_link primary_link secondary_link tertiary_link service".split())


def speed(value):
    return int(value) if value and value.isascii() and value.isdigit() and 0 < int(value) <= 150 else None


class Relations(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.fixed = defaultdict(list)
        self.average = {}

    def relation(self, obj):
        kinds = {x.strip() for x in (obj.tags.get("enforcement") or "").split(";")}
        if not kinds.intersection({"maxspeed", "average_speed"}):
            return
        members = [(m.type, m.ref, m.role) for m in obj.members]
        row = (obj.id, dict(obj.tags), members)
        if "average_speed" in kinds:
            self.average[obj.id] = row
        if "maxspeed" in kinds and "average_speed" not in kinds:
            for kind, ref, role in members:
                if kind == "n" and role == "device":
                    self.fixed[ref].append(row)


class Nodes(osmium.SimpleHandler):
    def __init__(self, relations):
        super().__init__()
        self.relations = relations
        self.nodes = {}

    def node(self, obj):
        tags = obj.tags
        kind = tags.get("enforcement")
        explicit = kind in ("maxspeed", "speed_camera")
        generic = tags.get("highway") == "speed_camera" and not kind
        if not (explicit or generic or obj.id in self.relations.fixed):
            return
        if kind and not explicit:
            return
        if obj.location.valid():
            self.nodes[obj.id] = (obj.location.lat, obj.location.lon, dict(tags))


class Roads(osmium.SimpleHandler):
    def __init__(self, nodes):
        super().__init__()
        self.nodes = nodes
        self.matches = defaultdict(list)

    def way(self, obj):
        if obj.tags.get("highway") not in ROAD_TYPES:
            return
        refs = [n.ref for n in obj.nodes]
        for i, ref in enumerate(refs):
            if ref in self.nodes:
                self.matches[ref].append((obj.id, i, refs, dict(obj.tags)))


def association(node_id, tags, roads, relations):
    # Only exact shared OSM nodes; no speculative nearest-road assignment.
    if len(roads) != 1:
        return None, None, "low", "no_unique_shared_road"
    way, index, refs, road_tags = roads[0]
    directions = set()
    for _, _, members in relations:
        starts = [ref for kind, ref, role in members if kind == "n" and role == "from"]
        ends = [ref for kind, ref, role in members if kind == "n" and role == "to"]
        if len(starts) != 1 or len(ends) != 1:
            continue
        if any(refs.count(ref) != 1 for ref in (node_id, starts[0], ends[0])):
            continue
        a, b = refs.index(starts[0]), refs.index(ends[0])
        if a < index < b:
            directions.add("forward")
        elif b < index < a:
            directions.add("backward")
    explicit = tags.get("direction")
    if explicit in ("forward", "backward"):
        directions.add(explicit)
    if explicit in ("both", "forward;backward", "forward; backward"):
        directions.update(("forward", "backward"))
    oneway = road_tags.get("oneway")
    if oneway in ("yes", "1", "true", "-1"):
        allowed = "backward" if oneway == "-1" else "forward"
        if directions and directions != {allowed}:
            return way, None, "low", "direction_conflict"
        if not directions:
            return way, allowed, "medium", "shared_node_oneway"
    if len(directions) == 1:
        return way, next(iter(directions)), "medium", "shared_node_direction"
    if len(directions) == 2 and explicit in ("both", "forward;backward", "forward; backward"):
        return way, "both", "medium", "shared_node_both"
    return way, None, "low", "unknown_or_conflicting_direction"


def build(pbf, output):
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    part = output.with_name(output.name + ".part")
    if part.exists():
        raise FileExistsError(part)
    rel = Relations()
    rel.apply_file(str(pbf))
    nodes = Nodes(rel)
    nodes.apply_file(str(pbf))
    roads = Roads(nodes.nodes)
    roads.apply_file(str(pbf))
    geometry = Geometry(nodes.nodes)
    geometry.apply_file(str(pbf), locations=True, idx='flex_mem')
    stats = Counter()
    try:
        with sqlite3.connect(part) as db:
            db.executescript("""
                CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE cameras(osm_node INTEGER PRIMARY KEY, lat REAL NOT NULL,
                    lon REAL NOT NULL, osm_way INTEGER, direction TEXT,
                    confidence TEXT NOT NULL, reason TEXT NOT NULL,
                    maxspeed INTEGER, raw_tags TEXT NOT NULL, relation_ids TEXT NOT NULL,
                    road_lat REAL, road_lon REAL, road_bearing REAL, offset_m REAL);
                CREATE INDEX cameras_location ON cameras(lat,lon);
                CREATE TABLE road_candidates(
                    osm_node INTEGER NOT NULL, osm_way INTEGER NOT NULL,
                    road_lat REAL NOT NULL, road_lon REAL NOT NULL,
                    road_bearing REAL NOT NULL, offset_m REAL NOT NULL,
                    oneway TEXT, grade_separated INTEGER NOT NULL,
                    shared_node INTEGER NOT NULL, direction_state TEXT NOT NULL,
                    direction TEXT, PRIMARY KEY(osm_node,osm_way));
                CREATE INDEX candidates_way ON road_candidates(osm_way);
                CREATE TABLE duplicate_hints(a INTEGER NOT NULL,b INTEGER NOT NULL,
                    distance_m REAL NOT NULL, PRIMARY KEY(a,b));
                CREATE TABLE average_speed_candidates(osm_relation INTEGER PRIMARY KEY,
                    raw_tags TEXT NOT NULL, members TEXT NOT NULL);
            """)
            for ref, (lat, lon, tags) in nodes.nodes.items():
                relations = rel.fixed.get(ref, [])
                way, direction, confidence, reason = association(ref, tags, roads.matches[ref], relations)
                candidate = geometry.candidates.get(ref, {})
                way, direction, confidence, reason, projection = resolve_geometry(
                    tags, candidate, way, direction, confidence, reason)
                limits = {v for v in [speed(tags.get("maxspeed"))] +
                          [speed(t.get("maxspeed")) for _, t, _ in relations] if v is not None}
                limit = next(iter(limits)) if len(limits) == 1 else None
                db.execute("INSERT INTO cameras VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           (ref, lat, lon, way, direction, confidence, reason, limit,
                            json.dumps(tags), json.dumps([r[0] for r in relations]), *projection))
                rows = list(candidate_rows(ref, tags, candidate, roads.matches[ref],
                                           way, direction, confidence, reason))
                db.executemany("INSERT INTO road_candidates VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
                stats["candidate_rows"] += len(rows)
                if rows:
                    stats["nodes_with_candidates"] += 1
                if any(row[9] == "unknown" for row in rows):
                    stats["nodes_with_unknown_direction"] += 1
                stats[confidence] += 1
                stats[reason] += 1
            for pair in duplicate_pairs(nodes.nodes):
                db.execute("INSERT INTO duplicate_hints VALUES (?,?,?)", pair)
                stats["duplicate_hints"] += 1
            for ref, (_, tags, members) in rel.average.items():
                db.execute("INSERT INTO average_speed_candidates VALUES (?,?,?)",
                           (ref, json.dumps(tags), json.dumps(members)))
            db.executemany("INSERT INTO metadata VALUES (?,?)", [
                ("schema", "speed-cameras-experimental-v3"), ("source", pbf.name),
                ("license", "OpenStreetMap contributors, ODbL"),
                ("rows", str(len(nodes.nodes)))])
            if db.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise ValueError("SQLite integrity check failed")
        part.rename(output)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    print(json.dumps({"cameras": len(nodes.nodes), "average_speed_candidates": len(rel.average),
                      "association": dict(stats), "output": str(output)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build(args.pbf, args.output)
