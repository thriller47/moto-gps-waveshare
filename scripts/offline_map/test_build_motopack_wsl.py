"""Non-destructive smoke tests for the WSL-only offline map orchestrator."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("build_motopack_wsl.sh")


class WslBuildTests(unittest.TestCase):
    def run_script(self, *args):
        return subprocess.run(["bash", str(SCRIPT), *args], text=True,
                              capture_output=True, check=False)

    def test_help_has_raw_inputs_and_resume(self):
        p = self.run_script("--help")
        self.assertEqual(p.returncode, 0, p.stderr)
        for flag in ("--pbf", "--poly", "--photon", "--out", "--resume"):
            self.assertIn(flag, p.stdout)

    def test_missing_values_fail_without_writes(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            p = self.run_script("--pbf", str(directory / "missing.osm.pbf"),
                                "--poly", str(directory / "missing.poly"),
                                "--photon", str(directory / "missing.jsonl"),
                                "--out", str(directory / "output"))
            self.assertNotEqual(p.returncode, 0)
            self.assertFalse((directory / "output").exists())

    def test_rejects_wrong_photon_archive_before_writes(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            for filename in ("italy.osm.pbf", "italy.poly", "photon.zip"):
                (directory / filename).write_bytes(b"x")
            p = self.run_script("--pbf", str(directory / "italy.osm.pbf"),
                                "--poly", str(directory / "italy.poly"),
                                "--photon", str(directory / "photon.zip"),
                                "--out", str(directory / "output"))
            self.assertNotEqual(p.returncode, 0)
            self.assertFalse((directory / "output").exists())

    def test_bash_syntax(self):
        p = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)


if __name__ == "__main__":
    unittest.main()
