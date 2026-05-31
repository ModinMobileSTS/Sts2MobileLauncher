# Steam 登录、游戏下载与云存档接入计划

> 目标读者：后续实现该功能的编码代理 / 维护者。  
> 当前状态：已按当前多版本 payload / launch profile 模型落地首版实现；本文保留为设计说明与后续维护 checklist。
> 适用仓库：`/mnt/datas/agent_workspace/s2_re`。  
> 参考工程：`../ref/SlayTheAmethystModded/`。  
> 最后同步：2026-05-31。

## 0. 结论摘要

本启动器可以实现类似 `../ref/SlayTheAmethystModded/` 的三项 Steam 能力：

1. **Steam 登录**：Android launcher 侧完成账号密码登录、Steam Guard、refresh token 持久化。
2. **从 Steam 下载 STS2 游戏本体**：用用户自己的 Steam 账号通过 SteamPipe 下载 AppID `2868840` 对应 depot，并安装到当前 payload store：`<files>/payloads/<payload_id>/game/`，随后创建/选择 launch profile。
3. **Steam 云存档**：由 launcher 侧同步 Steam Cloud 文件到当前 launch profile 解析出的 account root（全局 `<files>/default/<account>/` 或隔离 `<files>/instances/<profile_id>/default/<account>/`），不要在 Android 游戏进程里恢复桌面 Steamworks。

但这三项都不能直接照搬参考项目：参考项目是 STS1 / AppID `646570` / 单个 `desktop-1.0.jar` 下载；本项目是 STS2 / AppID `2868840` / Godot + Mono + 多文件 payload / 多版本 compat pack。

当前落地策略已按“一次性首版”实现，不再把功能拆成用户可见阶段：

```text
Steam center Activity
  -> 登录 / refresh token 验证 / 注销
  -> SteamPipe 下载 STS2 payload 到 staging
  -> PayloadManager.importPayloadDirectory(...) 安装到 payload store
  -> LaunchProfileManager 创建/选择 profile 并匹配 compat pack
  -> Steam Cloud 手动清单/拉取/上传/强制上传
  -> 可选启动前拉取、干净退出 marker 后上传
```

原计划中的 Phase 0~5 保留为设计来源和后续强化检查项，但本仓库当前实现已把登录、下载、手动云同步和自动挂点放入同一测试版本。

## 1. 范围与非目标

### 1.1 目标

- 在 Android launcher 中提供 Steam 账号登录入口。
- 保存 refresh token，后续自动连接 Steam CM，不在启动游戏关键路径上要求用户输入 2FA。
- 支持从 Steam 下载用户账号拥有的 STS2 PC 游戏本体，并复用现有 payload 导入、校验、PCK patch、payload store 安装、launch profile 创建/选择和 compat pack 匹配流程。
- 支持 Steam Cloud 存档同步：先手动，后自动；先保守拉取/上传，后再考虑镜像删除。
- 保持现有合规边界：仓库、APK 默认构建产物、文档中都不包含商业游戏资源、用户 zip、解压 payload、账号凭据或私钥。

### 1.2 非目标

- 不把游戏本体打包进仓库。
- 不提供绕过 Steam 授权的下载能力。
- 不在 Android 游戏进程内启用桌面 `Steamworks.NET` / `steam_api64.dll`。
- v1 不恢复 Steam Workshop 扫描；普通 MOD 仍走 `<files>/mods/` 与 NexusMods/本地导入路径。
- v1 不自动删除远端 Steam Cloud 文件。
- v1 不把 `settings.save` 原样强制上传到 PC 云端，除非先完成 Android-only 字段剥离/合并策略。

## 2. 必须遵守的项目边界

本仓库当前定位见：

- `AGENTS.md`
- `README.md`
- `doc/architecture/project-structure.md`
- `doc/build/building-and-packaging.md`
- `doc/runtime/compat-pack-loading-flow.md`

重要约束：

- 当前 APK 默认进入 `GameSettingsActivity`。
- 当前游戏本体由 `PayloadManager` 导入到 `<files>/payloads/<payload_id>/game/`，不再复制到固定 `<files>/game/`。
- 当前版本页管理 `<files>/instances/<profile_id>/instance.json` launch profile；同一 payload 可创建多个全局/隔离存档与 MOD 配置。旧 `<files>/game-versions/<id>/game/` 仅作为迁移来源。
- 当前 compat pack 绑定在 launch profile 上，并按该 profile 绑定 payload manifest 的 `release_info.version` 匹配。
- `port-mod` 兼容层明确跳过桌面 Steam 初始化与 Steam Workshop 枚举。
- 普通 MOD 不在本计划内改成 Steam Workshop 下载；未来若做，应作为独立计划。

## 3. 当前项目参考路径

### 3.1 Android launcher 入口与 UI

- `android/src/com/godot/game/GameSettingsActivity.java`
  - 默认 launcher Activity。
  - `launchGame()` 做 payload / compat pack 检查。
  - `prepareAndStartGame()` 调 `GameLaunchPreparationManager.prepareForLaunch()` 后启动 `GodotApp`。
  - 当前启动 `GodotApp` 后会 `finish()`，这会影响“游戏退出后自动云上传”的挂点设计。
- `android/src/com/godot/game/GamePage.java`
  - 首页“游戏本体”“存档状态”“启动游戏”等卡片。
  - 适合新增“Steam 登录状态”“从 Steam 下载游戏本体”入口。
- `android/src/com/godot/game/SettingsPage.java`
  - 适合新增“Steam Cloud 存档同步”设置卡片。
- `android/src/com/godot/game/ExtraSettingsActions.java`
  - 页面与 Activity 之间的操作接口；新增 Steam 操作时需扩展。
- `android/AndroidManifest.xml`
  - 已有 `INTERNET` / `ACCESS_NETWORK_STATE` 权限。
  - 新增登录/Guard/下载进度 Activity 或 foreground service 时需登记。

### 3.2 Payload / 版本 / 启动准备

- `android/src/com/godot/game/PayloadManager.java`
  - 现有 zip 导入、校验、PCK patch、manifest 写入、原子替换逻辑。
  - Steam 下载完成后应复用这里，而不是另写一套安装逻辑。
