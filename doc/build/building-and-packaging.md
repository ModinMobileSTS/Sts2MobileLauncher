# 构建与打包流程

## 1. 本地配置入口

构建脚本通过仓库根目录的两个本地文件读取机器相关配置：

```bash
cp .env.example .env
cp local.properties.example local.properties
```

- `.env`：工具链路径、外部参考 runtime/original DLL、私有 payload zip、签名环境变量等；已加入 `.gitignore`。
- `local.properties`：非 secret 的本地构建选项，例如 Gradle task、输出 APK 路径、compat pack staging 目录；已加入 `.gitignore`。

详细字段说明见 [`local-configuration.md`](local-configuration.md)。路径可以是绝对路径，也可以是相对仓库根目录的相对路径。

如需自动准备可公开 clone 的 GitHub 参考项目，可运行：

```bash
tools/deps/prepare-external-projects.sh
```

它会初始化 `port-mod` submodule，并把默认参考仓库 clone 到 `.agent/reference-repos/`（或 `local.properties` 的 `deps.external_projects_root`）。`.agent/` 是本地 agent/参考资料目录，已 gitignore，不应提交。商业游戏 payload、original DLL、keystore 和准备好的 Godot/Mono runtime 仍需你在 `.env` 中配置本地路径。

## 2. 必需工具与本地输入

常用依赖：

- Bash
- Python 3
- rsync
- JDK（必须有 `javac`，Java 17+；本地常用 JDK 21）
- Android SDK / NDK / CMake
- .NET SDK
- Godot 4.5.1 Mono 与 export templates（仅在需要重新导入资源、生成 Android 优化本体包时使用）
- arm64 Android 设备或模拟器（运行验证用）

`.env` 中至少需要配置：

```bash
JAVA_HOME=/path/to/jdk
ANDROID_HOME=/path/to/android-sdk
DOTNET_BIN=/path/to/dotnet
STS2_ANDROID_RUNTIME_REFERENCE_ROOT=/path/to/reference/android-template
STS2_FMOD_PLUGIN_AAR=/path/to/fmod-release.aar
STS2_CRYPTO_NATIVE_JAR=/path/to/libSystem.Security.Cryptography.Native.Android.jar
STS2_ORIGINAL_V103_REFERENCE_DIR=/path/to/v0.103.x/bin/Debug
STS2_ORIGINAL_V1061_REFERENCE_DIR=/path/to/v0.106.1/bin/Debug
STS2_ORIGINAL_V1070_REFERENCE_DIR=/path/to/v0.107.0/bin/Debug
```

