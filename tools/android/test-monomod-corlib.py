#!/usr/bin/env python3
"""Run with original MonoMod.Utils.dll and paired libmonosgen-2.0.so paths."""

import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "repair", Path(__file__).with_name("patch-monomod-corlib.py")
)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class MonoModCorlibTests(unittest.TestCase):
    original: bytes
    mono_path: Path

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "reference.dll"
        self.destination = self.root / "staged.dll"
        self.source.write_bytes(self.original)

    def stage(self, source=None, destination=None, libraries=None):
        return repair.stage(source or self.source, destination or self.destination,
                            [self.mono_path] if libraries is None else libraries)

    def test_only_helper_body_changes_and_repair_is_idempotent(self):
        fixed = repair.transform(self.original)
        self.assertEqual(hashlib.sha256(fixed).hexdigest(), repair.FIXED_SHA256)
        self.assertEqual(len(fixed), len(self.original))
        self.assertEqual(fixed[:repair.METHOD_OFFSET], self.original[:repair.METHOD_OFFSET])
        end = repair.METHOD_OFFSET + repair.METHOD_SPAN
        self.assertEqual(fixed[end:], self.original[end:])
        self.assertEqual(repair.transform(fixed), fixed)

    def test_stage_preserves_reference_and_publishes_verified_copy(self):
        self.stage()
        self.assertEqual(self.source.read_bytes(), self.original)
        self.assertEqual(self.destination.read_bytes(), repair.transform(self.original))
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["reference.dll", "staged.dll"])

    def test_unknown_managed_or_native_input_leaves_existing_output_intact(self):
        self.destination.write_bytes(b"previous-good-runtime")
        self.source.write_bytes(self.original + b"unknown")
        with self.assertRaisesRegex(ValueError, "Unsupported MonoMod"):
            self.stage()
        self.source.write_bytes(self.original)
        unknown_mono = self.root / "unknown.so"
        unknown_mono.write_bytes(b"different native access checks")
        with self.assertRaisesRegex(ValueError, "Unsupported paired Mono"):
            self.stage(libraries=[self.mono_path, unknown_mono])
        self.assertEqual(self.destination.read_bytes(), b"previous-good-runtime")
        with self.assertRaisesRegex(ValueError, "required"):
            self.stage(libraries=[])

    def test_reference_aliases_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "distinct files"):
            self.stage(destination=self.source)
        self.destination.hardlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "distinct files"):
            self.stage()
        self.destination.unlink()
        self.destination.symlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            self.stage()
        self.destination.unlink()
        alias = self.root / "input-link.dll"
        alias.symlink_to(self.source)
        with self.assertRaisesRegex(ValueError, "symbolic links"):
            self.stage(source=alias)
        self.assertEqual(self.source.read_bytes(), self.original)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    MonoModCorlibTests.mono_path = Path(sys.argv.pop()).resolve()
    MonoModCorlibTests.original = Path(sys.argv.pop()).read_bytes()
    if hashlib.sha256(MonoModCorlibTests.original).hexdigest() != repair.ORIGINAL_SHA256:
        raise SystemExit("Supply the ORIGINAL managed library for the regression")
    unittest.main(verbosity=2)
