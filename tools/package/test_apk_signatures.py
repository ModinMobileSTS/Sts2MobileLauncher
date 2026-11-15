#!/usr/bin/env python3
"""Real Android signing regressions; fixtures contain no application/game code."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


class ApkSignaturesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory(prefix="sts2-apk-signatures-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        sdk = Path(os.environ["ANDROID_HOME"])
        cls.jdk = Path(os.environ["JAVA_HOME"]) / "bin"
        cls.tools = sdk / "build-tools/35.0.0"
        cls.jar = cls.tools / "lib/apksigner.jar"
        cls.command(cls.jdk / "javac", "-cp", cls.jar, "-d", cls.root,
                    Path(__file__).with_name("VerifyApkSignatures.java"))
        for name in ("original", "different"):
            cls.command(cls.jdk / "keytool", "-genkeypair", "-noprompt", "-keystore",
                        cls.root / f"{name}.p12", "-storetype", "PKCS12", "-storepass",
                        "fixture-password", "-keypass", "fixture-password", "-alias", "fixture",
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                        "-dname", f"CN=APK Signature Fixture {name}")
        manifest = cls.root / "AndroidManifest.xml"
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.signingfixture" android:versionCode="1">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />
    <application android:hasCode="false" android:label="Signing fixture" />
</manifest>''', encoding="utf-8")
        cls.unsigned = cls.root / "unsigned.apk"
        cls.command(cls.tools / "aapt2", "link", "--manifest", manifest,
                    "-I", sdk / "platforms/android-35/android.jar", "-o", cls.unsigned)
        with zipfile.ZipFile(cls.unsigned, "a") as archive:
            archive.writestr("assets/fixture.txt", b"signing-fixture-content")
        cls.current = cls.sign("current")
        cls.previous = cls.sign("previous", legacy=True)
        cls.different = cls.sign("different", key="different", legacy=True)

    @staticmethod
    def command(*args: object) -> subprocess.CompletedProcess:
        return subprocess.run([str(arg) for arg in args], capture_output=True, text=True, check=True)

    @classmethod
    def sign(cls, name: str, key: str = "original", legacy: bool = False) -> Path:
        apk = cls.root / f"{name}.apk"
        cls.command(cls.tools / "apksigner", "sign", "--ks", cls.root / f"{key}.p12",
                    "--ks-pass", "pass:fixture-password", "--ks-key-alias", "fixture",
                    "--v1-signing-enabled", str(not legacy).lower(), "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", str(not legacy).lower(), "--v4-signing-enabled", "false",
                    "--out", apk, cls.unsigned)
        return apk

    def verify(self, apk: Path, previous: Path | None = None) -> subprocess.CompletedProcess:
        args = [str(self.jdk / "java"), "-cp", f"{self.jar}{os.pathsep}{self.root}",
                "VerifyApkSignatures", str(apk)]
        if previous is not None:
            args.append(str(previous))
        return subprocess.run(args, capture_output=True, text=True, check=False)

    def copy_current(self) -> Path:
        apk = self.root / f"{self._testMethodName}.apk"
        shutil.copyfile(self.current, apk)
        return apk

    def test_three_schemes_can_update_v2_only_release(self) -> None:
        result = self.verify(self.current, self.previous)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_changed_certificate_refuses_update(self) -> None:
        result = self.verify(self.current, self.different)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_v2_only_refuses_new_release(self) -> None:
        result = self.verify(self.previous)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_modified_apk_refuses_release(self) -> None:
        apk = self.copy_current()
        data = bytearray(apk.read_bytes())
        offset = data.index(b"signing-fixture-content")
        data[offset] ^= 1
        apk.write_bytes(data)
        result = self.verify(apk)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
