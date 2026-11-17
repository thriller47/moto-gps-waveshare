"""Unit tests: private diagnostic processing, no external map assets."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from analyze_progress_replay import (
    analyze, nearest_route_position, read_records, route_cumulative
)


class ReplayProgressAuditTests(unittest.TestCase):
    def test_nearest_position_projects_exactly_on_vertical_segment(self):
        points = [[45.0, 8.0], [45.002, 8.0]]
        cross, along = nearest_route_position(
            points, route_cumulative(points), 45.001, 8.0)
        self.assertLess(cross, 0.01)
        self.assertAlmostEqual(111.195, along, delta=0.5)

    def test_full_shape_and_independent_raw_guarded_maneuver(self):
        full = {"type": "route_full", "route_token": 7, "generation": 2,
                "points": [[45.0, 8.0], [45.002, 8.0]]}
        decision = {"type": "progress_debug", "route_token": 7,
                    "generation": 2, "lat": 45.001, "lon": 8.0,
                    "match_mode": "hold_offroute",
                    "matched_progress_m": 3.0,
                    "raw_remaining_m": 997,
                    "guarded_remaining_m": 999,
                    "plan_progress_m": 3.0,
                    "next_type": "roundabout", "next_id": 9,
                    "turn_m": 297}
        rows, totals = analyze([(0, full), (1000, decision)])
        self.assertEqual(len(rows), 1)
        self.assertEqual(totals["held"], 1)
        self.assertEqual(rows[0]["guard_delta_m"], 2.0)
        self.assertEqual(rows[0]["plan_progress_m"], 3.0)
        self.assertEqual(rows[0]["next_type"], "roundabout")
        self.assertAlmostEqual(rows[0]["nearest_route_progress_m"],
                               111.195, delta=0.5)

    def test_multi_part_zip_reconstructs_relative_session_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory, "parts.zip")
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("motogps-replay-part-00002.jsonl",
                    json.dumps({"type": "meta",
                                "session_elapsed_ms": 60000, "t_ms": 0}) + "\n" +
                    json.dumps({"type": "progress_debug",
                                "t_ms": 1500}) + "\n")
                archive.writestr("motogps-replay-part-00001.jsonl",
                    json.dumps({"type": "meta",
                                "session_elapsed_ms": 0, "t_ms": 0}) + "\n" +
                    json.dumps({"type": "progress_debug",
                                "t_ms": 1000}) + "\n")
            timed = [(t, e) for t, e in read_records(archive_path)
                     if e.get("type") == "progress_debug"]
            self.assertEqual([1000, 61500], [t for t, _ in timed])

    def test_old_replay_without_full_route_does_not_invent_analysis(self):
        rows, totals = analyze([(5, {"type": "progress_debug",
            "route_token": 1, "generation": 1, "lat": 45.0,
            "lon": 8.0})])
        self.assertFalse(rows)
        self.assertEqual(totals["missing_route"], 1)


if __name__ == "__main__":
    unittest.main()
