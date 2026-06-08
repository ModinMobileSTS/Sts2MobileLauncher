# MOD 与兼容包加载流程

本文记录 Android 兼容包、`STS2Mobile.dll`、`port_compat.pck`、原版 payload 和普通用户 MOD 的详细加载顺序。当前说明适用于内置的 `v0.103.x` 正式/稳定兼容分支、旧 `v0.106.1` beta 兼容分支与当前 `v0.107.0` beta 兼容分支。

## 1. 术语

- **payload**：用户导入的 PC 版游戏 zip 解压结果，安装到 `<files>/payloads/<payload_id>/game/`；切换版本不再复制到固定 active 目录。
- **compat pack**：Android 移动端兼容包，安装到 `<files>/compat-packs/<pack_id>/`，包含：
  - `compat_manifest.json`
  - `STS2Mobile.dll`
  - `port_compat.pck`
  - `SHA256SUMS`
- **compat fallback**：APK assets 中的 `android/assets/dotnet_bcl/STS2Mobile.dll` 与 `android/assets/port_compat.pck`，主要用于兼容旧启动路径或无已选包的兜底；正常 launcher 启动会先检查当前启动配置的 compat pack，缺包时不静默 fallback。
- **launch profile / 启动配置**：安装在 `<files>/instances/<profile_id>/instance.json`，绑定一个 payload、一个可选 compat pack，并决定存档/设置与 MOD 使用全局目录还是 profile 独立目录。兼容包选择属于启动配置，不再有运行时全局选中包 fallback。
- **普通用户 MOD**：默认放在全局 `<files>/mods/`；当当前 launch profile 的 `mods_mode=isolated` 时放在 `<files>/instances/<profile_id>/mods/`。由游戏原版 `ModManager` 在被兼容层 patch 后扫描加载。

Compat pack 不是普通用户 MOD。它必须早于原版 `ModManager.Initialize()` 加载，否则无法 patch Steam/Sentry/platform、路径、MOD 扫描、输入、shader 等 Android 必需行为。

## 2. 构建期流程

```text
port-mod branch
  -> dotnet build STS2Mobile.csproj
  -> STS2Mobile.dll
  -> make-port-overlay-pck.py
  -> port_compat.pck
  -> compat_manifest.json
  -> zip: sts2-android-compat-*.zip
```

相关脚本：

- `tools/android/build-port-mod.sh`
  - 构建当前 submodule checkout。
  - 输出 fallback：`android/assets/dotnet_bcl/STS2Mobile.dll`、`android/assets/port_compat.pck`。
- `port-mod/tools/build-compat-pack.sh`
  - 构建当前 compat 分支的独立 zip。
  - 写入 build metadata：branch、commit、dirty、timestamp。
- `tools/android/stage-bundled-compat-packs.sh`
  - 按 `tools/android/bundled-compat-packs.json` 构建多个分支。
  - 非当前分支使用临时 git worktree。
  - 输出到 gitignored 的 `android/assets/compat_packs/*.zip`，随本地 APK 打包但不由 git 跟踪。

## 3. 安装 / 首次进入设置页

1. Android 默认启动 `GameSettingsActivity`。设置页内部使用“画面 / 操作 / 存档 / 系统”顶部 Segmented Button 分区，较长的单选设置（如渲染器、分辨率、日志等级、桌面图标启动后）通过 Bottom Sheet 单选列表修改。桌面图标名称/图标使用主应用资源；“设置 → 系统 → 桌面图标启动后”默认打开附加设置，也可切换为向导完成后自动走 `GameSettingsActivity.launchGame()` 直接启动游戏。设置页快捷方式和游戏内返回设置不触发自动直启。
2. 设置页可在后台调用 `CompatPackManager.installBundledCompatPacks()`：
   - 枚举 APK assets `compat_packs/*.zip`。
   - 复制到私有临时目录。
   - 安全解压、寻找 `compat_manifest.json`。
   - 校验 `STS2Mobile.dll` 与 `port_compat.pck` 存在。
   - 安装到 `<files>/compat-packs/<pack_id>/`。
   - 不会自动把新安装的包设为全局选中包；用户需要在创建或编辑启动配置时选择。
