#!/usr/bin/env python3
"""Create a tiny Godot PCK overlay for Android port compatibility resources.

This intentionally avoids bundling game payload files. It packs only the files
under port-mod/overlay as res:// paths so Harmony patches can load replacement
shaders/resources at runtime.
"""
from __future__ import annotations
import hashlib
import os
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / "port-mod" / "overlay"
DEFAULT_OUT = ROOT / "android" / "assets" / "port_compat.pck"


def align16(value: int) -> int:
    return (value + 15) & ~15


def collect_files() -> list[tuple[str, Path, int, bytes]]:
    if not OVERLAY.is_dir():
        raise SystemExit(f"overlay dir missing: {OVERLAY}")
    entries: list[tuple[str, Path, int, bytes]] = []
    for path in sorted(OVERLAY.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(OVERLAY).as_posix()
        if rel.startswith(".") or "/." in rel:
            continue
        data = path.read_bytes()
        entries.append(("res://" + rel, path, len(data), hashlib.md5(data).digest()))
    if not entries:
        raise SystemExit(f"no overlay files found under {OVERLAY}")
    return entries


def index_size(entries: list[tuple[str, Path, int, bytes]]) -> int:
    size = 4
    for res_path, _path, _length, _md5 in entries:
        encoded = res_path.encode("utf-8")
        size += 4 + len(encoded) + 8 + 8 + 16
    return size


def main(argv: list[str]) -> int:
    out = Path(argv[1]).resolve() if len(argv) > 1 else DEFAULT_OUT
    entries = collect_files()
    header_size = 4 + 4 + 4 * 4 + 16 * 4
    idx_size = index_size(entries)
    data_start = align16(header_size + idx_size)

    offsets: list[int] = []
    cursor = data_start
    for _res_path, _path, length, _md5 in entries:
        offsets.append(cursor)
        cursor = align16(cursor + length)

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as fh:
        fh.write(b"GDPC")
        fh.write(struct.pack("<I", 2))
        fh.write(struct.pack("<IIII", 4, 4, 1, 0))
        fh.write(b"\0" * (16 * 4))
        fh.write(struct.pack("<I", len(entries)))
        for (res_path, _path, length, md5), offset in zip(entries, offsets):
            encoded = res_path.encode("utf-8")
            fh.write(struct.pack("<I", len(encoded)))
            fh.write(encoded)
            fh.write(struct.pack("<Q", offset))
            fh.write(struct.pack("<Q", length))
            fh.write(md5)
        if fh.tell() > data_start:
            raise RuntimeError("index grew past computed data start")
        fh.write(b"\0" * (data_start - fh.tell()))
        for (_res_path, path, length, _md5), offset in zip(entries, offsets):
            if fh.tell() != offset:
                fh.write(b"\0" * (offset - fh.tell()))
            fh.write(path.read_bytes())
            padding = align16(length) - length
            if padding:
                fh.write(b"\0" * padding)

    sha = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"Wrote {out} ({out.stat().st_size} bytes, {len(entries)} files, sha256={sha})")
    for res_path, _path, length, _md5 in entries:
        print(f"  {res_path} ({length} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
