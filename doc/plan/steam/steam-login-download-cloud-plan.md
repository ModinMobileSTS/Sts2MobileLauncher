# Steam 登录、游戏下载与云存档接入计划

> 目标读者：后续实现该功能的编码代理 / 维护者。  
> 当前状态：已按当前多版本 payload / launch profile 模型落地首版实现，并将 credential auth 改为前台服务持有的可恢复事务；SteamPipe 本体下载已具备 Steam CDN proxy/origin 路由、有界 chunk 并发与校验续传。本文保留为设计说明与后续维护 checklist。
> 适用仓库：本仓库根目录。
> 参考工程：`../ref/SlayTheAmethystModded/`。  
> 最后同步：2026-07-14。

## 0. 结论摘要

本启动器可以实现类似 `../ref/SlayTheAmethystModded/` 的三项 Steam 能力：

1. **Steam 登录**：Android launcher 侧完成账号密码登录、Steam Guard、refresh token 持久化；未完成的认证由 `dataSync` 前台服务作为可恢复事务持有，切到 Steam App 批准时不依赖 Activity 存活、小窗或分屏。
2. **从 Steam 下载 STS2 游戏本体**：用用户自己的 Steam 账号通过 SteamPipe 下载 AppID `2868840` 对应 depot，并安装到当前 payload store：`<files>/payloads/<payload_id>/game/`，随后创建/选择 launch profile。
3. **Steam 云存档**：由 launcher 侧同步 Steam Cloud 文件到当前 launch profile 解析出的 account root（全局 `<files>/default/<account>/` 或隔离 `<files>/instances/<profile_id>/default/<account>/`），不要在 Android 游戏进程里恢复桌面 Steamworks。

但这三项都不能直接照搬参考项目：参考项目是 STS1 / AppID `646570` / 单个 `desktop-1.0.jar` 下载；本项目是 STS2 / AppID `2868840` / Godot + Mono + 多文件 payload / 多版本 compat pack。

当前落地策略已按“一次性首版”实现，不再把功能拆成用户可见阶段：

```text
Steam center Activity
  -> 通过 bound service 提交仅驻留内存的账号密码 / Guard code
  -> SteamAuthForegroundService 持有短期 transaction handle、手机确认轮询与 CM 重连
  -> 登录完成后原子提交 refresh token / 验证 / 注销
  -> SteamPipe 下载 STS2 payload 到稳定 fingerprint 任务目录并校验续传
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
    SteamAuthStore.java
    SteamAuthTransactionManager.kt
    SteamAuthForegroundService.kt
    SteamLoginCoordinator.java
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
KNOWN_SUPPORTED_GAME_VERSIONS = v0.103.2, v0.103.3, v0.106.1, v0.107.0, v0.107.1, v0.108.0
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

当前实现：

```text
android/src/com/godot/game/steam/auth/SteamAuthStore.java
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
pending_auth_transaction
```

实现要求：

- 使用 `EncryptedSharedPreferences`。
- `pending_auth_transaction` 是默认 4 分钟有效的短期 handle，保存 transaction generation、账号名、Steam `client_id` / `request_id` / SteamID、poll interval、challenge 列表、已选择 challenge、phase、`new_client_id`、创建/截止时间，以及 Steam 已返回的 reusable `guard_data`。它不得包含账号密码、加密密码、用户本次输入的 Guard code、refresh token 或 access token。
- 这里的 `guard_data` 是 Steam 返回、可随下一次 BeginAuth 复用的设备数据，不是用户看到或输入的一次性 Guard 动态码；动态码始终只驻留内存。
- 读写失败时清理损坏的 encrypted prefs 并提示重新登录。
- 任何日志不得输出 token 明文。
- 提供 `clear()` 清除账号。
- 成功时只有当前 pending transaction id 仍匹配，才能在一次同步 commit 中写入 refresh token 并删除 handle；旧 generation 的迟到轮询不得覆盖新登录。
- 取消、过期或 handle 解析失败只清理 pending transaction；不要连带删除已经保存的 refresh token。用户明确“退出登录”才清除完整账号材料。
- 可选：实现 `revokeRefreshToken()`，退出登录时调用 Steam Authentication revoke。

### 8.2 登录状态机

参考：

- `SteamAuthenticationClient.beginAuthSession()`
- `SteamCredentialAuthSession.submitGuardCode()`
- `SteamCredentialAuthSession.pollStatus()`
- `SteamCloudClient.AuthPrompt`

当前职责划分：

```text
SteamAccountActivity
  - 显示已登录账号 / SteamID64 / 最近同步时间
  - 收集账号密码或 Guard code，并仅通过进程内 LocalBinder 交给 service
  - 绑定观察事务快照；onStop 只注销 observer / unbind
  - 提供打开 Steam App、继续等待和显式取消

