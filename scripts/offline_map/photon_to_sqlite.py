#!/usr/bin/env python3
"""Convert a Photon/Nominatim 0.1.x JSONL dump to offline Android SQLite FTS4.

The uncompressed JSONL or Zstandard-compressed JSONL is streamed. The output
schema is compact Photon v2 (FTS4 contentless, normalized city and region).
Python 3 stdlib only,
except for a system zstd executable when the input ends in .zst.
"""
import argparse
import contextlib
import functools
import json
import math
from pathlib import Path
import sqlite3
import subprocess
import sys

SCHEMA = """
PRAGMA user_version=2;
PRAGMA application_id=1296519248;
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE places(
 id INTEGER PRIMARY KEY, name TEXT NOT NULL, street TEXT NOT NULL,
 house TEXT NOT NULL, city_id INTEGER NOT NULL, region_id INTEGER NOT NULL,
 postcode TEXT NOT NULL, kind TEXT NOT NULL, lat REAL NOT NULL,
 lon REAL NOT NULL, importance REAL NOT NULL);
CREATE TABLE cities(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE regions(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE VIRTUAL TABLE places_fts USING fts4(
 search_text, content='', tokenize=unicode61 "remove_diacritics=1");
CREATE TABLE address_refs(place_id TEXT PRIMARY KEY, kind TEXT, name TEXT);
"""

def first(value):
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return next((x for item in value if (x := first(item))), "")
    return ""

def name_of(value):
    if not isinstance(value, dict):
        return first(value)
    for k in ("name", "name:it", "name:en", "official_name", "short_name"):
        v = first(value.get(k))
        if v: return v
    return next((v for x in value.values() if (v := first(x))), "")

def address_value(value, kind):
    if not isinstance(value, dict):
        return ""
    for k in (kind, kind + ":it", kind + ":en"):
        v = first(value.get(k))
        if v: return v
    return next((v for k, x in value.items()
                 if k.startswith(kind + ":") and (v := first(x))), "")

def kind_of(place):
    value = place.get("address_type")
    if isinstance(value, str) and value:
        return value.lower()
    rank = place.get("rank_address")
    if not isinstance(rank, int):
        return "other"
    if rank <= 4: return "country"
    if rank <= 10: return "state"
    if rank <= 14: return "county"
    if rank <= 18: return "city"
    if rank <= 22: return "district"
    if rank <= 25: return "locality"
    if rank <= 27: return "street"
    return "house"

