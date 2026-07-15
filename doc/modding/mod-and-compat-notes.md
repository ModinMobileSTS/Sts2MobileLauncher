# 普通 MOD 与 Android 兼容包维护说明

## 1. 两类“MOD”不要混淆

本项目里有两类扩展：

1. **Android compat pack**
   - 由 launcher/Godot runtime 早期加载。
   - 包含 `STS2Mobile.dll` 与 `port_compat.pck`。
   - 负责 patch 平台、路径、设置、输入、shader、LAN、普通 MOD loader。
   - 不放在 `<files>/mods`，不由原版 `ModManager` 作为普通 MOD 加载。
2. **普通用户 MOD**
   - 用户安装到当前 launch profile 解析出的 MOD 目录：全局模式为 `<files>/mods/`，隔离模式为 `<files>/instances/<profile_id>/mods/`。
   - 由被 compat pack patch 后的原版 `ModManager` 扫描、排序和加载。
   - 是否启用由当前 profile 的 `settings.save` 中的 MOD 设置/附加设置页控制。

## 2. 普通 MOD 目录和 manifest

当前普通 MOD 根目录由“版本”页选中的 launch profile 决定：

```text
# mods_mode=global
<files>/mods

# mods_mode=isolated
<files>/instances/<profile_id>/mods
```

`ModLoaderPatches` 会通过 `AppPaths.ModsDir` 递归扫描该目录。为兼容当前 PC `ModManager` 的 manifest 规则，会把：

```text
mod_manifest.json
```

运行时规范化为：

```text
<ModId>.json
```

并添加 `android_generated_manifest_alias=true`，必要时删除重复的 `mod_manifest.json`。

## 3. MOD 管理界面与导入冲突处理

`ModsPage` 采用紧凑 Material 3 顶栏：顶部为 MOD 总开关和药丸搜索框，导入、分组、创意工坊、排序、筛选、MOD 方案入口统一放在可横向滚动的 Chip 操作组中。NexusMods 商店 Activity 仍保留在工程内，但主 MOD 页入口暂时隐藏。

MOD 卡片默认折叠，只显示左侧拖拽手柄、名称、版本/作者和启用开关；展开后显示完整描述、分类、可点击跳转文件浏览器的清单路径（主题色+下划线）、作者、依赖、最低游戏版本，以及右下角图标按钮：选中、备注、信息、删除；超长描述默认截断到 10 行并提供“显示更多”。可为每个 MOD 设置本地显示备注名（保存在 `sts2_mod_profiles` 的 `mod_notes`，只用于启动器 UI），有备注时卡片主标题显示备注名，原名显示在版本号前的元信息行中。启动器会自动探查清单 `dependencies`、`min_game_version`、`has_pck`/`has_dll` 与对应文件、以及 `settings.save` 中原版 `ModSettings.ModList` 平面手工顺序是否把被依赖 MOD 排在依赖它的 MOD 之后：有问题时该 MOD 卡片以黄色描边高亮，AppBar 标题旁显示黄色警告图标与问题 MOD 数量，点击打开 BottomSheet；问题列表按紧凑 MOD 卡片展示名称、版本/作者和逐条警告，警告正文为白色且缺失依赖等关键对象用红色高亮。若存在顺序问题，BottomSheet 提供“自动修复加载顺序”按钮（按原版 `ModManager.SortModList` 的依赖拓扑规则重写平面 `mod_list`，不改分组顺序）。MOD 列表按“前置库 / 内容模组 / 用户新建分组 / 未分组”分区，分组右侧可收起/展开。长按卡片左侧手柄可跨分组或组内拖拽排序，长按分组 header 可移动整个分组；拖拽开始会触发一次轻微震动反馈，列表中会插入半透明虚线 ghost 占位并用 LayoutTransition 平滑让位。用户新建、重命名、删除分组以及把 MOD 分到某组只会更新本地 `sts2_mod_profiles` SharedPreferences 中的 `mod_groups`、`hidden_mod_groups`、`mod_group_assignments`、`mod_group_order` 与 `mod_order`，不会创建、重命名、删除或移动 MOD 文件/目录。旧版本遗留的 `.sts2_mod_group` 标记目录仍会作为初始 UI 分组兼容读取；删除或重命名这类旧分组时只写隐藏/映射元数据，不改真实路径。游戏进程内读取的是 `settings.save` 的平面 `mod_list`，并仍会按原版 `ModManager` 再做依赖拓扑排序。