3. 用户也可在“版本”页通过 SAF 导入外部 compat pack zip；导入后同样只进入已安装包列表。

## 4. Payload 导入与版本匹配

1. 用户在设置页选择 `SlayTheSpire2.zip`，直装版从 APK assets `payload/SlayTheSpire2.zip` 解压，或在 Steam 中心使用自己拥有 STS2 的 Steam 账号通过 SteamPipe 下载。
2. zip 路径由 `PayloadManager` 复制 zip 到私有临时文件并计算 sha256；Steam 路径由 `Sts2SteamPayloadDownloader` 下载 depot 文件到 `<files>/steam/downloads/staging-*` 后调用 `PayloadManager.importPayloadDirectory(...)`。
3. staging 目录统一校验；若用户 zip 顶层只有一个目录且必需文件都在该目录内，导入器会先把该顶层目录展平：
   - `SlayTheSpire2.pck`
   - `release_info.json`
   - `data_sts2_windows_x86_64/sts2.dll`
   - `sts2.deps.json`
   - `sts2.runtimeconfig.json`
4. `PckPatcher` 只修改私有 PCK copy，禁用 Sentry autoload/gdextension 元数据，避免 Android 缺少桌面 Sentry 扩展导致启动前解析错误。
5. 写入 staging 中的 `.payload_manifest.json`，包含 `release_info`、`version`、`commit`、`sts2_dll_sha256`、PCK patch 结果等。
6. 按 manifest 身份生成 `payload_id`，原子安装到 `<files>/payloads/<payload_id>/game/`；同一 payload 已存在时只替换 payload store 中的该目录，不再复制到 `<files>/game/`。
7. 导入/Steam 下载完成后创建或选择一个 launch profile，profile 会绑定该 payload；新建 profile 时会按 payload manifest 中的 `version` 填入推荐 compat pack，已有 profile 不会在每次导入/启动时被覆盖。Steam 来源会在 `.payload_manifest.json` 的 `source.kind=steam_depot` 与 `source.steam.depots[]` 中记录。
8. 旧安装中的 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制。

## 5. 启动前检查

`GameSettingsActivity.launchGame()`：

1. 检查当前 launch profile 绑定的 payload 是否 ready；配置存在但本体缺失/被删除时不 fallback 到旧 `<files>/game/`，而是提示重新导入/下载、切换配置或编辑配置；没有 payload 时提示导入，直装版可先解压内置 payload。
2. 如果 Android 兼容包开关启用：
   - 只读取当前 launch profile 的 `compat_pack_id` 并解析已安装包。
   - 若配置未选择兼容包或引用的包已删除，阻止启动并提示编辑启动配置。
   - 若选中包 manifest 支持版本列表与 payload version 不一致，弹出风险对话框，用户可取消、去启动配置页或强制启动。
3. 若 Steam Cloud 模式配置为“启动前拉取”或“完整自动”，且已保存 Steam refresh token，则先由 launcher 侧拉取当前 launch profile account root 的 Steam Cloud 文件；失败时弹窗允许取消、打开 Steam 中心或跳过同步继续启动。
4. 启动后台线程执行 `GameLaunchPreparationManager.prepareForLaunch()`。
5. 准备完成后启动 `GodotApp` 并附加 `launch_prepared=true`。

## 6. Launch preparation

`GameLaunchPreparationManager.prepareForLaunch()` 顺序：

1. 配置 Android 私有 temp 目录，避免 Harmony/MonoMod 使用不可写 `/tmp`。
2. 规范化 Android locale 到游戏支持的语言 key，避免厂商 locale 字符串污染 `settings.save`。
3. 刷新内置 compat packs（如果开关启用）。
4. 输出当前 selected compat pack 的诊断日志：pack id、target、source zip sha、build branch/commit/dirty、notes。
5. 对旧安装的 payload 补做 PCK patch 记录。
6. payload PCK stamp 变化时清理 Godot texture import cache。
7. Stage overlay：
   - 兼容包开关关闭：删除 `<files>/port_compat.pck`；启动前会先弹风险确认，用户选择继续才进入无兼容层准备流程。
   - 有 selected pack：复制 `<files>/compat-packs/<pack_id>/port_compat.pck` 到 `<files>/port_compat.pck`。
   - 无 selected pack：仅在兼容包开关开启但绕过 launcher 检查的 fallback 准备路径中使用 APK assets `port_compat.pck` fallback。
