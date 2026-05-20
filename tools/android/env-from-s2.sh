#!/usr/bin/env bash
# Source this file before running Gradle/Android commands in s2_re.
# It reuses the local JDK and Android SDK prepared by ../s2/tools/local_build_android_workflow.sh.
set -euo pipefail

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Usage: source tools/android/env-from-s2.sh" >&2
  exit 2
fi

S2_RE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$S2_RE_ROOT/../s2" && pwd -P)"

export JAVA_HOME="$S2_REFERENCE_ROOT/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="$S2_REFERENCE_ROOT/.godot-home/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:${PATH:-}"
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo "Missing local javac at $JAVA_HOME/bin/javac" >&2
  return 1
fi
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
  echo "Missing Android SDK android-35 under $ANDROID_HOME" >&2
  return 1
fi

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
