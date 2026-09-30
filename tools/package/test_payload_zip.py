#!/usr/bin/env python3
"""Consumer regressions for payload ZIP entry identity; fixtures contain no game data."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import build_android_body_zip as body


class PayloadZipEntryIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="sts2-zip-identity-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.dll = b"synthetic-managed-payload"
        self.release = {"version": "fixture-1", "commit": "fixture", "branch": "fixture"}
        self.files = {
            "SlayTheSpire2.pck": b"GDPCsynthetic-pck",
            "release_info.json": json.dumps(self.release).encode(),
            "data_sts2_windows_x86_64/sts2.dll": self.dll,
            "data_sts2_windows_x86_64/sts2.deps.json": json.dumps({
                "targets": {"fixture": {"FixtureAddon/1.0": {
                    "runtime": {"FixtureAddon.dll": {}}
                }}}
            }).encode(),
            "data_sts2_windows_x86_64/sts2.runtimeconfig.json": b"{}",
            "data_sts2_windows_x86_64/FixtureAddon.dll": b"synthetic-addon",
        }

    def archive(self, name: str, separator: str = "/", prefix: str = "") -> Path:
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            for relative, content in self.files.items():
                archive.writestr((prefix + relative).replace("/", separator), content)
        return path

    def validate_cli(self, path: Path) -> dict:
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("validate_payload_zip.py")), str(path)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_backslash_cli_preserves_the_same_payload_identity(self) -> None:
        canonical = self.validate_cli(self.archive("canonical.zip"))
        alternate = self.validate_cli(self.archive("backslash.zip", "\\"))
        self.assertEqual(canonical["identity"], alternate["identity"])
        self.assertEqual(hashlib.sha256(self.dll).hexdigest(), alternate["identity"]["sts2_dll_sha256"])
        self.assertEqual(self.release, alternate["release_info"])

    def test_wrapped_backslash_dependencies_survive_repack(self) -> None:
        source = self.archive("wrapped.zip", "\\", "bundle/")
        checked = body.validate_pc_zip(source)
        self.assertEqual("bundle/", checked["entry_prefix"])
        self.assertEqual(hashlib.sha256(self.dll).hexdigest(), checked["sts2_dll_sha256"])
        keep = body.load_keep_data_basenames(source)
        self.assertIn("FixtureAddon.dll", keep)
        pck = self.root / "android.pck"
        pck.write_bytes(b"GDPCsynthetic-android-pck")
        output = self.root / "android.zip"
        body.package_zip(source, pck, output, {"fixture": True}, keep)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(b"synthetic-addon", archive.read("data_sts2_windows_x86_64/FixtureAddon.dll"))
            self.assertEqual(self.dll, archive.read("data_sts2_windows_x86_64/sts2.dll"))
            self.assertEqual(pck.read_bytes(), archive.read("SlayTheSpire2.pck"))
        exported = body.validate_output_zip(output)
        self.assertEqual("", exported["entry_prefix"])
        self.assertEqual(checked["sts2_dll_sha256"], exported["sts2_dll_sha256"])

    def test_ambiguous_names_cannot_choose_different_payloads(self) -> None:
        source = self.archive("ambiguous.zip")
        with zipfile.ZipFile(source, "a") as archive:
            archive.writestr("data_sts2_windows_x86_64\\sts2.dll", b"different-payload")
        with self.assertRaises(ValueError):
            body.validate_pc_zip(source)


if __name__ == "__main__":
    unittest.main()
