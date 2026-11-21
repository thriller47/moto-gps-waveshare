"""Small pure geometry/category regressions for Road DNA tourist importer.

No Italian PBF, network, Android build or GitHub Actions required.
"""
import json
import sqlite3
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from tourist_to_sqlite import (
    SCHEMA, NodeRefFilter, build, classify, polygon_from_member_lines,
    relation_member_lines,
)


class TouristIndexTests(unittest.TestCase):
    def test_disk_reference_bloom_has_no_false_negatives(self):
        refs = [1, 2, 1234, 10_000_000_001, 99_999_999_999]
        bloom = NodeRefFilter()
        for ref in refs:
            bloom.add(ref)
        for ref in refs:
            self.assertTrue(bloom.maybe(ref))
        self.assertFalse(bloom.maybe(55_555_555_555))

    def test_secondary_water_tag(self):
        self.assertEqual(classify({"natural": "water", "water": "lake"}), ["lago"])
        self.assertEqual(classify({"natural": "water", "water": "reservoir"}), [])
        self.assertEqual(classify({"amenity": "public_bath", "bath:type": "thermal"}),
                         ["terme_stabilimento"])

    def test_relations_stitch_disconnected_member_ways(self):
        outer = [
            [(7, 45), (8, 45), (8, 46)],
            [(8, 46), (7, 46), (7, 45)],
        ]
        geom = polygon_from_member_lines(outer, [])
        self.assertIsNotNone(geom)
        self.assertAlmostEqual(geom.area, 1.0)
        self.assertTrue(geom.contains(geom.representative_point()))

    def test_relation_with_hole_not_navigable_at_center(self):
        outer = [[(7, 45), (9, 45), (9, 47), (7, 47), (7, 45)]]
        inner = [[(7.5, 45.5), (8.5, 45.5), (8.5, 46.5),
                  (7.5, 46.5), (7.5, 45.5)]]
        geom = polygon_from_member_lines(outer, inner)
        self.assertIsNotNone(geom)
        self.assertAlmostEqual(geom.area, 3.0)
        self.assertTrue(geom.contains(geom.representative_point()))
        from shapely.geometry import Point
        self.assertFalse(geom.contains(Point(8, 46)))

    def test_missing_inner_member_rejects_entire_relation(self):
        # Even a complete outer ring is unsafe if a hole was clipped away.
        outer = [(7, 45), (9, 45), (9, 47), (7, 47), (7, 45)]
        outer_lines, inner_lines, missing = relation_member_lines(
            [(101, "outer"), (102, "inner")], {101: outer})
        self.assertEqual(outer_lines, [outer])
        self.assertEqual(inner_lines, [])
        self.assertEqual(missing, [102])

    def test_resume_checkpoint_without_reopening_pbf(self):
        # Simulate an error after all coordinates resolved, including an
        # invalid relation member outside the clipped PBF. Only valid areas
        # may be published and skipped relations must be recorded in metadata.
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "italy.osm.pbf"
            source.write_bytes(b"synthetic-pbf-content")
            output = Path(directory) / "tourist.sqlite"
            part = output.with_name(output.name + ".part")
            st = source.stat()
            signature = json.dumps([str(source.resolve()), st.st_size, st.st_mtime_ns])
            with sqlite3.connect(part) as db:
                db.executescript(SCHEMA)
                db.execute("""CREATE TABLE selected_nodes(
                              id INTEGER PRIMARY KEY, lon REAL, lat REAL)""")
                db.execute("""CREATE TABLE selected_relations(
                              osm_id INTEGER PRIMARY KEY, categories_json TEXT NOT NULL,
                              name TEXT NOT NULL, members_json TEXT NOT NULL)""")
                db.execute("CREATE TABLE build_checkpoint(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                db.executemany("INSERT INTO build_checkpoint VALUES (?,?)",
                               [("source", signature), ("phase", "coordinates_ready")])
                db.executemany("INSERT INTO selected_nodes VALUES (?,?,?)",
                               [(1, 7., 45.), (2, 8., 45.),
                                (3, 8., 46.), (4, 7., 46.)])
                db.execute("INSERT INTO candidate_way VALUES (?,?,?)",
                           (101, "{}", json.dumps([1, 2, 3, 4, 1])))
                db.executemany("INSERT INTO selected_relations VALUES (?,?,?,?)",
                               [(201, '["lago"]', "Lake", '[[101, "outer"]]'),
                                (202, '["lago"]', "Clipped lake", '[[999, "inner"]]')])
                db.execute("""INSERT INTO tourist_poi VALUES
                          ('n',1,'belvedere','View',7,45,7,45,7,45,'point','exact')""")
            # No .apply_file methods: reopening the PBF would make this fail.
            with patch.dict("sys.modules", {"osmium": types.SimpleNamespace(SimpleHandler=object)}):
                build(source, output, resume=True)
            self.assertFalse(part.exists())
            with sqlite3.connect(output) as db:
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM tourist_poi WHERE osm_type='r'").fetchone()[0], 1)
                self.assertEqual(db.execute(
                    "SELECT value FROM tourist_metadata WHERE key='relation_missing_member_way'"
                ).fetchone(), ("1",))
                self.assertEqual(db.execute(
                    "SELECT value FROM tourist_metadata WHERE key='relation_coverage'"
                ).fetchone(), ("partial",))
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone(), ("ok",))

    def test_resume_rejects_modified_source(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "italy.osm.pbf"
            source.write_bytes(b"changed")
            dest = Path(directory) / "tourist.sqlite"
            with sqlite3.connect(str(dest) + ".part") as db:
                db.execute("CREATE TABLE build_checkpoint(key TEXT PRIMARY KEY, value TEXT)")
                db.executemany("INSERT INTO build_checkpoint VALUES (?,?)",
                               [("phase", "coordinates_ready"), ("source", "old-pbf")])
            with patch.dict("sys.modules", {"osmium": types.SimpleNamespace(SimpleHandler=object)}):
                with self.assertRaisesRegex(RuntimeError, "PBF changed"):
                    build(source, dest, resume=True)
            self.assertTrue(Path(str(dest) + ".part").exists())

    def test_unclosed_relation_is_rejected(self):
        self.assertIsNone(polygon_from_member_lines(
            [[(7, 45), (8, 45), (8, 46)]], []))


if __name__ == "__main__":
    unittest.main()
