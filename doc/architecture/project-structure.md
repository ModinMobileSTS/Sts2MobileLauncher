# 项目结构与版本模型

## 1. 分层定位

本仓库把 Slay the Spire 2 Android 侧拆成三层：

1. **Android shell / launcher / 附加设置**：位于 `android/`，负责 UI、导入、版本/兼容包管理、启动 Godot、日志和文件管理。
2. **原版游戏 payload**：用户本地提供 PC zip，导入到应用私有目录；仓库不包含游戏本体。
3. **Android 兼容包**：位于 `port-mod/` submodule，按游戏版本分支编译 `STS2Mobile.dll` 和 `port_compat.pck`。

## 2. 仓库目录职责

```text
s2_re/
  AGENTS.md                        # 编码代理/维护者专用操作约定，不是用户手册
  README.md                        # 普通开发/测试入口与用户可见说明
  LICENSE                          # 本仓库原创代码 MIT License
  THIRD_PARTY_LICENSES.md          # 第三方来源与许可证摘要
  android/                         # Android shell + Godot Gradle 工程
  port-mod/                        # git submodule: 多分支 Android 兼容 patcher
  tools/android/                   # runtime 同步、Gradle 环境、compat pack staging
  tools/package/                   # importer/direct APK 打包、payload zip 校验
  tools/deps/                      # GitHub 外部参考项目清单与自动准备脚本
  tools/git/                       # 父仓库/submodule HEAD 巡检
  doc/                             # 公开项目文档入口
  dist/                            # 本地构建输出，gitignore
  .agent/                          # agent 临时计划/报告/工作树/参考 clone/changelog/历史备份，gitignore，不追踪
    agent-docs/changelog/          # agent-only changelog，不提交
    historical-backup/docs/        # 旧 docs/ 历史 diff/validation 本地备份，不提交
```

## 3. Android shell 主要组件

- `GameSettingsActivity`：默认 launcher，承载欢迎向导、游戏主页、设置页、版本页、MOD 页，负责启动前检查；桌面图标默认打开附加设置，也可在设置页切换为完成向导后自动直接启动游戏。游戏主页采用 MD3 深色仪表盘：顶部 Steam chip、动态渐变启动卡、未导入空状态、MOD/存档双状态卡和 4 列维护/高级工具快捷入口；启动器图标统一通过 bundled Material Symbols Rounded 字体渲染。
- `SteamAccountActivity`：Steam 中心，负责 Steam 登录/Guard/refresh token 验证、SteamPipe 下载 STS2 payload，以及当前 launch profile account root 的 Steam Cloud 手动/自动同步。
- `WebDavCloudActivity`：WebDAV 云存档中心，负责 WebDAV URL/用户名/密码/远端槽位配置、连接测试，以及当前 launch profile account root 的 WebDAV 手动/自动同步。
- `GodotApp`：真正的 Godot Activity，拼接 Godot 命令行，加载 imported PCK 或 bootstrap PCK，暴露 Java bridge 给 C#；干净退出回设置时写入云存档自动上传 marker。
- `PayloadManager`：导入 PC zip 或 SteamPipe 下载目录、校验必需文件、patch 私有 PCK copy、写 `.payload_manifest.json` 并安装到 payload store。
- `LaunchProfileManager`：维护 payload store 与 launch profile，支持同一游戏本体多套全局/隔离存档和 MOD 配置，切换时不复制 PCK。
- `GameBodyVersionManager`：legacy facade，版本选择委托给 `LaunchProfileManager`。
- `CompatPackManager`：安装/选择/删除兼容包，从 APK assets 安装内置包，按 payload version 匹配。
- `GameLaunchPreparationManager`：启动前准备 Mono publish 目录、兼容包 dll、overlay pck、游戏 assemblies、纹理缓存。

## 4. 版本矩阵

| 通道 | 游戏版本 | 原版引用配置 | port-mod 分支 | ReferenceFlavor | 兼容包 id |
| --- | --- | --- | --- | --- | --- |
| 正式/稳定 | `v0.103.2` / `v0.103.3` | `.env` 的 `STS2_ORIGINAL_V103_REFERENCE_DIR` 或 `STS2_ORIGINAL_V103_ROOT` | `compat/v0.103.2` | `original` | `sts2-android-compat-v0.103.x` |
| Beta（旧测试） | `v0.106.1` | `.env` 的 `STS2_ORIGINAL_V1061_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1061_ROOT` | `compat/v0.106.1-beta` | `original-v0.106.1` | `sts2-android-compat-v0.106.1-beta` |
| Beta | `v0.107.0` | `.env` 的 `STS2_ORIGINAL_V1070_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1070_ROOT` | `compat/v0.107.0-beta` | `original-v0.107.0` | `sts2-android-compat-v0.107.0-beta` |

内置兼容包列表由 `tools/android/bundled-compat-packs.json` 控制。打包脚本会用 `stage-bundled-compat-packs.sh` 为列表中的每个分支构建 zip 并复制到 `android/assets/compat_packs/`；这些 zip 是构建产物，随本地 APK 打包但不再由 git 跟踪。compile gate 引用目录由 `.env` 解析后通过 `CompatReferenceDir` 传给 MSBuild，不依赖提交到仓库的个人 symlink。

## 5. APK assets 与私有运行时目录

APK assets：

