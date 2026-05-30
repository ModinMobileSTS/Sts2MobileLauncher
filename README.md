# Slay the Spire 2 Android 重构移植版

> 非官方、实验性的 Android 移植/重构工程。
> 本仓库**不包含**《Slay the Spire 2》游戏本体、用户 payload、完整 Godot/Mono 运行时大文件或签名密钥；请仅使用你合法拥有的 PC 版游戏文件进行本地构建和测试。

## 项目简介

本项目将 Slay the Spire 2 Android 侧拆成三层维护：

1. **Android shell / launcher / 附加设置**
   APK 默认启动附加设置页，负责导入本地 PC 游戏 zip、管理私有目录、启动 Godot Activity、查看日志/文件、备份存档和管理 MOD。

2. **原版游戏 payload**
   用户本地提供 `SlayTheSpire2.zip`，导入到应用私有目录 `<files>/game/`。构建直装版 APK 时也可以临时内置 zip，但不会提交到仓库。

3. **移动端兼容插件 / compatibility pack**
   `port-mod/` 是独立 git 仓库 `../sts2-android-compat` 的 submodule。插件按游戏版本使用 git 分支维护，可导出为独立 zip 兼容包（`compat_manifest.json` + `STS2Mobile.dll` + `port_compat.pck`），由启动器安装、选择并在启动 Godot 前 staging。

核心目标不是维护一份完整重编译的游戏源码，而是让 Android 壳、用户本地游戏资源和移动端兼容补丁分离，便于后续多游戏版本、多兼容包维护、验证与更新。

## 当前状态

- Android 包名：`com.megacrit.sts2re`
- Java package / Godot bridge：`com.godot.game`
- 默认构建任务：`assembleMonoRelease`（release build type 当前仍保留 `debuggable true` 便于本地验证）
- ABI：`arm64-v8a`
- minSdk / targetSdk：`24 / 35`
- Android Gradle Plugin：`8.6.1`
- Gradle：`8.13`
- Kotlin：`2.1.20`
- NDK：`28.1.13356709`
- Java source/target：`17`

> 注意：当前 `release` 配置仍偏向本地调试验证，正式分发前需要重新审视签名、`debuggable`、混淆、资源优化和 FileProvider 暴露范围。

## 仓库结构

```text
s2_re/
  android/                         # Android shell / Godot Android Gradle 工程
    AndroidManifest.xml            # Activity、provider、权限；默认 launcher 为 GameSettingsActivity
    build.gradle                   # Godot Android template 风格应用模块配置
    config.gradle                  # AGP、SDK、NDK、Java 等版本配置
    gradle.properties              # applicationId、ABI、签名、构建类型等本地属性
    src/com/godot/game/            # Java shell、附加设置、payload 导入、GodotApp 桥
    res/                           # 附加设置、崩溃页、文件浏览器、图标、主题等资源
    assets/
      bootstrap.pck                # 无游戏 payload 时的最小 Godot bootstrap pack
      port_compat.pck              # legacy fallback overlay pack，脚本生成
      compat_packs/                # 内置兼容包 zip；当前包含正式/稳定与 beta 包
      dotnet_bcl/                  # 大型 .NET/Godot runtime DLL，生成/同步产物，gitignore
      payload/                     # 直装版临时内置 zip，gitignore
    libs/                          # Godot/FMOD/template AAR，生成/同步产物，gitignore

  port-mod/                        # git submodule: ../sts2-android-compat，多分支兼容补丁仓库
    STS2AndroidPortCompat/         # 兼容插件源码，输出 STS2Mobile.dll
    overlay/                       # 打包进 port_compat.pck 的 shader/resource overlay
    refs/                          # 本地 original compile gate 引用说明/symlink
    tools/build-compat-pack.sh     # 导出独立可安装兼容包 zip

  tools/
    android/                       # Android runtime 同步、Gradle 环境、兼容包构建/staging 脚本
    package/                       # APK 打包、payload zip 校验脚本
    diff/                          # 差异清单工具

  doc/                             # 新规范化文档入口：changelog、结构、构建、运行时加载流程
  docs/                            # 历史差异、验证、迁移文档
  dist/                            # APK 输出副本，gitignore
```

## 多版本与兼容包

启动器现在内置“版本”页，管理两类对象：

- **游戏本体版本**：导入的 PC zip 仍会激活到 `<files>/game/`，同时可归档到 `<files>/game-versions/<id>/game/`，之后可在版本页切换。
- **移动端兼容包**：安装到 `<files>/compat-packs/<pack_id>/`，每个包包含 manifest、`STS2Mobile.dll` 和 `port_compat.pck`。启动游戏前只按 payload manifest 中的游戏版本号自动匹配或检查当前选择的兼容包；不再因 `sts2.dll` SHA-256 不一致阻止启动。

内置兼容包位于：

```text
android/assets/compat_packs/*.zip
```

当前内置包列表由 `tools/android/bundled-compat-packs.json` 控制，包含正式/稳定 `v0.103.2`（`compat/v0.103.2`，参考目录 `../s2_original/s21032/`）和 beta `v0.106.1`（`compat/v0.106.1-beta`，参考目录 `../s2_original/s201061/`）。

