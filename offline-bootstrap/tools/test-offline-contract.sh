#!/usr/bin/env bash
# Validate the reflection-only ModelDb contract resolver against synthetic API shapes
# and every locally configured original sts2.dll reference.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPO_ROOT="$(cd "$ROOT/.." && pwd -P)"
# shellcheck disable=SC1091
source "$REPO_ROOT/tools/env/load-local-config.sh"
sts2_init_env

DOTNET_BIN="${DOTNET_BIN:-dotnet}"
if command -v sts2_config_path >/dev/null 2>&1; then
  DOTNET_BIN="$(sts2_config_path DOTNET_BIN build.dotnet "$DOTNET_BIN")"
fi
PROJECT="$ROOT/tests/STS2OfflineBootstrap.ContractTests/STS2OfflineBootstrap.ContractTests.csproj"
SYNTHETIC_ONLY=0
if [[ "${1:-}" == "--synthetic-only" ]]; then
  SYNTHETIC_ONLY=1
  shift
fi
if [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--synthetic-only]" >&2
  exit 2
fi
if [[ "$DOTNET_BIN" != "dotnet" && ! -x "$DOTNET_BIN" ]]; then
  echo "Missing dotnet: $DOTNET_BIN" >&2
  exit 1
fi

args=()
if [[ "$SYNTHETIC_ONLY" != "1" ]]; then
  declare -A seen=()
  references=(
    "v0.103.x:STS2_ORIGINAL_V103_REFERENCE_DIR"
    "v0.106.1:STS2_ORIGINAL_V1061_REFERENCE_DIR"
    "v0.107.0:STS2_ORIGINAL_V1070_REFERENCE_DIR"
    "v0.107.1:STS2_ORIGINAL_V1071_REFERENCE_DIR"
    "v0.108.0:STS2_ORIGINAL_V1080_REFERENCE_DIR"
    "v0.109.x:STS2_ORIGINAL_V1090_REFERENCE_DIR"
  )
  for reference in "${references[@]}"; do
    label="${reference%%:*}"
    variable="${reference#*:}"
    reference_dir="${!variable:-}"
    if [[ -z "$reference_dir" || ! -f "$reference_dir/sts2.dll" ]]; then
      echo "Offline contract: skip $label ($variable is not configured with sts2.dll)."
      continue
    fi
    dll="$(realpath "$reference_dir/sts2.dll")"
    if [[ -z "${seen[$dll]:-}" ]]; then
      seen[$dll]=1
      args+=(--assembly "$dll")
    fi
  done
fi

"$DOTNET_BIN" run --project "$PROJECT" -v:q -- "${args[@]}"
