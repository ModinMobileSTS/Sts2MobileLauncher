#!/usr/bin/env bash
# Build the editable Android port compatibility MOD and copy it into the Android
# shell's dotnet_bcl assets under the assembly name expected by the patched Godot runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

DOTNET_BIN="$(sts2_config_path DOTNET_BIN build.dotnet "${DOTNET_BIN:-}")"
REFERENCE_FLAVOR="${REFERENCE_FLAVOR:-$(sts2_config_value '' compat.default_reference_flavor original-v0.110.0)}"
COMPAT_REFERENCE_DIR="$(sts2_compat_reference_dir_for_flavor "$REFERENCE_FLAVOR")"
PROJECT="$ROOT/port-mod/STS2AndroidPortCompat/STS2Mobile.csproj"
OUTPUT="$ROOT/port-mod/STS2AndroidPortCompat/bin/Debug/net9.0/STS2Mobile.dll"
TARGET="$ROOT/android/assets/dotnet_bcl/STS2Mobile.dll"

_sts2_compile_constants_for_flavor() {
  case "$1" in
    original)
      printf '%s\n' "STS2_TARGET_103X"
      ;;
    original-v0.106.1)
      printf '%s\n' "STS2_TARGET_1061"
      ;;
    original-v0.107.0)
      printf '%s\n' "STS2_TARGET_1070"
      ;;
    original-v0.107.1)
      printf '%s\n' "STS2_TARGET_1071"
      ;;
    original-v0.108.0)
      printf '%s\n' "STS2_TARGET_1080"
      ;;
    original-v0.109.0)
      printf '%s\n' "STS2_TARGET_1090"
      ;;
    original-v0.110.0)
      printf '%s\n' "STS2_TARGET_1100"
      ;;
    *)
      printf '%s\n' ""
      ;;
  esac
}

COMPILE_CONSTANTS="${COMPAT_COMPILE_CONSTANTS:-$(_sts2_compile_constants_for_flavor "$REFERENCE_FLAVOR")}"

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

msbuild_escape_property() {
  local value="$1"
  value="${value//'%'/'%25'}"
  value="${value//';'/'%3B'}"
  value="${value//','/'%2C'}"
  printf '%s' "$value"
}

GIT_BRANCH_MSBUILD="$(msbuild_escape_property "$GIT_BRANCH")"
GIT_SUBJECT_MSBUILD="$(msbuild_escape_property "$GIT_SUBJECT")"

MSBUILD_ARGS=(
  "$PROJECT"
  "-p:ReferenceFlavor=$REFERENCE_FLAVOR"
  "-p:CompatReferenceDir=$COMPAT_REFERENCE_DIR"
  "-p:_CompatGitBranch=$GIT_BRANCH_MSBUILD"
  "-p:_CompatGitCommit=$GIT_COMMIT"
  "-p:_CompatGitCommitSubject=$GIT_SUBJECT_MSBUILD"
  "-p:_CompatGitDirty=$GIT_DIRTY"
  "-p:_CompatBuildTimestampUtc=$BUILD_TIMESTAMP_UTC"
  "-v:q"
)
if [[ -n "$COMPILE_CONSTANTS" ]]; then
  MSBUILD_ARGS+=("-p:DefineConstants=$COMPILE_CONSTANTS")
fi
"$DOTNET_BIN" build "${MSBUILD_ARGS[@]}"
if [[ ! -f "$OUTPUT" ]]; then
  echo "Expected build output missing: $OUTPUT" >&2
  exit 1
fi
mkdir -p "$(dirname "$TARGET")"
cp -f "$OUTPUT" "$TARGET"
sha256sum "$TARGET"
echo "Copied compat MOD to $TARGET"
"$ROOT/tools/android/make-port-overlay-pck.py"
