# 项目结构与版本模型

## 1. 分层定位

本仓库把 Slay the Spire 2 Android 侧拆成三层：

1. **Android shell / launcher / 附加设置**：位于 `android/`，负责 UI、导入、版本/兼容包管理、启动 Godot、日志和文件管理。
2. **原版游戏 payload**：用户本地提供 PC zip，导入到应用私有目录；仓库不包含游戏本体。
3. **Android 兼容包**：位于 `port-mod/` submodule。legacy mode 仍可按游戏版本分支编译独立 `STS2Mobile.dll` / `port_compat.pck`；flat matrix mode 从同一 checkout 的 target 配置构建 schema 2 family 包，每个 target variant 拥有自己的 dll/pck。
4. **通用离线启动层**：位于 `offline-bootstrap/`。它独立于 `port-mod`，不静态引用 `sts2.dll`，构建 `sts2-android-offline-bootstrap.zip` 作为最低优先级 fallback；只有导入 payload 没有任何已安装 full compat 包按 SHA/version 命中时才会被自动推荐。

## 2. 仓库目录职责

```text
s2_re/
  AGENTS.md                        # 编码代理/维护者专用操作约定，不是用户手册
  README.md                        # 普通开发/测试入口与用户可见说明
  LICENSE                          # 本仓库原创代码 MIT License
  THIRD_PARTY_LICENSES.md          # 第三方来源与许可证摘要
  android/                         # Android shell + Godot Gradle 工程
  port-mod/                        # git submodule: Android 兼容 patcher 与 target matrix
  offline-bootstrap/               # 通用离线启动层；输出 schema 2 offline fallback compat pack
  tools/android/                   # runtime 同步、Gradle 环境、compat pack staging
  tools/package/                   # importer/direct APK 打包、payload zip 校验
  tools/debug/                     # ADB 自动化调试、安装、导入、启动、日志/性能采集脚本
  tools/deps/                      # GitHub 外部参考项目清单与自动准备脚本
  tools/git/                       # 父仓库/submodule HEAD 巡检
  doc/                             # 公开项目文档入口
  dist/                            # 本地构建输出，gitignore
  .agent/                          # agent 临时计划/报告/工作树/参考 clone/changelog/历史备份，gitignore，不追踪
    agent-docs/changelog/          # agent-only changelog，不提交
    historical-backup/docs/        # 旧 docs/ 历史 diff/validation 本地备份，不提交
```

## 3. Android shell 主要组件

