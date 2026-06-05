#!/usr/bin/env python3
"""Build an Android-optimized STS2 payload zip from a PC zip and a Godot source tree.

The generated zip keeps the original PC game assemblies (especially sts2.dll) so
MOD IL expectations remain tied to the official build, while the Godot resource
pack is re-exported through an Android preset to produce ETC2/ASTC texture
imports and smaller mobile-friendly font data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

MAGIC = 0x43504447  # "GDPC"
PACK_REL_FILEBASE = 0x02
PCK_HEADER_FORMAT = "<IIIIIIQQ16I"
PCK_HEADER_SIZE = struct.calcsize(PCK_HEADER_FORMAT)
PCK_ALIGNMENT = 16
ZIP_COMPRESSION_LEVEL = 9

REQUIRED_ZIP_ENTRIES = {
    "release_info.json",
    "SlayTheSpire2.pck",
    "data_sts2_windows_x86_64/sts2.dll",
    "data_sts2_windows_x86_64/sts2.deps.json",
    "data_sts2_windows_x86_64/sts2.runtimeconfig.json",
}

ALWAYS_KEEP_DATA_BASENAMES = {
    "sts2.dll",
    "sts2.deps.json",
    "sts2.runtimeconfig.json",
    # Small non-BCL managed dependencies commonly referenced by the official build.
    "0Harmony.dll",
    "JetBrains.Annotations.dll",
    "MonoMod.Backports.dll",
    "MonoMod.ILHelpers.dll",
    "Sentry.dll",
    "SharpGen.Runtime.dll",
    "SharpGen.Runtime.COM.dll",
    "SmartFormat.dll",
    "SmartFormat.ZString.dll",
    "Steamworks.NET.dll",
    "Vortice.DirectX.dll",
    "Vortice.DXGI.dll",
    "Vortice.Mathematics.dll",
}

PROTECTED_BCL_PREFIXES = (
    "System.",
)
PROTECTED_BCL_BASENAMES = {
    "GodotSharp.dll",
    "Microsoft.CSharp.dll",
    "Microsoft.VisualBasic.dll",
    "Microsoft.VisualBasic.Core.dll",
    "Microsoft.Win32.Primitives.dll",
    "Microsoft.Win32.Registry.dll",
    "WindowsBase.dll",
    "mscorlib.dll",
    "netstandard.dll",
}

EXPORT_PRESET = """[preset.0]

name="Android Body"
platform="Android"
runnable=false
advanced_options=false
dedicated_server=false
custom_features=""
export_filter="all_resources"
include_filter="*.tpsheet"
exclude_filter=".git/*,.git/**,.cache/*,.cache/**,build/*,build/**,android/*,android/**,bin/*,bin/**,obj/*,obj/**,.godot/mono/*,.godot/mono/**,*.pdb"
export_path="build/android-body/SlayTheSpire2.pck"
patches=PackedStringArray()
encrypt_pck=false
encrypt_directory=false

[preset.0.options]

