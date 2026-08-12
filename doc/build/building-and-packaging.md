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
STS2_ORIGINAL_V1071_REFERENCE_DIR=/path/to/v0.107.1/bin/Debug
STS2_ORIGINAL_V1080_REFERENCE_DIR=/path/to/v0.108.0/bin/Debug
# Historical V1090 name gates the shared v0.109.x target; point it at the latest v0.109.1 reference.
STS2_ORIGINAL_V1090_REFERENCE_DIR=/path/to/v0.109.1/bin/Debug
# Historical V1100 flavor; current shared v0.110.x gate uses v0.110.1.
STS2_ORIGINAL_V1100_REFERENCE_DIR=/path/to/v0.110.1/bin/Debug
```

`STS2_ORIGINAL_*_REFERENCE_DIR` 目录需包含 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll`。这些是 compile gate 引用，不提交到仓库。

如果你有统一的参考工程根目录，也可以设置 `STS2_REFERENCE_ROOT` / `STS2_LAUNCHER_REFERENCE_ROOT`，让脚本派生默认子路径；详见 `.env.example`。

## 3. Gradle/Android 配置

当前配置：

- AGP：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin：`2.1.20`
- Steam 子模块：`android/steam-protocol`、`android/steam-content`（Kotlin JVM + protobuf，用于 Steam 登录/SteamPipe 下载）
- 主要依赖：JavaSteam `1.6.0`、OkHttp `5.3.2`、protobuf `4.31.1`、AndroidX Security Crypto、Android Prefab zstd、XZ；app module 启用 `desugar_jdk_libs 2.0.3` core-library desugaring，使 Steam 下载/云同步使用的 `java.time` 在 minSdk 24/25 可用。许可证速览、直接参考仓库与发布前合规检查见 `THIRD_PARTY_LICENSES.md`
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
- versionName/versionCode：`v0.1.8` / `110`

`release` build type 当前保留 `debuggable true`，便于本地 sideload 和 `run-as` 验证。package 脚本默认使用 `RELEASE_KEYSTORE_*` 或 `local.properties` 中的签名配置；本地测试可使用 Android debug keystore，正式发布前必须重新配置签名和安全策略。

## 4. 同步大型 runtime

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容：

- Godot Android template AAR/native libs
- `.NET/Godot` BCL/runtime DLL
- crypto native jar
- FMOD AAR，并应用 `tools/android/fmod-shim/` 中的 Java shim；该 shim 补齐旧 native runtime 需要的 URI 文件描述符、耳机插拔和音频设备枚举回调。同步脚本会替换全部生成的 `FMOD*.class`，并在写回后校验 AAR 内的 `libs/fmod.jar`，缺少目标 jar 或 class 时直接终止构建
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

默认 `ReferenceFlavor` 来自 `local.properties` 的 `compat.default_reference_flavor`（示例为 `original-v0.110.0`）。可临时覆盖：

```bash
# 当前稳定版 v0.108.0
REFERENCE_FLAVOR=original-v0.108.0 tools/android/build-port-mod.sh

# 当前 public-beta v0.110.x（历史 flavor 名称）
REFERENCE_FLAVOR=original-v0.110.0 tools/android/build-port-mod.sh
# v0.109.x（历史 flavor 名，引用使用最新 v0.109.1）
REFERENCE_FLAVOR=original-v0.109.0 tools/android/build-port-mod.sh
# stable v0.107.1
REFERENCE_FLAVOR=original-v0.107.1 tools/android/build-port-mod.sh
# 旧 beta v0.107.0
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh
# 旧 beta v0.106.1
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh
# stable v0.103.x
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
```

脚本会从 `.env` 解析对应的 `STS2_ORIGINAL_*_REFERENCE_DIR`，并通过 MSBuild 属性 `CompatReferenceDir` 传入，不依赖 `port-mod/refs` 中的个人 symlink。

## 6. 构建内置兼容包

完整 APK 打包使用统一 staging 入口：

```bash
tools/android/stage-bundled-compat-artifacts.sh
```

该脚本会先清理 `android/assets/compat_packs/*.zip`，再依次 stage：

```text
android/assets/compat_packs/sts2-android-compat.zip
android/assets/compat_packs/sts2-android-offline-bootstrap.zip
```

`sts2-android-compat.zip` 是 full compatibility family 包。它默认使用 flat matrix 模式：脚本读取 `port-mod/targets/active/*/target.json`，从当前 checkout 依次用各目标的 `ReferenceFlavor` 编译，输出一个 schema 2 family 兼容包：

