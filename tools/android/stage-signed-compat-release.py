#!/usr/bin/env python3
"""Verify an official compatibility release and stage the original ZIP for the APK."""

import argparse
import base64
import errno
from http.client import HTTPException
import importlib.util
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPErrorProcessor, Request, build_opener
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SIGNING_PATH = ROOT / "port-mod/tools/compat-release-signing.py"
try:
    spec = importlib.util.spec_from_file_location("compat_release_signing", SIGNING_PATH)
    signing = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(signing)
except FileNotFoundError as exc:
    sys.exit(f"{Path(__file__).name}: initialize port-mod with the shared release verifier ({exc})")
except ImportError as exc:
    sys.exit(f"{Path(__file__).name}: release mode requires Python cryptography ({exc})")

INDEX_NAME = "compat-index.json"
SIGNATURE_NAME = INDEX_NAME + ".sig"
ASSETS = (INDEX_NAME, SIGNATURE_NAME, signing.ZIP_NAME)
CACHE_ROOT = ROOT / ".agent/cache/compat-releases"
TRUST_SOURCE = ROOT / "android/src/com/godot/game/CompatPackReleaseTrust.java"
GRADLE_PROPERTIES = ROOT / "android/gradle.properties"
DEFAULT_RELEASE = Path(__file__).with_name("bundled-compat-release.json")
OFFICIAL_HOSTS = {"github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"}
REDIRECT_CODES = {301, 302, 303, 307, 308}
MAX_REDIRECTS = 5


class DirectResponses(HTTPErrorProcessor):
    # The default processor dispatches the automatic redirect handler. Inspect
    # the raw status and Location ourselves before a new connection is opened.
    def http_response(self, request, response):
        return response

    https_response = http_response


def trusted_public_keys(path):
    # Match the complete literal return value, not arbitrary id/key pairs in
    # the source. Preserve string literals while removing Java comments.
    source = re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/',
                    lambda match: match[0] if match[0].startswith('"') else " ",
                    path.read_text(encoding="utf-8"))
    methods = re.findall(r"\bstatic\s+Map\s*<\s*String\s*,\s*String\s*>\s+trustedPublicKeys\s*"
                         r"\(\s*\)\s*\{([^{}]*)\}", source)
    signing.require(len(methods) == 1, "Expected a literal trustedPublicKeys() in CompatPackReleaseTrust.java")
    entry = re.fullmatch(r'\s*return\s+Collections\.singletonMap\s*\(\s*"([A-Za-z0-9_.-]+)"\s*,\s*'
                         r'"([A-Za-z0-9+/=]+)"\s*\)\s*;\s*', methods[0])
    signing.require(entry, "Empty or unsupported public key table in CompatPackReleaseTrust.java")
    key_id, public_key = entry.groups()
    signing.require(signing.IDENTIFIER.fullmatch(key_id) and len(base64.b64decode(public_key, validate=True)) == 32,
                    "Invalid trusted Ed25519 key in CompatPackReleaseTrust.java")
    return {key_id: public_key}


def launcher_version_code(path):
    values = re.findall(r"^[ \t]*export_version_code(?:[ \t]*[=:][ \t]*|[ \t]+)([^\n]*)$",
                        path.read_text(encoding="utf-8"), flags=re.MULTILINE)
    signing.require(len(values) == 1 and re.fullmatch(r"[1-9][0-9]{0,9}", values[0].strip()),
                    "android/gradle.properties must contain one positive export_version_code")
    return signing.positive_int(int(values[0]), "export_version_code")


def release_asset_url(tag, name):
    signing.require(name in ASSETS, "Unexpected compatibility release asset name")
    if tag == "latest":
        return f"{signing.RELEASE_BASE}/latest/download/{name}"
    signing.require(signing.TAG.fullmatch(tag), f"Invalid release tag: {tag!r}")
    return f"{signing.RELEASE_BASE}/download/{tag}/{name}"


def validate_https_url(url):
    # Reject characters that URL parsers may normalize away (including empty
    # credentials/fragments, stripped whitespace and percent-encoded authority).
    signing.require(re.fullmatch(r"[A-Za-z0-9:/?@\[\]!$&'()*+,;=%._~-]+", url)
                    and not re.search(r"%(?![A-Fa-f0-9]{2})", url), "Invalid GitHub HTTPS URL")
    parsed = urlsplit(url)
    host = parsed.hostname
    signing.require(parsed.scheme == "https" and host in OFFICIAL_HOSTS
                    and parsed.netloc.lower() in (host, f"{host}:443"),
                    "Only official GitHub HTTPS URLs on port 443 are allowed")


def copy_bounded(source, destination, limit):
    size = 0
    with destination.open("xb") as output:
        while chunk := source.read(min(1024 * 1024, limit + 1 - size)):
            size += len(chunk)
            signing.require(size <= limit, f"{destination.name} exceeds its allowed size ({limit} bytes)")
            output.write(chunk)
    signing.require(size > 0, f"Empty release asset: {destination.name}")


