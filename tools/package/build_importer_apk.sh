#!/usr/bin/env bash
# Build the current import-based APK shell. It does not embed a game zip.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
"$ROOT/tools/android/sync-runtime-from-references.sh"
"$ROOT/tools/android/stage-bundled-compat-packs.sh"
"$ROOT/tools/android/gradle-with-s2-env.sh" assembleMonoDebug
APK="$ROOT/android/build/outputs/apk/mono/debug/sts2-re.apk"
DIST_APK="$ROOT/dist/sts2-re-importer.apk"
if [[ -f "$APK" ]]; then
  mkdir -p "$ROOT/dist"
  cp -f "$APK" "$DIST_APK"
  sha256sum "$APK"
  echo "APK: $APK"
  echo "Stable importer APK: $DIST_APK"
else
  echo "Built, but expected APK was not found: $APK" >&2
  find "$ROOT/android/build/outputs" -type f -name '*.apk' -print 2>/dev/null || true
  exit 1
fi
