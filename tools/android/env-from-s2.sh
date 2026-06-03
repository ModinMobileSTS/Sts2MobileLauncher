#!/usr/bin/env bash
# Source this file before running Gradle/Android commands in s2_re.
#
# The script now loads tool paths from `.env` (see `.env.example`). It keeps the
# historical filename for compatibility with older documentation/scripts.
set -euo pipefail

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Usage: source tools/android/env-from-s2.sh" >&2
  exit 2
fi

S2_RE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$S2_RE_ROOT/tools/env/load-local-config.sh"
sts2_init_env || return 1

sts2_require_value "${JAVA_HOME:-}" "JAVA_HOME" || return 1
sts2_require_value "${ANDROID_HOME:-}" "ANDROID_HOME" || return 1
sts2_require_executable "${JAVA_HOME:-}/bin/javac" "javac" || return 1
sts2_require_dir "${ANDROID_HOME:-}/platforms/android-35" "Android SDK platform android-35" || return 1

if [[ -n "${DOTNET_BIN:-}" ]]; then
  echo "DOTNET_BIN=$DOTNET_BIN"
fi
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