8. 准备 Mono publish 目录 `<files>/.godot/mono/publish/arm64/`：
   - 复制 APK assets `dotnet_bcl/*`，但 `STS2Mobile.dll` 由 selected pack 决定。
   - 兼容包开关关闭：删除 publish 目录中的 `STS2Mobile.dll`；`GodotApp` 的直接启动 fallback 也不会再从 selected pack 或 APK asset 强制补回。
   - 有 selected pack：复制 selected `STS2Mobile.dll`。
   - 无 selected pack：仅在兼容包开关开启但绕过 launcher 检查的 fallback 准备路径中尝试复制 fallback `dotnet_bcl/STS2Mobile.dll`。
   - 复制当前 profile payload 目录中 `data_*/*` 的游戏 assemblies，跳过 `.so`，并保护 BCL/System/GodotSharp 等 runtime DLL。
   - 使用 SharedPreferences stamp 避免不必要的大文件重复复制；payload/profile 变化时强制刷新游戏 assemblies，并清理 publish 目录里旧 payload 遗留的游戏 DLL/JSON。

`GodotApp` 仍保留 fallback：如果不是从设置页 prepared 启动，会自己调用同一准备流程；该 fallback 同样尊重兼容包开关，关闭时不会补回 `STS2Mobile.dll`。游戏通过 Android 兼容层的退出回设置路径触发 `GodotApp.restartToSettingsFromGame()` 时，会写入 `<files>/launcher/expected_clean_game_exit.json`；下次设置页启动时，如 Steam Cloud 模式为完整自动，会尝试上传当前 launch profile account root 的本地变化。

## 7. Godot 启动与 runtime 入口

`GodotApp.getCommandLine()`：

- 添加 renderer/display 参数。
- 默认配置 `--log-file` 到当前 profile 日志目录 `<files>/instances/<profile_id>/logs/godot.log`，没有 profile 时 fallback 到 `<files>/logs/`；若附加设置 `log_level=off`，则不传 `--log-file`，完全禁用新的 `godot.log` 写入。
- `Sts2Application` 会在主进程早期启动应用内 logcat 采集器，统一写入全局 `<files>/logs/sts2.log`；启动准备和 `GodotApp` 进入当前 profile 后也继续使用同一个全局文件，不再写入 `<files>/instances/<profile_id>/logs/sts2.log`。每次启动游戏会像 `godot.log` 一样把旧全局 `sts2.log` 归档为 `sts2YYYY-MM-DDTHH.mm.ss.log` 并只把最新采集写入 `sts2.log`；输出采用紧凑 `level tag message` 格式，例如 `I DOTNET [STS2Mobile] ...`，采集过滤遵循附加设置 `log_level`（`off`→停止采集、`info`→I/W/E、`debug`→D/I/W/E、`very_debug`→V/D/I/W/E）。该文件用于补充 `godot.log` 抓不到的 Java/Godot/Mono stderr/native 顶层日志（例如 `[STS2Mobile]`），但普通 app 只能读取自身 UID/进程可见 logcat，完整设备级日志仍需 ADB。
- 固定追加 STS2 原生命令行 `--force-steam off`，让原版 `NGame.InitializePlatform()` 即使在 Harmony/MonoMod detour 失效的 ROM 上也走内置 Steam 跳过分支，避免继续尝试加载桌面 `steam_api64`。
- 根据附加设置中的 `log_level`（默认 `info`，可选 `off` / `debug` / `very_debug`）追加 STS2 原生命令行 `-log <LogType> <LogLevel>`，覆盖 `Generic`、`Network`、`Actions`、`GameSync`、`VisualSync` 的运行日志等级；`off` 时不追加 STS2 `-log` 参数，Debug/Very Debug 会增加日志量并在下次启动生效。
- 如果当前 profile payload 的 `SlayTheSpire2.pck` 存在，添加：