导入 MOD 时，Android shell 会先把选择的 zip/文件解包到 cache staging 目录并解析 manifest。如果发现新导入 manifest 的 `id` 与已安装 MOD 相同，会弹出冲突 Dialog，说明“连体现象”：界面可能显示两个项目，但任何一个开关都会按同 ID 同时影响两个。Dialog 会用信息卡分别展示原 MOD 和新 MOD，用户可选择保留原 MOD（丢弃本次 staging）或使用新 MOD（删除同 ID 原 MOD 后提交 staging）。该规则同样服务 Nexus 下载导入路径，避免同 ID manifest 在 `<files>/mods` 或隔离 MOD 根中长期并存。

同 ID 冲突处理后，普通本地/Nexus 导入流程还会按 staging 到当前 MOD 根目录的实际相对路径预检文件覆盖。如果新导入内容会写入已经存在的 `.dll` / `.pck` / `.json` 或其它资源文件，且这些文件不属于用户刚确认替换的同 ID 旧 MOD，会再次弹出“文件覆盖”警告，列出会被覆盖的相对路径和可推断的现有归属；默认取消/保留已安装文件，只有用户明确选择替换时才继续。底层普通提交接口默认不允许未确认的路径覆盖，避免把 A MOD 的 DLL/PCK 静默替换成 B MOD 的文件；创意工坊导入则固定提交到对应 `published_file_id` item 目录，更新同一 item 时整体替换该目录。

## 4. Steam 创意工坊导入与更新记录

`SteamWorkshopActivity` 不单独保存 Workshop 账号。MOD 页“创意工坊”按钮会打开塔2创意工坊页面；未登录 Steam 时通过 Steam Community 公开 Workshop 页面匿名展示公开条目，已登录时优先复用 Steam 中心的加密 refresh token 和 SteamID64 走 Steam CM 查询，缺少 SteamID64 时会验证 refresh token 补齐，失败时回落到公开浏览。页面使用列表、详情、已下载、设置四屏结构；侧栏支持热门、最新发布、最近更新、最多订阅排序，以及本周、30 天、3 个月、6 个月、一年、全部时间筛选，侧栏内容可滚动，并显示 Steam 中心登录账号/SteamID64 或匿名状态。列表预览图、详情截图、描述和前置 MOD 均从真实 Steam 公开页面/API 读取；列表靠近底部时自动加载下一页并追加条目，截图优先取详情页原图链接，图片请求会在兼容访问、原始域名和强制兼容访问之间重试，兼容访问也覆盖常见 Steam 图片媒体域。页面支持搜索、通过已知 Workshop ID/URL 直接打开条目、打开 Steam 网页、后台下载并导入条目、查看“下载中 / 已下载”列表、从已下载页 AppBar 手动检查更新，以及设置下载导入分组、创意工坊兼容访问和 UGC 分块并发数；搜索框粘贴纯数字 ID 或 Workshop URL 时也会直接进入应用内详情。创意工坊设置页提供“下载分支”：默认 `auto`，自动优先使用 Steam 下载 payload 时记录的 `source.steam.branch`，其次使用当前启动配置兼容包 manifest target 上的 `steam_branch`，两者都没有时才在下载前询问；也可固定为 `public`、`public-beta`、自定义分支或“每次询问”。下载器通过 `PublishedFile.GetItemInfo#1` 读取 author snapshots；若该接口没有返回 snapshots，则继续用 `PublishedFile.GetChangeHistory#1` 从 saved snapshot 历史中提取 branch min/max 与 manifest。固定分支或 `auto` 已能推断分支时会直接进入后台下载，manifest/depot/request code 在下载任务内部解析，避免一键队列被 UI 级分支解析串行阻塞；设置为“每次询问”或自动无法推断时才弹出分支/manifest 候选 Dialog；候选项展示 branch、manifest、depot、snapshot 时间、branch min/max、解析来源和 fallback 原因；当 Steam 只暴露默认 manifest 而没有分支快照时，Dialog/自动解析会额外派生目标分支的“按分支请求默认 manifest”候选，不把它标成已确认的分支快照；若 CM snapshot、change history 和默认 manifest 都不可用，则保留 WebAPI `hcontent_file` / `file_url` fallback 候选。

