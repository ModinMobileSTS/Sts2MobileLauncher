#!/usr/bin/env bash
# Copy large Android runtime artifacts from reference projects into this working tree.
# These files are intentionally gitignored because they are generated/copied runtime
# dependencies, not source code or user-provided game payloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
S2_REFERENCE_ROOT="$(cd "$ROOT/../s2" && pwd -P)"
LAUNCHER_REFERENCE_ROOT="$S2_REFERENCE_ROOT/.cache/StS2-Launcher_Mod_Manager"
ANDROID_SRC="$LAUNCHER_REFERENCE_ROOT/android"
ANDROID_DST="$ROOT/android"

require_dir() {
  [[ -d "$1" ]] || { echo "Missing directory: $1" >&2; exit 1; }
}
require_file() {
  [[ -f "$1" ]] || { echo "Missing file: $1" >&2; exit 1; }
}

require_dir "$ANDROID_SRC/libs"
require_dir "$ANDROID_SRC/assets/dotnet_bcl"
require_file "$ANDROID_SRC/gradle/wrapper/gradle-wrapper.jar"
CRYPTO_JAR="$LAUNCHER_REFERENCE_ROOT/vendor/godot/modules/mono/thirdparty/libSystem.Security.Cryptography.Native.Android.jar"
FMOD_PLUGIN_AAR="$S2_REFERENCE_ROOT/addons/fmod/libs/android/fmod-release.aar"
FMOD_SHIM_SRC="$ROOT/tools/android/fmod-shim/org/fmod/FMOD.java"
LOCAL_JAVAC="$S2_REFERENCE_ROOT/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"
ANDROID_JAR="$S2_REFERENCE_ROOT/.godot-home/Android/Sdk/platforms/android-35/android.jar"
require_file "$CRYPTO_JAR"
require_file "$FMOD_PLUGIN_AAR"
require_file "$FMOD_SHIM_SRC"
require_file "$LOCAL_JAVAC"
require_file "$ANDROID_JAR"

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
