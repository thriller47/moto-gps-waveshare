#!/usr/bin/env bash
# WSL-only, end-to-end builder: OSM PBF + matching POLY + Photon JSONL(.zst)
# => MotoMap v4, Valhalla V5, Photon SQLite v2, Road DNA tourist SQLite v1.
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
TOURIST="$OUT/tourist-italy-v1.sqlite"
DB="$OUT/photon-italy-v2.sqlite"
VAL_DIR="$OUT/ValhallaItaliaV5"
GRAPH="$VAL_DIR/valhalla_tiles.tar.zst"
PACK="$OUT/MotoChatGPS-Italia.motopack"
[[ ! -e "$PACK" && ! -e "$PACK.part" ]] || { echo "Package output already exists: $PACK" >&2; exit 2; }

step() { printf '\n===== %s =====\n' "$1"; }
step '1/5 MotoMap v4 (PBF + POLY)'
if [[ -e "$MAP" ]]; then
  $RESUME || { echo "Unexpected existing MotoMap: $MAP" >&2; exit 2; }
  echo "Reusing $MAP"
else
  [[ ! -e "$MAP.part" ]] || { echo "Remove incomplete MotoMap file before resuming: $MAP.part" >&2; exit 2; }
  "$PYTHON" "$SCRIPT_DIR/pbf_to_motomap.py" "$PBF" --coverage-poly "$POLY" -o "$MAP.part"
  mv -- "$MAP.part" "$MAP"
fi

step '2/5 Photon v2 (JSONL dump)'
if [[ -e "$DB" ]]; then
  $RESUME || { echo "Unexpected existing Photon: $DB" >&2; exit 2; }
  echo "Reusing $DB"
else
  [[ ! -e "$DB.part" ]] || { echo "Remove incomplete Photon file before resuming: $DB.part" >&2; exit 2; }
  "$PYTHON" "$SCRIPT_DIR/photon_to_sqlite.py" --input "$PHOTON" --output "$DB.part"
  mv -- "$DB.part" "$DB"
fi

# POIs are added inside Photon v2 SQLite, preserving the MotoPack's three
# components and the existing version. This also runs for a resumed Photon
# file if its POI index has not yet been added.
# Distinguish a new Photon v2 (POIs to build) from a corrupt or partially
# augmented SQLite file. An absent POI table is expected on a first build and
# must not generate a Python AssertionError traceback.
if "$PYTHON" - "$DB" <<'PY'
import sqlite3
import sys

with sqlite3.connect(sys.argv[1]) as conn:
    if conn.execute("PRAGMA user_version").fetchone()[0] != 2:
        raise SystemExit("Errore: Photon SQLite non e' v2")
    table = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='poi_places'"
    ).fetchone()
    if not table:
        # Exit code 10 means this Photon SQLite has not yet been augmented.
        sys.exit(10)
    schema = conn.execute(
        "SELECT value FROM metadata WHERE key='poi_schema'"
    ).fetchone()
    count = conn.execute(
        "SELECT value FROM metadata WHERE key='poi_count'"
    ).fetchone()
    index = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='index' "
        "AND name='poi_category_lat_lon'"
    ).fetchone()
    if schema != ("1",) or count is None or index is None:
        raise SystemExit("Errore: indice POI gia' presente ma incompleto")
    recorded = int(count[0])
    actual = conn.execute("SELECT COUNT(*) FROM poi_places").fetchone()[0]
    if recorded <= 0 or actual != recorded:
        raise SystemExit("Errore: conteggio POI non valido")
PY
then
  echo "Indice POI gia' completo nel database Photon: lo riutilizzo."
else
  rc=$?
  if (( rc == 10 )); then
    echo "Photon v2 pronto. Indice POI OSM assente: inizio estrazione dal PBF..."
    "$PYTHON" "$SCRIPT_DIR/add_poi_to_photon.py" --pbf "$PBF" --sqlite "$DB"
  else
    echo "Controllo dell'indice POI fallito (codice $rc): interrompo senza ricreare Photon." >&2
    exit "$rc"
  fi
fi

step '3/5 Road DNA tourist SQLite v1 (PBF: 3 passes, polygon validation)'
if [[ -e "$TOURIST" ]]; then
  $RESUME || { echo "Unexpected existing Road DNA index: $TOURIST" >&2; exit 2; }
  "$PYTHON" - "$TOURIST" <<'PY'
import sqlite3
import sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute("PRAGMA user_version").fetchone()[0] == 1
    assert db.execute("SELECT value FROM tourist_metadata WHERE key='schema'").fetchone() == ("road-dna-tourist-1",)
    count = db.execute("SELECT COUNT(*) FROM tourist_poi").fetchone()[0]
    assert count > 0
    assert db.execute("SELECT value FROM tourist_metadata WHERE key='count'").fetchone() == (str(count),)
    assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
PY
  echo "Reusing verified tourist index $TOURIST"
else
  [[ ! -e "$TOURIST.part" ]] || { echo "Road DNA generation left incomplete .part: $TOURIST.part" >&2; exit 2; }
  "$PYTHON" "$SCRIPT_DIR/tourist_to_sqlite.py" --pbf "$PBF" --output "$TOURIST"
fi

step '4/5 Valhalla motorcycle graph V5'
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

step '5/5 Package + SHA-256 verification'
"$PYTHON" "$SCRIPT_DIR/motopack.py" build --motomap "$MAP" --valhalla "$GRAPH" --photon "$DB" --tourist "$TOURIST" -o "$PACK"
"$PYTHON" "$SCRIPT_DIR/motopack.py" verify "$PACK"
echo "READY: $PACK"