```text
--main-pack <files>/payloads/<payload_id>/game/SlayTheSpire2.pck
```

- 否则解压并使用 `bootstrap.pck`。

patched Godot/.NET runtime 随后在 Mono publish 目录中寻找并加载：

```text
STS2Mobile.dll
STS2Mobile.ModEntry
```

调用入口：

- `InitializeGodotSharp(...)`：初始化 GodotSharp bridge。
- `Apply()`：创建 Harmony 实例并应用 Android 兼容 patches。

## 8. STS2Mobile patch 顺序

当前 `ModEntry.Apply()` 主要顺序：

1. temp 目录配置、build info 日志与 `HarmonyAndroidCompat` 后端准备。Android 上默认贴近 `../s2` 的 minimal bootstrap：不启用旧 native resolver / `DMDType=cecil` override，但会在真正的 `MonoMod.Utils` / `MonoMod.Core` 程序集上强制 MonoMod 使用 Android/Mono 后端，避免 HarmonyOS 等 ROM 被误判为 Posix/Linux 后在 `HarmonyLib.PatchFunctions.UpdateWrapper()` 中抛 `NotImplementedException`；`monomod_android_libc_shim` 仍由 AndroidSystem 按需提供指令缓存刷新和 `/proc/self/mem` executable-page patch fallback。早期初始化不得调用 Godot C# API（例如 `OS.GetName()` / `ProjectSettings.GlobalizePath()`），避免 Godot `StringName`/JNI 尚未稳定时崩溃；静态/虚方法 Harmony self-test 默认跳过，仅在 `<files>/launcher/enable_harmony_selftest.flag` 存在时运行，旧 bootstrap 仅在 `<files>/launcher/enable_old_harmony_compat_bootstrap.flag` 存在时作为诊断启用。
2. `PlatformPatches` 与 `SavePathPatches` 作为保命 patch 最先独立应用；前者跳过桌面 Steam 初始化，后者重定向当前 launch profile 的存档/设置路径。两组 patch 分别捕获异常，避免后续诊断或 UI patch 在特定 ROM 上失败时导致原版 `steam_api64` 路径重新执行。
3. BaseLib/RitsuLib/ModelDb/UnlockState 兼容。
   - 关键时序不变式：MOD 如 YuWanCard/BaseLib 用 `ModelDb.GetEntry` 的 Harmony postfix 给自定义内容 ID 加命名空间前缀（如 `ENCOUNTER.YUWANCARD-KILLER_ELITE`），并按 type **永久缓存**第一次 `GetEntry` 结果。PC 上每个 MOD 的 `PatchAll` 在 `ModManager.Initialize`（`ExecuteVeryEarly`）期间运行，严格早于 `ModelDb.Init`（`ExecuteEssential`），因此 ID 计算时前缀 patch 早已就位。兼容层必须**在 MOD patch 全部应用前，绝不对任何模型类型调用 `ModelDb.GetId`/`GetEntry`**，否则会污染前缀缓存并把模型注册到错误 key。
   - `ModelDbInitPatch` 把 `ModelDb.Init` 替换为干净的 two-phase，并分两层处理占位：
     - **早期原版占位**：`ModLoaderPatches` 在加载任何 MOD 之前预注册 `AbstractModelSubtypes.All`（仅原版）。Android/Mono 下 MOD initializer Harmony patch 某个 getter（如 HextechRunes patch `UnlockState.Relics`）会提前触发 `UnlockState..cctor`，走 `ModelDb.AllEncounters -> Act<Overgrowth>` 等原版模型；BaseLib post-mod-init act patching 与 MOD 静态构造（如 `wuwancients.HiddenSeaRecord..cctor` 引用多个原版遗物）也会提前触发 `BowlbugsNormal..cctor -> MONSTER.BOWLBUG_EGG`。原版类型不带命名空间前缀，提前算 ID 不会污染任何 MOD 的 `GetEntry` 前缀缓存。兼容层会记录早期占位 id 对应的 owner type。
     - **MOD initializer shield**：每个普通 MOD 的 `TryLoadMod` 调用期间，`ModelDb.Contains(Type)` 会对“非原版程序集类型，但当前 id 只命中早期原版占位”的情况短暂返回 `false`，还原 PC 上 MOD 初始化时 `ModelDb` 尚未被原版模型填充的行为。这样 RitsuLib/Valencina 这类 MOD 在初始化中构造与原版同名的模型（如 `Taunt`）时，不会因为 Android 早期原版占位误判 `DuplicateModelException`。该 shield 不隐藏原版类型、不隐藏同一 type 的真实重复，也不会在 phase 1/phase 2 后生效。
     - phase 1：`ExecuteEssential` 中、调用 `ModelDb.Init()` **之前**，按**最终** `ModelDb.GetId(Type)`（MOD GetEntry 前缀此时已生效）补齐全部模型（含 MOD 自定义类型）的占位；已有的原版占位跳过。这样在 MOD 的 `ModelDb.Init` prefix（在 `Priority.Last` phase 2 之前运行）提前触发 MOD 间静态构造（如 `RELIC.LONG_SNAKE_NECKLACE`）时不会缺失。
     - phase 2：`InitPrefix`（`Priority.Last`）在占位之上原地运行真实静态/实例构造器，跳过原版 one-pass body。部分 MOD 的 `ModelDb.Init` prefix 会自己返回 `false` 并让 Harmony 跳过后续 prefix，因此兼容层同时安装 `Priority.First` postfix 与 `ExecuteEssential` 后置兜底，确保构造 phase 一定执行。
   - 真正的模型构造仍保留在 `OneTimeInitialization.ExecuteEssential -> ModelDb.Init`；用户 MOD 的 `ModelDb.Init` prefix/postfix 仍会执行（prefix `Priority.Last`，跑完后返回 false 跳过原版 one-pass body，postfix 照常运行）；构造前后会清理早期占位枚举产生的 `ModelDb` / 模型实例派生缓存。
   - 自定义模型 ID 完全交给原版 `ModelDb.Init` + MOD 的 `GetEntry` patch 自然产生，不再人为迁移 key 或做动态兜底。
   - `UnlockStateCompatPatches` 会在 `ModelDb` 初始化完成前让 `ModelDb.AllEncounters` 返回空列表，避免 Android/Mono 因 Harmony patch getter 提前运行 `UnlockState..cctor` 时枚举到尚未构造/注册完成的 MOD encounter；初始化完成后会修复可能提前创建的 static readonly `UnlockState.all`，恢复正常“全部 encounter 已见过”的语义。