```text
android/assets/bootstrap.pck                 # 无 payload 时的最小 bootstrap
android/assets/port_compat.pck               # legacy fallback overlay
android/assets/compat_packs/*.zip            # 构建时生成的内置兼容包 assets，gitignore
android/assets/dotnet_bcl/                   # 同步的大型 runtime，gitignore
android/assets/payload/SlayTheSpire2.zip     # 直装版临时 payload，gitignore
```

Android Gradle 子模块：

```text
android/steam-protocol/                       # Steam CM/auth/content protobuf 协议子模块
android/steam-content/                        # SteamPipe depot manifest/chunk 下载子模块
```

应用私有目录：

```text
<files>/payloads/<payload_id>/game/         # 不可变导入游戏本体，切换版本不复制 PCK
<files>/payloads/<payload_id>/game/.payload_manifest.json # payload 身份与 patch 记录
<files>/instances/<profile_id>/instance.json # 启动配置：绑定 payload/compat/save/mod 模式
<files>/instances/<profile_id>/default/1/settings.save # 隔离存档/设置模式使用
<files>/instances/<profile_id>/mods/        # 隔离 MOD 模式使用
<files>/instances/<profile_id>/logs/        # 当前配置日志：godot.log / android-launch.log
<files>/steam/downloads/                    # SteamPipe 下载 staging / 任务诊断
<files>/steam/cloud/<profile_id>/           # Steam Cloud manifest、baseline、备份与诊断
<files>/webdav/cloud/<slot>/                # WebDAV manifest、baseline、备份与诊断
<files>/compat-packs/<pack_id>/             # 已安装兼容包
<files>/launcher/selected_instance.json     # 当前启动配置与解析后的运行路径
<files>/launcher/selected_game_version.json # legacy 兼容诊断记录，指向当前 payload
<files>/launcher/selected_compat_pack.json  # 当前启动配置解析出的兼容包诊断记录
<files>/default/1/settings.save             # 全局存档/设置根，profile 选择 global 时使用
<files>/mods/                              # 全局普通用户 MOD 根，profile 选择 global 时使用
<files>/.godot/mono/publish/arm64/          # Mono publish 目录
<files>/port_compat.pck                    # 启动前 staging 的 overlay
<files>/logs/                              # legacy/global 日志 fallback 与统一应用内 logcat：sts2.log
```

## 6. 版本选择模型

当前实现是“payload store + launch profile”的完整多实例模型：

- 导入 PC zip 或 SteamPipe 下载完成后，payload 安装到 `<files>/payloads/<payload_id>/game/`，`payload_id` 由版本、commit 与 payload hash 派生；同一 payload 不再复制到固定 active 目录。Steam 来源会在 `.payload_manifest.json` 的 `source.kind=steam_depot` 与 `source.steam.*` 中记录 app/depot/manifest/branch 诊断信息。
- 版本页以 Material 3 分段页呈现三类对象：`启动配置`、`游戏本体`、`兼容包`。列表项点击后从底部抽屉查看路径、版本、文件统计等详情；兼容包页只负责安装/导入/删除，具体使用哪个兼容包只能在创建或编辑启动配置时选择。
- 版本页维护 `<files>/instances/<profile_id>/instance.json` 启动配置。一个 profile 绑定一个 payload、一个可选 compat pack，并分别记录 save/settings 与 MOD 使用 `global` 还是 `isolated`。
- 切换游戏版本/配置只更新 `<files>/launcher/selected_instance.json` 与 SharedPreferences，不复制 `SlayTheSpire2.pck` 或解压目录；删除游戏本体或兼容包不会删除启动配置，配置会保留缺失引用并在启动前提示。
- 同一个 payload 可以创建多个 profile：例如同一 beta 本体分别使用全局 MOD、独立 MOD、独立存档等。
- Java 侧 `GodotApp` / `GameLaunchPreparationManager` 根据当前 profile 动态解析 PCK、assembly、settings、mods 与 logs 路径，并写入 `selected_instance.json`；C# 兼容层 `AppPaths` 从 Mono publish 目录或 Android 进程包名推导 `<files>` 后读取该 JSON（避免兼容层早期初始化时调用 Godot API/Java bridge），并由 `SavePathPatches` 将原版 `UserDataPathProvider` 重定向到当前 profile 的 account root。
- 旧 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制；`selected_game_version.json` 保留为 legacy 诊断文件。

## 7. 不提交内容

禁止提交：

- 用户 PC 游戏 zip、解压 payload、原版/参考 runtime DLL 或压缩包。
- `android/assets/dotnet_bcl/`、`android/libs/`、`android/assets/payload/`。
- keystore/jks/p12 等签名私钥。
- `android/assets/compat_packs/*.zip`：脚本生成的兼容包 assets，构建时刷新，不入库。
- `.agent/`：agent 草稿、计划、报告、临时 worktree、agent-only changelog、历史备份与外部参考 clone。
- `dist/`、APK/AAB/APKS、.NET bin/obj。

允许提交但需区分用途：

- `AGENTS.md`：编码代理/维护者专用操作约定，帮助后续自动化维护；用户可见说明仍应沉淀到 `README.md` / `doc/`。
- `doc/plan/`：长期设计计划或已落地方案的维护 checklist；一次性 agent 上下文/审查记录不要放入项目文档。
- `.agent/agent-docs/changelog/`：agent 修改流水与验证记录，仅本地保存，不提交。