创意工坊下载先加载 Steam 详情页的 `RequiredItems`，用 `<files>/workshop/library/index.json` 中记录的 `published_file_id` 与当前 launch profile MOD 根下对应 item 目录内真实存在的 MOD manifest 对照；若前置 item 未安装或本地文件已缺失，会弹出前置列表，用户可取消、只下载当前条目，或把缺失前置和当前条目放入队列一键下载。每个队列项仍先落到 `<files>/workshop/downloads/<published_file_id>-<uuid>/`，下载线程在后台运行，列表/详情按钮会立即显示圆形进度环和居中的方形停止按钮，下载页实时显示“下载中”；下载完成后调用 `ExtraSettingsRepository.prepareDownloadedModDirectory()` 把下载目录复制到 MOD 导入 staging，并检查 incoming MOD ID 是否与 item 目录外的已安装 MOD 冲突。提交成功后，启动器会把该 Workshop item 安装到设置中的导入分组（默认 `workshop`）下的 `<branch>/<published_file_id>/` 目录，更新同一 item 时固定沿用已安装记录的分支并直接覆盖同 ID 旧项，不再弹出覆盖确认；同一分支仍整体替换该目录，并在 `<files>/workshop/library/index.json` 按 `published_file_id@workshop_branch` 记录 `published_file_id`、`workshop_branch`、resolved manifest、解析来源、匹配 branch min/max、远端更新时间、导入的 MOD ID、item 安装根目录、大小和内容 SHA-1 摘要；导入成功的原始 `<files>/workshop/downloads/<published_file_id>-<uuid>/` 下载目录会立即静默删除，启动器/创意工坊页还会每天最多一次静默清理残留下载 staging；若记录已是当前版本且 item 目录仍能找到本地 MOD，列表/详情下载按钮会变为“详细信息”，点击后打开该 item 目录内 MOD 的本地详情；已下载页条目卡片和条目图标按钮会进入应用内 Workshop 详情，而不是跳转到 Steam App；若只有下载记录但 item 目录已找不到 MOD manifest，则显示本地文件已删除并把按钮改为重新下载。已下载页可删除单条下载记录，删除时可勾选同时删除对应 `published_file_id` item 目录。更新检查通过 Steam published file details 获取远端 `time_updated`，与记录的安装远端更新时间比较，标记 `available` / `current` / `failed`；重新下载更新固定沿用已安装记录分支，直接替换旧项；索引会隐藏并淘汰加分支前的 legacy 记录，避免更新后继续显示旧记录。

创意工坊下载实现参考 `Apricityx/WorkshopAndroidDownloader`：公开 `file_url` 走直链下载，UGC manifest 路径走 SteamPipe CDN chunk 下载；当用户在分支候选中选择 author snapshot 或默认 manifest fallback 时，下载器使用该候选的 manifest，并把对应 branch 传给 `ContentServerDirectory.GetManifestRequestCode#1`；未登录时下载器会尝试匿名 Steam 会话和公开 CDN 回退，部分公开 MOD 可直接下载，受限/需拥有权限的条目仍可能要求登录。后台下载线程使用低优先级，直链和 UGC 路径都会合并进度事件；UGC 分块下载默认并发 2，设置页可调 1-8。默认开启的“创意工坊兼容访问”会把 `steamcommunity.com`、常见 Steam 图片媒体域和 `api.steampowered.com` 请求转到参考项目同款 `steamcommunity.rmbgame.net` / `steamstore.rmbgame.net` 路径，并保留逻辑 Host；UGC manifest/chunk 下载沿用参考项目的 SteamPipe CDN 处理，允许 Steam 内容目录返回的 HTTP-only CDN endpoint，避免 Android 9+ 默认明文策略拦截。关闭后只使用原始 Steam 域名和普通 SteamPipe CDN 行为。当前 UI 只把成功下载出的文件作为普通用户 MOD 导入，不恢复游戏进程内的桌面 Steam Workshop 枚举。