SteamAuthForegroundService (foregroundServiceType=dataSync)
  - transaction 的唯一网络 owner
  - 前台通知、轮询、CM reconnect、deadline、terminal cleanup
  - Activity/配置重建/可恢复进程重启后从 encrypted handle 恢复

SteamAuthTransactionManager
  - begin / resume / selectChallenge / submitGuardCode / pollOnce
  - 每次 phase 或 new_client_id 变化后 generation-safe 持久化
  - 成功 compare-and-commit，取消/过期只清 pending
```

状态机与不变量：

```text
IDLE
  -> PREPARING_FOREGROUND
  -> BEGIN_AUTH (password only in binder/service memory)
  -> PERSIST_HANDLE
  -> WAITING_GUARD_CODE --submit in memory--> POLLING
  -> WAITING_REMOTE_CONFIRMATION ---------> POLLING immediately
  -> RECONNECTING ------------------------> POLLING with persisted ids
  -> SUCCESS / CANCELLED / EXPIRED / FAILED
```

1. Activity 先启动不含敏感 extra 的前台 service，再绑定并调用 `begin(account, password)`；密码不得放入 Intent/Bundle、saved instance state、通知或磁盘。若进程在 BeginAuth 返回 handle 前死亡，密码无法恢复，用户重新输入是预期行为。
2. BeginAuth 成功后先同步加密保存 handle，才允许进入等待 UI。此后 Activity `onStop()` 只解绑：按 Home、打开 Steam App、旋转或 Activity 重建都不得调用 cancel。
3. `DeviceConfirmation` / `EmailConfirmation` 被选中后先保存 phase，再立即调用 PollAuthSessionStatus；“打开 Steam”只是便利入口，不是开始轮询的开关，也不再提供必须先点的“已批准”按钮。用户可正常全屏切换应用，不需要小窗、分屏或 WakeLock。
4. `DeviceCode` / `EmailCode` 等输入 challenge 等待 UI 通过 binder 提交；code 只用于当次调用且不落盘。提交后保存 `POLLING` phase，进程随后被回收仍可从 handle 恢复。
5. 服务使用 Steam 返回的 poll interval，并在每次 poll 返回 `new_client_id` 后先更新 handle。CM WebSocket/transport 断开时，建立新的未认证 CM 连接，复用 handle 中原 `client_id` / `request_id`（以及最新 `new_client_id`）继续旧事务，不能重新提交密码创建平行 generation。
6. 普通后台期间由 `dataSync` 前台服务及低重要度 ongoing 通知承载事务。服务被系统按可恢复路径重建或 Activity 再次进入时，以 encrypted handle 为状态事实来源；Android 的用户显式 force-stop 不保证即时自动重启，但重新打开应用后仍可恢复未过期 handle。
7. 成功返回 token 时以 transaction id compare-and-commit：同一次持久化提交写入账号/SteamID/refresh token/新 guard data 并删除 pending handle。若用户已取消或开始了新 generation，旧轮询结果必须作为 superseded 丢弃。
8. 用户取消、默认 4 分钟 deadline 到期、handle 损坏或明确 fatal failure 会停止通知/网络并清理对应 pending handle；transport 短暂失败应优先进入 reconnect/backoff，不立刻清事务。取消 pending 登录不等于退出已登录账号。

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

当前网络层由 `SteamNetworkClientFactory` 提供共享 OkHttp 配置，SteamPipe manifest/chunk 进一步统一经过 `android/steam-content/.../SteamCdnTransport.kt`：

- Steam directory/content server 响应中的 `use_as_proxy`、`proxy_request_path_template` 与 `bypass_proxies_of_type` 会保留到 `CdnServer` 模型。请求按协议显式生成 proxy route 与 origin route；可用时先尝试 Steam 返回的 proxy endpoint，再回落同一源 CDN 的 origin endpoint，不再用 endpoint 数据类相等误判“已经是代理”。
- 该 proxy 能力只接受 Steam directory/content API 返回的服务器，不复用 Steam Community/API/图片的 `steamcommunity.rmbgame.net` / `steamstore.rmbgame.net` 兼容访问，因此 depot auth token 不会发送给这些第三方兼容域名。
- CDN token 保存 Steam 返回的 expiration，并在过期前刷新；同一 host 的并发 chunk 在 403/过期刷新时通过 singleflight 合并，避免重复 CM token 请求，也不会把已被拒绝的旧 token 再从 cache 返回。
- manifest/chunk 响应可校验预期长度；chunk 会以 manifest 的 compressed length 为准，长度不匹配视为当前路由失败并进入后续回退/重试。
- OkHttp Call 通过 cancellable coroutine 异步等待；用户取消或父协程失败会立即 `Call.cancel()`，不会被同步 `execute()` 的长 read/call timeout 卡住。

参考项目的 Watt/兼容访问实现仍可用于理解网络受限环境，但不得绕过以上 depot token 边界：

- `../ref/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAcceleratedHttp.kt`

## 9. Phase 2：Steam 下载 STS2 payload

### 9.1 下载流程总览

当前流程：

```text
用户点击“从 Steam 下载游戏本体”
  -> 检查 Steam 登录状态
  -> 选择 branch / 版本
  -> 解析 appinfo 与 depot candidates
  -> 下载 manifest
  -> 确认 manifest 含 payload 必需文件
  -> 估算大小与磁盘空间
  -> 将 prepared manifest 交给目录下载器，避免重复拉取
  -> 以 1 / 2 / 4 个 chunk worker 下载到 payload-<fingerprint> 任务目录
  -> 单 writer 落盘；校验并复用完整文件 / *.steam.part 已有 chunk
  -> 校验文件完整性
  -> 调 PayloadManager.importPayloadDirectory(...)
  -> 写 .payload_manifest.json，source.kind=steam_depot
  -> 安装到 payload store 并创建/选择 launch profile
  -> 新建 launch profile 时按版本填入推荐 compat pack
