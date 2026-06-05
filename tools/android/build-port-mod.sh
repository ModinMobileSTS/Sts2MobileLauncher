#!/usr/bin/env bash
# Build the editable Android port compatibility MOD and copy it into the Android
# shell's dotnet_bcl assets under the assembly name expected by the patched Godot runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

DOTNET_BIN="$(sts2_config_path DOTNET_BIN build.dotnet "${DOTNET_BIN:-}")"
REFERENCE_FLAVOR="${REFERENCE_FLAVOR:-$(sts2_config_value '' compat.default_reference_flavor original-v0.107.0)}"
COMPAT_REFERENCE_DIR="$(sts2_compat_reference_dir_for_flavor "$REFERENCE_FLAVOR")"
PROJECT="$ROOT/port-mod/STS2AndroidPortCompat/STS2Mobile.csproj"
OUTPUT="$ROOT/port-mod/STS2AndroidPortCompat/bin/Debug/net9.0/STS2Mobile.dll"
TARGET="$ROOT/android/assets/dotnet_bcl/STS2Mobile.dll"

sts2_require_executable "$DOTNET_BIN" "dotnet"
sts2_require_value "$COMPAT_REFERENCE_DIR" "compat reference dir for ReferenceFlavor=$REFERENCE_FLAVOR"
sts2_require_file "$COMPAT_REFERENCE_DIR/sts2.dll" "sts2.dll reference for ReferenceFlavor=$REFERENCE_FLAVOR"

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
"$DOTNET_BIN" build "$PROJECT" \
  -p:ReferenceFlavor="$REFERENCE_FLAVOR" \
  -p:CompatReferenceDir="$COMPAT_REFERENCE_DIR" \
  -p:_CompatGitBranch="$GIT_BRANCH" \
  -p:_CompatGitCommit="$GIT_COMMIT" \
  -p:_CompatGitCommitSubject="$GIT_SUBJECT" \
  -p:_CompatGitDirty="$GIT_DIRTY" \
  -p:_CompatBuildTimestampUtc="$BUILD_TIMESTAMP_UTC" \
  -v:q
if [[ ! -f "$OUTPUT" ]]; then
  echo "Expected build output missing: $OUTPUT" >&2
  exit 1
fi
mkdir -p "$(dirname "$TARGET")"
cp -f "$OUTPUT" "$TARGET"
sha256sum "$TARGET"
echo "Copied compat MOD to $TARGET"
"$ROOT/tools/android/make-port-overlay-pck.py"
