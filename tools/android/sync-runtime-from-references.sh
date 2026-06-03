#!/usr/bin/env bash
# Copy large Android runtime artifacts from reference projects into this working tree.
# These files are intentionally gitignored because they are generated/copied runtime
# dependencies, not source code or user-provided game payloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT/tools/env/load-local-config.sh"
sts2_init_env

ANDROID_SRC="$(sts2_config_path STS2_ANDROID_RUNTIME_REFERENCE_ROOT runtime.android_reference_root "${STS2_ANDROID_RUNTIME_REFERENCE_ROOT:-}")"
ANDROID_DST="$ROOT/android"
CRYPTO_JAR="$(sts2_config_path STS2_CRYPTO_NATIVE_JAR runtime.crypto_native_jar "${STS2_CRYPTO_NATIVE_JAR:-}")"
FMOD_PLUGIN_AAR="$(sts2_config_path STS2_FMOD_PLUGIN_AAR runtime.fmod_plugin_aar "${STS2_FMOD_PLUGIN_AAR:-}")"
FMOD_SHIM_SRC="$ROOT/tools/android/fmod-shim/org/fmod/FMOD.java"
LOCAL_JAVAC="${JAVA_HOME:-}/bin/javac"
ANDROID_JAR="${ANDROID_HOME:-}/platforms/android-35/android.jar"

sts2_require_value "$ANDROID_SRC" "runtime.android_reference_root / STS2_ANDROID_RUNTIME_REFERENCE_ROOT"
sts2_require_value "$CRYPTO_JAR" "runtime.crypto_native_jar / STS2_CRYPTO_NATIVE_JAR"
sts2_require_value "$FMOD_PLUGIN_AAR" "runtime.fmod_plugin_aar / STS2_FMOD_PLUGIN_AAR"
sts2_require_value "${JAVA_HOME:-}" "JAVA_HOME"
sts2_require_value "${ANDROID_HOME:-}" "ANDROID_HOME"
sts2_require_dir "$ANDROID_SRC/libs" "reference Android libs directory"
sts2_require_dir "$ANDROID_SRC/assets/dotnet_bcl" "reference dotnet_bcl directory"
sts2_require_file "$ANDROID_SRC/gradle/wrapper/gradle-wrapper.jar" "reference Gradle wrapper jar"
sts2_require_file "$CRYPTO_JAR" "crypto native jar"
sts2_require_file "$FMOD_PLUGIN_AAR" "FMOD plugin AAR"
sts2_require_file "$FMOD_SHIM_SRC" "FMOD shim source"
sts2_require_executable "$LOCAL_JAVAC" "javac"
sts2_require_file "$ANDROID_JAR" "Android platform jar"

mkdir -p "$ANDROID_DST/libs" "$ANDROID_DST/assets" "$ANDROID_DST/gradle/wrapper"
rsync -a --delete "$ANDROID_SRC/libs/" "$ANDROID_DST/libs/"
rsync -a --delete "$ANDROID_SRC/assets/dotnet_bcl/" "$ANDROID_DST/assets/dotnet_bcl/"
cp -f "$ANDROID_SRC/gradle/wrapper/gradle-wrapper.jar" "$ANDROID_DST/gradle/wrapper/gradle-wrapper.jar"
cp -f "$CRYPTO_JAR" "$ANDROID_DST/libs/debug/libSystem.Security.Cryptography.Native.Android.jar"
cp -f "$CRYPTO_JAR" "$ANDROID_DST/libs/release/libSystem.Security.Cryptography.Native.Android.jar"
# Use the release FMOD Android plugin even for monoDebug APKs. The plugin
# BuildConfig.DEBUG branch tries to load libfmodL/libfmodstudioL debug
# libraries, while the packaged Godot/FMOD runtime uses libfmod/libfmodstudio.
cp -f "$FMOD_PLUGIN_AAR" "$ANDROID_DST/libs/debug/fmod-release.aar"
rm -f "$ANDROID_DST/libs/debug/fmod-debug.aar"

patch_fmod_aar() {
  local aar="$1"
  local work
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  mkdir -p "$work/classes"
  "$LOCAL_JAVAC" -source 17 -target 17 -cp "$ANDROID_JAR" -d "$work/classes" "$FMOD_SHIM_SRC"
  python3 - "$aar" "$work/classes/org/fmod/FMOD.class" <<'PYEOF'
import io
import os
import sys
import tempfile
import zipfile

aar_path = sys.argv[1]
shim_class_path = sys.argv[2]
with open(shim_class_path, 'rb') as fh:
    shim_class = fh.read()

fd, tmp_path = tempfile.mkstemp(prefix=os.path.basename(aar_path), suffix='.tmp', dir=os.path.dirname(aar_path) or '.')
os.close(fd)
try:
    with zipfile.ZipFile(aar_path, 'r') as src, zipfile.ZipFile(tmp_path, 'w') as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == 'libs/fmod.jar':
                jar_in = io.BytesIO(data)
                jar_out = io.BytesIO()
                replaced = False
                with zipfile.ZipFile(jar_in, 'r') as jsrc, zipfile.ZipFile(jar_out, 'w') as jdst:
                    for jitem in jsrc.infolist():
                        jdata = jsrc.read(jitem.filename)
                        if jitem.filename == 'org/fmod/FMOD.class':
                            jdata = shim_class
                            replaced = True
                        jdst.writestr(jitem, jdata)
                    if not replaced:
                        jdst.writestr('org/fmod/FMOD.class', shim_class)
                data = jar_out.getvalue()
            dst.writestr(item, data)
    os.replace(tmp_path, aar_path)
finally:
    if os.path.exists(tmp_path):
        os.unlink(tmp_path)
PYEOF
  rm -rf "$work"
  trap - RETURN
}

patch_fmod_aar "$ANDROID_DST/libs/debug/fmod-release.aar"
patch_fmod_aar "$ANDROID_DST/libs/release/fmod-release.aar"

printf 'Synced runtime artifacts from %s to %s\n' "$ANDROID_SRC" "$ANDROID_DST"
