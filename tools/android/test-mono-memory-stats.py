#!/usr/bin/env python3
"""Run the Mono binary-repair safety regression against a local original library.

Usage: python3 tools/android/test-mono-memory-stats.py /path/to/original/libmonosgen-2.0.so
No runtime binary or commercial payload is stored in this test.
"""

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


spec = importlib.util.spec_from_file_location(
    "mono_memory_stats", Path(__file__).with_name("patch-mono-memory-stats.py")
)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class MonoMemoryStatsTests(unittest.TestCase):
    original: bytes

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "original.so"
        self.source.write_bytes(self.original)
        self.output = self.root / "fixed.so"

    def test_repair_changes_only_total_selector_and_restores_exact_original(self):
        repair.stage(self.source, self.output)
        fixed = self.output.read_bytes()
        # The sole allowed binary change is mov w0,#99 -> mov w0,#98.
        expected = bytearray(self.original)
        expected[0x1F2444:0x1F2448] = bytes.fromhex("400c8052")
        self.assertEqual(fixed, expected)
        self.assertNotEqual(fixed, self.original)
        restored = self.root / "restored.so"
        repair.stage(self.output, restored, restore=True)
        self.assertEqual(restored.read_bytes(), self.original)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_repair_and_restore_are_idempotent_on_verified_inputs(self):
        repair.stage(self.source, self.output)
        repeated = self.root / "repeated.so"
        repair.stage(self.output, repeated)
        self.assertEqual(repeated.read_bytes(), self.output.read_bytes())
        repair.stage(self.source, repeated, restore=True)
        self.assertEqual(repeated.read_bytes(), self.original)

    def test_unknown_binary_does_not_replace_previous_output(self):
        corrupted = bytearray(self.original)
        corrupted[-1] ^= 1  # Correct target instruction, different runtime build.
        self.source.write_bytes(corrupted)
        self.output.write_bytes(b"previous output")
        with self.assertRaisesRegex(ValueError, "Unsupported Mono SHA-256"):
            repair.stage(self.source, self.output)
        self.assertEqual(self.output.read_bytes(), b"previous output")
        self.assertEqual(self.source.read_bytes(), corrupted)

    def test_truncated_binary_does_not_publish_output(self):
        self.source.write_bytes(self.original[:0x1F2444])
        with self.assertRaisesRegex(ValueError, "Unsupported Mono SHA-256"):
            repair.stage(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_in_place_and_hardlinked_output_cannot_mutate_reference(self):
        with self.assertRaisesRegex(ValueError, "distinct files"):
            repair.stage(self.source, self.source)
        self.output.hardlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "distinct files"):
            repair.stage(self.source, self.output)
        self.assertEqual(self.source.read_bytes(), self.original)

    def test_symlink_cannot_redirect_input_or_output(self):
        self.output.symlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            repair.stage(self.source, self.output)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            repair.stage(self.output, self.root / "other.so")
        self.assertEqual(self.source.read_bytes(), self.original)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    MonoMemoryStatsTests.original = Path(sys.argv.pop()).read_bytes()
    # The positive regression must actually exercise the original -> fixed path.
    if repair.transform(MonoMemoryStatsTests.original, restore=True) != MonoMemoryStatsTests.original:
        raise SystemExit("Supply the ORIGINAL library, not the repaired copy")
    unittest.main(verbosity=2)
