#!/usr/bin/env bash
# Verify the staged Godot AAR input-pool repair without running an Android device.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

JAVAC="${JAVA_HOME:-}/bin/javac"
JAVAP="${JAVA_HOME:-}/bin/javap"
ANDROID_JAR="${ANDROID_HOME:-}/platforms/android-35/android.jar"
sts2_require_executable "$JAVAC" "javac"
sts2_require_executable "$JAVAP" "javap"
sts2_require_file "$ANDROID_JAR" "Android platform jar"

if (($# > 0)); then
  AARS=("$@")
else
  REFERENCE_ROOT="$(sts2_config_path STS2_ANDROID_RUNTIME_REFERENCE_ROOT runtime.android_reference_root "${STS2_ANDROID_RUNTIME_REFERENCE_ROOT:-}")"
  sts2_require_dir "$REFERENCE_ROOT" "Android runtime reference root"
  AARS=()
  for variant in debug release; do
    AARS+=("$REFERENCE_ROOT/libs/$variant/godot-lib.template_$variant.aar")
  done
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for source in "${AARS[@]}"; do
  sts2_require_file "$source" "Godot template AAR"
  name="$(basename "$source")"
  aar="$tmp/$name"
  cp -f "$source" "$aar"
  python3 "$ROOT/tools/android/patch-godot-input-pool.py" "$aar" \
    --javac "$JAVAC" --android-jar "$ANDROID_JAR"
  python3 "$ROOT/tools/android/patch-godot-input-pool.py" "$aar" --check

  classes="$tmp/$name.classes.jar"
  unzip -p "$aar" classes.jar > "$classes"
  wrapper="$($JAVAP -classpath "$classes" -c -p org.godotengine.godot.input.GodotInputHandler)"
  base="$($JAVAP -classpath "$classes" -c -p org.godotengine.godot.input.GodotInputHandlerBase)"
  case "$wrapper" in
    *InputEventRunnable*)
      echo "wrapper still references pooled input events: $source" >&2
      exit 1
      ;;
    *GodotInputHandlerBase*) ;;
    *)
      echo "wrapper does not delegate to the renamed base handler: $source" >&2
      exit 1
      ;;
  esac
  case "$base" in
    *InputEventRunnable.obtain*) ;;
    *)
      echo "base handler no longer contains the original dispatch path: $source" >&2
      exit 1
      ;;
  esac
done

echo "Godot input event pool regression passed for ${#AARS[@]} AAR(s)."
