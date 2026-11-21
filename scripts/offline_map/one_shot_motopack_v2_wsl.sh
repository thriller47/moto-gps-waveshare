#!/usr/bin/env bash
# Clean one-command rebuild; never overwrites prior package.
set -euo pipefail
BASE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
ROOT=$(cd -- "$BASE/../.." && pwd -P)
SRC=/mnt/c/MotoGPS/PBF
OUT="$HOME/motogps-build/ItaliaUnificata-RoadDNA-v2"
THREADS=2
RESUME=false
while (($#)); do
  case "$1" in
    --src|--out|--threads)
      (($# >= 2)) || { echo "Missing argument for $1" >&2; exit 2; }
      case "$1" in
        --src) SRC=$2;;
        --out) OUT=$2;;
        --threads) THREADS=$2;;
      esac
      shift 2;;
    --resume) RESUME=true; shift;;
    -h|--help)
      echo 'Usage: one_shot_motopack_v2_wsl.sh [--src PBF_DIR] [--out NEW_DIR] [--threads 2] [--resume]'
      exit 0;;
    *) echo "Unknown argument: $1" >&2; exit 2;;
  esac
done
[[ "$THREADS" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid --threads" >&2; exit 2; }
[[ -d "$SRC" ]] || { echo "Missing source directory" >&2; exit 2; }
SRC=$(realpath -- "$SRC")
OUT=$(realpath -m -- "$OUT")
[[ ! -L "$OUT" ]] || { echo "Refusing symlink output" >&2; exit 2; }
[[ "$OUT" != / && "$OUT" != "$HOME" && "$OUT" != "$SRC" && "$OUT" != "$HOME/motogps-build" ]] || {
  echo "Unsafe output path" >&2; exit 2;
}
case "$OUT/" in "$SRC/"*) echo "Output inside source directory" >&2; exit 2;; esac
for f in italy-latest.osm.pbf italy.poly photon-italy.jsonl.zst; do
  [[ -s "$SRC/$f" ]] || { echo "Missing source $SRC/$f" >&2; exit 2; }
done
if [[ -e "$OUT" && "$RESUME" != true ]]; then
  echo "Output exists; use --resume or choose a new --out" >&2
  exit 2
fi
if [[ ! -e "$OUT" && "$RESUME" == true ]]; then
  echo "Nothing to resume" >&2
  exit 2
fi
if [[ -e "$OUT" && ! -d "$OUT" ]]; then
  echo "Output is not a directory" >&2; exit 2
fi
[[ ! -e "$OUT/MotoChatGPS-Italia.motopack" ]] || {
  echo "Package already exists: refusing overwrite" >&2; exit 2;
}
cd -- "$ROOT"
echo "=== Syntax checks and unit tests before expensive work ==="
python3 -m py_compile scripts/offline_map/tourist_to_sqlite.py scripts/offline_map/add_poi_to_photon.py scripts/offline_map/motopack.py
bash -n scripts/offline_map/build_motopack_wsl.sh
python3 -m unittest discover -s scripts/offline_map -p 'test_*.py' -v
echo "=== Disk space ==="
df -h "$HOME" "$SRC"
params=(--pbf "$SRC/italy-latest.osm.pbf" --poly "$SRC/italy.poly" --photon "$SRC/photon-italy.jsonl.zst" --out "$OUT" --threads "$THREADS")
if [[ "$RESUME" == true ]]; then params+=(--resume); fi
echo "=== Full rebuild of all four MotoPack components ==="
bash "$BASE/build_motopack_wsl.sh" "${params[@]}"
echo "=== Final SHA and manifest verification ==="
python3 "$BASE/motopack.py" verify "$OUT/MotoChatGPS-Italia.motopack"
echo "READY: $OUT/MotoChatGPS-Italia.motopack"