custom_template/debug=""
custom_template/release=""
gradle_build/use_gradle_build=false
gradle_build/export_format=0
texture_format/s3tc_bptc=false
texture_format/etc=false
texture_format/etc2=true
architectures/armeabi-v7a=false
architectures/arm64-v8a=true
architectures/x86=false
architectures/x86_64=false
package/unique_name="com.megacrit.sts2.body"
package/name="Slay the Spire 2 Android Body"
version/code=1
version/name="1.0"
package/signed=false
screen/orientation=0
user_data_backup/allow=false
command_line/extra_args=""
apk_expansion/enable=false
"""


@dataclass
class PckEntry:
    path: str
    offset: int
    size: int
    md5: bytes
    flags: int


@dataclass
class PckInfo:
    format_version: int
    godot_major: int
    godot_minor: int
    godot_patch: int
    flags: int
    file_base: int
    dir_base: int
    entries: list[PckEntry]


class BuildError(RuntimeError):
    pass


def log(message: str) -> None:
    print(f"[android-body] {message}", flush=True)


def align(value: int, alignment: int = PCK_ALIGNMENT) -> int:
    return (value + alignment - 1) & ~(alignment - 1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def resolve_path(value: str | None, root: Path) -> Path | None:
    if value is None or value == "":
        return None
    expanded = os.path.expandvars(os.path.expanduser(value))
    path = Path(expanded)
    if not path.is_absolute():
        path = root / path
    return path.resolve(strict=False)


def find_default_godot(root: Path) -> Path | None:
    env = os.environ.get("GODOT_BIN")
    if env:
        path = resolve_path(env, root)
        if path and path.is_file():
            return path
    candidates = [
        root / "../s2/.tools/godot/Godot_v4.5.1-stable_mono_linux_x86_64/Godot_v4.5.1-stable_mono_linux.x86_64",
        root / ".tools/godot/Godot_v4.5.1-stable_mono_linux_x86_64/Godot_v4.5.1-stable_mono_linux.x86_64",
    ]
    for candidate in candidates:
        candidate = candidate.resolve(strict=False)
        if candidate.is_file():
            return candidate
    found = shutil.which("godot")
    return Path(found).resolve(strict=False) if found else None


def infer_default_godot_home(root: Path) -> Path | None:
    env = os.environ.get("GODOT_HOME") or os.environ.get("STS2_GODOT_HOME")
    if env:
        return resolve_path(env, root)
    candidate = (root / "../s2/.godot-home").resolve(strict=False)
    if candidate.is_dir():
        return candidate
    return None


def infer_default_dotnet_root(root: Path) -> Path | None:
    dotnet_bin = os.environ.get("DOTNET_BIN")
    if dotnet_bin:
        path = resolve_path(dotnet_bin, root)
        if path:
            return path.parent
    candidate = (root / "../s2/.local/dotnet").resolve(strict=False)
    if candidate.is_dir():
        return candidate
    return None


def infer_default_nuget_packages(root: Path) -> Path | None:
    env = os.environ.get("NUGET_PACKAGES")
    if env:
        return resolve_path(env, root)
    candidate = (root / "../s2/.local/nuget/packages").resolve(strict=False)
    if candidate.is_dir():
        return candidate
    return None


def read_pck(path: Path) -> PckInfo:
    with path.open("rb") as fp:
        header = fp.read(PCK_HEADER_SIZE)
        if len(header) != PCK_HEADER_SIZE:
            raise BuildError(f"PCK too small: {path}")
        values = struct.unpack(PCK_HEADER_FORMAT, header)
        magic, fmt, major, minor, patch, flags, file_base_raw, dir_base, *_ = values
        if magic != MAGIC:
            raise BuildError(f"Invalid PCK magic in {path}: 0x{magic:08x}")
        file_base = file_base_raw if (flags & PACK_REL_FILEBASE) else 0
        size = path.stat().st_size
        if dir_base <= 0 or dir_base >= size:
            raise BuildError(f"Invalid PCK directory offset in {path}: {dir_base}")
        fp.seek(dir_base)
        raw_count = fp.read(4)
        if len(raw_count) != 4:
            raise BuildError(f"Missing PCK file count in {path}")
        count = struct.unpack("<I", raw_count)[0]
        if count <= 0 or count > 2_000_000:
            raise BuildError(f"Suspicious PCK file count in {path}: {count}")
        entries: list[PckEntry] = []
        for index in range(count):
            raw_len = fp.read(4)
            if len(raw_len) != 4:
                raise BuildError(f"Truncated PCK directory at entry {index}")
            path_len = struct.unpack("<I", raw_len)[0]
            if path_len <= 0 or path_len > 128 * 1024:
                raise BuildError(f"Invalid PCK path length at entry {index}: {path_len}")
            raw_path = fp.read(path_len)
            if len(raw_path) != path_len:
                raise BuildError(f"Truncated PCK path at entry {index}")
            entry_path = raw_path.split(b"\0", 1)[0].decode("utf-8", "replace")
            raw_bounds = fp.read(16)
            if len(raw_bounds) != 16:
                raise BuildError(f"Truncated PCK bounds at entry {index}: {entry_path}")
            relative_offset, entry_size = struct.unpack("<QQ", raw_bounds)
            md5 = fp.read(16)
            raw_flags = fp.read(4)
            if len(md5) != 16 or len(raw_flags) != 4:
                raise BuildError(f"Truncated PCK metadata at entry {index}: {entry_path}")
            entry_flags = struct.unpack("<I", raw_flags)[0]
            absolute_offset = file_base + relative_offset
            if absolute_offset < 0 or entry_size < 0 or absolute_offset + entry_size > size:
                raise BuildError(f"Invalid PCK entry bounds for {entry_path}")
            entries.append(PckEntry(entry_path, absolute_offset, entry_size, md5, entry_flags))
    return PckInfo(fmt, major, minor, patch, flags, file_base_raw, dir_base, entries)


def pck_path_key(path: str) -> str:
    return path[6:] if path.startswith("res://") else path


def should_exclude_pck_entry(path: str) -> bool:
    key = pck_path_key(path).replace("\\", "/")
    lower = key.lower()
    if lower.startswith(".godot/mono/"):
        return True
    if lower.endswith((".bptc.ctex", ".s3tc.ctex")):
        return True
    if lower.endswith((".pdb", ".mdb")):
        return True
    if lower.startswith("addons/sentry/bin/") or lower == "addons/sentry/sentry.gdextension":
        return True
    # Native editor/desktop extension binaries are only needed while exporting.
    if lower.startswith("addons/") and lower.endswith((".dll", ".exe", ".dylib", ".so", ".a")):
        return True
    if lower.startswith("bin/") and lower.endswith((".dll", ".exe", ".so", ".dylib")):
        return True
    return False


def rewrite_filtered_pck(src: Path, dest: Path) -> dict:
    info = read_pck(src)
    included = [entry for entry in info.entries if not should_exclude_pck_entry(entry.path)]
    excluded = len(info.entries) - len(included)
    file_base = align(PCK_HEADER_SIZE)
    dest.parent.mkdir(parents=True, exist_ok=True)
    new_entries: list[tuple[PckEntry, int]] = []
    current = file_base
    with src.open("rb") as src_fp, dest.open("wb") as out:
        out.write(b"\0" * file_base)
        for entry in included:
            aligned = align(current)
            if aligned > current:
                out.write(b"\0" * (aligned - current))
                current = aligned
            src_fp.seek(entry.offset)
            remaining = entry.size
            while remaining > 0:
                chunk = src_fp.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise BuildError(f"Unexpected EOF while copying PCK entry {entry.path}")
                out.write(chunk)
                remaining -= len(chunk)
                current += len(chunk)
            new_entries.append((entry, aligned - file_base))
        dir_base = align(current)
        if dir_base > current:
            out.write(b"\0" * (dir_base - current))
            current = dir_base
        out.write(struct.pack("<I", len(new_entries)))
        for entry, relative_offset in new_entries:
            encoded = entry.path.encode("utf-8")
            padded_len = len(encoded) + ((4 - (len(encoded) % 4)) % 4)
            out.write(struct.pack("<I", padded_len))
            out.write(encoded)
            out.write(b"\0" * (padded_len - len(encoded)))
            out.write(struct.pack("<QQ", relative_offset, entry.size))
            out.write(entry.md5)
            out.write(struct.pack("<I", entry.flags))
        end = out.tell()
        out.seek(0)
        header = struct.pack(
            PCK_HEADER_FORMAT,
            MAGIC,
            info.format_version,
            info.godot_major,
            info.godot_minor,
            info.godot_patch,
            info.flags | PACK_REL_FILEBASE,
            file_base,
            dir_base,
            *([0] * 16),
        )
        out.write(header)
        out.seek(end)
    return {
        "input_size": src.stat().st_size,
        "output_size": dest.stat().st_size,
        "input_files": len(info.entries),
        "output_files": len(included),
        "excluded_files": excluded,
    }


def pck_stats(path: Path) -> dict:
    info = read_pck(path)
    by_suffix: dict[str, dict[str, int]] = {}
    by_top: dict[str, dict[str, int]] = {}
    for entry in info.entries:
        key = pck_path_key(entry.path).replace("\\", "/")
        suffix = Path(key).suffix.lower() or "<none>"
        for special in (".astc.ctex", ".etc2.ctex", ".s3tc.ctex", ".bptc.ctex", ".ctex", ".fontdata"):
            if key.lower().endswith(special):
                suffix = special
                break
        top = key.split("/", 1)[0]
        bucket = by_suffix.setdefault(suffix, {"count": 0, "bytes": 0})
        bucket["count"] += 1
        bucket["bytes"] += entry.size
        top_bucket = by_top.setdefault(top, {"count": 0, "bytes": 0})
        top_bucket["count"] += 1
        top_bucket["bytes"] += entry.size
    variants = {name: by_suffix.get(name, {"count": 0, "bytes": 0}) for name in (".astc.ctex", ".etc2.ctex", ".s3tc.ctex", ".bptc.ctex", ".ctex", ".fontdata")}
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
        "godot_version": f"{info.godot_major}.{info.godot_minor}.{info.godot_patch}",
        "file_count": len(info.entries),
        "texture_variants": variants,
        "largest_suffixes": sorted(by_suffix.items(), key=lambda item: item[1]["bytes"], reverse=True)[:20],
        "largest_top_dirs": sorted(by_top.items(), key=lambda item: item[1]["bytes"], reverse=True)[:20],
    }



def normalize_zip_name(name: str) -> str:
    normalized = name.replace("\\", "/").strip()
    while normalized.startswith("/"):
        normalized = normalized[1:]
    return normalized


def detect_pc_zip_prefix(zf: zipfile.ZipFile) -> str:
    names = {normalize_zip_name(info.filename) for info in zf.infolist() if not info.is_dir()}

    def has_required(prefix: str) -> bool:
        return all(prefix + required in names for required in REQUIRED_ZIP_ENTRIES)

    if has_required(""):
        return ""

    candidates: set[str] = set()
    for name in names:
        for required in REQUIRED_ZIP_ENTRIES:
            suffix = "/" + required
            if name.endswith(suffix):
                candidates.add(name[: -len(required)])
            elif name == required:
                candidates.add("")
    for prefix in sorted(candidates, key=lambda value: (value.count("/"), len(value))):
        if prefix and not prefix.endswith("/"):
            prefix += "/"
        if has_required(prefix):
            return prefix

    missing = sorted(REQUIRED_ZIP_ENTRIES - names)
    raise BuildError(f"PC zip is missing required entries: {missing}")

def validate_pc_zip(path: Path) -> dict:
    if not path.is_file():
        raise BuildError(f"Missing PC zip: {path}")
    with zipfile.ZipFile(path) as zf:
        prefix = detect_pc_zip_prefix(zf)
        release_info = json.loads(zf.read(prefix + "release_info.json").decode("utf-8"))
        sts2_dll = zf.read(prefix + "data_sts2_windows_x86_64/sts2.dll")
        with zf.open(prefix + "SlayTheSpire2.pck") as fp:
            if fp.read(4) != b"GDPC":
                raise BuildError("Original PC zip contains an invalid SlayTheSpire2.pck")
    return {
        "path": str(path),
        "size": path.stat().st_size,
        "sha256": sha256_file(path),
        "entry_prefix": prefix,
        "release_info": release_info,
        "sts2_dll_sha256": sha256_bytes(sts2_dll),
        "sts2_dll_size": len(sts2_dll),
    }


def load_keep_data_basenames(pc_zip: Path) -> set[str]:
    keep = set(ALWAYS_KEEP_DATA_BASENAMES)
    try:
        with zipfile.ZipFile(pc_zip) as zf:
            prefix = detect_pc_zip_prefix(zf)
            deps = json.loads(zf.read(prefix + "data_sts2_windows_x86_64/sts2.deps.json").decode("utf-8"))
        for target in deps.get("targets", {}).values():
            for library_name, metadata in target.items():
                if library_name.startswith("runtimepack.Microsoft.NETCore.App.Runtime") or library_name.startswith("GodotSharp/"):
                    continue
                runtime = metadata.get("runtime", {})
                for runtime_path in runtime:
                    basename = Path(runtime_path.replace("\\", "/")).name
                    if not basename:
                        continue
                    if is_protected_bcl(basename):
                        continue
                    if basename.endswith(".dll"):
                        keep.add(basename)
    except Exception as exc:  # keep the hard-coded safe set on deps parse failure.
        log(f"Warning: unable to derive dependency keep-list from deps.json: {exc}")
    return keep


def is_protected_bcl(name: str) -> bool:
    return name in PROTECTED_BCL_BASENAMES or any(name.startswith(prefix) for prefix in PROTECTED_BCL_PREFIXES)


def ensure_source_tree(source_dir: Path) -> None:
    if not source_dir.is_dir():
        raise BuildError(f"Missing source directory: {source_dir}")
    for relative in ("project.godot", "sts2.csproj"):
        if not (source_dir / relative).is_file():
            raise BuildError(f"Source directory is missing {relative}: {source_dir}")


def copy_project(source_dir: Path, project_dir: Path) -> None:
    if project_dir.exists():
        shutil.rmtree(project_dir)
    project_dir.parent.mkdir(parents=True, exist_ok=True)
    rsync = shutil.which("rsync")
    excludes = [
        ".git/",
        ".cache/",
        "build/",
        "android/",
        "bin/",
        "obj/",
        ".godot/imported/",
        ".godot/mono/",
        ".godot/shader_cache/",
        ".godot/editor/",
    ]
    if rsync:
        command = [rsync, "-a", "--delete"]
        for item in excludes:
            command.append(f"--exclude={item}")
        command.extend([str(source_dir) + "/", str(project_dir) + "/"])
        subprocess.run(command, check=True)
    else:
        def ignore(path: str, names: list[str]) -> set[str]:
            rel = Path(path).resolve(strict=False).relative_to(source_dir.resolve(strict=False)) if Path(path).resolve(strict=False) != source_dir.resolve(strict=False) else Path("")
            ignored: set[str] = set()
            for name in names:
                child_rel = (rel / name).as_posix()
                if name in {".git", ".cache", "build", "android", "bin", "obj"}:
                    ignored.add(name)
                if child_rel in {".godot/imported", ".godot/mono", ".godot/shader_cache", ".godot/editor"}:
                    ignored.add(name)
            return ignored
        shutil.copytree(source_dir, project_dir, ignore=ignore)
    (project_dir / ".godot/imported").mkdir(parents=True, exist_ok=True)


def copy_reference_native_support(port_reference: Path | None, project_dir: Path) -> list[str]:
    if port_reference is None or not port_reference.is_dir():
        return []
    copied: list[str] = []
    relative_dirs = [
        "addons/fmod/libs/linux",
        "addons/fmod/libs/android",
        "addons/spine/linux",
        "addons/spine/android",
    ]
    for rel in relative_dirs:
        src = port_reference / rel
        dest = project_dir / rel
        if not src.exists():
            continue
        if dest.exists():
            shutil.rmtree(dest)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(src, dest)
        copied.append(rel)

    # Some reference projects keep Spine native binaries in bin/<platform>/ while
    # the official .gdextension expects addons/spine/<platform>/.  Copy those
    # helper binaries into the temporary export project so headless import/export
    # can resolve the editor extension.  They are filtered from the final PCK.
    spine_fallbacks = [
        (port_reference / "bin/linux", project_dir / "addons/spine/linux"),
        (port_reference / "bin/android", project_dir / "addons/spine/android"),
    ]
    for src, dest in spine_fallbacks:
        if not src.exists() or dest.exists():
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(src, dest)
        copied.append(str(dest.relative_to(project_dir)))
    return copied


def patch_project_files(project_dir: Path) -> dict:
    result = {
        "font_imports_patched": 0,
        "project_godot_patched": False,
        "extension_list_patched": False,
        "sentry_gdextension_removed": False,
        "csproj_patched": False,
    }
    for import_file in (project_dir / "fonts").rglob("*.import"):
        text = import_file.read_text(encoding="utf-8", errors="replace")
        original = text
        text = text.replace("multichannel_signed_distance_field=true", "multichannel_signed_distance_field=false")
        text = text.replace("msdf_pixel_range=24", "msdf_pixel_range=8")
        # Some future exports may use a different non-minimal MSDF range.
        lines = []
        for line in text.splitlines():
            if line.startswith("msdf_pixel_range="):
                line = "msdf_pixel_range=8"
            lines.append(line)
        text = "\n".join(lines) + ("\n" if original.endswith("\n") else "")
        if text != original:
            import_file.write_text(text, encoding="utf-8")
            result["font_imports_patched"] += 1

    project_godot = project_dir / "project.godot"
    text = project_godot.read_text(encoding="utf-8", errors="replace")
    original = text
    new_lines: list[str] = []
    saw_rendering = False
    saw_etc2_astc = False
    in_rendering = False
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            if in_rendering and not saw_etc2_astc:
                new_lines.append("textures/vram_compression/import_etc2_astc=true")
                saw_etc2_astc = True
            in_rendering = stripped == "[rendering]"
            saw_rendering = saw_rendering or in_rendering
        if stripped.startswith("SentryInit="):
            raw = ";" + raw
        if stripped.startswith("textures/vram_compression/import_etc2_astc="):
            raw = "textures/vram_compression/import_etc2_astc=true"
            saw_etc2_astc = True
        if stripped.startswith("gui/fonts/dynamic_fonts/use_oversampling="):
            raw = "gui/fonts/dynamic_fonts/use_oversampling=false"
        if stripped.startswith("project/solution_directory="):
            raw = "project/solution_directory=\"./\""
        new_lines.append(raw)
    if in_rendering and not saw_etc2_astc:
        new_lines.append("textures/vram_compression/import_etc2_astc=true")
    if not saw_rendering:
        new_lines.extend(["", "[rendering]", "", "textures/vram_compression/import_etc2_astc=true"])
    text = "\n".join(new_lines) + "\n"
    if text != original:
        project_godot.write_text(text, encoding="utf-8")
        result["project_godot_patched"] = True

    sentry_extension = project_dir / "addons/sentry/sentry.gdextension"
    if sentry_extension.exists():
        sentry_extension.unlink()
        result["sentry_gdextension_removed"] = True

    extension_list = project_dir / ".godot/extension_list.cfg"
    extension_list.parent.mkdir(parents=True, exist_ok=True)
    entries = []
    if (project_dir / "addons/spine/spine_godot_extension.gdextension").is_file():
        entries.append("res://addons/spine/spine_godot_extension.gdextension")
    if (project_dir / "addons/fmod/fmod.gdextension").is_file():
        entries.append("res://addons/fmod/fmod.gdextension")
    new_extension_text = "\n".join(entries) + ("\n" if entries else "")
    if not extension_list.is_file() or extension_list.read_text(encoding="utf-8", errors="replace") != new_extension_text:
        extension_list.write_text(new_extension_text, encoding="utf-8")
        result["extension_list_patched"] = True

    csproj = project_dir / "sts2.csproj"
    text = csproj.read_text(encoding="utf-8", errors="replace")
    original = text
    text = text.replace("<TargetFramework>net90</TargetFramework>", "<TargetFramework>net9.0</TargetFramework>")
    if "<Compile Remove=\".cache/**/*.cs\"" not in text:
        insert = """  <ItemGroup>\n    <Compile Remove=\".cache/**/*.cs\" />\n    <Compile Remove=\"cache/**/*.cs\" />\n    <Compile Remove=\"tools/**/*.cs\" />\n  </ItemGroup>\n\n"""
        marker = "  <ItemGroup>\n    <Reference Include=\"Steamworks.NET\">"
        if marker in text:
            text = text.replace(marker, insert + marker, 1)
    if text != original:
        csproj.write_text(text, encoding="utf-8")
        result["csproj_patched"] = True
    return result