4. Release info、settings、display、font/UI scale；其中 `AppPaths` 从 Mono publish 目录或 Android 进程包名推导 `<files>` 后读取 `launcher/selected_instance.json`。
5. 移动端 layout/input、事件/商店/奖励/战斗背景等 UI 修正。
6. Android UI safety、游戏内设置入口、shader overlay、transition material 防黑屏、Android back/touch/controller、奖励/商人二次确认、移动端 tooltip 显示策略、tap preview、hand layout。`mobile_tooltip_mode` 默认 `immediate`，保持 PC 端悬停即显示；在附加设置“设置 → 操作 → Tooltip 显示”切到 `long_press` 后，`MobileTooltipPatches` 会在 `NHoverTipSet.CreateAndShow*` 前建立当前 owner 的长按计时，允许原版创建并完成对齐后立即隐藏 tooltip，并通过 tooltip owner 的 `GuiInput` / `MouseExited` 与 `NGame._Input` 共同跟踪触摸，只在同一触点按住约 1 秒且未明显拖动时临时显示，松手或移动过大后再次隐藏（不因单纯 `MouseExited` 取消，以免 hover 动画导致控件在静止手指下移动）。若原版在长按过程中频繁 `Clear()`/重建 hover tips，兼容层会保留当前 owner/计时状态，避免计时被每帧重置；游戏内设置页切换该选项时会刷新 `AndroidSettingsBridge` 缓存并立即移除或显示已有普通 hover tooltip，`hidden` / `long_press` 模式会阻止后续普通 hover tooltip 创建，但不拦截 inspect card/relic/potion 等显式详情页面自己的说明区域。
7. intent animation、quick restart、lifecycle/performance。`QuickRestartPatches` 在 pause menu 提供 Android 内置“重打/Retry”按钮：快速重开会先等待当前 run save 任务，再读取 autosave；淡出后清理旧 run，并执行原版保存恢复入口（`RunManager.SetUpSavedSinglePlayer()`，`v0.107.0` 为 `SetUpSavedSingleplayer()`；返回 `Task` 的版本会等待完成）以完整初始化 `NetService` / `MapSelectionSynchronizer` 等同步器后才调用 `NGame.LoadRun()`，避免资源预加载关闭或 IO 较慢时新 `RunState` 提前进入地图初始化、触发 `MapSelectionSynchronizer.GetVote()` 越界；若淡出后任一步失败，会先尝试 `FadeIn()` 解除黑屏遮罩，再显示错误弹窗。`LifecycleAndPerformancePatches` 在 `NMainMenu._Ready` 后启动安全 deferred preload，并在需要细分或额外 warmup 时接管原版 `LoadCommonAndMainMenuAssets()`：
   - `preload_enabled`：总开关，默认 `true`。
   - `preload_startup_common_enabled`：主菜单后加载 `AssetSets.CommonAssets`，默认 `true`。
   - `preload_startup_main_menu_enabled`：主菜单后加载 `AssetSets.MainMenuSet`，默认 `true`。
   - `preload_runtime_enabled`：保留 run/act/room 资源预加载，默认 `true`。
   - `preload_menu_hotspots_enabled`：额外实例化单人/多人常用子菜单，默认 `false`。
   - `preload_vfx_mode`：`off` / `hot` / `full`，默认 `off`；`hot` 仅实例化高频战斗 VFX，`full` 递归 `res://scenes/vfx/**/*.tscn`。
   - `preload_combat_code_enabled`：额外预热攻击/伤害/VFX 托管方法，默认 `false`。
   - `preload_shader_mode`：`off` / `load_resources`，默认 `off`；仅加载已知 shader 资源，不保证 GPU pipeline 已完成编译。
   Android 附加设置页在顶部“系统”分区的系统卡片中显示 `preload_enabled` 总开关；右侧箭头打开预加载详细管理 BottomSheet，默认不会自动展开。总开关开/关只写入自身，不改写上述细分项目；BottomSheet 的“恢复默认”只重置细分项目，不修改 `preload_enabled`。默认组合保持本次改动前的预加载行为，不额外启用 VFX/菜单/shader/code warmup。