```text
android/assets/compat_packs/sts2-android-compat.zip
  compat_manifest.json
  variants/<target_id>/STS2Mobile.dll
  variants/<target_id>/port_compat.pck
  SHA256SUMS
```

`sts2-android-offline-bootstrap.zip` 由 `offline-bootstrap/tools/build-offline-pack.sh` 构建。它不静态引用 `sts2.dll`，manifest 使用 `pack_kind=offline-bootstrap`、`match_mode=offline-wildcard` 与 `versions=["*"]`，启动器只会在没有任何已安装 full compat 包按 SHA/version 匹配当前 payload 时自动推荐它。构建脚本会先运行 `offline-bootstrap/tools/test-offline-contract.sh`：合成测试覆盖可接受与必须拒绝的 `ModelDb.Init` API 形状，并对本机已配置的所有 original reference 做反射契约检查。当前离线包使用 `compat_version=0.2.0-dev`、`probe_contract=offline-bootstrap-v2`；首次用它启动某个 pack/version/SHA 组合时弹风险确认，真实 ModelDb two-phase 成功后才标记 `ready`，已知终态失败的同一组合不再自动匹配。

compat 与 offline bootstrap 构建脚本会把 Git branch / commit / commit subject 写入构建元数据和 manifest；传给 MSBuild 的 branch/subject 会先转义逗号、分号和百分号，避免提交标题中的标点被 MSBuild 当成多个 `-p:` 属性解析。

可单独运行离线契约验证：

```bash
offline-bootstrap/tools/test-offline-contract.sh
```

如果只需要构建 full compatibility family 包，可直接运行：

```bash
tools/android/stage-bundled-compat-packs.sh
```

当前 active targets：

- `v0.103.x`（支持 `v0.103.2` / `v0.103.3`，`ReferenceFlavor=original`）
- `v0.106.1-beta`（旧 beta，`ReferenceFlavor=original-v0.106.1`）
- `v0.107.0-beta`（旧 beta，`ReferenceFlavor=original-v0.107.0`）
- `v0.107.1`（stable，`ReferenceFlavor=original-v0.107.1`）
- `v0.108.0`（当前稳定版，`ReferenceFlavor=original-v0.108.0`）
- `v0.109.0`（稳定 target id，显示为 v0.109.x，同时支持 v0.109.0/v0.109.1；历史 `ReferenceFlavor=original-v0.109.0` 指向最新 v0.109.1 引用）
- `v0.110.0` / `v0.110.1`（当前 public beta，共享 API/protocol target id `v0.110.0`，`ReferenceFlavor=original-v0.110.0`，当前 gate 使用 v0.110.1 引用）

同一 variant 覆盖多个 API-compatible 原版 DLL 时，target 的 `sts2_dll_sha256` 可声明为字符串数组；构建脚本会原样写入 schema 2 manifest，启动器会遍历全部元素做精确 SHA 匹配。旧单字符串 manifest 继续兼容。

输出到：

```text
android/assets/compat_packs/*.zip
```

该目录下的 zip 是构建产物，已 gitignore，不再由 git 跟踪；提交前不要 `git add -f` 这些 zip。

新版 APK 安装内置 matrix 包后，启动器会把旧 bundled schema 1 包选择迁移到 `sts2-android-compat` family 包的对应 target。新建启动配置的自动匹配也会在同等版本/SHA 命中时优先使用 schema 2 family 包，避免继续绑定旧单目标包。

legacy 分支构建模式仍可显式启用，用于回退诊断或对照旧发布包：

```bash
COMPAT_PACK_BUILD_MODE=legacy tools/android/stage-bundled-compat-packs.sh
```

legacy 模式读取 `tools/android/bundled-compat-packs.json`（或 `local.properties` 的 `compat.bundled_packs_config`），非当前分支使用 `compat.worktree_root`（默认 `.agent/worktrees/compat-packs/`）临时 worktree 构建；当前分支可直接使用 dirty worktree 方便测试。stage 脚本还会把共享热修源码注入 legacy worktree；该列表必须包含 `ModEntry.cs` 新增的直接依赖，目前包括 `DevTools/*.cs`、`RunHistoryPatches.cs`、`CombatAnimationWarmupPatches.cs` 和资源释放保护所需的 `AndroidAssetCacheLifecyclePatches.cs`。

也可以直接在 submodule 中局部构建 matrix 包：

```bash
cd port-mod
./tools/build-compat-matrix.sh --target v0.110.0
./tools/build-compat-matrix.sh
```

