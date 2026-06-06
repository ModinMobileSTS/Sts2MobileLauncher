# Slay the Spire 2 Android 重构移植版

> 非官方、实验性的 Android 移植/重构工程。
> 本仓库**不包含**《Slay the Spire 2》游戏本体、用户 payload、完整 Godot/Mono 运行时大文件或签名密钥；请仅使用你合法拥有的 PC 版游戏文件进行本地构建和测试。

## 项目简介

本项目将 Slay the Spire 2 Android 侧拆成三层维护：

1. **Android shell / launcher / 附加设置**
   APK 默认启动附加设置页，负责导入本地 PC 游戏 zip、Steam 登录/游戏下载/云存档、管理私有目录、启动 Godot Activity、查看日志/文件、备份存档和管理 MOD。

2. **原版游戏 payload**
   用户本地提供 `SlayTheSpire2.zip`，或使用自己拥有 STS2 的 Steam 账号从 SteamPipe 下载，导入到 payload store：`<files>/payloads/<payload_id>/game/`。版本页通过 launch profile 选择本体、兼容包、存档/MOD 隔离模式，切换时不复制 PCK。构建直装版 APK 时也可以临时内置 zip，但不会提交到仓库。

3. **移动端兼容插件 / compatibility pack**
   `port-mod/` 是独立 git 仓库 <https://github.com/ModinMobileSTS/sts2-android-compat> 的 submodule。插件按游戏版本使用 git 分支维护，可导出为独立 zip 兼容包（`compat_manifest.json` + `STS2Mobile.dll` + `port_compat.pck`），由启动器安装，并由具体启动配置选择后在启动 Godot 前 staging。

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
  README.md                        # 普通开发者/测试者入口
  LICENSE                          # 本仓库原创代码 MIT License
  THIRD_PARTY_LICENSES.md          # 第三方来源与许可证摘要
  AGENTS.md                        # 编码代理/维护者专用操作约定
  android/                         # Android shell / Godot Android Gradle 工程
    AndroidManifest.xml            # Activity、provider、权限；默认 launcher 为 GameSettingsActivity
    build.gradle                   # Godot Android template 风格应用模块配置
    config.gradle                  # AGP、SDK、NDK、Java 等版本配置
    gradle.properties              # applicationId、ABI、签名、构建类型等本地属性
    src/com/godot/game/            # Java/Kotlin shell、附加设置、payload 导入、Steam 中心、GodotApp 桥
    steam-protocol/                 # Steam CM/auth/content protobuf 协议子模块
    steam-content/                  # SteamPipe depot manifest/chunk 下载子模块
    res/                           # 附加设置、崩溃页、文件浏览器、图标、主题等资源
    assets/
      bootstrap.pck                # 无游戏 payload 时的最小 Godot bootstrap pack
      port_compat.pck              # legacy fallback overlay pack，脚本生成
      compat_packs/                # 构建时生成的兼容包 zip assets，gitignore
      dotnet_bcl/                  # 大型 .NET/Godot runtime DLL，生成/同步产物，gitignore
      payload/                     # 直装版临时内置 zip，gitignore
    libs/                          # Godot/FMOD/template AAR，生成/同步产物，gitignore

  port-mod/                        # git submodule: ModinMobileSTS/sts2-android-compat，多分支兼容补丁仓库
    STS2AndroidPortCompat/         # 兼容插件源码，输出 STS2Mobile.dll
    overlay/                       # 打包进 port_compat.pck 的 shader/resource overlay
    refs/                          # 本地 original compile gate 引用说明/symlink
    tools/build-compat-pack.sh     # 导出独立可安装兼容包 zip

  tools/
    android/                       # Android runtime 同步、Gradle 环境、兼容包构建/staging 脚本
    package/                       # APK 打包、payload zip 校验脚本
    diff/                          # 差异清单工具

  doc/                             # 公开项目文档入口：结构、构建、运行时加载流程等
  .agent/                          # 本地 agent 草稿/报告/worktree/参考 clone/changelog/历史备份，gitignore
  dist/                            # APK 输出副本，gitignore