8. LAN bootstrap。`LanMultiplayerBootstrapPatches` 在主菜单就绪后才尝试应用本地 LAN 兼容补丁；若 `settings.save` 中 `lan_multiplayer_enabled=false`，或已加载 `sts2_lan_connect` / STS2 Game Lobby 大厅 MOD，`LanMultiplayerPatches` 会整组跳过，避免其固定消息 ID / LAN host-join 补丁与大厅 MOD 自己的联机协议 profile 冲突。
9. `ModLoaderPatches`。
10. save diagnostic。
11. `RenderDiagnosticPatches` 后置调度；它只用于设备/渲染信息采集，调度异常会记录但不阻断 Steam 跳过和存档路径重定向等核心 patch。

失败时 `ModEntry` 会按 patch group 记录异常；部分 patch 失败可能导致后续游戏启动不完整，因此 `sts2.log`（应用内 logcat 采集）、ADB logcat 和 `godot.log` 是首要诊断来源。

## 9. Overlay PCK 加载

`ShaderCompatibilityPatches` 延迟等待 Godot main loop 就绪后加载：

```text
OS.GetDataDir()/port_compat.pck
```

成功后通过 `ProjectSettings.LoadResourcePack()` 挂载资源，并在节点加入树时替换已知桌面 shader 为 `res://shaders/mobile_compat/*`。

卡面/先古卡遮罩使用的 `res://shaders/blur/canvas_group_mask_blur.gdshader` 不再进入替换表，也不预加载或随 overlay 发布旧的 `mobile_compat/canvas_group_mask_blur_compat.gdshader`。该移动替代版在开启着色器兼容后可能把先古卡面渲染成纯白，因此保留原版 shader。