```

### 9.2 UI 入口

当前入口位于 `SteamAccountActivity` 的游戏下载页：

- 未登录时点击下载会先要求登录 Steam；已登录后可选择 `public`、`public-beta` 或自定义 branch。
- “并发下载分块”提供 1 / 2 / 4 三档，默认 2。1 适合内存/网络较弱设备，2 是吞吐与资源占用的默认平衡，4 适合连接与设备资源较好的环境。
- 下载进行中会锁定并发设置，显示文件/chunk/字节进度和停止按钮；停止通过 `PayloadManager.ImportControl` 传播到 coroutine，并取消正在执行的 OkHttp Call。
- 当前 payload worker 由 Steam 中心 Activity 启动的后台线程持有；它不阻塞 UI 主线程，但还不是跨 Activity/进程恢复的独立 foreground download service。

### 9.3 Depot 解析

参考：

- `SteamStsJarDownloadService.parseAppInfo()`
- `SteamStsJarDownloadService.resolveDepotCandidates()`
- `SteamContentClient.getManifestRequestCode()`
- `SteamCmSession.requestDepotDecryptionKey()`

当前 appinfo/depot 解析集中在：

```text
android/src/com/godot/game/steam/download/Sts2SteamPayloadDownloader.kt
```

内部候选模型：

```kotlin
data class DepotManifestCandidate(
    val appId: UInt,
    val depotId: UInt,
    val branch: String,
    val manifestId: ULong,
)
```

解析规则：

- 默认 branch：`public`。
- UI 的 beta 预设映射为已支持矩阵使用的 `public-beta`，同时保留自定义 branch。
- depots 可能有 `depotfromapp`，参考项目已有递归处理。
- 优先选择 manifest 中包含 `SlayTheSpire2.pck` 和 `data_sts2_windows_x86_64/sts2.dll` 的 depot 集合。
- 如果多个 depot 分散包含不同文件，需要组合下载多个 depot。

### 9.4 多文件下载器

参考单文件下载器：

- `../ref/SlayTheAmethystModded/workshop-core/src/main/kotlin/top/apricityx/workshop/workshop/SteamDepotSingleFileDownloader.kt`

当前多文件实现：

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
    val preparedManifest: PreparedDepotManifest? = null,
    val maxConcurrentChunks: Int = 1,
)
```

