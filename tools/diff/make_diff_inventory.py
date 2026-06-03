#!/usr/bin/env python3
"""Generate a source-oriented diff inventory for the Android port.

Inputs can be passed explicitly or configured locally:

  --mobile / --original
  STS2_DIFF_MOBILE_ROOT / STS2_DIFF_ORIGINAL_ROOT in .env

The script intentionally ignores generated caches/build outputs and classifies
paths into runtime, extra-settings, port-mod, resource-overlay, project-config,
or noise buckets. It does not apply any changes.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

EXCLUDE_DIR_NAMES = {
    ".git",
    ".godot",
    "build",
    ".gradle",
    ".cache",
    "bin",
    "obj",
    ".local",
    ".godot-home",
    "cache",
    "tmp",
    "temp",
}

EXCLUDE_PREFIXES = (
    "android/build/assets/",
    "android/build/build/",
    "android/build/.gradle/",
)

TEXT_EXTENSIONS = {
    ".cs",
    ".gd",
    ".gdshader",
    ".tscn",
    ".tres",
    ".godot",
    ".cfg",
    ".json",
    ".xml",
    ".gradle",
    ".properties",
    ".sh",
    ".md",
    ".txt",
}


def load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            values[key] = os.path.expandvars(os.path.expanduser(value))
    return values


def resolve_config_path(value: str | None, root: Path) -> Path | None:
    if not value:
        return None
    path = Path(os.path.expandvars(os.path.expanduser(value)))
    if not path.is_absolute():
        path = root / path
    return path.resolve(strict=False)


@dataclass(frozen=True)
class FileInfo:
    rel: str
    size: int
    sha256: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def should_skip(rel: str, path: Path) -> bool:
    rel_posix = rel.replace(os.sep, "/")
    if any(rel_posix.startswith(prefix) for prefix in EXCLUDE_PREFIXES):
        return True
    parts = rel_posix.split("/")
    if any(part in EXCLUDE_DIR_NAMES for part in parts[:-1]):
        return True
    name = path.name
    if name.endswith((".apk", ".aab", ".zip", ".7z", ".rar", ".log", ".tmp")):
        return True
    return False


def scan(root: Path) -> dict[str, FileInfo]:
    result: dict[str, FileInfo] = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_NAMES]
        current = Path(dirpath)
        for name in filenames:
            path = current / name
            rel = path.relative_to(root).as_posix()
            if should_skip(rel, path):
                continue
            try:
                stat = path.stat()
            except OSError:
                continue
            if not path.is_file():
                continue
            result[rel] = FileInfo(rel, stat.st_size, sha256_file(path))
    return result


def classify(rel: str, status: str) -> tuple[str, str]:
    if rel.startswith("android/build/src/com/godot/game/") or rel.startswith("android/build/res/"):
        return "extra-settings", "Android Java/resources copied into android/ shell"
    if rel.startswith("android/") or rel == "export_presets.cfg" or rel.startswith("tools/android_launcher_runtime/") or rel.startswith("tools/local_build_android"):
        return "runtime", "APK/Godot/Mono/native runtime layer; not a normal MOD"
    if rel.startswith("src/") and rel.endswith(".cs"):
        return "port-mod", "candidate Harmony/settings/input/platform patch"
    if rel.startswith(("scenes/", "shaders/", "themes/")):
        return "resource-overlay", "candidate overlay PCK or runtime node/material adjustment"
    if rel.startswith("addons/fmod/") or rel.startswith("addons/spine/"):
        return "runtime", "native/plugin dependency"
    if rel.startswith("addons/"):
        return "port-mod", "modding/library helper candidate"
    if rel == "project.godot" or rel.endswith((".csproj", ".sln")):
        return "project-config", "project/export/config difference"
    if "/.import" in rel or rel.endswith((".import", ".uid", ".generated")) or rel.startswith("images/"):
        return "noise-or-overlay", "Godot import/asset churn; verify before overlaying"
    if rel.startswith("scripts/") or rel.startswith("tools/"):
        return "tooling", "build/diff/helper tooling"
    return "unclassified", "needs manual triage"


def make_rows(original: dict[str, FileInfo], mobile: dict[str, FileInfo]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for rel in sorted(set(original) | set(mobile)):
        left = original.get(rel)
        right = mobile.get(rel)
        if left and right and left.sha256 == right.sha256:
            continue
        if left and right:
            status = "MOD"
        elif right:
            status = "ADD"
        else:
            status = "DEL"
        owner, note = classify(rel, status)
        rows.append({
            "path": rel,
            "status": status,
            "owner": owner,
            "note": note,
            "original_size": "" if left is None else str(left.size),
            "mobile_size": "" if right is None else str(right.size),
            "original_sha256": "" if left is None else left.sha256,
            "mobile_sha256": "" if right is None else right.sha256,
        })
    return rows


def write_csv(rows: list[dict[str, str]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=list(rows[0].keys()) if rows else ["path", "status", "owner", "note", "original_size", "mobile_size", "original_sha256", "mobile_sha256"])
        writer.writeheader()
        writer.writerows(rows)


def write_classified(rows: list[dict[str, str]], path: Path, mobile: Path, original: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    counts: dict[tuple[str, str], int] = {}
    for row in rows:
        key = (row["owner"], row["status"])
        counts[key] = counts.get(key, 0) + 1
    owners = sorted({row["owner"] for row in rows})
    lines = [
        "# Classified Android port diff inventory",
        "",
        f"Mobile port: `{mobile}`",
        f"PC original: `{original}`",
        "",
        "## Summary",
        "",
        "| Owner | ADD | MOD | DEL | Total |",
        "|---|---:|---:|---:|---:|",
    ]
    for owner in owners:
        add = counts.get((owner, "ADD"), 0)
        mod = counts.get((owner, "MOD"), 0)
        dele = counts.get((owner, "DEL"), 0)
        lines.append(f"| {owner} | {add} | {mod} | {dele} | {add + mod + dele} |")
    lines.extend(["", "## Notable paths by owner", ""])
    for owner in owners:
        owner_rows = [row for row in rows if row["owner"] == owner]
        lines.append(f"### {owner}")
        lines.append("")
        for row in owner_rows[:120]:
            lines.append(f"- `{row['status']}` `{row['path']}` — {row['note']}")
        if len(owner_rows) > 120:
            lines.append(f"- … {len(owner_rows) - 120} more entries (see CSV)")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_candidates(rows: list[dict[str, str]], path: Path) -> None:
    candidates = [row for row in rows if row["owner"] == "port-mod"]
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Port MOD candidate file list",
        "",
        "These changed files are candidates for Harmony patches, settings bridge code, or MOD helper logic.",
        "They are not copied into the game source; each item needs an explicit runtime-patch owner.",
        "",
    ]
    for row in candidates:
        lines.append(f"- `{row['status']}` `{row['path']}`")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    dotenv = load_dotenv(repo_root / ".env")
    parser = argparse.ArgumentParser()
    parser.add_argument("--mobile", default=os.environ.get("STS2_DIFF_MOBILE_ROOT") or dotenv.get("STS2_DIFF_MOBILE_ROOT"), type=Path)
    parser.add_argument("--original", default=os.environ.get("STS2_DIFF_ORIGINAL_ROOT") or dotenv.get("STS2_DIFF_ORIGINAL_ROOT"), type=Path)
    parser.add_argument("--csv", default=".agent/historical-backup/docs/inventory/port-diff-full.csv", type=Path)
    parser.add_argument("--classified", default=".agent/historical-backup/docs/inventory/port-diff-classified.md", type=Path)
    parser.add_argument("--candidates", default=".agent/historical-backup/docs/inventory/port-mod-candidate-list.md", type=Path)
    args = parser.parse_args()

    mobile = resolve_config_path(str(args.mobile) if args.mobile else None, repo_root)
    original = resolve_config_path(str(args.original) if args.original else None, repo_root)
    if mobile is None:
        raise SystemExit("Missing mobile port dir. Pass --mobile or set STS2_DIFF_MOBILE_ROOT in .env.")
    if original is None:
        raise SystemExit("Missing original dir. Pass --original or set STS2_DIFF_ORIGINAL_ROOT in .env.")
    if not mobile.is_dir():
        raise SystemExit(f"Missing mobile port dir: {mobile}")
    if not original.is_dir():
        raise SystemExit(f"Missing original dir: {original}")

    print(f"Scanning original: {original}")
    original_files = scan(original)
    print(f"Scanning mobile:   {mobile}")
    mobile_files = scan(mobile)
    rows = make_rows(original_files, mobile_files)
    write_csv(rows, args.csv)
    write_classified(rows, args.classified, mobile, original)
    write_candidates(rows, args.candidates)
    print(f"Changed rows: {len(rows)}")
    print(f"Wrote {args.csv}")
    print(f"Wrote {args.classified}")
    print(f"Wrote {args.candidates}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
