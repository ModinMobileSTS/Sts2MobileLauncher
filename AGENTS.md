# AGENTS.md

面向后续编码代理/维护者的项目速览与操作约定。当前目录：`/mnt/datas/agent_workspace/s2_re`。  
最后同步：2026-05-30。

## 0. 总原则

- 本工程是 **Slay the Spire 2 Android 重构移植/启动器工程**，不是完整游戏源码仓库。
- 仓库只维护 Android shell、导入/版本管理逻辑、兼容包构建脚本与 Android 兼容补丁源码；**不提交用户游戏 zip、解压后的完整游戏 payload、大型 Godot/Mono runtime、keystore**。
- `port-mod/` 是独立仓库 `../sts2-android-compat` 的 **git submodule**，按游戏版本使用多个分支维护 Android 兼容补丁。
- 新增或修改功能时必须同步文档：优先更新 `doc/`，并在 `doc/changelog/` 新增一条变更记录；历史 `docs/` 目录保留旧阶段差异/验证资料，不作为新文档入口。
- 完成用户要求的修改后，请用脚本构建一个 importer 版本 APK 便于测试：

```bash
tools/package/build_importer_apk.sh
```

## 1. 项目定位

Android 侧拆成三层维护：

1. **Android shell / launcher / 附加设置**
   - APK 默认进入 `GameSettingsActivity`，不是直接进入游戏。
   - 负责首次向导、本地 PC 游戏 zip 导入、Steam 登录/游戏下载/云存档、私有目录管理、游戏版本/兼容包管理、启动 Godot Activity、日志/文件浏览、存档备份、MOD 管理。
2. **原版游戏 payload**
   - 用户本地提供 `SlayTheSpire2.zip`，或使用自己拥有 STS2 的 Steam 账号从 SteamPipe 下载。
   - 导入/下载后安装到 `<files>/payloads/<payload_id>/game/`；版本/配置切换只切换 launch profile 指针，不再复制完整 PCK/解压目录。
   - “版本”页可为同一个 payload 创建多个 `<files>/instances/<profile_id>/instance.json` 启动配置，并分别选择存档/设置、MOD 使用全局目录或隔离目录。
   - 直装版构建时可临时内置 zip 到 APK assets，但构建脚本退出会清理，不能提交。
3. **Android 兼容包 / Harmony patcher**
   - `port-mod/STS2AndroidPortCompat` 编译输出 `STS2Mobile.dll`。
   - `port-mod/overlay` 打包输出 `port_compat.pck`。
   - 兼容包 zip 形态为：`compat_manifest.json` + `STS2Mobile.dll` + `port_compat.pck` + `SHA256SUMS`。
   - 兼容包不是普通用户 MOD；它由 launcher/Godot runtime 在游戏早期加载，用来 patch 原版 PC 程序集并让普通 MOD 系统在 Android 上工作。

## 2. 当前支持版本矩阵

`port-mod` 当前按分支维护不同游戏版本的兼容补丁。父仓库 `.gitmodules` 默认跟踪 beta 分支，但打包脚本会通过临时 worktree 同时构建多个内置兼容包。

| 通道 | 游戏版本 | 原版/解包源码目录 | submodule 分支 | compile gate `ReferenceFlavor` | 兼容包 id |
| --- | --- | --- | --- | --- | --- |
| 正式/稳定 | `v0.103.2` | `../s2_original/s21032/` | `compat/v0.103.2` | `original` | `sts2-android-compat-v0.103.2` |
| Beta 测试 | `v0.106.1` | `../s2_original/s201061/` | `compat/v0.106.1-beta` | `original-v0.106.1` | `sts2-android-compat-v0.106.1-beta` |

关键文件：

- `.gitmodules`：`port-mod` submodule URL 与默认 branch。
- `tools/android/bundled-compat-packs.json`：内置兼容包列表，当前包含 `compat/v0.103.2` 与 `compat/v0.106.1-beta`。
- `port-mod/refs/original/`：指向 `../s2_original/s21032/.godot/mono/temp/bin/Debug/` 的本地 symlink，用于 `v0.103.2` original compile gate。
- `port-mod/refs/original-v0.106.1/`：指向 `../s2_original/s201061/.godot/mono/temp/bin/Debug/` 的本地 symlink，用于 beta compile gate。
- `port-mod/compat_manifest.v0.106.1-beta.json`：当前 checkout 分支的 beta 兼容包 manifest；`v0.103.2` manifest 位于对应分支。

注意：启动器按 payload manifest 的 `release_info.version` 自动匹配已安装兼容包；若当前选择的兼容包与 payload 版本不一致，会弹出风险提示并允许用户取消、去版本页或强制启动。当前不会仅因 `sts2.dll` SHA-256 不一致硬阻止启动，但 manifest 中仍记录 SHA 供诊断和精确匹配升级使用。

## 3. 参考项目 / 本地输入

主要参考输入均在 workspace 相邻目录：