`--target` 只构建单个 target；不带参数会构建 `targets/active/` 下所有目标。未来停止维护旧版本时，将对应目录移到 `targets/archived/`，默认 matrix 构建就不会再内置它；需要临时导出 legacy 目标时可用 `BUILD_ARCHIVED_TARGETS=1`。

## 7. 游戏版本更新后的 port-mod 语法树审计

游戏更新后，可先把旧/新版本的 GDRE C# 源码导出目录放在仓库外，再运行语法树级别审计工具，快速找出原版类型、方法签名、方法体和 `port-mod` Harmony/反射触达点的变化：

```bash
tools/port_mod_ast_audit.py \
  --old-source ../s2_original/s201091 \
  --new-source ../s2_original/s201100 \
  --port-mod port-mod/STS2AndroidPortCompat \
  --out .agent/reports/v110-port-mod-ast-audit
```

输出目录包含：

- `summary.md`：人工阅读入口，优先列出 `port-mod` 命中的缺失目标、签名变化和实现变化。
- `port_mod_refs.csv`：每个 Harmony/反射引用的解析结果，可按 `status` 过滤。
- `member_changes.csv`：两个源码版本之间的类型成员变化清单。
- `audit.json`：完整机器可读结果。

该工具是纯 Python，无第三方依赖；它不编译源码，也不会修改 `port-mod`。`dynamic_type_expression` 表示目标类型来自 `__instance.GetType()` 或外部 MOD/库，需要人工按调用上下文确认；`external_or_self_reference` 表示系统库、Harmony/Godot 或 `port-mod` 自身引用，通常不是游戏版本迁移风险。

## 8. 生成启动器 Material Symbols 矢量图

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

## 9. 构建导入版 APK

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

正式 APK 默认声明 `android:appCategory="game"` / `android:isGame="true"`，让 OEM 游戏/GPU 调度识别 `GodotApp`；同时采用高刷新兼容模式。`GodotApp` 在启动、恢复前台、获得焦点和 Godot 主循环开始后只向 Activity 级 `HighRefreshRateController` 发起 generation 请求；控制器仅在 Activity resumed + focused 且 Godot `SurfaceView`/`Surface` 有效时实际应用，并在失去焦点、pause、destroy 或 Surface 销毁后取消延迟工作。控制器同时跟踪 surface epoch，以 100/500/1500ms 有限重试等待有效 Surface；Android 12+ 对每个 epoch 只调用一次 `Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)`。显式高刷 mode 使用对应 `preferredDisplayModeId`，仅有 alternative refresh rate 时清空 mode ID 并使用 `preferredRefreshRate`；随后延迟验证实际 mode/Hz，不使用 `SurfaceControl`。设置页“系统”分区提供默认关闭的“Show performance overlay”开关；开启后下次启动会加载 `godot-debug-menu`，显示 FPS、帧时间、CPU/GPU frame graph 和渲染器/硬件信息。

Java 不再把 `fullscreen_render_size` 转换为 Godot `--resolution`。根窗口始终使用 `CanvasItems`：Auto 比例取 UI scale target，固定比例取对应 fixed target，`global_scale` 独立作为 `ContentScaleFactor`。游戏内切换 `fullscreen_render_size` 后，compat 会先完成高层 `ContentScale*` setter，再只用 `RenderingServer.ViewportSetRenderDirectToScreen(false)`、`ViewportSetSize()` 与 `ViewportSetGlobalCanvasTransform()` 即时调整根 renderer RT；scene Window、输入变换与 Android Surface 均不改变，也不使用 `SurfaceHolder.setFixedSize()` 或 `ViewportAttachToScreen()`。`0x0` 恢复 native attachment 尺寸与原始 canvas transform；非零预设按当前 native attachment 比例以 Expand 语义覆盖请求矩形，例如 `2400x1080` 设备选择 `1280x720` 时实际 target 为 `1600x720`。自定义目标长边上限为 `max(4096, native 长边)`。根 Window `SizeChanged`、resume 与 ContentScale repair 后会重投 renderer 状态，因此动态切换前后内容相对大小与触控坐标保持不变，高刷 Surface 生命周期也不受分辨率切换影响。

## 10. 构建直装版 APK

直装版临时内置本地 PC zip。启动器会按内置 zip 的 SHA-256 判断当前 APK 自带本体是否已导入；如果用户从旧直装版升级且只导入过旧 APK 的本体，新 APK 首次进入主界面后会自动导入当前内置 zip，并为新 payload 创建/选择默认启动配置。

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

