#!/usr/bin/env python3
"""Audit every packaged 64-bit Android ELF LOAD segment for 16 KiB pages.

Usage: python scripts/diagnostics/check_android_16k_elf.py app-debug.apk
Run zipalign -c -P 16 -v 4 separately to check ZIP (rather than ELF) alignment.
"""
import re
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16 * 1024
SO_PATH = re.compile(r"^lib/(arm64-v8a|x86_64)/[^/]+\.so$")


def check_so(name: str, data: bytes) -> list[str]:
    failures = []
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return [f"{name}: not a valid ELF file"]
    if data[4] != 2 or data[5] not in (1, 2):
        return [f"{name}: unsupported ELF class or endianness"]
    endian = "<" if data[5] == 1 else ">"
    ph_offset = struct.unpack_from(endian + "Q", data, 32)[0]
    ph_entry_size, ph_count = struct.unpack_from(endian + "HH", data, 54)
    if not (ph_count and ph_entry_size >= 56) or ph_offset + ph_count * ph_entry_size > len(data):
        return [f"{name}: invalid or missing program-header table"]
    loads = 0
    for i in range(ph_count):
        p_type, _flags, p_offset, p_vaddr, _paddr, _filesz, _memsz, align = (
            struct.unpack_from(endian + "IIQQQQQQ", data, ph_offset + i * ph_entry_size)
        )
        if p_type != 1:  # PT_LOAD
            continue
        loads += 1
        if align < PAGE_SIZE or (p_vaddr - p_offset) % PAGE_SIZE:
            failures.append(
                f"{name}: LOAD #{loads} p_align=0x{align:x}, "
                f"vaddr=0x{p_vaddr:x}, offset=0x{p_offset:x} (requires 0x4000)"
            )
    if not loads:
        failures.append(f"{name}: no LOAD program headers")
    return failures


def check_apk(apk: Path) -> int:
    try:
        with zipfile.ZipFile(apk) as archive:
            files = sorted(name for name in archive.namelist() if SO_PATH.fullmatch(name))
            if not files or not any(name.startswith("lib/arm64-v8a/") for name in files):
                print("FAIL: APK has no arm64-v8a native shared libraries")
                return 1
            errors = []
            for name in files:
                failures = check_so(name, archive.read(name))
                errors.extend(failures)
                print(("FAIL" if failures else "PASS") + ": " + name)
            for err in errors:
                print("  " + err)
            print(f"Checked {len(files)} 64-bit ELF libraries; "
                  f"{len(errors)} invalid LOAD segments")
            return 1 if errors else 0
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"FAIL: Cannot read APK {apk}: {exc}")
        return 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: check_android_16k_elf.py <path-to.apk>")
        sys.exit(2)
    sys.exit(check_apk(Path(sys.argv[1])))
