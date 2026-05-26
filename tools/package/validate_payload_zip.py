#!/usr/bin/env python3
"""Validate that a local Slay the Spire 2 zip contains the minimum payload."""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

REQUIRED = {
    "SlayTheSpire2.pck",
    "release_info.json",
    "data_sts2_windows_x86_64/sts2.dll",
    "data_sts2_windows_x86_64/sts2.deps.json",
    "data_sts2_windows_x86_64/sts2.runtimeconfig.json",
}


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} /path/to/SlayTheSpire2.zip", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"Missing zip: {path}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(path) as zf:
        names = {info.filename.replace("\\", "/") for info in zf.infolist() if not info.is_dir()}
        missing = sorted(REQUIRED - names)
        if missing:
            print("Missing required entries:", file=sys.stderr)
            for item in missing:
                print(f"  - {item}", file=sys.stderr)
            return 1
        with zf.open("SlayTheSpire2.pck") as fp:
            magic = fp.read(4)
            if magic != b"GDPC":
                print(f"Invalid PCK magic: {magic!r}", file=sys.stderr)
                return 1
        release_info = json.loads(zf.read("release_info.json").decode("utf-8"))
        sts2_dll_bytes = zf.read("data_sts2_windows_x86_64/sts2.dll")
        sts2_dll_sha256 = hashlib.sha256(sts2_dll_bytes).hexdigest()
        sts2_dll_size = len(sts2_dll_bytes)
    sha256 = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            sha256.update(chunk)
    print(json.dumps({
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": sha256.hexdigest(),
        "release_info": release_info,
        "identity": {
            "version": release_info.get("version", ""),
            "commit": release_info.get("commit", ""),
            "branch": release_info.get("branch", ""),
            "main_assembly_hash": release_info.get("main_assembly_hash"),
            "sts2_dll_sha256": sts2_dll_sha256,
            "sts2_dll_size": sts2_dll_size,
        },
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