- `android/src/com/godot/game/LaunchProfileManager.java`
  - Steam 下载/zip 导入成功后创建或选择绑定 payload 的 launch profile，并解析当前 profile 的 save/mod/log/compat 路径。
- `android/src/com/godot/game/GameBodyVersionManager.java`
  - legacy facade；当前版本选择委托给 `LaunchProfileManager`。
- `android/src/com/godot/game/CompatPackManager.java`
  - 根据 payload manifest 匹配兼容包。
- `android/src/com/godot/game/GameLaunchPreparationManager.java`
  - 启动前复制 runtime / payload assemblies / compat dll / overlay。
- `android/src/com/godot/game/GodotApp.java`
  - 真正的 Godot Activity。
  - `getCommandLine()` 使用当前 launch profile 绑定的 `<files>/payloads/<payload_id>/game/SlayTheSpire2.pck`。
  - 暴露 `getGodotDataDir()` 给 C# 兼容层。

### 3.3 存档与设置

- `android/src/com/godot/game/ExtraSettingsRepository.java`
  - `exportSaveZip()` / `importSaveZip()`：当前本地存档导入导出。
  - `getAccountRootDir()` / `LaunchProfileManager.getSelectedAccountRootDir()`：当前账号根；profile 为 global 时默认 `<files>/default/1/`，profile 为 isolated 时使用 `<files>/instances/<profile_id>/default/1/`。
  - `getSettingsFile()`：当前账号根下的 `settings.save`。
  - `transferModSaveProfiles()`：普通存档与 `modded/` 存档互转，可作为云同步路径设计参考。
- `port-mod/STS2AndroidPortCompat/Android/AppPaths.cs`
  - C# 侧解析 `DataDir`、`GameDir`、`AccountRoot`、`SettingsPath`、`ModsDir`。
- `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsMerge.cs`
  - 保存 `settings.save` 时保护 Android-only 字段。
- `port-mod/STS2AndroidPortCompat/Patches/AndroidSettingsPatches.cs`
  - companion settings 投影到游戏运行时。

### 3.4 兼容层 Steam 相关现状

- `port-mod/STS2AndroidPortCompat/Patches/PlatformPatches.cs`
  - 跳过桌面 Steam 初始化。
  - patch `PrefsSave.UploadData` 等平台相关逻辑。
- `port-mod/STS2AndroidPortCompat/Patches/ModLoaderPatches.cs`
  - 跳过 `ReadSteamMods()`，不枚举 Steam Workshop。
- `port-mod/STS2AndroidPortCompat/Patches/LanMultiplayerPatches.cs`
  - 使用 `PlatformType.None` / Android 自定义 player id 逻辑。

结论：Steam 登录、下载、云同步应在 **Java/Kotlin launcher 侧**实现；不要让游戏主体认为桌面 Steamworks 可用。

### 3.5 构建配置

- `android/settings.gradle`
  - 当前只 include `:assetPackInstallTime`。
  - 如引入 `steam-protocol` / `steam-content` Gradle 子模块，需要在这里 include。
- `android/build.gradle`
  - 当前 app 模块依赖 Material、DocumentFile、CrashActivity 等。
  - 需要增加 OkHttp、Protobuf、Security Crypto、coroutines 等依赖。
- `android/config.gradle`
  - Kotlin `2.1.20`，Java 17。
- `tools/package/build_importer_apk.sh`
  - 最终实现后必须用该脚本验证导入版 APK。

## 4. 参考工程路径

### 4.1 Steam 登录 / 凭据 / Guard

- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudClient.java`
  - JavaSteam 版 Steam Cloud 客户端。
  - 已实现 credential auth、Guard prompt、refresh token 登录、Steam Cloud list/download/upload。
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAuthStore.kt`
  - `EncryptedSharedPreferences` 保存账号名、refresh token、guard data、SteamID64。
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAuthCoordinator.kt`
  - 登录编排与诊断输出。
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/settings/SteamCloudLoginScreen.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/settings/SteamCloudGuardScreen.kt`
  - Compose UI，不能直接拷贝到本项目，但交互状态机可参考。

### 4.2 自研 Steam protocol / SteamPipe

- `../ref/SlayTheAmethystModded/steam-protocol/src/main/kotlin/top/apricityx/workshop/steam/protocol/`
  - `OkHttpSteamCmSession.kt`
  - `SteamAuthenticationClient.kt`
  - `SteamDirectoryClient.kt`
  - `SteamContentClient.kt`
  - `SteamMachineId.kt`
  - `Models.kt`
- `../ref/SlayTheAmethystModded/steam-protocol/src/main/proto/steam_messages.proto`
- `../ref/SlayTheAmethystModded/steam-protocol/src/main/proto/content_manifest.proto`
- `../ref/SlayTheAmethystModded/workshop-core/src/main/kotlin/top/apricityx/workshop/workshop/`
  - `DepotManifest.kt`
  - `SteamCdnTransport.kt`
  - `SteamDepotSingleFileDownloader.kt`
  - `WorkshopChunkProcessor.kt`
  - `WorkshopFileIntegrity.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steam/SteamStsJarDownloadService.kt`
  - 单文件 `desktop-1.0.jar` 下载示例。
  - 只能作为 SteamPipe 流程参考；STS2 需要多文件 depot 下载器。

### 4.3 参考项目云存档设计

- `../ref/SlayTheAmethystModded/docs/steam-cloud-launcher-integration.md`
  - 已验证 STS1 云端文件清单、路径映射和分阶段接入策略。
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudPathMapper.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudLocalSnapshotCollector.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudBaselineStore.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudDiffPlanner.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudPullCoordinator.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudPushCoordinator.kt`
- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/config/SteamCloudSaveMode.kt`

### 4.4 参考项目依赖

- `../ref/SlayTheAmethystModded/app/build.gradle.kts`
  - `in.dragonbra:javasteam:1.6.0`
  - OkHttp BOM / OkHttp
  - Protobuf Java
  - AndroidX Security Crypto
  - BouncyCastle
  - coroutines / serialization
