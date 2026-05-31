# 构建与打包流程

## 1. 本地环境

构建脚本默认复用相邻参考工程 `../s2/` 的本机工具链：

```text
../s2/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64
../s2/.godot-home/Android/Sdk
../s2/.local/dotnet/dotnet
../s2/addons/fmod/libs/android/fmod-release.aar
../s2/.cache/StS2-Launcher_Mod_Manager/
```

不要裸用系统 Java/Gradle。使用：

```bash
tools/android/gradle-with-s2-env.sh <gradle-task>
```

或：

```bash
source tools/android/env-from-s2.sh
```

## 2. Gradle/Android 配置

当前配置：

- AGP：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin：`2.1.20`
- compileSdk/targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- Java source/target：`17`
- flavor：`mono`
- 默认 build type：`release`，脚本执行 `assembleMonoRelease`
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`0.1.0` / `1`

`release` build type 当前保留 `debuggable true`，并在脚本中默认使用 debug keystore 参数给 release APK 签名，便于本地 sideload 和 `run-as` 验证。正式发布前必须重新配置签名和安全策略。

## 3. 同步大型 runtime

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容：

- Godot Android template AAR/native libs
- `.NET/Godot` BCL/runtime DLL
- crypto native jar
- FMOD AAR，并应用 `tools/android/fmod-shim/` 中的 Java shim
- Gradle wrapper jar

这些产物位于 `android/assets/dotnet_bcl/`、`android/libs/` 等 gitignored 路径，不手工维护。

## 4. 构建当前 compat fallback

```bash
tools/android/build-port-mod.sh
```

用途：构建当前 `port-mod` checkout 分支，并 stage legacy fallback 到 APK assets：

```text
android/assets/dotnet_bcl/STS2Mobile.dll
android/assets/port_compat.pck
```

默认 `REFERENCE_FLAVOR=original-v0.106.1`，适合当前 `compat/v0.106.1-beta` 分支。正式/稳定分支可显式：

```bash
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
```

## 5. 构建内置兼容包

```bash
tools/android/stage-bundled-compat-packs.sh
```

脚本读取：

```text
tools/android/bundled-compat-packs.json
```

当前会构建：

- `compat/v0.103.2` → `sts2-android-compat-v0.103.2.zip`
- `compat/v0.106.1-beta` → `sts2-android-compat-v0.106.1-beta.zip`

非当前分支使用 `.agent/worktrees/compat-packs/<id>/` 临时 worktree 构建；当前分支可直接使用 dirty worktree 方便测试。为了让所有内置分支都支持当前 launch profile / `selected_instance.json` 路径桥、安全 deferred preload 修正，以及关闭预加载时的 transition material 防黑屏修正，staging 脚本会把当前 `STS2AndroidPortCompat/Android/AppPaths.cs`、`SavePathPatches.cs`、`LifecycleAndPerformancePatches.cs`、`TransitionMaterialPatches.cs` 同步到临时 worktree 后再构建。输出到：

```text
android/assets/compat_packs/*.zip
```

## 6. 构建导入版 APK

导入版不内置游戏 zip：

```bash
tools/package/build_importer_apk.sh
```

流程：

1. 同步 runtime。
2. 构建当前 compat fallback。
3. 构建/刷新全部内置 compat pack zip。
4. 执行 `assembleMonoRelease`。
5. 复制 APK。

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-importer.apk
```

## 7. 构建直装版 APK

直装版临时内置本地 PC zip：

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

流程：

1. `validate_payload_zip.py` 校验 zip。
2. 同步 runtime。
3. 构建当前 compat fallback。
4. 构建/刷新全部内置 compat pack zip。
5. 临时复制 zip 到 `android/assets/payload/SlayTheSpire2.zip`。
6. 执行 `assembleMonoRelease`。
7. 复制 APK，脚本退出时删除临时 zip。

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-direct.apk
```

## 8. 只做局部检查

```bash
# Java 编译检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# beta compile gate
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.106.1 -v:q

# v0.103.2 compile gate
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original -v:q
```

## 9. 构建后基本验证

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/compat-packs
adb shell run-as com.megacrit.sts2re ls files/.godot/mono/publish/arm64
```

首次启动应进入附加设置；导入 payload 后启动应在日志中看到：

- selected compatibility pack 信息；
- `Loading imported game PCK`；
- `STS2Mobile` build info；
- ordinary MOD scan from the current launch profile MOD root（全局 `<files>/mods` 或隔离 `<files>/instances/<profile_id>/mods`，如启用 MOD）。