- `GameSettingsActivity`：默认 launcher，承载欢迎向导、游戏主页、设置页、版本页、MOD 页，负责启动前检查；桌面图标默认打开附加设置，也可在设置页切换为完成向导后自动直接启动游戏；启动器更新检查可在关于页手动触发，手动无新版本时用 snackbar 提示，手动失败会弹窗显示原因，启动时自动检查无更新或失败仍保持静默日志。手机保持竖屏启动器，平板/大屏允许系统方向并在横屏时使用左侧 Navigation Rail、居中最大宽度内容、首页双栏与设置/关于双列卡片；游戏本体 `GodotApp` 保持横屏，旋转模式可在普通横屏、反向横屏和横屏传感器自动切换之间选择。游戏主页采用 MD3 深色仪表盘：顶部 Steam chip、动态渐变启动卡、未导入空状态、MOD/存档双状态卡和 4 列维护/高级工具快捷入口；MOD 页 Chip 操作组提供创意工坊入口；启动器图标统一通过 bundled Material Symbols Rounded 字体渲染。
- `SteamAccountActivity`：Steam 中心，负责展示 Steam 登录/Guard/refresh token 状态、SteamPipe 下载 STS2 payload，以及当前 launch profile account root 的 Steam Cloud 手动/自动同步。credential auth 的网络生命周期不再由 Activity 持有：Activity 只绑定并观察认证服务，切到 Steam App 时触发的 `onStop()` 只解绑，不取消事务。
- `SteamAuthForegroundService` / `SteamAuthTransactionManager`：Steam credential auth 的事务所有者。服务以 `dataSync` 前台任务持续轮询并发布通知；手机 App 确认 challenge 选中后立即轮询，因此可直接切屏批准且不依赖小窗。BeginAuth 返回后，manager 把短期 transaction handle 加密保存；CM 断开时使用新连接复用原 `client_id` / `request_id`（以及已轮换的 `new_client_id`）恢复 PollAuthSessionStatus。Activity 重建或可恢复进程重启后从 handle 恢复；成功仅在 transaction generation 匹配时原子提交 refresh token，取消/过期只清除 pending handle。
- `SteamWorkshopActivity`：Steam 创意工坊页面，采用列表、详情、已下载、设置四屏结构；侧栏支持“热门 / 最新发布 / 最近更新 / 最多订阅”排序和“本周 / 30 天 / 3 个月 / 6 个月 / 一年 / 全部”时间筛选，内容可滚动，并在顶部显示 Steam 中心登录账号/SteamID64 或匿名状态。未登录时通过 Steam Community 公开 Workshop 页面匿名浏览塔2公开条目，已登录时优先复用 Steam 中心保存的 refresh token/SteamID64 走 Steam CM 查询，缺少 SteamID64 时会验证 refresh token 补齐，失败时回落到公开浏览；侧滑菜单底部提供按 Workshop ID/URL 直接打开已知条目的入口，搜索框粘贴纯数字 ID 或 Workshop URL 时也会直接进入应用内详情；列表预览图、详情截图和前置 MOD 均从真实 Steam 页面/API 读取，列表靠近底部自动加载下一页并追加条目，图片请求会在兼容访问、原始域名和强制兼容访问之间重试。默认开启“创意工坊兼容访问”，对 `steamcommunity.com`、常见 Steam 图片媒体域和 `api.steampowered.com` 使用与 WorkshopAndroidDownloader 相同的 rmbgame 转发路径，并允许参考项目同款 SteamPipe HTTP-only CDN endpoint，以处理部分网络下原始 Steam Community/API 直连超时和 UGC manifest/chunk 下载被 Android 明文策略拦截。下载条目通过 MOD 导入 staging 流程安装到当前 launch profile MOD 根；创意工坊设置页提供“下载分支”：默认 `auto`，自动优先使用 Steam 下载 payload 时记录的 `source.steam.branch`，其次使用当前启动配置兼容包 manifest target 上的 `steam_branch`，两者都没有时才在下载前询问；也可固定为 `public`、`public-beta`、自定义分支或“每次询问”。下载器会通过 Steam CM `PublishedFile.GetItemInfo#1` 探测 author snapshots；若该接口没有返回 snapshots，则继续用 `PublishedFile.GetChangeHistory#1` 从 saved snapshot 历史中提取 branch min/max 与 manifest；固定分支或 `auto` 已能推断分支时会直接进入后台下载，manifest/depot/request code 在下载任务内部解析，避免一键队列被 UI 级分支解析串行阻塞；设置为“每次询问”或自动无法推断时才弹出分支/manifest 候选 Dialog。候选项显示 branch、manifest、depot、snapshot 时间、branch min/max、解析来源与 fallback 原因，用户选择后才开始下载；选择的 branch 会传给 `ContentServerDirectory.GetManifestRequestCode#1`；当 Steam 只暴露默认 manifest 而没有分支快照时，Dialog/自动解析会额外派生目标分支的“按分支请求默认 manifest”候选并明确标注 fallback 原因；CM snapshot、change history 和默认 manifest 都不可用时仍提供 WebAPI `hcontent_file` / `file_url` fallback 候选。最终目录为设置中的导入分组（默认 `workshop`）下的 `<branch>/<published_file_id>/` item 目录；匿名状态会尝试公开/匿名下载路径，部分受限条目仍可能需要登录；下载前会检查 Steam `RequiredItems`，用下载记录和对应 item 目录中真实存在的 MOD manifest 判断前置覆盖情况，缺失时弹出前置列表并可排队一键下载。下载支持后台任务，列表/详情按钮点击后立即变成圆形进度环和居中的方形停止按钮，已下载且当前版本按钮显示“详细信息”并打开该 item 目录内的本地 MOD 详情；已下载页条目卡片和条目图标按钮进入应用内 Workshop 详情而不是跳转 Steam App；后台下载线程使用低优先级，直链与 UGC 路径会合并进度事件，UGC 分块下载默认并发 2（设置页可调 1-8）以降低下载期间 UI 卡顿；导入成功后立即静默删除原始下载 staging，启动器/创意工坊页每天最多一次静默清理残留 `<files>/workshop/downloads/` 条目；若只有下载记录但 item 目录找不到对应本地 MOD，则显示本地文件已删除并提供重新下载。已下载页分为“下载中 / 已下载”，手动检查更新位于 AppBar；可删除单条下载记录，并可勾选同时删除对应 `published_file_id` item 目录。下载后在 `<files>/workshop/library/index.json` 按 `published_file_id@workshop_branch` 记录 `published_file_id`、`workshop_branch`、resolved manifest、解析来源、匹配 branch min/max、远端更新时间、导入的 MOD ID、item 安装根目录和安装摘要，用于后续更新检查；更新任务会沿用已安装记录的分支，导入完成后直接覆盖旧项并清理同一条目的 legacy 索引记录，避免分支迁移前的旧记录继续提示更新。页面内包含创意工坊网页入口、已下载列表、导入分组、兼容访问开关和 UGC 分块并发设置。
- `WebDavCloudActivity`：WebDAV 云存档中心，负责 WebDAV URL/用户名/密码/远端槽位配置、连接测试，以及当前 launch profile account root 的 WebDAV 手动/自动同步。
- `LocalSaveSnapshotManager`：本地存档快照管理，启动前和干净退出后自动创建当前 launch profile account root 的 zip 快照，默认保留最近 5 个；设置页“存档”分区可手动创建和恢复。
- `GodotApp`：真正的 Godot Activity，拼接 Godot 命令行，加载 imported PCK 或 bootstrap PCK，暴露 Java bridge 给 C#；干净退出回设置时写入云存档/本地快照自动处理 marker。
- `PayloadManager`：导入 PC zip 或 SteamPipe 下载目录、校验必需文件、patch 私有 PCK copy、写 `.payload_manifest.json` 并安装到 payload store。
- `LaunchProfileManager`：维护 payload store 与 launch profile，支持同一游戏本体多套全局/隔离存档和 MOD 配置，profile 保存 `compat_pack_id`，schema 2 family 包还保存 `compat_target_id`；从旧 schema 1 bundled 包升级到 flat matrix 内置包时会迁移旧 `sts2-android-compat-v0.*` 选择到 `sts2-android-compat` family target；切换时不复制 PCK。
- `GameBodyVersionManager`：legacy facade，版本选择委托给 `LaunchProfileManager`。
- `CompatPackManager`：安装/选择/删除兼容包，从 APK assets 安装内置包；支持 schema 1 单目标包、schema 2 family 包和受限的 schema 2 offline bootstrap 包，并按 payload `sts2_dll_sha256` / version 匹配具体 target variant。同等精确命中时优先推荐 schema 2 family 包；offline bootstrap wildcard 只在没有任何 full compat 包命中时作为最低优先级 fallback。安装 bundled 包后触发旧 bundled 选择到 flat family target 的迁移。
- `GameLaunchPreparationManager`：启动前准备 Mono publish 目录、兼容包 dll、overlay pck、游戏 assemblies、纹理缓存。
- `HighRefreshRateController`：Android 高刷新请求器，默认由 `android_high_refresh_rate_enabled=true` 启用；`GodotApp` 在启动/恢复/焦点/Godot 主循环阶段请求当前显示尺寸下最高 display mode，并对 Godot render `SurfaceView` 调用 Android frame-rate APIs，可在设置页“系统”分区的预加载下方关闭。
- `godot-debug-menu` overlay：打包进 `port-mod/overlay/addons/debug_menu/`，由设置页“系统”分区的性能显示开关控制，默认关闭；开启后下次启动显示 FPS、CPU/GPU frame graph 与渲染器/硬件信息。