- `../ref/SlayTheAmethystModded/gradle/libs.versions.toml`
  - 依赖版本来源。

## 5. STS2 原版存档路径参考

本计划中的云存档路径必须以真实 Steam Cloud 清单为准，但本地路径可参考已解包 STS2 原版源码：

- `../s2_original/s21032/src/Core/Saves/UserDataPathProvider.cs`
  - `GetAccountScopedBasePath(null)` 生成 account root。
  - `GetProfileDir(profileId)` 生成 `profile1` 或 `modded/profile1`。
- `../s2_original/s21032/src/Core/Saves/SaveManager.cs`
  - `ConstructDefault()`：Steam 初始化后原版会包一层 `CloudSaveStore`。
  - `SyncCloudToLocal()`：原版启动时同步 profile、progress、run、prefs、history。
  - `TryFirstTimeCloudSync()`：云为空且本地有档时上传本地。
- `../s2_original/s21032/src/Core/Platform/Steam/SteamRemoteSaveStore.cs`
  - 原版 Steam RemoteStorage 的 path canonicalize 规则：去掉 `user://`，统一 `/`。
- `../s2_original/s21032/src/Core/Saves/CloudSaveStore.cs`
  - 原版本地/云双写、拉取、目录同步、quota forget 逻辑。
- `../s2_original/s21032/src/Core/Saves/Managers/`
  - `SettingsSaveManager.cs`：`settings.save`
  - `ProfileSaveManager.cs`：`profile.save`
  - `ProgressSaveManager.cs`：`profileN/saves/progress.save`
  - `PrefsSaveManager.cs`：`profileN/saves/prefs.save`
  - `RunSaveManager.cs`：`profileN/saves/current_run.save` / `current_run_mp.save`
  - `RunHistorySaveManager.cs`：`profileN/saves/history/*.run`

本地 Android 当前默认根：

```text
<files>/default/1/
  settings.save
  profile.save
  profile1/saves/progress.save
  profile1/saves/prefs.save
  profile1/saves/current_run.save
  profile1/saves/current_run_mp.save
  profile1/saves/history/*.run
  profile2/saves/...
  profile3/saves/...
  modded/profile1/saves/...
```

## 6. 总体架构设计

### 6.1 分层

建议新增四层：

```text
Steam protocol layer
  - Steam CM websocket
  - Authentication service
  - ContentServerDirectory / SteamPipe
  - Cloud RemoteStorage RPC/HTTP

Steam service layer
  - 登录状态和凭据管理
  - STS2 appinfo/depot/manifest 解析
  - payload 下载任务
  - 云存档清单/下载/上传

Launcher orchestration layer
  - Activity / service / notification
  - 进度、暂停、取消、错误提示
  - 启动前/退出后挂点

Existing project integration layer
  - PayloadManager
  - LaunchProfileManager / GameBodyVersionManager legacy facade
  - CompatPackManager
  - ExtraSettingsRepository
  - GameLaunchPreparationManager
```

### 6.2 推荐新增目录

如果保持当前 Java shell 风格，建议在 `android/src/com/godot/game/steam/` 下放 Android 相关代码：

```text
android/src/com/godot/game/steam/
  auth/
    SteamAuthStore.java|kt
    SteamLoginCoordinator.java|kt
    SteamAccountSnapshot.java|kt
    SteamGuardChallenge.java|kt
  core/
    SteamClientIdentity.java|kt
    SteamNetworkClientFactory.java|kt
    SteamDiagnosticsStore.java|kt
  download/
    Sts2SteamAppInfoResolver.java|kt
    Sts2SteamDepotResolver.java|kt
    Sts2SteamPayloadDownloadService.java|kt
    Sts2SteamPayloadDownloadManager.java|kt
    Sts2SteamPayloadInstallRequest.java|kt
    Sts2SteamDownloadTaskStore.java|kt
  cloud/
    Sts2SteamCloudClient.java|kt
    Sts2SteamCloudPathMapper.java|kt
    Sts2SteamCloudManifestStore.java|kt
    Sts2SteamCloudBaselineStore.java|kt
    Sts2SteamCloudLocalSnapshotCollector.java|kt
    Sts2SteamCloudDiffPlanner.java|kt
    Sts2SteamCloudPullCoordinator.java|kt
    Sts2SteamCloudPushCoordinator.java|kt
  ui/
    SteamAccountActivity.java
    SteamLoginActivity.java
    SteamGuardActivity.java
    SteamPayloadDownloadActivity.java
    SteamCloudActivity.java
```

纯协议代码建议作为 Gradle 子模块，便于后续测试和避免污染 launcher UI：

```text
android/steam-protocol/       # 从参考 steam-protocol 裁剪/迁移
android/steam-content/        # 从参考 workshop-core 裁剪 SteamPipe 下载能力
```

`android/settings.gradle` 需要新增：

```gradle
include ':steam-protocol'
include ':steam-content'
```

`android/build.gradle` app dependencies 需要新增：

```gradle
implementation project(':steam-protocol')
implementation project(':steam-content')
implementation platform('com.squareup.okhttp3:okhttp-bom:<version>')
implementation 'com.squareup.okhttp3:okhttp'
implementation 'androidx.security:security-crypto:<version>'
implementation 'com.google.protobuf:protobuf-javalite:<version>'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>'
implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:<version>'
implementation 'in.dragonbra:javasteam:1.6.0' // 若云存档沿用 JavaSteam SteamCloudClient
```

是否引入 `javasteam` 需要在 Phase 0 决定：

- 若云存档直接迁移参考 `SteamCloudClient.java`，需要 `javasteam`。
- 若完全走自研 `steam-protocol` protobuf/RPC，需要补齐 Cloud RPC proto 和实现，可减少 JavaSteam 依赖但工作量更高。
- `SteamStsJarDownloadService.kt` 使用 JavaSteam `KeyValue` 解析 appinfo；也可以改为自写 VDF parser，避免仅为 appinfo 引入 JavaSteam。

## 7. Phase 0：只读验证 spike

### 7.1 目标

在不改现有用户流程的前提下验证三件事：

