#!/usr/bin/env python3
"""Exercise release bundling with signed synthetic ZIPs and no network."""

import base64
from email.message import Message
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import HTTPHandler, HTTPSHandler, ProxyHandler, build_opener
import zipfile

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


SCRIPT = Path(__file__).with_name("stage-signed-compat-release.py")
spec = importlib.util.spec_from_file_location("stage_signed_compat_release", SCRIPT)
stager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stager)
release_tools = stager.signing
TAG = "v0.8.0"
KEY_ID = "fixture-ed25519"
TARGET = "v0.114.0"
DLL = f"variants/{TARGET}/STS2Mobile.dll"
OVERLAY = f"variants/{TARGET}/port_compat.pck"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class InMemoryHTTPS(HTTPSHandler):
    def __init__(self, routes):
        self.routes = routes
        self.requests = []
        super().__init__()

    def https_open(self, request):
        url = request.full_url
        self.requests.append(url)
        status, payload = self.routes[url]
        response = io.BytesIO(payload if status == 200 else b"")
        response.status = response.code = status
        response.msg = "Fixture"
        response.headers = Message()
        if status in stager.REDIRECT_CODES:
            response.headers["Location"] = payload
        return response


class RejectHTTP(HTTPHandler):
    def http_open(self, request):
        raise AssertionError(f"Unexpected plaintext request to {request.full_url}")


def fake_opener(routes):
    transport = InMemoryHTTPS(routes)
    return build_opener(stager.DirectResponses(), transport, RejectHTTP(), ProxyHandler({})), transport.requests


class SignedCompatStagingTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="signed-compat-staging-test-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.cache = self.root / "cache"
        self.out = self.root / "assets"
        self.out.mkdir()
        self.staged = self.out / release_tools.ZIP_NAME
        self.staged.write_bytes(b"previous asset")
        self.trust = self.root / "CompatPackReleaseTrust.java"
        self.gradle = self.root / "gradle.properties"
        self.gradle.write_text("export_version_name=v0.1.13\nexport_version_code=115\n", encoding="utf-8")
        self.key = Ed25519PrivateKey.generate()
        public = base64.b64encode(release_tools.raw_public_key(self.key)).decode("ascii")
        self.trust.write_text(f'''public final class CompatPackReleaseTrust {{
    static Map<String, String> trustedPublicKeys() {{
        return Collections.singletonMap("{KEY_ID}", "{public}");
    }}
}}
''', encoding="utf-8")
        replacements = patch.multiple(stager, CACHE_ROOT=self.cache, TRUST_SOURCE=self.trust,
                                      GRADLE_PROPERTIES=self.gradle)
        replacements.start()
        self.addCleanup(replacements.stop)

    def fixture(self, *, tag=TAG, min_code=115, prerelease=False, bad_sums=False, manifest_mismatch=False):
        source = Path(tempfile.mkdtemp(dir=self.root))
        manifest = {
            "schema": 2,
            "pack_id": release_tools.PACK_ID,
            "display_name": "Fixture compatibility",
            "compat_version": tag.removeprefix("v"),
            "compat_revision": 800,
            "min_launcher_version_code": min_code,
            "channel": "mixed",
            "targets": [{"target_id": TARGET, "version": TARGET, "versions": [TARGET],
                         "artifacts": {"dll": DLL, "overlay_pck": OVERLAY}}],
        }
        metadata = release_tools.manifest_metadata(manifest)
        if manifest_mismatch:
            manifest["min_launcher_version_code"] = min_code + 1
        manifest_file = source / "manifest-fixture.json"
        release_tools.write_json(manifest_file, manifest)
        files = {"compat_manifest.json": manifest_file.read_bytes(), DLL: b"fixture DLL", OVERLAY: b"fixture PCK"}
        sums = "".join(f"{sha256(raw)}  {name}\n" for name, raw in files.items() if not bad_sums or name != DLL)
        zip_path = source / release_tools.ZIP_NAME
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, raw in files.items():
                archive.writestr(f"{release_tools.PACK_ID}/{name}", raw)
            archive.writestr(f"{release_tools.PACK_ID}/SHA256SUMS", sums)
        release_url, zip_url = release_tools.release_urls(tag)
        index = {
            "schema": 1,
            "repository": release_tools.REPOSITORY,
            "pack_id": release_tools.PACK_ID,
            "generated_utc": "2026-01-01T00:00:00Z",
            "latest_tag": tag,
            "releases": [{**metadata, "tag_name": tag, "release_url": release_url, "zip_download_url": zip_url,
                          "zip_asset_name": release_tools.ZIP_NAME, "zip_size_bytes": zip_path.stat().st_size,
                          "zip_sha256": sha256(zip_path.read_bytes()), "prerelease": prerelease,
                          "published_utc": "2026-01-01T00:00:00Z"}],
        }
        self.sign(source, index)
        return source, index

    def sign(self, source, index, *, key_id=KEY_ID, key=None):
        index_file = source / stager.INDEX_NAME
        release_tools.write_json(index_file, index)
        raw = index_file.read_bytes()
        release_tools.write_json(source / stager.SIGNATURE_NAME, {
            "schema": 1, "algorithm": "ed25519", "key_id": key_id, "payload_sha256": sha256(raw),
            "signature_base64": base64.b64encode((key or self.key).sign(release_tools.SIGNATURE_DOMAIN + raw)).decode(),
        })

    def reject(self, source, tag=TAG):
        before = self.staged.read_bytes()
        with patch.object(stager, "build_opener", side_effect=AssertionError("offline inputs must not access GitHub")):
            with self.assertRaises(ValueError):
                stager.stage_release(tag, source, self.out)
        self.assertEqual(self.staged.read_bytes(), before)

    def test_offline_shell_input_respects_explicit_environment(self):
        tag = "v0.8.1"
        source, index = self.fixture(tag=tag)
        project = self.root / "launcher"
        script_paths = ("tools/android/stage-bundled-compat-packs.sh", "tools/android/stage-signed-compat-release.py",
                        "tools/env/load-local-config.sh", "port-mod/tools/compat-release-signing.py")
        fixtures = {name: stager.ROOT / name for name in script_paths}
        fixtures.update({"android/gradle.properties": self.gradle,
                         "android/src/com/godot/game/CompatPackReleaseTrust.java": self.trust})
        for name, file in fixtures.items():
            dest = project / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, dest)
        release_tools.write_json(project / "tools/android/bundled-compat-release.json", {"tag": TAG})
        (project / "local.properties").write_text("compat.pack_build_mode=legacy\ncompat.bundled_release_tag=v0.7.0\n"
                                                  "compat.bundled_release_dir=properties-input\n", encoding="utf-8")
        (project / ".env").write_text("COMPAT_PACK_BUILD_MODE=matrix\nCOMPAT_BUNDLED_RELEASE_TAG=v0.6.0\n"
                                      "COMPAT_BUNDLED_RELEASE_DIR=dotenv-input\n", encoding="utf-8")
        env = dict(os.environ, STS2_RE_ROOT=str(project), STS2_ENV_FILE=str(project / ".env"),
                   STS2_LOCAL_CONFIG_FILE=str(project / "local.properties"), STS2_DOTENV_LOADED="0",
                   COMPAT_PACK_BUILD_MODE="release", COMPAT_BUNDLED_RELEASE_TAG=tag,
                   COMPAT_BUNDLED_RELEASE_DIR=str(source), COMPAT_PACK_ASSET_DIR=str(self.out))
        result = subprocess.run(["bash", str(project / script_paths[0])], cwd=project, env=env,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.staged.read_bytes(), (source / release_tools.ZIP_NAME).read_bytes())
        self.assertEqual(sha256(self.staged.read_bytes()), index["releases"][0]["zip_sha256"])

    def test_tampered_signature_is_rejected(self):
        source, _ = self.fixture()
        signature = source / stager.SIGNATURE_NAME
        envelope = release_tools.json_object(signature.read_bytes())
        raw = bytearray(base64.b64decode(envelope["signature_base64"]))
        raw[0] ^= 1
        envelope["signature_base64"] = base64.b64encode(raw).decode("ascii")
        release_tools.write_json(signature, envelope)
        self.reject(source)

    def test_unknown_key_id_and_dry_run_key_are_rejected(self):
        source, index = self.fixture()
        self.sign(source, index, key_id="untrusted")
        self.reject(source)
        self.sign(source, index, key=Ed25519PrivateKey.generate())
        self.reject(source)

    def test_empty_java_trust_table_is_rejected(self):
        source, _ = self.fixture()
        self.trust.write_text("static Map<String, String> trustedPublicKeys() { return Collections.emptyMap(); }",
                              encoding="utf-8")
        self.reject(source)

    def test_missing_pinned_tag_and_incompatible_launcher_are_rejected(self):
        source, _ = self.fixture()
        self.reject(source, "v0.8.5")
        source, index = self.fixture(min_code=116)
        self.reject(source)
        self.gradle.write_text("export_version_code=116\n", encoding="utf-8")
        stager.stage_release(TAG, source, self.out)
        self.assertEqual(sha256(self.staged.read_bytes()), index["releases"][0]["zip_sha256"])

    def test_tampered_zip_is_rejected_even_at_the_signed_length(self):
        source, _ = self.fixture()
        zip_path = source / release_tools.ZIP_NAME
        raw = bytearray(zip_path.read_bytes())
        raw[-1] ^= 1
        zip_path.write_bytes(raw)
        self.reject(source)

    def test_signed_zip_requires_inner_checksums_and_matching_manifest(self):
        for options in ({"bad_sums": True}, {"manifest_mismatch": True}):
            with self.subTest(options=options):
                source, _ = self.fixture(**options)
                self.reject(source)

    def test_signed_foreign_asset_urls_and_unsupported_schema_are_rejected(self):
        source, index = self.fixture()
        entry = index["releases"][0]
        for url in ("https://example.org/package.zip", entry["zip_download_url"].replace("https:", "http:", 1)):
            with self.subTest(url=url):
                self.sign(source, {**index, "releases": [{**entry, "zip_download_url": url}]})
                self.reject(source)
        self.sign(source, {**index, "releases": [{**entry, "manifest_schema": 3}]})
        self.reject(source)

    def test_pinned_tag_can_select_a_verified_history_entry(self):
        source, index = self.fixture()
        _, newer = self.fixture(tag="v0.8.1")
        newer["releases"].extend(index["releases"])
        self.sign(source, newer)
        stager.stage_release(TAG, source, self.out)
        self.assertEqual(sha256(self.staged.read_bytes()), index["releases"][0]["zip_sha256"])

    def test_cached_bundle_is_reverified_and_invalid_explicit_input_never_falls_back(self):
        source, index = self.fixture()
        self.cache.mkdir()
        cached = shutil.copytree(source, self.cache / TAG)
        with patch.object(stager, "build_opener", side_effect=AssertionError("cache hit must not open GitHub")):
            stager.stage_release(TAG, None, self.out)
        digest = index["releases"][0]["zip_sha256"]
        self.assertEqual(sha256(self.staged.read_bytes()), digest)
        (source / stager.SIGNATURE_NAME).write_bytes(b"{ invalid signature }")
        self.reject(source)
        (cached / release_tools.ZIP_NAME).write_bytes(b"corrupt cache")
        self.reject(None)
        self.assertEqual(sha256(self.staged.read_bytes()), digest)

    def test_latest_resolves_signed_stable_tag_and_populates_tag_cache(self):
        source, index = self.fixture()
        routes = {stager.release_asset_url("latest", name): (200, (source / name).read_bytes())
                  for name in stager.ASSETS[:2]}
        cdn = "https://release-assets.githubusercontent.com/package?signature=github%2Basset"
        zip_url = stager.release_asset_url(TAG, release_tools.ZIP_NAME)
        routes[zip_url] = (302, cdn)
        routes[cdn] = (200, (source / release_tools.ZIP_NAME).read_bytes())
        opener, requested = fake_opener(routes)
        with patch.object(stager, "build_opener", return_value=opener):
            stager.stage_release("latest", None, self.out)
        self.assertEqual(requested[-2:], [zip_url, cdn])
        self.assertEqual(sha256(self.staged.read_bytes()), index["releases"][0]["zip_sha256"])
        with patch.object(stager, "build_opener", side_effect=AssertionError("verified tag must be cached")):
            stager.stage_release(TAG, None, self.out)

    def test_latest_rejects_prerelease(self):
        source, _ = self.fixture(tag="v0.8.1-rc.1", prerelease=True)
        self.reject(source, "latest")

    def test_redirect_targets_and_hop_limit_are_enforced_before_connecting(self):
        url = stager.release_asset_url(TAG, stager.INDEX_NAME)
        for location in ("http://github.com/asset", "https://github.com.example.org/asset", "https://example.org/asset",
                         "https://github.com@objects.githubusercontent.com/asset", "https://github.com:444/asset",
                         "https://github.com/asset#fragment", "https://github.com\\@example.org/asset", "/relative"):
            with self.subTest(location=location):
                opener, requested = fake_opener({url: (302, location)})
                with self.assertRaises(ValueError):
                    stager.download_asset(opener, TAG, self.out / stager.INDEX_NAME, 100)
                self.assertEqual(requested, [url])
        extra = [f"https://objects.githubusercontent.com/hop{i}" for i in range(6)]
        opener, requested = fake_opener({left: (307, right) for left, right in zip([url, *extra], extra)})
        with self.assertRaises(ValueError):
            stager.download_asset(opener, TAG, self.out / stager.INDEX_NAME, 100)
        self.assertEqual(requested, [url, *extra[:5]])
        self.assertFalse((self.out / stager.INDEX_NAME).exists())


if __name__ == "__main__":
    unittest.main()
