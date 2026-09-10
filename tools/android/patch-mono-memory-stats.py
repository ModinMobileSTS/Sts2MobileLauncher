#!/usr/bin/env python3
"""Opt-in, reversible memory-total repair for one verified Android ARM64 Mono.

This is NOT a Mono rebuild or a general binary patcher. The original Ekyso
9.0.7.0 library remains the reference input. Only the final sysconf selector in
mono_determine_physical_ram_size changes from _SC_AVPHYS_PAGES to _SC_PHYS_PAGES.
The separate available-memory query, GC limits, ABI and Harmony changes remain
byte-for-byte intact. See doc/build/building-and-packaging.md for scope/rollback.
"""

import argparse
import hashlib
import os
from pathlib import Path
import stat
import tempfile


ORIGINAL_SHA256 = "ccf7112d0affea2be160a43ec561e0e939b9331cf4e2dfa652035c4edeb5e482"
FIXED_SHA256 = "a4e4372f5b3cc8a117f0155672d52644301db5cbe8df44e1c57dfe0f2050d64c"
# This verified ELF's executable PT_LOAD has identical file/virtual offsets.
INSTRUCTION_OFFSET = 0x1F2444
ORIGINAL_INSTRUCTION = bytes.fromhex("600c8052")  # mov w0, #99
FIXED_INSTRUCTION = bytes.fromhex("400c8052")  # mov w0, #98


def transform(data: bytes, restore: bool = False) -> bytes:
    """Accept only the exact original or our exact repaired binary, never guesses."""
    digest = hashlib.sha256(data).hexdigest()
    known_instructions = {
        ORIGINAL_SHA256: ORIGINAL_INSTRUCTION,
        FIXED_SHA256: FIXED_INSTRUCTION,
    }
    if digest not in known_instructions:
        raise ValueError(f"Unsupported Mono SHA-256 {digest}; refusing to patch")
    end = INSTRUCTION_OFFSET + len(ORIGINAL_INSTRUCTION)
    if data[INSTRUCTION_OFFSET:end] != known_instructions[digest]:
        raise ValueError("Mono instruction does not match the verified binary")
    expected_hash = ORIGINAL_SHA256 if restore else FIXED_SHA256
    if digest == expected_hash:
        return data
    instruction = ORIGINAL_INSTRUCTION if restore else FIXED_INSTRUCTION
    result = data[:INSTRUCTION_OFFSET] + instruction + data[end:]
    if hashlib.sha256(result).hexdigest() != expected_hash:
        raise ValueError("Repaired Mono SHA-256 mismatch; refusing to publish")
    return result


def stage(source: Path, destination: Path, restore: bool = False) -> str:
    """Publish a verified copy atomically; never modify the reference input."""
    if source.is_symlink() or destination.is_symlink():
        raise ValueError("Mono input/output must not be symbolic links")
    if source.resolve() == destination.resolve() or (
        destination.exists() and source.samefile(destination)
    ):
        raise ValueError("Mono input/output must be distinct files")
    result = transform(source.read_bytes(), restore)
    source_mode = stat.S_IMODE(source.stat().st_mode)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        dir=destination.parent, prefix=f".{destination.name}.", delete=False
    ) as output:
        temporary = Path(output.name)
        try:
            output.write(result)
            output.flush()
            os.fsync(output.fileno())
            os.chmod(temporary, source_mode)
            if temporary.read_bytes() != result:
                raise ValueError("Staged Mono content verification failed")
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    return hashlib.sha256(result).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original or verified repaired Mono")
    parser.add_argument("destination", type=Path, help="Separate staged output file")
    parser.add_argument("--restore", action="store_true", help="Produce the exact original library")
    args = parser.parse_args()
    try:
        digest = stage(args.source, args.destination, args.restore)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Mono memory statistics repair failed: {error}\n")
    mode = "original" if args.restore else "experimental-total-only"
    print(f"Mono memory statistics: {mode}; sha256={digest}; output={args.destination}")


if __name__ == "__main__":
    main()
