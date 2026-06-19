# Android local build environment

Build scripts load machine-specific paths from the repository root `.env` file
and non-secret local options from `local.properties`:

```bash
cp .env.example .env
cp local.properties.example local.properties
# edit paths/options for your machine
```

Important variables in `.env`:

- `JAVA_HOME` — full JDK with `bin/javac`.
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` — Android SDK containing platform 35.
- `DOTNET_BIN` — .NET SDK executable used for `STS2Mobile.dll`.
- `STS2_ANDROID_RUNTIME_REFERENCE_ROOT` — reference Android template directory
  containing `libs/`, `assets/dotnet_bcl/`, and `gradle/wrapper/gradle-wrapper.jar`.
- `STS2_FMOD_PLUGIN_AAR`, `STS2_CRYPTO_NATIVE_JAR` — runtime artifacts copied
  by `sync-runtime-from-references.sh`.
- `STS2_ORIGINAL_V103_REFERENCE_DIR`, `STS2_ORIGINAL_V1061_REFERENCE_DIR`, `STS2_ORIGINAL_V1070_REFERENCE_DIR`, and `STS2_ORIGINAL_V1071_REFERENCE_DIR`
  (or their `*_ROOT` shortcuts) — original PC compile-gate DLL directories.

Use:

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

or source `tools/android/env-from-s2.sh` before running Android/Gradle commands.
The historical script names are kept for compatibility, but they no longer embed
machine-specific reference paths.
