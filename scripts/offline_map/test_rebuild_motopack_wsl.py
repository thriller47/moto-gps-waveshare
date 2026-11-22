"""Non-destructive tests for the WSL MotoPack replacement wrapper."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

WRAPPER = Path(__file__).with_name("rebuild_motopack_wsl.sh")


class RebuildMotoPackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scripts = self.root / "scripts" / "offline_map"
        self.scripts.mkdir(parents=True)
        shutil.copyfile(WRAPPER, self.scripts / WRAPPER.name)
        self.src = self.root / "PBF"
        self.src.mkdir()
        for filename in ("italy-latest.osm.pbf", "italy.poly", "photon-italy.jsonl.zst"):
            (self.src / filename).write_bytes(b"mock source")
        self.out = self.root / "out"
        (self.scripts / "build_motopack_wsl.sh").write_text("""#!/usr/bin/env bash
set -euo pipefail
while (($#)); do
  case "$1" in
    --out) OUT=$2; shift 2;;
    --resume) shift;;
    --pbf|--poly|--photon|--threads|--python) shift 2;;
    *) exit 10;;
  esac
done
mkdir -p "$OUT/ValhallaItaliaV5"
echo new > "$OUT/italy-v4.motomap.zip"
echo new > "$OUT/photon-italy-v2.sqlite"
echo new > "$OUT/ValhallaItaliaV5/valhalla_tiles.tar.zst"
echo new > "$OUT/MotoChatGPS-Italia.motopack"
if [[ "${FAKE_FAIL:-0}" == 1 ]]; then exit 20; fi
""", encoding="utf-8")
        (self.scripts / "motopack.py").write_text('import sys\nassert sys.argv[1] == "verify"\n', encoding="utf-8")

    def run_script(self, *args, fail=False):
        env = os.environ.copy()
        if fail:
            env["FAKE_FAIL"] = "1"
        return subprocess.run(["bash", str(self.scripts / WRAPPER.name),
                               "--src", str(self.src), "--out", str(self.out), *args],
                              text=True, capture_output=True, check=False, env=env)

    def test_help_and_syntax(self):
        r = self.run_script("--help")
        self.assertEqual(r.returncode, 0, r.stderr)
        for option in ("--threads", "--src", "--out"):
            self.assertIn(option, r.stdout)
        r = subprocess.run(["bash", "-n", str(WRAPPER)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_replaces_old_artifacts_and_does_not_modify_sources(self):
        self.out.mkdir()
        (self.out / "MotoChatGPS-Italia.motopack").write_text("old")
        (self.out / "italy-v4.motomap.zip").write_text("old")
        (self.out / "ValhallaItaliaV5").mkdir()
        (self.out / "ValhallaItaliaV5" / "obsolete.txt").write_text("old")
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.out / "MotoChatGPS-Italia.motopack").read_text().strip(), "new")
        self.assertFalse((self.out / "ValhallaItaliaV5" / "obsolete.txt").exists())
        self.assertEqual((self.src / "italy.poly").read_bytes(), b"mock source")
        self.assertEqual(list(self.root.glob(".out.*")), [])

    def test_failure_keeps_previous_artifacts(self):
        self.out.mkdir()
        (self.out / "MotoChatGPS-Italia.motopack").write_text("old")
        r = self.run_script(fail=True)
        self.assertEqual(r.returncode, 20)
        self.assertEqual((self.out / "MotoChatGPS-Italia.motopack").read_text(), "old")
        stages = list(self.root.glob(".out.build.*"))
        self.assertEqual(len(stages), 1)
        self.assertTrue((stages[0] / ".road-dna-stage-sources").exists())

    def test_rejects_unrelated_files_and_preserves_them(self):
        self.out.mkdir()
        (self.out / "personal-notes.txt").write_text("important")
        r = self.run_script()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("File estraneo", r.stderr)
        self.assertEqual((self.out / "personal-notes.txt").read_text(), "important")

    def test_rejects_source_folder_as_output(self):
        r = self.run_script("--threads", "2")
        self.assertEqual(r.returncode, 0, r.stderr)
        r = self.run_script("--out", str(self.src))
        self.assertNotEqual(r.returncode, 0)
        self.assertTrue((self.src / "italy.poly").exists())

    def test_rejects_symlink_output(self):
        other = self.root / "other"
        other.mkdir()
        self.out.symlink_to(other, target_is_directory=True)
        r = self.run_script()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("simbolica", r.stderr)


if __name__ == "__main__":
    unittest.main()
