# M1/M2 Android shell validation snapshot

Date: 2026-05-20

Implemented in this snapshot:

- Copied old extra-settings Java/resources into `android/` with package
  `com.godot.game` retained for Java bridge compatibility.
- `GameSettingsActivity` remains the only launcher activity; `GodotApp` is the
  secondary game/runtime activity.
- Added Java-side `PayloadManager` for local zip import into `<files>/game` with:
  - SAF zip selection from Game page;
  - temporary app-private cached source zip and SHA-256;
  - safe zip extraction with canonical path guard;
  - staging directory and backup/rollback promotion;
  - validation for `SlayTheSpire2.pck` (`GDPC`), `release_info.json`,
    `data_sts2_windows_x86_64/sts2.dll`, `.deps.json`, `.runtimeconfig.json`;
  - `.payload_manifest.json` with source/game/compat metadata;
  - clear/reimport actions.
- Added bundled/direct-install path: if `android/assets/payload/SlayTheSpire2.zip`
  exists and no ready payload is installed, the settings shell auto-extracts it.
- `GodotApp` now uses `<files>/game/SlayTheSpire2.pck` as `--main-pack`, falls
  back to `bootstrap.pck`, and copies dotnet BCL + imported game assemblies to
  `.godot/mono/publish/arm64`.
- Build helper scripts reuse the local JDK/Android SDK from `../s2/tools/local_build_android_workflow.sh`.

Validation run:

```text
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
  => BUILD SUCCESSFUL

python3 tools/package/validate_payload_zip.py "../s2_pc/Slay the Spire 2.zip"
  => size 1872326043, sha256 86175dd93c69dc75b40c6f5dae9d2cffabeff10052bc12acd4a0256115a1eb03,
     release v0.103.2 / commit 89765e1e

python3 tools/package/validate_payload_zip.py /tmp/s2_re_payload_test/valid_payload.zip
  => exit 0
python3 tools/package/validate_payload_zip.py /tmp/s2_re_payload_test/invalid_payload.zip
  => exit 1 (invalid PCK magic)
python3 tools/package/validate_payload_zip.py /tmp/s2_re_payload_test/missing_payload.zip
  => exit 1 (missing required entries)

tools/package/build_importer_apk.sh
  => BUILD SUCCESSFUL
  => APK: android/build/outputs/apk/mono/debug/sts2-re_monoDebug.apk
  => SHA-256: 91fa0f3e537f1c650b9f95c600689110a0f5f3042245c14b3b316cbe68bd65b2

tools/package/build_direct_apk.sh /tmp/s2_re_payload_test/valid_payload.zip
  => BUILD SUCCESSFUL with a tiny synthetic bundled payload
  => APK: android/build/outputs/apk/mono/debug/sts2-re_monoDebug.apk
  => SHA-256: 644d1002c4f0db87c9854bc1baf927a1fd8db1c136ad7b790fcaebd8ff7f1481
  => temporary android/assets/payload/SlayTheSpire2.zip removed by trap
```

Not yet validated on device:

- First-run welcome UI and settings persistence via `adb run-as`.
- SAF import of the real 1.8G zip on Android.
- Auto-extraction of a bundled 1.8G zip in an installed direct APK.
- Actual game startup to menu; port compatibility MOD is not implemented yet.
