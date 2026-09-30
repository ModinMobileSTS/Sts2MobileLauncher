#!/usr/bin/env python3
"""Remove the legacy MonoAssembly pointer write from the pinned MonoMod helper.

Only for the verified Ekyso Android ARM64 Mono, whose native field/method access
checks already grant access. Preserve the managed ReflectionHelper assembly cache
and public method ABI; do not guess a replacement native layout or rebuild Mono.
The reference inputs remain untouched. See doc/build/building-and-packaging.md.
"""

import argparse
import hashlib
import os
from pathlib import Path
import stat
import struct
import tempfile


ORIGINAL_SHA256 = "e085299936748e22cb8575c5522780fc22e8253d5e483d270473b0a69538b593"
FIXED_SHA256 = "0557563916172c0517314d669aaf1d996fb69af1710b2b9c99a481f28235b4f0"
MONO_SHA256 = {
    "ccf7112d0affea2be160a43ec561e0e939b9331cf4e2dfa652035c4edeb5e482",
    # Same native access checks; opt-in memory-total-only repair.
    "a4e4372f5b3cc8a117f0155672d52644301db5cbe8df44e1c57dfe0f2050d64c",
}
# SetMonoCorlibInternal(Assembly, bool): RVA 0x1215c, PE file offset 0x1035c.
# One fat method header, 465 IL bytes, padding and two small finally clauses.
METHOD_OFFSET = 0x1035C
METHOD_SPAN = 508
HEADER = bytes.fromhex("1b300300d101000089000011")


def transform(data: bytes) -> bytes:
    digest = hashlib.sha256(data).hexdigest()
    if digest == FIXED_SHA256:
        return data
    if digest != ORIGINAL_SHA256:
        raise ValueError(f"Unsupported MonoMod.Utils SHA-256 {digest}; refusing to patch")
    if data[METHOD_OFFSET:METHOD_OFFSET + 12] != HEADER:
        raise ValueError("MonoMod helper header does not match the verified binary")

    start = METHOD_OFFSET + len(HEADER)
    # Keep IL_0000..0013: Mono runtime gate and asm null validation.
    # Keep IL_0099..0106: GetName + locked registration under hashed/full/short
    # names, including its finally. Remove both private native-field discovery
    # and the entire offset calculation/stind.i1 tail. Internal relative branches
    # remain unchanged; relocate the one surviving exception clause below.
    code = data[start:start + 0x14] + data[start + 0x99:start + 0x107] + b"\x2a"
    header = struct.pack("<HHII", 0x301B, 3, len(code), 0x11000089)
    shift = 0x99 - 0x14
    exception_table = b"\x01\x10\0\0" + struct.pack(
        "<HHBHBI", 2, 0xAA - shift, 0x51, 0xFB - shift, 0x0C, 0
    )
    body = header + code + bytes(-len(code) % 4) + exception_table
    # Leave all other methods, tokens, assembly identity and metadata intact.
    # Zero the discarded body; no unreachable native writer is left behind.
    result = data[:METHOD_OFFSET] + body.ljust(METHOD_SPAN, b"\0") + data[METHOD_OFFSET + METHOD_SPAN:]
    if hashlib.sha256(result).hexdigest() != FIXED_SHA256:
        raise ValueError("Repaired MonoMod SHA-256 mismatch; refusing to publish")
    return result


def stage(source: Path, destination: Path, mono_libraries: list[Path]) -> str:
    if not mono_libraries:
        raise ValueError("The paired native Mono libraries are required")
    for input_path in [source, *mono_libraries]:
        if input_path.is_symlink() or destination.is_symlink():
            raise ValueError("Runtime input/output must not be symbolic links")
        if input_path.resolve() == destination.resolve() or (
            destination.exists() and input_path.samefile(destination)
        ):
            raise ValueError("Runtime input/output must be distinct files")
    for library in mono_libraries:
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        if digest not in MONO_SHA256:
            raise ValueError(f"Unsupported paired Mono SHA-256 {digest}: {library}")
    result = transform(source.read_bytes())
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
                raise ValueError("Staged MonoMod content verification failed")
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    return hashlib.sha256(result).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original or verified repaired MonoMod.Utils.dll")
    parser.add_argument("destination", type=Path, help="Separate staged output file")
    parser.add_argument("--mono", type=Path, action="append", required=True,
                        help="Paired libmonosgen-2.0.so; repeat for each packaged build type")
    args = parser.parse_args()
    try:
        digest = stage(args.source, args.destination, args.mono)
    except (OSError, ValueError) as error:
        parser.exit(1, f"MonoMod native layout repair failed: {error}\n")
    print(f"MonoMod managed-cache-only helper: sha256={digest}; output={args.destination}")


if __name__ == "__main__":
    main()