另外 `TransitionMaterialPatches` 会在 `NTransition._Ready` 后复制场景默认 `ShaderMaterial`，并在原版 `AssetCache.GetMaterial()` 返回 `fade_transition_mat.tres` / `fight_transition_mat.tres` 时返回缓存材质的副本。这样关闭预加载时，原版 `LoadCommonAndMainMenuAssets()` 触发的 missed-cache 清理即使 dispose 了缓存条目，也不会把正在执行主菜单 `FadeIn()` 的 transition 材质一并释放，避免 `ObjectDisposedException: Godot.ShaderMaterial` 后黑屏。

是否启用由附加设置中的 `shader_compatibility_mode` 控制。

## 10. 普通用户 MOD 加载

普通 MOD 不由 Android shell 直接注入游戏进程，而是由被 patch 后的原版 `ModManager` 加载。

`ModLoaderPatches` 行为：

- Prefix 替换 `ModManager.Initialize()`，避免 Android 上高风险 IL transpiler；`v0.107.0` 起原方法返回 `Task`，跳过原方法时兼容层会返回 `Task.CompletedTask`，避免 `ExecuteVeryEarly()` `await` 到 `null`。
- 设置原版私有字段 `_settings`、`_fileIo`、`_gameVersion`。
- 添加 assembly resolve fallback。
- 为对齐 PC 时序，在用户 MOD 的 Harmony patch 全部应用前不对任何 MOD 模型类型调用 `ModelDb.GetId`/`GetEntry`。原版模型占位提前到**加载任何 MOD 之前**（`ModLoaderPatches` 触发，原版不带前缀，安全，修复 MOD patch getter / MOD 静态构造引用原版模型的早访问）；每个 MOD initializer 期间只隐藏非原版类型命中早期原版占位的 `ModelDb.Contains(Type)` 结果，避免同名模型误判；MOD 自定义模型占位延迟到 `ModelDb.Init()` 之前的 phase 1，按最终 ID 进行。
- 扫描 `AppPaths.ModsDir`。该路径由当前 launch profile 决定：

```text
# mods_mode=global
<files>/mods

# mods_mode=isolated
<files>/instances/<profile_id>/mods
```

- 跳过 `ReadSteamMods()`，不枚举 Steam Workshop。Steam 登录、游戏 depot 下载和 Steam Cloud 存档同步均在 Android launcher 侧完成，不恢复桌面 Steamworks 到游戏进程内。
- 递归读取本地 MOD manifest，调用游戏原本的私有 scanner、dependency sort、TryLoadMod。
- 对 `mod_manifest.json` 自动生成 `<ModId>.json` alias，以兼容当前 PC scanner 期望。
- 将 companion settings 中的启用/禁用状态投影回运行时 `ModSettings`。

## 11. 关闭兼容包开关时

如果用户在附加设置中关闭 Android compat pack：

- 启动前不会强制要求 selected compat pack。
- publish 目录中的 `STS2Mobile.dll` 会被删除。
- `<files>/port_compat.pck` 会被删除。
- 游戏可能以更接近原版 PC 行为启动，但 Android 必需 patch 缺失，崩溃/黑屏/输入异常风险很高。

此模式主要用于诊断，不应作为普通推荐路径。

## 12. 诊断入口

常用日志/检查：

```bash
adb logcat | grep -E 'Sts2|STS2Mobile|GODOT'
adb shell run-as com.megacrit.sts2re ls files/compat-packs
adb shell run-as com.megacrit.sts2re cat files/launcher/selected_compat_pack.json
adb shell run-as com.megacrit.sts2re ls files/.godot/mono/publish/arm64
adb shell run-as com.megacrit.sts2re cat files/logs/android-launch.log
adb shell run-as com.megacrit.sts2re cat files/logs/sts2.log
```

关键日志关键词：

- `Selected compatibility pack for launch`
- `Prepared compat entry dll`
- `Prepared compat overlay`
- `STS2Mobile Android port compatibility`
- `Critical platform patches applied`
- `Critical save path patches applied`
- `CompatBuildInfo`
- `Loading imported game PCK`
- `Shader compatibility overlay pack load`
- `[Mods] Android mod initialization loaded`
