# MOD 与兼容包加载流程

本文记录 Android 兼容包、`STS2Mobile.dll`、`port_compat.pck`、原版 payload 和普通用户 MOD 的详细加载顺序。当前说明适用于内置的 `v0.103.2` 正式/稳定兼容分支与 `v0.106.1` beta 兼容分支。

## 1. 术语

- **payload**：用户导入的 PC 版游戏 zip 解压结果，安装到 `<files>/payloads/<payload_id>/game/`；切换版本不再复制到固定 active 目录。
- **compat pack**：Android 移动端兼容包，安装到 `<files>/compat-packs/<pack_id>/`，包含：
  - `compat_manifest.json`
  - `STS2Mobile.dll`
  - `port_compat.pck`
  - `SHA256SUMS`
- **compat fallback**：APK assets 中的 `android/assets/dotnet_bcl/STS2Mobile.dll` 与 `android/assets/port_compat.pck`，主要用于兼容旧启动路径或无已选包的兜底。
- **launch profile / 启动配置**：安装在 `<files>/instances/<profile_id>/instance.json`，绑定一个 payload、一个可选 compat pack，并决定存档/设置与 MOD 使用全局目录还是 profile 独立目录。
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
  - 输出到 `android/assets/compat_packs/*.zip`，随 APK 打包。

## 3. 安装 / 首次进入设置页

1. Android 默认启动 `GameSettingsActivity`。桌面图标名称/图标使用主应用资源；“设置 → 系统 → 桌面图标启动后”默认打开附加设置，也可切换为向导完成后自动走 `GameSettingsActivity.launchGame()` 直接启动游戏。设置页快捷方式和游戏内返回设置不触发自动直启。
2. 设置页可在后台调用 `CompatPackManager.installBundledCompatPacks()`：
   - 枚举 APK assets `compat_packs/*.zip`。
   - 复制到私有临时目录。
   - 安全解压、寻找 `compat_manifest.json`。
   - 校验 `STS2Mobile.dll` 与 `port_compat.pck` 存在。
   - 安装到 `<files>/compat-packs/<pack_id>/`。
   - 如果当前未选择兼容包，选择最新安装的包。
3. 用户也可在“版本”页通过 SAF 导入外部 compat pack zip。

## 4. Payload 导入与版本匹配

1. 用户在设置页选择 `SlayTheSpire2.zip`，或直装版从 APK assets `payload/SlayTheSpire2.zip` 解压。
2. `PayloadManager` 复制 zip 到私有临时文件并计算 sha256。
3. 安全解压到 staging，校验：
   - `SlayTheSpire2.pck`
   - `release_info.json`
   - `data_sts2_windows_x86_64/sts2.dll`
   - `sts2.deps.json`
   - `sts2.runtimeconfig.json`
4. `PckPatcher` 只修改私有 PCK copy，禁用 Sentry autoload/gdextension 元数据，避免 Android 缺少桌面 Sentry 扩展导致启动前解析错误。
5. 写入 staging 中的 `.payload_manifest.json`，包含 `release_info`、`version`、`commit`、`sts2_dll_sha256`、PCK patch 结果等。
6. 按 manifest 身份生成 `payload_id`，原子安装到 `<files>/payloads/<payload_id>/game/`；同一 payload 已存在时只替换 payload store 中的该目录，不再复制到 `<files>/game/`。
7. 导入完成后创建或选择一个 launch profile，profile 会绑定该 payload，并按 payload manifest 中的 `version` 自动选择最佳 compat pack。
8. 旧安装中的 `<files>/game/` 与 `<files>/game-versions/<id>/game/` 会在启动器 bootstrap 时尽量通过 rename 迁移到 payload store，避免大文件复制。

## 5. 启动前检查

`GameSettingsActivity.launchGame()`：

1. 检查当前 launch profile 绑定的 payload 是否 ready；没有 payload 时提示导入，直装版可先解压内置 payload。
2. 如果 Android 兼容包开关启用：
   - 调 `CompatPackManager.findBestMatch(payload.manifest)`。
   - 找到匹配包且当前 profile 未绑定匹配包时自动选择。
   - 若无选中包，阻止启动。
   - 若选中包 target version 与 payload version 不一致，弹出风险对话框，用户可取消、去版本页或强制启动。
3. 启动后台线程执行 `GameLaunchPreparationManager.prepareForLaunch()`。
4. 准备完成后启动 `GodotApp` 并附加 `launch_prepared=true`。

## 6. Launch preparation

`GameLaunchPreparationManager.prepareForLaunch()` 顺序：

