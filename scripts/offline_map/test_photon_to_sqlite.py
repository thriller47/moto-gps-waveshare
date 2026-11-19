"""Offline Photon JSONL converter regression checks, no network required."""
import json
import subprocess
import sys
from unittest.mock import patch
from pathlib import Path
import sqlite3
import tempfile
import unittest

from photon_to_sqlite import build, open_lines
from analyze_valhalla_graph import inspect

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
                    "SELECT p.name,p.street,p.house,c.name FROM places p JOIN cities c ON c.id=p.city_id WHERE p.kind='house'"
                ).fetchone(),
                    ("Viale dell'Appennino 25", "Viale dell'Appennino",
                     "25", "Forlì"))
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM places_fts WHERE places_fts MATCH ?",
                    ("viale* dell* appennino* forli*",)).fetchone()[0], 1)
                self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0], 2)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM cities WHERE name='Forlì'").fetchone()[0], 1)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM sqlite_master WHERE name='places_fts_content'").fetchone()[0], 0)
                self.assertEqual(db.execute("PRAGMA quick_check('places')").fetchone()[0], "ok")

    def test_city_region_normalized_and_fts_search_preserved(self):
        records = [
            {"type": "NominatimDumpFile", "content": {"version": "0.1.0"}},
            {"type": "Place", "content": [
                {"place_id": 10, "country_code": "it", "address_type": "street",
                 "name": {"name": "Via Roma"}, "centroid": [7.68, 45.07],
                 "address": {"city": "Torino", "state": "Piemonte"}},
                {"place_id": 11, "country_code": "it", "address_type": "house",
                 "name": {"name": "Via Roma 12"}, "centroid": [7.681, 45.071],
                 "address": {"city": "Torino", "state": "Piemonte"},
                 "housenumber": "12"},
                {"place_id": 12, "country_code": "it", "address_type": "locality",
                 "name": {"name": "Passo Stelvio"}, "centroid": [10.45, 46.53],
                 "address": {"city": "Bormio", "state": "Lombardia"}},
            ]},
        ]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "photon.jsonl"
            target = Path(directory) / "compact.sqlite"
            source.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in records),
                              encoding="utf-8")
            build(str(source), str(target))
            with sqlite3.connect(target) as db:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM places").fetchone()[0], 3)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM cities WHERE name='Torino'").fetchone()[0], 1)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM regions WHERE name='Piemonte'").fetchone()[0], 1)
                hits = db.execute("""
                    SELECT p.name,c.name,r.name FROM places_fts
                    JOIN places p ON p.id=places_fts.docid
                    JOIN cities c ON c.id=p.city_id
                    JOIN regions r ON r.id=p.region_id
                    WHERE places_fts MATCH ? ORDER BY p.id
                """, ("via* roma* torino*",)).fetchall()
                self.assertEqual(hits, [
                    ("Via Roma", "Torino", "Piemonte"),
                    ("Via Roma 12", "Torino", "Piemonte")])
                # Execute the actual Android SELECT against a generated v2 DB,
                # catching stale column names which Kotlin compilation misses.
                kotlin = (Path(__file__).resolve().parents[2] /
                          "platforms/android/app/src/main/java/com/motogps/android"
                          "/viewmodel/OfflinePhotonPlaceSearch.kt").read_text(
                              encoding="utf-8")
                import re
                match = re.search(r'val sql = """(.*?)""".trimIndent',
                                  kotlin, flags=re.DOTALL)
                self.assertIsNotNone(match, "Android Photon search query missing")
                sql = match.group(1)
                result = db.execute(
                    sql, ("via* roma* torino*", "Via Roma Torino",
                          "Via Roma Torino", "Via Roma Torino")).fetchall()
                self.assertEqual(len(result), 2)
                self.assertEqual(result[0][3], "Torino")
                self.assertEqual(result[0][4], "Piemonte")
                self.assertEqual(result[0][0], "Via Roma")
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM places_fts WHERE places_fts MATCH ?",
                    ("passo* stelvio* bormio*",)).fetchone()[0], 1)
                self.assertEqual(db.execute("PRAGMA quick_check('places')").fetchone()[0], "ok")

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


class TestZstdPipes(unittest.TestCase):
    """Exercise real subprocess streaming with >1MiB diagnostic output."""

    def test_photon_decoder_verbose_stderr_does_not_deadlock(self):
        script = (
            'import signal,sys; signal.alarm(6); '
            'sys.stderr.write("E"*1048576); '
            'sys.stdout.write("one\\ntwo\\n")'
        )
        real_popen = subprocess.Popen
        def fake_zstd(argv, **kwargs):
            self.assertEqual(argv[0], "zstd")
            return real_popen([sys.executable, "-c", script], **kwargs)
        with patch.object(subprocess, "Popen", side_effect=fake_zstd):
            with open_lines("virtual.jsonl.zst") as source:
                self.assertEqual(list(source), ["one\n", "two\n"])

    def test_graph_decoder_verbose_stderr_does_not_deadlock(self):
        script = """
import io, signal, sys, tarfile
signal.alarm(6)
sys.stderr.write("E" * 1048576)
header = bytearray(48)
header[16:19] = b"3.1"
header[40:48] = (1 | (2 << 21)).to_bytes(8, "little")
with tarfile.open(fileobj=sys.stdout.buffer, mode="w|") as archive:
    member = tarfile.TarInfo("0/000/000/001.gph")
    member.size = len(header)
    archive.addfile(member, io.BytesIO(header))
"""
        real_popen = subprocess.Popen
        def fake_zstd(argv, **kwargs):
            self.assertEqual(argv[0], "zstd")
            return real_popen([sys.executable, "-c", script], **kwargs)
        with tempfile.TemporaryDirectory() as directory:
            fake_input = Path(directory) / "graph.tar.zst"
            fake_input.write_bytes(b"test")
            with patch.object(subprocess, "Popen", side_effect=fake_zstd):
                report = inspect(fake_input)
        self.assertTrue(report["complete"])
        self.assertEqual(report["totals"]["nodes"], 1)
        self.assertEqual(report["totals"]["directed_edges"], 2)
