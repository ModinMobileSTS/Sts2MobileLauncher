#!/usr/bin/env bash
# Run Gradle in android/ with the JDK/Android SDK from ../s2.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$ROOT/../s2" && pwd -P)"
export JAVA_HOME="$S2_REFERENCE_ROOT/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="$S2_REFERENCE_ROOT/.godot-home/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:${PATH:-}"
export LD_LIBRARY_PATH="$JAVA_HOME/lib/server${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo "Missing local javac at $JAVA_HOME/bin/javac" >&2
  exit 1
fi
cd "$ROOT/android"
exec ./gradlew --no-daemon "$@"