## 11. 生成 Android 优化本体 zip（可选）

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
- 字体 import 会在临时工程中关闭 MSDF 大 fontdata 设置，以接近移植版体积；Sentry autoload/GDExtension 会同时在 `project.godot` 与 `project.binary` 中禁用。
- 临时工程会从 `.tscn` / `.tres` 引用合成缺失的 `.uid` sidecar，确保重导出的 `.godot/uid_cache.bin` 继续匹配原场景里的 `uid://...` 引用，避免 Android 首帧资源绑定崩溃。
- Spine `.atlas` / `.skel` 导入产物是平台无关资源；若 headless Linux export 环境没有 Spine editor importer，脚本会从源工程 `.godot/imported` 注入 `.spatlas` / `.spskel` 与对应 `.atlas.import` / `.skel.import` remap 到最终 PCK，并在缺失时失败，避免主菜单、地图节点或战斗场景黑屏。
- 产物仍满足 `validate_payload_zip.py` 与当前 launcher 导入格式，可在导入版 APK 中当普通 payload zip 导入；输入 PC zip 若包含单一顶层目录（例如 `Slay the Spire 2/...`），脚本会自动识别并在输出 zip 中展平。
- 源工程 patch 同时禁用旧 `SentryInit` 与 v0.110.x 使用的 C# `SentryBootstrap` autoload，并移除桌面 Sentry GDExtension；managed dependency keep-list 从原版 `sts2.deps.json` 推导，因此 v0.110.x 的 `Sentry.Godot.dll` 会和原版 `sts2.dll` 一起保留。

常用本地示例：

```bash
# v0.103.3：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/v0.103.3/SlayTheSpire2.zip" \
  --source-dir "/path/to/s201033" \
  --out "dist/payload/sts2-v0.103.3-android-body.zip"

# v0.108.0 当前稳定版：PC zip + 对应源码/反导出工程
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.108.0.zip" \
  --source-dir "/path/to/s201080" \
  --out "dist/payload/sts2-v0.108.0-android-body.zip"

# v0.109.1 旧 public beta
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.109.1.zip" \
  --source-dir "/path/to/s201091" \
  --out "dist/payload/sts2-v0.109.1-android-body.zip"

# v0.110.x 当前 public beta（稳定 target id 仍为 v0.110.0）
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/sts2-v0.110.1.zip" \
  --source-dir "/path/to/s201101" \
  --out "dist/payload/sts2-v0.110.1-android-body.zip"
```

临时工程和 Godot 日志默认写入 `.agent/tmp/android-body-build/`，该目录不入库；生成的 zip 应留在 `dist/` 或其他本地输出目录，不要提交。

## 12. 局部检查

```bash
# Steam 可恢复认证协议单元测试
tools/android/gradle-with-s2-env.sh :steam-protocol:test

# Java/Kotlin/Steam 子模块编译检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# 窗口生命周期 / ContentScale 静态回归约束
! rg 'GodotLib|dispatchImmediateGodotFocusChange' \
  android/src/com/godot/game/GodotApp.java
! rg 'NotificationWMWindowFocusIn|NotificationApplicationFocusIn' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
! rg 'window\.ContentScale(Mode|Aspect|Size|Factor)\s*=' \
  port-mod/STS2AndroidPortCompat/Patches/UiScalePatches.cs
! rg 'CustomRender|ContentScaleModeEnum\.Viewport' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
rg 'ContentScaleModeEnum\.CanvasItems' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
rg 'ViewportSetRenderDirectToScreen|ViewportSetSize|ViewportSetGlobalCanvasTransform' \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs
! rg -- '--resolution' \
  android/src/com/godot/game/GodotApp.java
rg 'CHANGE_FRAME_RATE_ALWAYS' \
  android/src/com/godot/game/HighRefreshRateController.java
rg 'preferredDisplayModeId' \
  android/src/com/godot/game/HighRefreshRateController.java
rg 'preferredRefreshRate' \
  android/src/com/godot/game/HighRefreshRateController.java
! rg 'SurfaceControl|setFixedSize|ViewportAttachToScreen' \
  android/src/com/godot/game/HighRefreshRateController.java \
  android/src/com/godot/game/GodotApp.java \
  port-mod/STS2AndroidPortCompat/Patches/DisplaySettingsPatches.cs

# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# 当前 public-beta v0.110.x compile gate（当前引用为 v0.110.1）
REFERENCE_FLAVOR=original-v0.110.0 tools/android/build-port-mod.sh

# 旧 v0.109.x compile gate（历史 flavor 名，CompatReferenceDir 指向 v0.109.1）
REFERENCE_FLAVOR=original-v0.109.0 tools/android/build-port-mod.sh

# v0.107.1 / v0.103.x / 旧 beta compile gate
REFERENCE_FLAVOR=original-v0.107.1 tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# 查看/准备 GitHub 外部参考项目
tools/deps/prepare-external-projects.sh --list
tools/deps/prepare-external-projects.sh --group modding-reference

# 检查 Material Symbols vector drawable 是否已按当前 glyph 映射刷新
tools/android/generate-material-symbol-vectors.py --check

# standalone dotnet 编译也可显式传入 CompatReferenceDir
"$DOTNET_BIN" build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.110.0 \
  -p:CompatReferenceDir="$STS2_ORIGINAL_V1100_REFERENCE_DIR" -v:q
```

