#!/usr/bin/env bash
# Build an Android-optimized STS2 payload zip while preserving original PC DLLs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=../env/load-local-config.sh
source "$REPO_ROOT/tools/env/load-local-config.sh"
sts2_init_env

exec python3 "$SCRIPT_DIR/build_android_body_zip.py" "$@"
