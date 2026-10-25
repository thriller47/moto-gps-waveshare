#!/usr/bin/env python3
"""Check the working SIGNAL radio/GPS memory baseline (stdlib only).

Run from any directory:
    python platforms/esp32/tools/check_signal_baseline.py
Checks both the committed sdkconfig snapshot and sdkconfig.defaults.
This check does not rewrite configuration or invoke ESP-IDF.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "CONFIG_SPIRAM_USE_MALLOC": "y",
    "CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL": "512",
    "CONFIG_ESP_MAIN_TASK_STACK_SIZE": "24576",
    "CONFIG_FREERTOS_TASK_CREATE_ALLOW_EXT_MEM": "y",
    "CONFIG_AUDIO_DECODER_MP3_SUPPORT": "y",
}

def read_config(path: Path) -> dict[str, str]:
    settings: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("CONFIG_") and "=" in raw:
            key, value = raw.split("=", 1)
            settings[key] = value
    return settings

def main() -> int:
    failed = False
    for name in ("sdkconfig", "sdkconfig.defaults"):
        path = ROOT / name
        if not path.is_file():
            print(f"ERROR: missing {path}", file=sys.stderr)
            failed = True
            continue
        actual = read_config(path)
        for key, wanted in EXPECTED.items():
            got = actual.get(key)
            if got != wanted:
                print(f"FAIL {name}: {key} expected {wanted}, got {got!r}", file=sys.stderr)
                failed = True
        if actual.get("CONFIG_SPIRAM_USE_CAPS_ALLOC") == "y":
            print(f"FAIL {name}: CONFIG_SPIRAM_USE_CAPS_ALLOC must not be enabled", file=sys.stderr)
            failed = True
        if not failed:
            print(f"OK {name}: SIGNAL baseline matches")
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main())