## 5. NexusMods 商店导入

`NexusModsStoreActivity` 仍作为实验性页面保留，但 `ModsPage` 暂时不展示入口。后续重新开放时需继续遵循：

- 用户必须手动输入并保存自己的 NexusMods Personal API Key；获取教程入口指向 <https://www.nexusmods.com/settings/api-keys>，提示用户滑到页面底部并点击 “Request Personal API Key”。
- API Key 存在 Android 私有 `SharedPreferences`（`sts2_nexus_mod_store`）中，不写入仓库、不导出到构建产物。
- 默认游戏域名为 `slaythespire2`。官方 API 没有完整全站文本搜索接口，因此关键词搜索会聚合并筛选 trending/latest/updated feed；输入 Nexus MOD URL 或数字 MOD ID 会走精确查询。
- 下载流程会获取 NexusMods 文件列表，选择文件后尝试生成下载链接并把下载到的 ZIP 先交给 `ExtraSettingsRepository.prepareDownloadedModImport()` 解包到 staging；若触发同 ID 或路径覆盖冲突，会复用本地导入弹窗，确认后再提交到当前 launch profile 的 MOD 目录并启用 MOD 总开关。
- NexusMods 对非 Premium 用户可能要求先访问网页；此时界面会引导打开网页并支持粘贴 `nxm://...key=...&expires=...` 链接重试下载。

## 6. MOD 启用/禁用协议

Java 附加设置页通过 `ExtraSettingsRepository` 写入当前 launch profile 的 settings：

```text
# save_mode=global
<files>/default/1/settings.save

# save_mode=isolated
<files>/instances/<profile_id>/default/1/settings.save
```

兼容层中的 `AppPaths` / `SavePathPatches` 会把原版 `UserDataPathProvider` 指向当前 profile 的 account root；`AndroidSettingsBridge` / `AndroidSettingsPatches` 通过 `AppPaths.SettingsPath` 读取 companion JSON，将 Android-only 字段投影到当前游戏版本的 `ModSettings`：

- `mod_settings.mods_enabled`
- `mod_list[]`
- legacy `disabled_mods[]`

同一个 settings JSON 也承载兼容层显示/输入协议。`android_screen_rotation_mode` 是当前旋转模式字段，取值为 `user_landscape`（默认，跟随系统，只在横屏间旋转，受系统自动旋转开关约束）、`auto`（强制双向横屏旋转，通过重力监听器绕过系统旋转锁定限制）、`landscape`（不旋转）或 `reverse_landscape`（固定 180°）；旧 `android_flip_screen_180` 仍会同步，供旧兼容包 fallback。

根窗口的 `ContentScaleMode` / `ContentScaleAspect` / `ContentScaleSize` 只能由 `DisplaySettingsPatches` 协调，逻辑 owner 优先级是 `FixedAspect > UiScaleAuto`，Mode 始终为 `CanvasItems`。Auto 比例使用 `UiScalePatches` 提供的 UI scale target，固定比例使用对应 fixed target；owner 必须在任何 Window setter 前发布，setter 必须 compare-before-set。`UiScalePatches` 中的窗口变化补丁只能请求 single-flight deferred 重算，不得直接写 `ContentScale*`。`fullscreen_render_size` 不得接管逻辑 ContentScale，也不再由 Java 转换成 `--resolution`；游戏内写入后由 compat 立即应用到根 renderer RT。实现顺序必须是先完成高层 `ContentScale*` setter，再只调用 `RenderingServer.ViewportSetRenderDirectToScreen(false)`、`ViewportSetSize()` 与 `ViewportSetGlobalCanvasTransform()`。不得修改 scene Window/输入变换/Android Surface，也不得调用 `SurfaceHolder.setFixedSize()` 或 `ViewportAttachToScreen()`。`0x0` 恢复 native RT 尺寸与高层 ContentScale 生成的原始 canvas transform；非零预设按当前 native attachment 宽高比以 Expand 语义覆盖请求矩形，例如 `2400x1080` + `1280x720` 得到 `1600x720`。自定义目标长边上限为 `max(4096, native 长边)`。根 Window `SizeChanged`、application resume 和一致性 repair 后必须重投 renderer override，防止高层 setter 复位动态目标。`global_scale` 始终独立作为 `ContentScaleFactor` 应用，`ui_font_scale_percent` 也独立；原版游戏内 UI scale 选择保存在 `user://ui_scale.cfg`，FixedAspect 期间不控制 Size，回到 Auto 后恢复。runtime 显示设置只在 `NotificationApplicationResumed` 时合并为一次 deferred apply，不得在 window/application focus 通知中同步重建 viewport。

