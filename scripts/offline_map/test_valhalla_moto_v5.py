"""Smoke tests for the V5 Valhalla Mjolnir build wrapper; no Valhalla needed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("build_valhalla_moto_v5.sh")


class TestValhallaMotoV5(unittest.TestCase):
    def test_config_only_disables_exclusive_walk_cycle(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            bin_dir = directory / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "valhalla_build_config"
            fake.write_text(
                "#!/bin/sh\n"
                "echo '{\"mjolnir\":{\"include_bicycle\":true,"
                "\"include_pedestrian\":true,\"include_driving\":true,"
                "\"hierarchy\":true},\"loki\":{\"actions\":[\"route\"]}}'\n",
                encoding="utf8",
            )
            fake.chmod(0o755)
            pbf = directory / "italy-test.osm.pbf"
            pbf.write_bytes(b"not used in config preview")
            out = directory / "ValhallaItaliaV5"
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"])
            command = ["bash", str(SCRIPT), str(pbf), str(out),
                       "--config-only", "--threads", "2"]
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            config = json.loads((out / "valhalla.json").read_text(encoding="utf8"))
            self.assertFalse(config["mjolnir"]["include_bicycle"])
            self.assertFalse(config["mjolnir"]["include_pedestrian"])
            self.assertTrue(config["mjolnir"]["include_driving"])
            self.assertTrue(config["mjolnir"]["hierarchy"])
            self.assertEqual(config["loki"]["actions"], ["route"])
            # Cannot overwrite an existing V4 or V5 workspace.
            second = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertIn("already exists", second.stderr)

    def test_unknown_flags_fail_closed(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            bin_dir = directory / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "valhalla_build_config"
            fake.write_text("#!/bin/sh\necho '{\"mjolnir\":{}}'\n", encoding="utf8")
            fake.chmod(0o755)
            pbf = directory / "sample.osm.pbf"
            pbf.write_bytes(b"sample")
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", str(SCRIPT), str(pbf), str(directory / "V5"), "--config-only"],
                env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing boolean inclusion flags", result.stderr)


if __name__ == "__main__":
    unittest.main()