1. 配置 Android 私有 temp 目录，避免 Harmony/MonoMod 使用不可写 `/tmp`。
2. 规范化 Android locale 到游戏支持的语言 key，避免厂商 locale 字符串污染 `settings.save`。
3. 刷新内置 compat packs（如果开关启用）。
4. 输出当前 selected compat pack 的诊断日志：pack id、target、source zip sha、build branch/commit/dirty、notes。
5. 对旧安装的 payload 补做 PCK patch 记录。
6. payload PCK stamp 变化时清理 Godot texture import cache。
7. Stage overlay：
   - 兼容包开关关闭：删除 `<files>/port_compat.pck`。
   - 有 selected pack：复制 `<files>/compat-packs/<pack_id>/port_compat.pck` 到 `<files>/port_compat.pck`。
   - 无 selected pack：使用 APK assets `port_compat.pck` fallback。
8. 准备 Mono publish 目录 `<files>/.godot/mono/publish/arm64/`：
   - 复制 APK assets `dotnet_bcl/*`，但 `STS2Mobile.dll` 由 selected pack 决定。
   - 兼容包开关关闭：删除 publish 目录中的 `STS2Mobile.dll`。
   - 有 selected pack：复制 selected `STS2Mobile.dll`。
   - 无 selected pack：尝试复制 fallback `dotnet_bcl/STS2Mobile.dll`。
   - 复制当前 profile payload 目录中 `data_*/*` 的游戏 assemblies，跳过 `.so`，并保护 BCL/System/GodotSharp 等 runtime DLL。
   - 使用 SharedPreferences stamp 避免不必要的大文件重复复制；payload/profile 变化时强制刷新游戏 assemblies，并清理 publish 目录里旧 payload 遗留的游戏 DLL/JSON。

`GodotApp` 仍保留 fallback：如果不是从设置页 prepared 启动，会自己调用同一准备流程。

## 7. Godot 启动与 runtime 入口

`GodotApp.getCommandLine()`：

- 添加 renderer/display 参数。
- 配置 `--log-file` 到当前 profile 日志目录 `<files>/instances/<profile_id>/logs/godot.log`，没有 profile 时 fallback 到 `<files>/logs/`。
- 根据附加设置中的 `log_level`（`info` / `debug` / `very_debug`）追加 STS2 原生命令行 `-log <LogType> <LogLevel>`，覆盖 `Generic`、`Network`、`Actions`、`GameSync`、`VisualSync` 的运行日志等级；Debug/Very Debug 会增加日志量并在下次启动生效。
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

1. temp 目录配置与 build info 日志。
2. `RenderDiagnosticPatches`。
3. BaseLib/RitsuLib/ModelDb/UnlockState 兼容。
   - 关键时序不变式：MOD 如 YuWanCard/BaseLib 用 `ModelDb.GetEntry` 的 Harmony postfix 给自定义内容 ID 加命名空间前缀（如 `ENCOUNTER.YUWANCARD-KILLER_ELITE`），并按 type **永久缓存**第一次 `GetEntry` 结果。PC 上每个 MOD 的 `PatchAll` 在 `ModManager.Initialize`（`ExecuteVeryEarly`）期间运行，严格早于 `ModelDb.Init`（`ExecuteEssential`），因此 ID 计算时前缀 patch 早已就位。兼容层必须**在 MOD patch 全部应用前，绝不对任何模型类型调用 `ModelDb.GetId`/`GetEntry`**，否则会污染前缀缓存并把模型注册到错误 key。
   - `ModelDbInitPatch` 把 `ModelDb.Init` 替换为干净的 two-phase，并分两层处理占位：
     - **早期原版占位**：`ModLoaderPatches` 在加载任何 MOD 之前预注册 `AbstractModelSubtypes.All`（仅原版）。Android/Mono 下 MOD initializer Harmony patch 某个 getter（如 HextechRunes patch `UnlockState.Relics`）会提前触发 `UnlockState..cctor`，走 `ModelDb.AllEncounters -> Act<Overgrowth>` 等原版模型；BaseLib post-mod-init act patching 与 MOD 静态构造（如 `wuwancients.HiddenSeaRecord..cctor` 引用多个原版遗物）也会提前触发 `BowlbugsNormal..cctor -> MONSTER.BOWLBUG_EGG`。原版类型不带命名空间前缀，提前算 ID 不会污染任何 MOD 的 `GetEntry` 前缀缓存。
     - phase 1：`ExecuteEssential` 中、调用 `ModelDb.Init()` **之前**，按**最终** `ModelDb.GetId(Type)`（MOD GetEntry 前缀此时已生效）补齐全部模型（含 MOD 自定义类型）的占位；已有的原版占位跳过。这样在 MOD 的 `ModelDb.Init` prefix（在 `Priority.Last` phase 2 之前运行）提前触发 MOD 间静态构造（如 `RELIC.LONG_SNAKE_NECKLACE`）时不会缺失。
     - phase 2：`InitPrefix`（`Priority.Last`）在占位之上原地运行真实静态/实例构造器，跳过原版 one-pass body。部分 MOD 的 `ModelDb.Init` prefix 会自己返回 `false` 并让 Harmony 跳过后续 prefix，因此兼容层同时安装 `Priority.First` postfix 与 `ExecuteEssential` 后置兜底，确保构造 phase 一定执行。
   - 真正的模型构造仍保留在 `OneTimeInitialization.ExecuteEssential -> ModelDb.Init`；用户 MOD 的 `ModelDb.Init` prefix/postfix 仍会执行（prefix `Priority.Last`，跑完后返回 false 跳过原版 one-pass body，postfix 照常运行）；构造前后会清理早期占位枚举产生的 `ModelDb` / 模型实例派生缓存。
   - 自定义模型 ID 完全交给原版 `ModelDb.Init` + MOD 的 `GetEntry` patch 自然产生，不再人为迁移 key 或做动态兜底。
   - `UnlockStateCompatPatches` 会在 `ModelDb` 初始化完成前让 `ModelDb.AllEncounters` 返回空列表，避免 Android/Mono 因 Harmony patch getter 提前运行 `UnlockState..cctor` 时枚举到尚未构造/注册完成的 MOD encounter；初始化完成后会修复可能提前创建的 static readonly `UnlockState.all`，恢复正常“全部 encounter 已见过”的语义。