- `../s2/`
  - 旧 Android 移植版/参考工程。
  - 复用其中的附加设置资源、Godot Android build template 配置、FMOD 资源、本机 JDK/Android SDK/.NET SDK。
  - 构建脚本会读取：
    - `../s2/.cache/local-jdk/full/usr/lib/jvm/java-21-openjdk-amd64`
    - `../s2/.godot-home/Android/Sdk`
    - `../s2/.local/dotnet/dotnet`
    - `../s2/addons/fmod/libs/android/fmod-release.aar`
- `../s2/.cache/StS2-Launcher_Mod_Manager/`
  - 旧 Launcher/Mod Manager runtime 参考。
  - `tools/android/sync-runtime-from-references.sh` 会从中同步 Godot template AAR、`dotnet_bcl`、Gradle wrapper jar、crypto native jar 等大型运行时产物。
- `../s2_original/s21032/`
  - 正式/稳定 `v0.103.2` PC 原版/解包基线。
- `../s2_original/s201061/`
  - Beta `v0.106.1` PC 原版/解包基线。
- `../s2_pc/Slay the Spire 2.zip`
  - 本地测试用 PC 游戏 zip 样例，不提交。可替换为用户合法拥有的其他路径。

## 4. 当前目录结构

```text
s2_re/
  AGENTS.md                        # 本文件，给后续 agent/维护者的项目说明
  README.md                        # 面向普通开发者/测试者的入口说明
  android/                         # Android shell / Godot Android Gradle 工程根目录
    AndroidManifest.xml            # Activity/provider/权限；GameSettingsActivity 是默认 launcher
    build.gradle                   # Godot Android template 风格应用模块配置
    config.gradle                  # AGP/Kotlin/SDK/NDK/Java 版本与 Godot export property helpers
    gradle.properties              # applicationId、ABI、签名、构建类型等本地属性
    settings.gradle                # pluginManagement + install-time asset pack
    assetPackInstallTime/          # install-time asset pack 占位
    src/com/godot/game/            # Java/Kotlin shell、附加设置、payload/版本/兼容包管理、Steam 中心、GodotApp 桥
    steam-protocol/                # Steam CM/auth/content protobuf 协议子模块
    steam-content/                 # SteamPipe depot manifest/chunk 下载子模块
    res/                           # 附加设置/崩溃页/文件浏览器/图标/shortcut/theme 等 Android 资源
    assets/
      bootstrap.pck                # 无游戏 payload 时的最小 Godot bootstrap pack
      port_compat.pck              # legacy fallback overlay pack，脚本生成
      compat_packs/                # 内置兼容包 zip，stage-bundled-compat-packs.sh 生成/刷新
      dotnet_bcl/                  # 大型 .NET/Godot runtime DLL，同步生成，gitignore
      payload/                     # 直装版临时内置 zip，gitignore
    libs/                          # Godot/FMOD/template AAR，同步生成，gitignore
  port-mod/                        # git submodule: ../sts2-android-compat，多分支兼容补丁仓库
    compat_manifest.*.json         # 当前分支的兼容包 manifest
    STS2AndroidPortCompat/         # 兼容插件源码，输出 STS2Mobile.dll
      STS2Mobile.csproj            # runtime 期望的程序集名：STS2Mobile.dll
      ModEntry.cs                  # unmanaged entrypoints: InitializeGodotSharp / Apply
      Patches/                     # 平台、设置、输入、MOD、LAN、shader、生命周期等 Harmony patch
      Android/                     # Android settings/path bridge
    overlay/                       # 打包进 port_compat.pck 的 shader/resource overlay
    refs/                          # 本地 original compile gate symlink/说明
    tools/build-compat-pack.sh     # 导出独立可安装 compat pack zip
  tools/
    android/
      env-from-s2.sh               # source 后使用 ../s2 的 JDK/Android SDK
      gradle-with-s2-env.sh        # 在 android/ 下带本机环境执行 Gradle
      sync-runtime-from-references.sh # 同步 Godot/FMOD/dotnet_bcl 等大型运行时产物
      build-port-mod.sh            # 编译当前 submodule 分支并 stage legacy fallback dll/pck
      stage-bundled-compat-packs.sh # 按 bundled-compat-packs.json 多分支构建内置兼容包
      bundled-compat-packs.json    # 内置兼容包分支列表
      make-bootstrap-pck.py        # 生成最小 bootstrap.pck
      make-port-overlay-pck.py     # 从 port-mod/overlay 生成 legacy fallback port_compat.pck
      fmod-shim/                   # 替换 FMOD Java class 的 shim 源码
    package/
      validate_payload_zip.py      # 校验 PC 游戏 zip 必需文件/PCK magic/hash
      build_importer_apk.sh        # 构建不内置游戏 zip 的导入版 APK
      build_direct_apk.sh          # 构建临时内置游戏 zip 的直装版 APK
    diff/                          # 差异清单工具
    git/report-heads.sh            # 输出父仓库与 submodule HEAD/branch/upstream 状态
  doc/                             # 新规范化文档入口；新增/修改功能时同步维护
    README.md                      # 文档索引与维护规则
    changelog/                     # 每次用户可见/维护相关修改的记录
    architecture/                  # 项目结构、目录职责、版本模型
    build/                         # 构建/打包/发布流程
    runtime/                       # 启动、兼容包、MOD 加载流程
    modding/                       # 普通 MOD 与兼容包开发维护说明
  docs/                            # 历史阶段资料：旧 diff inventory / validation，待逐步迁移
  dist/                            # APK/兼容包输出副本，本地生成，gitignore
  .agent/                          # agent 计划/报告/本地状态，gitignore
```