## 4. 版本矩阵

| 通道 | 游戏版本 | 原版引用配置 | legacy port-mod 分支 | ReferenceFlavor | flat target id | legacy 兼容包 id |
| --- | --- | --- | --- | --- | --- | --- |
| 正式/稳定 | `v0.103.2` / `v0.103.3` | `.env` 的 `STS2_ORIGINAL_V103_REFERENCE_DIR` 或 `STS2_ORIGINAL_V103_ROOT` | `compat/v0.103.2` | `original` | `v0.103.x` | `sts2-android-compat-v0.103.x` |
| Beta（旧测试） | `v0.106.1` | `.env` 的 `STS2_ORIGINAL_V1061_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1061_ROOT` | `compat/v0.106.1-beta` | `original-v0.106.1` | `v0.106.1-beta` | `sts2-android-compat-v0.106.1-beta` |
| Beta（旧测试） | `v0.107.0` | `.env` 的 `STS2_ORIGINAL_V1070_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1070_ROOT` | `compat/v0.107.0-beta` | `original-v0.107.0` | `v0.107.0-beta` | `sts2-android-compat-v0.107.0-beta` |
| 正式/稳定 | `v0.107.1` | `.env` 的 `STS2_ORIGINAL_V1071_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1071_ROOT` | — | `original-v0.107.1` | `v0.107.1` | — |
| 正式/稳定 | `v0.108.0` | `.env` 的 `STS2_ORIGINAL_V1080_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1080_ROOT` | — | `original-v0.108.0` | `v0.108.0` | — |

