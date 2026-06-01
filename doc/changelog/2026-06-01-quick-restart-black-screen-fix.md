# 2026-06-01 Android 快速重开黑屏修复

## 背景

用户反馈在某些情况下点击 Android 内置快速重开后会卡黑屏，日志显示：

```text
[STS2Mobile] Built-in Android quick restart failed: System.ArgumentOutOfRangeException
  at MegaCrit.Sts2.Core.Multiplayer.Game.MapSelectionSynchronizer.GetVote(Player player)
  at MegaCrit.Sts2.Core.Nodes.Screens.Map.NMapScreen.InitMapVotes()
  at MegaCrit.Sts2.Core.Nodes.Screens.Map.NMapScreen.SetMap(...)
  at MegaCrit.Sts2.Core.Runs.RunManager.GenerateMap()
  at MegaCrit.Sts2.Core.Nodes.NGame.LoadRun(...)
  at STS2Mobile.Patches.QuickRestartPatches.QuickLoadInternalAsync()
```

原因为快速重开在 `FadeOut()` 后调用原版 `RunManager.SetUpSavedSinglePlayer(runState, saveData)` 时没有 `await`。当运行时预加载被关闭/跳过、设备 IO 较慢或 modded save 写入仍在进行时，`NGame.LoadRun()` 可能抢先执行，地图初始化用新 `RunState` 的 `Player` 查询旧/未完成初始化的 `MapSelectionSynchronizer`，导致 `_votes[-1]` 越界。异常发生在淡出之后，后续 `FadeIn()` 未执行，因此用户看到永久黑屏。

## 改动

- `port-mod/STS2AndroidPortCompat/Patches/QuickRestartPatches.cs`
  - 快速重开开始前等待 `SaveManager.Instance.CurrentRunSaveTask`（如存在），避免读取正在写入的 autosave。
  - 清理旧 run 后通过跨版本 helper 调用 `RunManager.SetUpSavedSinglePlayer(runState, saveData)`：在 `v0.106.1` 这种返回 `Task` 的版本会等待任务完成，在 `v0.103.2` 这种同步 `void` 版本则直接继续；从而确保 `NetService`、`ActionQueueSynchronizer`、`MapSelectionSynchronizer` 等新 run 同步器完整初始化后再进入 `NGame.LoadRun()`。
  - 记录 `FadeOut()` 是否已经完成；若后续任一步异常，先尝试 `Transition.FadeIn()` 解除黑屏遮罩，再显示错误弹窗。
- `tools/android/stage-bundled-compat-packs.sh`
  - 跨分支 worktree 注入列表新增 `QuickRestartPatches.cs`，确保内置 `v0.103.2` 与 `v0.106.1-beta` 兼容包都使用同一快速重开修复。
- 同步更新 `AGENTS.md`、`doc/build/building-and-packaging.md`、`doc/runtime/compat-pack-loading-flow.md` 与 compat README 中的快速重开说明。

## 验证

- `../s2/.local/dotnet/dotnet build port-mod/STS2AndroidPortCompat/STS2Mobile.csproj -p:ReferenceFlavor=original-v0.106.1 -v:q`
  - 成功，0 warnings / 0 errors。
- `tools/package/build_importer_apk.sh`
  - 成功；过程中 `stage-bundled-compat-packs.sh` 同时构建了 `v0.103.2` 与 `v0.106.1-beta` 内置兼容包，两个 compat build 均为 0 warnings / 0 errors。
  - 输出 APK：`dist/sts2-re-importer.apk`。
  - 仍有既有 FMOD shim Java deprecation、Gradle Groovy DSL deprecation 与 Android SDK cmdline-tools 路径提示；未新增构建错误。

## 注意事项

- 该修复不改变快速重开的入口开关：仍由 companion `quick_sl_enabled` 控制，且检测到外部 `quickRestart2` MOD 已提供 UI 时不会重复添加按钮。
- 如果 autosave 本身损坏或原版 `LoadRun()` 后续仍失败，快速重开仍会失败，但不应再因为淡出后异常而永久卡黑屏。