`STS2_ORIGINAL_*_REFERENCE_DIR` 目录需包含 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll`。这些是 compile gate 引用，不提交到仓库。

如果你有统一的参考工程根目录，也可以设置 `STS2_REFERENCE_ROOT` / `STS2_LAUNCHER_REFERENCE_ROOT`，让脚本派生默认子路径；详见 `.env.example`。

## 3. Gradle/Android 配置

当前配置：

- AGP：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin：`2.1.20`
- Steam 子模块：`android/steam-protocol`、`android/steam-content`（Kotlin JVM + protobuf，用于 Steam 登录/SteamPipe 下载）
- 主要依赖：JavaSteam `1.6.0`、OkHttp `5.3.2`、protobuf `4.31.1`、AndroidX Security Crypto、Android Prefab zstd、XZ；许可证速览、直接参考仓库与发布前合规检查见 `THIRD_PARTY_LICENSES.md`
- compileSdk/targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- CMake：`3.22.1`（用于 `libworkshop_zstd.so` JNI wrapper）
- Java source/target：`17`
- flavor：`mono`
- 默认 build type：`release`，脚本执行 `assembleMonoRelease`
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`v0.1.1` / `2`

`release` build type 当前保留 `debuggable true`，便于本地 sideload 和 `run-as` 验证。package 脚本默认使用 `RELEASE_KEYSTORE_*` 或 `local.properties` 中的签名配置；本地测试可使用 Android debug keystore，正式发布前必须重新配置签名和安全策略。

## 4. 同步大型 runtime

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容：

- Godot Android template AAR/native libs
- `.NET/Godot` BCL/runtime DLL
- crypto native jar
- FMOD AAR，并应用 `tools/android/fmod-shim/` 中的 Java shim
- Gradle wrapper jar

这些产物位于 `android/assets/dotnet_bcl/`、`android/libs/` 等 gitignored 路径，不手工维护。长期源码化状态和剩余阻塞见 [`source-dependencies.md`](source-dependencies.md)。

## 5. 构建当前 compat fallback

```bash
tools/android/build-port-mod.sh
```

用途：构建当前 `port-mod` checkout 分支，并 stage legacy fallback 到 APK assets：

```text
android/assets/dotnet_bcl/STS2Mobile.dll
android/assets/port_compat.pck
```

默认 `ReferenceFlavor` 来自 `local.properties` 的 `compat.default_reference_flavor`（示例为 `original-v0.107.0`）。可临时覆盖：

```bash
# 当前 beta v0.107.0
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh
# 旧 beta v0.106.1
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh
# stable v0.103.x
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
```

脚本会从 `.env` 解析对应的 `STS2_ORIGINAL_*_REFERENCE_DIR`，并通过 MSBuild 属性 `CompatReferenceDir` 传入，不依赖 `port-mod/refs` 中的个人 symlink。

## 6. 构建内置兼容包

```bash
tools/android/stage-bundled-compat-packs.sh
```

默认使用 flat matrix 模式：脚本读取 `port-mod/targets/active/*/target.json`，从当前 checkout 依次用各目标的 `ReferenceFlavor` 编译，输出一个 schema 2 family 兼容包：

```text
android/assets/compat_packs/sts2-android-compat.zip
  compat_manifest.json
  variants/<target_id>/STS2Mobile.dll
  variants/<target_id>/port_compat.pck
  SHA256SUMS
```

当前 active targets：

- `v0.103.x`（支持 `v0.103.2` / `v0.103.3`，`ReferenceFlavor=original`）
- `v0.106.1-beta`（旧 beta，`ReferenceFlavor=original-v0.106.1`）
- `v0.107.0-beta`（当前 beta，`ReferenceFlavor=original-v0.107.0`）

输出到：

```text
android/assets/compat_packs/*.zip
```

该目录下的 zip 是构建产物，已 gitignore，不再由 git 跟踪；提交前不要 `git add -f` 这些 zip。

legacy 分支构建模式仍可显式启用，用于回退诊断或对照旧发布包：

```bash
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh
```

legacy 模式读取 `tools/android/bundled-compat-packs.json`（或 `local.properties` 的 `compat.bundled_packs_config`），非当前分支使用 `compat.worktree_root`（默认 `.agent/worktrees/compat-packs/`）临时 worktree 构建；当前分支可直接使用 dirty worktree 方便测试。

也可以直接在 submodule 中局部构建 matrix 包：

```bash
cd port-mod
./tools/build-compat-matrix.sh --target v0.107.0-beta
./tools/build-compat-matrix.sh
```

`--target` 只构建单个 target；不带参数会构建 `targets/active/` 下所有目标。未来停止维护旧版本时，将对应目录移到 `targets/archived/`，默认 matrix 构建就不会再内置它；需要临时导出 legacy 目标时可用 `BUILD_ARCHIVED_TARGETS=1`。

## 7. 生成启动器 Material Symbols 矢量图

启动器原生 Android UI 使用 `MaterialSymbols` helper 加载 `android/res/drawable/ic_ms_*.xml`。这些文件由 bundled `android/res/font/material_symbols_rounded.ttf` 离线生成，保留 Google Material Symbols Rounded 官方轮廓，但运行时不再依赖系统字体 ligature，避免部分 ROM（例如关闭 MIUI 优化后）把图标显示成 `settings` / `play_arrow` 这类 glyph 名称。

新增或修改 `MaterialSymbols` 的 glyph 映射后，刷新并检查生成资源：

```bash
# 推荐用本地 venv；.agent/ 已 gitignore，不应提交
python3 -m venv .agent/venv-fonttools
.agent/venv-fonttools/bin/pip install fonttools
.agent/venv-fonttools/bin/python tools/android/generate-material-symbol-vectors.py
.agent/venv-fonttools/bin/python tools/android/generate-material-symbol-vectors.py --check
```

如果本机 Python 允许用户级安装，也可以直接运行：

```bash
python3 -m pip install --user fonttools
tools/android/generate-material-symbol-vectors.py
tools/android/generate-material-symbol-vectors.py --check
```

## 8. 构建导入版 APK

导入版不内置游戏 zip：

```bash
tools/package/build_importer_apk.sh
```

流程：

1. 同步 runtime。
2. 构建当前 compat fallback。
3. 构建/刷新全部内置 compat pack zip 到 gitignored 的 `android/assets/compat_packs/`。
4. 执行 `local.properties` 中的 `android.gradle.task`（默认 `assembleMonoRelease`）。
5. 复制 APK 到 `android.importer.dist`（默认 `dist/sts2-re-importer.apk`）。

正式 APK 默认采用高刷新兼容模式：manifest 不声明 `android:appCategory="game"` / `android:isGame="true"`，`GodotApp` 会在启动、恢复前台、获得焦点和 Godot 主循环开始后向 `WindowManager.LayoutParams.preferredDisplayModeId`、`preferredRefreshRate`、`Surface.setFrameRate()` 与 `SurfaceControl.Transaction.setFrameRate()` 请求当前显示尺寸下最高刷新率。设置页“系统”分区提供默认关闭的“Show performance overlay”开关；开启后下次启动会加载 `godot-debug-menu`，显示 FPS、帧时间、CPU/GPU frame graph 和渲染器/硬件信息。

## 9. 构建直装版 APK

直装版临时内置本地 PC zip：

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

也可以在 `.env` 设置 `STS2_PAYLOAD_ZIP` 后无参数调用：

```bash
tools/package/build_direct_apk.sh
```

流程：

1. `validate_payload_zip.py` 校验 zip。
2. 同步 runtime。
3. 构建当前 compat fallback。
4. 构建/刷新全部内置 compat pack zip 到 gitignored 的 `android/assets/compat_packs/`。
5. 临时复制 zip 到 `android/assets/payload/SlayTheSpire2.zip`。
6. 执行 Gradle task。
7. 复制 APK 到 `android.direct.dist`（默认 `dist/sts2-re-direct.apk`），脚本退出时删除临时 zip。

## 10. 生成 Android 优化本体 zip（可选）

如果同时拥有某个版本的 PC 原版 zip 与匹配的 Godot 源码/反导出工程，可以重新用 Android export preset 导入资源，生成更适合移动端导入的本体 zip：

```bash
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/SlayTheSpire2.zip" \
  --source-dir "/path/to/sts2-godot-source" \
  --out "dist/payload/sts2-vX.Y.Z-android-body.zip"
```

该脚本的原则：

- `data_sts2_windows_x86_64/sts2.dll`、`sts2.deps.json`、`sts2.runtimeconfig.json` 来自 PC zip 原版，不用重新编译出的 DLL，避免 IL 变化影响 MOD。
- `SlayTheSpire2.pck` 由临时 Godot 工程通过 Android `--export-pack` 重新生成，启用 ETC2/ASTC 导入，并过滤 PC 用的 BPTC/S3TC 纹理、`.godot/mono` publish、桌面 native runtime 等。
- 字体 import 会在临时工程中关闭 MSDF 大 fontdata 设置，以接近移植版体积；Sentry autoload/GDExtension 在临时工程中禁用。
- 产物仍满足 `validate_payload_zip.py` 与当前 launcher 导入格式，可在导入版 APK 中当普通 payload zip 导入；输入 PC zip 若包含单一顶层目录（例如 `Slay the Spire 2/...`），脚本会自动识别并在输出 zip 中展平。

常用本地示例：

```bash
# v0.103.3：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/v0.103.3/SlayTheSpire2.zip" \
  --source-dir "/path/to/s201033" \
  --out "dist/payload/sts2-v0.103.3-android-body.zip"

# v0.107.0 beta：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.107.0.zip" \
  --source-dir "/path/to/s201070" \
  --out "dist/payload/sts2-v0.107.0-android-body.zip"
```

临时工程和 Godot 日志默认写入 `.agent/tmp/android-body-build/`，该目录不入库；生成的 zip 应留在 `dist/` 或其他本地输出目录，不要提交。

## 11. 局部检查

```bash
# Java/Kotlin/Steam 子模块编译检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# beta compile gate（通过脚本自动传 CompatReferenceDir）
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# v0.103.x / 旧 beta compile gate
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh

# 查看/准备 GitHub 外部参考项目
tools/deps/prepare-external-projects.sh --list
tools/deps/prepare-external-projects.sh --group modding-reference

# 检查 Material Symbols vector drawable 是否已按当前 glyph 映射刷新
tools/android/generate-material-symbol-vectors.py --check

# standalone dotnet 编译也可显式传入 CompatReferenceDir
"$DOTNET_BIN" build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.107.0 \
  -p:CompatReferenceDir="$STS2_ORIGINAL_V1070_REFERENCE_DIR" -v:q
```

## 12. 构建后基本验证

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

Steam 相关验证可额外检查：

```bash
adb shell run-as com.megacrit.sts2re ls files/steam
adb shell run-as com.megacrit.sts2re ls files/steam/cloud
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name .payload_manifest.json
```