1. Android 上可登录 Steam，并保存/复用 refresh token。
2. 对 STS2 AppID `2868840` 可读取 appinfo、depot、branch、manifest。
3. 对 STS2 可列出 Steam Cloud 真实远端文件路径。

### 7.2 建议新增调试入口

先做隐藏/开发者入口，避免未稳定功能暴露给普通用户：

- `SettingsPage` 的日志/高级区域增加“Steam 诊断”按钮；或
- 新增 `SteamDiagnosticsActivity`，只在 debug/release debuggable 本地测试中使用。

### 7.3 AppID 与分支

STS2 商店页面来自当前代码：

- `android/src/com/godot/game/AboutPage.java`
  - `https://store.steampowered.com/app/2868840/Slay_the_Spire_2/`

计划常量：

```text
STS2_STEAM_APP_ID = 2868840
DEFAULT_BRANCH = public
KNOWN_SUPPORTED_GAME_VERSIONS = v0.103.2, v0.106.1
```

Phase 0 输出文件建议：

```text
<files>/steam/diagnostics/login-summary.txt
<files>/steam/diagnostics/appinfo-2868840.txt
<files>/steam/diagnostics/depot-candidates.json
<files>/steam/diagnostics/cloud-list.tsv
```

输出中禁止写入密码、refresh token、完整 access token。账号名可 mask。

### 7.4 验证清单

- 登录成功后能拿到：
  - `accountName`
  - `steamId64`
  - `refreshToken`
  - `guardData`（可能为空）
- refresh token 二次连接成功。
- appinfo 中能找到 depots。
- manifest 中能找到至少以下目标文件：

```text
SlayTheSpire2.pck
release_info.json
data_sts2_windows_x86_64/sts2.dll
data_sts2_windows_x86_64/sts2.deps.json
data_sts2_windows_x86_64/sts2.runtimeconfig.json
```

- 云端清单真实路径被记录，例如：

```text
remote_path<TAB>size<TAB>timestamp<TAB>sha1<TAB>machine
```

### 7.5 Phase 0 不做的事

- 不安装 payload。
- 不覆盖本地存档。
- 不上传云存档。
- 不添加自动同步。

## 8. Phase 1：Steam 登录正式接入

### 8.1 凭据存储

参考：

- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAuthStore.kt`

本项目建议新增：

```text
android/src/com/godot/game/steam/auth/SteamAuthStore.kt
```

存储字段：

```text
account_name
steam_id_64
refresh_token
guard_data
last_auth_at_ms
last_successful_connect_at_ms
last_error
```

实现要求：

- 使用 `EncryptedSharedPreferences`。
- 读写失败时清理损坏的 encrypted prefs 并提示重新登录。
- 任何日志不得输出 token 明文。
- 提供 `clear()` 清除账号。
- 可选：实现 `revokeRefreshToken()`，退出登录时调用 Steam Authentication revoke。

### 8.2 登录状态机

参考：

- `SteamAuthenticationClient.beginAuthSession()`
- `SteamCredentialAuthSession.submitGuardCode()`
- `SteamCredentialAuthSession.pollStatus()`
- `SteamCloudClient.AuthPrompt`

本项目 UI 建议：

```text
SteamAccountActivity
  - 显示已登录账号 / SteamID64 / 最近同步时间
  - 登录 / 注销 / Steam 诊断

SteamLoginActivity
  - 输入账号密码
  - 开始登录

SteamGuardActivity
  - 根据 challenge 类型显示：
    - 手机 App 确认
    - 手机令牌动态码
    - 邮箱验证码
  - 提交后轮询直到拿到 refresh token
```

如果不想新增多个 Activity，也可以先在 `GameSettingsActivity` 中使用 Dialog，但多 Activity 更利于处理旋转、后台、输入法和长轮询。

### 8.3 设备身份

参考：

- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/workshop/WorkshopSteamClientIdentity.kt`
- `../ref/SlayTheAmethystModded/steam-protocol/src/main/kotlin/.../SteamMachineId.kt`

本项目建议新增：

```text
android/src/com/godot/game/steam/core/SteamClientIdentity.kt
```

生成规则：

- 优先 `Settings.Secure.ANDROID_ID`。
- 若不可用，生成并持久化 installation UUID。
- 组合 `packageName`、设备 manufacturer/model/fingerprint，生成 Steam machine id。
- machine name 建议：`STS2 Android Launcher`。

### 8.4 网络层

建议新增：

```text
android/src/com/godot/game/steam/core/SteamNetworkClientFactory.kt
```

v1 先只做直连 OkHttp：

- connect timeout：15s~40s。
- read timeout：60s。
- call timeout：120s。
- retryOnConnectionFailure：true。

参考项目的 Watt/加速链路：

- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAcceleratedHttp.kt`

本项目不建议一开始引入复杂加速链路。可在 Phase 4+ 增加“高级网络设置”。

## 9. Phase 2：Steam 下载 STS2 payload

### 9.1 下载流程总览

目标流程：

```text
用户点击“从 Steam 下载游戏本体”
  -> 检查 Steam 登录状态
  -> 选择 branch / 版本
  -> 解析 appinfo 与 depot candidates
  -> 下载 manifest
  -> 确认 manifest 含 payload 必需文件
  -> 估算大小与磁盘空间
  -> 前台服务下载所有文件到 staging
  -> 校验文件完整性
  -> 调 PayloadManager.installFromDirectory(...)
  -> 写 .payload_manifest.json，source.kind=steam_depot
  -> 安装到 payload store 并创建/选择 launch profile
  -> 自动匹配 compat pack
