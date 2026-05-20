#!/usr/bin/env bash
# Copy large Android runtime artifacts from reference projects into this working tree.
# These files are intentionally gitignored because they are generated/copied runtime
# dependencies, not source code or user-provided game payloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$ROOT/../s2" && pwd -P)"
LAUNCHER_REFERENCE_ROOT="$S2_REFERENCE_ROOT/.cache/StS2-Launcher_Mod_Manager"
ANDROID_SRC="$LAUNCHER_REFERENCE_ROOT/android"
ANDROID_DST="$ROOT/android"

require_dir() {
  [[ -d "$1" ]] || { echo "Missing directory: $1" >&2; exit 1; }
}
require_file() {
  [[ -f "$1" ]] || { echo "Missing file: $1" >&2; exit 1; }
}

require_dir "$ANDROID_SRC/libs"
require_dir "$ANDROID_SRC/assets/dotnet_bcl"
require_file "$ANDROID_SRC/gradle/wrapper/gradle-wrapper.jar"

mkdir -p "$ANDROID_DST/libs" "$ANDROID_DST/assets" "$ANDROID_DST/gradle/wrapper"
rsync -a --delete "$ANDROID_SRC/libs/" "$ANDROID_DST/libs/"
rsync -a --delete "$ANDROID_SRC/assets/dotnet_bcl/" "$ANDROID_DST/assets/dotnet_bcl/"
cp -f "$ANDROID_SRC/gradle/wrapper/gradle-wrapper.jar" "$ANDROID_DST/gradle/wrapper/gradle-wrapper.jar"

printf 'Synced runtime artifacts from %s to %s\n' "$ANDROID_SRC" "$ANDROID_DST"
