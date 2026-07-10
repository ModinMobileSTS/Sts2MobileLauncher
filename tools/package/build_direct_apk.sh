#!/usr/bin/env bash
# Build a local direct-install APK by temporarily copying a game zip into assets.
# The zip is never committed; android/assets/payload/ is gitignored.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [/path/to/SlayTheSpire2.zip]" >&2
  exit 2
fi
ZIP_SRC="${1:-${STS2_PAYLOAD_ZIP:-}}"
if [[ -z "$ZIP_SRC" ]]; then
  echo "Missing payload zip. Pass it as an argument or set STS2_PAYLOAD_ZIP in .env." >&2
  exit 2
fi
ZIP_SRC="$(sts2_resolve_path "$ZIP_SRC")"
if [[ ! -f "$ZIP_SRC" ]]; then
  echo "Missing payload zip: $ZIP_SRC" >&2
  exit 1
fi

GRADLE_TASK="$(sts2_config_value GRADLE_TASK android.gradle.task assembleMonoRelease)"
APK="$(sts2_config_path APK_OUTPUT android.apk.output "$ROOT/android/build/outputs/apk/mono/release/sts2-re.apk")"
DIST_APK="$(sts2_config_path DIRECT_DIST_APK android.direct.dist "$ROOT/dist/sts2-re-direct.apk")"
RELEASE_KEYSTORE_FILE_VALUE="$(sts2_config_path RELEASE_KEYSTORE_FILE android.release_keystore_file "${HOME:-}/.android/debug.keystore")"
RELEASE_KEYSTORE_PASSWORD_VALUE="$(sts2_config_value RELEASE_KEYSTORE_PASSWORD android.release_keystore_password android)"
RELEASE_KEYSTORE_ALIAS_VALUE="$(sts2_config_value RELEASE_KEYSTORE_ALIAS android.release_keystore_alias androiddebugkey)"

python3 "$ROOT/tools/package/validate_payload_zip.py" "$ZIP_SRC"
"$ROOT/tools/android/sync-runtime-from-references.sh"
"$ROOT/tools/android/build-port-mod.sh"
"$ROOT/tools/android/stage-bundled-compat-artifacts.sh"
PAYLOAD_DIR="$ROOT/android/assets/payload"
mkdir -p "$PAYLOAD_DIR"
trap 'rm -f "$PAYLOAD_DIR/SlayTheSpire2.zip"' EXIT
cp -f "$ZIP_SRC" "$PAYLOAD_DIR/SlayTheSpire2.zip"

GRADLE_ARGS=("$GRADLE_TASK")
if [[ -n "$RELEASE_KEYSTORE_FILE_VALUE" ]]; then
  GRADLE_ARGS+=("-Prelease_keystore_file=$RELEASE_KEYSTORE_FILE_VALUE")
fi
if [[ -n "$RELEASE_KEYSTORE_PASSWORD_VALUE" ]]; then
  GRADLE_ARGS+=("-Prelease_keystore_password=$RELEASE_KEYSTORE_PASSWORD_VALUE")
fi
if [[ -n "$RELEASE_KEYSTORE_ALIAS_VALUE" ]]; then
  GRADLE_ARGS+=("-Prelease_keystore_alias=$RELEASE_KEYSTORE_ALIAS_VALUE")
fi
"$ROOT/tools/android/gradle-with-s2-env.sh" "${GRADLE_ARGS[@]}"

if [[ -f "$APK" ]]; then
  mkdir -p "$(dirname "$DIST_APK")"
  cp -f "$APK" "$DIST_APK"
  sha256sum "$APK"
  echo "Direct-install APK: $APK"
  echo "Stable direct APK: $DIST_APK"
else
  echo "Built, but expected APK was not found: $APK" >&2
  find "$ROOT/android/build/outputs" -type f -name '*.apk' -print 2>/dev/null || true
  exit 1
fi