4. Platform、release info、save path、settings、display、font/UI scale；其中 `AppPaths` 从 Mono publish 目录或 Android 进程包名推导 `<files>` 后读取 `launcher/selected_instance.json`，`SavePathPatches` 将原版 `UserDataPathProvider` 重定向到当前 launch profile 的 account root。
5. 移动端 layout/input、事件/商店/奖励/战斗背景等 UI 修正。
6. Android UI safety、游戏内设置入口、shader overlay、Android back/touch/controller、奖励/商人二次确认、tap preview、hand layout。
7. intent animation、quick restart、lifecycle/performance。
8. LAN bootstrap。
9. `ModLoaderPatches`。
10. save diagnostic。

失败时 `ModEntry` 会记录异常；部分 patch 失败可能导致后续游戏启动不完整，因此 logcat 和 `godot.log` 是首要诊断来源。

## 9. Overlay PCK 加载

`ShaderCompatibilityPatches` 延迟等待 Godot main loop 就绪后加载：

```text
OS.GetDataDir()/port_compat.pck
```

成功后通过 `ProjectSettings.LoadResourcePack()` 挂载资源，并在节点加入树时替换已知桌面 shader 为 `res://shaders/mobile_compat/*`。

是否启用由附加设置中的 `shader_compatibility_mode` 控制。

## 10. 普通用户 MOD 加载

普通 MOD 不由 Android shell 直接注入游戏进程，而是由被 patch 后的原版 `ModManager` 加载。

`ModLoaderPatches` 行为：

- Prefix 替换 `ModManager.Initialize()`，避免 Android 上高风险 IL transpiler。
- 设置原版私有字段 `_settings`、`_fileIo`、`_gameVersion`。
- 添加 assembly resolve fallback。
- 为对齐 PC 时序，在用户 MOD 的 Harmony patch 全部应用前不对任何 MOD 模型类型调用 `ModelDb.GetId`/`GetEntry`。原版模型占位提前到**加载任何 MOD 之前**（`ModLoaderPatches` 触发，原版不带前缀，安全，修复 MOD patch getter / MOD 静态构造引用原版模型的早访问）；MOD 自定义模型占位延迟到 `ModelDb.Init()` 之前的 phase 1，按最终 ID 进行。
- 扫描 `AppPaths.ModsDir`。该路径由当前 launch profile 决定：

```text
# mods_mode=global
<files>/mods

# mods_mode=isolated
<files>/instances/<profile_id>/mods
```

- 跳过 `ReadSteamMods()`，不枚举 Steam Workshop。
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
```

关键日志关键词：

- `Selected compatibility pack for launch`
- `STS2Mobile Android port compatibility`
- `CompatBuildInfo`
- `Loading imported game PCK`
- `Shader compatibility overlay pack load`
- `[Mods] Android mod initialization loaded`
