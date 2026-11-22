"""Regression tests for the packaged Android ELF 16-KiB page alignment gate."""
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_android_16k_elf import check_so, check_apk


def elf64(load_align: int, load_offset: int = 0) -> bytes:
    # Minimal synthetic ELF64, enough to exercise PT_LOAD header parsing.
    raw = bytearray(128)
    raw[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", raw, 32, 64)       # e_phoff
    struct.pack_into("<HH", raw, 54, 56, 1)  # e_phentsize / e_phnum
    struct.pack_into("<IIQQQQQQ", raw, 64,
                     1, 5, load_offset, 0, 0, 0, 0, load_align)
    return bytes(raw)


class Elf16kTests(unittest.TestCase):
    def test_16k_load_passes(self):
        self.assertEqual([], check_so("lib/arm64-v8a/libok.so", elf64(0x4000)))

    def test_4k_load_fails(self):
        failures = check_so("lib/arm64-v8a/libold.so", elf64(0x1000))
        self.assertEqual(1, len(failures))
        self.assertIn("p_align=0x1000", failures[0])

    def test_16k_alignment_cannot_hide_misaligned_address(self):
        failures = check_so("lib/arm64-v8a/libodd.so", elf64(0x4000, 0x1000))
        self.assertEqual(1, len(failures))

    def test_corrupt_elf_fails(self):
        self.assertTrue(check_so("lib/arm64-v8a/libbad.so", b"not an ELF"))

    def test_apk_checks_every_arm64_library(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("lib/arm64-v8a/libok.so", elf64(0x4000))
                z.writestr("lib/arm64-v8a/libold.so", elf64(0x1000))
            self.assertEqual(1, check_apk(apk))

    def test_apk_all_16k_libraries_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("lib/arm64-v8a/libok.so", elf64(0x4000))
            self.assertEqual(0, check_apk(apk))


if __name__ == "__main__":
    unittest.main()