## 5. 文档规范

新文档统一放入 `doc/`：

- `doc/README.md`：文档索引、维护规则。
- `doc/changelog/`：每次修改新增一条 `YYYY-MM-DD-简短主题.md`，记录背景、改动、验证、注意事项。
- `doc/architecture/project-structure.md`：目录职责、运行时私有目录、版本/兼容包模型。
- `doc/build/building-and-packaging.md`：构建环境、脚本流程、常用命令、产物位置。
- `doc/runtime/compat-pack-loading-flow.md`：移动端兼容包与普通 MOD 的详细加载流程。
- `doc/modding/mod-and-compat-notes.md`：普通 MOD 目录、启停协议、兼容补丁开发注意事项。

维护要求：

1. 改动构建脚本、目录结构、版本矩阵、兼容包流程时，必须同步 `AGENTS.md` 和对应 `doc/` 页面。
2. 每次可见行为变化或维护规则变化都要新增 changelog；不要只改 `.agent/` 内部计划。
3. `docs/` 旧目录仍可引用，但新说明和长期维护文档应写入 `doc/`。
4. 文档中不要写入用户私有 zip hash/路径之外的敏感信息，不要复制商业游戏资源内容。

## 6. Android shell 关键点

- Java package 保持 `com.godot.game`，便于兼容旧 C# / patched runtime 桥；实际 `applicationId` 由 `android/gradle.properties` 设置为 `com.megacrit.sts2re`。
- `GameSettingsActivity` 是默认 `LAUNCHER`：首次进入欢迎向导/附加设置页；设置页的“桌面图标启动后”偏好可让桌面图标在向导完成且 payload 就绪后自动走 `launchGame()` 直接进游戏，默认仍打开附加设置。
- 主要页面/管理器：
  - `WelcomeSetupPage`：首次向导。
  - `GamePage` / `SettingsPage` / `ModsPage` / `GameVersionManagerPage`：主页、设置、MOD、版本/兼容包管理。
  - `NexusModsStoreActivity`：从 `ModsPage` 的“导入 MOD”下方进入 NexusMods 商店；用户手动保存 Personal API Key 后可浏览热门/最新/近期更新结果、按 URL/数字 ID 精确查询、下载 ZIP 并导入到当前 launch profile 的 MOD 目录（全局 `<files>/mods/` 或隔离 `<files>/instances/<id>/mods/`）。非 Premium 下载若被 NexusMods API 拒绝，可引导用户打开网页并粘贴 NXM 链接中的 `key/expires`。
  - `SteamAccountActivity`：Steam 中心；首次打开会显示带动态倒计时、5 秒后才能关闭的账号安全提示（本地保存 refresh token、可信来源、未知 MOD 风险、云存档备份、国内可能需要加速器），页面底部常驻“安全说明”按钮可再次查看；完成账号密码登录、Steam Guard、refresh token 加密保存、SteamPipe 下载 STS2 payload 到 payload store，以及当前 launch profile account root 的 Steam Cloud 手动拉取/上传和可选自动同步设置。
  - `PayloadManager`：导入/校验/安装 PC 游戏 zip 或 SteamPipe 下载目录到 payload store。
  - `LaunchProfileManager`：维护 `<files>/payloads/<payload_id>/game/` 与 `<files>/instances/<profile_id>/instance.json`，支持同一游戏本体创建多个全局/隔离存档和 MOD 的启动配置；切换配置不复制 PCK。
  - `GameBodyVersionManager`：legacy facade，版本选择委托给 `LaunchProfileManager`，不再执行 active/归档目录复制。
  - `CompatPackManager`：安装、选择、删除兼容包；从 APK assets 安装内置兼容包；按 payload manifest 匹配目标版本。
  - `GameLaunchPreparationManager`：启动前后台准备 Mono publish 目录、兼容包 dll、overlay pck、payload assembly 和纹理缓存清理。
  - `GodotApp`：真正的 Godot 游戏 Activity。
- `GodotApp` 启动行为：
  - 首次向导未完成时会重定向回 `GameSettingsActivity`。
  - `getCommandLine()` 加 renderer/display/log 参数；日志等级由附加设置 `log_level`（`info` / `debug` / `very_debug`）转为 STS2 `-log <LogType> <LogLevel>` 命令行，覆盖 Generic/Network/Actions/GameSync/VisualSync；有当前 launch profile payload 的 `SlayTheSpire2.pck` 时传 `--main-pack <files>/payloads/<payload_id>/game/SlayTheSpire2.pck`，否则使用 `assets/bootstrap.pck`。
  - 暴露 `launchGameSettingsFromGame()`、`restartToSettingsFromGame()`、`getGodotDataDir()`、`getSelectedGameDir()`、`getSelectedAccountRootDir()`、`getSelectedModsDir()`、`getSelectedLaunchContextJson()`、`getSelectedCompatPackDir()`、`getSelectedCompatOverlayPck()` 等静态桥给 C# 兼容层。
  - 维护当前 profile 的 `logs/godot.log` 与 `logs/android-launch.log`，并保留最近若干 Godot 日志。