def write_export_preset(project_dir: Path) -> None:
    (project_dir / "export_presets.cfg").write_text(EXPORT_PRESET, encoding="utf-8")


def build_env(root: Path, godot_home: Path | None, dotnet_root: Path | None, nuget_packages: Path | None) -> dict[str, str]:
    env = dict(os.environ)
    if godot_home:
        env["HOME"] = str(godot_home)
        env["XDG_DATA_HOME"] = str(godot_home / ".local/share")
        env["XDG_CONFIG_HOME"] = str(godot_home / ".config")
    if dotnet_root:
        env["DOTNET_ROOT"] = str(dotnet_root)
        env["PATH"] = str(dotnet_root) + os.pathsep + env.get("PATH", "")
    if nuget_packages:
        env["NUGET_PACKAGES"] = str(nuget_packages)
    java_home = env.get("JAVA_HOME")
    if java_home:
        env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
        lib_server = str(Path(java_home) / "lib/server")
        env["LD_LIBRARY_PATH"] = lib_server + (os.pathsep + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    # Help GDExtension dependencies during headless import/export if copied from ../s2.
    extra_libs = [
        root / "../s2/addons/fmod/libs/linux",
        root / "../s2/addons/spine/linux",
    ]
    existing = [str(path.resolve(strict=False)) for path in extra_libs if path.is_dir()]
    if existing:
        env["LD_LIBRARY_PATH"] = os.pathsep.join(existing + ([env["LD_LIBRARY_PATH"]] if env.get("LD_LIBRARY_PATH") else []))
    return env


def run_logged(command: Sequence[str], cwd: Path, env: dict[str, str], log_path: Path, timeout: int | None = None) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log(f"Running: {' '.join(command)}")
    log(f"Log: {log_path}")
    with log_path.open("w", encoding="utf-8", errors="replace") as log_file:
        log_file.write(f"$ {' '.join(command)}\n")
        log_file.flush()
        process = subprocess.Popen(command, cwd=str(cwd), env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        assert process.stdout is not None
        for line in process.stdout:
            log_file.write(line)
        status = process.wait(timeout=timeout)
    if status != 0:
        tail = ""
        try:
            lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
            tail = "\n".join(lines[-120:])
        except Exception:
            pass
        raise BuildError(f"Command failed with exit code {status}: {' '.join(command)}\n--- log tail ---\n{tail}")


def export_android_pck(project_dir: Path, godot_bin: Path, env: dict[str, str], logs_dir: Path) -> Path:
    imported_dir = project_dir / ".godot/imported"
    if imported_dir.exists():
        shutil.rmtree(imported_dir)
    imported_dir.mkdir(parents=True, exist_ok=True)
    run_logged([str(godot_bin), "--headless", "--path", str(project_dir), "--import", "--quit"], project_dir, env, logs_dir / "godot-import.log")
    raw_pck = project_dir / "build/android-body/SlayTheSpire2.raw.pck"
    raw_pck.parent.mkdir(parents=True, exist_ok=True)
    run_logged([str(godot_bin), "--headless", "--path", str(project_dir), "--export-pack", "Android Body", str(raw_pck)], project_dir, env, logs_dir / "godot-export-pack.log")
    if not raw_pck.is_file():
        # Some Godot versions may honor export_path instead of the explicit path after export-pack errors.
        fallback = project_dir / "build/android-body/SlayTheSpire2.pck"
        if fallback.is_file():
            raw_pck = fallback
        else:
            raise BuildError(f"Godot export did not create PCK: {raw_pck}")
    return raw_pck


def validate_optimized_stats(stats: dict) -> None:
    variants = stats["texture_variants"]
    astc = variants[".astc.ctex"]["count"]
    etc2 = variants[".etc2.ctex"]["count"]
    bptc = variants[".bptc.ctex"]["count"]
    s3tc = variants[".s3tc.ctex"]["count"]
    if astc + etc2 <= 0:
        raise BuildError("Optimized PCK does not contain Android ASTC/ETC2 texture imports.")
    if bptc or s3tc:
        raise BuildError(f"Optimized PCK still contains desktop texture imports: bptc={bptc}, s3tc={s3tc}")


def package_zip(pc_zip: Path, pck: Path, out_zip: Path, manifest: dict, keep_data_basenames: set[str]) -> dict:
    out_zip.parent.mkdir(parents=True, exist_ok=True)
    temp_zip = out_zip.with_suffix(out_zip.suffix + ".tmp")
    if temp_zip.exists():
        temp_zip.unlink()
    written: list[str] = []
    total_uncompressed = 0
    with zipfile.ZipFile(pc_zip, "r") as zin, zipfile.ZipFile(temp_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=ZIP_COMPRESSION_LEVEL) as zout:
        source_prefix = detect_pc_zip_prefix(zin)
        pck_info = zipfile.ZipInfo("SlayTheSpire2.pck", date_time=time.localtime()[:6])
        pck_info.compress_type = zipfile.ZIP_DEFLATED
        with pck.open("rb") as fp:
            zout.writestr(pck_info, fp.read(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=ZIP_COMPRESSION_LEVEL)
        written.append("SlayTheSpire2.pck")
        total_uncompressed += pck.stat().st_size

        for info in zin.infolist():
            name = normalize_zip_name(info.filename)
            if info.is_dir():
                continue
            if source_prefix:
                if not name.startswith(source_prefix):
                    continue
                rel_name = name[len(source_prefix):]
            else:
                rel_name = name
            if not rel_name or rel_name == "SlayTheSpire2.pck":
                continue
            include = False
            if rel_name in {"release_info.json", "mp_names.json"} or rel_name.startswith("controller_config/"):
                include = True
            elif rel_name.startswith("data_sts2_windows_x86_64/"):
                basename = Path(rel_name).name
                include = basename in keep_data_basenames
            if not include:
                continue
            data = zin.read(info)
            new_info = zipfile.ZipInfo(rel_name, date_time=info.date_time)
            new_info.external_attr = info.external_attr
            new_info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(new_info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=ZIP_COMPRESSION_LEVEL)
            written.append(rel_name)
            total_uncompressed += len(data)
        manifest_info = zipfile.ZipInfo(".android_body_manifest.json", date_time=time.localtime()[:6])
        manifest_info.compress_type = zipfile.ZIP_DEFLATED
        manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        zout.writestr(manifest_info, manifest_bytes, compress_type=zipfile.ZIP_DEFLATED, compresslevel=ZIP_COMPRESSION_LEVEL)
        written.append(".android_body_manifest.json")
        total_uncompressed += len(manifest_bytes)
    os.replace(temp_zip, out_zip)
    return {
        "path": str(out_zip),
        "size": out_zip.stat().st_size,
        "sha256": sha256_file(out_zip),
        "file_count": len(written),
        "total_uncompressed_bytes": total_uncompressed,
        "entries": written,
    }


def validate_output_zip(path: Path) -> dict:
    return validate_pc_zip(path)


def main(argv: Sequence[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Build an Android-optimized STS2 body zip using original PC DLLs and a re-exported Android PCK.")
    parser.add_argument("--pc-zip", required=True, help="Original PC game zip, e.g. SlayTheSpire2.zip")
    parser.add_argument("--source-dir", required=True, help="Godot source/decompiled project matching the PC zip version")
    parser.add_argument("--out", required=True, help="Output optimized payload zip")
    parser.add_argument("--work-dir", default=".agent/tmp/android-body-build", help="Work root for copied project and logs")
    parser.add_argument("--godot-bin", default=None, help="Godot 4.5.1 Mono executable. Defaults to GODOT_BIN or ../s2/.tools/godot/...")
    parser.add_argument("--godot-home", default=None, help="Isolated Godot HOME/XDG root. Defaults to GODOT_HOME/STS2_GODOT_HOME or ../s2/.godot-home if present")
    parser.add_argument("--dotnet-root", default=None, help=".NET SDK root. Defaults to DOTNET_BIN parent or ../s2/.local/dotnet if present")
    parser.add_argument("--nuget-packages", default=None, help="NuGet package cache. Defaults to NUGET_PACKAGES or ../s2/.local/nuget/packages if present")
    parser.add_argument("--port-reference", default="../s2", help="Optional port/reference project used only for Linux/Android GDExtension helper binaries")
    parser.add_argument("--keep-work", action="store_true", help="Keep previous work project instead of recreating it. Mostly useful for debugging")
    args = parser.parse_args(argv)

    pc_zip = resolve_path(args.pc_zip, root)
    source_dir = resolve_path(args.source_dir, root)
    out_zip = resolve_path(args.out, root)
    work_root = resolve_path(args.work_dir, root)
    godot_bin = resolve_path(args.godot_bin, root) if args.godot_bin else find_default_godot(root)
    godot_home = resolve_path(args.godot_home, root) if args.godot_home else infer_default_godot_home(root)
    dotnet_root = resolve_path(args.dotnet_root, root) if args.dotnet_root else infer_default_dotnet_root(root)
    nuget_packages = resolve_path(args.nuget_packages, root) if args.nuget_packages else infer_default_nuget_packages(root)
    port_reference = resolve_path(args.port_reference, root) if args.port_reference else None

    assert pc_zip is not None and source_dir is not None and out_zip is not None and work_root is not None
    if godot_bin is None or not godot_bin.is_file():
        raise BuildError("Missing Godot executable. Pass --godot-bin or set GODOT_BIN.")
    ensure_source_tree(source_dir)
    pc_info = validate_pc_zip(pc_zip)
    version = pc_info["release_info"].get("version") or "unknown"
    safe_version = str(version).replace("/", "_").replace(" ", "_")
    build_root = work_root / safe_version
    project_dir = build_root / "project"
    logs_dir = build_root / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    log(f"PC zip: {pc_zip}")
    log(f"Source: {source_dir}")
    log(f"Version: {version}")
    if pc_info.get("entry_prefix"):
        log(f"PC zip root prefix: {pc_info['entry_prefix']}")
    log(f"Godot: {godot_bin}")
    if godot_home:
        log(f"Godot HOME: {godot_home}")
    if dotnet_root:
        log(f"DOTNET_ROOT: {dotnet_root}")
    if nuget_packages:
        log(f"NUGET_PACKAGES: {nuget_packages}")

    if not args.keep_work or not project_dir.exists():
        log("Copying source project into ignored work directory...")
        copy_project(source_dir, project_dir)
    native_copied = copy_reference_native_support(port_reference, project_dir)
    if native_copied:
        log("Copied reference GDExtension helper dirs: " + ", ".join(native_copied))
    patch_info = patch_project_files(project_dir)
    write_export_preset(project_dir)
    log(f"Patch info: {json.dumps(patch_info, ensure_ascii=False)}")

    env = build_env(root, godot_home, dotnet_root, nuget_packages)
    raw_pck = export_android_pck(project_dir, godot_bin, env, logs_dir)
    filtered_pck = build_root / "SlayTheSpire2.pck"
    filter_info = rewrite_filtered_pck(raw_pck, filtered_pck)
    optimized_stats = pck_stats(filtered_pck)
    validate_optimized_stats(optimized_stats)
    keep_data_basenames = load_keep_data_basenames(pc_zip)

    manifest = {
        "schema": 1,
        "kind": "sts2_android_optimized_body_zip",
        "built_at_unix": int(time.time()),
        "source_pc_zip": pc_info,
        "source_dir": str(source_dir),
        "godot_bin": str(godot_bin),
        "optimizations": {
            "original_sts2_dll_preserved": True,
            "android_pck_reexported": True,
            "android_texture_formats": ["astc", "etc2"],
            "desktop_texture_formats_removed_from_pck": ["bptc", "s3tc"],
            "font_msdf_disabled_for_smaller_fontdata": True,
            "sentry_autoload_disabled_in_temp_project": True,
            "pc_bcl_and_windows_native_runtime_removed_from_zip": True,
            "kept_data_basenames": sorted(keep_data_basenames),
        },
        "patch_info": patch_info,
        "native_support_copied_for_export": native_copied,
        "pck_filter": filter_info,
        "optimized_pck": optimized_stats,
    }
    zip_info = package_zip(pc_zip, filtered_pck, out_zip, manifest, keep_data_basenames)
    validate_output_zip(out_zip)
    final_manifest = dict(manifest)
    final_manifest["output_zip"] = zip_info
    (build_root / "android_body_manifest.json").write_text(json.dumps(final_manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    log("Done.")
    log(f"Output zip: {out_zip}")
    log(f"Output size: {zip_info['size'] / 1024 / 1024:.1f} MiB")
    log(f"Output sha256: {zip_info['sha256']}")
    variants = optimized_stats["texture_variants"]
    log(
        "PCK texture counts: "
        f"astc={variants['.astc.ctex']['count']}, "
        f"etc2={variants['.etc2.ctex']['count']}, "
        f"bptc={variants['.bptc.ctex']['count']}, "
        f"s3tc={variants['.s3tc.ctex']['count']}, "
        f"fontdata={variants['.fontdata']['bytes'] / 1024 / 1024:.1f} MiB"
    )
    log(f"Work manifest: {build_root / 'android_body_manifest.json'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BuildError as exc:
        print(f"[android-body] ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
