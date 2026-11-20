#!/usr/bin/env python3
"""Add geographically searchable OSM POIs to an existing Photon SQLite v2.

Uses the same Italy PBF already used by MotoMap and Valhalla. The existing
Photon tables and format version are preserved, so the MotoPack bundle format
and Photon importer remain compatible. Requires pyosmium (osmium).

Example:
 python3 scripts/offline_map/add_poi_to_photon.py \
   --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
   --sqlite ~/motogps-build/ItaliaUnificata/photon-italy-v2.sqlite

Supports OSM nodes and ways. For ways, the representative coordinate is the
average of referenced node locations; this is for POI discovery, not routing.
No network requests. A failure rolls back SQLite changes.
"""
import argparse
import collections
import math
import sqlite3
import tempfile
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

        class POIs(osmium.SimpleHandler):
            def add(self, obj, osm_type, latitude, longitude):
                classified = classify(obj.tags)
                if classified is None:
                    return
                if not (math.isfinite(latitude) and math.isfinite(longitude)
                        and -90 <= latitude <= 90 and -180 <= longitude <= 180):
                    return
                category, subtype = classified
                db.execute(statement, (osm_type, int(obj.id), category, subtype,
                    label(obj.tags, category, subtype),
                    str(obj.tags.get("brand", "")).strip()[:100],
                    latitude, longitude))
                counts[category] += 1
                if sum(counts.values()) % 50000 == 0:
                    print("POI indexed:", sum(counts.values()), flush=True)

            def node(self, node):
                if node.location.valid():
                    self.add(node, "n", node.location.lat, node.location.lon)

            def way(self, way):
                if classify(way.tags) is None:
                    return
                coords = [(n.location.lat, n.location.lon)
                          for n in way.nodes if n.location.valid()]
                if not coords:
                    return
                # Avoid counting the repeated closing node twice.
                if len(coords) > 2 and coords[0] == coords[-1]:
                    coords.pop()
                self.add(way, "w",
                    sum(p[0] for p in coords) / len(coords),
                    sum(p[1] for p in coords) / len(coords))

        # locations=True resolves way nodes, including POIs mapped as building
        # outlines. A file-backed sparse index needs an explicit filename;
        # keep it temporary and inside WSL to conserve RAM and allow cleanup.
        with tempfile.TemporaryDirectory(prefix="motopoi-nodes-",
                                         dir=target.parent) as scratch:
            node_index = str(Path(scratch) / "nodes.idx")
            POIs().apply_file(str(source), locations=True,
                              idx="sparse_file_array," + node_index)
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
