#!/usr/bin/env bash
# Stage all bundled compatibility artifacts into Android assets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

ASSET_DIR="$(sts2_config_path COMPAT_PACK_ASSET_DIR compat.asset_dir "$ROOT/android/assets/compat_packs")"
OFFLINE_ROOT="$(sts2_config_path OFFLINE_BOOTSTRAP_ROOT offline_bootstrap.root "$ROOT/offline-bootstrap")"
OFFLINE_ENABLED="$(sts2_config_value OFFLINE_BOOTSTRAP_ENABLED offline_bootstrap.enabled 1)"

mkdir -p "$ASSET_DIR"
rm -f "$ASSET_DIR"/*.zip

COMPAT_PACK_SKIP_ASSET_CLEAN=1 "$ROOT/tools/android/stage-bundled-compat-packs.sh"

if [[ "$OFFLINE_ENABLED" == "1" || "$OFFLINE_ENABLED" == "true" || "$OFFLINE_ENABLED" == "yes" ]]; then
  if [[ ! -x "$OFFLINE_ROOT/tools/build-offline-pack.sh" ]]; then
    echo "Missing offline bootstrap build script: $OFFLINE_ROOT/tools/build-offline-pack.sh" >&2
    exit 1
  fi
  "$OFFLINE_ROOT/tools/build-offline-pack.sh"
  cp -f "$OFFLINE_ROOT"/dist/offline-bootstrap/*.zip "$ASSET_DIR"/
fi

sha256sum "$ASSET_DIR"/*.zip