```

## 多版本与兼容包

启动器现在内置“版本”页，管理三类对象：

- **游戏本体版本 / payload**：导入的 PC zip 或 SteamPipe 下载结果安装到 `<files>/payloads/<payload_id>/game/`。`payload_id` 由版本、commit 和 payload hash 派生，切换版本不再复制完整本体。
- **启动配置 / launch profile**：保存到 `<files>/instances/<profile_id>/instance.json`，绑定一个 payload、一个可选兼容包，并指定存档/设置和 MOD 使用全局目录还是该 profile 的隔离目录。同一个 payload 可以创建多个 profile。
- **移动端兼容包**：安装到 `<files>/compat-packs/<pack_id>/`，每个包包含 manifest、`STS2Mobile.dll` 和 `port_compat.pck`。新建/编辑启动配置时按当前 profile payload manifest 中的游戏版本号推荐匹配包，启动时只检查该配置保存的 `compat_pack_id`；manifest 可用 `target_game.supported_versions` 让一个包兼容多个游戏 patch 版本；不再因 `sts2.dll` SHA-256 不一致阻止启动。

APK 打包前，脚本会把内置兼容包生成到：

```text
android/assets/compat_packs/*.zip
```

这些 zip 是可复现的构建产物，**不再由 git 跟踪**；需要刷新时运行 `tools/android/stage-bundled-compat-packs.sh` 或完整打包脚本。当前内置包列表由 `tools/android/bundled-compat-packs.json` 控制，包含正式/稳定 `v0.103.x`（同一个 `compat/v0.103.2` 分支兼容 `v0.103.2` 与 `v0.103.3`，compile gate `original`）、旧 beta `v0.106.1`（`compat/v0.106.1-beta`，compile gate `original-v0.106.1`）和当前 beta `v0.107.0`（`compat/v0.107.0-beta`，compile gate `original-v0.107.0`）。对应原版 DLL 引用目录通过 `.env` 中的 `STS2_ORIGINAL_V103_REFERENCE_DIR` / `STS2_ORIGINAL_V1061_REFERENCE_DIR` / `STS2_ORIGINAL_V1070_REFERENCE_DIR` 配置。

## 不提交的内容

以下内容为本地私有输入、生成产物或大型运行时文件，不应提交：

- 用户的 `SlayTheSpire2.zip`
- 解压后的完整游戏 payload
- `android/assets/dotnet_bcl/`
- `android/libs/`
- `android/assets/payload/`
- `android/assets/compat_packs/*.zip`（构建时生成的兼容包 assets）
- `dist/`、`*.apk`、`*.aab`、`*.apks`
- keystore / jks / p12 等签名密钥
- .NET `bin/` / `obj/`
- `.agent/` 本地 agent 计划、报告、临时 worktree 与外部参考 clone

## 构建前准备

本仓库不再在脚本中写死个人机器上的相对路径。首次构建前复制示例配置：

```bash
cp .env.example .env
cp local.properties.example local.properties
```

然后编辑 `.env`：

- `JAVA_HOME`：完整 JDK，必须包含 `bin/javac`。
- `ANDROID_HOME` / `ANDROID_SDK_ROOT`：Android SDK 根目录。
- `DOTNET_BIN`：.NET SDK 可执行文件。
- `STS2_ANDROID_RUNTIME_REFERENCE_ROOT`：参考 Android runtime/template 目录，需包含 `libs/`、`assets/dotnet_bcl/`、`gradle/wrapper/gradle-wrapper.jar`。
- `STS2_FMOD_PLUGIN_AAR`、`STS2_CRYPTO_NATIVE_JAR`：同步 runtime 时需要的 AAR/JAR。
- `STS2_ORIGINAL_V103_REFERENCE_DIR`、`STS2_ORIGINAL_V1061_REFERENCE_DIR`、`STS2_ORIGINAL_V1070_REFERENCE_DIR`（或对应 `*_ROOT`）：兼容层 original compile gate 引用目录，需包含 `sts2.dll`、`GodotSharp.dll`、`0Harmony.dll`。
- `RELEASE_KEYSTORE_*`：本地签名配置；测试可使用 Android debug keystore，正式发布请改为私有 release keystore。

非 secret 的本地选项（Gradle task、输出路径、compat pack staging 目录等）放在 `local.properties`。两个文件都已加入 `.gitignore`。完整说明见 [`doc/build/local-configuration.md`](doc/build/local-configuration.md)。

可公开 clone 的 GitHub 参考项目可用脚本准备：

```bash
tools/deps/prepare-external-projects.sh
# 查看清单
tools/deps/prepare-external-projects.sh --list
```

该脚本不会下载商业游戏 payload、原版 DLL、keystore 或准备好的 Godot/Mono runtime。

常用依赖：Bash、Python 3、rsync、Android SDK/NDK/CMake、JDK、.NET SDK，以及用于验证的 arm64 Android 设备或模拟器。

建议始终通过项目脚本构建，不要直接裸跑系统 Java/Gradle。

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

脚本还会检查 `SlayTheSpire2.pck` 的 PCK magic，并输出 zip 的 sha256 与 `release_info.json` 信息；如果 PC zip 只有一个顶层目录（如 `Slay the Spire 2/`）且必需文件位于其中，也会自动识别。

### 2. 可选：生成 Android 优化本体 zip

如果你有某个版本的 PC 原版 zip 和匹配的 Godot 源码/反导出工程，可以先生成移动端优化本体 zip，再在导入版 APK 中导入：

```bash
tools/package/build_android_body_zip.sh \
  --pc-zip "/path/to/SlayTheSpire2.zip" \
  --source-dir "/path/to/sts2-godot-source" \
  --out "dist/payload/sts2-vX.Y.Z-android-body.zip"
```

脚本会保留 PC zip 里的原版 `sts2.dll` / deps / runtimeconfig，避免重新编译改变 IL；资源 PCK 则通过 Godot Android `--export-pack` 重新导入为 ETC2/ASTC，并过滤 PC 纹理格式和桌面 runtime。输入 PC zip 可以是平铺结构或单一顶层目录结构；生成的 zip 会展平为 launcher 导入格式，仍可用 `tools/package/validate_payload_zip.py` 校验并由当前 launcher 导入。

### 3. 构建导入版 APK

导入版 APK 不内置游戏资源。用户安装后，在附加设置页选择本地 `SlayTheSpire2.zip` 或上述 Android 优化本体 zip 导入。

```bash
tools/package/build_importer_apk.sh
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-importer.apk
```

### 4. 构建直装版 APK

直装版会在构建时临时把本地 zip 复制到 `android/assets/payload/SlayTheSpire2.zip`，首次启动时自动解压到应用私有目录。该 zip 会在脚本退出时自动删除，不应提交。

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-direct.apk
```

### 5. 安装和验证

```bash
adb install -r dist/sts2-re-importer.apk
# 或
adb install -r dist/sts2-re-direct.apk
```

常用检查命令：

```bash
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/default/1
adb shell run-as com.megacrit.sts2re ls files/payloads
adb shell run-as com.megacrit.sts2re ls files/instances
```

推荐 smoke test：

1. 首次打开进入欢迎向导/附加设置页，而不是直接进游戏。
2. 导入版能选择并导入本地 PC zip。
3. 导入或 Steam 下载成功后，`files/payloads/<payload_id>/game/.payload_manifest.json` 和 `files/payloads/<payload_id>/game/SlayTheSpire2.pck` 存在，并创建/选择 `files/instances/<profile_id>/instance.json`。
4. 点击启动游戏后，logcat 能看到加载 imported game PCK 的日志。
5. 附加设置中的图形、输入、MOD 设置能写入 `files/default/1/settings.save`。
6. 游戏内返回附加设置、日志页、文件浏览器、崩溃页不崩溃。
7. MOD 总开关和单 MOD 禁用能在启动日志中反映；MOD 页导入同 ID MOD 会弹冲突选择框，卡片默认折叠且可展开查看作者/依赖，长按左侧手柄可拖到分组。
8. Steam 中心可登录/验证 refresh token；从 Steam 下载的本体进入 `files/payloads/`，新建启动配置会按版本填入推荐兼容包，后续只能在创建/编辑启动配置时调整兼容包。
9. Steam Cloud 手动刷新/拉取/上传使用当前 launch profile 的存档根；拉取前会在 `files/steam/cloud/<profile_id>/backups/` 创建备份。

## 常用开发命令

同步大型运行时产物：

```bash
tools/android/sync-runtime-from-references.sh
```

构建并 stage 当前 submodule 兼容插件到 legacy assets fallback：

```bash
tools/android/build-port-mod.sh
```

构建/刷新内置兼容包 zip 到 gitignored 的 `android/assets/compat_packs/`：

```bash
tools/android/stage-bundled-compat-packs.sh
```

只编译 Java / Gradle 检查：

```bash
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac
```

只构建兼容 MOD / compile gate：

```bash
# 默认 ReferenceFlavor 来自 local.properties 的 compat.default_reference_flavor
tools/android/build-port-mod.sh

# 0.107.0 beta original gate（当前默认）
REFERENCE_FLAVOR=original-v0.107.0 tools/android/build-port-mod.sh

# 0.106.1 beta original gate（旧 beta）
REFERENCE_FLAVOR=original-v0.106.1 tools/android/build-port-mod.sh

# 0.103.x stable original gate
REFERENCE_FLAVOR=original tools/android/build-port-mod.sh
```

如需裸跑 `dotnet build`，请显式传入 `.env` 中配置的引用目录：

```bash
"$DOTNET_BIN" build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.107.0 \
  -p:CompatReferenceDir="$STS2_ORIGINAL_V1070_REFERENCE_DIR" -v:q
```

准备/查看 GitHub 外部参考项目：

```bash
tools/deps/prepare-external-projects.sh --list
tools/deps/prepare-external-projects.sh --group modding-reference
```

重新生成 Android 适配 overlay pack：

```bash
tools/android/make-port-overlay-pck.py
```

## 运行时目录约定

应用私有目录中的关键路径：

```text
<files>/payloads/<payload_id>/game/         # 导入游戏 payload
<files>/payloads/<payload_id>/game/SlayTheSpire2.pck
<files>/payloads/<payload_id>/game/release_info.json
<files>/payloads/<payload_id>/game/.payload_manifest.json
<files>/instances/<profile_id>/instance.json # 启动配置
<files>/instances/<profile_id>/default/1/settings.save # 隔离存档/设置
<files>/instances/<profile_id>/mods/        # 隔离 MOD 目录
<files>/instances/<profile_id>/logs/        # profile 日志
<files>/steam/downloads/                    # SteamPipe 下载 staging / 任务诊断
<files>/steam/cloud/<profile_id>/           # Steam Cloud manifest、baseline、备份与诊断
<files>/compat-packs/<pack_id>/             # 已安装移动端兼容包
<files>/launcher/selected_instance.json     # 当前启动上下文
<files>/launcher/selected_compat_pack.json  # 当前启动配置解析出的兼容包诊断记录
<files>/default/1/settings.save             # 全局存档/设置
<files>/mods/                              # 全局 MOD 目录
<files>/.godot/mono/publish/arm64/          # Godot/Mono publish 目录
```

关键流程：

- `GameSettingsActivity` 是默认 launcher，负责首次向导和附加设置；“游戏”主页采用 MD3 深色仪表盘，提供 Steam chip、动态启动卡、MOD/存档状态卡和导入/存档/日志/启动配置等快捷入口；启动器图标统一改用 bundled Material Symbols Rounded 字体渲染。
- `SteamAccountActivity` 提供 Steam 登录、refresh token 验证、SteamPipe 下载游戏本体，以及当前 launch profile account root 的 Steam Cloud 手动/自动同步入口；首次打开会显示带动态倒计时、5 秒后才能关闭的账号安全提示，提醒本地 refresh token、可信来源、未知 MOD 风险、云存档备份和国内网络加速器需求，页面底部也常驻“安全说明”按钮可再次查看。
- `PayloadManager` 负责 SAF 选择 zip、assets 内置 payload 解压、校验、staging、安装到 payload store 和 rollback。
- `LaunchProfileManager` 负责选择当前 payload/compat/save/mod/log 路径，并写入 `<files>/launcher/selected_instance.json`；兼容包是每个启动配置的 `compat_pack_id`，不是全局选中项。删除游戏本体或兼容包时启动配置会保留，启动前再提示缺失项。
- `GodotApp` 是真正的 Godot 游戏 Activity：
  - 有当前 profile payload 的 `SlayTheSpire2.pck` 时传入 `--main-pack`。
  - 无 payload 时使用 `assets/bootstrap.pck`。
  - 常规启动由 `GameSettingsActivity` 先在后台同步 APK assets 中的 `dotnet_bcl`、所选兼容包和当前 payload 中的 `data_*/*` 到 Godot publish 目录，避免在 Activity 主线程做大文件复制。
- `STS2Mobile.dll` / `STS2Mobile.ModEntry` 是 patched Godot runtime 期望加载的 Android 兼容 MOD 入口。

## 兼容插件概览

`port-mod/` 作为独立 submodule 负责在运行时通过 Harmony patch 适配 Android 环境。不同游戏版本通过该独立仓库的分支维护，并使用 `tools/build-compat-pack.sh` 导出可安装兼容包。当前覆盖方向包括：

- 禁用或替代桌面 Steam / Sentry / platform 路径（Steam 登录、游戏下载和云存档由 Android launcher 侧处理）
- 从 Android 私有目录读取 `release_info.json`
- 桥接附加设置中的 Android-only 配置
- 调整显示、分辨率、FPS、UI scale、横屏方向等移动端选项
- 将本地 MOD 路径重定向到当前 launch profile 的 MOD 目录（全局 `<files>/mods` 或隔离 `<files>/instances/<profile_id>/mods`）
- 跳过 Steam Workshop 枚举
- 加载 `port_compat.pck` 中的 shader/resource overlay
- 适配触摸输入、返回键、手柄 trigger axis、双指 inspect 等输入行为；附加设置“设置 → 操作 → Tooltip 显示”默认保持 PC 端立即显示，也可切换为长按 1 秒显示或隐藏，减少触屏短按时 tooltip 遮挡屏幕
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

## 授权、第三方来源与文档边界

本仓库原创代码建议/采用 MIT License，见 [`LICENSE`](LICENSE)。该授权不覆盖第三方源码/模板/二进制 runtime、FMOD、Steam/NexusMods 服务 API、用户游戏文件或其他本仓库无权再授权的内容。

直接引用资源和参考代码实现的第三方仓库已集中记录在 [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)，重点包括：

- [`ModinMobileSTS/SlayTheAmethystModded`](https://github.com/ModinMobileSTS/SlayTheAmethystModded)：Steam 登录、SteamPipe 下载、Steam Cloud 相关实现与设计参考；`android/steam-protocol/`、`android/steam-content/` 的协议/下载代码从该项目思路与源码改编。其顶层标准许可证需继续确认，发布前应保留授权依据。
- [`iunius612/StS2-Launcher_Mod_Manager`](https://github.com/iunius612/StS2-Launcher_Mod_Manager)：Android launcher/runtime、Godot/Mono publish 目录、兼容补丁加载顺序与构建脚本参考；上游 MIT。

项目文档与 agent 专用资料区分如下：

- 面向开发者/测试者的长期公开项目文档：本 `README.md` 与 [`doc/`](doc/)。
- 面向编码代理/维护者的操作约定：[`AGENTS.md`](AGENTS.md)。它不是用户手册；用户可见说明应沉淀到 `README.md` / `doc/`。
- changelog 主要记录 agent 的修改过程与验证流水，已从公开 `doc/` 移到本地 ignored 的 `.agent/agent-docs/changelog/`。
- 旧 `docs/` 历史 diff/validation 资料已移到本地 ignored 的 `.agent/historical-backup/docs/`，不再公开追踪。
- 本地 agent 草稿、计划、报告、临时 worktree、changelog、历史备份和参考仓库 clone：`.agent/`，已 gitignore，不应提交。

## 相关文档

- [`doc/`](doc/)：规范化公开项目文档入口（项目结构、构建、兼容包/MOD 加载流程）
- [`doc/runtime/compat-pack-loading-flow.md`](doc/runtime/compat-pack-loading-flow.md)：Android 兼容包与普通 MOD 详细加载流程
- [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)：第三方来源、许可证与发布前合规检查
- [`AGENTS.md`](AGENTS.md)：编码代理/维护者专用操作约定
- [`port-mod/README.md`](port-mod/README.md)：Android 兼容插件 / compat pack 说明

## 免责声明

本项目为非官方移植/兼容性研究工程，不隶属于或代表 Mega Crit、Slay the Spire 2 或 Godot 官方。仓库不提供、分发或授权分发任何商业游戏资源。请遵守相关软件许可、平台规则和当地法律，仅在你合法拥有游戏副本的前提下进行本地构建与测试。