下载行为：

- 优先复用 appinfo/depot 筛选阶段传入的 `preparedManifest`；只有独立调用且未传入时才下载 depot manifest，并校验 prepared manifest 的 app/depot/manifest 身份。
- 若 `filenamesEncrypted`，调用 `manifest.decryptFilenames(depotKey)`。
- 跳过 directory / symlink / link target。
- 对每个文件创建安全相对路径，禁止 `..`、绝对路径、空 segment。
- 先按 chunk id 规划唯一下载项和一个或多个文件 offset destination；相同 chunk 只下载/处理一次：
  - CDN path：`depot/<depotId>/chunk/<chunkId>`。
  - `ChunkProcessor.process(raw, chunk, depotKey)` 解压/解密。
  - worker 数按请求限制；游戏本体 UI 只暴露 1 / 2 / 4，默认 2。worker 输出进入容量 0/1 的有界结果通道，单 writer 按 offset 写入 `RandomAccessFile`，避免多个 worker 同时修改文件。
  - 在途 reservation 包含压缩和解压后大小；总预算取 JVM 最大堆约 `1/12`，最少 16 MiB、最多 64 MiB。超过预算的单个 chunk 允许独占执行，不会死锁。
- 文件先写入同目录 `*.steam.part`。重试同一任务时，已有完整正式文件通过长度/manifest 完整性校验后复用；part 文件逐 chunk 读取并校验 Steam Adler checksum，已验证区间计入进度，只下载缺失/损坏 chunk。
- writer `fsync` 并关闭后，使用 `WorkshopFileIntegrityVerifier` 做最终文件校验；通过后才用 rename 把 part 原子改为正式文件名。校验失败保留 part 供诊断/后续按 chunk 修复，不把不完整文件交给 `PayloadManager`。
- 进度包括文件数、chunk 数、当前文件、字节和百分比；高频 worker 完成事件会合并，避免压垮 UI。
- 取消由轮询 `ImportControl` 的 coroutine 子任务传播；`CancellationException` / `InterruptedException` 不进入普通 CDN 重试，活动 OkHttp Call 会被取消。

任务目录与续传已落地，不再使用随机 `staging-*` 的 v1/v2 占位方案：

```text
<files>/steam/downloads/payload-<fingerprint>/
  <payload files>
  <unfinished file>.steam.part
```

fingerprint 包含下载布局版本、规范化 branch 和已选 app/depot/manifest 组合。同一组合再次下载会复用该目录；下载与最终 `PayloadManager` 安装全程持有 `<files>/steam/downloads/locks/payload-download.lock` 全局文件锁，Activity 重建或不同 branch 任务不能并行修改 staging/payload store。旧 `staging-*` / `failed-*` 会直接清理，其他 `payload-*` fingerprint 任务只在超过 7 天后清理。

### 9.5 下载任务生命周期

当前本体下载由 `SteamAccountActivity` 创建命名后台线程，UI 主线程只接收合并后的进度；停止按钮通过 `PayloadManager.ImportControl` 取消下载。网络层的 cancellable coroutine 会继续取消活动 OkHttp Call，稳定 fingerprint 目录和 `*.steam.part` 则保留已校验数据，重新进入 Steam 中心后可重新发起同一 branch/manifest 组合完成续传。

当前实现不是跨 Activity/进程长期持有的 payload foreground service；离开/销毁 Steam 中心期间不承诺下载继续。若后续把本体任务迁移为独立服务，仍应复用现有 fingerprint、prepared manifest、有界 pipeline 与取消协议，并处理本项目 targetSdk 对通知权限和 `foregroundServiceType=dataSync` 的要求，不能另建一套随机 staging 下载器。

### 9.6 PayloadManager 目录导入

Steam 下载避免“目录 -> zip -> 再解压”，直接使用已经落地的公共目录安装入口：

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