修改设置 key 时必须同步：

- Java repository / UI；
- `port-mod/STS2AndroidPortCompat/Android/AndroidSettingsBridge.cs`；
- 相关 `Patches/*Settings*.cs`；
- `.agent/agent-docs/changelog/`（agent-only，不提交）。

## 7. compat pack target 维护

`port-mod` 默认在 `main` 上维护 flat matrix。普通共用修复不要再按游戏版本开开发分支；版本差异放到 `targets/active/<target_id>/target.json`、target adapter/capability 或少量条件编译。历史 `compat/*` 分支只用于 legacy schema 1 包对照、回退诊断或已经冻结的旧维护线。

当前 active targets：

| 游戏版本 | target id | 原版引用配置 | ReferenceFlavor |
| --- | --- | --- | --- |
| `v0.103.2` / `v0.103.3` | `v0.103.x` | `.env`: `STS2_ORIGINAL_V103_REFERENCE_DIR` 或 `STS2_ORIGINAL_V103_ROOT` | `original` |
| `v0.106.1` beta（旧测试） | `v0.106.1-beta` | `.env`: `STS2_ORIGINAL_V1061_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1061_ROOT` | `original-v0.106.1` |
| `v0.107.0` beta（旧测试） | `v0.107.0-beta` | `.env`: `STS2_ORIGINAL_V1070_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1070_ROOT` | `original-v0.107.0` |
| `v0.107.1` stable | `v0.107.1` | `.env`: `STS2_ORIGINAL_V1071_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1071_ROOT` | `original-v0.107.1` |
| `v0.108.0` stable | `v0.108.0` | `.env`: `STS2_ORIGINAL_V1080_REFERENCE_DIR` 或 `STS2_ORIGINAL_V1080_ROOT` | `original-v0.108.0` |

开发步骤建议：

```bash
# 1. 确认 submodule 在 main 上
git -C port-mod status --short --branch

# 2. 编译 matrix original gates（引用目录从 .env 解析）
(cd port-mod && ./tools/build-compat-matrix.sh)

# 3. 可选：构建 fallback 或 legacy schema 1 诊断包
REFERENCE_FLAVOR=original-v0.108.0 tools/android/build-port-mod.sh
# 或
tools/android/build-port-mod.sh
# 或
(cd port-mod && ./tools/build-compat-pack.sh)

# 4. 构建内置包/导入版 APK
tools/android/stage-bundled-compat-packs.sh
tools/package/build_importer_apk.sh
```

只调试单个 target 时可运行 `(cd port-mod && ./tools/build-compat-matrix.sh --target v0.108.0)`；legacy schema 1 诊断包仍用 `REFERENCE_FLAVOR=original` / `original-v0.106.1` / `original-v0.107.0` 指定对应原版 gate，单独调试 `v0.107.1` / `v0.108.0` 可分别用 `REFERENCE_FLAVOR=original-v0.107.1` / `original-v0.108.0`。

## 8. 新增游戏版本 checklist

新增一个目标游戏版本时，至少需要：

