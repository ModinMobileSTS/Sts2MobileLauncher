#!/usr/bin/env bash
# Build a local direct-install APK by temporarily copying a game zip into assets.
# The zip is never committed; android/assets/payload/ is gitignored.
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/SlayTheSpire2.zip" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ZIP_SRC="$1"
if [[ ! -f "$ZIP_SRC" ]]; then
  echo "Missing payload zip: $ZIP_SRC" >&2
  exit 1
fi
python3 "$ROOT/tools/package/validate_payload_zip.py" "$ZIP_SRC"
"$ROOT/tools/android/sync-runtime-from-references.sh"
PAYLOAD_DIR="$ROOT/android/assets/payload"
mkdir -p "$PAYLOAD_DIR"
trap 'rm -f "$PAYLOAD_DIR/SlayTheSpire2.zip"' EXIT
cp -f "$ZIP_SRC" "$PAYLOAD_DIR/SlayTheSpire2.zip"
"$ROOT/tools/android/gradle-with-s2-env.sh" assembleMonoDebug
APK="$ROOT/android/build/outputs/apk/mono/debug/sts2-re_monoDebug.apk"
if [[ -f "$APK" ]]; then
  sha256sum "$APK"
  echo "Direct-install APK: $APK"
else
  echo "Built, but expected APK was not found: $APK" >&2
  find "$ROOT/android/build/outputs" -type f -name '*.apk' -print 2>/dev/null || true
  exit 1
fi
