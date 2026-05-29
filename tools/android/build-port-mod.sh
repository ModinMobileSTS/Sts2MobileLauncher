#!/usr/bin/env bash
# Build the editable Android port compatibility MOD and copy it into the Android
# shell's dotnet_bcl assets under the assembly name expected by the patched Godot runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$ROOT/../s2" && pwd -P)"
DOTNET_BIN="$S2_REFERENCE_ROOT/.local/dotnet/dotnet"
PROJECT="$ROOT/port-mod/STS2AndroidPortCompat/STS2Mobile.csproj"
REFERENCE_FLAVOR="${REFERENCE_FLAVOR:-original-v0.106.1}"
OUTPUT="$ROOT/port-mod/STS2AndroidPortCompat/bin/Debug/net9.0/STS2Mobile.dll"
TARGET="$ROOT/android/assets/dotnet_bcl/STS2Mobile.dll"
if [[ ! -x "$DOTNET_BIN" ]]; then
  echo "Missing reference dotnet: $DOTNET_BIN" >&2
  exit 1
fi
GIT_BRANCH="$(git -C "$ROOT/port-mod" branch --show-current 2>/dev/null || true)"
if [[ -z "$GIT_BRANCH" ]]; then
  GIT_BRANCH="$(git -C "$ROOT/port-mod" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
fi
GIT_COMMIT="$(git -C "$ROOT/port-mod" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
GIT_SUBJECT="$(git -C "$ROOT/port-mod" log -1 --pretty=%s 2>/dev/null || echo unknown)"
GIT_DIRTY="false"
if ! git -C "$ROOT/port-mod" diff --quiet --ignore-submodules -- 2>/dev/null || ! git -C "$ROOT/port-mod" diff --cached --quiet --ignore-submodules -- 2>/dev/null || [[ -n "$(git -C "$ROOT/port-mod" ls-files --others --exclude-standard 2>/dev/null)" ]]; then
  GIT_DIRTY="true"
fi
BUILD_TIMESTAMP_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"$DOTNET_BIN" build "$PROJECT" -p:ReferenceFlavor="$REFERENCE_FLAVOR" -p:_CompatGitBranch="$GIT_BRANCH" -p:_CompatGitCommit="$GIT_COMMIT" -p:_CompatGitCommitSubject="$GIT_SUBJECT" -p:_CompatGitDirty="$GIT_DIRTY" -p:_CompatBuildTimestampUtc="$BUILD_TIMESTAMP_UTC" -v:q
if [[ ! -f "$OUTPUT" ]]; then
  echo "Expected build output missing: $OUTPUT" >&2
  exit 1
fi
mkdir -p "$(dirname "$TARGET")"
cp -f "$OUTPUT" "$TARGET"
sha256sum "$TARGET"
echo "Copied compat MOD to $TARGET"
"$ROOT/tools/android/make-port-overlay-pck.py"
