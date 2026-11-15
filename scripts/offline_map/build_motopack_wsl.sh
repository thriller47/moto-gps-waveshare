#!/usr/bin/env bash
# WSL-only, end-to-end builder: OSM PBF + matching POLY + Photon JSONL(.zst)
# => MotoMap v4, Valhalla V5, Photon SQLite v2, verified .motopack.
set -euo pipefail

usage() {
  cat <<'HELP'
Usage:
  bash scripts/offline_map/build_motopack_wsl.sh \
    --pbf /mnt/c/MotoGPS/PBF/italy-latest.osm.pbf \
    --poly /mnt/c/MotoGPS/PBF/italy.poly \
    --photon /mnt/c/MotoGPS/PBF/photon-italy.jsonl.zst \
    --out "$HOME/motogps-build/ItaliaUnificata" [--threads 2] [--python python3] [--resume]

Run inside WSL Ubuntu with Valhalla 3.9.1 tools available on PATH, plus
Python with osmium and shapely, and zstd. Use a NEW output folder.
--resume reuses complete, previously generated intermediate artifacts; it
never overwrites an existing final .motopack or an incomplete Valhalla graph.
The script never deletes source files or any previous map installation.
HELP
}

PBF= POLY= PHOTON= OUT= THREADS=2 PYTHON=python3 RESUME=false
while (($#)); do
  case "$1" in
    --pbf|--poly|--photon|--out|--threads|--python)
      (($# >= 2)) || { echo "Missing value after $1" >&2; exit 2; }
      case "$1" in
        --pbf) PBF=$2;; --poly) POLY=$2;; --photon) PHOTON=$2;;
        --out) OUT=$2;; --threads) THREADS=$2;; --python) PYTHON=$2;;
      esac
      shift 2;;
    --resume) RESUME=true; shift;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2;;
  esac
done

[[ -n "$PBF" && -n "$POLY" && -n "$PHOTON" && -n "$OUT" ]] || { usage >&2; exit 2; }
[[ "$THREADS" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid --threads" >&2; exit 2; }
[[ -f /proc/sys/kernel/osrelease ]] && grep -qiE 'microsoft|wsl' /proc/sys/kernel/osrelease || {
  echo "This entry point must be run inside WSL (not PowerShell or native Linux)" >&2; exit 2;
}
for input in "$PBF" "$POLY" "$PHOTON"; do
  [[ -s "$input" ]] || { echo "Missing or empty input: $input" >&2; exit 2; }
done
[[ "$PBF" == *.osm.pbf && "$POLY" == *.poly ]] || { echo "Expected .osm.pbf and .poly inputs" >&2; exit 2; }
[[ "$PHOTON" == *.jsonl || "$PHOTON" == *.jsonl.zst ]] || {
  echo "Photon input must be Nominatim/Photon JSONL (.jsonl or .jsonl.zst), not an SQLite or Java/Lucene archive" >&2; exit 2;
}

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
command -v "$PYTHON" >/dev/null || { echo "Missing Python: $PYTHON" >&2; exit 2; }
for program in valhalla_build_config valhalla_build_tiles valhalla_build_extract zstd; do
  command -v "$program" >/dev/null || { echo "Missing required command: $program" >&2; exit 2; }
done
"$PYTHON" -c 'import osmium, shapely' || { echo "Install osmium and shapely into the selected Python environment" >&2; exit 2; }
# Valhalla V5 uses the system python3 (check separately from the selected Python).
command -v python3 >/dev/null || { echo "Missing python3 for Valhalla" >&2; exit 2; }

PBF=$(realpath -- "$PBF")
POLY=$(realpath -- "$POLY")
PHOTON=$(realpath -- "$PHOTON")
mkdir -p -- "$(dirname -- "$OUT")"
if [[ -e "$OUT" && "$RESUME" != true ]]; then
  echo "Output already exists: $OUT. Choose a new folder or use --resume." >&2; exit 2
fi
mkdir -p -- "$OUT"
OUT=$(cd -- "$OUT" && pwd -P)
for source in "$PBF" "$POLY" "$PHOTON"; do
  case "$source" in "$OUT"/*) echo "Source must not be inside the output directory: $source" >&2; exit 2;; esac
done
MAP="$OUT/italy-v4.motomap.zip"
DB="$OUT/photon-italy-v2.sqlite"
VAL_DIR="$OUT/ValhallaItaliaV5"
GRAPH="$VAL_DIR/valhalla_tiles.tar.zst"
PACK="$OUT/MotoChatGPS-Italia.motopack"
[[ ! -e "$PACK" && ! -e "$PACK.part" ]] || { echo "Package output already exists: $PACK" >&2; exit 2; }

step() { printf '\n===== %s =====\n' "$1"; }
step '1/4 MotoMap v4 (PBF + POLY)'
if [[ -e "$MAP" ]]; then
  $RESUME || { echo "Unexpected existing MotoMap: $MAP" >&2; exit 2; }
  echo "Reusing $MAP"
else
  [[ ! -e "$MAP.part" ]] || { echo "Remove incomplete MotoMap file before resuming: $MAP.part" >&2; exit 2; }
  "$PYTHON" "$SCRIPT_DIR/pbf_to_motomap.py" "$PBF" --coverage-poly "$POLY" -o "$MAP.part"
  mv -- "$MAP.part" "$MAP"
fi

step '2/4 Photon v2 (JSONL dump)'
if [[ -e "$DB" ]]; then
  $RESUME || { echo "Unexpected existing Photon: $DB" >&2; exit 2; }
  echo "Reusing $DB"
else
  [[ ! -e "$DB.part" ]] || { echo "Remove incomplete Photon file before resuming: $DB.part" >&2; exit 2; }
  "$PYTHON" "$SCRIPT_DIR/photon_to_sqlite.py" --input "$PHOTON" --output "$DB.part"
  mv -- "$DB.part" "$DB"
fi

step '3/4 Valhalla motorcycle graph V5'
if [[ -s "$GRAPH" ]]; then
  $RESUME || { echo "Unexpected existing graph: $GRAPH" >&2; exit 2; }
  echo "Reusing $GRAPH"
elif [[ -e "$VAL_DIR" ]]; then
  echo "Valhalla output directory already exists but compressed graph is missing/empty: $VAL_DIR" >&2
  echo "Inspect this incomplete build manually; never delete or overwrite it automatically." >&2
  exit 2
else
  bash "$SCRIPT_DIR/build_valhalla_moto_v5.sh" "$PBF" "$VAL_DIR" --zstd --threads "$THREADS"
fi

step '4/4 Package + SHA-256 verification'
"$PYTHON" "$SCRIPT_DIR/motopack.py" build --motomap "$MAP" --valhalla "$GRAPH" --photon "$DB" -o "$PACK"
"$PYTHON" "$SCRIPT_DIR/motopack.py" verify "$PACK"
echo "READY: $PACK"
