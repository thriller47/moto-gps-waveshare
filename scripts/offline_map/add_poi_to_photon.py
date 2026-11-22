#!/usr/bin/env python3
"""Add geographically searchable OSM POIs to an existing Photon SQLite v2.

Uses the same Italy PBF already used by MotoMap and Valhalla. The existing
Photon tables and format version are preserved, so the MotoPack bundle format
and Photon importer remain compatible. Requires pyosmium (osmium).

Example:
 python3 scripts/offline_map/add_poi_to_photon.py \
   --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
   --sqlite ~/motogps-build/ItaliaUnificata/photon-italy-v2.sqlite

Supports OSM nodes and ways with two PBF passes; no giant file-backed node index. For ways, the representative coordinate is the
average of referenced node locations; this is for POI discovery, not routing.
No network requests. A failure rolls back SQLite changes.
"""
import argparse
import collections
import math
import sqlite3
import json
from pathlib import Path

CATEGORIES = {
    ("amenity", "fuel"): "fuel",
    ("amenity", "restaurant"): "restaurant",
    ("amenity", "fast_food"): "restaurant",
    ("amenity", "cafe"): "cafe",
    ("amenity", "bar"): "bar",
    ("amenity", "pub"): "bar",
    ("tourism", "hotel"): "hotel",
    ("tourism", "motel"): "hotel",
    ("tourism", "guest_house"): "hotel",
    ("tourism", "hostel"): "hotel",
    ("tourism", "apartment"): "hotel",
    ("tourism", "chalet"): "hotel",
}
SCHEMA = """
CREATE TABLE IF NOT EXISTS poi_places(
 osm_type TEXT NOT NULL,
 osm_id INTEGER NOT NULL,
 category TEXT NOT NULL,
 subtype TEXT NOT NULL,
 name TEXT NOT NULL,
 brand TEXT NOT NULL,
 lat REAL NOT NULL,
 lon REAL NOT NULL,
 PRIMARY KEY(osm_type, osm_id)
);
CREATE INDEX IF NOT EXISTS poi_category_lat_lon
 ON poi_places(category,lat,lon);
"""

def classify(tags):
    for (key, value), category in CATEGORIES.items():
        if tags.get(key) == value:
            return category, value
    return None

def label(tags, category, subtype):
    for key in ("name", "name:it", "brand", "operator"):
        value = tags.get(key)
        if value:
            return str(value).strip()[:160]
    return {
        "fuel": "Distributore", "restaurant": "Ristorante",
        "cafe": "Caffetteria", "bar": "Bar", "hotel": "Alloggio",
    }[category]

def build(pbf, database):
    import osmium
    source = Path(pbf)
    target = Path(database)
    if not source.is_file() or not target.is_file():
        raise FileNotFoundError("OSM PBF or Photon SQLite missing")
    db = sqlite3.connect(target)
    try:
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version != 2 or not db.execute(
                "SELECT 1 FROM sqlite_master WHERE name='places'").fetchone():
            raise ValueError("Expected an existing Photon SQLite v2 file")
        if db.execute(
                "SELECT 1 FROM sqlite_master WHERE name='poi_places'").fetchone():
            raise ValueError("POI index already exists; use a fresh Photon v2 SQLite")
        db.executescript(SCHEMA)
        db.execute("BEGIN")  # One transaction for the entire POI augmentation.
        statement = """INSERT OR REPLACE INTO poi_places
            (osm_type,osm_id,category,subtype,name,brand,lat,lon)
            VALUES (?,?,?,?,?,?,?,?)"""
        counts = collections.Counter()

        needed_refs = set()
        # SQLite TEMP table holds selected ways only, not a full OSM location
        # index. All references must resolve or the build is rejected.
        db.execute("""CREATE TEMP TABLE poi_candidate_ways(
            osm_id INTEGER PRIMARY KEY, category TEXT, subtype TEXT,
            name TEXT, brand TEXT, refs_json TEXT)""")
        class FirstPass(osmium.SimpleHandler):
            def node(self, node):
                classified = classify(node.tags)
                if classified is None or not node.location.valid():
                    return
                category, subtype = classified
                db.execute(statement, ("n", int(node.id), category, subtype,
                    label(node.tags, category, subtype),
                    str(node.tags.get("brand", "")).strip()[:100],
                    node.location.lat, node.location.lon))
                counts[category] += 1

            def way(self, way):
                classified = classify(way.tags)
                if classified is None:
                    return
                category, subtype = classified
                refs = [int(node.ref) for node in way.nodes]
                new_refs = set(refs) - needed_refs
                if not refs or len(needed_refs) + len(new_refs) > 3_000_000:
                    raise RuntimeError("Photon POI references exceed memory safety limit")
                needed_refs.update(new_refs)
                db.execute("INSERT INTO poi_candidate_ways VALUES(?,?,?,?,?,?)",
                           (int(way.id), category, subtype,
                            label(way.tags, category, subtype),
                            str(way.tags.get("brand", "")).strip()[:100],
                            json.dumps(refs)))

        print("Photon POI scan 1/2: selecting tourist-service objects...", flush=True)
        FirstPass().apply_file(str(source), locations=False)
        print("Photon POI scan 2/2: resolving {:,} selected node references...".format(
              len(needed_refs)), flush=True)
        coords = {}
        class SecondPass(osmium.SimpleHandler):
            def node(self, node):
                ident = int(node.id)
                if ident in needed_refs:
                    if node.location.valid():
                        coords[ident] = (node.location.lat, node.location.lon)
                    needed_refs.discard(ident)
        if needed_refs:
            SecondPass().apply_file(str(source), locations=False)

        for ident, category, subtype, name, brand, refs_json in db.execute(
                "SELECT osm_id,category,subtype,name,brand,refs_json FROM poi_candidate_ways"):
            refs = json.loads(refs_json)
            if any(ref not in coords for ref in refs):
                raise RuntimeError("Photon POI way {} has missing node locations".format(ident))
            points = [coords[ref] for ref in refs]
            if len(points) > 2 and points[0] == points[-1]:
                points.pop()
            db.execute(statement, ("w", ident, category, subtype, name, brand,
                                   sum(p[0] for p in points) / len(points),
                                   sum(p[1] for p in points) / len(points)))
            counts[category] += 1
        db.execute("DROP TABLE poi_candidate_ways")
        if not sum(counts.values()):
            raise ValueError("No POIs found: verify source OSM PBF and tags")
        db.execute("INSERT INTO metadata(key,value) VALUES('poi_schema','1')")
        db.execute("INSERT INTO metadata(key,value) VALUES('poi_count',?)",
                   (str(sum(counts.values())),))
        db.commit()
        print("POI index ready:", dict(counts), flush=True)
    except BaseException:
        db.rollback()
        # executescript() commits the DDL: do not leave a phantom POI schema
        # which a later resumed builder might mistake for a finished index.
        db.execute("DROP INDEX IF EXISTS poi_category_lat_lon")
        db.execute("DROP TABLE IF EXISTS poi_places")
        db.execute("DELETE FROM metadata WHERE key IN ('poi_schema','poi_count')")
        db.commit()
        raise
    finally:
        db.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", required=True)
    parser.add_argument("--sqlite", required=True)
    args = parser.parse_args()
    build(args.pbf, args.sqlite)

if __name__ == "__main__":
    main()
