#!/usr/bin/env bash
# Run Gradle in android/ with paths loaded from `.env` (see `.env.example`).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env
sts2_require_value "${JAVA_HOME:-}" "JAVA_HOME"
sts2_require_value "${ANDROID_HOME:-}" "ANDROID_HOME"
sts2_require_executable "${JAVA_HOME:-}/bin/javac" "javac"
sts2_require_dir "${ANDROID_HOME:-}/platforms/android-35" "Android SDK platform android-35"
cd "$ROOT/android"
exec ./gradlew --no-daemon "$@"