`SourceInfo` 当前通过 JSON extras 写入 Steam 来源信息；`.payload_manifest.json` 的关键结构包括：

```json
{
  "source": {
    "kind": "steam_depot",
    "display_name": "Steam App 2868840 / public",
    "size": 0,
    "sha256": "",
    "steam": {
      "app_id": 2868840,
      "branch": "public",
      "concurrent_chunks": 2,
      "depots": [
        {
          "depot_id": 0,
          "manifest_id": "0"
        }
      ]
    }
  }
}
```

Steam 多文件下载没有单个 source zip sha256，可在 manifest 中记录：

- `source.kind=steam_depot`
- `source.steam.app_id`
- `source.steam.branch`
- `source.steam.concurrent_chunks`
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
- 下载页应提前显示“当前兼容包支持版本：v0.103.x / v0.106.1-beta / v0.107.0-beta / v0.107.1 / v0.108.0；Steam 分支可能下载到未支持版本”。

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
- 本应用不会上传任何 Steam 账号信息，只在本地加密保存 refresh token；应只在从可信来源下载的 APK 中输入 Steam 账号信息。
- 安装未知 MOD 后建议退出 Steam 登录，避免潜在恶意 MOD 读取本地 refresh token / RT 的账号风险。
- 不会把游戏本体上传或提交到仓库。
- 云存档覆盖本地前会备份；用户使用云存档前也建议主动备份，避免意外故障造成存档丢失。
- `settings.save` 同步可能影响 PC 设置，默认关闭。
- 网络/Steam 服务不稳定时可继续使用本地导入 zip；国内使用 Steam 相关服务可能需要开启加速器。

## 14. 安全与隐私

### 14.1 凭据

- 密码与用户本次输入的 Guard 动态码只在 Activity 到 bound service 的进程内调用和对应 Steam 请求期间驻留内存，不持久化，也不进入 Intent、Bundle、saved instance state 或通知。
- 未完成登录只在 `EncryptedSharedPreferences` 中短期保存 transaction handle；handle 默认 4 分钟过期，包含续接 PollAuthSessionStatus 所需的路由/phase/deadline，不包含密码、Guard code、refresh token 或 access token。
- refresh token 使用 `EncryptedSharedPreferences`。
- 日志中只记录 token 是否存在和长度，不记录内容。
- 诊断文件默认不包含账号密码、Guard code 或 token；账号名与协议路由标识如需记录，应尽量 mask，并确保它们不能用于还原凭据。
- Activity 离开前台不代表用户取消；只有明确取消、过期或 fatal failure 才清理 pending transaction。成功提交必须校验 transaction generation，避免迟到结果覆盖新登录。
- 导出日志时需 redact：

