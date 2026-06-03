#!/usr/bin/env bash
# Build the current import-based APK shell. It does not embed a game zip.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

GRADLE_TASK="$(sts2_config_value GRADLE_TASK android.gradle.task assembleMonoRelease)"
APK="$(sts2_config_path APK_OUTPUT android.apk.output "$ROOT/android/build/outputs/apk/mono/release/sts2-re.apk")"
DIST_APK="$(sts2_config_path IMPORTER_DIST_APK android.importer.dist "$ROOT/dist/sts2-re-importer.apk")"
RELEASE_KEYSTORE_FILE_VALUE="$(sts2_config_path RELEASE_KEYSTORE_FILE android.release_keystore_file "${HOME:-}/.android/debug.keystore")"
RELEASE_KEYSTORE_PASSWORD_VALUE="$(sts2_config_value RELEASE_KEYSTORE_PASSWORD android.release_keystore_password android)"
RELEASE_KEYSTORE_ALIAS_VALUE="$(sts2_config_value RELEASE_KEYSTORE_ALIAS android.release_keystore_alias androiddebugkey)"

"$ROOT/tools/android/sync-runtime-from-references.sh"
"$ROOT/tools/android/build-port-mod.sh"
"$ROOT/tools/android/stage-bundled-compat-packs.sh"

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
  echo "APK: $APK"
  echo "Stable importer APK: $DIST_APK"
else
  echo "Built, but expected APK was not found: $APK" >&2
  find "$ROOT/android/build/outputs" -type f -name '*.apk' -print 2>/dev/null || true
  exit 1
fi