- 启动路径：
  1. `GameSettingsActivity.launchGame()` 检查 payload 是否 ready。
  2. 如果兼容包开关启用，尝试自动选择最佳匹配包；无包则阻止启动，版本不匹配则弹窗提示。
  3. 后台执行 `GameLaunchPreparationManager.prepareForLaunch()`。
  4. 以 `launch_prepared=true` 启动 `GodotApp`；`GodotApp` 仍保留 fallback 准备路径防止直接启动遗漏。

## 7. Payload / 版本管理

`PayloadManager` 负责本地游戏 zip 导入：

- 支持 SAF 选择 zip 与 assets 内置 `payload/SlayTheSpire2.zip`。
- zip 必需文件：
  - `SlayTheSpire2.pck`
  - `release_info.json`
  - `data_sts2_windows_x86_64/sts2.dll`
  - `data_sts2_windows_x86_64/sts2.deps.json`
  - `data_sts2_windows_x86_64/sts2.runtimeconfig.json`
- 导入流程：复制到私有临时文件并计算 sha256 → 安全解压到 staging → 校验 PCK magic 与必需文件 → 对私有 PCK copy 做 length-preserving Sentry metadata patch → 写 `.payload_manifest.json` → 按 version/commit/hash 生成 payload id → 原子安装到 `<files>/payloads/<payload_id>/game/`。
- 安全措施：Zip Slip canonical path 防护、backup/rollback、取消控制、旧 scratch 清理。
- 导入成功后会尝试：
  - `LaunchProfileManager.createOrSelectDefaultProfileForPayload()`：创建/选择绑定该 payload 的启动配置；默认配置使用全局存档和全局 MOD，用户可在“版本”页新建隔离配置。
  - `CompatPackManager.findBestMatch()`：按版本自动为当前 profile 选择匹配兼容包。
  - 旧 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制。

应用私有目录约定：

```text
<files>/payloads/<payload_id>/game/         # 不可变导入游戏 payload
<files>/payloads/<payload_id>/game/.payload_manifest.json # 导入 manifest，含 release_info / dll sha / pck patch 记录
<files>/instances/<profile_id>/instance.json # 启动配置，绑定 payload/compat/save/mod 模式
<files>/instances/<profile_id>/default/<account>/settings.save # 隔离存档/设置目录
<files>/instances/<profile_id>/mods/        # 隔离普通用户 MOD 目录
<files>/instances/<profile_id>/logs/        # profile 日志目录
<files>/steam/downloads/                    # SteamPipe 下载 staging / 任务诊断
<files>/steam/cloud/<profile_id>/           # Steam Cloud manifest、baseline、备份与诊断
<files>/compat-packs/<pack_id>/             # 已安装 Android 兼容包
<files>/launcher/selected_instance.json     # 当前启动配置解析结果
<files>/launcher/selected_game_version.json # legacy 兼容诊断记录，指向当前 payload
<files>/launcher/selected_compat_pack.json  # 当前兼容包选择记录
<files>/default/<account>/settings.save     # 全局存档/设置目录，默认 account=1
<files>/mods/                              # 全局普通用户 MOD 目录
<files>/.godot/mono/publish/arm64/          # Godot/Mono publish 目录
<files>/port_compat.pck                    # 启动前 staging 的当前兼容包 overlay
<files>/logs/                              # legacy/global 日志 fallback
```

## 8. 兼容包 / port-mod submodule

### 8.1 submodule 分支与工作方式

`port-mod/` 是 submodule，不要把它当父仓库普通目录直接混合提交。常用检查：

```bash
git submodule status
git -C port-mod status --short --branch
git -C port-mod branch -a
tools/git/report-heads.sh
```

切换或更新时注意：

- 修改兼容层源码时先确认当前 `port-mod` 分支是否是目标版本分支。
- 兼容层改动要在 submodule 仓库内提交，再在父仓库更新 submodule 指针。
- `tools/android/stage-bundled-compat-packs.sh` 会为非当前分支创建临时 worktree 到 `.agent/worktrees/compat-packs/`，避免不同版本补丁互相污染。
- 当前 checkout 分支若有未提交改动，stage 脚本会直接用当前 dirty worktree 构建对应分支的内置包，便于本地测试；正式提交前必须清理 dirty 状态。

### 8.2 构建入口

- 当前分支 legacy fallback 构建：

```bash
tools/android/build-port-mod.sh
```

默认 `REFERENCE_FLAVOR=original-v0.106.1`，适合当前 `compat/v0.106.1-beta` 分支。脚本会：

