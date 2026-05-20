#!/usr/bin/env bash
# Build the editable Android port compatibility MOD and copy it into the Android
# shell's dotnet_bcl assets under the assembly name expected by the patched Godot runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$ROOT/../s2" && pwd -P)"
DOTNET_BIN="$S2_REFERENCE_ROOT/.local/dotnet/dotnet"
PROJECT="$ROOT/port-mod/STS2AndroidPortCompat/STS2Mobile.csproj"
OUTPUT="$ROOT/port-mod/STS2AndroidPortCompat/bin/Debug/net9.0/STS2Mobile.dll"
TARGET="$ROOT/android/assets/dotnet_bcl/STS2Mobile.dll"
if [[ ! -x "$DOTNET_BIN" ]]; then
  echo "Missing reference dotnet: $DOTNET_BIN" >&2
  exit 1
fi
"$DOTNET_BIN" build "$PROJECT" -v:q
if [[ ! -f "$OUTPUT" ]]; then
  echo "Expected build output missing: $OUTPUT" >&2
  exit 1
fi
mkdir -p "$(dirname "$TARGET")"
cp -f "$OUTPUT" "$TARGET"
sha256sum "$TARGET"
echo "Copied compat MOD to $TARGET"
"$ROOT/tools/android/make-port-overlay-pck.py"