def download_asset(opener, tag, destination, limit):
    url = release_asset_url(tag, destination.name)
    for hop in range(MAX_REDIRECTS + 1):
        validate_https_url(url)
        with opener.open(Request(url, headers={"User-Agent": "STS2-Android-Compat-Bundler"}), timeout=45) as response:
            if response.status == 200:
                copy_bounded(response, destination, limit)
                return
            signing.require(response.status in REDIRECT_CODES,
                            f"GitHub returned HTTP {response.status} for {destination.name}")
            signing.require(hop < MAX_REDIRECTS, "GitHub redirect limit exceeded")
            locations = response.headers.get_all("Location", [])
            signing.require(len(locations) == 1, "Expected one GitHub redirect Location")
            url = locations[0]


def verify_release_index(work, keys, requested_tag, version_code):
    index_file = work / INDEX_NAME
    signature_file = work / SIGNATURE_NAME
    envelope = signing.json_object(signature_file.read_bytes())
    key_id = signing.string_field(envelope, "key_id")
    signing.require(key_id in keys, f"Unknown signature key_id: {key_id!r}")
    index = signing.verify_index(index_file, signature_file, keys[key_id], key_id)
    tag = index["latest_tag"] if requested_tag == "latest" else requested_tag
    release = next((entry for entry in index["releases"] if entry["tag_name"] == tag), None)
    signing.require(release is not None, f"Release tag {tag} is not in the verified index")
    signing.require(requested_tag != "latest" or not release["prerelease"], "latest must be a stable release")
    signing.require(release["manifest_schema"] <= 2, f"Release {tag} requires a newer manifest schema")
    required = release["min_launcher_version_code"]
    signing.require(required <= version_code,
                    f"Release {tag} requires min_launcher_version_code {required}; export_version_code is {version_code}")
    return release


def cache_verified_release(work, cache):
    if os.path.lexists(cache):
        return  # A pinned cache is not overwritten by a latest lookup.
    cache.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".download-", dir=cache.parent) as candidate:
        for name in ASSETS:
            shutil.copyfile(work / name, Path(candidate) / name)
        try:
            Path(candidate).rename(cache)
        except OSError as exc:
            # Another build may have published this tag while we downloaded it.
            if exc.errno not in (errno.EEXIST, errno.ENOTEMPTY):
                raise


def stage_release(tag, release_dir, asset_dir):
    signing.require(tag == "latest" or signing.TAG.fullmatch(tag), f"Invalid release tag: {tag!r}")
    keys = trusted_public_keys(TRUST_SOURCE)
    version_code = launcher_version_code(GRADLE_PROPERTIES)
    # Explicit offline input is exclusive. Pinned cache hits are re-verified;
    # latest always resolves GitHub's current stable index when online.
    cache = CACHE_ROOT / tag
    if release_dir is None and tag != "latest" and os.path.lexists(cache):
        release_dir = cache
    origin = str(release_dir) if release_dir is not None else f"official GitHub Release ({tag})"
    if release_dir is not None:
        signing.require(release_dir.is_dir(), f"Not a release asset directory: {release_dir}")
    print(f"Verifying compatibility release from {origin}", flush=True)
    opener = build_opener(DirectResponses()) if release_dir is None else None

    asset_dir.mkdir(parents=True, exist_ok=True)
    # Verify private snapshots; never hash an input and then re-open that input
    # for the final copy. Rename the verified ZIP on the same filesystem.
    with tempfile.TemporaryDirectory(prefix=".compat-release-", dir=asset_dir) as temp:
        work = Path(temp)

        def acquire(name, limit, source_tag):
            destination = work / name
            if release_dir is None:
                download_asset(opener, source_tag, destination, limit)
            else:
                source_path = release_dir / name
                signing.require(source_path.is_file(), f"Missing release asset: {source_path}")
                with source_path.open("rb") as source:
                    copy_bounded(source, destination, limit)

        acquire(INDEX_NAME, signing.MAX_INDEX_BYTES, tag)
        acquire(SIGNATURE_NAME, signing.MAX_SIGNATURE_BYTES, tag)
        release = verify_release_index(work, keys, tag, version_code)
        selected_tag = release["tag_name"]
        size = release["zip_size_bytes"]
        digest = release["zip_sha256"].lower()
        acquire(signing.ZIP_NAME, size, selected_tag)
        zip_path = work / signing.ZIP_NAME
        with zip_path.open("rb") as stream:
            signing.require(os.fstat(stream.fileno()).st_size == size, "ZIP size does not match signed index")
            signing.require(signing.stream_sha256(stream) == digest, "ZIP SHA-256 does not match signed index")
            with zipfile.ZipFile(stream) as archive:
                signing.verify_archive(archive, release, None)
        if release_dir is None:
            cache_verified_release(work, CACHE_ROOT / selected_tag)
        destination = asset_dir / signing.ZIP_NAME
        zip_path.replace(destination)
    print(f"Staged signed {selected_tag}: {digest}  {destination}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="signed release tag, or latest (default: repository pin)")
    parser.add_argument("--release-dir", type=Path, help="verify local release assets without accessing the network")
    parser.add_argument("--asset-dir", type=Path, required=True, help="APK compatibility asset output directory")
    args = parser.parse_args()
    try:
        tag = args.tag if args.tag is not None else signing.string_field(signing.json_object(DEFAULT_RELEASE.read_bytes()), "tag")
        stage_release(tag, args.release_dir, args.asset_dir)
    except (ValueError, OSError, RuntimeError, TypeError, HTTPException, zipfile.BadZipFile) as exc:
        parser.exit(1, f"{parser.prog}: {exc}\n")


if __name__ == "__main__":
    main()