1. 使用 `../s2/.local/dotnet/dotnet` 编译 `port-mod/STS2AndroidPortCompat/STS2Mobile.csproj`。
2. 写入 build metadata（branch/commit/dirty/timestamp）。
3. 复制输出到 `android/assets/dotnet_bcl/STS2Mobile.dll` 作为 fallback。
4. 运行 `tools/android/make-port-overlay-pck.py` 生成 `android/assets/port_compat.pck`。

- 构建当前 submodule 分支的独立兼容包：

```bash
cd port-mod
./tools/build-compat-pack.sh
```

- 构建全部内置兼容包并复制到 APK assets：

```bash
tools/android/stage-bundled-compat-packs.sh
```

该脚本读取 `tools/android/bundled-compat-packs.json`，为每个分支输出 `android/assets/compat_packs/*.zip`。APK 启动时 `CompatPackManager.installBundledCompatPacks()` 会把这些 zip 安装到 `<files>/compat-packs/`。

### 8.3 compile gate

检查是否误依赖旧 Android port 改过的 `sts2.dll`，请使用对应原版引用：

```bash
# v0.103.2 正式/稳定
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original -v:q

# v0.106.1 beta
../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj \
  -p:ReferenceFlavor=original-v0.106.1 -v:q
```

`ReferenceFlavor=runtime`（默认 MSBuild 属性）引用旧 launcher runtime，适合快速编译；正式兼容分支应通过对应 original gate。

### 8.4 runtime 加载概要

正常启动前，Java shell 会：

1. 复制 APK `dotnet_bcl` runtime 到 `<files>/.godot/mono/publish/arm64/`。
2. 按选择的兼容包复制 `STS2Mobile.dll` 到 publish 目录；关闭兼容包开关时删除该 dll。
3. 复制兼容包 `port_compat.pck` 到 `<files>/port_compat.pck`；无选择时使用 `android/assets/port_compat.pck` fallback。
4. 复制当前 launch profile payload 目录 `<files>/payloads/<payload_id>/game/data_*/*` 到 publish 目录，但保护 BCL/System/GodotSharp 等 runtime DLL 不被 payload 覆盖；profile/payload 切换时会清理旧游戏 assembly 残留。
5. patched Godot runtime 加载 `STS2Mobile.dll` / `STS2Mobile.ModEntry`，调用 `InitializeGodotSharp` 与 `Apply`。
6. `ModEntry.Apply()` 以固定顺序应用 Harmony patches：诊断、BaseLib/RitsuLib、ModelDb/UnlockState、平台/release/save-path/settings/layout/input、shader、LAN、ModLoader 等；`AppPaths` 从 publish 目录或 Android 进程包名推导 `<files>` 并读取 `<files>/launcher/selected_instance.json`（避免兼容层早期初始化调用 Godot API/Java bridge），`SavePathPatches` 会把原版 `UserDataPathProvider` 重定向到当前 launch profile 的 account root，确保隔离存档/设置生效。
7. `ModLoaderPatches` 接管原版 `ModManager.Initialize()`，扫描当前 launch profile 的 `AppPaths.ModsDir`（全局 `<files>/mods` 或隔离 `<files>/instances/<profile_id>/mods`），跳过 Steam Workshop，并处理 `mod_manifest.json` → `<ModId>.json` manifest alias；**加载任何 MOD 之前先预注册仅原版模型占位**（`AbstractModelSubtypes.All`），避免 Android/Mono 下 MOD initializer Harmony patch 某个 getter（如 HextechRunes patch `UnlockState.Relics`）时提前触发 `UnlockState..cctor -> ACT.OVERGROWTH`、或 MOD 静态构造引用原版模型时崩溃；原版类型不带命名空间前缀，提前算 ID 不会污染 YuWanCard/BaseLib 的 `GetEntry` 前缀缓存。**不对 MOD 模型类型提前算 ID**，MOD 占位延迟到 phase 1。
8. `QuickRestartPatches` 在 pause menu 提供 Android 内置“重打/Retry”按钮；快速重开会先等待当前 run save 任务，淡出后执行原版 `RunManager.SetUpSavedSinglePlayer()`（返回 `Task` 的版本会等待完成）以完整初始化新 run 的 `NetService` / `MapSelectionSynchronizer` 等同步器后再调用 `NGame.LoadRun()`，并在淡出后失败时尝试 `FadeIn()` 恢复可见画面，避免关闭/跳过运行时预加载时因 async 时序竞态卡黑屏。
9. `LifecycleAndPerformancePatches` 会在 `NMainMenu._Ready` 后启动安全 deferred preload，并在需要细分或额外 warmup 时接管原版 `LoadCommonAndMainMenuAssets()`：总开关 `preload_enabled` 默认开启；Android 附加设置页中它只作为总开关显示，右侧箭头打开预加载详细管理 BottomSheet，总开关开/关不改写细分项目；`preload_startup_common_enabled=true`、`preload_startup_main_menu_enabled=true`、`preload_runtime_enabled=true` 保持旧版默认资源加载；`preload_menu_hotspots_enabled=false`、`preload_vfx_mode=off`、`preload_combat_code_enabled=false`、`preload_shader_mode=off` 默认为关闭，避免默认行为比旧版更重。高级开关可分别控制 CommonAssets、MainMenuSet、常用菜单实例化、VFX 场景 warmup、战斗代码 warmup、已知 shader 资源加载与 run/act/room 预加载，BottomSheet 的“恢复默认”只重置这些细分项目，不修改 `preload_enabled`。
10. `TransitionMaterialPatches` 会复制 `NTransition` 使用的 fade/fight `ShaderMaterial`，避免关闭预加载时原版 missed-cache 清理 dispose 掉仍在 `FadeIn()` 中使用的共享 transition 材质，从而黑屏。
11. `ModelDbInitPatch` 分三个阶段处理模型占位：
   - **早期原版占位**（加载 MOD 前，由 `ModLoaderPatches` 触发）：仅原版，解决 MOD patch getter / MOD 静态构造引用原版模型的早访问。
   - **phase 1**（`ExecuteEssential` 中、`ModelDb.Init()` 调用**之前**）：MOD patch 已全部应用，按最终 `ModelDb.GetId(Type)` 补齐全部模型（含 MOD 自定义类型）占位；解决 MOD 间静态构造引用（如 `wuwancients.HiddenSeaRecord..cctor -> RELIC.LONG_SNAKE_NECKLACE`），这些构造会在 MOD 的 `ModelDb.Init` prefix 期间被 Android/Mono 提前触发。
   - **phase 2**（`InitPrefix` 中，`Priority.Last`）：在占位上原地运行真实静态/实例构造器，并跳过原版 one-pass body。因部分 MOD 的 `ModelDb.Init` prefix 会自己返回 `false` 并让 Harmony 跳过后续 prefix，兼容层还安装 `Priority.First` postfix 与 `ExecuteEssential` 后置兜底，确保构造 phase 一定执行。自定义模型 ID（含 `ENCOUNTER.YUWANCARD-KILLER_ELITE` 等带前缀 ID）完全由原版 `ModelDb.Init` + MOD `GetEntry` patch 自然产生，不再人为迁移 key。用户 MOD 的 `ModelDb.Init` prefix/postfix 生命周期保留。
   - `UnlockStateCompatPatches` 在 `ModelDb` 初始化完成前让 `ModelDb.AllEncounters` 返回空列表，避免 Android/Mono 因 Harmony patch getter 提前运行 `UnlockState..cctor` 时枚举到尚未构造/注册完成的 MOD encounter；初始化完成后会修复可能提前创建的 static readonly `UnlockState.all`。