`port-mod` 默认跟踪 `main`。内置 full 兼容包默认使用 flat matrix 模式：`stage-bundled-compat-packs.sh` 读取 `port-mod/targets/active/*/target.json`，从同一 checkout 构建并复制一个 `sts2-android-compat.zip` schema 2 family 包到 `android/assets/compat_packs/`。APK 打包默认调用 `stage-bundled-compat-artifacts.sh`，会同时 stage `offline-bootstrap/` 生成的 `sts2-android-offline-bootstrap.zip`。legacy 内置兼容包列表仍由 `tools/android/bundled-compat-packs.json` 控制，仅在 `COMPAT_PACK_BUILD_MODE=legacy` 时为每个分支构建 schema 1 zip。升级到 flat matrix APK 后，启动器会将旧 bundled schema 1 pack id（如 `sts2-android-compat-v0.103.x`、`sts2-android-compat-v0.106.1-beta`、`sts2-android-compat-v0.107.0-beta`）迁移为 `sts2-android-compat` + 对应 target id。所有 zip 都是构建产物，随本地 APK 打包但不再由 git 跟踪。compile gate 引用目录由 `.env` 解析后通过 `CompatReferenceDir` 传给 MSBuild，不依赖提交到仓库的个人 symlink。

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
<files>/instances/<profile_id>/instance.json # 启动配置：绑定 payload/compat/save/mod 模式，schema 2 可含 compat_target_id
<files>/instances/<profile_id>/default/1/settings.save # 隔离存档/设置模式使用
<files>/instances/<profile_id>/mods/        # 隔离 MOD 模式使用
<files>/instances/<profile_id>/logs/        # 当前配置日志：godot.log / android-launch.log
<files>/steam/downloads/                    # SteamPipe 下载 staging / 任务诊断
<files>/workshop/downloads/                 # Steam Workshop 条目下载 staging / 任务日志；导入成功或每日维护会静默清理
<files>/workshop/library/index.json         # 已导入 Workshop item 的 PublishedFileId / 分支 / manifest 解析来源 / item 根目录 / 更新时间 / MOD ID 记录
<files>/steam/cloud/<profile_id>/           # Steam Cloud manifest、baseline、备份与诊断
<files>/webdav/cloud/<slot>/                # WebDAV manifest、baseline、备份与诊断
<files>/automation/                         # ADB 自动化调试 token、inbox、run result；本地测试数据
<files>/save-snapshots/profiles/<profile_id>/ # 本地存档快照 zip，默认保留最近 5 个
<files>/compat-packs/<pack_id>/             # 已安装兼容包；schema 2 在 variants/<target_id>/ 下放 dll/pck
<files>/launcher/selected_instance.json     # 当前启动配置与解析后的运行路径
<files>/launcher/selected_game_version.json # legacy 兼容诊断记录，指向当前 payload
<files>/launcher/selected_compat_pack.json  # 当前启动配置解析出的兼容包诊断记录
<files>/default/1/settings.save             # 全局存档/设置根，profile 选择 global 时使用
<files>/mods/                              # 全局普通用户 MOD 根，profile 选择 global 时使用
<files>/.godot/mono/publish/arm64/          # Mono publish 目录
<files>/port_compat.pck                    # 启动前 staging 的 overlay
<files>/logs/                              # legacy/global 日志 fallback 与统一应用内 logcat：sts2.log
```

Steam 认证状态另存于 Android 加密偏好 `sts2_steam_auth`，不作为普通文件协议暴露。已登录账号仍按既有策略加密保存 refresh token 和 Steam 返回的 reusable guard data；尚未完成的登录只短期加密保存 `pending_auth_transaction` handle，默认 4 分钟失效。handle 包含事务 generation、账号名、Steam client/request 路由 ID、轮询间隔、challenge/phase、创建/截止时间和可能已有的 reusable guard data，但不包含账号密码、加密密码或用户本次输入的 Guard 动态码。密码和 Guard code 只在 Activity 到 bound service 的进程内调用期间驻留内存，不得放进 service Intent、Bundle、通知、诊断文件或日志。

认证服务是 transaction 的唯一网络 owner：手机确认进入等待态前先持久化 phase，随后立刻轮询；Activity 离开前台只解除 UI 观察。普通后台切换、配置重建或系统可恢复的服务重建不会要求“小窗保活”。若 CM transport 断开，服务建立新的未认证 CM 连接并用 handle 中的 ID 续接旧事务，而不是重新提交密码。成功提交采用 generation compare-and-commit，同时写入 refresh token 并删除同一 pending handle；用户取消、deadline 到期或 handle 损坏只删除对应 pending 状态，迟到的旧事务结果不能覆盖较新的登录。

## 6. 版本选择模型

当前实现是“payload store + launch profile”的完整多实例模型：

- 导入 PC zip 或 SteamPipe 下载完成后，payload 安装到 `<files>/payloads/<payload_id>/game/`，`payload_id` 由版本、commit 与 payload hash 派生；同一 payload 不再复制到固定 active 目录。Steam 来源会在 `.payload_manifest.json` 的 `source.kind=steam_depot` 与 `source.steam.*` 中记录 app/depot/manifest/branch 诊断信息。
- 版本页以 Material 3 分段页呈现三类对象：`启动配置`、`游戏本体`、`兼容包`。列表项点击后从底部抽屉查看路径、版本、文件统计等详情；兼容包页只负责安装/导入/删除，具体使用哪个兼容包只能在创建或编辑启动配置时选择。
- 版本页维护 `<files>/instances/<profile_id>/instance.json` 启动配置。一个 profile 绑定一个 payload、一个可选 compat pack，并分别记录 save/settings 与 MOD 使用 `global` 还是 `isolated`。schema 2 family 包会额外记录 `compat_target_id`，因此未来停止内置某个旧 target 时，可以只移出该 target 配置，不影响其他 target；旧 bundled schema 1 选择会在新版内置 family 包安装后自动改写为 family pack + target。
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
- `.agent/debug/runs/`：ADB 自动化调试结果、logcat、Perfetto trace 和拉回的 app 私有诊断文件。
- `dist/`、APK/AAB/APKS、.NET bin/obj。

允许提交但需区分用途：

- `AGENTS.md`：编码代理/维护者专用操作约定，帮助后续自动化维护；用户可见说明仍应沉淀到 `README.md` / `doc/`。
- `doc/plan/`：长期设计计划或已落地方案的维护 checklist；一次性 agent 上下文/审查记录不要放入项目文档。
- `.agent/agent-docs/changelog/`：agent 修改流水与验证记录，仅本地保存，不提交。