@contextlib.contextmanager
def open_lines(filename):
    if filename == "-":
        yield sys.stdin
    elif str(filename).lower().endswith(".zst"):
        proc = subprocess.Popen(["zstd", "-dc", "--", str(filename)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                encoding="utf-8", text=True)
        try:
            yield proc.stdout
        finally:
            if proc.stdout: proc.stdout.close()
            error = proc.stderr.read() if proc.stderr else ""
            if proc.wait() != 0:
                raise RuntimeError("zstd error: " + error[-500:])
    else:
        with open(filename, "r", encoding="utf-8") as stream:
            yield stream

def build(source, output, max_places=0):
    target = Path(output)
    if target.exists():
        raise FileExistsError("Output already exists; not overwriting " + str(target))
    target.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(target)
    kept = skipped = 0
    try:
        db.execute("PRAGMA synchronous=OFF")
        db.execute("PRAGMA journal_mode=OFF")
        db.executescript(SCHEMA)
        db.execute("INSERT INTO metadata VALUES('source','photon-json-dump-0.1.0')")
        db.execute("INSERT INTO metadata VALUES('data_region','IT')")
        db.execute("INSERT INTO metadata VALUES('format','compact-fts4-contentless-v2')")
        # Dimension tables replace repeated city/region strings in millions of places.
        for table in ("cities", "regions"):
            db.execute("INSERT INTO " + table + "(id,name) VALUES(0,'')")
        @functools.lru_cache(maxsize=30000)
        def dimension_id(table, name):
            if not name:
                return 0
            db.execute("INSERT OR IGNORE INTO " + table + "(name) VALUES(?)", (name,))
            return db.execute("SELECT id FROM " + table + " WHERE name=?", (name,)).fetchone()[0]
        @functools.lru_cache(maxsize=150000)
        def lookup(identifier):
            return db.execute(
                "SELECT kind,name FROM address_refs WHERE place_id=?",
                (identifier,)).fetchone()
        first_indexed_token = None
        header = False
        with open_lines(source) as stream:
            for line in stream:
                if not line.strip():
                    continue
                event = json.loads(line)
                typ = event.get("type")
                if not header:
                    if typ != "NominatimDumpFile":
                        raise ValueError("First record must be NominatimDumpFile")
                    version = str((event.get("content") or {}).get("version", ""))
                    if not version.startswith("0.1."):
                        raise ValueError("Unsupported JSONL dump version: " + version)
                    db.execute("INSERT INTO metadata VALUES('dump_version',?)", (version,))
                    header = True
                    continue
                if typ != "Place":
                    continue
                values = event.get("content")
                if not isinstance(values, list):
                    raise ValueError("Place.content must be an array")
                for item in values:
                    if not isinstance(item, dict):
                        continue
                    kind = kind_of(item)
                    label = name_of(item.get("name"))
                    ident = item.get("place_id")
                    if ident is not None and label and kind in (
                        "street", "city", "locality", "district", "county", "state"):
                        db.execute("INSERT OR REPLACE INTO address_refs VALUES(?,?,?)",
                                   (str(ident), kind, label))
                    if str(item.get("country_code") or "it").lower() != "it":
                        skipped += 1
                        continue
                    point = item.get("centroid")
                    if not isinstance(point, list) or len(point) < 2:
                        skipped += 1
                        continue
                    try:
                        lon, lat = float(point[0]), float(point[1])
                    except (ValueError, TypeError):
                        skipped += 1
                        continue
                    if not (math.isfinite(lat) and math.isfinite(lon)
                            and -90 <= lat <= 90 and -180 <= lon <= 180):
                        skipped += 1
                        continue
                    addr = item.get("address")
                    parts = {k: address_value(addr, k) for k in (
                        "street", "city", "locality", "district", "county", "state")}
                    # Prior referenced places are resolved before saving this record.
                    for ref in item.get("addresslines") or []:
                        if not isinstance(ref, dict) or ref.get("isaddress") is False:
                            continue
                        ref_id = ref.get("place_id")
                        resolved = lookup(str(ref_id)) if ref_id is not None else None
                        if resolved:
                            ref_kind, ref_name = resolved
                            if ref_kind in parts and not parts[ref_kind]:
                                parts[ref_kind] = ref_name
                    street = parts["street"] or (label if kind == "street" else "")
                    city = (parts["city"] or parts["locality"]
                            or parts["district"] or parts["county"]
                            or (label if kind == "city" else ""))
                    region = parts["state"]
                    house = first(item.get("housenumber"))
                    postcode = first(item.get("postcode"))
                    name = ((street + " " + house) if street and house
                            else label or street or (city if kind == "city" else ""))
                    if not name:
                        skipped += 1
                        continue
                    try:
                        importance = float(item.get("importance") or 0)
                        if not math.isfinite(importance): importance = 0
                    except (ValueError, TypeError):
                        importance = 0
                    cursor = db.execute(
                        "INSERT INTO places(name,street,house,city_id,region_id,postcode,kind,lat,lon,importance) "
                        "VALUES(?,?,?,?,?,?,?,?,?,?)",
                        (name, street, house, dimension_id("cities", city),
                         dimension_id("regions", region), postcode, kind,
                         lat, lon, importance))
                    terms = " ".join((name, street, house, city, region, postcode))
                    db.execute("INSERT INTO places_fts(docid,search_text) VALUES(?,?)",
                               (cursor.lastrowid, terms))
                    if first_indexed_token is None:
                        import re
                        first_indexed_token = next(
                            (t for t in re.findall(r"[^\W_]+", terms, re.UNICODE) if t),
                            None)
                    kept += 1
                    if kept % 5000 == 0: db.commit()
                    if kept % 100000 == 0:
                        print("Imported {:,} places".format(kept), file=sys.stderr)
                    if max_places and kept >= max_places: break
                if max_places and kept >= max_places: break
        if not header or kept == 0:
            raise ValueError("No searchable Photon Italian places")
        db.execute("INSERT INTO metadata VALUES('places',?)", (str(kept),))
        if max_places:
            db.execute("INSERT INTO metadata VALUES('partial','1')")
        db.execute("DROP TABLE address_refs")
        db.commit()
        # FTS4 optimize merges index segments; VACUUM removes pages freed by
        # the temporary address index. Build on a separate output file.
        db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
        db.commit()
        db.execute("VACUUM")
        # SQLite cannot verify an FTS4 contentless inverted index via
        # PRAGMA quick_check/integrity_check (reports a false SQL logic error).
        # Check relational tables individually and probe FTS with MATCH.
        for table in ("places", "cities", "regions", "metadata"):
            if db.execute("PRAGMA quick_check('" + table + "')").fetchone()[0] != "ok":
                raise ValueError("SQLite table check failed: " + table)
        if db.execute("SELECT COUNT(*) FROM places").fetchone()[0] != kept:
            raise ValueError("SQLite place count mismatch")
        # The FTS table itself is only readable through MATCH in contentless mode.
        if first_indexed_token and not db.execute(
            "SELECT docid FROM places_fts WHERE places_fts MATCH ? LIMIT 1",
            (first_indexed_token + "*",)
        ).fetchone():
            raise ValueError("Photon contentless FTS4 search check failed")
        print("Imported {:,} places; {:,} skipped; sqlite={:.1f} MiB".format(
            kept, skipped, target.stat().st_size / 1048576))
    except BaseException:
        db.close()
        with contextlib.suppress(OSError): target.unlink()
        raise
    finally:
        with contextlib.suppress(Exception): db.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--max-places", type=int, default=0)
    args = parser.parse_args()
    build(args.input, args.output, args.max_places)
