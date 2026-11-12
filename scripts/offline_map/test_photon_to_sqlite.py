"""Offline Photon JSONL converter regression checks, no network required."""
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from photon_to_sqlite import build

class TestPhotonSQLite(unittest.TestCase):
    def test_indirect_addresses_and_unicode(self):
        events = [
            {"type": "NominatimDumpFile", "content": {"version": "0.1.0"}},
            {"type": "Place", "content": [
                {"place_id": 1, "country_code": "it", "address_type": "city",
                 "name": {"name": "Forlì"}, "centroid": [12.04, 44.22]},
                {"place_id": 2, "country_code": "it", "address_type": "street",
                 "name": {"name": "Viale dell'Appennino"},
                 "centroid": [12.05, 44.21]},
            ]},
            {"type": "Place", "content": [
                {"place_id": 3, "country_code": "it", "address_type": "house",
                 "housenumber": "25",
                 "addresslines": [{"place_id": 1, "isaddress": True},
                                  {"place_id": 2, "isaddress": True}],
                 "centroid": [12.051, 44.213]},
                {"place_id": 4, "country_code": "fr", "address_type": "city",
                 "name": {"name": "Paris"}, "centroid": [2.3, 48.8]},
                {"place_id": 5, "country_code": "it", "address_type": "city",
                 "name": {"name": "Invalid"}, "centroid": [200, 45.0]}
            ]}
        ]
        with tempfile.TemporaryDirectory() as directory:
            src = Path(directory) / "test.jsonl"
            target = Path(directory) / "test.sqlite"
            src.write_text("\n".join(json.dumps(e, ensure_ascii=False)
                                     for e in events), encoding="utf8")
            build(str(src), str(target))
            with sqlite3.connect(target) as db:
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM places").fetchone()[0], 3)
                self.assertEqual(db.execute(
                    "SELECT name,street,house,city FROM places WHERE kind='house'"
                ).fetchone(),
                    ("Viale dell'Appennino 25", "Viale dell'Appennino",
                     "25", "Forlì"))
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM places_fts WHERE search_text MATCH ?",
                    ("viale* dell* appennino* forli*",)).fetchone()[0], 2)
                self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 1)

    def test_failed_import_removes_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            src = Path(directory) / "wrong.jsonl"
            out = Path(directory) / "out.sqlite"
            src.write_text('{"type":"Place","content":[]}\n', encoding="utf8")
            with self.assertRaises(ValueError):
                build(str(src), str(out))
            self.assertFalse(out.exists())

if __name__ == "__main__":
    unittest.main()