上述 MOD 初始化时序、预加载设置协议与快速重开 async 时序修复已同步到 `compat/v0.103.2` 与 `compat/v0.106.1-beta`，两条内置分支都应保持相同不变式。详细流程见 `doc/runtime/compat-pack-loading-flow.md`。

### 8.5 MOD 兼容性排查规范

排查普通 MOD 在 Android 上无法加载、依赖缺失、初始化顺序异常或行为与 PC 不一致时，遵循以下约定：

- 可以把常用前置/依赖 MOD 仓库 clone 到工作区外或 `.agent/reference-repos/` 等不提交的位置，并 checkout 到与目标游戏版本、目标 MOD 版本匹配的 tag/branch/commit 后对照排查；不要把这些第三方源码或构建产物提交到本仓库。
- 优先参考对应版本 PC 原版/解包代码，尤其是 `ModManager`、依赖排序、manifest 解析、assembly resolve、资源加载和初始化回调的时序；重点确认 Android 兼容层是否漏掉某一步、提前/延后某一步，或改变了原版加载顺序导致 MOD 兼容问题。
- 对没有公开源码的 MOD，可以通过反编译其程序集获取可参考信息，用于定位入口类、manifest、依赖声明、Harmony patch、资源路径和初始化假设；反编译结果只作为本地诊断依据，不要提交第三方反编译源码或违反其许可条款。
- 常见前置/依赖仓库：
  - RitsuLib: <https://github.com/BAKAOLC/STS2-RitsuLib>
  - BaseLib-StS2: <https://github.com/Alchyr/BaseLib-StS2>

## 9. 构建 / 打包环境

### 9.1 Android/Gradle 版本

来自 `android/config.gradle` / `android/gradle.properties`：

- Android Gradle Plugin：`8.6.1`
- Gradle wrapper：`8.13`
- Kotlin plugin：`2.1.20`
- Steam 相关 Gradle 子模块：`android/steam-protocol`、`android/steam-content`；主要依赖 JavaSteam `1.6.0`、OkHttp `5.3.2`、protobuf `4.31.1`、AndroidX Security Crypto、Android Prefab zstd（Steam VZstd chunk native 解压）、XZ。
- compileSdk / targetSdk：`35`
- minSdk：`24`
- buildTools：`35.0.0`
- NDK：`28.1.13356709`
- CMake：`3.22.1`（用于 `libworkshop_zstd.so` JNI wrapper；Gradle 可按 SDK license 自动安装到 `../s2/.godot-home/Android/Sdk`）
- Java source/target：`17`
- flavor：`mono`
- 默认 build type：`release`（脚本执行 `assembleMonoRelease`）
- ABI：`arm64-v8a`
- applicationId：`com.megacrit.sts2re`
- versionName/versionCode：`0.1.0` / `1`
- 默认测试签名：`/home/wsdx233/.android/debug.keystore`