1. 准备对应 PC 原版/解包目录或 DLL 引用目录，并在 `.env.example` / 文档中增加对应 `STS2_ORIGINAL_*_REFERENCE_DIR` 说明。
2. 为新版本定义 `ReferenceFlavor` 到 `CompatReferenceDir` 的解析方式（脚本映射或显式环境变量），不要提交指向个人 workspace 的 symlink。
3. 在 `port-mod/targets/active/<target_id>/target.json` 新增 target 描述，包含 `target_id`、`versions`、`reference_flavor`、`source`、可选 `sts2_dll_sha256` 与 compile constants。
4. 若源码需要版本差异，优先新增 target adapter/capability；只有无法避免时才使用少量条件编译。
5. 用对应 `ReferenceFlavor` 做 compile gate，并运行 `port-mod/tools/build-compat-matrix.sh` 覆盖所有 active target。
6. 运行 `tools/android/stage-bundled-compat-packs.sh`。
7. 更新 `AGENTS.md`、`doc/architecture/project-structure.md`、本文件，并在 `.agent/agent-docs/changelog/` 写 agent changelog。
8. 构建 `tools/package/build_importer_apk.sh` 并做至少一次导入/启动 smoke test。
9. 只有需要 legacy schema 1 对照包时，才额外新建 `compat/vX.Y.Z` 分支、新增/更新 `compat_manifest.*.json`，并更新 `tools/android/bundled-compat-packs.json`。

## 9. patch 开发注意事项

- 优先使用 prefix/postfix 和反射兜底，谨慎使用复杂 transpiler；Android 上 MonoMod/Cecil/Godot StringName 生命周期问题更容易暴露。
- 任何直接引用游戏内部类型的 patch 都可能随游戏版本变化失效，应通过 active target compile gate 验证；只在单版本复现的问题优先收敛到 target adapter/capability 或条件编译，不要默认拆回 compat 分支。
- `ModEntry.Apply()` patch 顺序很重要：BaseLib/RitsuLib 与平台/路径类 patch 必须早于 ModLoader。
- Android temp 目录必须尽早配置，否则 Harmony/MonoMod 可能尝试使用不可写 `/tmp`。
- Shader/resource overlay 资源应放入 `port-mod/overlay/`，重新打包 `port_compat.pck` 后才能生效。
- 普通 MOD loader 的目标是尽量复用游戏原本的 scanner、dependency sort 和 TryLoadMod，减少与 PC 行为分叉。
- LAN 兼容层只适配 Android transport/UI/settings/player/save；`MessageTypes` 消息发现与排序、`NetMessageBus` 序列化/反序列化必须继续由匹配版本的原版程序集负责。不要维护 Android 固定消息表，也不要仅按内置类型重建排序，否则会同时破坏未修改 PC 联机和普通 MOD 自定义 `INetMessage`。
- `v0.103.x`、`v0.107.1` / `v0.108.0` stable target、`v0.106.1` beta 与 `v0.107.0` beta target 应保持同一套 Android/Mono MOD 初始化不变式：加载任何 MOD 前只允许预注册原版模型占位；每个 MOD initializer 期间只允许短暂隐藏“非原版类型命中早期原版占位”的 `ModelDb.Contains(Type)` 结果，避免与原版同名的 MOD 模型因 Android 提前占位误报重复；若 MOD 因早期占位误判 ModelDb 已初始化并调用 `AbstractModel.InitId()`，兼容层只在 `ModelIdSerializationCache.Init()` 完成前跳过该次早调用，后续正常 `ModelDb.InitIds()` 会统一设置排序 ID，不得提前动态分配 net ID；MOD 自定义模型占位必须等到所有 MOD Harmony patch 应用后、`ModelDb.Init()` 前再按最终 ID 注册。用户 MOD 在 initializer / `PatchAll` 中 patch STS2 Godot/UI 类型时，`DeferredModPatchQueue` 可将带静态初始化器的 UI patch 延后到 `ExecuteEssential` 完成后重放；不要把普通模型类 patch 一并延后，否则会破坏 MOD 依赖的 `ModelDb.Init` 前置 hook 时序。

## 10. MOD 兼容性排查规范

排查普通 MOD 在 Android 上无法加载、依赖缺失、初始化顺序异常或行为与 PC 不一致时，建议按以下方式收集参照信息：