```text
refresh_token=***
access_token=***
guard_data=***
guard_code=***
password=***
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
- `SteamAuthTransactionHandle` JSON round-trip、deadline 与 unknown challenge
- credential auth fake CM：poll 返回 `new_client_id` 后持久化、transport 重连复用 client/request id、旧 generation 禁止 commit

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

### 15.3 可恢复 Steam 认证实机验证

必须用装有 Steam App 的 Android 实机覆盖：

1. **正常手机确认**：进入“在 Steam App 批准”状态时前台通知已经出现且轮询已经开始；不点任何“已批准”按钮，直接切到 Steam App 批准再返回，应自动完成并保存 refresh token。
2. **普通切后台**：按 Home、打开其他 App、在最近任务间切换数次；`SteamAccountActivity.onStop()` 只解绑，前台服务、deadline 与轮询继续。整个流程不得依赖小窗或分屏。
3. **Activity 重建**：等待确认/轮询时旋转、切换语言/显示尺寸或让系统重建 Activity；返回后 UI 重新绑定现有事务，不创建第二次 BeginAuth。
4. **CM 断线恢复**：handle 已保存后切换 Wi-Fi/移动网络，短暂飞行模式后恢复；服务应显示 reconnecting，并用持久化的 client/request id 建立新 CM 连接继续 poll。
5. **进程恢复**：事务在后台时执行 `adb shell am kill com.megacrit.sts2re`；系统重建服务或用户重新进入 Steam 中心后，未过期 handle 继续。若杀进程发生在 BeginAuth 返回 handle 之前，应安全要求重新输入密码。`force-stop` 不作为“自动重启”保证，但重新打开后可恢复未过期 handle。
6. **Guard code**：分别验证 DeviceCode / EmailCode。输入 code 前杀进程允许要求重新输入；提交后进入 polling 则可恢复。用 logcat、私有偏好字符串扫描和代码审查确认密码/Guard code 没有明文落盘或进入 Intent/日志。
7. **取消/过期**：等待确认和等待 code 两种阶段分别取消；确认通知停止、socket 关闭、对应 pending handle 删除，而既有已登录 refresh token 不被误删。等待超过默认 4 分钟也应自动清理。
8. **generation 竞争**：取消事务 A 后立刻开始事务 B，让 A 的 poll 尽量迟到；A 不得提交 token 或清掉 B。成功事务 B 应在一次原子 commit 中保存 token 并删除自身 handle。
9. **通知权限**：Android 13+ 允许和拒绝 `POST_NOTIFICATIONS` 各测一次；拒绝权限不得破坏 service/handle 状态，返回 Activity 后仍可观察和取消。
10. **失败分类**：错误 Guard code 保留当前事务并允许重输；短暂网络错误进入 reconnect/backoff；fatal auth error 清 pending 并给出可理解提示。

辅助检查：

```bash
adb shell dumpsys activity services com.megacrit.sts2re
adb logcat | rg 'Sts2SteamAuth|SteamAuthForegroundService'
```

### 15.4 Steam 下载验证

场景：

1. 未登录点击下载 -> 跳登录。
2. 登录账号未拥有 STS2 -> 清晰错误。
3. public branch 下载 -> payload ready。
4. beta branch 下载 -> payload version 正确。
5. 下载中断 -> 当前 OkHttp Call 被取消；`payload-<fingerprint>` 中已验证的完整文件 / `*.steam.part` chunk 保留，重新下载同一组合时只补缺失内容。
6. 空间不足 -> 下载前阻止。
7. 下载版本未匹配 compat pack -> 启动前风险提示。
8. 并发分别选择 1 / 2 / 4 -> 三档都能完成并得到相同 payload，默认新安装为 2；运行期间不能修改设置。
9. Steam directory 返回 `use_as_proxy` -> 先命中 proxy route；代理故障时能回落 origin；`bypass_proxies_of_type` 命中时直接 origin。
10. 403/过期 CDN token -> 同 host 并发只触发一次刷新；日志与异常不得包含 token，rmbgame 兼容域名不得收到 depot token。
11. CDN 返回长度不匹配或 chunk 校验失败 -> 不 finalize 文件，重试/下次续传会修复对应 chunk。

完成后检查：

```bash
adb shell run-as com.megacrit.sts2re ls files/payloads
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name release_info.json
adb shell run-as com.megacrit.sts2re find files/payloads -maxdepth 3 -name .payload_manifest.json
adb shell run-as com.megacrit.sts2re find files/steam/downloads -maxdepth 3 -name '*.steam.part'
adb shell run-as com.megacrit.sts2re ls files/instances
adb shell run-as com.megacrit.sts2re cat files/launcher/selected_compat_pack.json
```

### 15.5 云存档验证

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

### 15.6 回归测试

- 不登录 Steam 时，本地 zip 导入流程不变。
- NexusMods 商店不受影响。
- 兼容包安装不受影响；具体使用哪个兼容包仍由启动配置保存。
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

- [x] 新增 `SteamAuthStore` 与 generation-safe pending transaction 存储。
- [x] 新增 `SteamClientIdentity`。
- [x] 在 `SteamAccountActivity` 中实现登录/Guard UI，并通过 bound service 观察状态。
- [x] 新增 `SteamAuthForegroundService` / `SteamAuthTransactionManager`，以 `dataSync` 前台任务持有可恢复事务。
- [x] 密码与 Guard code 只走进程内调用；短期 handle 加密落盘并带 deadline。
- [x] 手机确认立即轮询；CM 断线复用 client/request id 重连。
- [x] refresh token generation-matched 原子提交，取消/过期清理 pending。
- [x] 二次连接验证、清除账号与日志 redaction。
- [ ] 完成 15.3 的多设备/OEM/Android 版本实机矩阵并记录结果。

### 16.3 下载

- [x] AppID `2868840` appinfo 解析。
- [x] `public` / `public-beta` / 自定义 branch 选择。
- [x] depot/manifest candidates 解析，并把 prepared manifest 复用到正式下载。
- [x] 多文件 depot downloader：有界 chunk worker、单 writer、16–64 MiB 自适应预算。
- [x] Steam `use_as_proxy` proxy→origin fallback、bypass 规则、token expiration/singleflight 与 cancellable OkHttp Call。
- [x] 稳定 `payload-<fingerprint>` 任务目录、完整文件复用、`*.steam.part` chunk 校验续传、全局 payload 下载/安装文件锁与 7 天旧任务清理。
- [x] 进度/取消 UI 和 1 / 2 / 4（默认 2）并发设置。
- [ ] 将 payload 下载从 Activity worker 迁移为可跨 Activity/进程持有的 foreground service（当前不阻塞主线程，取消与续传已实现）。
- [x] `PayloadManager.importPayloadDirectory()`。
- [x] payload manifest 记录 Steam source、depots 与 `concurrent_chunks`。
- [x] 下载后归档版本并匹配 compat pack。

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
| 切到 Steam App 时 Activity/CM 被挂起 | 手机确认无法完成 | Activity 只解绑；`dataSync` 前台服务立即轮询；短期 handle 恢复；CM 用持久化 routing id 重连，不依赖小窗 |
| 旧认证轮询迟到 | 覆盖新账号/token | transaction generation compare-and-commit；取消/替换后旧结果视为 superseded |
| AppID/depot/branch 解析错误 | 下载错误版本或缺文件 | 以 manifest 必需文件校验为准；下载后仍走 `PayloadManager` 校验 |
| Steam 默认分支更新到未支持版本 | 下载后无法安全启动 | 启动前 compat mismatch 提示；下载页显示支持版本 |
| 下载体积大导致 Activity/进程中断 | 用户体验差、重复下载 | 稳定 fingerprint 任务目录、完整文件/part chunk 校验续传、其他任务 7 天清理；后续可迁移 foreground service |
| chunk 并发导致内存或文件竞争 | OOM、payload 损坏 | UI 限制 1/2/4 默认 2；有界结果通道；16–64 MiB 自适应预算；单 writer；finalize 前完整性校验 |
| CDN 代理或 token 路由错误 | 下载失败或凭据泄漏 | 只接受 Steam 返回的 `use_as_proxy`，显式 proxy→origin fallback；depot token 不进入 rmbgame 兼容访问；token 按 host/expiration 缓存并合并刷新 |
| 云路径映射错误 | 存档覆盖错误 | Phase 0 真实枚举；v1 只支持白名单路径；覆盖前备份 |
| `settings.save` 污染 PC 设置 | PC 设置异常 | 默认排除；实现 merge/strip 后再允许 |
| 异常退出后自动上传坏档 | 云端存档损坏 | 只有干净退出 marker 后自动上传；冲突不自动覆盖 |
| token 泄漏 | 账号风险 | EncryptedSharedPreferences、日志 redaction、不导出 token |

## 18. 最终建议

首版已按用户要求一次性接入登录、SteamPipe 下载、手动云同步与自动挂点。credential auth 已按可恢复事务维护：密码/Guard code 不落盘、手机确认立即轮询、前台服务持有、CM 可续接、成功原子提交、取消/过期清理。本体下载已复用 prepared manifest，并实现 Steam 返回代理到 origin 的显式回退、有界 1/2/4 chunk worker、单 writer、自适应内存预算、可取消 OkHttp Call，以及基于稳定 fingerprint 目录和 `*.steam.part` 校验的续传。后续测试重点应包括真实设备切屏/进程恢复/OEM 后台策略、不同网络下 proxy/origin 与 token 刷新行为，以及真实 Steam 账号的 appinfo/depot 分支枚举、不同游戏版本 payload 的 compat pack 匹配、当前 launch profile（全局/隔离存档）与 Steam Cloud 路径映射是否完全符合 PC 端。

Steam 下载必须继续复用 payload store + launch profile 模型；Steam Cloud 必须继续复用当前 launch profile 的 account root；二者都不应改变 `port-mod` 当前“禁用桌面 Steamworks”的基本不变式。