注意：`release` build type 当前仍保留 `debuggable true`，便于 sideload 后使用 `run-as` 验证；正式发布前必须重新审视签名、debuggable、混淆、资源优化、FileProvider 暴露范围。

本仓库构建复用 `../s2` 中准备好的 JDK/Android SDK。容器系统 `/usr/lib/jvm/java-21-openjdk-amd64` 可能只是 JRE，不能直接编译 Java；请使用：

```bash
tools/android/gradle-with-s2-env.sh <gradle-task>
```

或先：

```bash
source tools/android/env-from-s2.sh
```

### 9.2 运行时二进制同步

`android/assets/dotnet_bcl/`、`android/libs/`、`android/gradle/wrapper/gradle-wrapper.jar` 等大型/生成产物不应手写维护，使用：

```bash
tools/android/sync-runtime-from-references.sh
```

同步内容包括：Godot template AAR/native libs、`.NET/Godot` BCL/runtime DLL、crypto native jar、FMOD AAR（带 FMOD Java shim patch）、Gradle wrapper jar。

### 9.3 导入版 APK

导入版不内置游戏 zip，用户安装后在附加设置中选择本地 `SlayTheSpire2.zip`。

```bash
tools/package/build_importer_apk.sh
```

脚本流程：

1. `tools/android/sync-runtime-from-references.sh`
2. `tools/android/build-port-mod.sh`
3. `tools/android/stage-bundled-compat-packs.sh`
4. `tools/android/gradle-with-s2-env.sh assembleMonoRelease`（默认使用 debug keystore 参数签 release build）
5. 复制输出：
   - Gradle 产物：`android/build/outputs/apk/mono/release/sts2-re.apk`
   - 稳定副本：`dist/sts2-re-importer.apk`

### 9.4 直装版 APK

直装版在构建时临时把本地 PC zip 复制到 `android/assets/payload/SlayTheSpire2.zip`，首次启动自动解压/安装到 payload store `<files>/payloads/<payload_id>/game/` 并创建 launch profile。zip 复制有 trap 清理，不提交。

```bash
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

输出：

```text
android/build/outputs/apk/mono/release/sts2-re.apk
dist/sts2-re-direct.apk
```

### 9.5 常用检查命令

```bash
# payload zip 校验
tools/package/validate_payload_zip.py "/path/to/SlayTheSpire2.zip"

# 只编译 Java/Gradle 检查
tools/android/gradle-with-s2-env.sh :compileMonoDebugJavaWithJavac

# 只构建当前兼容 MOD fallback
tools/android/build-port-mod.sh

