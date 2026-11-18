#!/usr/bin/env bash
# Rebuild the entire MotoPack in WSL, replacing ONLY a known generated output.
# Sources are never modified; a failed rebuild leaves the previous output intact.
set -euo pipefail

usage() {
  cat <<'HELP'
Uso (WSL Ubuntu, ambiente valhalla-env attivo):
  bash scripts/offline_map/rebuild_motopack_wsl.sh [--out CARTELLA] [--threads 2] [--src CARTELLA_PBF] [--python python3]

Sorgenti predefiniti: /mnt/c/MotoGPS/PBF
  italy-latest.osm.pbf, italy.poly, photon-italy.jsonl.zst
Destinazione predefinita: ~/motogps-build/ItaliaUnificata-20261118

Ricrea MotoMap v4, Photon SQLite v2 e Valhalla V5 DAL PBF, quindi
assembla/verifica MotoChatGPS-Italia.motopack. Il vecchio
valhalla_italia_v5.tar.zst nella cartella sorgenti NON viene riutilizzato.
La vecchia destinazione rimane intatta finche' il pacchetto nuovo non supera
la verifica. Alla fine viene sostituita (backup temporaneo cancellato).
L'output preesistente puo' contenere solo i quattro artefatti generati;
per non perdere dati estranei, lo script rifiuta cartelle con altri file.
Servono temporaneamente spazio per VECCHIA e NUOVA build insieme.
HELP
}

SRC="/mnt/c/MotoGPS/PBF"
OUT="$HOME/motogps-build/ItaliaUnificata-20261118"
THREADS=2
PYTHON=python3
while (($#)); do
  case "$1" in
    --src|--out|--threads|--python)
      (($# >= 2)) || { echo "Valore mancante per $1" >&2; exit 2; }
      case "$1" in
        --src) SRC=$2;;
        --out) OUT=$2;;
        --threads) THREADS=$2;;
        --python) PYTHON=$2;;
      esac
      shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "Opzione sconosciuta: $1" >&2; usage >&2; exit 2;;
  esac
done

[[ "$THREADS" =~ ^[1-9][0-9]*$ ]] || { echo "--threads richiede un intero positivo" >&2; exit 2; }
[[ -d "$SRC" ]] || { echo "Cartella sorgenti assente: $SRC" >&2; exit 2; }
[[ ! -L "$OUT" ]] || { echo "Destinazione simbolica non consentita: $OUT" >&2; exit 2; }
SRC=$(realpath -- "$SRC")
OUT=$(realpath -m -- "$OUT")
[[ "$OUT" != / && "$OUT" != "$HOME" && "$OUT" != "$HOME/motogps-build" && "$OUT" != "$SRC" ]] || {
  echo "Destinazione potenzialmente pericolosa: $OUT" >&2; exit 2;
}
case "$OUT/" in "$SRC/"*) echo "Non scrivere dentro i sorgenti: $OUT" >&2; exit 2;; esac
for file in italy-latest.osm.pbf italy.poly photon-italy.jsonl.zst; do
  [[ -s "$SRC/$file" ]] || { echo "Sorgente mancante/vuota: $SRC/$file" >&2; exit 2; }
  resolved=$(realpath -- "$SRC/$file")
  case "$resolved" in "$OUT/"*) echo "Sorgente interna alla destinazione: $resolved" >&2; exit 2;; esac
done

if [[ -e "$OUT" ]]; then
  [[ -d "$OUT" ]] || { echo "La destinazione non e' una cartella: $OUT" >&2; exit 2; }
  # Do not discard logs, unrelated files or manually imported archives.
  shopt -s nullglob dotglob
  for existing in "$OUT"/*; do
    case "${existing##*/}" in
      italy-v4.motomap.zip|photon-italy-v2.sqlite|ValhallaItaliaV5|MotoChatGPS-Italia.motopack) ;;
      *) echo "File estraneo nella cartella di output: $existing" >&2
         echo "Spostalo altrove o scegli --out prima di sostituire la cartella." >&2
         exit 2;;
    esac
  done
  shopt -u nullglob dotglob
fi

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
BUILDER="$SCRIPT_DIR/build_motopack_wsl.sh"
[[ -f "$BUILDER" ]] || { echo "Generatore non trovato: $BUILDER" >&2; exit 2; }
PARENT=$(dirname -- "$OUT")
BASE=$(basename -- "$OUT")
mkdir -p -- "$PARENT"

STAGE=""
BACKUP=""
cleanup() {
  code=$?
  trap - EXIT
  if [[ -n "$BACKUP" && -d "$BACKUP" && ! -e "$OUT" ]]; then
    mv -- "$BACKUP" "$OUT" || echo "ATTENZIONE: ripristina manualmente $BACKUP -> $OUT" >&2
  fi
  if [[ -n "$STAGE" && -d "$STAGE" ]]; then
    rm -rf -- "$STAGE" || echo "ATTENZIONE: cartella temporanea ancora presente: $STAGE" >&2
  fi
  if (( code != 0 )); then
    echo "Ricostruzione non completata (codice $code). L'output precedente non e' stato eliminato." >&2
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

STAGE=$(mktemp -d -- "$PARENT/.$BASE.build.XXXXXXXX")
# The integrated builder requires its output path not to exist yet.
rmdir -- "$STAGE"
echo "Sorgenti:    $SRC"
echo "Destinazione: $OUT"
echo "Thread:      $THREADS"
echo "Ricreo tutti e tre i componenti; l'archivio Valhalla precedente non viene usato."

bash "$BUILDER" \
  --pbf "$SRC/italy-latest.osm.pbf" \
  --poly "$SRC/italy.poly" \
  --photon "$SRC/photon-italy.jsonl.zst" \
  --out "$STAGE" --threads "$THREADS" --python "$PYTHON"

# The integrated builder already verifies SHA-256, but verify again before publication.
"$PYTHON" "$SCRIPT_DIR/motopack.py" verify "$STAGE/MotoChatGPS-Italia.motopack"
chmod 755 -- "$STAGE"
if [[ -d "$OUT" ]]; then
  BACKUP=$(mktemp -d -- "$PARENT/.$BASE.old.XXXXXXXX")
  rmdir -- "$BACKUP"
  mv -- "$OUT" "$BACKUP"
fi
mv -- "$STAGE" "$OUT"
STAGE=""
if [[ -n "$BACKUP" ]]; then
  rm -rf -- "$BACKUP"
  BACKUP=""
fi
trap - EXIT
trap - INT TERM
printf '\nPRONTO: %s/MotoChatGPS-Italia.motopack\n' "$OUT"