## 13. ADB 自动化调试

连接设备后可用 `tools/debug/sts2-adb-debug.sh` 直接安装、配置、导入测试文件、执行启动准备、启动游戏并采集日志/性能 trace：

```bash
tools/debug/sts2-adb-debug.sh build-install
tools/debug/sts2-adb-debug.sh status --pull
tools/debug/sts2-adb-debug.sh prepare --mode compat --clear publish --pull
tools/debug/sts2-adb-debug.sh launch --mode perf --preload aggressive --logcat-duration 45 --perfetto 45 --pull
```

脚本默认使用 `run-as com.megacrit.sts2re` 把本地 payload/compat/MOD 文件推入 app 私有 `files/automation/inbox/<run_id>/`，再触发应用内 `DebugAutomationActivity` 复用正常导入/准备/启动逻辑。结果拉回 `.agent/debug/runs/<run_id>/`，设备侧结果保存在 `<files>/automation/runs/<run_id>/`。精确测试某个 MOD、某个 compat target、某组 preload 设置的示例见 [`adb-automation-debugging.md`](adb-automation-debugging.md)。

## 14. 构建后基本验证

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
adb shell dumpsys activity services com.megacrit.sts2re
```

Steam credential auth 必须用装有 Steam App 的实机做以下场景，不能只用“页面没崩”或小窗模式代替：

1. 输入账号密码并进入手机确认后，确认认证前台通知已出现、服务已经开始轮询；直接按 Home 或打开 Steam App 批准，再从最近任务返回启动器，应自动完成登录，不应要求先点“已批准”，也不应要求小窗/分屏。
2. 等待确认时反复前后台切换、旋转或让系统重建 `SteamAccountActivity`；`onStop()` 只应解绑 UI，通知和认证事务继续，返回后 UI 重新附着到当前状态。
3. BeginAuth 已创建 transaction handle 后切换 Wi-Fi/移动网络，或短暂断网再恢复；CM WebSocket 断开后应建立新连接并复用既有 client/request id 继续轮询，而不是要求重新输入密码。
4. 事务等待期间把应用放到后台后执行 `adb shell am kill com.megacrit.sts2re`，等待系统恢复服务或手动重新打开 Steam 中心；未过期 handle 应恢复轮询。`am force-stop` 是用户显式强停，Android 不保证立即重启服务，但重新打开应用后仍应恢复尚未过期的 handle。
5. 分别验证手机令牌动态码和邮箱验证码。Guard code 只用于当次进程内提交；若进程在提交前死亡，允许要求重新输入 code，但磁盘、Intent 和 logcat 中不得出现明文。提交成功进入轮询后，后续恢复不应再次需要密码。
6. 在等待确认/验证码时点取消，确认前台通知消失且 pending transaction 被清理，既有已登录账号不受影响；让事务超过默认 4 分钟 deadline，确认同样清理且不会继续后台轮询。
7. 取消旧登录后立刻开始新登录，并制造旧请求迟到成功；只有当前 transaction generation 可以原子提交 refresh token，旧结果不得覆盖新账号或新事务。
8. Android 13+ 拒绝通知权限后重复登录；系统可能不在通知栏展示普通通知，但认证状态和可恢复事务不应因此损坏，返回 Steam 中心仍可继续观察或取消。

验证期间同时检查 `adb logcat` 和 app 私有偏好中不存在账号密码、加密密码或本次 Guard code 明文。允许加密保存的内容只有既有登录材料与短期 transaction handle；认证成功必须在同一次 generation-matched commit 中写入 refresh token 并删除 handle，取消、过期或 handle 损坏只删除 pending 状态。
