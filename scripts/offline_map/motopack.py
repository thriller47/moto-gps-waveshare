#!/usr/bin/env python3
"""Build and verify Moto Chat GPS unified offline bundles (Python 3 stdlib).

python3 scripts/offline_map/motopack.py build --motomap italy-v4.motomap.zip --valhalla valhalla_tiles.tar.zst --photon photon-italy-v2.sqlite --tourist tourist-italy-v1.sqlite --elevation elevation-italy.zip -o italy.motopack
python3 scripts/offline_map/motopack.py verify italy.motopack
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import zipfile

FORMAT = "motochat-offline"
NAMES = {
    "motomap": "motomap/italy-v4.motomap.zip",
    "valhalla": "valhalla/valhalla_tiles.tar.zst",
    "photon": "photon/photon-italy-v2.sqlite",
    "tourist": "road-dna/tourist-italy-v1.sqlite",
    "elevation": "elevation/elevation-italy.zip",
}
CHUNK = 1024 * 1024

def validate_inputs(paths):
    with zipfile.ZipFile(paths["motomap"]) as archive:
        meta = json.loads(archive.read("motomap_v4.json"))
        if (meta.get("format"), meta.get("version"), meta.get("zoom"), meta.get("junction")) != (
            "motomap", 4, 15, "osm-junction-way-v4"
        ):
            raise ValueError("Expected MotoMap v4 with OSM junction tags")
        if not any(name.startswith("tile_15_") and name.endswith(".motomap")
                   for name in archive.namelist()):
            raise ValueError("MotoMap does not contain road tiles")
    with sqlite3.connect("file:" + str(paths["photon"].resolve()) + "?mode=ro", uri=True) as db:
        if db.execute("PRAGMA user_version").fetchone()[0] != 2:
            raise ValueError("Photon SQLite v2 required")
        if db.execute("SELECT value FROM metadata WHERE key='source'").fetchone() != (
            "photon-json-dump-0.1.0",):
            raise ValueError("Unexpected Photon source")
        if db.execute("SELECT COUNT(*) FROM places").fetchone()[0] < 1:
            raise ValueError("Photon index empty")
        # Existing Photon v2 remains supported; augmented packs additionally
        # validate the OSM POI count and indexed category table.
        poi_marker = db.execute(
            "SELECT value FROM metadata WHERE key='poi_schema'").fetchone()
        if poi_marker is not None:
            if poi_marker != ("1",) or not db.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' "
                    "AND name='poi_places'").fetchone():
                raise ValueError("Invalid OSM POI schema")
            count = db.execute(
                "SELECT COUNT(*) FROM poi_places").fetchone()[0]
            expected = db.execute(
                "SELECT value FROM metadata WHERE key='poi_count'").fetchone()
            if count <= 0 or expected != (str(count),):
                raise ValueError("OSM POI count does not match Photon metadata")
            if not db.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='index' "
                    "AND name='poi_category_lat_lon'").fetchone():
                raise ValueError("OSM POI spatial index missing")
    with sqlite3.connect("file:" + str(paths["tourist"].resolve()) + "?mode=ro", uri=True) as db:
        if db.execute("PRAGMA user_version").fetchone()[0] != 1:
            raise ValueError("Road DNA tourist SQLite schema v1 required")
        required = {"tourist_poi", "tourist_metadata"}
        tables = {row[0] for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if not required <= tables:
            raise ValueError("Missing Road DNA tourist tables")
        metadata = dict(db.execute("SELECT key,value FROM tourist_metadata"))
        if metadata.get("schema") != "road-dna-tourist-1":
            raise ValueError("Unexpected Road DNA tourist schema marker")
        count = db.execute("SELECT COUNT(*) FROM tourist_poi").fetchone()[0]
        if count < 1 or metadata.get("count") != str(count):
            raise ValueError("Road DNA tourist count/metadata mismatch")
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='index' "
                          "AND name='tourist_category_lat_lon'").fetchone():
            raise ValueError("Road DNA tourist spatial index missing")
        if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("Road DNA tourist SQLite integrity check failed")
    with zipfile.ZipFile(paths["elevation"]) as dem:
        names = [name for name in dem.namelist() if not name.endswith("/")]
        pattern = re.compile(r"[NS][0-9]{2}/[NS][0-9]{2}[EW][0-9]{3}\.hgt\.gz")
        if (len(names) < 50 or len(names) != len(set(names)) or
                not all(pattern.fullmatch(name) for name in names)):
            raise ValueError("Invalid Skadi DEM Italia tile archive")
        for example in ("N45/N45E009.hgt.gz", "N40/N40E014.hgt.gz"):
            if example not in names:
                raise ValueError("Missing DEM sample tile: " + example)
        for name in names:
            with dem.open(name) as entry:
                if entry.read(2) != b"\x1f\x8b":
                    raise ValueError("DEM tile not gzip: " + name)
    with paths["valhalla"].open("rb") as f:
        if f.read(4) != bytes.fromhex("28b52ffd"):
            raise ValueError("Valhalla must be .tar.zst")

def verify(filename):
    with zipfile.ZipFile(filename) as archive:
        entries = archive.infolist()
        expected = {"manifest.json", *NAMES.values()}
        if len(entries) != len(expected) or {e.filename for e in entries} != expected:
            raise ValueError("Missing, extra or duplicate archive entries")
        if any(e.is_dir() or (e.flag_bits & 1) for e in entries):
            raise ValueError("Unsupported directory/encryption in archive")
        if not 0 < archive.getinfo("manifest.json").file_size <= 16384:
            raise ValueError("Invalid manifest length")
        manifest = json.loads(archive.read("manifest.json"))
        if (manifest.get("format"), manifest.get("version"), manifest.get("region")) != (
            FORMAT, 3, "IT"
        ):
            raise ValueError("Unsupported bundle format")
        components = manifest.get("components")
        if not isinstance(components, dict) or set(components) != set(NAMES):
            raise ValueError("Missing bundle components")
        for key, name in NAMES.items():
            item = components[key]
            entry = archive.getinfo(name)
            if (not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}
                    or item["path"] != name or type(item["bytes"]) is not int
                    or item["bytes"] < 1 or item["bytes"] != entry.file_size
                    or not isinstance(item["sha256"], str)
                    or not re.fullmatch("[0-9a-f]{64}", item["sha256"])):
                raise ValueError("Invalid descriptor: " + key)
            sha = hashlib.sha256()
            count = 0
            with archive.open(entry) as source:
                while chunk := source.read(CHUNK):
                    sha.update(chunk)
                    count += len(chunk)
            if (sha.hexdigest(), count) != (item["sha256"], item["bytes"]):
                raise ValueError("SHA-256 / length mismatch: " + key)
    return manifest

def build(args):
    paths = {key: Path(getattr(args, key)) for key in NAMES}
    for key, path in paths.items():
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Missing or empty file: " + key)
    validate_inputs(paths)
    output = Path(args.output)
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = output.with_name(output.name + ".part")
    if pending.exists():
        raise FileExistsError(pending)
    manifest = {"format": FORMAT, "version": 3, "region": "IT",
                "attribution": "OpenStreetMap contributors (ODbL); Valhalla; Photon",
                "components": {}}
    try:
        with zipfile.ZipFile(pending, "w", allowZip64=True) as archive:
            for key, name in NAMES.items():
                sha = hashlib.sha256()
                count = 0
                with paths[key].open("rb") as source, archive.open(
                    name, "w", force_zip64=True
                ) as destination:
                    while chunk := source.read(CHUNK):
                        sha.update(chunk)
                        destination.write(chunk)
                        count += len(chunk)
                manifest["components"][key] = {
                    "path": name, "bytes": count, "sha256": sha.hexdigest()
                }
            archive.writestr(
                "manifest.json", json.dumps(manifest, separators=(",", ":"), sort_keys=True)
            )
        verify(pending)
        pending.replace(output)
    finally:
        if pending.exists():
            pending.unlink()
    print("Created:", output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    for key in NAMES:
        b.add_argument("--" + key, required=True)
    b.add_argument("-o", "--output", required=True)
    v = sub.add_parser("verify")
    v.add_argument("file")
    args = parser.parse_args()
    if args.command == "build":
        build(args)
    else:
        verify(args.file)
        print("OK: MotoPack v3 / MotoMap v4 + Valhalla V5 + DEM + Photon v2 + Road DNA v1")

if __name__ == "__main__":
    main()
