#!/usr/bin/env python3
"""Create the minimal Godot 4 PCK used by the offline bootstrap pack."""
from __future__ import annotations
import hashlib
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "overlay"
DEFAULT_OUT = ROOT / "dist" / "offline-bootstrap" / "port_compat.pck"

MAGIC = 0x43504447
FORMAT_VERSION = 3
GODOT_MAJOR = 4
GODOT_MINOR = 5
GODOT_PATCH = 1
PACK_REL_FILEBASE = 0x02
ALIGNMENT = 32
HEADER_SIZE = 4 + 4 + 4 + 4 + 4 + 4 + 8 + 8 + (16 * 4)


def align(offset: int, alignment: int = ALIGNMENT) -> int:
    return (offset + alignment - 1) & ~(alignment - 1)


def pad_string(path: str) -> tuple[int, bytes]:
    data = path.encode("utf-8")
    padded_len = len(data) + ((4 - len(data) % 4) % 4)
    return padded_len, data + b"\0" * (padded_len - len(data))


def collect_files() -> list[tuple[str, bytes, bytes]]:
    if not OVERLAY.is_dir():
        raise SystemExit(f"overlay dir missing: {OVERLAY}")
    entries: list[tuple[str, bytes, bytes]] = []
    for path in sorted(OVERLAY.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(OVERLAY).as_posix()
        if rel.startswith(".") or "/." in rel:
            continue
        data = path.read_bytes()
        entries.append(("res://" + rel, data, hashlib.md5(data).digest()))
    if not entries:
        raise SystemExit(f"no overlay files found under {OVERLAY}")
    return entries


def main(argv: list[str]) -> int:
    out = Path(argv[1]).resolve() if len(argv) > 1 else DEFAULT_OUT
    entries = collect_files()

    file_base = align(HEADER_SIZE)
    file_offsets: list[int] = []
    cursor = file_base
    for _res_path, data, _md5 in entries:
        file_offsets.append(cursor - file_base)
        cursor += len(data)
        cursor = align(cursor)
    dir_base = align(cursor)

    dir_section = bytearray()
    dir_section += struct.pack("<I", len(entries))
    for (res_path, data, md5), rel_offset in zip(entries, file_offsets):
        padded_len, padded_path = pad_string(res_path)
        dir_section += struct.pack("<I", padded_len)
        dir_section += padded_path
        dir_section += struct.pack("<Q", rel_offset)
        dir_section += struct.pack("<Q", len(data))
        dir_section += md5
        dir_section += struct.pack("<I", 0)

    header = bytearray()
    header += struct.pack("<I", MAGIC)
    header += struct.pack("<I", FORMAT_VERSION)
    header += struct.pack("<I", GODOT_MAJOR)
    header += struct.pack("<I", GODOT_MINOR)
    header += struct.pack("<I", GODOT_PATCH)
    header += struct.pack("<I", PACK_REL_FILEBASE)
    header += struct.pack("<Q", file_base)
    header += struct.pack("<Q", dir_base)
    header += b"\0" * (16 * 4)

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as fh:
        fh.write(header)
        fh.write(b"\0" * (file_base - HEADER_SIZE))
        cursor = file_base
        for (_res_path, data, _md5), rel_offset in zip(entries, file_offsets):
            absolute_offset = file_base + rel_offset
            if cursor < absolute_offset:
                fh.write(b"\0" * (absolute_offset - cursor))
                cursor = absolute_offset
            fh.write(data)
            cursor += len(data)
            padded = align(cursor)
            if padded > cursor:
                fh.write(b"\0" * (padded - cursor))
                cursor = padded
        if cursor < dir_base:
            fh.write(b"\0" * (dir_base - cursor))
        fh.write(dir_section)

    sha = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"Wrote {out} ({out.stat().st_size} bytes, {len(entries)} files, sha256={sha})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