- 可以把常用前置/依赖 MOD 仓库 clone 到工作区外或 `.agent/reference-repos/` 等不提交的位置，并 checkout 到与目标游戏版本、目标 MOD 版本匹配的 tag/branch/commit 后对照排查；不要把这些第三方源码或构建产物提交到本仓库。
- 优先参考对应版本 PC 原版/解包代码，尤其是 `ModManager`、依赖排序、manifest 解析、assembly resolve、资源加载和初始化回调的时序；重点确认 Android 兼容层是否漏掉某一步、提前/延后某一步，或改变了原版加载顺序导致 MOD 兼容问题。
- 对没有公开源码的 MOD，可以通过反编译其程序集获取可参考信息，用于定位入口类、manifest、依赖声明、Harmony patch、资源路径和初始化假设；反编译结果只作为本地诊断依据，不要提交第三方反编译源码或违反其许可条款。
- 常见前置/依赖仓库：
  - RitsuLib: <https://github.com/BAKAOLC/STS2-RitsuLib>
  - BaseLib-StS2: <https://github.com/Alchyr/BaseLib-StS2>

## 11. 兼容包 manifest 约定

schema 1 legacy 单目标 manifest：

```json
{
  "schema": 1,
  "pack_id": "sts2-android-compat-v0.107.0-beta",
  "display_name": "STS2 Android Compatibility beta for v0.107.0",
  "compat_version": "0.3.2-beta.1070",
  "channel": "beta",
  "target_game": {
    "version": "v0.107.0",
    "supported_versions": ["v0.107.0"],
    "source": "original_pc_reference_v0.107.0",
    "sts2_dll_sha256": "...",
    "match": "exact-preferred"
  },
  "runtime": {
    "entry_assembly": "STS2Mobile.dll",
    "entry_type": "STS2Mobile.ModEntry",
    "entry_method": "Apply"
  },
  "resources": {
    "overlay_pck": "port_compat.pck"
  },
  "notes": []
}
```

schema 2 family manifest：

```json
{
  "schema": 2,
  "pack_id": "sts2-android-compat",
  "display_name": "STS2 Android Compatibility",
  "compat_version": "0.4.1",
  "channel": "mixed",
  "targets": [
    {
      "target_id": "v0.108.0",
      "versions": ["v0.108.0"],
      "source": "original_pc_reference_v0.108.0",
      "sts2_dll_sha256": "...",
      "artifacts": {
        "dll": "variants/v0.108.0/STS2Mobile.dll",
        "overlay_pck": "variants/v0.108.0/port_compat.pck"
      }
    }
  ]
}
```

`CompatPackManager` 对 schema 1 优先用 `target_game.version` 与 payload manifest 的 `version` 精确匹配；对 schema 2 会把 `targets[]` 展开成可选 variant，并优先按 payload 的 `sts2_dll_sha256` 精确匹配，再回落到 `version` / `versions` 列表。启动配置保存 `compat_pack_id`；schema 2 还保存 `compat_target_id`，因此一个 family 包可以覆盖多个游戏版本，也可以在停止维护旧 target 后把它拆成独立 legacy 包。

## 12. 用户 MOD 测试建议

1. 先确认无普通 MOD 时游戏可启动。
2. 安装 BaseLib/RitsuLib 等基础库 MOD，查看 log 中 BaseLib/RitsuLib compatibility patch 是否正常。
3. 安装目标普通 MOD 到当前 launch profile 的 MOD 目录（全局 `<files>/mods/` 或隔离 `<files>/instances/<profile_id>/mods/`）。
4. 在附加设置中开启 MOD 总开关并确认单 MOD 未禁用。
5. 启动后查看日志：
   - `[Mods] Android mod initialization loaded ...`
   - dependency sort / TryLoadMod 相关日志；
   - 是否出现 assembly resolve 失败。
6. 若某 MOD 只支持特定游戏版本，优先在版本页切到匹配 payload 和 compat pack；需要同一本体多套 MOD/存档时，在版本页为同一个 game body 新建多个隔离 launch profile。
