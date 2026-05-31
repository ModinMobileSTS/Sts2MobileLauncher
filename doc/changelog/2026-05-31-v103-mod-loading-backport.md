# 2026-05-31 v0.103.2 MOD 加载时序修复回移

## 背景

`compat/v0.106.1-beta` 分支已经修复 Android/Mono 下普通 MOD 加载期间的 `ModelDb` / `UnlockState` 提前初始化问题。为避免正式/稳定 `v0.103.2` payload 使用旧兼容包时仍出现同类 MOD 启动崩溃，需要把该套 MOD 加载时序修复同步回 `compat/v0.103.2`。

## 改动

- 在 `port-mod` 的 `compat/v0.103.2` 分支新增本地提交 `15b3cec Backport Android mod loading fixes to v0.103.2`。
- 回移 `v0.106.1` 分支的 MOD 初始化关键逻辑：
  - `ModLoaderPatches` 在加载任何用户 MOD 前仅预注册原版模型占位，避免 Harmony patch getter 时提前触发原版模型访问失败；
  - `ModelDbInitPatch` 改为 phase 1/phase 2 幂等流程，MOD 自定义模型占位延迟到所有 MOD patch 就位后、`ModelDb.Init()` 调用前按最终 ID 注册，并加入 postfix/`ExecuteEssential` 兜底；
  - 新增 `UnlockStateCompatPatches`，在 `ModelDb` 初始化完成前保护 `AllEncounters`/`Acts` 访问，并在初始化完成后修复提前创建的 `UnlockState.all`；
  - 新增 `RitsuLibCompatPatches`，跳过移动端不需要的 `RunState.CreateForTest` 可选 patch 目标，避免过早触发 `UnlockState`；
  - 同步 BaseLib extended save 移动端 workaround 与 build metadata 输出。
- 修复 `LifecycleAndPerformancePatches` 中 Godot `Callable.From(async () => ...)` 返回 `Task` 的 Variant 转换风险。
- 刷新文档说明：`AGENTS.md`、`doc/runtime/compat-pack-loading-flow.md` 与 `doc/modding/mod-and-compat-notes.md` 明确 `v0.103.2` 与 `v0.106.1` 共用同一套 MOD 初始化不变式。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original -v:minimal`：通过，0 warnings / 0 errors。
- `tools/package/build_importer_apk.sh`：通过，已刷新内置 compat pack 并生成 `dist/sts2-re-importer.apk`。
  - `android/assets/compat_packs/sts2-android-compat-v0.103.2.zip`：`340fdec4fd2f261556ff42ab52a4106a607dba381e50789656dfe4c740ac2e9f`
  - `android/assets/compat_packs/sts2-android-compat-v0.106.1-beta.zip`：`bc85c2afddfbeabd7238927ba0650f769c90a1007662c8b6aceace9c51ef6e6c`
  - `android/build/outputs/apk/mono/release/sts2-re.apk`：`5a5a51ccea164f7a83477e50bb9d864a92dd450c462c1ed9ccaa9b15d9c7f4cb`

## 注意事项

- `compat/v0.103.2` 本地分支已领先 `origin/compat/v0.103.2` 1 个提交；发布时需要先推送 submodule 分支，再刷新/提交父仓库内置兼容包 zip。
- 该修复不改变普通 MOD 根目录，仍为 `<files>/mods/`；也不引入按游戏版本隔离 MOD 的行为。
