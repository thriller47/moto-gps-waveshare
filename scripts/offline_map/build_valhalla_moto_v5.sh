#!/usr/bin/env bash
# Build Moto Chat GPS Valhalla V5 from an OSM PBF, dropping ONLY ways which
# Valhalla classifies as exclusively bicycle/pedestrian accessible.
# V4 and the source PBF are never modified.
set -euo pipefail

usage() {
  echo "Usage: $0 INPUT.osm.pbf NEW_OUTPUT_DIR [--zstd] [--threads N] [--config-only]"
  echo "Requires valhalla_build_config, Python 3; full build also requires valhalla_build_tiles and valhalla_build_extract."
  exit 2
}
[[ $# -ge 2 ]] || usage
PBF=$(realpath "$1")
OUT=$2
COMPRESS=false
THREADS=2
CONFIG_ONLY=false
shift 2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --zstd) $COMPRESS && usage; COMPRESS=true; shift ;;
    --threads) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]*$ ]] || usage; THREADS=$2; shift 2 ;;
    --config-only) $CONFIG_ONLY && usage; CONFIG_ONLY=true; shift ;;
    *) usage ;;
  esac
done
[[ -f "$PBF" && "$PBF" == *.osm.pbf ]] || { echo "Missing input .osm.pbf: $PBF" >&2; exit 2; }
for cmd in valhalla_build_config python3; do
  command -v "$cmd" >/dev/null || { echo "Missing command: $cmd" >&2; exit 2; }
done
if ! $CONFIG_ONLY; then
  for cmd in valhalla_build_tiles valhalla_build_extract; do
    command -v "$cmd" >/dev/null || { echo "Missing command: $cmd" >&2; exit 2; }
  done
  if $COMPRESS; then
    command -v zstd >/dev/null || { echo "Missing command: zstd" >&2; exit 2; }
  fi
fi

# A completely fresh workspace is mandatory. Never delete or modify a V4 build.
if [[ -e "$OUT" ]]; then
  echo "Output path already exists: $OUT. Use a new, empty V5 directory." >&2
  exit 2
fi
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd -P)
TILES="$OUT/valhalla_tiles"
TAR="$OUT/valhalla_tiles.tar"
CONFIG="$OUT/valhalla.json"
mkdir "$TILES"

valhalla_build_config --mjolnir-tile-dir "$TILES" \
  --mjolnir-tile-extract "$TAR" \
  --mjolnir-concurrency "$THREADS" \
  --mjolnir-timezone "$TILES/timezones.sqlite" \
  --mjolnir-admin "$TILES/admins.sqlite" > "$CONFIG"

# Modify the JSON produced by the INSTALLED Valhalla builder, rather than
# assuming a particular CLI spelling for boolean flags. Fail on unknown schema.
python3 - "$CONFIG" <<'PY'
import json
from pathlib import Path
import sys
path = Path(sys.argv[1])
config = json.loads(path.read_text(encoding="utf-8"))
mjolnir = config.get("mjolnir")
if not isinstance(mjolnir, dict):
    raise SystemExit("Unsupported Valhalla config: missing mjolnir object")
required = ("include_bicycle", "include_pedestrian", "include_driving")
if not all(isinstance(mjolnir.get(key), bool) for key in required):
    raise SystemExit("Unsupported Valhalla config: missing boolean inclusion flags")
mjolnir.update(include_bicycle=False, include_pedestrian=False,
               include_driving=True)
path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
if any(mjolnir[key] != expected for key, expected in
       (("include_bicycle", False), ("include_pedestrian", False),
        ("include_driving", True))):
    raise SystemExit("Valhalla V5 access filter validation failed")
print("V5 filter: include_bicycle=false, include_pedestrian=false, include_driving=true")
PY

if $CONFIG_ONLY; then
  # A preview workspace is deliberately not reusable for an actual build.
  # Keeping it immutable avoids silently mixing generated files with V5.
  echo "Configuration preview ONLY. No graph was built: $CONFIG"
  exit 0
fi

if command -v valhalla_build_timezones >/dev/null; then
  valhalla_build_timezones > "$TILES/timezones.sqlite"
else
  echo "WARNING: valhalla_build_timezones not found; time-dependent routing may be reduced." >&2
fi
if command -v valhalla_build_admins >/dev/null; then
  valhalla_build_admins -c "$CONFIG" "$PBF"
else
  echo "WARNING: valhalla_build_admins not found; admin-related attributes may be reduced." >&2
fi

valhalla_build_tiles -c "$CONFIG" "$PBF"
valhalla_build_extract -c "$CONFIG" -v
[[ -s "$TAR" ]] || { echo "Valhalla did not produce a TAR" >&2; exit 1; }
python3 - "$TAR" <<'PY'
import sys
import tarfile
with tarfile.open(sys.argv[1], "r:") as archive:
    tiles = sum(item.isfile() and item.name.endswith(".gph") for item in archive)
if tiles == 0:
    raise SystemExit("No .gph tiles in Valhalla TAR")
print(f"Validated V5 TAR: {tiles} .gph tiles")
PY
if $COMPRESS; then
  zstd -T2 -6 "$TAR" -o "$TAR.zst"
  echo "Android import: $TAR.zst"
else
  echo "Android import: $TAR"
fi
echo "V5 is experimental. Compare V4/V5 graph counts and test actual motorcycle routes before installation."
