"""POI index regression checks; synthetic OSM records, no network/PBF required."""
import json
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from photon_to_sqlite import build as build_photon
from add_poi_to_photon import build as build_poi, classify, label

class MockTags(dict):
    pass

class MockPoint:
    def __init__(self, lat, lon):
        self.lat, self.lon = lat, lon
    def valid(self):
        return True

class MockNode:
    def __init__(self, ident, lat, lon, tags):
        self.id, self.location, self.tags = ident, MockPoint(lat, lon), MockTags(tags)

class MockWayNode:
    def __init__(self, lat, lon):
        self.location = MockPoint(lat, lon)

class MockWay:
    def __init__(self, ident, tags):
        self.id, self.tags = ident, MockTags(tags)
        self.nodes = [MockWayNode(45.0, 7.0), MockWayNode(45.001, 7.001),
                      MockWayNode(45.0, 7.0)]

class TestPOIIndex(unittest.TestCase):
    def test_categories_distinguish_restaurant_bar_hotel_and_fuel(self):
        assert classify({"amenity": "restaurant"}) == ("restaurant", "restaurant")
        assert classify({"amenity": "fuel"}) == ("fuel", "fuel")
        assert classify({"amenity": "cafe"}) == ("cafe", "cafe")
        assert classify({"tourism": "motel"}) == ("hotel", "motel")
        assert classify({"tourism": "guest_house"}) == ("hotel", "guest_house")
        assert classify({"amenity": "parking"}) is None
        assert label({"name": "La Pergola"}, "restaurant", "restaurant") == "La Pergola"
        assert label({}, "fuel", "fuel") == "Distributore"

    def test_generated_photon_is_augmented_without_changing_v2(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "photon.jsonl"
            source.write_text("\n".join(map(json.dumps, [
                {"type": "NominatimDumpFile", "content": {"version": "0.1.0"}},
                {"type": "Place", "content": [{"place_id": 1, "country_code": "it",
                    "address_type": "city", "name": {"name": "Cuneo"},
                    "centroid": [7.55, 44.39]}]}])) + "\n")
            db = Path(folder) / "photon.sqlite"
            build_photon(source, db)
            fake_pbf = Path(folder) / "italy.osm.pbf"
            fake_pbf.write_bytes(b"fixture")
            class SimpleHandler:
                def apply_file(self, filename, locations, idx):
                    assert locations is True
                    assert idx == "sparse_file_array"
                    self.node(MockNode(101, 44.39, 7.55,
                        {"amenity": "restaurant", "name": "La Pergola"}))
                    self.node(MockNode(102, 44.395, 7.551,
                        {"amenity": "fuel", "brand": "IP"}))
                    self.way(MockWay(103,
                        {"tourism": "hotel", "name": "Albergo Monte"}))
                    self.node(MockNode(104, 44.4, 7.6,
                        {"amenity": "parking"}))
            fake_osmium = types.ModuleType("osmium")
            fake_osmium.SimpleHandler = SimpleHandler
            with patch.dict(sys.modules, {"osmium": fake_osmium}):
                build_poi(fake_pbf, db)
            with sqlite3.connect(db) as sql:
                assert sql.execute("PRAGMA user_version").fetchone()[0] == 2
                assert sql.execute("SELECT COUNT(*) FROM places").fetchone()[0] == 1
                assert sql.execute("SELECT COUNT(*) FROM poi_places").fetchone()[0] == 3
                assert sql.execute("SELECT value FROM metadata WHERE key='poi_count'").fetchone()[0] == "3"
                names = [r[0] for r in sql.execute(
                    "SELECT name FROM poi_places WHERE category='restaurant'")]
                assert names == ["La Pergola"]
                hotels = sql.execute("SELECT COUNT(*) FROM poi_places WHERE category='hotel'").fetchone()[0]
                assert hotels == 1
                assert sql.execute("SELECT COUNT(*) FROM sqlite_master WHERE name='poi_category_lat_lon'").fetchone()[0] == 1
            with patch.dict(sys.modules, {"osmium": fake_osmium}):
                with self.assertRaisesRegex(ValueError, "already exists"):
                    build_poi(fake_pbf, db)

if __name__ == "__main__":
    unittest.main()