## 不提交的内容

以下内容为本地私有输入、生成产物或大型运行时文件，不应提交：

- 用户的 `SlayTheSpire2.zip`
- 解压后的完整游戏 payload
- `android/assets/dotnet_bcl/`
- `android/libs/`
- `android/assets/payload/`
- `dist/`、`*.apk`、`*.aab`、`*.apks`
- keystore / jks / p12 等签名密钥
- .NET `bin/` / `obj/`

## 构建前准备

当前脚本默认复用相邻参考工程中的本机环境和运行时产物。期望目录大致如下：

```text
../s2/                                      # 旧 Android 移植版/参考工程
../s2_original/s21032/                      # v0.103.2 正式/稳定 PC 原版/解包基线
../s2_original/s201061/                     # v0.106.1 beta PC 原版/解包基线
../s2_pc/Slay the Spire 2.zip               # 本地测试用 PC 游戏 zip，可替换为你自己的路径
```

脚本会读取的关键本地工具/资源包括：

```text
../s2/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64
../s2/.godot-home/Android/Sdk
../s2/.local/dotnet/dotnet
../s2/addons/fmod/libs/android/fmod-release.aar
../s2/.cache/StS2-Launcher_Mod_Manager/
```

如果你的本地目录不同，请先调整 `tools/android/env-from-s2.sh`、`tools/android/sync-runtime-from-references.sh` 等脚本中的参考路径。

常用依赖：

- Bash
- Python 3
- rsync
- Android SDK / NDK
- JDK
- .NET SDK
- arm64 Android 设备或模拟器

建议始终通过项目脚本构建，不要直接使用系统 Java/Gradle；本仓库的脚本会使用参考工程提供的 JDK、Android SDK 和 .NET SDK。

## 快速开始

### 1. 校验本地 PC 游戏 zip

```bash
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"
```

zip 至少需要包含：

```text
SlayTheSpire2.pck
release_info.json
data_sts2_windows_x86_64/sts2.dll
data_sts2_windows_x86_64/sts2.deps.json
data_sts2_windows_x86_64/sts2.runtimeconfig.json
```

脚本还会检查 `SlayTheSpire2.pck` 的 PCK magic，并输出 zip 的 sha256 与 `release_info.json` 信息。

### 2. 构建导入版 APK

导入版 APK 不内置游戏资源。用户安装后，在附加设置页选择本地 `SlayTheSpire2.zip` 导入。

```bash
tools/package/build_importer_apk.sh
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-importer.apk
```

### 3. 构建直装版 APK

直装版会在构建时临时把本地 zip 复制到 `android/assets/payload/SlayTheSpire2.zip`，首次启动时自动解压到应用私有目录。该 zip 会在脚本退出时自动删除，不应提交。

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-direct.apk
```

### 4. 安装和验证

```bash
adb install -r dist/sts2-re-importer.apk
# 或
adb install -r dist/sts2-re-direct.apk
```

常用检查命令：

```bash
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/default/1
adb shell run-as com.megacrit.sts2re ls files/game
```

推荐 smoke test：

1. 首次打开进入欢迎向导/附加设置页，而不是直接进游戏。
2. 导入版能选择并导入本地 PC zip。
3. 导入成功后，`files/game/.payload_manifest.json` 和 `files/game/SlayTheSpire2.pck` 存在。
4. 点击启动游戏后，logcat 能看到加载 imported game PCK 的日志。
5. 附加设置中的图形、输入、MOD 设置能写入 `files/default/1/settings.save`。
6. 游戏内返回附加设置、日志页、文件浏览器、崩溃页不崩溃。
7. MOD 总开关和单 MOD 禁用能在启动日志中反映。

## 常用开发命令

同步大型运行时产物：

```bash
tools/android/sync-runtime-from-references.sh
```

构建并 stage 当前 submodule 兼容插件到 legacy assets fallback：

```bash
tools/android/build-port-mod.sh
```

构建并复制内置兼容包 zip 到 `android/assets/compat_packs/`：

```bash
tools/android/stage-bundled-compat-packs.sh
```

只编译 Java / Gradle 检查：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

只构建兼容 MOD：

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -v:q
```