# 构建全部内置兼容包
tools/android/stage-bundled-compat-packs.sh
```

## 10. Git / 产物注意事项

- `.gitignore` 已排除：
  - `dist/`、`*.apk`、`*.aab`、`*.apks`
  - `local-inputs/`、用户 `*.zip`、keystore/jks/p12
  - `android/.gradle/`、`android/**/build/`
  - `android/assets/dotnet_bcl/`
  - `android/libs/`
  - `android/assets/payload/`
  - .NET `bin/` / `obj/`
- `android/assets/compat_packs/*.zip` 是明确允许的内置兼容包产物；提交前确认这些 zip 来自当前受控分支/脚本构建，且不含游戏 payload。
- 不要提交 `../s2_pc`、`../s2_original/*.zip` 或任何商业游戏资源。
- 修改 `port-mod/overlay` 后需要重新生成 `port_compat.pck`，并重新导出/复制内置兼容包。
- 修改 `tools/android/make-bootstrap-pck.py` 后需要重新生成 `android/assets/bootstrap.pck`。
- 修改 Java bridge 包名/类名要谨慎：C# helper 和 patched runtime 默认找 `com.godot.game.GodotApp`。
- 修改所有内置分支都必须带上的兼容层热修（例如 `AppPaths.cs`、`SavePathPatches.cs`、`LifecycleAndPerformancePatches.cs`、`TransitionMaterialPatches.cs`、`QuickRestartPatches.cs`）时，同步更新 `tools/android/stage-bundled-compat-packs.sh` 的 worktree 注入列表，确保 `v0.103.2` 与 `v0.106.1` 内置包都得到同一修复。
- 改 `applicationId` 时同步 shortcuts、FileProvider、manifest、Gradle 配置与所有 hard-coded target package。

仓库 / 子模块 HEAD 巡检：

```bash
tools/git/report-heads.sh
# 如需刷新远端引用：
tools/git/report-heads.sh --fetch
```

该脚本只读取/可选 fetch Git 信息，不修改工作区文件；适合提交前排查 `port-mod` 分支、父仓库记录的 submodule commit、dirty/upstream ahead-behind 状态。

## 11. 常用验证路径

本地构建：

```bash
tools/package/build_importer_apk.sh
# 或
tools/package/build_direct_apk.sh "/path/to/SlayTheSpire2.zip"
```

安装后建议检查：

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files
adb shell run-as com.megacrit.sts2re ls files/compat-packs
adb shell run-as com.megacrit.sts2re ls files/payloads
adb shell run-as com.megacrit.sts2re ls files/instances
adb shell run-as com.megacrit.sts2re cat files/launcher/selected_instance.json
adb shell run-as com.megacrit.sts2re ls files/.godot/mono/publish/arm64
```

重点 smoke test：

1. 首次打开进入欢迎向导/附加设置，而不是直接进游戏。
2. “版本”页能安装/显示内置兼容包，至少包含正式 `v0.103.2` 与 beta `v0.106.1` 对应包。
3. 导入版选择 PC zip 或 Steam 下载后，`files/payloads/<payload_id>/game/.payload_manifest.json` 存在，`files/payloads/<payload_id>/game/SlayTheSpire2.pck` 存在，并创建/选择 `files/instances/<profile_id>/instance.json`；切换版本不应复制回 `files/game/`。Steam 下载来源应在 manifest 中记录 `source.kind=steam_depot`。
4. 与 payload 版本匹配的兼容包会被自动选择；不匹配时启动前弹风险提示。
5. 点击启动后 logcat / 当前 profile 的 `files/instances/<profile_id>/logs/android-launch.log` 能看到 selected compatibility pack 和 `Loading imported game PCK`。
6. `files/.godot/mono/publish/arm64/STS2Mobile.dll` 来自当前选择的兼容包；`files/port_compat.pck` 已 staging。
7. 修改图形/输入/MOD 设置后，当前 profile 解析出的 settings（全局 `files/default/1/settings.save` 或隔离 `files/instances/<profile_id>/default/1/settings.save`）有对应字段。
8. 从游戏内打开附加设置、退出回设置、crash/log/file browser 页面不崩溃。
9. MOD master switch / 单 MOD disable 能在启动日志或游戏内 MOD 状态中反映；普通 MOD 从当前 profile 的 MOD 目录扫描（全局 `files/mods` 或隔离 `files/instances/<profile_id>/mods`），不走 Steam Workshop。
10. Beta `v0.106.1` payload 应使用 `sts2-android-compat-v0.106.1-beta`，正式 `v0.103.2` payload 应使用 `sts2-android-compat-v0.103.2`。
11. Steam 中心可登录/验证 refresh token；Steam Cloud 手动刷新/拉取/上传使用当前 launch profile 的 account root，拉取前在 `files/steam/cloud/<profile_id>/backups/` 创建备份。

## 12. 维护提醒

- 当前工程是“Android shell + payload/version manager + compat pack”的组合，不是传统 Android Studio `app/` 子模块结构；Gradle 根就在 `android/`。
- 实际打包推荐用 `tools/package/*.sh`，不要裸跑 Gradle，除非已同步 runtime、准备好环境并理解 compat pack staging。
- `settings.save` 的 Android-only key 是 Java 附加设置与 Harmony patcher/Java 启动参数的协议；改 key 要同步 `ExtraSettingsRepository`、页面 UI、`AndroidSettingsBridge` 或 `GodotApp.getCommandLine()` 等消费者、相关 patches，并记录到 `doc/changelog/`。`log_level` 额外同步到 SharedPreferences，避免原版游戏保存 settings 时丢失该 Android 字段。
- `<files>/default/<account>` 的账号选择逻辑与旧移植版兼容但较脆弱，多账号/自定义 platform player id 改动要同时检查 Java 与兼容 MOD。
- 当前普通 MOD 目录由 launch profile 决定：`mods_mode=global` 使用 `<files>/mods`，`mods_mode=isolated` 使用 `<files>/instances/<profile_id>/mods`；新增路径相关功能必须同步 Java 管理页、C# `AppPaths`、ModLoader patches 和迁移/备份逻辑。
- Steam Cloud 同步必须使用当前 launch profile 的 account root：`save_mode=global` 使用 `<files>/default/<account>`，`save_mode=isolated` 使用 `<files>/instances/<profile_id>/default/<account>`；不要把云存档固定写死到全局 `<files>/default/1`。
- 多版本兼容包的长期方向是 manifest 化、可安装、可选择、可诊断；不要把某一游戏版本的兼容 patch 直接写死到 Android shell。
- 对 beta 分支改动时务必用 `ReferenceFlavor=original-v0.106.1` 编译；对正式分支改动时务必用 `ReferenceFlavor=original` 编译。
- 新增兼容分支时需要同时增加：submodule 分支、原版 refs/ReferenceFlavor、compat manifest、`tools/android/bundled-compat-packs.json` 条目、文档版本矩阵、至少一次 importer APK 构建验证。

## 修改说明

完成用户要求的修改后，请用脚本构建一个 importer 版本 APK，便于用户测试：

```bash
tools/package/build_importer_apk.sh
```
