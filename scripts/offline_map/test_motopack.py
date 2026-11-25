"""Regression tests for Moto Chat GPS unified offline bundle."""
import importlib.util
import gzip
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("motopack", Path(__file__).with_name("motopack.py"))
mp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mp)


class MotoPackTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.roads = self.directory / "roads.zip"
        with zipfile.ZipFile(self.roads, "w") as z:
            z.writestr("motomap_v4.json", json.dumps({"format": "motomap", "version": 4,
                       "zoom": 15, "junction": "osm-junction-way-v4"}))
            z.writestr("tile_15_1_2.motomap", "data")
        self.db = self.directory / "places.sqlite"
        with sqlite3.connect(self.db) as db:
            db.execute("PRAGMA user_version=2")
            db.execute("CREATE TABLE metadata(key TEXT,value TEXT)")
            db.execute("INSERT INTO metadata VALUES('source','photon-json-dump-0.1.0')")
            db.execute("CREATE TABLE places(id INTEGER)")
            db.execute("INSERT INTO places VALUES(1)")
        self.graph = self.directory / "graph.tar.zst"
        self.graph.write_bytes(bytes.fromhex("28b52ffd") + b"fixture")
        self.tourist = self.directory / "tourist.sqlite"
        with sqlite3.connect(self.tourist) as tourist:
            tourist.execute("PRAGMA user_version=1")
            tourist.execute("CREATE TABLE tourist_metadata(key TEXT, value TEXT)")
            tourist.execute("INSERT INTO tourist_metadata VALUES('schema','road-dna-tourist-1')")
            tourist.execute("INSERT INTO tourist_metadata VALUES('count','1')")
            tourist.execute("""CREATE TABLE tourist_poi(
                osm_type TEXT,osm_id INT,category TEXT,name TEXT,lon REAL,lat REAL,
                min_lon REAL,min_lat REAL,max_lon REAL,max_lat REAL,
                geometry_type TEXT,location_quality TEXT)""")
            tourist.execute("INSERT INTO tourist_poi VALUES ('n',1,'belvedere','Belvedere',7,45,7,45,7,45,'point','exact')")
            tourist.execute("CREATE INDEX tourist_category_lat_lon ON tourist_poi(category,lat,lon)")
        self.road_index = self.directory / "road-dna-italy.sqlite"
        from road_dna_to_sqlite import SCHEMA
        with sqlite3.connect(self.road_index) as db:
            db.executescript(SCHEMA)
            db.executemany("INSERT INTO metadata VALUES (?,?)", [("schema","road-dna-roads-v1"),("rows","1")])
            db.execute("INSERT INTO roads VALUES (1,123,0,'Test','secondary','asphalt',45,7,45,7,45.01,7.01,1000,4,1,NULL,.8,900,140)")
            db.execute("CREATE INDEX roads_cell ON roads(cell_lat,cell_lon)")
        self.dem = self.directory / "elevation-italy.zip"
        with zipfile.ZipFile(self.dem, "w") as archive:
            sample = gzip.compress(b"0123456789" * 80)
            for tile in range(50):
                archive.writestr("N40/N40E%03d.hgt.gz" % tile, sample)
            archive.writestr("N45/N45E009.hgt.gz", sample)
        self.out = self.directory / "italy.motopack"

    def build(self):
        class Args:
            pass
        args = Args()
        args.motomap = str(self.roads)
        args.valhalla = str(self.graph)
        args.photon = str(self.db)
        args.tourist = str(self.tourist)
        args.elevation = str(self.dem)
        args.road_index = str(self.road_index)
        args.output = str(self.out)
        mp.build(args)

    def test_roundtrip(self):
        self.build()
        result = mp.verify(self.out)
        self.assertEqual(result["version"], 4)
        self.assertEqual(result["components"]["road_index"]["bytes"], self.road_index.stat().st_size)
        self.assertEqual(result["components"]["elevation"]["bytes"], self.dem.stat().st_size)
        self.assertEqual(result["components"]["tourist"]["bytes"], self.tourist.stat().st_size)
        self.assertEqual(result["components"]["photon"]["bytes"], self.db.stat().st_size)

    def test_corrupt_component(self):
        self.build()
        with zipfile.ZipFile(self.out) as z:
            payloads = {name: z.read(name) for name in mp.NAMES.values()}
            manifest = json.loads(z.read("manifest.json"))
        payloads[mp.NAMES["photon"]] += b"x"
        manifest["components"]["photon"]["bytes"] += 1
        with zipfile.ZipFile(self.out, "w") as z:
            for name, data in payloads.items():
                z.writestr(name, data)
            z.writestr("manifest.json", json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            mp.verify(self.out)

    def test_duplicate_manifest(self):
        self.build()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.out, "a") as z:
                z.writestr("manifest.json", "{}")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            mp.verify(self.out)

    def test_refuse_motomap_v3(self):
        with zipfile.ZipFile(self.roads, "w") as z:
            z.writestr("motomap_v4.json", json.dumps({"format":"motomap","version":3}))
        with self.assertRaisesRegex(ValueError, "MotoMap v4"):
            mp.validate_inputs({"motomap":self.roads,"valhalla":self.graph,"photon":self.db,"tourist":self.tourist,"elevation":self.dem,"road_index":self.road_index})

    def test_refuse_overwrite(self):
        self.build()
        with self.assertRaises(FileExistsError):
            self.build()


if __name__ == "__main__":
    unittest.main()