```

### 9.2 UI 入口

建议在 `GamePage.buildPayloadCard()` 当前按钮组下新增：

- `Steam 登录 / 账号` 按钮。
- `从 Steam 下载游戏本体` 按钮。
- 如果未登录，点击下载先跳转 Steam 登录。
- 如果已登录，进入 `SteamPayloadDownloadActivity`。

需要扩展：

- `ExtraSettingsActions`
  - `openSteamAccount()`
  - `requestDownloadGamePayloadFromSteam()`
- `GameSettingsActivity`
  - 实现上述方法。
- `strings_game_settings.xml` / `values-zh/strings_game_settings.xml`
  - 增加按钮、状态、错误、确认文案。

### 9.3 Depot 解析

参考：

- `SteamStsJarDownloadService.parseAppInfo()`
- `SteamStsJarDownloadService.resolveDepotCandidates()`
- `SteamContentClient.getManifestRequestCode()`
- `SteamCmSession.requestDepotDecryptionKey()`

本项目建议新增：

```text
android/src/com/godot/game/steam/download/Sts2SteamAppInfoResolver.kt
android/src/com/godot/game/steam/download/Sts2SteamDepotResolver.kt
```

输出模型：

```kotlin
data class Sts2DepotManifestCandidate(
    val appId: UInt,
    val depotId: UInt,
    val branch: String,
    val manifestId: ULong,
    val osList: List<String>,
    val language: String?,
    val isSharedDepot: Boolean,
)
```

解析规则：

- 默认 branch：`public`。
- beta branch 需要 Phase 0 实测 branch 名称；不要硬编码 `beta` 后直接上线。
- depots 可能有 `depotfromapp`，参考项目已有递归处理。
- 优先选择 manifest 中包含 `SlayTheSpire2.pck` 和 `data_sts2_windows_x86_64/sts2.dll` 的 depot 集合。
- 如果多个 depot 分散包含不同文件，需要组合下载多个 depot。

### 9.4 多文件下载器

参考单文件下载器：

- `../ref/SlayTheAmethystModded/workshop-core/src/main/kotlin/top/apricityx/workshop/workshop/SteamDepotSingleFileDownloader.kt`

需扩展为多文件：

```text
android/steam-content/src/main/kotlin/.../SteamDepotDirectoryDownloader.kt
```

请求模型：

```kotlin
data class SteamDepotDirectoryDownloadRequest(
    val appId: UInt,
    val depotId: UInt,
    val manifestId: ULong,
    val branch: String,
    val outputRoot: File,
    val depotKey: ByteArray,
    val includePredicate: (ManifestFile) -> Boolean,
)
```

下载行为：

- 下载 depot manifest。
- 若 `filenamesEncrypted`，调用 `manifest.decryptFilenames(depotKey)`。
- 跳过 directory / symlink / link target。
- 对每个文件创建安全相对路径，禁止 `..`、绝对路径、空 segment。
- 每个文件按 chunk 下载：
  - CDN path：`depot/<depotId>/chunk/<chunkId>`。
  - `ChunkProcessor.process(raw, chunk, depotKey)` 解压/解密。
  - 写入 `RandomAccessFile` 对应 offset。
- 文件完成后用 `WorkshopFileIntegrityVerifier` 校验。
- 支持进度：文件数、当前文件、字节、百分比。
- 支持取消：下载循环中检查 cancellation flag。

v1 可不做断点续传，但建议保存 task manifest，避免失败后难排查：

```text
<files>/steam/downloads/current-task.json
<files>/steam/downloads/staging-<id>/
<files>/steam/downloads/logs/<id>.txt
```

v2 再做断点续传：记录每个文件已验证 chunks。

### 9.5 Foreground service

下载完整 STS2 体积较大，不建议只靠 Activity 线程。建议新增：

```text
android/src/com/godot/game/steam/download/Sts2SteamPayloadDownloadService.java|kt
```

Manifest 需要：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" tools:targetApi="33" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" tools:targetApi="34" />

<service
    android:name=".steam.download.Sts2SteamPayloadDownloadService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

注意：本项目 minSdk 24 / targetSdk 35，Android 13+ 通知权限、Android 14 foreground service type 都要处理。

如果 v1 为降低复杂度先不用 foreground service，至少要求：

- 下载 Activity 常驻；
- 明确提示后台可能中断；
- 支持失败后清理 staging；
- 不在 `GameSettingsActivity` 主线程下载。

### 9.6 PayloadManager 改造

当前 `PayloadManager.installFromZip(...)` 是私有 zip 流程。Steam 下载应避免“目录 -> zip -> 再解压”的浪费。建议抽出公共安装入口：

```java
public Status importPayloadDirectory(
    File sourceDirectory,
    SourceInfo source,
    ProgressListener progressListener,
    ImportControl control
) throws Exception
```

内部复用：

- `validateGameDir(staging)`
- `patchPayloadPck(staging)`
- `writeManifest(staging, source, validation, patchResult)`
- 原子安装到 `<files>/payloads/<payload_id>/game/`，不覆盖其它 payload 或 profile
- rollback / cleanup

`SourceInfo` 建议扩展或新增 JSON extras：

```json
{
  "kind": "steam_depot",
  "display_name": "Steam App 2868840 public",
  "app_id": 2868840,
  "branch": "public",
  "depots": [
    {
      "depot_id": 0,
      "manifest_id": "0"
    }
  ],
  "size": 0,
  "sha256": ""
}
```

Steam 多文件下载没有单个 source zip sha256，可在 manifest 中记录：

- `source.kind=steam_depot`
- `source.steam.app_id`
- `source.steam.branch`
- `source.steam.depots[]`
- `source.steam.downloaded_at_unix`
- `source.steam.file_count`
- `source.steam.total_bytes`

保留 `identity.sts2_dll_sha256` 和 `identity.pck_sha256_after_patch`，用于 compat pack 匹配/诊断。

### 9.7 版本与 compat pack

下载完成后必须执行当前导入成功后的相同行为：

1. `LaunchProfileManager.createOrSelectDefaultProfileForPayload(payload, true)`
2. `CompatPackManager.findBestMatch(payload.manifest)`
3. 将匹配包写入当前 launch profile / selected compat 记录
4. 刷新版本页/首页；切换版本只切换 profile 指针，不复制 PCK

如果 Steam 下载到的版本不在当前支持矩阵：

- 允许安装，但启动前按现有逻辑提示兼容包不匹配。
- 下载页应提前显示“当前兼容包支持版本：v0.103.2 / v0.106.1-beta；Steam 分支可能下载到未支持版本”。

## 10. Phase 3：Steam Cloud 手动拉取 / 清单

### 10.1 先确认真实远端路径

不要直接套用 STS1 参考项目的：

```text
%GameInstall%preferences/...
%GameInstall%saves/...
```

STS2 必须通过 Phase 0 对 AppID `2868840` 实测。输出示例：

```text
remote_path                      size  timestamp  sha1  machine
profile.save                     ...   ...        ...   ...
profile1/saves/progress.save     ...   ...        ...   ...
profile1/saves/prefs.save        ...   ...        ...   ...
profile1/saves/current_run.save  ...   ...        ...   ...
```

如果 Steam 返回 `%GameInstall%` 前缀，则在 mapper 中单独处理。

### 10.2 本地路径映射

已新增/建议维护：

```text
android/src/com/godot/game/steam/cloud/Sts2SteamCloudPathMapper.java
```

映射以当前 launch profile 的 account root 为本地根：`LaunchProfileManager.getSelectedAccountRootDir()`；`save_mode=global` 时是 `<files>/default/<account>/`，`save_mode=isolated` 时是 `<files>/instances/<profile_id>/default/<account>/`。v1 支持本地相对路径：

```text
profile.save
profile1/saves/progress.save
profile1/saves/prefs.save
profile1/saves/current_run.save
profile1/saves/current_run.save.backup
profile1/saves/current_run_mp.save
profile1/saves/current_run_mp.save.backup
profile1/saves/history/*.run
profile2/saves/...
profile3/saves/...
modded/profile1/saves/...
modded/profile2/saves/...
modded/profile3/saves/...
```

`settings.save` v1 建议默认排除，原因：

- Android launcher 在里面保存 Android-only key。
- PC 端是否忽略所有未知字段需验证。
- 本项目已有 `AndroidSettingsMerge` 保护本地字段，但还没有“上传前剥离 / 下载后 merge”的 Java 侧实现。

可提供高级开关：

```text
同步 settings.save（实验性，可能影响 PC 设置）
```

开启后必须实现：

- 拉取前备份本地 `settings.save`。
- 下载远端 `settings.save` 后，把本地 Android-only key merge 回来。
- 上传前可选择剥离 Android-only key。

### 10.3 手动拉取流程

建议新增：

```text
Sts2SteamCloudPullCoordinator.pullAll(...)
```

流程：

```text
读取 SteamAuthStore
  -> refresh token 登录
  -> list remote cloud files
  -> mapper 过滤支持路径
  -> 创建本地备份 zip
  -> 下载到 staging
  -> 校验 size / sha1
  -> 原子替换对应本地文件
  -> 写 baseline
  -> 写 manifest snapshot
```

备份位置建议：

```text
<files>/steam/cloud/backups/sts2-steam-cloud-pull-backup-YYYYMMDD-HHMMSS.zip
```

也可提供“导出到 Downloads”的按钮，但自动拉取前必须至少有私有备份。

### 10.4 清单与状态文件

建议：

```text
<files>/steam/cloud/manifest.json
<files>/steam/cloud/baseline.json
<files>/steam/cloud/pull-summary.txt
<files>/steam/cloud/push-summary.txt
<files>/steam/cloud/diagnostics/*.txt
```

不要放到当前 account root 下面，避免被游戏误扫或被云同步。

## 11. Phase 4：Steam Cloud 上传 / 冲突处理

### 11.1 baseline 模型

参考：

- `SteamCloudBaselineStore.kt`
- `SteamCloudDiffPlanner.kt`
- `SteamCloudSyncModels.kt`

本项目模型：

```kotlin
data class Sts2CloudLocalEntry(
    val localRelativePath: String,
    val size: Long,
    val lastModifiedMs: Long,
    val sha256: String,
    val sha1: String,
)

data class Sts2CloudRemoteEntry(
    val remotePath: String,
    val localRelativePath: String,
    val size: Long,
    val timestampMs: Long,
    val sha1: String,
    val machineName: String,
    val persistState: String,
)

data class Sts2CloudSyncBaseline(
    val syncedAtMs: Long,
    val localEntries: List<Sts2CloudLocalEntry>,
    val remoteEntries: List<Sts2CloudRemoteEntry>,
)
```

### 11.2 冲突规则

不要只比时间戳。使用 baseline 判定：

```text
localChanged  = 当前本地 hash/存在性 相比 baseline local 变化
remoteChanged = 当前远端 size/timestamp/sha1/存在性 相比 baseline remote 变化
```

处理：

| 情况 | 行为 |
| --- | --- |
| 仅本地变化 | 可上传 |
| 仅远端变化 | 可拉取 |
| 本地和远端都变化且 sha1 不同 | 冲突，禁止自动覆盖 |
| 双端内容 sha1 相同 | 刷新 baseline |
| 本地删除 | v1 不自动删除远端，只记录 warning |
| 远端删除 | v1 不自动删除本地，只记录 warning |

### 11.3 上传流程

建议新增：

```text
Sts2SteamCloudPushCoordinator.buildUploadPlan(...)
Sts2SteamCloudPushCoordinator.pushLocalChanges(...)
```

流程：

```text
refresh remote manifest
  -> collect local snapshot
  -> read baseline
  -> build diff plan
  -> if conflicts: show conflict UI
  -> begin upload batch
  -> upload changed files
  -> complete upload batch
  -> refresh remote manifest
  -> write baseline
```

如果沿用参考 `SteamCloudClient.java`，可复用：

- `beginUploadBatch()`
- `uploadFile()`
- `completeUploadBatch()`

但要把 appId 改为 `2868840`，并替换路径 mapper。

### 11.4 强制覆盖操作

提供两个明确高风险按钮：

- “使用本地覆盖云端”
- “使用云端覆盖本地”

要求：

- 必须二次确认。
- 必须显示文件数量、预计影响、是否包含 `settings.save`。
- “云端覆盖本地”前必须备份本地。
- “本地覆盖云端”v1 不执行远端删除；如果未来支持镜像删除，需单独三次确认。

## 12. Phase 5：自动同步

### 12.1 启动前自动拉取

挂点：

- `GameSettingsActivity.launchGame()`
- 在 payload / compat 检查通过后，`prepareAndStartGame()` 之前。

逻辑：

```text
if Steam Cloud 模式启用 && 已登录:
  refresh remote manifest
  build diff plan
  if 仅远端变化:
    备份本地
    拉取远端
    继续启动
  if 仅本地变化:
    可选择先上传，也可延后到退出后；v1 建议不阻止启动
  if 冲突:
    弹窗：使用本地 / 使用云端 / 跳过同步继续启动 / 取消启动
  if 网络失败:
    弹窗：重试 / 跳过同步继续启动 / 取消启动
```

不建议在首次实现时把登录/Guard 放进启动链路。若 token 失效，提示用户去 Steam 账号页重新登录。

### 12.2 干净退出后自动上传

当前项目难点：`GameSettingsActivity.prepareAndStartGame()` 启动 `GodotApp` 后调用 `finish()`，没有像参考项目那样完整的 `LauncherReturnAction.ExpectedCleanShutdown` 分析链路。

首版已实现手动上传，并补充“干净退出”标记用于可选自动上传。

当前设计：

1. 新增 marker：

```text
<files>/launcher/expected_clean_game_exit.json
```

2. 在 C# 兼容层 / Java bridge 中标记：

- `port-mod/STS2AndroidPortCompat/Patches/ExternalSettingsPatches.cs`
  - 退出回设置前已经调用 `SaveSettings()`、`SavePrefsFile()`、`SaveProgressFile()`、`SaveProfile()`。
  - 可在调用 `GodotApp.restartToSettingsFromGame()` 前增加 Java bridge：`GodotApp.markExpectedCleanGameExit(String source)`。
- `android/src/com/godot/game/GodotApp.java`
  - 新增静态方法写 marker。
  - `restartToSettingsAndExitProcess()` 也可写 marker。

3. `GameSettingsActivity.onCreate()` / `onResume()` 检查 marker：

```text
if marker recent && Steam Cloud 模式启用 && 已登录:
  触发上传计划
  如果仅本地变化 -> 自动上传
  如果冲突/远端也变 -> 不自动上传，显示提示
```

4. 异常退出、崩溃、系统杀进程时不写 marker，不自动上传。

### 12.3 自动同步开关

建议设置项：

```text
Steam Cloud 模式：
  - 关闭：完全不自动同步
  - 手动：只显示按钮
  - 启动前拉取：启动前检查远端变化
  - 启动前拉取 + 干净退出后上传：完整自动同步
```

v1 默认：关闭或手动。

## 13. UI 设计建议

### 13.1 GamePage：Steam 游戏本体卡片

在 `GamePage.buildPayloadCard()` 增加：

- 已登录状态：账号名 / SteamID64。
- 未登录状态：提示“使用 Steam 下载需要登录你拥有 STS2 的账号”。
- 按钮：
  - `登录 Steam`
  - `从 Steam 下载游戏本体`
  - `查看 Steam 诊断`

下载页显示：

- AppID `2868840`
- branch
- 预计下载大小
- 当前文件
- 总进度
- 暂停 / 继续 / 取消
- 下载完成后版本：来自 `release_info.json`
- 匹配 compat pack 结果

### 13.2 SettingsPage：Steam Cloud 卡片

在 `SettingsPage.buildSaveCard()` 下方或新增 `buildSteamCloudCard()`：

- 登录状态。
- 云存档模式。
- 最近清单刷新时间。
- 最近拉取/上传时间。
- 远端文件数。
- 本地文件数。
- 冲突数量。
- 按钮：
  - 刷新清单
  - 拉取云端到本地
  - 上传本地到云端
  - 冲突详情
  - 设置同步排除项
  - 清除 Steam 凭据

### 13.3 重要提示文案

必须提示：

- 该功能只使用用户自己的 Steam 账号下载到本机私有目录。
- 不会把游戏本体上传或提交到仓库。
- 云存档覆盖本地前会备份。
- `settings.save` 同步可能影响 PC 设置，默认关闭。
- 网络/Steam 服务不稳定时可继续使用本地导入 zip。

## 14. 安全与隐私

### 14.1 凭据

- 密码只在登录请求期间驻留内存，不持久化。
- refresh token 使用 `EncryptedSharedPreferences`。
- 日志中只记录 token 是否存在和长度，不记录内容。
- 诊断文件默认不包含账号密码/token。
- 导出日志时需 redact：

```text
refresh_token=***
access_token=***
guard_data=***
```

### 14.2 文件安全

- Steam depot 文件名必须做 Zip Slip 等价防护：禁止绝对路径、`..`、空 segment。
- 下载 staging 不得直接覆盖 `<files>/payloads/<payload_id>/game/`；必须经 `PayloadManager.importPayloadDirectory(...)` 校验、patch 和原子安装。
- 云拉取 staging 不得直接覆盖当前 launch profile account root。
- 覆盖前必须备份。
- 失败时 rollback。

### 14.3 合规

- 不提交下载到的 depot 文件。
- 不提交云存档。
- 不提交账号诊断中的个人信息。
- 文档和日志中不要记录用户私有 zip hash 以外的敏感数据；即便记录 hash，也仅用于本地诊断。

## 15. 测试计划

### 15.1 单元测试

建议对纯逻辑模块加 JVM test：

- `SteamCloudPathMapper`
- `SteamCloudDiffPlanner`
- `LocalSnapshotCollector`
- `Depot path sanitizer`
- `Payload source manifest writer`

测试样例：

```text
profile1/saves/progress.save
profile1/saves/history/123.run
modded/profile1/saves/progress.save
../evil
/profile1/saves/progress.save
profile1//saves
settings.save 默认排除
```

### 15.2 Phase 0 实机验证

```bash
adb install -r dist/sts2-re-importer.apk
adb shell run-as com.megacrit.sts2re ls files/steam/diagnostics
adb shell run-as com.megacrit.sts2re cat files/steam/diagnostics/depot-candidates.json
adb shell run-as com.megacrit.sts2re cat files/steam/diagnostics/cloud-list.tsv
```

### 15.3 Steam 下载验证

场景：

1. 未登录点击下载 -> 跳登录。
2. 登录账号未拥有 STS2 -> 清晰错误。
3. public branch 下载 -> payload ready。
4. beta branch 下载 -> payload version 正确。
5. 下载中断 -> staging 清理或可恢复。
6. 空间不足 -> 下载前阻止。
7. 下载版本未匹配 compat pack -> 启动前风险提示。

完成后检查：

```bash
adb shell run-as com.megacrit.sts2re ls files/payloads
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name release_info.json
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name .payload_manifest.json
adb shell run-as com.megacrit.sts2re ls files/instances
adb shell run-as com.megacrit.sts2re cat files/launcher/selected_compat_pack.json
```

### 15.4 云存档验证

场景：

1. 本地无档、云端有档 -> 手动拉取成功。
2. 本地有档、云端无档 -> 不自动删除本地；可手动上传。
3. 双端同时变化 -> 冲突。
4. 云拉取前本地备份存在。
5. `settings.save` 默认不上传。
6. modded 存档路径被保留。
7. Steam token 过期 -> 提示重新登录。
8. 网络失败 -> 不覆盖本地。

检查：

```bash
adb shell run-as com.megacrit.sts2re ls files/default/1
adb shell run-as com.megacrit.sts2re ls files/steam/cloud
adb shell run-as com.megacrit.sts2re cat files/steam/cloud/baseline.json
adb shell run-as com.megacrit.sts2re ls files/steam/cloud/backups
```

### 15.5 回归测试

- 不登录 Steam 时，本地 zip 导入流程不变。
- NexusMods 商店不受影响。
- 兼容包安装/选择不受影响。
- `tools/package/build_importer_apk.sh` 构建通过。
- `tools/package/build_direct_apk.sh` 仍能临时内置 zip。
- 无 Steam 功能时启动游戏不增加等待。

## 16. 实施 checklist

### 16.1 准备

- [ ] Phase 0 前确认是否允许引入 `javasteam`。
- [ ] 确认 `android/` Gradle 多模块方案。
- [ ] 从参考项目迁移 `steam-protocol` 的许可证/NOTICE 记录。
- [ ] 更新 `THIRD_PARTY_LICENSES.md`（如果新增依赖）。

### 16.2 登录

- [ ] 新增 `SteamAuthStore`。
- [ ] 新增 `SteamClientIdentity`。
- [ ] 新增登录 Activity / Guard Activity。
- [ ] 保存 refresh token。
- [ ] 二次连接验证。
- [ ] 清除账号。
- [ ] 日志 redaction。

### 16.3 下载

- [ ] AppID `2868840` appinfo 解析。
- [ ] branch 选择。
- [ ] depot/manifest candidates 解析。
- [ ] 多文件 depot downloader。
- [ ] foreground service / 进度 UI。
- [ ] `PayloadManager.importPayloadDirectory()`。
- [ ] payload manifest 记录 Steam source。
- [ ] 下载后归档版本并匹配 compat pack。

### 16.4 云存档

- [ ] 真实远端路径枚举。
- [ ] `Sts2SteamCloudPathMapper`。
- [ ] 本地 snapshot。
- [ ] manifest store。
- [ ] baseline store。
- [ ] diff planner。
- [ ] 手动 pull + 本地备份。
- [ ] 手动 push。
- [ ] 冲突 UI。
- [ ] `settings.save` 默认排除，或实现 merge 后再开放。

### 16.5 自动同步

- [ ] 启动前检查挂到 `GameSettingsActivity.launchGame()`。
- [ ] 干净退出 marker。
- [ ] `GodotApp.markExpectedCleanGameExit()` Java bridge。
- [ ] `ExternalSettingsPatches` 退出前写 marker。
- [ ] `GameSettingsActivity` 回到设置页后触发上传计划。
- [ ] 异常退出不上传。

### 16.6 文档与构建

- [ ] 更新 `AGENTS.md` 版本/功能说明。
- [ ] 更新 `README.md` 用户说明。
- [ ] 更新 `doc/architecture/project-structure.md` 运行时目录。
- [ ] 更新 `doc/build/building-and-packaging.md` 依赖和构建。
- [ ] 更新 `doc/runtime/compat-pack-loading-flow.md` 如果新增 Java bridge/退出 marker。
- [ ] 新增 changelog。
- [ ] 构建 `tools/package/build_importer_apk.sh`。

## 17. 主要风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Steam 协议在 Android 上不稳定 | 登录/下载失败 | Phase 0 单独验证；保留本地 zip 导入作为主路径 |
| AppID/depot/branch 解析错误 | 下载错误版本或缺文件 | 以 manifest 必需文件校验为准；下载后仍走 `PayloadManager` 校验 |
| Steam 默认分支更新到未支持版本 | 下载后无法安全启动 | 启动前 compat mismatch 提示；下载页显示支持版本 |
| 下载体积大导致后台被杀 | 用户体验差、staging 残留 | foreground service、任务状态、清理/恢复 |
| 云路径映射错误 | 存档覆盖错误 | Phase 0 真实枚举；v1 只支持白名单路径；覆盖前备份 |
| `settings.save` 污染 PC 设置 | PC 设置异常 | 默认排除；实现 merge/strip 后再允许 |
| 异常退出后自动上传坏档 | 云端存档损坏 | 只有干净退出 marker 后自动上传；冲突不自动覆盖 |
| token 泄漏 | 账号风险 | EncryptedSharedPreferences、日志 redaction、不导出 token |

## 18. 最终建议

首版已按用户要求一次性接入登录、SteamPipe 下载、手动云同步与自动挂点。后续测试重点应放在真实 Steam 账号的 appinfo/depot 分支枚举、不同游戏版本 payload 的 compat pack 匹配、以及当前 launch profile（全局/隔离存档）与 Steam Cloud 路径映射是否完全符合 PC 端。

Steam 下载必须继续复用 payload store + launch profile 模型；Steam Cloud 必须继续复用当前 launch profile 的 account root；二者都不应改变 `port-mod` 当前“禁用桌面 Steamworks”的基本不变式。