使用 PC 原版程序集做 compile gate，检查是否误依赖旧移植版专属 API。0.106.1 beta 目标使用：

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q
```

旧 0.103.2 基线可使用：

```bash
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:q
```

重新生成 Android 适配 overlay pack：

```bash
tools/android/make-port-overlay-pck.py
```

## 运行时目录约定

应用私有目录中的关键路径：

```text
<files>/game/                              # 当前启用的游戏 payload
<files>/game/SlayTheSpire2.pck             # 主 PCK
<files>/game/release_info.json             # 游戏 release 信息
<files>/game/.payload_manifest.json        # 导入 manifest，含版本 / sts2.dll sha256 identity
<files>/game-versions/<id>/game/            # 版本管理器归档的游戏本体
<files>/compat-packs/<pack_id>/             # 已安装移动端兼容包
<files>/launcher/selected_compat_pack.json  # 当前兼容包选择记录
<files>/default/1/settings.save             # 附加设置 / 游戏设置
<files>/mods/                              # 本地 MOD 目录
<files>/.godot/mono/publish/arm64/          # Godot/Mono publish 目录
```

关键流程：

- `GameSettingsActivity` 是默认 launcher，负责首次向导和附加设置。
- `PayloadManager` 负责 SAF 选择 zip、assets 内置 payload 解压、校验、staging、原子替换和 rollback。
- `GodotApp` 是真正的 Godot 游戏 Activity：
  - 有 `<files>/game/SlayTheSpire2.pck` 时传入 `--main-pack`。
  - 无 payload 时使用 `assets/bootstrap.pck`。
  - 常规启动由 `GameSettingsActivity` 先在后台同步 APK assets 中的 `dotnet_bcl`、所选兼容包和导入 payload 中的 `data_*/*.dll` 到 Godot publish 目录，避免在 Activity 主线程做大文件复制。
- `STS2Mobile.dll` / `STS2Mobile.ModEntry` 是 patched Godot runtime 期望加载的 Android 兼容 MOD 入口。

## 兼容插件概览

`port-mod/` 作为独立 submodule 负责在运行时通过 Harmony patch 适配 Android 环境。不同游戏版本通过该独立仓库的分支维护，并使用 `tools/build-compat-pack.sh` 导出可安装兼容包。当前覆盖方向包括：

- 禁用或替代桌面 Steam / Sentry / platform 路径
- 从 Android 私有目录读取 `release_info.json`
- 桥接附加设置中的 Android-only 配置
- 调整显示、分辨率、FPS、UI scale、横屏方向等移动端选项
- 将本地 MOD 路径重定向到 `<files>/mods`
- 跳过 Steam Workshop 枚举
- 加载 `port_compat.pck` 中的 shader/resource overlay
- 适配触摸输入、返回键、手柄 trigger axis、双指 inspect 等输入行为
- 增加游戏内打开附加设置、退出回设置页、快速重开等入口
- 桥接 LAN / ENet 多人相关设置

更多细节见：[`port-mod/README.md`](port-mod/README.md)。

## 故障排查

### 缺少 `android/assets/dotnet_bcl` 或 `android/libs`

先同步运行时：

```bash
tools/android/sync-runtime-from-references.sh
```

### Java 编译失败或提示系统 JDK 不完整

不要裸跑系统 Gradle。使用：

```bash
tools/android/gradle-with-s2-env.sh assembleMonoDebug
```

或使用打包脚本：

```bash
tools/package/build_importer_apk.sh
```

### payload zip 校验失败

请确认选择的是 PC 版游戏 zip，并且包含 `SlayTheSpire2.pck`、`release_info.json` 和 `data_sts2_windows_x86_64/` 下的 .NET 入口文件。

### 直装版构建后担心 zip 被提交

`android/assets/payload/` 和 `*.zip` 已被 `.gitignore` 排除；脚本也会在退出时删除临时复制的 `SlayTheSpire2.zip`。仍建议提交前检查：

```bash
git status --short
```

### 修改 overlay 后没有生效

重新生成 `port_compat.pck`，或直接重新构建兼容 MOD：

```bash
tools/android/build-port-mod.sh
```

## 维护注意事项

- 修改 Java bridge 包名或类名要谨慎，C# 侧和兼容 MOD 默认寻找 `com.godot.game.GodotApp`。
- 修改附加设置 key 时，需要同步 Java 的设置仓库、UI 和 `port-mod` 中的 `AndroidSettingsBridge` / patches。
- 修改 `port-mod/overlay` 后需要重新生成 `android/assets/port_compat.pck`，并重新导出/复制内置兼容包。
- 修改 `tools/android/make-bootstrap-pck.py` 后需要重新生成 `android/assets/bootstrap.pck`。
- 改包名时需要同步 shortcuts、FileProvider、manifest、Gradle 配置和所有 hard-coded target package。
- 正式发布前应重新配置签名、版本号、release build type、安全策略和版权合规流程。

## 相关文档

- [`AGENTS.md`](AGENTS.md)：面向后续编码代理/维护者的完整项目速览
- [`doc/`](doc/)：规范化文档入口（changelog、项目结构、构建、兼容包/MOD 加载流程）
- [`doc/runtime/compat-pack-loading-flow.md`](doc/runtime/compat-pack-loading-flow.md)：Android 兼容包与普通 MOD 详细加载流程
- [`port-mod/README.md`](port-mod/README.md)：Android 兼容插件 / compat pack 说明
- [`docs/inventory/`](docs/inventory/)：旧移植版与 PC 原版差异清单
- [`docs/validation/`](docs/validation/)：阶段性验证记录

## 免责声明

本项目为非官方移植/兼容性研究工程，不隶属于或代表 Mega Crit、Slay the Spire 2 或 Godot 官方。仓库不提供、分发或授权分发任何商业游戏资源。请遵守相关软件许可、平台规则和当地法律，仅在你合法拥有游戏副本的前提下进行本地构建与测试。
