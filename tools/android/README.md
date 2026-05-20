# Android local build environment

This restructured project intentionally reuses the local Android toolchain that
already exists in the reference port (`../s2/tools/local_build_android_workflow.sh`):

- JDK: `../s2/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64`
- Android SDK: `../s2/.godot-home/Android/Sdk`

Use:

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

or source `tools/android/env-from-s2.sh` before running Android/Gradle commands.
The system `/usr/lib/jvm/java-21-openjdk-amd64` in this container is a JRE and
cannot compile Java sources; the reference project's local JDK includes `javac`.
